#!/usr/bin/env python3
"""Recommendation tracking: accumulate daily picks and mark-to-market.

Reads data/daily.json (today's picks) and appends to data/history.json keyed by
date (idempotent — re-running the same date overwrites, never duplicates).
For every historical pick, looks up the latest stored close to compute
mark-to-market return since the pick date.

Output data/history.json:
  { "updated": ..., "days": [ { "date","picks":[ {code,name,price,...} ] }, ... ],
    "latest_prices": { code: {price, ret_since_pick_date? } } }
"""
import json
import sys
import time
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
DATA = ROOT / "data"
sys.path.insert(0, str(Path(__file__).resolve().parent))
import fetch_prices as fp


def load_json(path):
    if path.exists():
        try:
            return json.loads(path.read_text(encoding="utf-8"))
        except Exception:
            return None
    return None


def latest_close(code):
    conn = fp.db_conn()
    row = conn.execute(
        "SELECT close FROM daily WHERE code=? AND close IS NOT NULL "
        "ORDER BY ts DESC LIMIT 1", (code,)
    ).fetchone()
    conn.close()
    return row[0] if row else None


def main():
    daily_path = DATA / "daily.json"
    hist_path = DATA / "history.json"
    daily = load_json(daily_path)
    if not daily or not daily.get("picks"):
        print("daily.json missing or empty; nothing to track", file=sys.stderr)
        return

    today = daily["date"]
    hist = load_json(hist_path) or {"days": []}
    days = hist["days"]

    # pick payload we keep for tracking
    today_picks = []
    for p in daily["picks"]:
        today_picks.append({
            "code": p.get("code"),
            "name": p.get("name"),
            "industry": p.get("industry"),
            "price": p.get("price"),
            "score": p.get("score"),
            "reason": p.get("reason", ""),
        })

    # replace today's entry if present, else prepend
    days = [d for d in days if d.get("date") != today]
    days.insert(0, {"date": today, "picks": today_picks})
    # keep last 120 trading days
    days = days[:120]

    # mark-to-market every historical pick
    latest = {}
    for d in days:
        for p in d["picks"]:
            c = p.get("code")
            px = latest_close(c)
            base = p.get("price")
            ret = (px / base - 1.0) * 100 if (px and base) else None
            latest[c] = {"last": px, "ret_pct": ret}
            p["ret_pct"] = ret
            p["last_price"] = px

    out = {
        "updated": time.strftime("%Y-%m-%d %H:%M:%S UTC"),
        "days": days,
    }
    hist_path.write_text(json.dumps(out, ensure_ascii=False, indent=1),
                         encoding="utf-8")
    n = sum(len(d["picks"]) for d in days)
    print(f"history: {len(days)} days, {n} tracked picks -> {hist_path}")


if __name__ == "__main__":
    main()
