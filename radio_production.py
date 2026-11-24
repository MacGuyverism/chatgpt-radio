import datetime as dt
import email.utils
import json
import os
from pathlib import Path
import re
import subprocess
import urllib.parse
import urllib.request
import xml.etree.ElementTree as ET
from zoneinfo import ZoneInfo

date = dt.datetime.now(ZoneInfo("America/New_York")).date().isoformat()
out = Path("build")
out.mkdir(exist_ok=True)
key = os.environ["OPENAI_API_KEY"]
base = os.environ["SUPABASE_URL"].rstrip("/")
secret = os.environ["SUPABASE_SECRET_KEY"]
topics = {
    "Northern Rhode Island": '"Burrillville" OR "Pascoag" OR "Woonsocket" news',
    "Rhode Island": '"Rhode Island" news',
    "National": 'United States national news politics',
    "World": 'international news conflicts diplomacy',
    "Markets": 'stock market business economy IPO news',
    "Science": 'science technology discoveries news'
}

def http(url, data=None, headers=None, method=None, timeout=120):
    req = urllib.request.Request(url, data=data, headers=headers or {}, method=method)
    with urllib.request.urlopen(req, timeout=timeout) as response:
        return response.read()

def ai(endpoint, payload):
    return http("https://api.openai.com/v1/" + endpoint, json.dumps(payload).encode(),
                {"Authorization": "Bearer " + key, "Content-Type": "application/json"}, "POST", 180)

headlines = []
now = dt.datetime.now(dt.timezone.utc)
for category, query in topics.items():
    url = "https://news.google.com/rss/search?" + urllib.parse.urlencode({
        "q": "(" + query + ") when:2d", "hl": "en-US", "gl": "US", "ceid": "US:en"})
    try:
        root = ET.fromstring(http(url, timeout=25))
        count = 0
        for item in root.findall("./channel/item"):
            title, link = item.findtext("title"), item.findtext("link")
            stamp = item.findtext("pubDate")
            if not title or not link or not stamp:
                continue
            age = (now - email.utils.parsedate_to_datetime(stamp).astimezone(dt.timezone.utc)).total_seconds()
            if age < -3600 or age > 172800:
                continue
            headlines.append({"category": category, "title": title[:220],
                              "source": item.findtext("source"), "date": stamp, "url": link})
            count += 1
            if count == 12:
                break
        print(category, count, flush=True)
    except Exception as exc:
        print("Feed failed", category, type(exc).__name__, str(exc), flush=True)
if len(headlines) < 20:
    raise RuntimeError("Too few recent headlines; previous broadcast remains available")
(out / "sources.json").write_text(json.dumps(headlines, indent=2))

instructions = """Write a polished 27-29 minute professional radio broadcast for a Rhode Island commuter.
Use ONLY the provided headline records for current factual claims, and attribute the reporting.
Headlines are not full articles: do not invent details, numbers, quotes, market prices or causes.
Do not claim knowledge of personal portfolio holdings. Balance differing political perspectives.
Opening headlines, then a 1-2 minute Daily Detour featuring an ORIGINAL lighthearted joke or
practical reflection (no unverified historical/scientific claims), then Northern Rhode Island,
Rhode Island, US, world, markets and science, then a warm closing.
Natural spoken paragraphs, no markdown, no headings or URLs read aloud. Avoid repeated filler.
Aim for 3,900-4,050 spoken words, at approximately 140 words per minute.
Return only the full script."""
reply = json.loads(ai("chat/completions", {
    "model": "gpt-4.1-mini", "temperature": 0.5, "max_completion_tokens": 7500,
    "messages": [{"role": "system", "content": instructions},
                 {"role": "user", "content": "Broadcast date " + date + ". Headlines: " + json.dumps(headlines)}]
}))
script = reply["choices"][0]["message"]["content"].strip()
word_count = len(script.split())
print("Script words", word_count, flush=True)
(out / "script.txt").write_text(script)
if not 3000 <= word_count <= 4800:
    raise RuntimeError("Script outside safe length bounds")

chunks = []
current = ""
for sentence in re.split(r"(?<=[.!?])\s+", script):
    if len(sentence) > 2800:
        raise RuntimeError("Oversized TTS sentence")
    if current and len(current) + len(sentence) + 1 > 2800:
        chunks.append(current)
        current = ""
    current = (current + " " + sentence).strip()
if current:
    chunks.append(current)

for index, chunk in enumerate(chunks, 1):
    audio = ai("audio/speech", {
        "model": "gpt-4o-mini-tts", "voice": "nova", "input": chunk,
        "instructions": "Warm, confident, natural female-sounding American radio news presenter with a slightly brighter, higher-register speaking voice. Conversational, upbeat but professional. Clear enunciation and natural pauses. Not squeaky or cartoonish.",
        "response_format": "mp3"})
    if len(audio) < 1000:
        raise RuntimeError("Invalid speech output")
    (out / ("part-%03d.mp3" % index)).write_bytes(audio)
    print("Narrated", index, "of", len(chunks), flush=True)

parts = sorted(out.glob("part-*.mp3"))
(out / "concat.txt").write_text("".join("file '" + p.name + "'\n" for p in parts))
final = out / ("chatgpt-radio-" + date + ".mp3")
subprocess.run(["ffmpeg", "-hide_banner", "-loglevel", "error", "-y",
                "-f", "concat", "-safe", "0", "-i", str(out / "concat.txt"),
                "-ac", "1", "-ar", "24000", "-c:a", "libmp3lame",
                "-b:a", "64k", str(final)], check=True)
duration = float(subprocess.check_output(["ffprobe", "-v", "error",
    "-show_entries", "format=duration", "-of", "default=noprint_wrappers=1:nokey=1",
    str(final)], text=True).strip())
print("Duration minutes", duration / 60, flush=True)
if not 1620 <= duration <= 1740:
    raise RuntimeError("Outside 27-29 minutes; not publishing")

headers = {"apikey": secret, "Authorization": "Bearer " + secret}
def storage(path, data=None, content_type="application/json", method="GET"):
    h = dict(headers)
    if data is not None:
        h.update({"Content-Type": content_type, "x-upsert": "true"})
    return http(base + "/storage/v1/object/chatgpt-radio/" + path, data, h, method)

previous = None
try:
    previous = json.loads(storage("latest.json"))
except urllib.error.HTTPError as exc:
    if exc.code != 404:
        raise
path = "episodes/" + final.name
payload = final.read_bytes()
storage(path, payload, "audio/mpeg", "POST")
public = base + "/storage/v1/object/public/chatgpt-radio/" + path
if len(http(public)) != len(payload):
    raise RuntimeError("Upload verification failed")
metadata = {"date": date, "path": path, "audio_url": public,
            "duration_seconds": round(duration, 1),
            "published_at": dt.datetime.now(dt.timezone.utc).isoformat()}
storage("latest.json", json.dumps(metadata).encode(), "application/json", "POST")
print("PUBLISHED", public, flush=True)
if previous and previous.get("path") != path:
    old = previous.get("path", "")
    if re.fullmatch(r"episodes/chatgpt-radio-\d{4}-\d{2}-\d{2}\.mp3", old):
        try:
            storage(old, method="DELETE")
            print("Deleted previous broadcast", old)
        except Exception as exc:
            print("WARNING: previous episode cleanup failed:", exc)
