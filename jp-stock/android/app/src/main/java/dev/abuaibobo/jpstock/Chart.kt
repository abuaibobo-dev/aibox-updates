package dev.abuaibobo.jpstock

import androidx.compose.foundation.Canvas
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import kotlin.math.abs

private val UpColor = Color(0xFFE53935)      // JP convention: red = up
private val DownColor = Color(0xFF1E88E5)    // blue = down

/**
 * Minimal candlestick chart drawn with Canvas. Red/blue follow the JP color
 * convention (red up, blue down). Enough for a quick trend read.
 */
@Composable
fun CandlestickChart(bars: List<KLine>, modifier: Modifier = Modifier) {
    if (bars.size < 2) return
    val closes = bars.map { it.close }
    var lo = bars.minOf { it.low }
    var hi = bars.maxOf { it.high }
    val pad = (hi - lo) * 0.05
    lo -= pad; hi += pad

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
            drawRect(color, Offset(x - bw / 2, top), bw, bodyH)
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
