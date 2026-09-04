#!/usr/bin/env python3
"""Export today's recommendation to data/daily.json (the App's data feed).

JSON schema matches the Android client in android/.
"""
import json
import sys
import time
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent))
import scoring as sc
import recommend as rec


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


def main():
    jp_meta = load_jp_meta()
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
        out["picks"].append({
            "code": code,
            "name": jname,
            "industry": jind,
            "price": s.get("price"),
            "score": s["blend"],
            "tech": s["score"],
            "fund": s.get("vq"),
            "per": fd.get("per_f") or fd.get("per"),
            "pbr": fd.get("pbr"),
            "roe": fd.get("roe") or fd.get("roe_f"),
            "div_yield": fd.get("div_yield"),
            "m6": s.get("m126"),
            "m12": s.get("m252"),
            "dd": s.get("dd_pct"),
            "reason": rec.reason(code, jname, s),
            "reason_ja": rec.reason_ja(code, jname, s),
        })
    dest = Path(__file__).resolve().parent.parent / "data" / "daily.json"
    dest.write_text(json.dumps(out, ensure_ascii=False, indent=2), encoding="utf-8")
    print(f"wrote {len(out['picks'])} picks -> {dest}")


if __name__ == "__main__":
    main()
