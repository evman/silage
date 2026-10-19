#!/usr/bin/env python3
"""Explicit hardware recovery test. Runs as the desktop user; sudo calls only installed tools."""
import argparse
import hashlib
import json
import os
from pathlib import Path
import select
import subprocess
import sys
import time

sys.path.insert(0, str(Path(__file__).resolve().parent.parent / "dv-lab"))
from dv_lab import Mpv, SAMPLE_SHA256

HELPER = "/usr/libexec/silage-dv-helper/silage-dv-helper"
ENV = {key: value for key, value in os.environ.items() if key not in ("LD_PRELOAD", "LD_LIBRARY_PATH")}


def probe():
    return json.loads(subprocess.check_output(["sudo", "-n", HELPER, "--probe"], env=ENV, text=True, timeout=5))


def event(process, timeout=8):
    deadline = time.monotonic() + timeout
    line = b""
    while time.monotonic() < deadline:
        if select.select([process.stdout], [], [], max(0, deadline - time.monotonic()))[0]:
            byte = os.read(process.stdout.fileno(), 1)
            if not byte:
                raise RuntimeError("Helper exited before acknowledgement")
            line += byte
            if byte == b"\n":
                return json.loads(line)
            if len(line) > 2048:
                raise RuntimeError("Invalid helper response")
    raise TimeoutError("Helper acknowledgement timed out")


def trial(kind):
    before = probe()
    if not before["ordinary_hdr"] or before["candidate_programmed"]:
        raise RuntimeError("Ordinary HDR must be active before this test")
    process = subprocess.Popen(["sudo", "-n", HELPER], stdin=subprocess.PIPE, stdout=subprocess.PIPE,
                               env=ENV, start_new_session=True)
    observations = []
    try:
        ready = event(process)
        if ready["event"] != "ready":
            raise RuntimeError("Helper not ready")
        process.stdin.write(b"enable DP-3\n")
        process.stdin.flush()
        observations.append(event(process))
        if observations[-1]["event"] != "active" or not probe()["candidate_programmed"]:
            raise RuntimeError("Signal not active")
        if kind == "relay-sigkill":
            relay = ready["relay_pid"]
            command = Path(f"/proc/{relay}/cmdline").read_bytes().split(b"\0")
            if HELPER.encode() not in command or Path(f"/proc/{relay}").stat().st_uid != 0:
                raise RuntimeError("Refusing to signal an unrelated process")
            subprocess.run(["sudo", "-n", "/usr/bin/kill", "-KILL", str(relay)], env=ENV, check=True, timeout=3)
        # Deliberately send no heartbeats. A live relay times out; a dead one gives EOF.
        while True:
            observation = event(process)
            observations.append(observation)
            if observation["event"] == "restored":
                break
        process.wait(timeout=8)
        after = probe()
        if not after["ordinary_hdr"] or after["candidate_programmed"]:
            raise RuntimeError("Normal HDR was not restored")
        return {"case": kind, "events": observations, "restored": True}
    finally:
        try:
            process.stdin.close()
        except (OSError, BrokenPipeError):
            pass
        try:
            process.wait(timeout=10)
        finally:
            process.stdout.close()


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--clip", required=True, type=Path)
    parser.add_argument("--output", required=True, type=Path)
    args = parser.parse_args()
    if os.geteuid() == 0:
        raise ValueError("Run this test as the desktop user")
    with args.clip.open("rb") as stream:
        if hashlib.file_digest(stream, "sha256").hexdigest() != SAMPLE_SHA256:
            raise ValueError("Expected the pinned official Profile 5 sample")
    player = Mpv("DP-3")
    result = {"stock_compositor": True, "tests": []}
    try:
        player.command("loadfile", str(args.clip.resolve()))
        deadline = time.monotonic() + 10
        while time.monotonic() < deadline:
            measured = player.measure()
            if (measured["source_profile"] == "5" and measured["output_transfer"] == "pq"
                    and "DP-3" in (measured["display_names"] or "").split(",")
                    and any("reshaping" in desc for desc in measured["passes"])):
                break
            time.sleep(.1)
        else:
            raise RuntimeError("Profile 5 renderer not ready on DP-3")
        result["tests"].append(trial("heartbeat-timeout"))
        result["tests"].append(trial("relay-sigkill"))
        result["renderer"] = player.measure()
    finally:
        player.close()
        result["final_probe"] = probe()
        args.output.write_text(json.dumps(result, indent=2) + "\n")
    print(f"Hardware recovery tests passed: {args.output}")


if __name__ == "__main__":
    main()
