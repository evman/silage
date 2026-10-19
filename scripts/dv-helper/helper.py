#!/usr/bin/python3 -I
"""Opt-in, monitor-specific DV signaling helper for stock KDE on Intel A380.

Installed root-owned; never imports application code or accepts register values,
file names, shell commands, or metadata from its caller. The detached guardian
owns the register writes and restores on EOF, heartbeat expiry, or relay death.
This is experimental signaling, not a certified Dolby Vision implementation.
"""
import fcntl
import hashlib
import json
import os
from pathlib import Path
import re
import select
import signal
import stat
import struct
import subprocess
import sys
import time
from dataclasses import dataclass

INSTALL = Path(__file__).resolve().parent
INTEL_REG = "/usr/bin/intel_reg"
TIMEOUT = 5.0
INTERVAL = 0.5
MAX_SESSION = 12 * 60 * 60
CAPABILITY = bytes.fromhex("0146d0004c025d89606f87")
PACKET = bytes.fromhex("00871d4c011a0200") + struct.pack(
    "<12H", 35366, 14710, 8456, 39806, 6650, 2118, 15854, 16432, 1000, 0, 1022, 938
) + bytes(4)
SAFE_ENV = {"PATH": "/usr/bin:/bin", "LC_ALL": "C", "HOME": "/root"}


def trusted(path):
    """All installed code and its resolved parents must be owned by root."""
    path = Path(path).resolve(strict=True)
    for node in (path, *path.parents):
        info = node.stat()
        if info.st_uid != 0 or info.st_mode & (stat.S_IWGRP | stat.S_IWOTH):
            raise ValueError("Helper installation is not protected from user writes")
    return path


def run(argv):
    return subprocess.check_output(argv, text=True, env=SAFE_ENV, cwd="/",
                                   stdin=subprocess.DEVNULL, stderr=subprocess.PIPE, timeout=2)


def validated_edid(raw):
    if (len(raw) < 128 or raw[:8] != bytes.fromhex("00ffffffffffff00")
            or len(raw) != (raw[126] + 1) * 128
            or any(sum(raw[i:i + 128]) % 256 for i in range(0, len(raw), 128))
            or raw[8:12] != bytes.fromhex("06b3f232")):
        raise ValueError("Expected the validated ASUS PG32UCDM EDID")
    blocks = []
    for start in range(128, len(raw), 128):
        block = raw[start:start + 128]
        if block[0] != 2 or block[1] < 3 or not block[2]:
            continue
        if not 4 <= block[2] <= 127:
            raise ValueError("Invalid CTA boundary")
        pos = 4
        while pos < block[2]:
            size = block[pos] & 31
            if pos + 1 + size > block[2]:
                raise ValueError("Truncated CTA block")
            data = block[pos + 1:pos + 1 + size]
            if block[pos] >> 5 == 7 and data[:4] == CAPABILITY[:4]:
                blocks.append(data)
            pos += size + 1
    if blocks != [CAPABILITY]:
        raise ValueError("Monitor Dolby capability differs from the tested profile")
    return hashlib.sha256(raw).hexdigest()


def pipe_state(text, connector_id, connector):
    sections = re.findall(r"(?ms)^\[CRTC:(\d+):pipe ([A-D])\]:\n(.*?)(?=^\S|\Z)", text)
    found = [(crtc, pipe, body) for crtc, pipe, body in sections
             if f"[CONNECTOR:{connector_id}:{connector}]" in body]
    if len(found) != 1:
        raise ValueError("Output is not on a single active pipe")
    crtc, pipe, body = found[0]
    mode = re.search(r'^\s*uapi: enable=yes, active=yes, mode="3840x2160": (.+)$', body, re.M)
    if not mode or not re.search(r"\bpipe src=.*\bbpp=30\b", body):
        raise ValueError("The validated output requires active 4K, 10-bit HDR")
    link = re.search(r"^\s*port_clock=.*$", body, re.M)
    if not link:
        raise ValueError("Cannot identify the active display link")
    return pipe, (crtc, mode[0].strip(), link[0].strip())


@dataclass(frozen=True)
class Output:
    identity: tuple
    base: int
    expected: bytes
    packet: bytes
    control: int


class Hardware:
    def __init__(self):
        self.tool = str(trusted(INSTALL / "kms-snapshot"))
        trusted(INTEL_REG)
        candidates = []
        for node in Path("/sys/class/drm").glob("card[0-9]*"):
            if not re.fullmatch(r"card\d+", node.name):
                continue
            gpu = (node / "device").resolve()
            if (gpu / "vendor").exists() and (
                (gpu / "vendor").read_text().strip(), (gpu / "device").read_text().strip()
            ) == ("0x8086", "0x56a5") and (gpu / "driver").resolve().name == "i915":
                candidates.append((node.name, gpu))
        if len(candidates) != 1:
            raise ValueError("Requires exactly one Intel Arc A380 using i915")
        self.card, self.gpu = candidates[0]

    def registers(self, addresses):
        output = run([INTEL_REG, f"--pci-slot={self.gpu.name}", "read", *map(hex, addresses)])
        pairs = re.findall(r"\(0x([0-9a-fA-F]+)\):\s*0x([0-9a-fA-F]+)", output)
        words = {int(address, 16): int(value, 16) for address, value in pairs}
        if len(pairs) != len(addresses) or set(words) != set(addresses):
            raise ValueError("Incomplete register readback")
        return [words[address] for address in addresses]

    def snapshot(self, connector):
        if not re.fullmatch(r"DP-[1-9][0-9]?", connector):
            raise ValueError("Only a DisplayPort connector name is accepted")
        node = Path("/sys/class/drm") / f"{self.card}-{connector}"
        if (node / "device/device").resolve() != self.gpu:
            raise ValueError("GPU assignment changed")
        edid = validated_edid((node / "edid").read_bytes())
        kms = json.loads(run([self.tool, f"/dev/dri/{self.card}", connector]))
        raw = bytes.fromhex(kms.get("hdr_output_metadata_hex") or "")
        props = kms["properties"]
        colorspace = props.get("Colorspace", {})
        if (not kms["connected"] or len(raw) != 32 or raw[:6] != bytes.fromhex("000000000200")
                or colorspace.get("value") != colorspace.get("enums", {}).get("BT2020_RGB")
                or "BT2020_RGB" not in colorspace.get("enums", {})):
            raise ValueError("Output must already use PQ HDR with BT.2020 RGB")
        debug = Path("/sys/kernel/debug/dri") / self.card[4:] / "i915_display_info"
        pipe, mode = pipe_state(debug.read_text(), kms["connector_id"], connector)
        if kms.get("crtc_id") != int(mode[0]):
            raise ValueError("Display topology changed during validation")
        vt = Path("/sys/class/tty/tty0/active").read_text().strip()
        base = 0x602e0 + (ord(pipe) - ord("A")) * 0x1000
        control, *data = self.registers([base - 0xe0, *range(base, base + 36, 4)])
        identity = (self.gpu.name, kms["connector_id"], edid, pipe, *mode, vt)
        return Output(identity, base, bytes.fromhex("00871d4c011a") + raw[4:30] + bytes(4),
                      struct.pack("<9I", *data), control)

    def write(self, base, packet):
        if base not in (0x602e0, 0x612e0, 0x622e0, 0x632e0) or len(packet) != 36:
            raise ValueError("Invalid internal packet operation")
        control = base - 0xe0
        value = self.registers([control])[0]
        args = [INTEL_REG, f"--pci-slot={self.gpu.name}", "write", "--post"]
        pairs = [(control, value & ~16),
                 *zip(range(base, base + 36, 4), struct.unpack("<9I", packet)), (control, value | 16)]
        for address, word in pairs:
            args.extend((hex(address), hex(word)))
        run(args)


class SignalLease:
    def __init__(self, hardware):
        self.hardware = hardware
        self.baseline = None
        self.connector = None
        self.dirty = False

    def enable(self, connector):
        if self.baseline is not None:
            raise ValueError("Output session is already active")
        baseline = self.hardware.snapshot(connector)
        if not baseline.control & 16 or baseline.packet != baseline.expected or baseline.packet == PACKET:
            raise ValueError("Packet does not match ordinary HDR; refusing to overwrite another session")
        self.baseline, self.connector = baseline, connector
        self.dirty = True  # Arm restoration before the first potentially partial write.
        self.hardware.write(baseline.base, PACKET)
        self.dirty = False
        self.tick()

    def tick(self):
        if self.baseline is None:
            return
        state = self.hardware.snapshot(self.connector)
        if state.identity != self.baseline.identity or state.expected != self.baseline.expected:
            raise ValueError("Display configuration changed; ending the experimental signal")
        if not state.control & 16 or state.packet not in (PACKET, state.expected):
            raise ValueError("Display packet changed unexpectedly")
        if state.packet == state.expected:
            # Stock KWin may reprogram ordinary HDR. Reapply only that known state.
            self.dirty = True
            self.hardware.write(state.base, PACKET)
            self.dirty = False
            verified = self.hardware.snapshot(self.connector)
            if (verified.identity != state.identity or verified.expected != state.expected
                    or not verified.control & 16 or verified.packet != PACKET):
                raise ValueError("Signal readback failed")

    def release(self):
        if self.baseline is None:
            return "inactive"
        last_error = None
        for _ in range(3):
            try:
                current = self.hardware.snapshot(self.connector)
                if current.identity != self.baseline.identity:
                    # Never write a stale saved packet into a reused pipe or another VT.
                    self.baseline = None
                    return "output-changed"
                if current.packet == PACKET or self.dirty:
                    self.hardware.write(current.base, current.expected)
                    current = self.hardware.snapshot(self.connector)
                if (current.identity != self.baseline.identity or not current.control & 16
                        or current.packet != current.expected):
                    raise ValueError("Restoration readback failed")
                self.baseline = None
                self.dirty = False
                return "restored"
            except (OSError, ValueError, subprocess.SubprocessError) as error:
                last_error = error
                time.sleep(.1)
        raise RuntimeError("Could not verify restoration; display may have disconnected") from last_error


def emit(event, **values):
    print(json.dumps({"version": 1, "event": event, **values}), flush=True)


def guardian(fd, hardware, report=emit, timeout=TIMEOUT, interval=INTERVAL):
    lease = SignalLease(hardware)
    started = time.monotonic()
    deadline = started + timeout
    buffer = b""
    result = 0
    try:
        report("ready", guardian_pid=os.getpid(), relay_pid=os.getppid())
        while True:
            now = time.monotonic()
            if now >= deadline or now - started >= MAX_SESSION:
                raise TimeoutError("Application heartbeat expired")
            readable, _, _ = select.select([fd], [], [], min(interval, deadline - now))
            if readable:
                data = os.read(fd, 512)
                if not data:
                    break
                buffer += data
                lines = buffer.split(b"\n")
                buffer = lines.pop()
                if len(buffer) > 128 or len(lines) > 8 or any(len(line) > 128 for line in lines):
                    raise ValueError("Invalid helper command length or rate")
                # Receipt after the deadline cannot revive an expired lease.
                if time.monotonic() >= deadline:
                    raise TimeoutError("Application heartbeat arrived too late")
                for line in lines:
                    command = line.decode("ascii")
                    if command == "renew":
                        deadline = time.monotonic() + timeout
                    elif re.fullmatch(r"enable DP-[1-9][0-9]?", command):
                        lease.enable(command[7:])
                        report("active", connector=command[7:])
                    elif command == "release":
                        report(lease.release())
                    elif command == "quit":
                        return finish(lease, report)
                    else:
                        raise ValueError("Unsupported helper command")
            lease.tick()
    except (Exception, KeyboardInterrupt) as error:
        result = 1
        try:
            report("error", message=str(error))
        except (OSError, ValueError):
            pass
    finally:
        try:
            restored = lease.release()
            try:
                report(restored)
            except (OSError, ValueError):
                pass
        except Exception as error:
            result = 1
            print(f"Silage DV helper: {error}", file=sys.stderr, flush=True)
    return result


def finish(lease, report):
    # Complete restoration before a successful quit can be acknowledged.
    report(lease.release())
    return 0


def interrupted(signum, frame):
    raise InterruptedError("Helper interrupted; restoring ordinary HDR")


def relay(hardware, lock_fd):
    """Relay loss closes a pipe; its detached child retains the restoration state."""
    read_fd, write_fd = os.pipe()
    pid = os.fork()
    if pid == 0:
        os.close(write_fd)
        os.setsid()
        os.close(0)
        signal.signal(signal.SIGHUP, signal.SIG_IGN)
        signal.signal(signal.SIGTERM, interrupted)
        signal.signal(signal.SIGINT, interrupted)
        result = guardian(read_fd, hardware)
        os.close(read_fd)
        os.close(lock_fd)
        os._exit(result)
    os.close(read_fd)
    os.close(lock_fd)
    try:
        while True:
            ended, status = os.waitpid(pid, os.WNOHANG)
            if ended:
                return os.waitstatus_to_exitcode(status)
            if select.select([0], [], [], .25)[0]:
                data = os.read(0, 512)
                if not data:
                    break
                os.write(write_fd, data)
    except (BrokenPipeError, InterruptedError):
        pass
    finally:
        os.close(write_fd)
    _, status = os.waitpid(pid, 0)
    return os.waitstatus_to_exitcode(status)


def main():
    if sys.argv[1:] == ["--version"]:
        print("silage-dv-helper 1 (experimental Intel A380 / ASUS PG32UCDM / DisplayPort)")
        return 0
    if sys.argv[1:] not in ([], ["--probe"]):
        raise ValueError("Only --version or --probe is accepted; playback uses stdin")
    if os.geteuid() != 0:
        raise ValueError("Administrator authorization is required")
    trusted(__file__)
    hardware = Hardware()
    if sys.argv[1:] == ["--probe"]:
        # Fixed connector for this initial installation's read-only check.
        output = hardware.snapshot("DP-3")
        emit("probe", ordinary_hdr=bool(output.control & 16) and output.packet == output.expected,
             candidate_programmed=output.packet == PACKET, pipe=output.identity[3])
        return 0
    lock = os.open("/run/silage-dv-helper.lock", os.O_CREAT | os.O_RDWR | os.O_CLOEXEC | os.O_NOFOLLOW, 0o600)
    fcntl.flock(lock, fcntl.LOCK_EX | fcntl.LOCK_NB)
    signal.signal(signal.SIGTERM, interrupted)
    signal.signal(signal.SIGINT, interrupted)
    return relay(hardware, lock)


if __name__ == "__main__":
    try:
        sys.exit(main())
    except (ValueError, OSError, subprocess.SubprocessError) as error:
        print(f"Silage DV helper: {error}", file=sys.stderr)
        sys.exit(1)
