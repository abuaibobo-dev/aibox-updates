#!/usr/bin/env python3
"""One-command daily pipeline: universe -> prices -> fundamentals -> export.

Runs the full chain so both local use and GitHub Actions share one entrypoint.
Heavy steps (price history for the whole Prime universe) reuse any existing DB,
so subsequent runs are incremental.

Steps:
  1. fetch_universe.py   -> data/prime.csv   (list + industry; ~2min)
  2. fetch_prices.py     -> market.sqlite    (10y daily OHLCV; big on first run)
  3. pick technical top candidates
  4. fetch_fundamentals.py <candidates>      (irbank, serial ~1s each)
  5. export_daily.py     -> data/daily.json  (the App feed)
"""
import subprocess
import sys
import time
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
ENGINE = Path(__file__).resolve().parent


def run(args, **kw):
    print(f"\n>>> {' '.join(args)}", file=sys.stderr, flush=True)
    t0 = time.time()
    r = subprocess.run(args, cwd=str(ENGINE), **kw)
    print(f"    ({time.time()-t0:.0f}s, exit {r.returncode})", file=sys.stderr)
    if r.returncode != 0:
        sys.exit(f"step failed: {args[0]}")
    return r


def main():
    run([sys.executable, "fetch_universe.py"])
    run([sys.executable, "fetch_prices.py"])

    # candidates = top ~120 by technical score (fundamentals only for these)
    sys.path.insert(0, str(ENGINE))
    import scoring as sc
    ranked = sc.rank_all()
    cands = [c for c, _, _ in ranked[:120]]
    print(f"top candidates for fundamentals: {len(cands)}", file=sys.stderr)
    if cands:
        run([sys.executable, "fetch_fundamentals.py"] + cands)
    run([sys.executable, "export_daily.py"])
    run([sys.executable, "fetch_market.py"])
    run([sys.executable, "history.py"])
    print("\ndaily.json + market.json + history.json ready", file=sys.stderr)


if __name__ == "__main__":
    main()
