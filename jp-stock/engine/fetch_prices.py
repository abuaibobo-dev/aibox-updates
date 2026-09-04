#!/usr/bin/env python3
"""Daily OHLCV history for the Prime universe via Yahoo Finance v8 chart.

Zero deps. Stores into data/market.sqlite. Resume-friendly: stocks already
up-to-date (last bar is today) are skipped.
"""
import json
import sqlite3
import sys
import time
import urllib.request
from concurrent.futures import ThreadPoolExecutor, as_completed
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
DATA = ROOT / "data"
DB = DATA / "market.sqlite"
UA = {"User-Agent": "Mozilla/5.0 (X11; Linux x86_64)"}
RANGE = "10y"
HOSTS = ["query1.finance.yahoo.com", "query2.finance.yahoo.com"]


def db_conn():
    conn = sqlite3.connect(DB)
    conn.execute("PRAGMA journal_mode=WAL")
    return conn


def init_db():
    with db_conn() as c:
        c.executescript(
            """
            CREATE TABLE IF NOT EXISTS stocks(
                code TEXT PRIMARY KEY,
                name TEXT,
                industry TEXT DEFAULT '',
                last_price REAL,
                high52 REAL, low52 REAL,
                updated TEXT
            );
            CREATE TABLE IF NOT EXISTS daily(
                code TEXT, ts INTEGER, open REAL, high REAL,
                low REAL, close REAL, volume INTEGER,
                PRIMARY KEY(code, ts)
            ) WITHOUT ROWID;
            CREATE INDEX IF NOT EXISTS idx_daily_code ON daily(code);
            """
        )
        # migrate older DBs that lack the industry column
        cols = [r[1] for r in c.execute("PRAGMA table_info(stocks)")]
        if "industry" not in cols:
            c.execute("ALTER TABLE stocks ADD COLUMN industry TEXT DEFAULT ''")


def _fetch(code, host):
    u = f"https://{host}/v8/finance/chart/{code}.T?range={RANGE}&interval=1d"
    req = urllib.request.Request(u, headers=UA)
    with urllib.request.urlopen(req, timeout=20) as r:
        return json.load(r)


def fetch_one(code):
    last_err = None
    for host in HOSTS:
        try:
            d = _fetch(code, host)
            res = d["chart"]["result"]
            if not res:
                return code, "EMPTY", None
            r = res[0]
            m = r["meta"]
            ts = r.get("timestamp") or []
            q = r["indicators"]["quote"][0]
            closes, vols = q.get("close") or [], q.get("volume") or []
            bars = []
            for i, t in enumerate(ts):
                bars.append((code, t, q["open"][i], q["high"][i],
                             q["low"][i], closes[i], vols[i] or 0))
            info = (m.get("longName") or m.get("shortName") or "",
                    m.get("regularMarketPrice"),
                    m.get("fiftyTwoWeekHigh"), m.get("fiftyTwoWeekLow"),
                    time.strftime("%Y-%m-%d %H:%M:%S"))
            return code, "OK", (bars, info)
        except urllib.error.HTTPError as e:
            last_err = f"HTTP{e.code}"
            if e.code == 404:
                return code, "404", None
        except Exception as e:
            last_err = f"{type(e).__name__}"
    return code, f"ERR:{last_err}", None


def up_to_date(conn, code, today_ts):
    row = conn.execute(
        "SELECT MAX(ts) FROM daily WHERE code=?", (code,)
    ).fetchone()
    return row and row[0] and abs(row[0] - today_ts) < 86400 * 2


def load_industries():
    csvf = DATA / "prime.csv"
    if not csvf.exists():
        return {}
    out = {}
    for line in csvf.read_text(encoding="utf-8").splitlines():
        parts = line.split(",")
        if len(parts) >= 3:
            out[parts[0]] = parts[2]
    return out


def sync_codes(codes, workers=5, today=None, force=False):
    today_ts = today or int(time.time())
    ind = load_industries()
    init_db()
    conn = db_conn()
    if force:
        todo = codes
    else:
        todo = [c for c in codes if not up_to_date(conn, c, today_ts)]
    done = len(codes) - len(todo)
    print(f"{done}/{len(codes)} already fresh, fetching {len(todo)}", file=sys.stderr)
    ok = err = 0
    with ThreadPoolExecutor(max_workers=workers) as ex:
        futs = {ex.submit(fetch_one, c): c for c in todo}
        for i, fut in enumerate(as_completed(futs), 1):
            code, status, payload = fut.result()
            if status == "OK":
                bars, (name, last, hi52, lo52, _upd) = payload
                with db_conn() as c:
                    c.executemany(
                        "INSERT OR REPLACE INTO daily VALUES(?,?,?,?,?,?,?)", bars
                    )
                    c.execute(
                        "INSERT OR REPLACE INTO stocks(code,name,industry,last_price,high52,low52,updated) "
                        "VALUES(?,?,?,?,?,?,?)",
                        (code, name, ind.get(code, ""), last, hi52, lo52,
                         time.strftime("%Y-%m-%d %H:%M:%S")),
                    )
                ok += 1
            else:
                err += 1
                print(f"  [{status}] {code}", file=sys.stderr)
            if i % 200 == 0:
                print(f"  progress {i}/{len(todo)}", file=sys.stderr)
    # backfill industry into stocks table
    with db_conn() as c:
        for code, industry in ind.items():
            c.execute("UPDATE stocks SET industry=? WHERE code=?", (industry, code))
    conn.close()
    print(f"OK {ok}, failed {err}", file=sys.stderr)
    return ok, err


def main():
    import os
    csvf = DATA / "prime.csv"
    codes = [l.split(",")[0] for l in csvf.read_text(encoding="utf-8").splitlines() if l.strip()]
    sync_codes(codes, force=os.environ.get("FORCE") == "1")


if __name__ == "__main__":
    main()
