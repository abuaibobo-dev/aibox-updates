#!/usr/bin/env python3
"""Market overview feed for the App.

Produces data/market.json:
  indices  : Nikkei (^N225) + TOPIX (1348.T ETF proxy), day & 5d change
  sectors  : per-industry equal-weight day change from the stored universe
  date     : latest trading date
Zero deps; uses stored daily bars for sectors, Yahoo v8 for indices.
"""
import json
import time
import urllib.request
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
DATA = ROOT / "data"

sys_path = str(Path(__file__).resolve().parent)
import sys
sys.path.insert(0, sys_path)
import fetch_prices as fp

UA = {"User-Agent": "Mozilla/5.0 (X11; Linux x86_64)"}

INDICES = [
    {"key": "nikkei", "name": "日经225", "symbol": "%5EN225"},
    {"key": "topix", "name": "TOPIX", "symbol": "1348.T"},
]


def index_snapshot(symbol):
    u = f"https://query1.finance.yahoo.com/v8/finance/chart/{symbol}?range=10d&interval=1d"
    req = urllib.request.Request(u, headers=UA)
    with urllib.request.urlopen(req, timeout=20) as r:
        d = json.load(r)
    res = d["chart"]["result"][0]
    m = res["meta"]
    ts = res.get("timestamp") or []
    cl = res["indicators"]["quote"][0]["close"]
    pts = [(t, c) for t, c in zip(ts, cl) if c is not None]
    if len(pts) < 2:
        return None
    last = pts[-1][1]
    prev_day = pts[-2][1]
    # 5 trading days back
    base5 = pts[-6][1] if len(pts) >= 6 else pts[0][1]
    return {
        "last": last,
        "chg_day": (last / prev_day - 1.0) * 100 if prev_day else None,
        "chg_5d": (last / base5 - 1.0) * 100 if base5 else None,
        "time": time.strftime("%Y-%m-%d %H:%M", time.localtime(pts[-1][0])),
    }


def load_industry_map():
    """code -> industry from prime.csv (source of truth, not stocks.industry
    which sync_codes may wipe on REPLACE)."""
    m = {}
    p = DATA / "prime.csv"
    if p.exists():
        for line in p.read_text(encoding="utf-8").splitlines():
            parts = line.split(",")
            if len(parts) >= 3:
                m[parts[0]] = parts[2]
    return m


def sector_snapshot():
    """Equal-weight % change per industry between the two latest trading days."""
    ind_map = load_industry_map()
    conn = fp.db_conn()
    rows = conn.execute(
        """SELECT code, close,
                  (SELECT c2.close FROM daily c2
                   WHERE c2.code=d.code AND c2.ts<d.ts AND c2.close IS NOT NULL
                   ORDER BY c2.ts DESC LIMIT 1) AS prev
           FROM daily d
           WHERE d.ts=(SELECT MAX(ts) FROM daily WHERE code=d.code)
             AND d.close IS NOT NULL"""
    ).fetchall()
    conn.close()
    agg = {}
    for code, close, prev in rows:
        ind = ind_map.get(code)
        if not (ind and close and prev):
            continue
        chg = close / prev - 1.0
        agg.setdefault(ind, []).append(chg)
    sectors = sorted(
        ({
            "name": ind,
            "chg_day": (sum(v) / len(v)) * 100,
            "count": len(v),
        } for ind, v in agg.items()),
        key=lambda s: -s["chg_day"],
    )
    return sectors


def main():
    indices = []
    for ix in INDICES:
        try:
            snap = index_snapshot(ix["symbol"])
            if snap:
                snap["key"] = ix["key"]
                snap["name"] = ix["name"]
                indices.append(snap)
        except Exception as e:
            print(f"index {ix['key']} failed: {e}", file=sys.stderr)
    sectors = sector_snapshot()
    out = {
        "date": time.strftime("%Y-%m-%d"),
        "generated_at": time.strftime("%Y-%m-%d %H:%M:%S UTC"),
        "indices": indices,
        "sectors": sectors,
    }
    dest = DATA / "market.json"
    dest.write_text(json.dumps(out, ensure_ascii=False, indent=2), encoding="utf-8")
    print(f"wrote {len(indices)} indices, {len(sectors)} sectors -> {dest}")


if __name__ == "__main__":
    main()
