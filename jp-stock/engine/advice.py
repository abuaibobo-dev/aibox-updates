"""Actionable price levels (dip-buy zone / stop / targets) shared by the
analysis backend (server.py) and the daily export (export_daily.py). Pure,
no deps. Input candles = [{"h": high, "l": low, ...}, ...] (recent bars)."""


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
        buy_low = sup1 * 0.99
        buy_high = min(price, sup1 * 1.06)
        if buy_high <= buy_low:
            buy_high = buy_low * 1.03
    else:
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
