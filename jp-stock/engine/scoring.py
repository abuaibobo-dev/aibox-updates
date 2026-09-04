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
    """Live fundamentals (irbank point-in-time) for the fetched candidates.

    hist_fund is intentionally NOT used here for point valuation: its "latest"
    fiscal-year PER/PBR can diverge badly from the current price (verified: e.g.
    龟田製菓 hist PER 3.7 vs a sane ~15-25), which would poison daily stock
    picking. hist_fund stays for backtesting only.
    """
    conn = fp.db_conn()
    frows = conn.execute(
        "SELECT code,mcap,per,per_f,pbr,div_yield,roe,roe_f FROM fundamentals"
    ).fetchall()
    conn.close()
    out = {}
    for r in frows:
        out[r[0]] = {
            "mcap": r[1], "per": r[2], "per_f": r[3], "pbr": r[4],
            "div_yield": r[5], "roe": r[6], "roe_f": r[7], "src": "live",
        }
    return out


def rank_all():
    """Rank universe by validated value+quality strategy (backtested +5.7pp/yr).

    Backtest winner (backtest_combo.py): cross-sectional percentile of
    low-PBR (value) + high-ROE (quality) selects a top quintile that beat the
    universe in 7/10 years, +9.3pp/yr since 2021. Technicals act as a hard
    filter (skip broken/illiquid/too-hot names) and a small tiebreak.
    """
    conn = fp.db_conn()
    stocks = conn.execute(
        "SELECT code, name, industry, last_price, high52 FROM stocks ORDER BY code"
    ).fetchall()
    conn.close()
    fmap = load_fundamentals()

    # pass 1: technical filter (reuse score_stock eligibility, ignore its blend)
    eligible = []
    for code, name, industry, last, hi52 in stocks:
        got = load_closes(code)
        if not got:
            continue
        closes, volume = got
        s = score_stock(closes, volume, last, hi52)
        if not s:
            continue
        fund = fmap.get(code)
        s["code"] = code
        s["name"] = name or ""
        s["industry"] = industry or ""
        s["fund_data"] = fund or {}
        # unify: top-level per/pbr/roe drive scoring; keep fund_data in sync so
        # downstream (reason/export) can read either
        s["per"] = (fund or {}).get("per_f") or (fund or {}).get("per")
        s["pbr"] = (fund or {}).get("pbr")
        s["roe"] = (fund or {}).get("roe") or (fund or {}).get("roe_f")
        s["fund_data"]["per"] = s["per"]
        s["fund_data"]["pbr"] = s["pbr"]
        s["fund_data"]["roe"] = s["roe"]
        eligible.append(s)

    def _rank(vals, x, higher_better):
        if not vals or x is None:
            return 0.5
        return (sum(1 for v in vals if v is not None
                    and (v < x if higher_better else v > x))
                / len(vals))

    pbrs = [e["pbr"] for e in eligible if e["pbr"] is not None]
    pers = [e["per"] for e in eligible if e["per"] is not None]
    roes = [e["roe"] for e in eligible if e["roe"] is not None]

    for e in eligible:
        per = e["per"]
        pbr = e["pbr"]
        roe = e["roe"]
        # data-quality guardrails: absurd PER (<3 or >200) or PBR<=0.15 usually
        # means a bad-data / distressed year — do not reward as deep value
        bad = (per is None or pbr is None or roe is None or pbr <= 0.15
               or (per is not None and (per < 3.0 or per > 200.0)))
        if bad:
            e["vq"] = None
            e["blend"] = round(40.0 + 0.1 * e["score"], 1)
        else:
            v_pbr = _rank(pbrs, pbr, higher_better=False)
            v_per = _rank(pers, per, higher_better=False)
            q_roe = _rank(roes, roe, higher_better=True)
            e["vq"] = round(100 * (0.45 * v_pbr + 0.35 * q_roe + 0.20 * v_per), 1)
            e["blend"] = round(0.85 * e["vq"] + 0.15 * e["score"], 1)

    ranked = sorted([e for e in eligible if e["vq"] is not None],
                    key=lambda e: -e["blend"])
    return [(e["code"], e["name"], e) for e in ranked]


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
        per = s.get("per")
        pbr = s.get("pbr")
        roe = s.get("roe")
        fd = s["fund_data"] or {}
        dy = fd.get("div_yield")
        print(f"{code:>5} {s['blend']:5.1f}  {name[:18]:18} {s['industry'][:6]:6} "
              f"vq={s.get('vq',0):5.1f} tech={s['score']:5.1f} "
              f"PER={per or 0:>5.1f} PBR={pbr or 0:>4.2f} ROE={roe or 0:>5.1f} "
              f"DY={dy or 0:>4.2f} 12m%={s['m252']*100:+.0f}%")


if __name__ == "__main__":
    main()
