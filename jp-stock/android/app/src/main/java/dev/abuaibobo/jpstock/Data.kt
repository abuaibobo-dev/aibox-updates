package dev.abuaibobo.jpstock

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.util.concurrent.TimeUnit

const val GITHUB_RAW =
    "https://raw.githubusercontent.com/abuaibobo-dev/aibox-updates/main/jp-stock/data"

data class Pick(
    val code: String, val name: String, val industry: String,
    val price: Double, val score: Double,
    val per: Double?, val pbr: Double?, val roe: Double?, val divYield: Double?,
    val m6: Double?, val m12: Double?, val reason: String,
    val reasonJa: String = "",
)

data class IndexQuote(
    val key: String, val name: String, val last: Double,
    val chgDay: Double?, val chg5d: Double?,
)

data class Sector(val name: String, val chgDay: Double, val count: Int)

data class MarketFeed(
    val date: String,
    val indices: List<IndexQuote>,
    val sectors: List<Sector>,
)

data class KLine(val ts: Long, val open: Double, val high: Double,
                 val low: Double, val close: Double, val volume: Long)

data class TrackedPick(
    val code: String, val name: String, val price: Double,
    val retPct: Double?, val score: Double, val date: String,
)

data class HistoryFeed(
    val updated: String,
    val days: List<HistoryDay>,
)

data class HistoryDay(val date: String, val picks: List<TrackedPick>)

object Api {
    private val client = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(20, TimeUnit.SECONDS)
        .build()

    private fun getText(url: String): String = withContext(Dispatchers.IO) {
        val req = Request.Builder().url(url).build()
        client.newCall(req).execute().use { resp ->
            if (!resp.isSuccessful) throw RuntimeException("HTTP ${resp.code}")
            resp.body!!.string()
        }
    }

    fun fetchDaily(): List<Pick> = withContext(Dispatchers.IO) {
        val obj = JSONObject(getText("$GITHUB_RAW/daily.json"))
        val arr = obj.getJSONArray("picks")
        (0 until arr.length()).map { i ->
            val p = arr.getJSONObject(i)
            Pick(
                code = p.optString("code"),
                name = p.optString("name"),
                industry = p.optString("industry"),
                price = p.optDouble("price", 0.0),
                score = p.optDouble("score", 0.0),
                per = nullable(p, "per"),
                pbr = nullable(p, "pbr"),
                roe = nullable(p, "roe"),
                divYield = nullable(p, "div_yield"),
                m6 = nullable(p, "m6"),
                m12 = nullable(p, "m12"),
                reason = p.optString("reason"),
                reasonJa = p.optString("reason_ja"),
            )
        }
    }

    fun fetchMarket(): MarketFeed = withContext(Dispatchers.IO) {
        val obj = JSONObject(getText("$GITHUB_RAW/market.json"))
        val ivals = obj.getJSONArray("indices")
        val indices = (0 until ivals.length()).map { i ->
            val x = ivals.getJSONObject(i)
            IndexQuote(
                key = x.optString("key"), name = x.optString("name"),
                last = x.optDouble("last", 0.0),
                chgDay = nullable(x, "chg_day"), chg5d = nullable(x, "chg_5d"),
            )
        }
        val sarr = obj.getJSONArray("sectors")
        val sectors = (0 until sarr.length()).map { i ->
            val s = sarr.getJSONObject(i)
            Sector(s.optString("name"), s.optDouble("chg_day", 0.0), s.optInt("count", 0))
        }
        MarketFeed(obj.optString("date"), indices, sectors)
    }

    fun fetchHistory(): HistoryFeed = withContext(Dispatchers.IO) {
        val obj = JSONObject(getText("$GITHUB_RAW/history.json"))
        val darr = obj.getJSONArray("days")
        val days = (0 until darr.length()).map { di ->
            val d = darr.getJSONObject(di)
            val pks = d.getJSONArray("picks")
            val picks = (0 until pks.length()).map { pi ->
                val p = pks.getJSONObject(pi)
                TrackedPick(
                    code = p.optString("code"),
                    name = p.optString("name"),
                    price = p.optDouble("price", 0.0),
                    retPct = nullable(p, "ret_pct"),
                    score = p.optDouble("score", 0.0),
                    date = d.optString("date"),
                )
            }
            HistoryDay(d.optString("date"), picks)
        }
        HistoryFeed(obj.optString("updated"), days)
    }

    /** Candles straight from Yahoo v8 chart API (no auth needed). */
    fun fetchCandles(code: String): List<KLine> = withContext(Dispatchers.IO) {
        val url = "https://query1.finance.yahoo.com/v8/finance/chart/$code.T" +
            "?range=1y&interval=1d"
        val req = Request.Builder().url(url)
            .header("User-Agent", "Mozilla/5.0 (Linux; Android 13)")
            .build()
        val txt = client.newCall(req).execute().use { resp ->
            if (!resp.isSuccessful) throw RuntimeException("HTTP ${resp.code}")
            resp.body!!.string()
        }
        val r = JSONObject(txt).getJSONObject("chart").getJSONArray("result")
            .getJSONObject(0)
        val tsArr = r.getJSONArray("timestamp")
        val q = r.getJSONObject("indicators").getJSONArray("quote").getJSONObject(0)
        val closes = q.getJSONArray("close")
        val list = mutableListOf<KLine>()
        val opens = q.getJSONArray("open")
        val highs = q.getJSONArray("high")
        val lows = q.getJSONArray("low")
        val vols = q.getJSONArray("volume")
        for (i in 0 until tsArr.length()) {
            val c = closes.optDouble(i, Double.NaN)
            if (c.isNaN()) continue
            list.add(
                KLine(
                    ts = tsArr.getLong(i),
                    open = opens.optDouble(i, Double.NaN),
                    high = highs.optDouble(i, Double.NaN),
                    low = lows.optDouble(i, Double.NaN),
                    close = c,
                    volume = vols.optLong(i, 0L),
                )
            )
        }
        list
    }

    private fun nullable(o: JSONObject, k: String): Double? =
        if (o.has(k) && !o.isNull(k)) o.getDouble(k) else null
}
