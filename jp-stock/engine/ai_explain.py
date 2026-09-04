#!/usr/bin/env python3
"""DeepSeek-powered analyst notes for today's picks.

Reads data/daily.json, calls DeepSeek chat to write a short analyst-style
Chinese note (plus a Japanese line) for each pick, and writes the result back
into daily.json under `ai_reason` / `ai_reason_ja`. Purely additive; any pick
that fails keeps its rule-based reason. Requires DEEPSEEK_API_KEY in env.
Zero deps (urllib only).

Usage: python3 ai_explain.py [--skip-if-missing]
"""
import json
import os
import sys
import urllib.request
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
DAILY = ROOT / "data" / "daily.json"
ENDPOINT = "https://api.deepseek.com/chat/completions"
MODEL = "deepseek-chat"
MAX_CHARS = 140


def _build_prompt(p):
    pct = lambda v, suf="": (f"  {suf}历史分位 {v:.0f}%" if v is not None else "")
    parts = [
        f"标的: {p.get('name')}(代码{p.get('code')}, {p.get('industry')})",
        f"现价 ¥{p.get('price')} 综合评分 {p.get('score')}",
    ]
    if p.get("per"): parts.append(f"PE {p['per']:.1f}倍{pct(p.get('per_pct'),'PE')}")
    if p.get("pbr"): parts.append(f"PB {p['pbr']:.2f}{pct(p.get('pbr_pct'),'PB')}")
    if p.get("roe"): parts.append(f"ROE {p['roe']:.1f}%")
    if p.get("div_yield"): parts.append(f"股息率 {p['div_yield']:.1f}%")
    if p.get("m6") is not None: parts.append(f"6月涨幅 {p['m6']*100:+.0f}%")
    if p.get("m12") is not None: parts.append(f"1年涨幅 {p['m12']*100:+.0f}%")
    if p.get("dd") is not None: parts.append(f"距52周高 {p['dd']*100:+.0f}%")
    data = "\n".join(parts)
    return (
        "你是为持牌投顾做辅助分析的日本股市分析师。根据给定数据，面向个人投资者输出"
        "一份结构化研究笔记（简体中文，200-300字）。要求：\n"
        "第1行给出结论（看多/中性/看空 + 一句话定性）；\n"
        "随后按「基本面」「估值」「股息与回报」「走势与动量」「主要风险」「关注要点」"
        "分点（每点一行，基于数据给出具体判断）。\n"
        "客观严谨，不做收益承诺，明确提示不构成投资建议。\n"
        f"数据如下:\n{data}\n"
        "只输出 JSON: {\"zh\": \"用\\n分行的完整中文笔记\", "
        "\"ja\": \"对应的一行日文摘要\"}"
    )


def call_deepseek(prompt):
    key = os.environ.get("DEEPSEEK_API_KEY")
    if not key:
        raise RuntimeError("DEEPSEEK_API_KEY not set")
    body = json.dumps({
        "model": MODEL,
        "messages": [
            {"role": "system", "content": "你是严谨的日本股市分析师。"},
            {"role": "user", "content": prompt},
        ],
        "temperature": 0.4,
        "max_tokens": 800,
        "response_format": {"type": "json_object"},
    }).encode()
    req = urllib.request.Request(
        ENDPOINT, data=body,
        headers={"Content-Type": "application/json",
                 "Authorization": f"Bearer {key}"},
    )
    with urllib.request.urlopen(req, timeout=60) as r:
        d = json.load(r)
    content = d["choices"][0]["message"]["content"]
    return json.loads(content)


def main():
    if not DAILY.exists():
        print("no daily.json", file=sys.stderr)
        return
    daily = json.loads(DAILY.read_text(encoding="utf-8"))
    for p in daily.get("picks", []):
        try:
            out = call_deepseek(_build_prompt(p))
            zh = (out.get("zh") or "").strip()
            ja = (out.get("ja") or "").strip()
            p["ai_reason"] = zh[:400]
            p["ai_reason_ja"] = ja[:300]
            print(f"{p.get('code')}: AI note ok ({len(zh)}字)", file=sys.stderr)
        except Exception as e:
            print(f"{p.get('code')}: AI skipped ({type(e).__name__}: {str(e)[:80]})",
                  file=sys.stderr)
    DAILY.write_text(json.dumps(daily, ensure_ascii=False, indent=2),
                     encoding="utf-8")
    print("daily.json updated with AI notes", file=sys.stderr)


if __name__ == "__main__":
    main()
