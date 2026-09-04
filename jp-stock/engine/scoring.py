#!/usr/bin/env python3
"""Multi-factor scoring of the Prime universe from stored daily bars.

Pure stdlib. Factors (each normalized 0..100):
  1. trend    - price vs MA200/MA50, bull alignment
  2. mom126   - 6-month momentum, moderate winners preferred
  3. mom252   - 1-year momentum positive
  4. risk     - lower realized vol scores higher (稳健)
  5. drawdown - healthy distance from 52w high (not chasing, not broken)

Filters: too little volume, price deep below MA200, extreme vol.
Outputs a ranked list; top N printed as the day's picks.
"""
import math
import os
import statistics
import sys
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent))
import fetch_prices as fp  # reuse db path + conn

TRADING = 252


def sma(vals, n):
    if len(vals) < n:
        return None
    return sum(vals[-n:]) / n


def pct(vals, n):
    """Return (n-day change, oldest price). None if insufficient data."""
    if len(vals) <= n or vals[-1 - n] <= 0:
        return None
    return (vals[-1] / vals[-1 - n] - 1.0)


def annualized_vol(closes):
    if len(closes) < 40:
        return None
    rets = []
    prev = None
    for c in closes:
        if prev:
            rets.append(math.log(c / prev))
        prev = c
    sd = statistics.pstdev(rets)
    return sd * math.sqrt(TRADING)


def norm(x, lo, hi, invert=False):
    """clip(x, lo, hi) mapped linearly to 0..100; invert flips direction."""
    if x is None:
        return 50.0
    x = max(lo, min(hi, x))
    v = (x - lo) / (hi - lo) * 100.0
    return 100.0 - v if invert else v


def score_stock(closes, volume, price=None, hi52=None):
    """Return dict of factor scores + total, or None if ineligible.

    All factors derive from the given closes/volume slice (<= today) so callers
    can backtest without look-ahead. hi52 defaults to trailing-252d high.
    """
    n = len(closes)
    if n < 80:
        return None
    cur = closes[-1]
    if cur <= 0:
        return None
    if hi52 is None:
        hi52 = max(closes[-252:]) if n >= 252 else max(closes)

    ma50 = sma(closes, 50)
    ma200 = sma(closes, 200)
    vol = annualized_vol(closes)

    # --- filters ---
    avg_vol20 = sum(volume[-20:]) / 20 if len(volume) >= 20 else 0
    if avg_vol20 < 200_000:
        return None  # too illiquid (units = shares)
    if ma200 and cur < ma200 * 0.90:
        return None  # deep below trend -> broken
    if vol and vol > 0.65:
        return None  # extremely volatile (稳健)

    m126 = pct(closes, 126)
    m252 = pct(closes, 252)
    m20 = pct(closes, 20)

    # --- factors ---
    trend = 50.0
    if ma200 and ma50:
        above = (cur / ma200 - 1.0)
        aln = 1.0 if ma50 > ma200 else 0.0
        trend = 0.7 * norm(above, -0.2, 0.6) + 0.3 * (100.0 if aln else 30.0)
    elif ma200:
        trend = norm(cur / ma200 - 1.0, -0.2, 0.6)

    # 6m momentum: sweet spot ~0.05..0.40, decay if too hot
    mom126 = 50.0
    if m126 is not None:
        if m126 <= 0.0:
            mom126 = norm(m126, -0.40, 0.0) * 0.5
        elif m126 <= 0.40:
            mom126 = 50.0 + norm(m126, 0.0, 0.40) * 0.5
        else:
            mom126 = 100.0 - norm(m126, 0.40, 1.20) * 0.6

    mom252 = 50.0
    if m252 is not None:
        mom252 = 50.0 + norm(max(-0.5, m252), -0.50, 0.80) * 0.5

    # risk: map vol 0.10..0.50 -> 100..0 (lower vol = higher score)
    risk = norm(vol, 0.10, 0.50, invert=True) if vol else 50.0

    # drawdown health: ideal pullback -6%..-22% from 52w high (established
    # uptrend with a re-entry window). Near the high = ok; deep = risky.
    dd = (cur / hi52 - 1.0) if hi52 else -0.10
    if dd is None or dd >= -0.05:
        dd_score = 70.0
    elif dd >= -0.22:
        # closer to -0.22 slightly higher (better entry) but cap range 70..100
        dd_score = 70.0 + norm(dd, -0.22, -0.05) * 0.3
    else:
        dd_score = norm(dd, -0.60, -0.22)

    # small penalty for a very hot last 20d (avoid chasing)
    hot_pen = 0.0
    if m20 is not None and m20 > 0.20:
        hot_pen = min(15.0, (m20 - 0.20) * 50.0)

    total = (
        0.30 * trend + 0.25 * mom126 + 0.15 * mom252 + 0.15 * risk + 0.15 * dd_score
    ) - hot_pen

    return {
        "trend": round(trend, 1), "mom126": round(mom126, 1),
        "mom252": round(mom252, 1), "risk": round(risk, 1),
        "dd": round(dd_score, 1), "vol": vol, "dd_pct": dd,
        "m126": m126, "m252": m252, "m20": m20,
        "price": cur,
        "score": round(total, 1),
    }


def load_closes(code):
    conn = fp.db_conn()
    rows = conn.execute(
        "SELECT close, volume FROM daily WHERE code=? ORDER BY ts", (code,)
    ).fetchall()
    conn.close()
    if not rows:
        return None
    rows = [(c, v or 0) for c, v in rows if c is not None]
    if not rows:
        return None
    return [r[0] for r in rows], [r[1] for r in rows]


def fundamental_score(f):
    """Blend valuation + quality + income into 0..100. f may be None."""
    if not f:
        return 50.0  # neutral when data missing
    roe = f.get("roe") or f.get("roe_f")
    per = f.get("per_f") or f.get("per")
    pbr = f.get("pbr")
    dy = f.get("div_yield")

    quality = 50.0
    if roe is not None:
        quality = 50.0 + norm(max(-20.0, roe), -20.0, 20.0) * 0.5

    value = 50.0
    if per is not None and per > 0:
        if per < 8:
            value = 80.0  # cheap but possibly value trap; moderate
        elif per <= 25:
            value = 90.0 - norm(per, 8.0, 25.0) * 0.4  # sweet band high
        else:
            value = 60.0 - norm(per, 25.0, 60.0) * 0.8  # expensive decays
    if pbr is not None and pbr > 0:
        if pbr > 5.0:
            value = min(value, 40.0)
        elif pbr <= 0.7:
            value = max(value, 40.0)  # deep value, floor guard
        # 0.7..5 blend keeps value as computed

    income = 50.0
    if dy is not None:
        income = 50.0 + norm(min(6.0, dy), 0.0, 4.0) * 0.5 if dy >= 0 else 30.0

    return round(0.45 * quality + 0.35 * value + 0.20 * income, 1)


def load_fundamentals():
    conn = fp.db_conn()
    frows = conn.execute(
        "SELECT code,mcap,per,per_f,pbr,div_yield,roe,roe_f FROM fundamentals"
    ).fetchall()
    conn.close()
    out = {}
    for r in frows:
        out[r[0]] = {
            "mcap": r[1], "per": r[2], "per_f": r[3], "pbr": r[4],
            "div_yield": r[5], "roe": r[6], "roe_f": r[7],
        }
    return out


def rank_all():
    """Return list of {code,name,factors} ranked by blended score."""
    conn = fp.db_conn()
    stocks = conn.execute(
        "SELECT code, name, industry, last_price, high52 FROM stocks ORDER BY code"
    ).fetchall()
    conn.close()
    fmap = load_fundamentals()
    results = []
    for code, name, industry, last, hi52 in stocks:
        got = load_closes(code)
        if not got:
            continue
        closes, volume = got
        s = score_stock(closes, volume, last, hi52)
        if s:
            s["industry"] = industry or ""
            fund = fmap.get(code)
            fs = fundamental_score(fund)
            s["fund"] = fs
            s["blend"] = round(0.6 * s["score"] + 0.4 * fs, 1)
            s["fund_data"] = fund
            results.append((code, name or "", s))
    results.sort(key=lambda x: -x[2]["blend"])
    return results


def main():
    results = rank_all()
    total_stocks = len({r[0] for r in results})  # all passed tech filter
    conn = fp.db_conn()
    all_count = conn.execute("SELECT COUNT(*) FROM stocks").fetchone()[0]
    conn.close()
    # industry diversification: at most N picks per industry, in score order
    max_per_ind = int(os.environ.get("MAX_PER_IND", "2"))
    per_ind = {}
    diversified = []
    for code, name, s in results:
        ind = s["industry"]
        if per_ind.get(ind, 0) >= max_per_ind:
            continue
        per_ind[ind] = per_ind.get(ind, 0) + 1
        diversified.append((code, name, s))
    print(f"scored {total_stocks}/{all_count}  (filtered "
          f"{all_count-total_stocks})", file=sys.stderr)
    print(f"industries in diversified top: "
          f"{sorted({s['industry'] for _,_,s in diversified[:30]})}", file=sys.stderr)
    for code, name, s in diversified[:20]:
        fd = s["fund_data"] or {}
        per = fd.get("per_f") or fd.get("per")
        pbr = fd.get("pbr")
        roe = fd.get("roe") or fd.get("roe_f")
        dy = fd.get("div_yield")
        print(f"{code:>5} {s['blend']:5.1f}  {name[:20]:20} {s['industry'][:7]:7} "
              f"tech={s['score']:5.1f} fund={s['fund']:5.1f} "
              f"PER={per or 0:>5} PBR={pbr or 0:>4.2f} ROE={roe or 0:>5.1f} DY={dy or 0:>4.2f} "
              f"12m%={s['m252']*100:+.0f}%")


if __name__ == "__main__":
    main()
