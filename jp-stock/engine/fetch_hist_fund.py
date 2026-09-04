#!/usr/bin/env python3
"""Fetch historical annual PER/PBR series from irbank.net.

irbank serves /<code>/per and /<code>/pbr pages with one value per fiscal
year (e.g. 2014-03-31 -> 10.13x). Serial with delay (site rejects
concurrency). Writes to `hist_fund` table: (code, year, per, pbr).

Usage: python3 fetch_hist_fund.py [codes...]   (defaults to sample300 list)
"""
import re
import sqlite3
import sys
import time
import urllib.request
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
DB = ROOT / "data" / "market.sqlite"
UA = {"User-Agent": "Mozilla/5.0", "Referer": "https://irbank.net/"}
DELAY = 1.0


def db():
    c = sqlite3.connect(DB)
    c.execute("PRAGMA journal_mode=WAL")
    return c


def init():
    with db() as c:
        c.execute(
            """CREATE TABLE IF NOT EXISTS hist_fund(
                code TEXT, year INTEGER, per REAL, pbr REAL,
                PRIMARY KEY(code, year)
            ) WITHOUT ROWID"""
        )


def _http_get(code, kind):
    u = f"https://irbank.net/{code}/{kind}"
    last = None
    for attempt in range(4):
        try:
            req = urllib.request.Request(u, headers=UA)
            with urllib.request.urlopen(req, timeout=25) as r:
                return r.read().decode("utf-8", "replace")
        except Exception as e:
            last = e
            code_n = getattr(e, "code", None)
            if code_n is not None and 400 <= code_n < 500 and code_n != 429:
                raise
            time.sleep(2 * (attempt + 1))
    raise last


def series(code, kind):
    h = _http_get(code, kind)
    pairs = re.findall(
        r"<dt>(\d{4})年\d+月\d+日</dt><dd>.*?<span class=\"text\">([^<]+)</span>",
        h, re.S)
    out = {}
    for y, v in pairs:
        num = re.sub(r"[^0-9.]", "", v)
        if num:
            out[int(y)] = float(num)
    return out


def store_rows(rows):
    with db() as c:
        c.executemany(
            "INSERT OR REPLACE INTO hist_fund VALUES(?,?,?,?)", rows)


def main():
    codes = sys.argv[1:] or [
        l.strip() for l in (ROOT / "data" / "sample300.txt").read_text().split() if l.strip()]
    init()
    ok = fail = 0
    for code in codes:
        try:
            per = series(code, "per")
            pbr = series(code, "pbr")
            yrs = sorted(set(per) | set(pbr))
            rows = [(code, y, per.get(y), pbr.get(y)) for y in yrs]
            store_rows(rows)
            ok += 1
            print(f"{code}: {len(yrs)}y PER={sorted(per)[-1] if per else '-'}",
                  file=sys.stderr)
        except Exception as e:
            fail += 1
            print(f"{code}: FAIL {type(e).__name__} {str(e)[:60]}", file=sys.stderr)
        time.sleep(DELAY)
    print(f"done ok={ok} fail={fail}", file=sys.stderr)


if __name__ == "__main__":
    main()
