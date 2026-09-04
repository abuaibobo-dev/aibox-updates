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
import fetch_market as fm


def _yahoo_price(code):
    """Real-time price series from Yahoo (1y daily). Fresh even intraday."""
    import urllib.request as uq
    url = (f"https://query1.finance.yahoo.com/v8/finance/chart/{code}.T"
           f"?range=1y&interval=1d")
    req = uq.Request(url, headers={"User-Agent": "Mozilla/5.0"})
    with uq.urlopen(req, timeout=20) as r:
        d = json.load(r)
    res = d["chart"]["result"][0]
    m = res["meta"]
    ts = res.get("timestamp") or []
    q = res["indicators"]["quote"][0]
    opens, highs, lows, closes, vols = (q["open"], q["high"], q["low"],
                                        q["close"], q["volume"])
    rows = []
    for i, t in enumerate(ts):
        c = closes[i]
        if c is None:
            continue
        rows.append((t, opens[i], highs[i], lows[i], c, vols[i] or 0))
    if len(rows) < 60:
        raise ValueError(f"insufficient yahoo data for {code}")
    closes = [r[4] for r in rows]
    vols = [r[5] for r in rows]
    last90 = rows[-90:]
    return {
        "closes": closes, "vols": vols,
        "candles": [{"t": t, "o": o, "h": hi, "l": lo, "c": c}
                    for t, o, hi, lo, c, _v in last90],
        "price": (m.get("regularMarketPrice") or closes[-1]),
        "name": m.get("longName") or m.get("shortName") or "",
    }


def build_analysis(code):
    """Return dict for one stock or raise."""
    # --- real-time prices from Yahoo (fresh; falls back to local DB) ---
    conn = fp.db_conn()
    meta = conn.execute(
        "SELECT name, industry FROM stocks WHERE code=?", (code,)
    ).fetchone()
    conn.close()
    local_industry = meta[1] if meta else ""

    try:
        px = _yahoo_price(code)
    except Exception:
        # fallback: local 10y DB
        conn = fp.db_conn()
        rows = conn.execute(
            "SELECT ts, open, high, low, close, volume FROM daily WHERE code=? "
            "AND close IS NOT NULL ORDER BY ts", (code,)
        ).fetchall()
        conn.close()
        if not rows:
            raise ValueError(f"no price data for {code}")
        closes = [r[4] for r in rows]
        vols = [r[5] or 0 for r in rows]
        px = {
            "closes": closes, "vols": vols,
            "candles": [{"t": r[0], "o": r[1], "h": r[2], "l": r[3], "c": r[4]}
                        for r in rows[-90:]],
            "price": closes[-1],
            "name": (meta[0] if meta else "") or f"code {code}",
        }

    closes, vols = px["closes"], px["vols"]
    price = px["price"]
    name = px["name"] or (meta[0] if meta else "") or f"code {code}"
    industry = local_industry

    # technical snapshot
    tech = sc.score_stock(closes, vols)
    trend = tech["score"] if tech else None
    # indicators + candles (last 90 bars)
    ind = sc_indicator_snapshot(closes)
    candles = px["candles"]

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
        if parsed.path == "/market":
            indices = []
            for ix in fm.INDICES:
                try:
                    snap = fm.index_snapshot(ix["symbol"])
                    if snap:
                        snap["key"] = ix["key"]
                        snap["name"] = ix["name"]
                        indices.append(snap)
                except Exception:
                    pass
            self._send(200, {
                "date": time.strftime("%Y-%m-%d"),
                "generated": time.strftime("%H:%M:%S UTC"),
                "indices": indices,
            })
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
