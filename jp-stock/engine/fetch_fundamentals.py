#!/usr/bin/env python3
"""Fetch fundamental/valuation data from irbank.net (free Japanese source).

irbank serves stock pages without login. Serial fetch with a delay (the site
rejects concurrency). Only the given candidate codes are fetched to keep it
fast. Persists into the `fundamentals` table.
"""
import re
import sqlite3
import sys
import time
import urllib.request
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
DB = ROOT / "data" / "market.sqlite"
UA = {"User-Agent": "Mozilla/5.0 (Windows NT 10.0; Win64; x64)",
      "Accept-Language": "ja", "Referer": "https://irbank.net/"}
DELAY = 1.2


def db_conn():
    conn = sqlite3.connect(DB)
    conn.execute("PRAGMA journal_mode=WAL")
    return conn


def init_db():
    with db_conn() as c:
        c.execute(
            """
            CREATE TABLE IF NOT EXISTS fundamentals(
                code TEXT PRIMARY KEY,
                mcap REAL, per REAL, per_f REAL, pbr REAL,
                div_yield REAL, roe REAL, roe_f REAL,
                eps REAL, bps REAL, equity_ratio REAL,
                updated TEXT
            )
            """
        )


def num(v):
    """Parse '9.59倍', '45兆4925億', '3.21% (100)', '295.25...' -> float or None."""
    if not v:
        return None
    v = v.split("<")[0].strip().replace(",", "")
    if not v:
        return None
    # Japanese big-number notation: [N兆][N億][N] etc.
    def part(unit):
        m = re.search(r"([0-9.]+)" + unit, v)
        return float(m.group(1)) if m else 0.0
    if "兆" in v or "億" in v:
        total = part("兆") * 1e12 + part("億") * 1e8 + part("万") * 1e4
        # digits after 億's 万-less tail already handled; crude but fine for scale
        tail = re.search(r"億([0-9.]+)", v)
        if tail:
            total += float(tail.group(1))
        return total if total else None
    m = re.search(r"[-+]?[0-9][0-9.]*", v)
    return float(m.group(0)) if m else None


def fetch_page(code):
    req = urllib.request.Request(f"https://irbank.net/{code}", headers=UA)
    with urllib.request.urlopen(req, timeout=20) as r:
        return r.read().decode("utf-8", "replace")


def parse_page(html):
    idxs = [m.start() for m in re.finditer(r'id="c_Valuation"', html)]
    i = idxs[-1] if idxs else html.find("株式指標")
    block = html[i:i + 5000]
    out = {}
    for k, v in re.findall(r"<dt>(.*?)</dt>\s*<dd>(.*?)</dd>", block, re.S):
        key = re.sub(r"\s+", " ", re.sub(r"<[^>]+>", "", k)).replace("\u2009", "").strip()
        vt = re.search(r'class="text">(.*?)</span>', v, re.S)
        out[key] = re.sub(r"\s+", " ", vt.group(1)).strip() if vt else ""
    return out


def get_one(code):
    html = fetch_page(code)
    d = parse_page(html)
    row = {
        "code": code,
        "mcap": num(d.get("時価総額", "")),
        "per": num(d.get("PER（連）", "")),
        "per_f": num(d.get("PER（連）予", "")),
        "pbr": num(d.get("PBR（連）", "")),
        "div_yield": num(d.get("配当利回り 予", "")),
        "roe": num(d.get("ROE（連）", "")),
        "roe_f": num(d.get("ROE（連）予", "")),
        "eps": num(d.get("EPS（連）", "")),
        "bps": num(d.get("BPS（連）", "")),
        "equity_ratio": num(d.get("株主資本比率（連）", "")),
    }
    return row, d


def store(row):
    with db_conn() as c:
        c.execute(
            """INSERT OR REPLACE INTO fundamentals
               (code,mcap,per,per_f,pbr,div_yield,roe,roe_f,eps,bps,equity_ratio,updated)
               VALUES(?,?,?,?,?,?,?,?,?,?,?,?)""",
            (row["code"], row["mcap"], row["per"], row["per_f"], row["pbr"],
             row["div_yield"], row["roe"], row["roe_f"], row["eps"], row["bps"],
             row["equity_ratio"], time.strftime("%Y-%m-%d %H:%M:%S")),
        )


def main():
    codes = sys.argv[1:] or [
        l.split(",")[0] for l in (ROOT / "data" / "prime.csv").read_text(encoding="utf-8").splitlines() if l.strip()
    ]
    init_db()
    ok = fail = 0
    for code in codes:
        try:
            row, raw = get_one(code)
            store(row)
            ok += 1
            print(f"{code}: PER={row['per']} PBR={row['pbr']} DY={row['div_yield']} "
                  f"ROE={row['roe']} mcap={row['mcap']}", file=sys.stderr)
        except Exception as e:
            fail += 1
            print(f"{code}: FAIL {type(e).__name__} {str(e)[:80]}", file=sys.stderr)
        time.sleep(DELAY)
    print(f"done ok={ok} fail={fail}", file=sys.stderr)


if __name__ == "__main__":
    main()
