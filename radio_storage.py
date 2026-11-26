"""Supabase release state and public archive."""
import datetime as dt
import hashlib
import json
import time
import urllib.error
import urllib.parse
import uuid
from zoneinfo import ZoneInfo

EASTERN = ZoneInfo("America/New_York")
UTC = dt.timezone.utc


def schedule(day):
    release = dt.datetime.combine(day, dt.time(7), EASTERN)
    return release - dt.timedelta(minutes=45), release


class Releases:
    def __init__(self, base, secret, http):
        self.base = base.rstrip("/") + "/storage/v1/object/"
        self.headers = {"apikey": secret, "Authorization": "Bearer " + secret}
        self.http = http

    def public(self, path):
        return self.base + "public/chatgpt-radio/" + path

    def object(self, path, data=None, kind="application/json", method="GET", upsert=True):
        headers = dict(self.headers)
        if data is not None:
            headers.update({"Content-Type": kind, "x-upsert": str(upsert).lower(), "cache-control": "0"})
        return self.http(self.base + "chatgpt-radio/" + path, data, headers, method)

    def read(self, path, default=None):
        try:
            return json.loads(self.object(path))
        except urllib.error.HTTPError as exc:
            body = exc.read().decode("utf-8", errors="replace").lower()
            if exc.code == 404 or (exc.code == 400 and any(
                marker in body for marker in ("not found", "not_found", "no such object")
            )):
                return default
            raise

    def write(self, path, value, upsert=True):
        self.object(path, json.dumps(value).encode(), method="POST", upsert=upsert)

    def claim(self, day):
        if (self.read("latest.json") or {}).get("date") == str(day):
            return False
        try:
            self.write(f"claims/{day}.json", {"date": str(day)}, upsert=False)
        except urllib.error.HTTPError as exc:
            body = exc.read().decode("utf-8", errors="replace").lower()
            if exc.code == 409 or (exc.code == 400 and "duplicate" in body):
                return False
            raise
        return True

    def status(self, day, state):
        start, release = schedule(day)
        next_start, next_release = schedule(day + dt.timedelta(days=1))
        self.write("status.json", {
            "station_id": "morning-commute", "date": str(day), "state": state,
            "heartbeat_at": dt.datetime.now(UTC).isoformat(),
            "production_at": start.isoformat(), "release_at": release.isoformat(),
            "next_production_at": next_start.isoformat(), "next_release_at": next_release.isoformat(),
        })

    def publish(self, day, final, duration, release):
        while (seconds := (release - dt.datetime.now(UTC)).total_seconds()) > 0:
            time.sleep(min(seconds, 15))
        path = f"episodes/chatgpt-radio-{day}-{uuid.uuid4().hex}.mp3"
        chapters = path[:-4] + ".json"
        audio = final.read_bytes()
        chapter_data = final.with_suffix(".json").read_bytes()
        for name, data, kind in (
            (path, audio, "audio/mpeg"), (chapters, chapter_data, "application/json")
        ):
            self.object(name, data, kind, "POST", upsert=False)
            if self.http(self.public(name)) != data:
                raise RuntimeError(f"Upload verification failed for {name}")
        entry = {
            "station_id": "morning-commute", "date": str(day), "path": path,
            "audio_url": self.public(path), "chapter_url": self.public(chapters),
            "chapter_sha256": hashlib.sha256(chapter_data).hexdigest(),
            "sha256": hashlib.sha256(audio).hexdigest(), "bytes": len(audio),
            "duration_seconds": round(duration, 3), "published_at": dt.datetime.now(UTC).isoformat(),
        }
        previous = self.read("latest.json")
        archive = self.read("archive.json", {"episodes": []})["episodes"]
        if previous and all(e["path"] != previous["path"] for e in archive):
            archive.append(previous)
        cutoff = day - dt.timedelta(days=6)
        current = [entry] + [e for e in archive if e["date"] >= str(cutoff)]
        self.write("archive.json", {"schema_version": 1, "episodes": current})
        self.write("latest.json", entry)
        self.prune(day)

    def list_objects(self, prefix):
        offset = 0
        while True:
            data = json.dumps({"prefix": prefix, "offset": offset, "limit": 100,
                               "sortBy": {"column": "name", "order": "asc"}}).encode()
            rows = json.loads(self.http(self.base + "list/chatgpt-radio", data, {
                **self.headers, "Content-Type": "application/json"
            }, "POST"))
            yield from (row["name"] for row in rows)
            if len(rows) < 100:
                return
            offset += len(rows)

    def prune(self, day):
        import re
        try:
            cutoff = str(day - dt.timedelta(days=6))
            previous = self.read("latest.json") or {}
            protected = previous.get("path", "")
            archive = self.read("archive.json", {"schema_version": 1, "episodes": []})
            remaining = [e for e in archive["episodes"] if e["date"] >= cutoff or e["path"] == protected]
            if remaining != archive["episodes"]:
                self.write("archive.json", dict(archive, episodes=remaining))
            for prefix, pattern in (
                ("episodes", r"chatgpt-radio-(\d{4}-\d{2}-\d{2})(?:-[a-f0-9]{32})?\.(?:mp3|json)"),
                ("claims", r"(\d{4}-\d{2}-\d{2})\.json")
            ):
                for name in list(self.list_objects(prefix)):
                    match = re.fullmatch(pattern, name)
                    path = f"{prefix}/{name}"
                    if match and match[1] < cutoff and path not in (protected, protected[:-4] + ".json"):
                        self.object(urllib.parse.quote(path), method="DELETE")
        except Exception as exc:
            print("Retention cleanup will be retried:", type(exc).__name__, flush=True)
