import importlib.util
import os
from pathlib import Path
import signal
import struct
import sys
import tempfile
import threading
import time
import unittest
from dataclasses import replace

spec = importlib.util.spec_from_file_location("silage_dv_helper", Path(__file__).with_name("helper.py"))
helper = importlib.util.module_from_spec(spec)
sys.modules[spec.name] = helper
spec.loader.exec_module(helper)

BASELINE = bytes.fromhex("00871d4c011a0200") + struct.pack("<12H", *range(12)) + bytes(4)


class Hardware:
    def __init__(self, log=None):
        self.state = helper.Output(("gpu", 543, "edid", "C", "269", "mode", "link", "tty2"),
                                   0x622e0, BASELINE, BASELINE, 16)
        self.writes = []
        self.fail_write = False
        self.log = log

    def snapshot(self, connector):
        if connector != "DP-3":
            raise ValueError("Wrong output")
        return self.state

    def write(self, base, packet):
        self.writes.append((base, packet))
        if self.fail_write:
            self.fail_write = False
            self.state = replace(self.state, packet=bytes(36), control=0)
            raise OSError("Partial register write")
        self.state = replace(self.state, packet=packet, control=16)
        if self.log:
            with open(self.log, "a") as stream:
                stream.write("active\n" if packet == helper.PACKET else "restored\n")


class SignalTests(unittest.TestCase):
    def setUp(self):
        self.hw = Hardware()
        self.lease = helper.SignalLease(self.hw)

    def test_enable_readback_and_restore(self):
        self.lease.enable("DP-3")
        self.assertEqual(self.hw.state.packet, helper.PACKET)
        self.assertEqual(self.lease.release(), "restored")
        self.assertEqual(self.hw.state.packet, BASELINE)

    def test_refuse_foreign_packet_and_duplicate_owner(self):
        self.hw.state = replace(self.hw.state, packet=helper.PACKET)
        with self.assertRaises(ValueError):
            self.lease.enable("DP-3")
        self.assertFalse(self.hw.writes)

    def test_compositor_reset_reapplies_only_known_baseline(self):
        self.lease.enable("DP-3")
        self.hw.state = replace(self.hw.state, packet=BASELINE)
        self.lease.tick()
        self.assertEqual(self.hw.state.packet, helper.PACKET)
        self.assertEqual(len(self.hw.writes), 2)

    def test_unexpected_packet_is_not_overwritten(self):
        self.lease.enable("DP-3")
        self.hw.state = replace(self.hw.state, packet=bytes(36))
        with self.assertRaises(ValueError):
            self.lease.tick()
        with self.assertRaises(RuntimeError):
            self.lease.release()
        self.assertEqual(len(self.hw.writes), 1)

    def test_reused_pipe_and_changed_vt_are_never_restored_from_saved_state(self):
        for identity in (("another monitor",), ("another VT",)):
            hw = Hardware()
            lease = helper.SignalLease(hw)
            lease.enable("DP-3")
            hw.state = replace(hw.state, identity=identity)
            with self.assertRaises(ValueError):
                lease.tick()
            self.assertEqual(lease.release(), "output-changed")
            self.assertEqual(len(hw.writes), 1)

    def test_updated_hdr_metadata_restores_current_compositor_request(self):
        self.lease.enable("DP-3")
        requested = BASELINE[:-4] + bytes.fromhex("01020304")
        self.hw.state = replace(self.hw.state, expected=requested)
        with self.assertRaises(ValueError):
            self.lease.tick()
        self.assertEqual(self.lease.release(), "restored")
        self.assertEqual(self.hw.state.packet, requested)

    def test_partial_write_is_armed_for_restoration(self):
        self.hw.fail_write = True
        with self.assertRaises(OSError):
            self.lease.enable("DP-3")
        self.assertEqual(self.lease.release(), "restored")
        self.assertEqual(self.hw.state.packet, BASELINE)

    def test_transient_read_failure_retries_restoration(self):
        self.lease.enable("DP-3")
        original = self.hw.snapshot
        attempts = []

        def snapshot(connector):
            attempts.append(connector)
            if len(attempts) == 1:
                raise OSError("Transient device read failure")
            return original(connector)

        self.hw.snapshot = snapshot
        self.assertEqual(self.lease.release(), "restored")
        self.assertEqual(self.hw.state.packet, BASELINE)

    def test_wrong_connector_does_not_write(self):
        with self.assertRaises(ValueError):
            self.lease.enable("DP-1")
        self.assertFalse(self.hw.writes)


class GuardianTests(unittest.TestCase):
    def run_guardian(self, after_enable):
        hw = Hardware()
        read_fd, write_fd = os.pipe()
        active = threading.Event()
        events = []
        result = []

        def report(event, **values):
            events.append(event)
            if event == "active":
                active.set()

        worker = threading.Thread(target=lambda: result.append(
            helper.guardian(read_fd, hw, report, timeout=.3, interval=.01)))
        worker.start()
        try:
            os.write(write_fd, b"enable DP-3\n")
            self.assertTrue(active.wait(2))
            after_enable(write_fd, hw)
        finally:
            os.close(write_fd)
            worker.join(3)
            os.close(read_fd)
        self.assertFalse(worker.is_alive())
        self.assertEqual(hw.state.packet, BASELINE)
        self.assertIn("restored", events)
        return result, events

    def test_eof_restores(self):
        result, _ = self.run_guardian(lambda fd, hw: None)
        self.assertEqual(result, [0])

    def test_quit_and_release_restore(self):
        for command in (b"quit\n", b"release\nquit\n"):
            result, _ = self.run_guardian(lambda fd, hw: os.write(fd, command))
            self.assertEqual(result, [0])

    def test_heartbeat_expiry_restores(self):
        result, events = self.run_guardian(lambda fd, hw: time.sleep(.5))
        self.assertEqual(result, [1])
        self.assertIn("error", events)

    def test_heartbeats_keep_live_playback_active(self):
        def heartbeat(fd, hw):
            for _ in range(6):
                os.write(fd, b"renew\n")
                time.sleep(.1)
                self.assertEqual(hw.state.packet, helper.PACKET)
        result, _ = self.run_guardian(heartbeat)
        self.assertEqual(result, [0])

    def test_malformed_commands_and_oversized_input_restore(self):
        for command in (b"write 0x622e0 0\n", b"x" * 129, b"enable HDMI-A-1\n", b"\xff\n"):
            result, _ = self.run_guardian(lambda fd, hw: (os.write(fd, command), time.sleep(.1)))
            self.assertEqual(result, [1])

    def test_lost_stdout_restores_even_when_error_cannot_be_reported(self):
        hw = Hardware()
        read_fd, write_fd = os.pipe()
        os.write(write_fd, b"enable DP-3\n")
        os.close(write_fd)

        def report(event, **values):
            if event != "ready":
                raise BrokenPipeError("Application vanished")

        try:
            self.assertEqual(helper.guardian(read_fd, hw, report), 1)
            self.assertEqual(hw.state.packet, BASELINE)
        finally:
            os.close(read_fd)

    def test_killing_relay_leaves_guardian_to_restore(self):
        # Actual fork/pipe lifecycle, with a fake register backend and no root access.
        with tempfile.TemporaryDirectory() as tmp:
            log = Path(tmp) / "writes"
            read_fd, write_fd = os.pipe()
            pid = os.fork()
            if pid == 0:
                os.close(write_fd)
                os.dup2(read_fd, 0)
                if read_fd != 0:
                    os.close(read_fd)
                sink = os.open(os.devnull, os.O_WRONLY)
                os.dup2(sink, 1)
                os.dup2(sink, 2)
                lock = os.open(Path(tmp) / "lock", os.O_CREAT | os.O_RDWR, 0o600)
                os._exit(helper.relay(Hardware(log), lock))
            os.close(read_fd)
            try:
                os.write(write_fd, b"enable DP-3\n")
                deadline = time.monotonic() + 3
                while not log.exists() and time.monotonic() < deadline:
                    time.sleep(.01)
                self.assertTrue(log.exists())
                os.kill(pid, signal.SIGKILL)
                os.waitpid(pid, 0)
                while "restored" not in log.read_text() and time.monotonic() < deadline:
                    time.sleep(.01)
                self.assertEqual(log.read_text().splitlines(), ["active", "restored"])
            finally:
                os.close(write_fd)


class ValidationTests(unittest.TestCase):
    def test_exact_dolby_block_and_valid_edid_required(self):
        base = bytearray(128)
        base[:8] = bytes.fromhex("00ffffffffffff00")
        base[8:12] = bytes.fromhex("06b3f232")
        base[126] = 1
        base[-1] = -sum(base) % 256
        extension = bytearray(128)
        extension[:4] = bytes((2, 3, 16, 0))
        extension[4] = 0xe0 | len(helper.CAPABILITY)
        extension[5:16] = helper.CAPABILITY
        extension[-1] = -sum(extension) % 256
        valid = bytes(base + extension)
        self.assertEqual(len(helper.validated_edid(valid)), 64)
        for changed in (valid[:-1], valid[:8] + bytes(4) + valid[12:], valid[:-1] + bytes(1)):
            with self.assertRaises(ValueError):
                helper.validated_edid(changed)
        altered = bytearray(valid)
        altered[140] ^= 1
        altered[-1] = 0
        altered[-1] = -sum(altered[128:]) % 256
        with self.assertRaises(ValueError):
            helper.validated_edid(bytes(altered))

    def test_pipe_parser_does_not_capture_inactive_connector_section(self):
        text = ('[CRTC:269:pipe C]:\n'
                '\tuapi: enable=yes, active=yes, mode="3840x2160": 240 2332000\n'
                '\tpipe src=1920x2160+0+0, dither=no, bpp=30\n'
                '\tport_clock=810000, lane_count=4\n'
                '\t\t[CONNECTOR:543:DP-3]\n'
                'Connector info\n---------\n[CONNECTOR:999:DP-4]: status: connected\n')
        self.assertEqual(helper.pipe_state(text, 543, "DP-3")[0], "C")
        with self.assertRaises(ValueError):
            helper.pipe_state(text, 999, "DP-4")


if __name__ == "__main__":
    unittest.main()
