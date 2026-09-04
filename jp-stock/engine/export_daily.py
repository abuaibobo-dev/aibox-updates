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
import fetch_prices as fp
import advice


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


def build_tags(p):
    """Short human tags explaining WHY this pick was chosen."""
    t = []
    pp = p.get("pbr_pct"); ep = p.get("per_pct")
    if pp is not None and pp <= 25:
        t.append(f"PB历史{pp:.0f}%分位·低估")
    elif pp is not None and pp >= 80:
        t.append(f"PB历史{pp:.0f}%分位·偏高")
    if ep is not None and ep <= 25:
        t.append(f"PE历史{ep:.0f}%分位·低估")
    roe = p.get("roe")
    if roe is not None and roe >= 15:
        t.append(f"ROE {roe:.0f}%·高质量")
    elif roe is not None and roe >= 10:
        t.append(f"ROE {roe:.0f}%·良好")
    dy = p.get("div_yield")
    if dy is not None and dy >= 3.0:
        t.append(f"股息 {dy:.1f}%")
    m12 = p.get("m12"); m6 = p.get("m6")
    if m12 is not None and m12 >= 0.3:
        t.append(f"1年{m12*100:+.0f}%·趋势强")
    if m6 is not None and m6 < 0 and m12 is not None and m12 > 0:
        t.append("短调蓄势")
    if not t:
        t.append("价值+质量评分居前")
    return t[:4]


def build_pitch(d):
    """Client-facing 'why buy / watch' pitch — highlights the merits in plain
    language, plus a caution line. Reads from the exported pick dict."""
    nm = d.get("name", d.get("code", ""))
    pts = []
    roe = d.get("roe")
    if roe is not None:
        if roe >= 15:
            pts.append(f"盈利能力出众(ROE {roe:.0f}%)，明显高于行业平均")
        elif roe >= 10:
            pts.append(f"盈利质量稳健(ROE {roe:.0f}%)")
    per = d.get("per")
    pp = d.get("pbr_pct")
    if per is not None and per > 0:
        if per <= 15:
            pts.append(f"估值便宜(PE 仅约{per:.1f}倍)")
        elif per <= 25:
            pts.append(f"估值合理(PE 约{per:.1f}倍)")
    if pp is not None and pp <= 30 and d.get("pbr"):
        pts.append(f"市净率处于自身历史{pp:.0f}%分位的低位区间")
    dy = d.get("div_yield")
    if dy is not None and dy >= 2.5:
        pts.append(f"提供约{dy:.1f}%的股息回报")
    m12 = d.get("m12")
    if m12 is not None and m12 >= 0.2:
        pts.append(f"近1年{'+' if m12>0 else ''}{m12*100:.0f}%，趋势获资金认可")
    if not pts:
        pts.append("综合多因子评分居全池前列")
    lead = f"{nm}({d.get('code')})作为{d.get('industry','')}方向标的，"
    body = "；".join(pts) + "。"
    tail = "综合看属于估值与质量较均衡的选择，可作为稳健配置的观察参考。非收益承诺。"
    return lead + body + tail


def client_fit(d):
    """Profile hint: which kind of client this name fits (zh for the adviser,
    ja embedded into the client note)."""
    dy = d.get("div_yield")
    roe = d.get("roe")
    m12 = d.get("m12")
    m6 = d.get("m6")
    if dy is not None and dy >= 3.0:
        zh = "偏中长期 · 股息/现金流回报型"
        ja = "配当・インカムを重視する中長期投資向け"
    elif roe is not None and roe >= 15:
        zh = "偏成长 · 盈利质量高中长期持有"
        ja = "収益性の高い成長株として、中長期の保有向け"
    elif (m12 or 0) >= 0.4 or (m6 or 0) >= 0.25:
        zh = "偏趋势交易 · 波动较大，注意仓位与止损"
        ja = "中短期の値動きを活かす投資向け（値動きにご注意）"
    else:
        zh = "均衡型 · 适合作为组合配置的一角"
        ja = "分散投資の一環としての保有に適性"
    return zh, ja


def build_pitch_ja(d):
    """Japanese client-facing rationale (polite です/ます register)."""
    nm = d.get("name", d.get("code", ""))
    ind = d.get("industry", "")
    price = d.get("price")
    roe = d.get("roe")
    per = d.get("per")
    pp = d.get("pbr_pct")
    dy = d.get("div_yield")
    m12 = d.get("m12")
    dd = d.get("dd")

    out = [f"【{ind}】{nm}（{d.get('code')}）"]
    out.append(f"株価：{price:,.0f}円" if price else f"コード：{d.get('code')}")
    out.append("")
    out.append("＜選定ポイント＞")
    pts = []
    if per is not None and 0 < per <= 15:
        pts.append(f"PER約{per:.1f}倍と、利益に対して割安な水準")
    elif per is not None and per <= 25:
        pts.append(f"PER約{per:.1f}倍と妥当な水準")
    if roe is not None and roe >= 15:
        pts.append(f"ROE{roe:.1f}％と収益性が高い")
    elif roe is not None and roe >= 10:
        pts.append(f"ROE{roe:.1f}％と収益性は安定")
    if pp is not None and pp <= 30 and d.get("pbr"):
        pts.append(f"PBRは過去{pp:.0f}％分位と、自身の歴史では割安圏")
    if dy is not None and dy >= 2.5:
        pts.append(f"配当利回り約{dy:.1f}％")
    if m12 is not None and m12 >= 0.2:
        pts.append(f"直近1年で+{m12*100:.0f}％と上昇基調")
    for p in (pts or ["総合スコアが東証プライム全体で上位"]):
        out.append(f"・{p}")
    out.append("")
    out.append("＜留意点＞")
    risks = []
    if per is not None and per > 25:
        risks.append("バリュエーションがやや割高な領域")
    if dd is not None and dd <= -0.15:
        risks.append("株価は高値圏から調整中の局面")
    if dy is not None and dy < 1.0:
        risks.append("配当水準は限定的")
    for r in (risks or ["業績や市況の変動リスク"]):
        out.append(f"・{r}")
    out.append("")
    _zf, jaf = client_fit(d)
    out.append("＜投資スタイル（目安）＞")
    out.append(f"・{jaf}")
    out.append("")
    out.append("※本内容は情報提供を目的とした分析資料であり、投資勧誘を目的とするものでは"
               "ありません。最終的な投資判断はお客様ご自身でお願いいたします。")
    return "\n".join(out)


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
        roe = fd.get("roe") or fd.get("roe_f")
        dy = fd.get("div_yield")
        hist_pct = valuation_percentiles(code, per, pbr, hist)
        m126 = s.get("m126"); m252 = s.get("m252")
        d = {
            "code": code,
            "name": jname,
            "industry": jind,
            "price": s.get("price"),
            "score": s["blend"],
            "tech": s["score"],
            "fund": s.get("vq"),
            "per": per,
            "pbr": pbr,
            "roe": roe,
            "div_yield": dy,
            "per_pct": hist_pct.get("per_pct"),
            "pbr_pct": hist_pct.get("pbr_pct"),
            "m6": m126,
            "m12": m252,
            "dd": s.get("dd_pct"),
            "tags": build_tags({
                "pbr_pct": hist_pct.get("pbr_pct"),
                "per_pct": hist_pct.get("per_pct"),
                "roe": roe, "div_yield": dy,
                "m6": m126, "m12": m252,
            }),
            "reason": _with_val_pct(rec.reason(code, jname, s), hist_pct, pbr, per),
            "reason_ja": rec.reason_ja(code, jname, s),
        }
        d["pitch"] = build_pitch(d)
        d["pitch_ja"] = build_pitch_ja(d)
        fit_zh, fit_ja = client_fit(d)
        d["fit_zh"] = fit_zh
        d["fit_ja"] = fit_ja

        # actionable levels (buy zone / stop / targets) from recent ~90 bars
        px_now = d.get("price")
        if px_now:
            cq = fp.db_conn()
            try:
                rr = cq.execute(
                    "SELECT high, low FROM daily WHERE code=? AND close IS NOT NULL "
                    "ORDER BY ts DESC LIMIT 90", (code,)
                ).fetchall()
            finally:
                cq.close()
            if rr:
                act = advice.compute_action(
                    [{"h": h, "l": l} for h, l in reversed(rr)], px_now)
                if act:
                    d["action"] = {k: act[k] for k in
                                   ("buy_low", "buy_high", "stop", "t1", "t2", "watch")}
        out["picks"].append(d)
    out["strategy"] = (
        "从东证Prime全池按【低估值(历史分位) + 高盈利质量(ROE) + 股息 + 趋势过滤】"
        "多因子打分排序，再按行业分散选出当日推荐。该组合策略经2015-2024十年回测，"
        "年化超额约6个百分点(2021年东证改革后约9个百分点)。非投资建议。"
    )
    dest = Path(__file__).resolve().parent.parent / "data" / "daily.json"
    dest.write_text(json.dumps(out, ensure_ascii=False, indent=2), encoding="utf-8")
    print(f"wrote {len(out['picks'])} picks -> {dest}")


if __name__ == "__main__":
    main()
