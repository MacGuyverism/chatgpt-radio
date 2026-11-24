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

instructions = """You are a careful, engaging American radio news writer for a northern Rhode Island commuter.
Use only supplied dated headlines for current-event factual claims and attribute reporting.
Headlines are not full articles. Never invent quotes, details, numbers, market prices,
explanations, political motives, interviews or personal portfolio data.
Avoid padding and repeated claims. Write polished, natural spoken paragraphs only.
No headings, bullet points, markdown or spoken URLs. Balanced, thoughtful and professional.
If evidence is thin, offer clearly labeled general context or useful non-news commentary
without pretending to have verified facts. Do not make up new current events."""

sections = [
    ("Opening headlines", 260, "Lead with an engaging introduction and a fast overview of the strongest stories from all categories."),
    ("Daily Detour", 230, "A creative, original lighthearted multi-line joke, everyday-life observation, or practical reflection. Keep it entertaining and distinct from the news."),
    ("Northern Rhode Island", 550, "Local Burrillville, Pascoag, Woonsocket, Smithfield and surrounding-area headlines. Prioritize genuine local stories and avoid inventing community specifics."),
    ("Rhode Island", 480, "Statewide Rhode Island news with helpful context, keeping local and statewide stories distinct."),
    ("United States", 900, "National news and political developments. Attribute contested positions and explain multiple viewpoints fairly."),
    ("World", 680, "International headlines and conflicts. Avoid asserting developments beyond headline evidence."),
    ("Business and markets", 530, "Business, economy, stocks and IPOs. Do not invent market quotes, personal holdings, returns or price movements."),
    ("Science and technology", 270, "Science and technology headlines, with thoughtful context and clear uncertainty."),
    ("Closing", 100, "A warm and memorable sign-off that briefly looks ahead without promising specific news.")
]
generated = []
for title, target, guidance in sections:
    relevant = headlines if title in ("Opening headlines", "Closing") else [
        h for h in headlines if h["category"] == {
            "Northern Rhode Island": "Northern Rhode Island",
            "Rhode Island": "Rhode Island",
            "United States": "National",
            "World": "World",
            "Business and markets": "Markets",
            "Science and technology": "Science"
        }.get(title, "")]
    if title == "Daily Detour":
        relevant = []
    prompt = ("Broadcast date " + date + ". Write ONLY the " + title +
              " segment, about " + str(target) + " words (within 15 percent). " +
              guidance + " Earlier segment context: " +
              " | ".join(x[0] for x in generated[-2:]) +
              ". Available headline records: " + json.dumps(relevant))
    response = json.loads(ai("chat/completions", {
        "model": "gpt-4.1-mini", "temperature": 0.45, "max_completion_tokens": max(700, int(target * 2.2)),
        "messages": [{"role": "system", "content": instructions},
                     {"role": "user", "content": prompt}]
    }))
    segment = response["choices"][0]["message"]["content"].strip()
    count = len(segment.split())
    print("Section", title, count, "words", flush=True)
    # Ask for a genuine continuation rather than aborting over a small shortfall.
    # Never require padding when the available reporting cannot support it.
    if count < target * 0.85:
        continuation = json.loads(ai("chat/completions", {
            "model": "gpt-4.1-mini", "temperature": 0.4,
            "max_completion_tokens": max(400, int((target - count) * 2.6)),
            "messages": [
                {"role": "system", "content": instructions},
                {"role": "user", "content": (
                    "Continue the " + title + " radio segment with about " +
                    str(max(100, target - count)) + " additional spoken words. " +
                    "Do not repeat any existing sentences or claims. Use only the "
                    "supplied headline records for current facts. If there are no "
                    "more supported facts, use useful clearly identified general "
                    "context or analysis, never invented specifics. Existing segment: " +
                    segment + "\nAvailable records: " + json.dumps(relevant)
                )}
            ]
        }))["choices"][0]["message"]["content"].strip()
        if continuation:
            segment += "\n\n" + continuation
            count = len(segment.split())
            print("Extended section", title, count, "words", flush=True)
    if count < target * 0.55:
        raise RuntimeError("Section remains too short: " + title + ". Aborting before narration.")
    generated.append((title, segment))
script = "\n\n".join(text for _, text in generated)
word_count = len(script.split())
print("Total script words", word_count, flush=True)
(out / "script.txt").write_text(script)
if not 3200 <= word_count <= 4700:
    raise RuntimeError("Script outside safe length bounds; no narration or publish")

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
