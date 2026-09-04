#!/usr/bin/env python3
"""Local analysis backend for the JP Stock app.

Serves GET /analyze?code=7203  -> JSON with real-time valuation (irbank),
technical indicators + candles (local market.sqlite), and an AI analyst note
(DeepSeek). Run:  DEEPSEEK_API_KEY=sk-... python3 server.py [port]
Stdlib only. AI step is optional (skipped when no key / on failure).
"""
import json
import sys
import time
import urllib.parse
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
sys.path.insert(0, str(Path(__file__).resolve().parent))

import fetch_prices as fp
import fetch_fundamentals as ff
import fetch_hist_fund as fhf  # noqa: F401  (DB path helpers)
import ai_explain
import scoring as sc
import export_daily as ed


def build_analysis(code):
    """Return dict for one stock or raise."""
    # --- price data from local DB (10y OHLCV) ---
    conn = fp.db_conn()
    rows = conn.execute(
        "SELECT ts, open, high, low, close, volume FROM daily WHERE code=? "
        "AND close IS NOT NULL ORDER BY ts", (code,)
    ).fetchall()
    meta = conn.execute(
        "SELECT name, industry, last_price FROM stocks WHERE code=?", (code,)
    ).fetchone()
    conn.close()
    if not rows:
        raise ValueError(f"no price data for {code}")
    closes = [r[4] for r in rows]
    vols = [r[5] or 0 for r in rows]
    name = (meta[0] if meta else "") or f"code {code}"
    industry = meta[1] if meta else ""
    price = closes[-1]

    # technical snapshot
    tech = sc.score_stock(closes, vols)
    trend = tech["score"] if tech else None
    # indicators + candles (last 90 bars)
    ind = sc_indicator_snapshot(closes)

    # --- real-time valuation from irbank (fresh fetch) ---
    fund = {}
    per = per_f = pbr = roe = roe_f = dy = None
    try:
        row, _ = ff.get_one(code)
        per, per_f, pbr = row["per"], row["per_f"], row["pbr"]
        roe, roe_f, dy = row["roe"], row["roe_f"], row["div_yield"]
        fund = row
    except Exception as e:
        pass  # valuation unavailable; keep None

    # valuation percentile vs history
    hist = ed.load_hist_series()
    hp = ed.valuation_percentiles(code, per_f or per, pbr, hist)
    per_now = per_f or per
    m126 = tech["m126"] if tech else None
    m252 = tech["m252"] if tech else None
    dd = tech["dd_pct"] if tech else None

    # candles for the chart: last 90 bars {t,o,h,l,c}
    last90 = rows[-90:]
    candles = [{
        "t": t, "o": o, "h": hi, "l": lo, "c": c,
    } for t, o, hi, lo, c, _v in last90]

    # --- AI analyst note (optional) ---
    ai_zh, ai_ja = "", ""
    fake_pick = {
        "code": code, "name": name, "industry": industry, "price": price,
        "score": round((trend or 50), 1),
        "per": per_now, "per_pct": hp.get("per_pct"),
        "pbr": pbr, "pbr_pct": hp.get("pbr_pct"),
        "roe": roe or roe_f, "div_yield": dy,
        "m6": m126, "m12": m252, "dd": dd,
    }
    try:
        out = ai_explain.call_deepseek(ai_explain._build_prompt(fake_pick))
        ai_zh = (out.get("zh") or "").strip()[:500]
        ai_ja = (out.get("ja") or "").strip()[:300]
    except Exception:
        pass

    return {
        "code": code, "name": name, "industry": industry, "price": price,
        "score": round((trend or 50), 1),
        "per": per_now, "per_f": per_f, "per_pct": hp.get("per_pct"),
        "pbr": pbr, "pbr_pct": hp.get("pbr_pct"),
        "roe": roe or roe_f, "div_yield": dy,
        "m6": m126, "m12": m252, "dd": dd,
        "indicators": ind,
        "candles": candles,
        "ai_reason": ai_zh,
        "ai_reason_ja": ai_ja,
        "generated": time.strftime("%Y-%m-%d %H:%M:%S"),
    }


def sc_indicator_snapshot(closes):
    """Return RSI/MACD/BB/MA like the App computes, computed server-side."""
    # mirror Android computeIndicators using a small local reimplementation
    def ema(vals, p):
        out = [vals[0]]
        k = 2.0 / (p + 1)
        for v in vals[1:]:
            out.append(v * k + out[-1] * (1 - k))
        return out

    n = len(closes)
    if n < 26:
        return {}
    e12 = ema(closes, 12)
    e26 = ema(closes, 26)
    macd_line = [a - b for a, b in zip(e12, e26)]
    sig = ema(macd_line, 9)
    macd = macd_line[-1]
    signal = sig[-1]

    def sma(vals, w):
        if len(vals) < w:
            return None
        return sum(vals[-w:]) / w

    mid = sma(closes, 20)
    upper = lower = bb = None
    if mid:
        s = closes[-20:]
        sd = (sum((x - mid) ** 2 for x in s) / 20) ** 0.5
        upper, lower = mid + 2 * sd, mid - 2 * sd
        if upper > lower:
            bb = max(0.0, min(100.0, (closes[-1] - lower) / (upper - lower) * 100))
    # RSI via ai? no - compute
    r = _rsi(closes[-60:])
    return {
        "rsi": round(r, 1) if r is not None else None,
        "macd": round(macd, 3), "macd_signal": round(signal, 3),
        "macd_hist": round(macd - signal, 3),
        "bb_pct": round(bb, 1) if bb is not None else None,
        "ma20": mid, "ma60": sma(closes, 60),
        "ma200": sma(closes, 200) if n >= 200 else None,
    }


def _rsi(vals, period=14):
    if len(vals) <= period:
        return None
    gain = loss = 0.0
    for i in range(1, period + 1):
        d = vals[i] - vals[i - 1]
        if d >= 0:
            gain += d
        else:
            loss -= d
    ag, al = gain / period, loss / period
    for i in range(period + 1, len(vals)):
        d = vals[i] - vals[i - 1]
        ag = (ag * (period - 1) + (d if d > 0 else 0)) / period
        al = (al * (period - 1) + (-d if d < 0 else 0)) / period
    if al == 0:
        return 100.0
    return 100.0 - 100.0 / (1 + ag / al)


class Handler(BaseHTTPRequestHandler):
    def _send(self, code, payload):
        body = json.dumps(payload, ensure_ascii=False).encode()
        self.send_response(code)
        self.send_header("Content-Type", "application/json; charset=utf-8")
        self.send_header("Access-Control-Allow-Origin", "*")
        self.send_header("Content-Length", str(len(body)))
        self.end_headers()
        self.wfile.write(body)

    def do_OPTIONS(self):
        self.send_response(204)
        self.send_header("Access-Control-Allow-Origin", "*")
        self.send_header("Access-Control-Allow-Methods", "GET, OPTIONS")
        self.send_header("Access-Control-Allow-Headers", "*")
        self.end_headers()

    def do_GET(self):
        parsed = urllib.parse.urlparse(self.path)
        if parsed.path == "/health":
            self._send(200, {"ok": True})
            return
        if parsed.path == "/analyze":
            q = urllib.parse.parse_qs(parsed.query)
            code = (q.get("code") or [""])[0].strip()
            if not code or not code.isdigit() or len(code) != 4:
                self._send(400, {"error": "code must be 4 digits"})
                return
            try:
                self._send(200, build_analysis(code))
            except ValueError as e:
                self._send(404, {"error": str(e)})
            except Exception as e:
                self._send(500, {"error": f"{type(e).__name__}: {e}"})
            return
        self._send(404, {"error": "not found"})

    def log_message(self, *a):
        pass


def main():
    port = int(sys.argv[1]) if len(sys.argv) > 1 else 8090
    server = ThreadingHTTPServer(("0.0.0.0", port), Handler)
    print(f"analysis backend on :{port}  (DEEPSEEK_API_KEY "
          f"{'set' if __import__('os').environ.get('DEEPSEEK_API_KEY') else 'NOT set'})",
          file=sys.stderr)
    try:
        server.serve_forever()
    except KeyboardInterrupt:
        pass


if __name__ == "__main__":
    main()
