"""Retrieve accessible publisher articles and group overlapping reports."""
import re
import urllib.parse
import urllib.request
from concurrent.futures import ThreadPoolExecutor

from googlenewsdecoder import gnewsdecoder
from trafilatura import extract

GOOGLE = "news.google.com"
STOP_WORDS = set("a an and are as at be by for from how in into is it of on or over that the their this to was what when will with you your".split())


def canonical(url):
    u = urllib.parse.urlsplit(url)
    args = [(k, v) for k, v in urllib.parse.parse_qsl(u.query)
            if not k.lower().startswith("utm_") and k not in ("fbclid", "gclid")]
    return urllib.parse.urlunsplit((u.scheme, u.netloc.lower(), u.path.rstrip("/"),
                                  urllib.parse.urlencode(sorted(args)), ""))


def open_html(url):
    req = urllib.request.Request(url, headers={"User-Agent": "Briefsona/0.0.1 (news briefing reader)"})
    with urllib.request.urlopen(req, timeout=12) as response:
        if "html" not in response.headers.get("Content-Type", "").lower():
            return None, response.geturl()
        raw = response.read(2_000_001)
        return (raw if len(raw) <= 2_000_000 else None), response.geturl()


def fetch_article(record):
    try:
        url = record["url"]
        if urllib.parse.urlsplit(url).scheme not in ("https", "http"):
            return None
        raw, final_url = open_html(url)
        if urllib.parse.urlsplit(final_url).hostname == GOOGLE:
            resolved = gnewsdecoder(final_url, interval=0)
            url = resolved.get("decoded_url", "")
            parsed = urllib.parse.urlsplit(url)
            if not resolved.get("success") or parsed.scheme not in ("https", "http") or parsed.hostname == GOOGLE:
                return None
            raw, final_url = open_html(url)
        if not raw or urllib.parse.urlsplit(final_url).hostname == GOOGLE:
            return None
        article = extract(raw, url=final_url, include_comments=False, include_tables=False, favor_precision=True)
        if not article or len(article.split()) < 100:
            return None
        return dict(record, url=canonical(final_url), article=article[:12000])
    except Exception as exc:
        print("Article unavailable", record.get("source"), type(exc).__name__, flush=True)
        return None


def words(value):
    return re.findall(r"\w+", value.casefold())


def headline_terms(title):
    return set(words(title)) - STOP_WORDS


def shingles(article):
    text = words(article)
    return set(zip(text, text[1:], text[2:], text[3:], text[4:]))


def overlap(a, b):
    return len(a & b) / min(len(a), len(b)) if a and b else 0


def publisher(record):
    host = urllib.parse.urlsplit(record["url"]).hostname or ""
    return host.removeprefix("www.").removeprefix("m.")


def enrich_articles(records):
    candidates = {}
    for record in records:
        candidates.setdefault(canonical(record["url"]), record)
    with ThreadPoolExecutor(max_workers=12) as pool:
        results = list(pool.map(fetch_article, candidates.values()))
    seen_urls, seen_text = set(), set()
    output = []
    for record in results:
        if record is None:
            continue
        fingerprint = " ".join(words(record["article"]))
        url = record["url"]
        if url in seen_urls or fingerprint in seen_text:
            continue
        seen_urls.add(url)
        seen_text.add(fingerprint)
        output.append(record)
    print("Accessible articles:", len(output), "of", len(candidates), flush=True)
    return output


def cluster_stories(records):
    groups = []
    for record in records:
        text = shingles(record["article"])
        terms = headline_terms(record["title"])
        host = publisher(record)
        for group, signatures in groups:
            match = [(body, other_host) for headline, body, other_host in signatures
                     if overlap(text, body) >= .45
                     or (len(terms & headline) >= 4 and overlap(terms, headline) >= .72)]
            if not match:
                continue
            independent = all(other_host != host and overlap(text, body) < .65
                              for _, body, other_host in signatures)
            signatures.append((terms, text, host))
            if independent and len(group["reporting"]) < 4:
                group["reporting"].append(record)
            break
        else:
            groups.append(({"category": record["category"], "title": record["title"], "reporting": [record]},
                           [(terms, text, host)]))
    return [group for group, _ in groups]
