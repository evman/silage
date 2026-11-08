"""The sponsor progress bar: adding up lifetime sponsorships and drawing the bar."""
from pathlib import Path
import importlib.util
import unittest
import xml.etree.ElementTree as ET

_path = Path(__file__).resolve().parents[1] / 'sponsors' / 'progress.py'
_spec = importlib.util.spec_from_file_location('sponsor_progress', _path)
progress = importlib.util.module_from_spec(_spec)
_spec.loader.exec_module(progress)


class SponsorProgressTest(unittest.TestCase):
    def test_adds_up_every_sponsors_lifetime_amount(self):
        pages = [{'nodes': [{'amountInCents': 500}, {'amountInCents': 12500}]}, {'nodes': [{'amountInCents': 99}]}]
        self.assertEqual(13099, progress.total_cents(pages))
        self.assertEqual(0, progress.total_cents([{'nodes': []}]))

    def test_draws_how_much_of_the_goal_is_raised(self):
        svg = progress.render_svg(raised_cents=6250, goal_cents=12500, purpose='Apple Developer + Google Play accounts')
        root = ET.fromstring(svg)  # well-formed SVG
        self.assertEqual('{http://www.w3.org/2000/svg}svg', root.tag)
        self.assertIn('$62.50 of $125 raised', svg)
        self.assertIn('Apple Developer + Google Play accounts', svg)
        self.assertIn('50%', svg)
        self.assertIn('width="210.0"', svg)  # half of the 420-wide bar

    def test_whole_dollars_print_without_cents_and_the_bar_stops_at_full(self):
        svg = progress.render_svg(raised_cents=20000, goal_cents=12500, purpose='x')
        self.assertIn('$200 of $125 raised', svg)
        self.assertIn('100%', svg)
        self.assertIn('width="420.0"', svg)
        self.assertIn('$0 of $125 raised', progress.render_svg(raised_cents=0, goal_cents=12500, purpose='x'))

    def test_text_is_escaped(self):
        svg = progress.render_svg(raised_cents=0, goal_cents=100, purpose='Apple & <Google>')
        ET.fromstring(svg)
        self.assertIn('Apple &amp; &lt;Google&gt;', svg)


if __name__ == '__main__':
    unittest.main()
