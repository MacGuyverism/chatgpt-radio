import re
import urllib.parse
import urllib.request
from concurrent.futures import ThreadPoolExecutor
from trafilatura import extract

def canonical(url):
    u = urllib.parse.urlsplit(url)
    args = [(k,v) for k,v in urllib.parse.parse_qsl(u.query) if not k.lower().startswith("utm_") and k not in ("fbclid","gclid")]
    return urllib.parse.urlunsplit((u.scheme,u.netloc.lower(),u.path.rstrip("/"),urllib.parse.urlencode(sorted(args)),""))

def fetch_article(record):
    try:
        url = record["url"]
        if urllib.parse.urlsplit(url).scheme not in ("https","http"):
            return None
        req = urllib.request.Request(url, headers={"User-Agent":"Briefsona/0.0.1 (news briefing reader)"})
        with urllib.request.urlopen(req, timeout=12) as response:
            if "html" not in response.headers.get("Content-Type","").lower():
                return None
            raw = response.read(2_000_001)
            if len(raw)>2_000_000:
                return None
            final_url = response.geturl()
        if urllib.parse.urlsplit(final_url).hostname == "news.google.com":
            return None
        article = extract(raw, url=final_url, include_comments=False, include_tables=False, favor_precision=True)
        if not article or len(article.split()) < 100:
            return None
        return dict(record, url=canonical(final_url), article=article[:12000])
    except Exception as exc:
        print("Article unavailable", record.get("source"), type(exc).__name__, flush=True)
        return None

def enrich_articles(records):
    seen = set()
    unique = []
    for r in records:
        key = canonical(r["url"])
        if key not in seen:
            seen.add(key)
            unique.append(r)
    with ThreadPoolExecutor(max_workers=8) as pool:
        results = list(pool.map(fetch_article, unique))
    seen.clear()
    output = []
    for r in results:
        if r is None:
            continue
        fingerprint = re.sub(r"\W+", " ", r["article"].lower()).strip()
        key = canonical(r["url"])
        if key in seen or fingerprint in seen:
            continue
        seen.update((key,fingerprint))
        output.append(r)
    print("Accessible articles:", len(output), "of", len(unique), flush=True)
    return output
