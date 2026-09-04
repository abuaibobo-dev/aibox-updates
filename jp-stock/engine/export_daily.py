#!/usr/bin/env python3
"""Export today's recommendation to data/daily.json (the App's data feed).

JSON schema matches the Android client in android/.
"""
import json
import sqlite3
import sys
import time
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent))
import scoring as sc
import recommend as rec


def load_hist_series():
    """code -> {year: (per, pbr)} from hist_fund."""
    conn = sqlite3.connect(Path(__file__).resolve().parent.parent / "data" / "market.sqlite")
    rows = conn.execute(
        "SELECT code, year, per, pbr FROM hist_fund ORDER BY code, year"
    ).fetchall()
    conn.close()
    out = {}
    for code, y, per, pbr in rows:
        out.setdefault(code, {})[y] = (per, pbr)
    return out


def pct_rank(values, x):
    """Fraction of historical values below x (lower => cheaper vs its own history)."""
    vals = [v for v in values if v is not None and v > 0]
    if not vals or x is None or x <= 0:
        return None
    return round(100.0 * sum(1 for v in vals if v < x) / len(vals), 0)


def valuation_percentiles(code, per_now, pbr_now, hist):
    ser = hist.get(code, {})
    pers = [v[0] for v in ser.values()]
    pbrs = [v[1] for v in ser.values()]
    return {
        "per_pct": pct_rank(pers, per_now),
        "pbr_pct": pct_rank(pbrs, pbr_now),
    }


def load_jp_meta():
    """code -> (Japanese name, industry) from prime.csv (source of truth)."""
    m = {}
    p = Path(__file__).resolve().parent.parent / "data" / "prime.csv"
    if p.exists():
        for line in p.read_text(encoding="utf-8").splitlines():
            parts = line.split(",")
            if len(parts) >= 3:
                m[parts[0]] = (parts[1], parts[2])
    return m


def pick_daily(ranked, n=5, per_ind=1):
    per_ind_count, picks = {}, []
    used = set()
    while len(picks) < n:
        progressed = False
        for code, name, s in ranked:
            if code in used:
                continue
            ind = s["industry"]
            if per_ind_count.get(ind, 0) >= per_ind:
                continue
            picks.append((code, name, s))
            used.add(code)
            per_ind_count[ind] = per_ind_count.get(ind, 0) + 1
            progressed = True
            if len(picks) >= n:
                break
        if not progressed:
            break
    return picks


def _with_val_pct(reason, hp, pbr, per):
    bits = []
    if hp.get("pbr_pct") is not None and pbr:
        if hp["pbr_pct"] <= 20:
            bits.append(f"市净率处于自身历史{hp['pbr_pct']:.0f}%分位(历史低位)")
        elif hp["pbr_pct"] >= 80:
            bits.append(f"市净率处于历史{hp['pbr_pct']:.0f}%分位(偏高)")
    if hp.get("per_pct") is not None and per:
        if hp["per_pct"] <= 20:
            bits.append(f"市盈率处于历史{hp['per_pct']:.0f}%分位(历史低位)")
    extra = "；".join(bits)
    return reason + ("(" + extra + ")。" if extra else "")


def main():
    jp_meta = load_jp_meta()
    hist = load_hist_series()
    ranked = sc.rank_all()
    picks = pick_daily(ranked)
    out = {
        "date": time.strftime("%Y-%m-%d"),
        "generated_at": time.strftime("%Y-%m-%d %H:%M:%S %Z"),
        "universe_size": len(ranked),
        "picks": [],
    }
    for code, name, s in picks:
        fd = s.get("fund_data") or {}
        jname, jind = jp_meta.get(code, (name, s["industry"]))
        per = fd.get("per_f") or fd.get("per")
        pbr = fd.get("pbr")
        hist_pct = valuation_percentiles(code, per, pbr, hist)
        out["picks"].append({
            "code": code,
            "name": jname,
            "industry": jind,
            "price": s.get("price"),
            "score": s["blend"],
            "tech": s["score"],
            "fund": s.get("vq"),
            "per": per,
            "pbr": pbr,
            "roe": fd.get("roe") or fd.get("roe_f"),
            "div_yield": fd.get("div_yield"),
            "per_pct": hist_pct.get("per_pct"),
            "pbr_pct": hist_pct.get("pbr_pct"),
            "m6": s.get("m126"),
            "m12": s.get("m252"),
            "dd": s.get("dd_pct"),
            "reason": _with_val_pct(rec.reason(code, jname, s), hist_pct, pbr, per),
            "reason_ja": rec.reason_ja(code, jname, s),
        })
    dest = Path(__file__).resolve().parent.parent / "data" / "daily.json"
    dest.write_text(json.dumps(out, ensure_ascii=False, indent=2), encoding="utf-8")
    print(f"wrote {len(out['picks'])} picks -> {dest}")


if __name__ == "__main__":
    main()
