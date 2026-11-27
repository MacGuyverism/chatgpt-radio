import datetime as dt
import io
import json
import os
from pathlib import Path
import shutil
import subprocess
import tempfile
import unittest
import urllib.error
from unittest.mock import patch

import radio_articles
import radio_production
from radio_audio import validate_chapters
from radio_storage import Releases, schedule


class ArticlesTest(unittest.TestCase):
    def test_google_resolution_extracts_publisher_only(self):
        incoming = "https://news.google.com/rss/articles/abc"
        resolved = "https://publisher.example/story?utm_campaign=example"
        body = b"<html><body><article><p>" + b"Accessible reporting contains public details. " * 30 + b"</p></article></body></html>"
        with patch.object(radio_articles, "open_html", side_effect=[(b"intermediary", incoming), (body, resolved)]), \
             patch.object(radio_articles, "gnewsdecoder", return_value={"success": True, "decoded_url": resolved}):
            article = radio_articles.fetch_article({"url": incoming, "source": "Publisher"})
        self.assertEqual(article["url"], "https://publisher.example/story")
        self.assertIn("Accessible reporting", article["article"])

    def test_rejects_intermediary_and_short_teaser(self):
        incoming = "https://news.google.com/rss/articles/abc"
        with patch.object(radio_articles, "open_html", return_value=(b"teaser", incoming)), \
             patch.object(radio_articles, "gnewsdecoder", return_value={"success": False}):
            self.assertIsNone(radio_articles.fetch_article({"url": incoming}))
        with patch.object(radio_articles, "open_html", return_value=(b"<p>Short teaser only.</p>", "https://publisher.example/story")):
            self.assertIsNone(radio_articles.fetch_article({"url": "https://publisher.example/story"}))

    def test_clustering_preserves_independent_reporting(self):
        def record(host, headline, text):
            return {"url": f"https://{host}/news", "title": headline, "category": "National", "article": text}
        title = "River council approves emergency flood funding Tuesday"
        wire = "The council met on Tuesday to approve funding after the flood damaged several riverside businesses."
        groups = radio_articles.cluster_stories([
            record("first.example", title, wire),
            record("syndicated.example", title, wire),
            record("independent.example", title + " after debate",
                   "Officials allocated five million dollars from reserves. One member opposed the allocation."),
            record("other.example", "Coast guard begins winter air patrol",
                   "Flights are scheduled to start next week from a base on the coast."),
        ])
        self.assertEqual([len(group["reporting"]) for group in groups], [2, 1])
        self.assertIn("independent.example", groups[0]["reporting"][1]["url"])


class FakeStorage:
    def __init__(self):
        self.objects = {}
        self.fail_public = False

    def __call__(self, url, data=None, headers=None, method="GET", **_):
        method = method or "GET"
        if "/list/" in url:
            prefix = json.loads(data)["prefix"] + "/"
            return json.dumps([{"name": k.removeprefix(prefix)} for k in self.objects
                               if k.startswith(prefix)]).encode()
        path = url.split("/chatgpt-radio/", 1)[1]
        if method == "POST":
            if headers["x-upsert"] == "false" and path in self.objects:
                raise urllib.error.HTTPError(url, 409, "duplicate", {}, io.BytesIO(b"duplicate"))
            self.objects[path] = data
        elif method == "DELETE":
            del self.objects[path]
        elif path not in self.objects:
            raise urllib.error.HTTPError(url, 404, "missing", {}, io.BytesIO(b"not found"))
        return b"corrupt" if "/public/" in url and self.fail_public else self.objects.get(path)


class ReleaseTest(unittest.TestCase):
    day = dt.date(2025, 3, 9)

    def setUp(self):
        self.store = FakeStorage()
        self.release = Releases("https://example.supabase.co", "fixture", self.store)

    def test_duplicate_claim_skips_before_production(self):
        self.assertTrue(self.release.claim(self.day))
        self.assertFalse(self.release.claim(self.day))
        env = {"RADIO_EVENT": "schedule", "RADIO_TEST_ONLY": "0",
               "SUPABASE_URL": "https://example.supabase.co", "SUPABASE_SECRET_KEY": "fixture"}
        with patch.dict(os.environ, env), patch.object(radio_production, "Releases") as factory, \
             patch.object(radio_production, "produce") as produce:
            factory.return_value.claim.return_value = False
            radio_production.main()
        produce.assert_not_called()

    def test_schedule_observes_eastern_dst(self):
        for day, utc_hour in ((dt.date(2025, 3, 8), 12), (self.day, 11)):
            start, release = schedule(day)
            self.assertEqual((release - start).total_seconds(), 2700)
            self.assertEqual(release.astimezone(dt.timezone.utc).hour, utc_hour)

    def test_failed_publish_preserves_previous_and_retention_handles_pairs(self):
        previous = {"date": "2025-03-08", "path": "episodes/chatgpt-radio-2025-03-08.mp3"}
        self.release.write("latest.json", previous)
        self.release.write("archive.json", {"episodes": [previous]})
        for name in ("2025-03-02.mp3", "2025-03-02.json", "2025-03-03.mp3", "2025-03-08.mp3"):
            self.store.objects["episodes/chatgpt-radio-" + name] = b"fixture"
        self.store.fail_public = True
        with tempfile.TemporaryDirectory() as tmp:
            final = Path(tmp) / "broadcast.mp3"
            final.write_bytes(b"audio")
            final.with_suffix(".json").write_text("{}")
            with self.assertRaises(RuntimeError):
                self.release.publish(self.day, final, 10, schedule(self.day)[1])
            self.assertEqual(self.release.read("latest.json"), previous)
            self.assertEqual(self.release.read("archive.json")["episodes"], [previous])
            self.store.fail_public = False
            self.release.publish(self.day, final, 10, schedule(self.day)[1])
        self.assertEqual(len(self.release.read("archive.json")["episodes"]), 2)
        self.assertNotIn("episodes/chatgpt-radio-2025-03-02.mp3", self.store.objects)
        self.assertNotIn("episodes/chatgpt-radio-2025-03-02.json", self.store.objects)
        self.assertIn("episodes/chatgpt-radio-2025-03-03.mp3", self.store.objects)


@unittest.skipUnless(shutil.which("ffmpeg") and shutil.which("ffprobe"), "FFmpeg required")
class AudioTest(unittest.TestCase):
    def test_complete_short_broadcast_with_chapters(self):
        with tempfile.TemporaryDirectory() as tmp:
            tone = subprocess.check_output([
                "ffmpeg", "-v", "error", "-f", "lavfi", "-i", "sine=440:duration=2",
                "-ar", "24000", "-ac", "1", "-codec:a", "libmp3lame", "-f", "mp3", "-"
            ])
            answers = iter([
                "Good morning. Here is today's regional update.",
                "Here is a short detour from the headlines.",
                "The full council approved emergency funding on Tuesday.",
                "Thank you for listening to Briefsona."
            ])

            def ai(endpoint, _payload):
                if endpoint == "audio/speech":
                    return tone
                return json.dumps({"choices": [{"message": {"content": next(answers)}}]}).encode()

            with patch.object(radio_production, "ai", side_effect=ai), \
                 patch.object(radio_production, "collect_feeds", return_value=[]), \
                 patch.object(radio_production, "enrich_articles", return_value=[{
                     "category": "National", "title": "Council approves funding",
                     "url": "https://news.example/example", "article": "The council approved funding Tuesday."
                 }]), patch.dict(os.environ, {"RADIO_BUILD_DIR": tmp}):
                audio, duration = radio_production.produce(dt.date(2025, 3, 9))
            metadata = validate_chapters(audio)
            self.assertLess(duration, 60)
            self.assertEqual([c["title"] for c in metadata["chapters"]],
                             ["Opening headlines", "Daily Detour", "United States", "Closing"])
            self.assertEqual(metadata["chapters"][0]["start_ms"], 0)
            self.assertEqual(metadata["chapters"][-1]["end_ms"], metadata["duration_ms"])

    def test_profile_run_uses_one_order_for_script_audio_and_chapters(self):
        profile = {"segments": [
            {"name": "Intro"}, {"name": "Traffic", "random_position": True},
            {"name": "Daily Detour"}, {"name": "Weather", "random_position": True}, {"name": "Outro"},
        ]}
        expected = ["Intro", "Weather", "Daily Detour", "Traffic", "Outro"]
        with tempfile.TemporaryDirectory() as tmp:
            tone = subprocess.check_output([
                "ffmpeg", "-v", "error", "-f", "lavfi", "-i", "sine=440:duration=1",
                "-ar", "24000", "-ac", "1", "-codec:a", "libmp3lame", "-f", "mp3", "-"
            ])
            spoken = []
            replies = iter(row["name"] for row in profile["segments"])

            def ai(endpoint, payload):
                if endpoint == "audio/speech":
                    spoken.append(payload["input"])
                    return tone
                return json.dumps({"choices": [{"message": {"content": next(replies)}}]}).encode()

            with patch.object(radio_production, "ai", side_effect=ai), \
                 patch.object(radio_production.random, "shuffle", side_effect=list.reverse) as draw, \
                 patch.object(radio_production, "collect_feeds", return_value=[]), \
                 patch.object(radio_production, "enrich_articles", return_value=[{
                     "category": "National", "title": "Transit report", "url": "https://example.org/story",
                     "article": "The council announced an update."
                 }]), patch.dict(os.environ, {"RADIO_BUILD_DIR": tmp, "RADIO_STATION_PROFILE": json.dumps(profile)}):
                audio, _ = radio_production.produce(dt.date(2025, 3, 9))
            draw.assert_called_once()
            self.assertEqual((Path(tmp) / "script.txt").read_text().split("\n\n"), expected)
            self.assertEqual(spoken, expected)
            self.assertEqual([chapter["title"] for chapter in validate_chapters(audio)["chapters"]], expected)


if __name__ == "__main__":
    unittest.main()
