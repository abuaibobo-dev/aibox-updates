#!/usr/bin/env python3
"""Walk-forward backtest of the technical scoring rule.

Honest scope: fundamentals are a point-in-time snapshot (no history), so this
validates ONLY the technical sub-score. Each rebalance scores on a trailing
slice ending at the rebalance date -> no look-ahead. Benchmark is equal-weight
of every scorable stock (proxy for the Prime universe).

Usage: python3 backtest.py [period_days] [top_n]
"""
import sqlite3
import sys
import time
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent))
import fetch_prices as fp
import scoring as sc

PERIOD = int(sys.argv[1]) if len(sys.argv) > 1 else 21
TOP_N = int(sys.argv[2]) if len(sys.argv) > 2 else 10
MAX_HIST = 260  # enough for MA200 + margins; trims memory


def load_grid_and_series():
    """Build a common trading-day grid + per-stock aligned arrays.

    Returns (grid, aligned) where aligned[code] = (closes, vols) length == len(grid),
    entries before listing are None.
    """
    conn = fp.db_conn()
    rows = conn.execute(
        "SELECT code, ts, close, volume FROM daily ORDER BY code, ts"
    ).fetchall()
    conn.close()
    bycode = {}
    for code, ts, close, volume in rows:
        if close is None:
            continue
        bycode.setdefault(code, []).append((ts, float(close), volume or 0))
    grid = sorted({ts for s in bycode.values() for ts, _, _ in s})
    pos = {ts: i for i, ts in enumerate(grid)}
    aligned = {}
    for code, data in bycode.items():
        closes = [None] * len(grid)
        vols = [None] * len(grid)
        for ts, c, v in data:
            closes[pos[ts]] = c
            vols[pos[ts]] = v
        aligned[code] = (closes, vols)
    return grid, aligned


def trailing(closes, vols, upto):
    """Valid (close, vol) pairs up to grid index upto."""
    cs, vs = [], []
    for i in range(upto, -1, -1):
        c = closes[i]
        if c is None:
            break  # gap before listing — stop
        cs.append(c)
        vs.append(vols[i] or 0)
        if len(cs) >= MAX_HIST:
            break
    return cs[::-1], vs[::-1]


def ret_between(aligned, code, i0, i1):
    closes, _ = aligned[code]
    c0 = closes[i0]
    c1 = closes[i1]
    return (c1 / c0 - 1.0) if (c0 and c1) else None


def main():
    grid, aligned = load_grid_and_series()
    codes = list(aligned.keys())
    print(f"stocks={len(codes)} grid_days={len(grid)} period={PERIOD} top={TOP_N}",
          file=sys.stderr)
    rebalances = list(range(200, len(grid) - PERIOD, PERIOD))
    if not rebalances:
        print("not enough history"); return

    strat = bench = 1.0
    curves = ([1.0], [1.0])
    n_reb = 0
    for ri in rebalances:
        scored = []
        for code in codes:
            closes, vols = aligned[code]
            cs, vs = trailing(closes, vols, ri)
            if len(cs) < 80 or cs[-1] is None:
                continue
            s = sc.score_stock(cs, vs)
            if s:
                scored.append((code, s["score"]))
        if len(scored) < TOP_N:
            continue
        scored.sort(key=lambda x: -x[1])
        top = [c for c, _ in scored[:TOP_N]]
        ei = ri + PERIOD
        # top-N avg return
        pr = [r for c in top if (r := ret_between(aligned, c, ri, ei)) is not None]
        br = [r for c in scored if (r := ret_between(aligned, c[0], ri, ei)) is not None]
        if not pr or not br:
            continue
        sr, brr = sum(pr) / len(pr), sum(br) / len(br)
        strat *= 1 + sr
        bench *= 1 + brr
        curves[0].append(strat); curves[1].append(bench)
        n_reb += 1
        print(f"reb {n_reb}: {time.strftime('%Y-%m-%d', time.gmtime(grid[ri]))} "
              f"pick={sr*100:+.1f}% bench={brr*100:+.1f}% "
              f"cum={strat:.3f}/{bench:.3f}", file=sys.stderr)

    yrs = max(1e-9, n_reb * PERIOD / 252)
    ann_s = (strat ** (1 / yrs) - 1) * 100
    ann_b = (bench ** (1 / yrs) - 1) * 100
    print(f"\n== {n_reb} rebalances x {PERIOD}d, top {TOP_N}, ~{yrs:.1f}y ==")
    print(f"strategy:  {(strat-1)*100:+.1f}% total  ({ann_s:+.1f}% ann)   [{strat:.2f}x]")
    print(f"benchmark: {(bench-1)*100:+.1f}% total  ({ann_b:+.1f}% ann)   [{bench:.2f}x]")
    print(f"outperformance: {strat/bench:.2f}x")
    wins = sum(1 for i in range(len(curves[0])-1)
               if curves[0][i+1]/curves[0][i] > curves[1][i+1]/curves[1][i])
    print(f"beat benchmark in {wins}/{len(curves[0])-1} periods")


if __name__ == "__main__":
    main()
