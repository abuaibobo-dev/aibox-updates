package dev.abuaibobo.jpstock

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject
import java.util.concurrent.TimeUnit

const val GITHUB_RAW =
    "https://raw.githubusercontent.com/abuaibobo-dev/aibox-updates/main/jp-stock/data"

// Default local analysis backend (server.py). Changeable in the app's
// Settings screen; same device => localhost, separate phone => LAN IP.
const val DEFAULT_BASE = "http://127.0.0.1:8092"

data class Pick(
    val code: String, val name: String, val industry: String,
    val price: Double, val score: Double,
    val per: Double?, val pbr: Double?, val roe: Double?, val divYield: Double?,
    val perPct: Double?, val pbrPct: Double?,
    val m6: Double?, val m12: Double?, val reason: String,
    val reasonJa: String = "",
    val aiReason: String = "",
    val tags: List<String> = emptyList(),
    val pitch: String = "",
    val pitchJa: String = "",
)

data class DailyFeed(val strategy: String, val picks: List<Pick>)

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

data class CandleBar(val ts: Long, val open: Double, val high: Double,
                     val low: Double, val close: Double)

data class AnalysisData(
    val code: String, val name: String, val industry: String,
    val price: Double, val score: Double,
    val per: Double?, val pbr: Double?, val roe: Double?, val divYield: Double?,
    val perPct: Double?, val pbrPct: Double?,
    val m6: Double?, val m12: Double?, val dd: Double?,
    val rsi: Double?, val macd: Double?, val macdSignal: Double?,
    val macdHist: Double?, val bbPct: Double?,
    val ma20: Double?, val ma60: Double?, val ma200: Double?,
    val candles: List<CandleBar>,
    val aiReason: String,
    val adviceZh: String = "",
    val adviceJa: String = "",
)

data class IndustryCat(val name: String, val count: Int)

data class StockQuote(val code: String, val name: String, val industry: String,
                      val price: Double, val chgPct: Double?)

data class StockFeed(val total: Int, val industries: List<IndustryCat>,
                     val stocks: List<StockQuote>)

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
    @Volatile
    var analysisBase: String = DEFAULT_BASE
        private set

    fun setAnalysisBase(url: String) {
        analysisBase = url.trim().trimEnd('/')
    }

    private val client = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(20, TimeUnit.SECONDS)
        .build()

    private suspend fun getText(url: String): String = withContext(Dispatchers.IO) {
        val req = Request.Builder().url(url).build()
        client.newCall(req).execute().use { resp ->
            if (!resp.isSuccessful) throw RuntimeException("HTTP ${resp.code}")
            resp.body!!.string()
        }
    }

    suspend fun fetchDaily(): DailyFeed = withContext(Dispatchers.IO) {
        val obj = JSONObject(getText("$GITHUB_RAW/daily.json"))
        val arr = obj.getJSONArray("picks")
        val picks = (0 until arr.length()).map { i ->
            val p = arr.getJSONObject(i)
            val tags = mutableListOf<String>()
            val ta = p.optJSONArray("tags")
            if (ta != null) {
                for (j in 0 until ta.length()) tags.add(ta.optString(j))
            }
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
                perPct = nullable(p, "per_pct"),
                pbrPct = nullable(p, "pbr_pct"),
                reason = p.optString("reason"),
                reasonJa = p.optString("reason_ja"),
                aiReason = p.optString("ai_reason"),
                tags = tags,
                pitch = p.optString("pitch"),
                pitchJa = p.optString("pitch_ja"),
            )
        }
        DailyFeed(obj.optString("strategy"), picks)
    }

    suspend fun fetchMarket(): MarketFeed = withContext(Dispatchers.IO) {
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

    /** Real-time indices from local backend; null when backend unreachable. */
    suspend fun fetchLiveIndices(): List<IndexQuote>? = withContext(Dispatchers.IO) {
        try {
            val o = JSONObject(getText("$analysisBase/market"))
            val ivals = o.getJSONArray("indices")
            (0 until ivals.length()).map { i ->
                val x = ivals.getJSONObject(i)
                IndexQuote(
                    key = x.optString("key"), name = x.optString("name"),
                    last = x.optDouble("last", 0.0),
                    chgDay = nullable(x, "chg_day"), chg5d = nullable(x, "chg_5d"),
                )
            }
        } catch (_: Exception) {
            null
        }
    }

    suspend fun fetchHistory(): HistoryFeed = withContext(Dispatchers.IO) {
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

    /** Fast backend reachability probe (4s budget). */
    suspend fun testConnection(): Boolean = withContext(Dispatchers.IO) {
        val quick = OkHttpClient.Builder()
            .connectTimeout(3, TimeUnit.SECONDS)
            .readTimeout(4, TimeUnit.SECONDS)
            .build()
        try {
            val req = Request.Builder().url("$analysisBase/health").build()
            quick.newCall(req).execute().use { it.isSuccessful }
        } catch (_: Exception) {
            false
        }
    }

    /** Full universe browse feed from local backend. */
    suspend fun fetchStocks(): StockFeed = withContext(Dispatchers.IO) {
        val o = JSONObject(getText("$analysisBase/stocks"))
        val ia = o.getJSONArray("industries")
        val industries = (0 until ia.length()).map { i ->
            val x = ia.getJSONObject(i)
            IndustryCat(x.optString("name"), x.optInt("count", 0))
        }
        val sa = o.getJSONArray("stocks")
        val stocks = (0 until sa.length()).map { i ->
            val s = sa.getJSONObject(i)
            StockQuote(s.optString("code"), s.optString("name"),
                s.optString("industry"), s.optDouble("price", 0.0),
                nullable(s, "chg_pct"))
        }
        StockFeed(o.optInt("total", 0), industries, stocks)
    }

    /** Candles straight from Yahoo v8 chart API (no auth needed). */
    suspend fun fetchCandles(code: String): List<KLine> = withContext(Dispatchers.IO) {
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

    /** Ask the local analysis backend for one stock's full parse + AI note. */
    suspend fun fetchAnalysis(code: String): AnalysisData = withContext(Dispatchers.IO) {
        val url = "$analysisBase/analyze?code=$code"
        val req = Request.Builder().url(url).build()
        val txt = client.newCall(req).execute().use { resp ->
            if (!resp.isSuccessful) {
                val body = resp.body?.string() ?: ""
                val msg = try {
                    JSONObject(body).optString("error", "HTTP ${resp.code}")
                } catch (_: Exception) { "HTTP ${resp.code}" }
                throw RuntimeException(msg)
            }
            resp.body!!.string()
        }
        val o = JSONObject(txt)
        val arr = o.getJSONArray("candles")
        val candles = (0 until arr.length()).map { i ->
            val c = arr.getJSONObject(i)
            CandleBar(c.getLong("t"), c.getDouble("o"), c.getDouble("h"),
                c.getDouble("l"), c.getDouble("c"))
        }
        val ind = o.optJSONObject("indicators") ?: JSONObject()
        fun d(oo: JSONObject, k: String): Double? =
            if (oo.has(k) && !oo.isNull(k)) oo.getDouble(k) else null
        AnalysisData(
            code = o.optString("code"),
            name = o.optString("name"),
            industry = o.optString("industry"),
            price = o.optDouble("price", 0.0),
            score = o.optDouble("score", 0.0),
            per = d(o, "per"), pbr = d(o, "pbr"),
            roe = d(o, "roe"), divYield = d(o, "div_yield"),
            perPct = d(o, "per_pct"), pbrPct = d(o, "pbr_pct"),
            m6 = d(o, "m6"), m12 = d(o, "m12"), dd = d(o, "dd"),
            rsi = d(ind, "rsi"), macd = d(ind, "macd"),
            macdSignal = d(ind, "macd_signal"), macdHist = d(ind, "macd_hist"),
            bbPct = d(ind, "bb_pct"),
            ma20 = d(ind, "ma20"), ma60 = d(ind, "ma60"), ma200 = d(ind, "ma200"),
            candles = candles,
            aiReason = o.optString("ai_reason"),
            adviceZh = o.optJSONObject("advice")?.optString("zh") ?: "",
            adviceJa = o.optJSONObject("advice")?.optString("ja") ?: "",
        )
    }
}
