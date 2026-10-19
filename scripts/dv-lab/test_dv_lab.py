import struct
import unittest

from dv_lab import dolby_blocks, hdr_metadata, rendering_checks, register_words, active_pipe


def checksummed(block):
    block[127] = -sum(block[:127]) % 256
    return bytes(block)


def edid_with_cta(data):
    base = bytearray(128)
    base[:8] = bytes.fromhex("00ffffffffffff00")
    base[126] = 1
    cta = bytearray(128)
    cta[:4] = bytes((2, 3, 4 + len(data), 0))
    cta[4:4 + len(data)] = data
    return checksummed(base) + checksummed(cta)


class EdidTests(unittest.TestCase):
    def test_live_monitor_capability_block(self):
        payload = bytes.fromhex("0146d0004c025d89606f87")
        result = dolby_blocks(edid_with_cta(bytes((0xe0 | len(payload),)) + payload))
        self.assertEqual(result, [{"version": 2, "hex": payload.hex()}])

    def test_truncated_data_block_rejected(self):
        with self.assertRaisesRegex(ValueError, "Truncated CTA"):
            dolby_blocks(edid_with_cta(bytes.fromhex("eb0146")))

    def test_corrupt_extension_rejected_before_dv_detection(self):
        edid = bytearray(edid_with_cta(bytes.fromhex("e50146d00040")))
        edid[-1] ^= 1
        with self.assertRaisesRegex(ValueError, "checksum"):
            dolby_blocks(edid)

    def test_missing_extension_rejected(self):
        with self.assertRaisesRegex(ValueError, "length mismatch"):
            dolby_blocks(edid_with_cta(b"")[:128])

    def test_non_dolby_block_is_not_support(self):
        self.assertEqual(dolby_blocks(edid_with_cta(bytes.fromhex("e50147110040"))), [])


class MetadataTests(unittest.TestCase):
    def test_live_hdr_blob_and_i915_packet_mapping(self):
        blob = "0000000002008485823baf2f798a0a1cdf0a3a3d474009010100090109010000"
        result = hdr_metadata(blob)
        self.assertEqual(result["eotf_name"], "PQ")
        self.assertEqual(result["display_primaries"][0], [34180, 15234])
        self.assertEqual(result["max_cll"], 265)
        self.assertEqual(result["predicted_i915_dp_hdr_sdp_hex"],
                         "00871d4c011a02008485823baf2f798a0a1cdf0a3a3d47400901010009010901")

    def test_unknown_descriptor_rejected(self):
        with self.assertRaisesRegex(ValueError, "descriptor"):
            hdr_metadata((struct.pack("<I", 1) + bytes(28)).hex())

    def test_invalid_abi_size_rejected(self):
        with self.assertRaisesRegex(ValueError, "ABI size"):
            hdr_metadata("00" * 30)

    def test_sdr_without_blob(self):
        self.assertIsNone(hdr_metadata(None))


class RenderingEvidenceTests(unittest.TestCase):
    def state(self, **overrides):
        return {"source_profile": "5", "display_names": "DP-3", "output_transfer": "pq",
                "passes": ["color decoding, reshaping"], **overrides}

    def test_profile5_metadata_ab(self):
        checks = rendering_checks(self.state(), self.state(passes=["color decoding"]), "DP-3")
        self.assertTrue(all(checks.values()))

    def test_wrong_monitor_cannot_pass(self):
        checks = rendering_checks(self.state(display_names="DP-30"), self.state(), "DP-3")
        self.assertFalse(checks["correct_monitor"])

    def test_profile8_cannot_satisfy_profile5_control(self):
        checks = rendering_checks(self.state(source_profile="8"), self.state(), "DP-3")
        self.assertFalse(checks["profile5"])

    def test_no_renderer_passes_does_not_prove_metadata_disabled(self):
        checks = rendering_checks(self.state(), self.state(passes=[]), "DP-3")
        self.assertFalse(checks["metadata_reshaping_disabled"])


class RegisterEvidenceTests(unittest.TestCase):
    def test_connector_summary_does_not_create_duplicate_pipe_match(self):
        state = ("[CRTC:269:pipe C]:\n\t[CONNECTOR:543:DP-3]\n"
                 "[CRTC:505:pipe D]:\n\tuapi: enable=no\n"
                 "Connector info\n--------------\n[CONNECTOR:543:DP-3]: status: connected\n")
        self.assertEqual(active_pipe(state, 543, "DP-3"), "C")

    def test_unresolved_pipe_rejected(self):
        with self.assertRaisesRegex(ValueError, "single active"):
            active_pipe("Connector info\n[CONNECTOR:543:DP-3]: status: connected\n", 543, "DP-3")

    def test_readback_uses_requested_address_order(self):
        output = " (0x000622e4): 0x00021a01\n (0x000622e0): 0x4c1d8700\n"
        self.assertEqual(register_words(output, [0x622e0, 0x622e4]), [0x4c1d8700, 0x00021a01])

    def test_missing_register_cannot_pass(self):
        with self.assertRaisesRegex(ValueError, "Incomplete"):
            register_words(" (0x000622e0): 0x4c1d8700\n", [0x622e0, 0x622e4])

    def test_duplicate_register_cannot_pass(self):
        with self.assertRaisesRegex(ValueError, "Incomplete"):
            register_words(" (0x000622e0): 0x4c1d8700\n" * 2, [0x622e0])


if __name__ == "__main__":
    unittest.main()
