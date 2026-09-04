#!/usr/bin/env python3
"""Composite value+quality cross-sectional backtest.

Quality proxy: ROE ~ PBR/PER (derivable from hist series; verified ~3pp err).
Value: low PBR. Each fiscal year Y ranks stocks on percentile(value)+
percentile(quality), takes top group, measures next-calendar-year return vs
bottom group and all-sample. No look-ahead (Y valuation, Y+1 returns).

Usage: python3 backtest_combo.py
"""
import sqlite3
import sys
import time
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent))
import fetch_prices as fp

START_YEAR = 2014


def prices_for_year(code, year):
    conn = fp.db_conn()
    lo = time.mktime(time.strptime(f"{year}0101", "%Y%m%d"))
    hi = time.mktime(time.strptime(f"{year+1}0101", "%Y%m%d"))
    rows = conn.execute(
        "SELECT close FROM daily WHERE code=? AND ts>=? AND ts<? "
        "AND close IS NOT NULL ORDER BY ts", (code, lo, hi)
    ).fetchall()
    conn.close()
    if len(rows) < 40:
        return None
    return rows[0][0], rows[-1][0]


def pct_rank(vals, x):
    """Percentile rank of x within vals (0..1), higher=better."""
    if not vals:
        return 0.5
    below = sum(1 for v in vals if v < x)
    return below / len(vals)


def main():
    conn = fp.db_conn()
    data = conn.execute(
        "SELECT code, year, per, pbr FROM hist_fund ORDER BY code, year"
    ).fetchall()
    conn.close()
    series = {}
    for code, y, per, pbr in data:
        series.setdefault(code, {})[y] = (per, pbr)

    rows_out = []
    for sel_yr in range(START_YEAR, 2025):
        ret_yr = sel_yr + 1
        pool = []
        for code, by_year in series.items():
            v = by_year.get(sel_yr)
            if not v:
                continue
            per, pbr = v
            if not (per and pbr and per > 0 and pbr > 0):
                continue
            r = prices_for_year(code, ret_yr)
            if r is None:
                continue
            c0, c1 = r
            pool.append({"code": code, "pbr": pbr, "per": per,
                         "roe": pbr / per, "ret": c1 / c0 - 1.0})
        if len(pool) < 60:
            continue
        pbrs = [s["pbr"] for s in pool]
        roes = [s["roe"] for s in pool]
        for s in pool:
            # value: low pbr -> high score ; quality: high roe -> high score
            s["v"] = 1.0 - pct_rank(pbrs, s["pbr"])
            s["q"] = pct_rank(roes, s["roe"])
            s["combo"] = s["v"] + s["q"]
        pool.sort(key=lambda s: -s["combo"])
        n = len(pool)
        top = [s["ret"] for s in pool[: n // 5]]
        bot = [s["ret"] for s in pool[-n // 5:]]
        allr = [s["ret"] for s in pool]
        mt = sum(top) / len(top)
        mb = sum(bot) / len(bot)
        ma = sum(allr) / len(allr)
        rows_out.append((sel_yr, n, mt, mb, ma))
        print(f"{sel_yr:>4} n={n:>3} top(值+质)={mt*100:>+7.1f}% "
              f"bottom={mb*100:>+7.1f}% all={ma*100:>+7.1f}% "
              f"prem={(mt-mb)*100:>+6.1f}pp", file=sys.stderr)

    print("\n== composite value+quality, next-year returns ==")
    print(f"years: {rows_out[0][0]}-{rows_out[-1][0]} ({len(rows_out)})")
    a_t = sum(r[2] for r in rows_out) / len(rows_out)
    a_b = sum(r[3] for r in rows_out) / len(rows_out)
    a_a = sum(r[4] for r in rows_out) / len(rows_out)
    print(f"avg top: {a_t*100:+.1f}%  bottom: {a_b*100:+.1f}%  all: {a_a*100:+.1f}%")
    print(f"avg premium(top-bottom): {(a_t-a_b)*100:+.1f}pp/yr")
    print(f"top beat all in {sum(1 for r in rows_out if r[2]>r[4])}/{len(rows_out)} yrs")
    print(f"top beat bottom in {sum(1 for r in rows_out if r[2]>r[3])}/{len(rows_out)} yrs")
    # recent regime (2021+, TSE reforms) vs earlier
    recent = [r for r in rows_out if r[0] >= 2021]
    if recent:
        print(f"\n2021+ ({len(recent)}y): avg premium {(sum(r[2]-r[3] for r in recent)/len(recent))*100:+.1f}pp/yr")
        print(f"2021+ top annual {(sum(r[2] for r in recent)/len(recent))*100:+.1f}% vs all {(sum(r[4] for r in recent)/len(recent))*100:+.1f}%")


if __name__ == "__main__":
    main()
