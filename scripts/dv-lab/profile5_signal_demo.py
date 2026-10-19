#!/usr/bin/env python3
"""Play the pinned Profile 5 sample during the bounded DP signaling experiment.

The player runs as the desktop user; only signal_test.py runs through sudo.
Renderer and register evidence do not establish Dolby colorimetric conformance.
"""
import argparse
import hashlib
import json
import os
from pathlib import Path
import subprocess
import sys
import time

from dv_lab import Mpv, SAMPLE_SHA256, snapshot


def validate_renderer(state):
    checks = {
        "profile5": state["source_profile"] == "5",
        "correct_monitor": "DP-3" in (state["display_names"] or "").split(","),
        "pq_output": state["output_transfer"] == "pq",
        "bt2020_output": state["output_primaries"] == "bt.2020",
        "rpu_reshaping_present": any("reshaping" in p for p in state["passes"]),
    }
    if not all(checks.values()):
        raise ValueError(f"Renderer prerequisites failed: {checks}")
    return checks


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--video", type=Path, required=True)
    parser.add_argument("--reference", type=Path, required=True)
    parser.add_argument("--output", type=Path, required=True)
    args = parser.parse_args()
    if os.geteuid() == 0 or not os.environ.get("WAYLAND_DISPLAY"):
        raise ValueError("Run as the desktop user with WAYLAND_DISPLAY set")
    video = args.video.resolve(strict=True)
    with video.open("rb") as stream:
        digest = hashlib.file_digest(stream, "sha256").hexdigest()
    if digest != SAMPLE_SHA256:
        raise ValueError("Use the pinned official Dolby Profile 5 sample")
    args.output = args.output.resolve()
    args.output.parent.mkdir(parents=True, exist_ok=True)
    signal_output = args.output.with_name(args.output.stem + "-signal.json")
    if args.output.exists() or signal_output.exists():
        raise ValueError("Use a new output filename to preserve prior evidence")
    report = {"test": "Profile 5 renderer plus bounded DP DV activation signal",
              "sample_sha256": digest, "renderer_samples": [],
              "panel_observation": None, "full_dolby_video_conformance_verified": False,
              "before": snapshot("/dev/dri/card1", "DP-3", readback=True)}
    player = Mpv("DP-3")
    process = None
    try:
        player.command("loadfile", str(video))
        deadline = time.monotonic() + 20
        while not player.prop("vo-passes") or not player.prop("video-target-params/gamma"):
            if time.monotonic() > deadline:
                raise RuntimeError("Timed out waiting for the Profile 5 renderer")
            time.sleep(.1)
        time.sleep(2)
        state = player.measure()
        report["renderer_prerequisites"] = validate_renderer(state)
        report["renderer_before_signal"] = state
        print("Profile 5 ready on DP-3 with RPU reshaping. Starting 15-second signal test.", flush=True)
        script = Path(__file__).with_name("signal_test.py").resolve()
        process = subprocess.Popen(["sudo", "-n", sys.executable, str(script), "--apply", "--seconds", "15",
                                    "--reference", str(args.reference.resolve()), "--output", str(signal_output)])
        deadline = time.monotonic() + 25
        while process.poll() is None:
            if time.monotonic() > deadline:
                raise RuntimeError("Signaling helper exceeded its time limit")
            report["renderer_samples"].append(player.measure())
            time.sleep(.5)
        if process.returncode:
            raise RuntimeError(f"Signaling helper failed ({process.returncode})")
        report["signal_test"] = json.loads(signal_output.read_text())
        report["renderer_checks_all_samples"] = all(
            all(validate_renderer(state).values()) for state in report["renderer_samples"])
    finally:
        try:
            if process is not None and process.poll() is None:
                process.terminate()
                process.wait(timeout=10)
        finally:
            player.close()
            time.sleep(.5)
            report["after"] = snapshot("/dev/dri/card1", "DP-3", readback=True)
            report["desktop_metadata_restored"] = (
                report["before"]["hdr_output_metadata_hex"] == report["after"]["hdr_output_metadata_hex"]
                and report["after"]["intel_readback"]["matches_requested_hdr_sdp"])
            args.output.write_text(json.dumps(report, indent=2) + "\n")
            print(f"Saved {args.output}; desktop metadata restored: {report['desktop_metadata_restored']}", flush=True)


if __name__ == "__main__":
    try:
        main()
    except (ValueError, RuntimeError, OSError, subprocess.CalledProcessError) as error:
        print(f"Profile 5 signal demo: {error}", file=sys.stderr)
        sys.exit(1)
