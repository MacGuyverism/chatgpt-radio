"""Verified publisher feeds consulted alongside Google News discovery."""
from concurrent.futures import ThreadPoolExecutor
import datetime as dt
import email.utils
import urllib.parse
import xml.etree.ElementTree as ET

TOPICS = {
    "Northern Rhode Island": '"Burrillville" OR "Pascoag" OR "Woonsocket" news',
    "Rhode Island": '"Rhode Island" news',
    "National": "United States national news politics",
    "World": "international news conflicts diplomacy",
    "Markets": "stock market business economy IPO news",
    "Science": "science technology discoveries news",
}
PREFERRED = {
    "Northern Rhode Island": ["https://www.valleybreeze.com/search/?f=rss&t=article"],
    "Rhode Island": ["https://rhodeislandcurrent.com/feed/"],
    "National": ["https://feeds.foxnews.com/foxnews/politics", "https://feeds.npr.org/1003/rss.xml",
                 "https://feeds.bbci.co.uk/news/world/us_and_canada/rss.xml"],
    "World": ["https://feeds.bbci.co.uk/news/world/rss.xml", "https://feeds.npr.org/1004/rss.xml"],
    "Markets": ["https://feeds.bbci.co.uk/news/business/rss.xml", "https://feeds.npr.org/1006/rss.xml"],
    "Science": ["https://www.nasa.gov/news-release/feed/", "https://www.universetoday.com/feed/",
                "https://feeds.bbci.co.uk/news/science_and_environment/rss.xml",
                "https://feeds.npr.org/1007/rss.xml", "https://feeds.feedburner.com/TheHackersNews"],
}
SUPPLEMENTAL = {
    "World": ["https://www.aljazeera.com/xml/rss/all.xml"],
    "Markets": ["https://feeds.foxnews.com/foxbusiness/latest"],
    "Science": ["https://www.sciencedaily.com/rss/top/science.xml",
                "https://feeds.arstechnica.com/arstechnica/index", "https://techcrunch.com/feed/",
                "https://krebsonsecurity.com/feed/", "https://www.bleepingcomputer.com/feed/",
                "https://www.space.com/feeds/all"],
}


def collect_feeds(http, now):
    sources = [
        (category, url, tier)
        for tier, group in (("preferred", PREFERRED), ("supplemental", SUPPLEMENTAL))
        for category, urls in group.items() for url in urls
    ]
    sources.extend(
        (category, "https://news.google.com/rss/search?" + urllib.parse.urlencode({
            "q": "(" + query + ") when:2d", "hl": "en-US", "gl": "US", "ceid": "US:en"
        }), "discovery")
        for category, query in TOPICS.items()
    )

    def read_feed(source):
        category, url, tier = source
        records = []
        try:
            body = http(url, headers={"User-Agent": "Briefsona/0.0.1",
                                      "Accept": "application/rss+xml, application/xml"}, timeout=15, limit=4_000_001)
            if len(body) > 4_000_000:
                raise ValueError("Oversized feed")
            root = ET.fromstring(body)
            for item in root.findall("./channel/item"):
                title, link, stamp = (item.findtext(key) for key in ("title", "link", "pubDate"))
                if not title or not link or not stamp:
                    continue
                try:
                    age = now - email.utils.parsedate_to_datetime(stamp).astimezone(dt.timezone.utc)
                except (ValueError, TypeError, OverflowError):
                    continue
                if not -3600 <= age.total_seconds() <= 172800:
                    continue
                publisher = item.find("source")
                name = publisher.text if publisher is not None else root.findtext("./channel/title")
                origin = publisher.get("url") if publisher is not None else url
                records.append({"category": category, "title": title.removesuffix(" - " + (name or ""))[:220],
                                "source": name, "publisher_url": origin, "tier": tier,
                                "date": stamp, "url": link})
                if len(records) >= (8 if tier == "discovery" else 4):
                    break
            print("Feed", tier, category, url, len(records), flush=True)
        except Exception as exc:
            print("Feed unavailable", category, url, type(exc).__name__, flush=True)
        return records

    with ThreadPoolExecutor(max_workers=12) as pool:
        return [record for records in pool.map(read_feed, sources) for record in records]
