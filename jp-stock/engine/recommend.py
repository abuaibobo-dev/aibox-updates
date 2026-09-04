#!/usr/bin/env python3
"""Daily recommendation report.

Uses scoring.rank_all() (tech + fundamentals blended, industry-aware) and
emits a human-readable Top-N with per-stock reasoning, in Chinese.
"""
import sys
from pathlib import Path

sys.path.insert(0, str(Path(__file__).resolve().parent))
import scoring as sc


def pick_daily(ranked, n=5, per_ind=1):
    """Round-robin over industries, in score order, until n picks."""
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


def reason(code, name, s):
    """Structured Chinese rationale: trend narrative + valuation + quality +
    income + risk, reads like an analyst note rather than field concat."""
    m126 = s.get("m126") or 0
    m252 = s.get("m252") or 0
    dd = s.get("dd_pct")
    fd = s.get("fund_data") or {}
    per = fd.get("per_f") or fd.get("per")
    pbr = fd.get("pbr")
    roe = fd.get("roe") or fd.get("roe_f")
    dy = fd.get("div_yield")

    # --- trend story ---
    trend_s = "中期趋势向上"
    if s.get("trend", 0) >= 75:
        trend_s = "长期趋势向上、站稳长均线上方"
    elif s.get("trend", 0) < 55:
        trend_s = "趋势正在修复"
    parts = [f"{name}({code})处于{trend_s}的格局。"]

    # momentum
    if m126 >= 0.2:
        parts.append(f"近6个月上涨{m126*100:.0f}%、1年{m252*100:+.0f}%，动能与持续性兼备")
    elif m126 > 0:
        parts.append(f"近6个月缓涨{m126*100:.0f}%，节奏稳健")
    else:
        parts.append(f"近6个月回调{m126*100:.0f}%，属上升途中的整理而非转弱")
    parts[-1] += "。"

    # entry window via drawdown
    if dd is not None and dd <= -0.10:
        parts.append(f"现价距52周高点回撤约{abs(dd)*100:.0f}%，具备相对低吸的介入窗口。")
    elif dd is not None and dd >= -0.02:
        parts.append("股价贴近阶段新高，趋势确认但需留意追高风险。")

    # valuation + quality
    val_bits = []
    if per and per > 0:
        if per <= 12:
            val_bits.append(f"PE约{per:.1f}倍(低估)")
        elif per <= 25:
            val_bits.append(f"PE约{per:.1f}倍(合理)")
        else:
            val_bits.append(f"PE约{per:.1f}倍(偏贵)")
    if pbr and pbr > 0:
        val_bits.append(f"PB {pbr:.2f}")
    if roe is not None:
        val_bits.append(f"ROE {roe:.1f}%(盈利质量{'优' if roe>=12 else '中' if roe>=8 else '一般'})")
    if dy and dy >= 2:
        val_bits.append(f"股息率{dy:.1f}%(具回报)")
    if val_bits:
        parts.append("基本面维度：" + "、".join(val_bits) + "。")

    # risk framing
    if dd is not None and dd <= -0.22:
        parts.append("注意：距高点回撤较深，需结合基本面确认是否趋势转弱。")
    if per and per > 30:
        parts.append("注意：估值偏高，波动可能放大。")
    parts.append("该标的由多因子模型筛选，仅供研究参考。")
    return "".join(parts)


def reason_ja(code, name, s):
    """Short Japanese blurb for social sharing."""
    fd = s.get("fund_data") or {}
    per = fd.get("per_f") or fd.get("per")
    pbr = fd.get("pbr")
    roe = fd.get("roe") or fd.get("roe_f")
    dy = fd.get("div_yield")
    m126 = s.get("m126") or 0
    bits = []
    if s.get("trend", 0) >= 75:
        bits.append("長期トレンド強く")
    if m126 >= 0.2:
        bits.append(f"6ヶ月で{m126*100:.0f}%上昇")
    if per and 0 < per <= 25:
        bits.append(f"PER {per:.1f}倍")
    if roe and roe >= 10:
        bits.append(f"ROE {roe:.1f}%")
    if dy and dy >= 2:
        bits.append(f"配当利回り{dy:.1f}%")
    body = "・".join(bits) if bits else "多因子スコア上位"
    return (f"【日株ピック】{name}({code}) ¥{s.get('price',0):,.0f} "
            f"スコア{s['blend']:.0f}\n{body}\n"
            f"※機械学習による参考情報であり投資助言ではありません。")


def main():
    ranked = sc.rank_all()
    picks = pick_daily(ranked)
    lines = []
    lines.append("=" * 62)
    lines.append("日本股市 每日推荐  (技术面+基本面 多因子 / 行业分散)")
    lines.append("=" * 62)
    lines.append("免责声明：以下为量化规则信号，非投资建议。")
    lines.append("")
    for i, (code, name, s) in enumerate(picks, 1):
        fd = s["fund_data"] or {}
        per = fd.get("per_f") or fd.get("per")
        pbr = fd.get("pbr")
        roe = fd.get("roe") or fd.get("roe_f")
        dy = fd.get("div_yield")
        lines.append(f"#{i}  {code}  {name}")
        lines.append(f"    行业: {s['industry']}  现价: ¥{s.get('price','?'):,}  "
                     f"综合分: {s['blend']:.1f} (技术{s['score']:.0f}/基本面{s['fund']:.0f})")
        detail = []
        if per:
            detail.append(f"PE {per:.1f}")
        if pbr:
            detail.append(f"PB {pbr:.2f}")
        if roe:
            detail.append(f"ROE {roe:.1f}%")
        if dy:
            detail.append(f"股息 {dy:.1f}%")
        lines.append(f"    指标: {' | '.join(detail)}")
        lines.append(f"    理由: {reason(code, name, s)}")
        lines.append("")
    report = "\n".join(lines)
    print(report)
    out = Path(__file__).resolve().parent.parent / "data" / "report-latest.txt"
    out.write_text(report, encoding="utf-8")
    print(f"wrote -> {out}", file=sys.stderr)


if __name__ == "__main__":
    main()
