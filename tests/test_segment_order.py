import unittest
from unittest.mock import patch

from radio_production import broadcast_order, station_sections


class SegmentOrderTest(unittest.TestCase):
    def test_only_completed_random_slots_can_move_each_run(self):
        profile = {"segments": [
            {"name": "Hello", "random_position": True}, {"name": "Transit", "random_position": True},
            {"name": "Inactive", "enabled": False, "random_position": True}, {"name": "Local"},
            {"name": "Skipped", "random_position": True}, {"name": "Weather", "random_position": True},
            {"name": "Goodbye", "random_position": True},
        ]}
        sections = station_sections(profile)
        reports = [(s, s.title) for s in sections if s.title != "Skipped"]
        recorded = ["Hello", "Transit", "Local", "Weather", "Goodbye"]
        with patch("radio_production.random.shuffle", side_effect=list.reverse) as draw:
            self.assertEqual([text for _, text in broadcast_order(reports)],
                             ["Hello", "Weather", "Local", "Transit", "Goodbye"])
            broadcast_order(reports)
        self.assertEqual(draw.call_count, 2)
        self.assertEqual([text for _, text in reports], recorded)

    def test_fixed_default_and_single_random_never_shuffle(self):
        for random_position in (False, True):
            profile = {"segments": [{"name": "Opening"}, {"name": "One", "random_position": random_position},
                                    {"name": "Two"}, {"name": "Closing"}]}
            reports = [(s, s.title) for s in station_sections(profile)]
            with patch("radio_production.random.shuffle") as draw:
                self.assertEqual(broadcast_order(reports), reports)
                draw.assert_not_called()
        self.assertFalse(any(s.random_position for s in station_sections(None)))

    def test_legacy_random_station_respects_explicit_segment_settings(self):
        profile = {"random": True, "segments": [
            {"name": "Start"}, {"name": "First"}, {"name": "Second", "random_position": False}, {"name": "End"}]}
        self.assertEqual([s.random_position for s in station_sections(profile)], [False, True, False, False])
