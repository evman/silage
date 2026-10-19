import struct
import unittest
from unittest.mock import patch

from signal_test import addresses, candidate_packet, restore, write_packet


def reference():
    raw = "34000200000000000200000000000000268a763908217e9bfa194608ee3d3040e8030000fe03aa03000000000000000003000000"
    return {"displays": [{"physically_connected": True, "active": True,
        "hdr_get_status": 0, "dolby_vision_capable": True,
        "hdr_color_data_v2_hex": raw,
        "mastering_primaries_white_luminance_cll_fall": list(struct.unpack_from("<12H", bytes.fromhex(raw), 16))}]}


class SignalTestSafeguards(unittest.TestCase):
    def test_candidate_preserves_captured_values_in_i915_wrapper(self):
        self.assertEqual(candidate_packet(reference()).hex(),
            "00871d4c011a0200268a763908217e9bfa194608ee3d3040e8030000fe03aa0300000000")

    def test_virtual_reference_rejected(self):
        r = reference(); r["displays"][0]["physically_connected"] = False
        with self.assertRaisesRegex(ValueError, "physical"):
            candidate_packet(r)

    def test_non_get_reference_rejected(self):
        r = reference(); r["displays"][0]["hdr_color_data_v2_hex"] = "3400020001000000" + r["displays"][0]["hdr_color_data_v2_hex"][16:]
        with self.assertRaisesRegex(ValueError, "GET layout"):
            candidate_packet(r)

    def test_metadata_list_tampering_rejected(self):
        r = reference(); r["displays"][0]["mastering_primaries_white_luminance_cll_fall"][0] += 1
        with self.assertRaisesRegex(ValueError, "disagree"):
            candidate_packet(r)

    @patch("signal_test.subprocess.check_output")
    @patch("signal_test.subprocess.run")
    def test_arbitrary_register_address_cannot_be_written(self, run, read):
        with self.assertRaisesRegex(ValueError, "register address"):
            write_packet(0x1234, candidate_packet(reference()))
        run.assert_not_called(); read.assert_not_called()

    @patch("signal_test.subprocess.run")
    def test_packet_update_preserves_other_control_bits(self, run):
        packet = candidate_packet(reference())
        with patch("signal_test.read_words", return_value=[0x100010]):
            write_packet(0x622e0, packet)
        args = run.call_args.args[0]
        pairs = list(zip(args[4::2], args[5::2]))
        self.assertEqual(pairs[0], ("0x62200", "0x100000"))
        self.assertEqual(pairs[-1], ("0x62200", "0x100010"))
        self.assertEqual([int(a, 16) for a, _ in pairs[1:-1]], addresses(0x622e0)[1])

    @patch("signal_test.write_packet")
    def test_restore_checks_original_bytes_and_enabled_state(self, write):
        packet = candidate_packet(reference())
        state = {"base": 0x622e0, "original_packet": packet.hex()}
        with patch("signal_test.read_words", return_value=[16, *struct.unpack("<9I", packet)]):
            self.assertTrue(restore(state))
        with patch("signal_test.read_words", return_value=[0, *struct.unpack("<9I", packet)]):
            self.assertFalse(restore(state))
        altered = bytearray(packet); altered[8] ^= 1
        with patch("signal_test.read_words", return_value=[16, *struct.unpack("<9I", altered)]):
            self.assertFalse(restore(state))


if __name__ == "__main__":
    unittest.main()
