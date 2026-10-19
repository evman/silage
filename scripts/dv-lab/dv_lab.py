#!/usr/bin/env python3
"""Experimental native-DV investigation. Snapshot is read-only; profile5 opens mpv.

Rendering checks never establish native Dolby Vision output. Reports deliberately
keep the native milestone unverified until independent transport/panel validation.
"""
import argparse
import ctypes as C
import ctypes.util
import datetime
import hashlib
import json
import os
from pathlib import Path
import re
import shlex
import struct
import subprocess
import sys
import time

ROOT = Path(__file__).resolve().parents[2]
SAMPLE_SHA256 = "dacfd04518accd6367530b650dfeea429227df2be171bd99b4bdad36d31bbf9f"


def dolby_blocks(edid):
    """Validate all EDID blocks, return just Dolby VSVDBs (no monitor serials)."""
    if len(edid) < 128 or edid[:8] != bytes.fromhex("00ffffffffffff00"):
        raise ValueError("Invalid EDID header")
    if len(edid) != (edid[126] + 1) * 128:
        raise ValueError("EDID extension count/length mismatch")
    if any(sum(edid[i:i + 128]) % 256 for i in range(0, len(edid), 128)):
        raise ValueError("EDID checksum mismatch")
    found = []
    for start in range(128, len(edid), 128):
        block = edid[start:start + 128]
        if block[0] != 2 or block[1] < 3 or block[2] == 0:
            continue
        end = block[2]
        if not 4 <= end <= 127:
            raise ValueError("Invalid CTA data block boundary")
        pos = 4
        while pos < end:
            size = block[pos] & 31
            if pos + 1 + size > end:
                raise ValueError("Truncated CTA data block")
            data = block[pos + 1:pos + 1 + size]
            if block[pos] >> 5 == 7 and data[:4] == bytes.fromhex("0146d000"):
                if len(data) < 5:
                    raise ValueError("Truncated Dolby capability block")
                found.append({"version": data[4] >> 5, "hex": data.hex()})
            pos += size + 1
    return found


def hdr_metadata(raw_hex):
    """Decode Linux hdr_output_metadata ABI, excluding its trailing padding."""
    if raw_hex is None:
        return None
    raw = bytes.fromhex(raw_hex)
    if len(raw) != 32:
        raise ValueError(f"Unsupported HDR metadata ABI size: {len(raw)}")
    kind, eotf, descriptor, *values = struct.unpack_from("<IBB12H", raw)
    if kind != 0 or descriptor != 0:
        raise ValueError("Unknown HDR metadata descriptor")
    return {
        "eotf": eotf,
        "eotf_name": {0: "SDR", 1: "traditional HDR", 2: "PQ", 3: "HLG"}.get(eotf, "reserved"),
        "display_primaries": [values[i:i + 2] for i in (0, 2, 4)],
        "white_point": values[6:8],
        "max_mastering_luminance": values[8],
        "min_mastering_luminance": values[9],
        "max_cll": values[10], "max_fall": values[11],
        "cta_payload_hex": raw[4:30].hex(),
        # intel_dp_hdr_metadata_infoframe_sdp_pack: HB0..HB3, version,
        # length, 26-byte CTA payload. This is a prediction, not wire capture.
        "predicted_i915_dp_hdr_sdp_hex": (bytes.fromhex("00871d4c011a") + raw[4:30]).hex(),
        "provenance": "KMS connector property, not a captured DisplayPort packet",
    }


def register_words(output, addresses):
    pairs = re.findall(r"\(0x([0-9a-fA-F]+)\):\s*0x([0-9a-fA-F]+)", output)
    words = {int(address, 16): int(value, 16) for address, value in pairs}
    if set(words) != set(addresses) or len(pairs) != len(addresses):
        raise ValueError("Incomplete or unexpected Intel register readback")
    return [words[address] for address in addresses]


def active_pipe(state, connector_id, connector):
    sections = re.findall(r"(?ms)^\[CRTC:\d+:pipe ([A-D])\]:\n(.*?)(?=^\S|\Z)", state)
    pipes = [pipe for pipe, body in sections
             if f"[CONNECTOR:{connector_id}:{connector}]" in body]
    if len(pipes) != 1:
        raise ValueError("Cannot resolve a single active connector pipe")
    return pipes[0]


def intel_readback(card, kms):
    """Read the validated A380's programmed GMP SDP; no register writes."""
    gpu = (Path("/sys/class/drm") / Path(card).name / "device").resolve(strict=True)
    if ((gpu / "vendor").read_text().strip(), (gpu / "device").read_text().strip()) != ("0x8086", "0x56a5"):
        raise ValueError("Register readback is currently validated only for Intel Arc A380 (8086:56a5)")
    if kms["driver"] != "i915" or not kms["connector"].startswith("DP-"):
        raise ValueError("Register readback requires i915 DisplayPort")
    debug = Path("/sys/kernel/debug/dri") / Path(card).name.removeprefix("card") / "i915_display_info"
    state = subprocess.check_output(["sudo", "-n", "cat", str(debug)], text=True)
    pipe = active_pipe(state, kms["connector_id"], kms["connector"])
    offset = (ord(pipe) - ord("A")) * 0x1000
    control = 0x60200 + offset
    data = [0x602e0 + offset + 4 * i for i in range(9)]
    addresses = [control, *data]
    output = subprocess.check_output(["sudo", "-n", "intel_reg", f"--pci-slot={gpu.name}",
                                      "read", *map(hex, addresses)], text=True)
    words = register_words(output, addresses)
    packet = b"".join(struct.pack("<I", value) for value in words[1:])
    hdr = kms["hdr_metadata"]
    return {
        "provenance": "Intel programmed packet registers, not an external link analyzer capture",
        "pipe": pipe, "gmp_enabled": bool(words[0] & (1 << 4)),
        "register_base": hex(data[0]), "packet_storage_hex": packet.hex(),
        "matches_requested_hdr_sdp": bool(hdr) and
            packet[:32].hex() == hdr["predicted_i915_dp_hdr_sdp_hex"],
    }


def snapshot(card, connector, readback=False):
    device = Path(card)
    node = Path("/sys/class/drm") / f"{device.name}-{connector}"
    if not node.exists():
        raise ValueError(f"Unknown connector: {node}")
    binary = ROOT / "build/dv-lab/kms-snapshot"
    source = Path(__file__).with_name("kms_snapshot.c")
    if not binary.exists() or source.stat().st_mtime > binary.stat().st_mtime:
        flags = shlex.split(subprocess.check_output(
            ["pkg-config", "--cflags", "--libs", "libdrm"], text=True))
        binary.parent.mkdir(parents=True, exist_ok=True)
        subprocess.run(["cc", "-std=c11", "-D_GNU_SOURCE", "-Wall", "-Wextra", "-Werror",
                        str(source), "-o", str(binary), *flags], check=True)
    kms = json.loads(subprocess.check_output([str(binary), card, connector], text=True))
    if not kms["connected"]:
        raise ValueError(f"{connector} is disconnected")
    edid = (node / "edid").read_bytes()
    kms["dolby_capability_blocks"] = dolby_blocks(edid)
    kms["hdr_metadata"] = hdr_metadata(kms["hdr_output_metadata_hex"])
    kms["driver"] = (node / "device/device/driver").resolve(strict=True).name
    kms["kernel"] = os.uname().release
    kms["card"] = card
    kms["timestamp_utc"] = datetime.datetime.now(datetime.timezone.utc).isoformat()
    if readback:
        kms["intel_readback"] = intel_readback(card, kms)
    return kms


class Mpv:
    def __init__(self, connector):
        self.lib = C.CDLL(ctypes.util.find_library("mpv") or "libmpv.so.2")
        self.lib.mpv_create.restype = C.c_void_p
        self.lib.mpv_set_option_string.argtypes = [C.c_void_p, C.c_char_p, C.c_char_p]
        self.lib.mpv_set_option_string.restype = C.c_int
        self.lib.mpv_initialize.argtypes = [C.c_void_p]
        self.lib.mpv_initialize.restype = C.c_int
        self.lib.mpv_command.argtypes = [C.c_void_p, C.POINTER(C.c_char_p)]
        self.lib.mpv_command.restype = C.c_int
        self.lib.mpv_get_property_string.argtypes = [C.c_void_p, C.c_char_p]
        self.lib.mpv_get_property_string.restype = C.c_void_p
        self.lib.mpv_free.argtypes = [C.c_void_p]
        self.lib.mpv_terminate_destroy.argtypes = [C.c_void_p]
        self.ctx = self.lib.mpv_create()
        if not self.ctx:
            raise RuntimeError("mpv_create failed")
        try:
            options = {
                "config": "no", "load-scripts": "no", "osc": "no", "terminal": "no",
                "profile": "high-quality", "vo": "gpu-next", "gpu-api": "vulkan",
                "gpu-context": "waylandvk", "hwdec": "vaapi,auto-safe",
                "target-colorspace-hint": "yes", "scale": "ewa_lanczossharp",
                "cscale": "ewa_lanczossharp", "deband": "yes", "fullscreen": "yes",
                "fs-screen-name": connector, "mute": "yes", "ao": "null",
                "title": "Silage DV lab: Profile 5 rendering control",
            }
            for name, value in options.items():
                self.check(self.lib.mpv_set_option_string(self.ctx, name.encode(), value.encode()), name)
            self.check(self.lib.mpv_initialize(self.ctx), "initialize")
        except BaseException:
            self.close()
            raise

    @staticmethod
    def check(code, operation):
        if code < 0:
            raise RuntimeError(f"libmpv {operation} failed ({code})")

    def command(self, *args):
        encoded = [a.encode() for a in args]
        self.check(self.lib.mpv_command(self.ctx, (C.c_char_p * (len(args) + 1))(*encoded, None)), args)

    def prop(self, name):
        ptr = self.lib.mpv_get_property_string(self.ctx, name.encode())
        if not ptr:
            return None
        try:
            return C.string_at(ptr).decode()
        finally:
            self.lib.mpv_free(ptr)

    def measure(self):
        names = {
            "source_profile": "current-tracks/video/dolby-vision-profile",
            "output_transfer": "video-target-params/gamma",
            "output_primaries": "video-target-params/primaries",
            "hardware_decoder": "hwdec-current", "display_names": "display-names",
            "dropped_frames": "frame-drop-count", "mpv_version": "mpv-version",
        }
        result = {key: self.prop(value) for key, value in names.items()}
        passes = json.loads(self.prop("vo-passes") or "{}")
        result["passes"] = [p.get("desc", "") for group in passes.values() for p in group]
        return result

    def close(self):
        if self.ctx:
            self.lib.mpv_terminate_destroy(self.ctx)
            self.ctx = None


def rendering_checks(enabled, disabled, connector):
    return {
        "profile5": enabled["source_profile"] == disabled["source_profile"] == "5",
        "correct_monitor": all(connector in (state["display_names"] or "").split(",")
                               for state in (enabled, disabled)),
        "pq_output": enabled["output_transfer"] == disabled["output_transfer"] == "pq",
        "metadata_reshaping_enabled": any("reshaping" in p for p in enabled["passes"]),
        "metadata_reshaping_disabled": bool(disabled["passes"]) and
                                       not any("reshaping" in p for p in disabled["passes"]),
    }


def profile5(args):
    if not os.environ.get("WAYLAND_DISPLAY"):
        raise ValueError("Set WAYLAND_DISPLAY to the active Wayland socket")
    video = Path(args.video).resolve(strict=True)
    with video.open("rb") as stream:
        digest = hashlib.file_digest(stream, "sha256").hexdigest()
    if digest != SAMPLE_SHA256:
        raise ValueError("This control test requires the pinned official Dolby Profile 5 sample; see README")
    report = {"test": "Profile 5 metadata A/B rendering control", "sample_sha256": digest,
              "native_dolby_vision_monitor_signaling_verified": False,
              "before": snapshot(args.card, args.connector, args.intel_readback)}
    player = Mpv(args.connector)
    try:
        player.command("loadfile", str(video))
        deadline = time.monotonic() + 20
        while not player.prop("vo-passes") or not player.prop("video-target-params/gamma"):
            if time.monotonic() > deadline:
                raise RuntimeError("Timed out waiting for video output")
            time.sleep(.1)
        time.sleep(2)
        enabled = player.measure()
        report["metadata_enabled"] = {"renderer": enabled, "kms": snapshot(args.card, args.connector, args.intel_readback)}
        # Intentionally invalid colors are shown briefly as a negative rendering control.
        player.command("vf", "add", "format=dolbyvision=no")
        time.sleep(2)
        disabled = player.measure()
        report["metadata_disabled"] = {"renderer": disabled, "kms": snapshot(args.card, args.connector, args.intel_readback)}
        report["rendering_checks"] = rendering_checks(enabled, disabled, args.connector)
    finally:
        player.close()
    time.sleep(.5)
    report["after"] = snapshot(args.card, args.connector, args.intel_readback)
    return report


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("action", choices=("snapshot", "profile5"))
    parser.add_argument("--card", required=True)
    parser.add_argument("--connector", required=True)
    parser.add_argument("--video", help="Pinned official Dolby Profile 5 MP4 (profile5 action)")
    parser.add_argument("--intel-readback", action="store_true",
                        help="Read A380 packet registers with sudo -n and intel_reg (no writes)")
    parser.add_argument("--output", type=Path, required=True)
    args = parser.parse_args()
    if args.action == "profile5" and not args.video:
        parser.error("profile5 requires --video")
    report = snapshot(args.card, args.connector, args.intel_readback) if args.action == "snapshot" else profile5(args)
    args.output.parent.mkdir(parents=True, exist_ok=True)
    args.output.write_text(json.dumps(report, indent=2) + "\n")
    print(f"Saved {args.output}")
    if args.action == "profile5":
        print(json.dumps(report["rendering_checks"], indent=2))
        print("Native Dolby Vision output: UNVERIFIED")
        return 0 if all(report["rendering_checks"].values()) else 1
    return 0


if __name__ == "__main__":
    try:
        sys.exit(main())
    except (ValueError, RuntimeError, OSError, subprocess.CalledProcessError) as error:
        print(f"DV lab: {error}", file=sys.stderr)
        sys.exit(1)
