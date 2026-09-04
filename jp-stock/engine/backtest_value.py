#!/usr/bin/env python3
"""Cross-sectional value backtest on historical annual PER/PBR.

Method (no look-ahead): the fiscal-year Y valuation (available ~mid-Y after
reports) is used to rank stocks; we measure each stock's return over calendar
year Y+1 (Jan 1 -> Dec 31). Stocks sorted into value quintiles by PBR then PER;
compares lowest-valuation (Q1) vs highest (Q5) vs all.

Usage: python3 backtest_value.py
"""
import sqlite3
import sys
import time
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent))
import fetch_prices as fp

START_YEAR = 2013  # hist PBR broadly available from ~2013


def prices_for_year(code, year):
    """Return (first_close_of_year, last_close_of_year) via daily bars."""
    conn = fp.db_conn()
    lo = time.mktime(time.strptime(f"{year}0101", "%Y%m%d"))
    hi = time.mktime(time.strptime(f"{year+1}0101", "%Y%m%d"))
    rows = conn.execute(
        "SELECT ts, close FROM daily WHERE code=? AND ts>=? AND ts<? "
        "AND close IS NOT NULL ORDER BY ts", (code, lo, hi)
    ).fetchall()
    conn.close()
    if len(rows) < 40:
        return None
    return rows[0][1], rows[-1][1]


def annual_return(code, year):
    pr = prices_for_year(code, year)
    if not pr:
        return None
    c0, c1 = pr
    return c1 / c0 - 1.0 if c0 and c1 else None


def main():
    conn = fp.db_conn()
    data = conn.execute(
        "SELECT code, year, per, pbr FROM hist_fund ORDER BY code, year"
    ).fetchall()
    conn.close()
    # group by (code -> {year: (per,pbr)})
    series = {}
    for code, y, per, pbr in data:
        series.setdefault(code, {})[y] = (per, pbr)

    print(f"{'selYr':>4} {'n':>4} {'Q1低估值':>10} {'Q5高估值':>10} {'全样本':>10} "
          f"{'Q1-Q5':>8}", file=sys.stderr)

    rows_out = []
    for sel_yr in range(START_YEAR, 2025):
        # valuations from fiscal sel_yr; returns during sel_yr+1
        ret_yr = sel_yr + 1
        stocks = []
        for code, by_year in series.items():
            v = by_year.get(sel_yr)
            if not v:
                continue
            per, pbr = v
            if not pbr or pbr <= 0:
                continue
            r = annual_return(code, ret_yr)
            if r is None:
                continue
            # value score: low PBR good; use PBR primary, PER tiebreak
            stocks.append((code, pbr, per or 999, r))
        if len(stocks) < 50:
            continue
        stocks.sort(key=lambda x: (x[1], x[2]))  # ascending PBR
        n = len(stocks)
        q1 = [s[3] for s in stocks[: n // 5]]
        q5 = [s[3] for s in stocks[-(n // 5):]]
        allr = [s[3] for s in stocks]
        m1, m5, ma = (sum(q1)/len(q1), sum(q5)/len(q5), sum(allr)/len(allr))
        rows_out.append((sel_yr, n, m1, m5, ma))
        print(f"{sel_yr:>4} {n:>4} {m1*100:>+9.1f}% {m5*100:>+9.1f}% {ma*100:>+9.1f}% "
              f"{(m1-m5)*100:>+7.1f}pp", file=sys.stderr)

    if not rows_out:
        print("no years with data"); return
    print("\n== summary (PBR value factor, next-year returns) ==")
    print(f"years tested: {rows_out[0][0]}-{rows_out[-1][0]} ({len(rows_out)})")
    avg_q1 = sum(r[2] for r in rows_out) / len(rows_out)
    avg_q5 = sum(r[3] for r in rows_out) / len(rows_out)
    avg_all = sum(r[4] for r in rows_out) / len(rows_out)
    print(f"avg Q1(low PBR): {avg_q1*100:+.1f}%  |  avg Q5(high PBR): {avg_q5*100:+.1f}%  |  all: {avg_all*100:+.1f}%")
    print(f"avg value premium (Q1-Q5): {(avg_q1-avg_q5)*100:+.1f}pp/yr")
    wins = sum(1 for r in rows_out if r[2] > r[3])
    print(f"Q1 beat Q5 in {wins}/{len(rows_out)} years")


if __name__ == "__main__":
    main()
