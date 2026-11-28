import json
import unittest
from pathlib import Path

from radio_preview import FIXTURES, generate_preview
from radio_production import finalize_shuffled_introductions, station_sections


class ReporterBackendTest(unittest.TestCase):
    def test_published_fixtures_and_master_match_clients(self):
        root = Path(__file__).resolve().parents[1]
        edge = root / "supabase/functions/reporter-preview"
        self.assertEqual(len(FIXTURES), 10)
        self.assertEqual(FIXTURES, json.loads((edge / "headlines.json").read_text()))
        self.assertEqual(FIXTURES, json.loads((root / "android/app/src/main/assets/preview-headlines.json").read_text()))
        self.assertEqual((root / "master/Master.md").read_text(), json.loads((edge / "master.json").read_text()))

    def test_invalid_voice_rejected_before_generation(self):
        with self.assertRaises(ValueError):
            generate_preview(FIXTURES[0]["headline"], "missing", 1, "Calm", request=lambda *_: self.fail())

    def test_introductions_use_final_order_and_default_reporter(self):
        profile = {"segments": [{"name": "Opening"}, {"name": "Technology", "reporter_id": "tech"},
                                {"name": "Business", "reporter_id": "gone"}, {"name": "Closing"}],
                   "reporters": {"samantha": {"name": "Samantha", "voice": "nova"},
                                 "tech": {"name": "Emily", "voice": "coral"}}}
        opening, tech, business, closing = station_sections(profile)
        reports = [(section, section.title) for section in (opening, business, tech, closing)]
        scripts = [text for _, text in finalize_shuffled_introductions(reports, profile)]
        self.assertEqual(scripts, ["This is Samantha. Opening", "Business", "This is Emily. Technology", "Closing"])
