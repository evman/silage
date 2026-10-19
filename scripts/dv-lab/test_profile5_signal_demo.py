import unittest

from profile5_signal_demo import validate_renderer


class PlaybackPrerequisites(unittest.TestCase):
    def state(self, **overrides):
        return {"source_profile": "5", "display_names": "DP-3", "output_transfer": "pq",
                "output_primaries": "bt.2020", "passes": ["color decoding, reshaping"], **overrides}

    def test_profile8_cannot_start_profile5_signaling_demo(self):
        with self.assertRaisesRegex(ValueError, "profile5.*False"):
            validate_renderer(self.state(source_profile="8"))

    def test_wrong_monitor_cannot_start_signaling(self):
        with self.assertRaisesRegex(ValueError, "correct_monitor.*False"):
            validate_renderer(self.state(display_names="DP-30"))

    def test_profile5_without_rpu_processing_cannot_start_signaling(self):
        with self.assertRaisesRegex(ValueError, "rpu_reshaping_present.*False"):
            validate_renderer(self.state(passes=["color decoding"]))

    def test_sdr_output_cannot_start_signaling(self):
        with self.assertRaisesRegex(ValueError, "pq_output.*False"):
            validate_renderer(self.state(output_transfer="gamma2.2"))


if __name__ == "__main__":
    unittest.main()
