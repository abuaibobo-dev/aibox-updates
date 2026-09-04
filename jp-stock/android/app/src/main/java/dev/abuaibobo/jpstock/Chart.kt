package dev.abuaibobo.jpstock

import androidx.compose.foundation.Canvas
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.nativeCanvas
import android.graphics.Paint
import kotlin.math.abs
import kotlin.math.sqrt

private val UpColor = Color(0xFFE53935)      // JP convention: red = up
private val DownColor = Color(0xFF1E88E5)    // blue = down

/**
 * Find support/resistance from swing highs/lows (window k), clustering prices
 * that are within ~1% so nearby pivots merge into one level. Returns the two
 * nearest levels below (supports) and above (resistances) the current price.
 */
fun findKeyLevels(highs: List<Double>, lows: List<Double>, cur: Double,
                  k: Int = 4): Pair<List<Double>, List<Double>> {
    if (highs.size < 2 * k + 1) return emptyList<Double>() to emptyList()
    val pivH = mutableListOf<Double>()
    val pivL = mutableListOf<Double>()
    for (i in k until highs.size - k) {
        var isH = true
        var isL = true
        for (j in -k..k) {
            if (highs[i + j] > highs[i]) isH = false
            if (lows[i + j] < lows[i]) isL = false
        }
        if (isH) pivH.add(highs[i])
        if (isL) pivL.add(lows[i])
    }
    fun cluster(vals: List<Double>): List<Double> {
        if (vals.isEmpty()) return emptyList()
        val s = vals.sorted()
        val groups = mutableListOf<MutableList<Double>>()
        for (v in s) {
            val g = groups.lastOrNull()
            if (g != null && v - g.last() <= cur * 0.012) g.add(v)
            else groups.add(mutableListOf(v))
        }
        return groups.map { it.average() }
    }
    var below = cluster(pivL.filter { it < cur * 0.995 }).filter { it < cur }
    var above = cluster(pivH.filter { it > cur * 1.005 }).filter { it > cur }
    // fixed display: always 2 supports + 2 resistances; top up with percentage
    // probes when the swing detector found fewer.
    val supProbes = doubleArrayOf(0.99, 0.965, 0.94)
    for (probe in supProbes) {
        if (below.size >= 2) break
        val v = cur * probe
        if (!below.any { abs(it - v) <= cur * 0.004 }) {
            below = (below + v).sorted()
        }
    }
    val resProbes = doubleArrayOf(1.01, 1.035, 1.07)
    for (probe in resProbes) {
        if (above.size >= 2) break
        val v = cur * probe
        if (!above.any { abs(it - v) <= cur * 0.004 }) {
            above = (above + v).sorted()
        }
    }
    return (below.takeLast(2)) to (above.take(2))
}

/**
 * Minimal candlestick chart drawn with Canvas. Red/blue follow the JP color
 * convention (red up, blue down). Enough for a quick trend read.
 */
@Composable
fun CandlestickChart(
    bars: List<KLine>,
    modifier: Modifier = Modifier,
    supports: List<Double> = emptyList(),
    resistances: List<Double> = emptyList(),
) {
    if (bars.size < 2) return
    val closes = bars.map { it.close }
    var lo = bars.minOf { it.low }
    var hi = bars.maxOf { it.high }
    val pad = (hi - lo) * 0.05
    lo -= pad; hi += pad
    // ensure levels sit inside the plotted range
    for (lv in supports + resistances) {
        if (lv < lo) lo = lv
        if (lv > hi) hi = lv
    }
    val vPad = (hi - lo) * 0.02
    lo -= vPad; hi += vPad

    // moving average 20 & 60
    val ma20 = movingAvg(closes, 20)
    val ma60 = movingAvg(closes, 60)

    Canvas(modifier = modifier) {
        val w = size.width
        val h = size.height
        val n = bars.size
        val slot = w / n
        val bw = (slot * 0.6f).coerceAtMost(8f)
        fun y(v: Double): Float = ((hi - v) / (hi - lo) * h).toFloat()

        bars.forEachIndexed { i, b ->
            val x = i * slot + slot / 2
            val up = b.close >= b.open
            val color = if (up) UpColor else DownColor
            // wick
            drawLine(color, Offset(x, y(b.high)), Offset(x, y(b.low)), 1f)
            // body
            val yOpen = y(b.open)
            val yClose = y(b.close)
            val top = minOf(yOpen, yClose)
            val bodyH = abs(yClose - yOpen).coerceAtLeast(1.5f)
            drawRect(color, Offset(x - bw / 2, top), Size(bw, bodyH))
        }

        // support / resistance level lines: short right-hand ticks + labels
        if (supports.isNotEmpty() || resistances.isNotEmpty()) {
            val dashed = PathEffect.dashPathEffect(floatArrayOf(8f, 6f))
            val tp = Paint().apply {
                isAntiAlias = true
                textSize = 11f
                color = android.graphics.Color.WHITE
                textAlign = android.graphics.Paint.Align.RIGHT
            }
            fun priceLabel(v: Double): String =
                if (v >= 1000) String.format(java.util.Locale.US, "%.0f", v)
                else String.format(java.util.Locale.US, "%.1f", v)
            val tickFrom = w * 0.62f
            fun drawLevels(list: List<Double>, color: Color) {
                for (pv in list) {
                    val yy = y(pv)
                    drawLine(color, Offset(tickFrom, yy), Offset(w, yy), 1.5f,
                        pathEffect = dashed)
                    drawIntoCanvas { canvas ->
                        canvas.nativeCanvas.drawText(priceLabel(pv), w - 8f, yy - 4f, tp)
                    }
                }
            }
            drawLevels(supports, DownBlue)
            drawLevels(resistances, UpRed)
        }

        // moving averages as lines
        drawPolyline(ma20, n, slot, ::y, Color(0xFFFFB300))
        drawPolyline(ma60, n, slot, ::y, Color(0xFF9C27B0))
    }
}

private fun movingAvg(vals: List<Double>, win: Int): DoubleArray {
    val out = DoubleArray(vals.size) { Double.NaN }
    var sum = 0.0
    for (i in vals.indices) {
        sum += vals[i]
        if (i >= win) sum -= vals[i - win]
        if (i >= win - 1) out[i] = sum / win
    }
    return out
}

private fun androidx.compose.ui.graphics.drawscope.DrawScope.drawPolyline(
    series: DoubleArray, n: Int, slot: Float,
    yOf: (Double) -> Float, color: Color,
) {
    val path = Path()
    var started = false
    for (i in 0 until n) {
        if (series[i].isNaN()) continue
        val x = i * slot + slot / 2
        val y = yOf(series[i])
        if (!started) {
            path.moveTo(x, y); started = true
        } else {
            path.lineTo(x, y)
        }
    }
    drawPath(path, color, style = Stroke(width = 2f, cap = StrokeCap.Round))
}

// --- technical indicators computed from close series (pure functions) ---

data class TechIndicators(
    val rsi: Double?,
    val macd: Double?,
    val macdSignal: Double?,
    val macdHist: Double?,
    val bbUpper: Double?,
    val bbMid: Double?,
    val bbLower: Double?,
    val bbPct: Double?,
    val ma20: Double?,
    val ma60: Double?,
    val ma200: Double?,
)

private fun emaSeries(vals: List<Double>, period: Int): DoubleArray {
    val out = DoubleArray(vals.size) { Double.NaN }
    if (vals.isEmpty()) return out
    val k = 2.0 / (period + 1)
    var prev = vals[0]
    out[0] = prev
    for (i in 1 until vals.size) {
        prev = vals[i] * k + prev * (1 - k)
        out[i] = prev
    }
    return out
}

fun rsi(closes: List<Double>, period: Int = 14): Double? {
    if (closes.size <= period) return null
    var gain = 0.0
    var loss = 0.0
    for (i in 1..period) {
        val d = closes[i] - closes[i - 1]
        if (d >= 0) gain += d else loss -= d
    }
    var avgGain = gain / period
    var avgLoss = loss / period
    for (i in period + 1 until closes.size) {
        val d = closes[i] - closes[i - 1]
        avgGain = (avgGain * (period - 1) + (if (d > 0) d else 0.0)) / period
        avgLoss = (avgLoss * (period - 1) + (if (d < 0) -d else 0.0)) / period
    }
    if (avgLoss == 0.0) return 100.0
    return 100.0 - 100.0 / (1 + avgGain / avgLoss)
}

private fun stdDev(vals: List<Double>, from: Int, to: Int, mean: Double): Double {
    var s = 0.0
    for (i in from until to) s += (vals[i] - mean) * (vals[i] - mean)
    return sqrt(s / (to - from))
}

fun computeIndicators(closes: List<Double>): TechIndicators {
    val n = closes.size
    if (n < 26) return TechIndicators(null, null, null, null, null, null, null, null, null, null, null)
    val ema12 = emaSeries(closes, 12)
    val ema26 = emaSeries(closes, 26)
    val macdLine = DoubleArray(n) { ema12[it] - ema26[it] }
    val signal = emaSeries(macdLine.toList(), 9)
    val macd = macdLine.last()
    val sig = signal.last()
    val hist = macd - sig

    val ma20 = movingAvg(closes, 20)
    val ma60 = movingAvg(closes, 60)
    val ma200 = if (n >= 200) movingAvg(closes, 200) else null
    val mid = ma20[n - 1]
    val sd = stdDev(closes, n - 20, n, mid)
    val upper = mid + 2 * sd
    val lower = mid - 2 * sd
    val price = closes.last()
    val bbPct = if (upper - lower > 0) ((price - lower) / (upper - lower) * 100).coerceIn(0.0, 100.0) else null

    fun lastOr(a: DoubleArray?): Double? = a?.last()?.takeIf { !it.isNaN() }
    return TechIndicators(
        rsi = rsi(closes),
        macd = macd,
        macdSignal = sig,
        macdHist = hist,
        bbUpper = upper,
        bbMid = mid,
        bbLower = lower,
        bbPct = bbPct,
        ma20 = lastOr(ma20),
        ma60 = lastOr(ma60),
        ma200 = lastOr(ma200),
    )
}
