#!/usr/bin/env python3
"""Bounded A380 DisplayPort metadata experiment, with independent restoration.

This replaces only the programmed static HDR SDP for a few seconds. It is a
signaling experiment using NVAPI-reported metadata, not verified Dolby transport
or a complete DV pixel/content-mapping implementation. Run --plan first.
"""
import argparse
import datetime
import json
import os
from pathlib import Path
import signal
import struct
import subprocess
import sys
import time

from dv_lab import ROOT, register_words, snapshot

PCI = "0000:11:00.0"


def candidate_packet(reference):
    displays = [d for d in reference["displays"] if d.get("physically_connected") and d.get("active")]
    if len(displays) != 1:
        raise ValueError("Reference must identify one active physical NVIDIA display")
    display = displays[0]
    if display.get("hdr_get_status") != 0 or not display.get("dolby_vision_capable"):
        raise ValueError("Reference must have successful HDR GET and Dolby capability")
    raw = bytes.fromhex(display["hdr_color_data_v2_hex"])
    if len(raw) != 52 or struct.unpack_from("<IIII", raw) != (0x20034, 0, 2, 0):
        raise ValueError("Reference must be the known NVAPI V2 HDR GET layout")
    if struct.unpack_from("<III", raw, 40) != (0, 0, 3):
        raise ValueError("Reference must report RGB/full-range/10-bpc enum values")
    values = struct.unpack_from("<12H", raw, 16)
    if list(values) != display["mastering_primaries_white_luminance_cll_fall"]:
        raise ValueError("Reference metadata fields disagree with raw API bytes")
    # Standard i915 HDR SDP wrapper, PQ/descriptor zero, API mastering values.
    # Whether this activates DV is the experiment, not an assumption.
    return bytes.fromhex("00871d4c011a0200") + struct.pack("<12H", *values) + bytes(4)


def read_words(addresses):
    output = subprocess.check_output(["intel_reg", f"--pci-slot={PCI}", "read", *map(hex, addresses)], text=True)
    return register_words(output, addresses)


def addresses(base):
    if base not in (0x602e0, 0x612e0, 0x622e0, 0x632e0):
        raise ValueError("Unexpected A380 GMP register address")
    return base - 0xe0, [base + i * 4 for i in range(9)]


def write_packet(base, packet):
    if len(packet) != 36 or packet[:6] != bytes.fromhex("00871d4c011a"):
        raise ValueError("Unexpected HDR SDP layout")
    control, data = addresses(base)
    current = read_words([control])[0]
    pairs = [(control, current & ~16), *zip(data, struct.unpack("<9I", packet)), (control, current | 16)]
    args = ["intel_reg", f"--pci-slot={PCI}", "write", "--post"]
    for address, value in pairs:
        args.extend((hex(address), hex(value)))
    subprocess.run(args, check=True, stdout=subprocess.DEVNULL)


def restore(state):
    write_packet(state["base"], bytes.fromhex(state["original_packet"]))
    control, data = addresses(state["base"])
    words = read_words([control, *data])
    return bool(words[0] & 16) and struct.pack("<9I", *words[1:]).hex() == state["original_packet"]


def watchdog(path):
    state = json.loads(path.read_text())
    cancel = path.with_suffix(".cancel")
    deadline = time.monotonic() + state["watchdog_seconds"]
    while time.monotonic() < deadline:
        if cancel.exists():
            return
        time.sleep(.2)
    result = {"watchdog_restored": restore(state)}
    path.with_suffix(".watchdog-result.json").write_text(json.dumps(result) + "\n")


def interrupted(signum, frame):
    raise InterruptedError(f"Signal {signum}; restoring display metadata")


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--reference", type=Path)
    parser.add_argument("--output", type=Path)
    parser.add_argument("--seconds", type=int, default=10, choices=range(5, 16))
    parser.add_argument("--apply", action="store_true", help="Perform the bounded register experiment")
    parser.add_argument("--plan", action="store_true", help="Validate inputs and print the experiment without writing registers")
    parser.add_argument("--watchdog", type=Path, help=argparse.SUPPRESS)
    args = parser.parse_args()
    if args.watchdog:
        watchdog(args.watchdog)
        return
    if not args.reference or not args.output or args.apply == args.plan:
        parser.error("Provide --reference, --output and exactly one of --plan or --apply")
    if args.apply and os.geteuid() != 0:
        raise ValueError("--apply requires root for the register experiment")
    packet = candidate_packet(json.loads(args.reference.read_text(encoding="utf-8-sig")))
    baseline = snapshot("/dev/dri/card1", "DP-3", readback=True)
    readback = baseline["intel_readback"]
    if not baseline["hdr_metadata"] or baseline["hdr_metadata"]["eotf"] != 2:
        raise ValueError("The Linux output must already be in PQ HDR")
    if baseline["dolby_capability_blocks"] != [{"version": 2, "hex": "0146d0004c025d89606f87"}]:
        raise ValueError("This experiment is restricted to the validated ASUS capability block")
    if not readback["gmp_enabled"] or not readback["matches_requested_hdr_sdp"]:
        raise ValueError("Programmed SDP does not match the KMS baseline; no writes allowed")
    if baseline["properties"]["Colorspace"]["value"] != 9:
        raise ValueError("Expected the existing BT.2020 RGB desktop output")
    base = int(readback["register_base"], 16)
    addresses(base)
    if (Path("/sys/class/drm/card1/device").resolve().name != PCI):
        raise ValueError("PCI device moved; no writes allowed")
    report = {
        "timestamp_utc": datetime.datetime.now(datetime.timezone.utc).isoformat(),
        "test": "Windows-reported HDR metadata on Linux DP; signaling experiment only",
        "baseline": baseline, "candidate_packet_hex": packet.hex(), "seconds": args.seconds,
        "native_dolby_vision_mode_activation_verified": None,
        "full_dolby_vision_video_conformance_verified": False,
        "samples": [], "rollback_verified": False,
    }
    args.output.parent.mkdir(parents=True, exist_ok=True)
    if args.plan:
        report["applied"] = False
        args.output.write_text(json.dumps(report, indent=2) + "\n")
        print(f"Validated plan: {args.output}; no register writes")
        return
    state = {"base": base, "original_packet": readback["packet_storage_hex"],
             "watchdog_seconds": args.seconds + 8}
    state_path = args.output.with_suffix(".rollback.json")
    if state_path.exists() or state_path.with_suffix(".cancel").exists():
        raise ValueError("Use a new output filename; existing rollback evidence must be preserved")
    state_path.write_text(json.dumps(state) + "\n")
    # Detached watchdog survives loss of the initiating shell/process group.
    subprocess.Popen([sys.executable, str(Path(__file__).resolve()), "--watchdog", str(state_path.resolve())],
                     start_new_session=True, stdin=subprocess.DEVNULL,
                     stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL)
    signal.signal(signal.SIGTERM, interrupted)
    signal.signal(signal.SIGINT, interrupted)
    try:
        write_packet(base, packet)
        report["applied"] = True
        print("Candidate metadata active. Check the monitor OSD now.", flush=True)
        control, data = addresses(base)
        for _ in range(args.seconds):
            words = read_words([control, *data])
            report["samples"].append({"gmp_enabled": bool(words[0] & 16),
                "candidate_still_programmed": struct.pack("<9I", *words[1:]) == packet})
            time.sleep(1)
    finally:
        report["rollback_verified"] = restore(state)
        if report["rollback_verified"]:
            state_path.with_suffix(".cancel").write_text("restored\n")
        args.output.write_text(json.dumps(report, indent=2) + "\n")
        print(f"Original metadata restored: {report['rollback_verified']}. Report: {args.output}", flush=True)
    if not report["rollback_verified"]:
        raise RuntimeError("Immediate restoration failed; independent watchdog remains armed")


if __name__ == "__main__":
    try:
        main()
    except (ValueError, RuntimeError, InterruptedError, OSError, subprocess.CalledProcessError) as error:
        print(f"Signal test: {error}", file=sys.stderr)
        sys.exit(1)
