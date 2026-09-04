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

    act = compute_action(candles, price)
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
        "advice": act,
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


def compute_action(candles, price):
    """Rule-based actionable levels (dip-buy zone / stop / targets) in plain
    words. Uses the recent ~20d swing structure so levels are meaningful to a
    client (not 52-week extremes). None when history too short."""
    if not candles or len(candles) < 30:
        return None

    def f(v):
        return f"{v:,.0f}" if v >= 1000 else f"{v:,.1f}"

    hs = [c["h"] for c in candles]
    ls = [c["l"] for c in candles]
    sup1 = min(ls[-20:])              # near swing support
    sup2 = min(ls[-60:])              # deeper support (watch)
    res1 = max(hs[-20:])              # near swing resistance
    hi60 = max(hs[-60:])

    # Dip-buy zone sits at/under current price; never above it.
    near_sup = price <= sup1 * 1.10
    if near_sup:
        # price at/near support -> buy on the support band
        buy_low = sup1 * 0.99
        buy_high = min(price, sup1 * 1.06)
        if buy_high <= buy_low:
            buy_high = buy_low * 1.03
    else:
        # extended above support -> wait for a shallow pullback
        buy_low = price * 0.93
        buy_high = price * 0.97

    # Stop a fixed ~6% under the buy-zone floor (tight, actionable).
    stop = buy_low * 0.94
    stop_from_buy = 6.0

    t1 = res1 if res1 > price else price * 1.05
    t2 = hi60 * 1.02 if hi60 > price * 1.02 else t1 * 1.04
    if t2 <= t1:
        t2 = t1 * 1.04
    upside1 = (t1 / price - 1.0) * 100

    if near_sup:
        zh = (f"现价 {f(price)}，正处在近期支撑附近。操作参考（技术位，仅供参考，非保证）：\n"
              f"· 低吸区间：{f(buy_low)} ~ {f(buy_high)}（企稳分批）\n"
              f"· 止损参考：跌破 {f(stop)}（低吸下沿约-{stop_from_buy:.0f}%）离场\n"
              f"· 止盈参考：第一目标 {f(t1)}（约+{upside1:.0f}%，分批落袋），再看 {f(t2)}")
        ja = (f"株価 {f(price)}円（直近サポート付近）\n＜操作メモ＞※テクニカル参考値\n"
              f"・押し目買い目安：{f(buy_low)}〜{f(buy_high)}円\n"
              f"・損切り目安：{f(stop)}円割れ（約{stop_from_buy:.0f}%）\n"
              f"・利確目安：{f(t1)}円（約+{upside1:.0f}%）→ {f(t2)}円\n"
              f"※投資判断はお客様ご自身でお願いいたします")
    else:
        zh = (f"现价 {f(price)}，短期偏强、离支撑较远，追高风险大。建议：\n"
              f"· 关注回踩：{f(buy_low)} ~ {f(buy_high)} 一带再分批介入（勿追高）\n"
              f"· 若已在低位建仓：跌破 {f(stop)}（约-{stop_from_buy:.0f}%）止损\n"
              f"· 止盈参考：第一目标 {f(t1)}（约+{upside1:.0f}%），再看 {f(t2)}")
        ja = (f"株価 {f(price)}円（上昇基調・直近サポートから乖離）\n＜操作メモ＞※参考値\n"
              f"・押し目買い目安：{f(buy_low)}〜{f(buy_high)}円（高値追いは推奨せず）\n"
              f"・損切り目安：{f(stop)}円割れ（約{stop_from_buy:.0f}%）\n"
              f"・利確目安：{f(t1)}円（約+{upside1:.0f}%）→ {f(t2)}円\n"
              f"※投資判断はお客様ご自身でお願いいたします")

    return {"buy_low": round(buy_low, 2), "buy_high": round(buy_high, 2),
            "stop": round(stop, 2), "t1": round(t1, 2), "t2": round(t2, 2),
            "watch": round(sup2, 2),
            "upside1": round(upside1, 1), "zh": zh, "ja": ja}


def build_stocks():
    """Full Prime universe with latest close + day change, for browse/search.
    Reads prime.csv for JP names/industries and daily for the last two bars."""
    import collections
    conn = fp.db_conn()
    rows = conn.execute(
        """SELECT d.code, d.close,
                  (SELECT c2.close FROM daily c2
                   WHERE c2.code=d.code AND c2.ts<d.ts AND c2.close IS NOT NULL
                   ORDER BY c2.ts DESC LIMIT 1)
           FROM daily d
           WHERE d.ts=(SELECT MAX(ts) FROM daily WHERE code=d.code)
             AND d.close IS NOT NULL"""
    ).fetchall()
    conn.close()
    meta = {}
    csvf = ROOT / "data" / "prime.csv"
    if csvf.exists():
        for line in csvf.read_text(encoding="utf-8").splitlines():
            parts = line.split(",")
            if len(parts) >= 3:
                meta[parts[0]] = (parts[1], parts[2])
    ind_count = collections.Counter()
    stocks = []
    for code, close, prev in rows:
        nm, ind = meta.get(code, (f"{code}", ""))
        chg = ((close / prev - 1.0) * 100) if (prev and close) else None
        ind_count[ind] += 1
        stocks.append({"code": code, "name": nm, "industry": ind,
                       "price": close, "chg_pct": round(chg, 2) if chg is not None else None})
    industries = [{"name": n, "count": c}
                  for n, c in ind_count.most_common() if n]
    stocks.sort(key=lambda s: (s["industry"], s["code"]))
    return {"date": time.strftime("%Y-%m-%d"),
            "total": len(stocks),
            "industries": industries,
            "stocks": stocks}


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
        if parsed.path == "/stocks":
            payload = build_stocks()
            q = urllib.parse.parse_qs(urllib.parse.urlparse(self.path).query)
            ind = (q.get("industry") or [""])[0].strip()
            term = (q.get("q") or [""])[0].strip().lower()
            if ind or term:
                st = payload["stocks"]
                if ind:
                    st = [s for s in st if s["industry"] == ind]
                if term:
                    st = [s for s in st if term in s["code"].lower()
                          or term in s["name"].lower()]
                payload["stocks"] = st
                payload["total"] = len(st)
            self._send(200, payload)
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
