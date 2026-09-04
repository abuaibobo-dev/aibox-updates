package dev.abuaibobo.jpstock

import android.content.ContentValues
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import android.widget.Toast
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.IntSize
import kotlinx.coroutines.launch
import java.io.ByteArrayOutputStream
import java.io.File
import kotlin.math.abs

/**
 * Chart that also supports LONG-PRESS to export a PNG of the candles to the
 * gallery. Rendering for the export is done with plain android.graphics.Canvas
 * (no experimental Compose graphics API), so it always compiles.
 */
@Composable
fun CapturableChart(
    bars: List<KLine>,
    modifier: Modifier = Modifier,
    supports: List<Double> = emptyList(),
    resistances: List<Double> = emptyList(),
    fileName: String = "chart",
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var size by remember { androidx.compose.runtime.mutableStateOf(IntSize.Zero) }

    Box(
        modifier
            .onSizeChangedCompat { size = it }
            .pointerInput(Unit) {
                detectTapGestures(onLongPress = {
                    val sz = size
                    if (sz.width > 0 && sz.height > 0 && bars.size > 1) {
                        scope.launch {
                            val bmp = renderChart(bars, supports, resistances,
                                sz.width, sz.height)
                            val ok = saveImage(context, bmp, fileName)
                            Toast.makeText(
                                context,
                                if (ok) "已保存K线图到相册(JPStock)" else "保存失败",
                                Toast.LENGTH_SHORT
                            ).show()
                        }
                    }
                })
            }
    ) {
        CandlestickChart(bars = bars, modifier = Modifier.matchParentSize(),
            supports = supports, resistances = resistances)
    }
}

private fun Modifier.onSizeChangedCompat(block: (IntSize) -> Unit): Modifier =
    this.then(androidx.compose.ui.layout.onSizeChanged { block(IntSize(it.width, it.height)) })

/** Draw the same visual (monochrome bg + JP candles + level ticks) to a Bitmap. */
private fun renderChart(bars: List<KLine>, supports: List<Double>,
                        resistances: List<Double>, w: Int, h: Int): Bitmap {
    val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
    val c = Canvas(bmp)
    val up = 0xFFFF5A5F.toInt()
    val down = 0xFF41A0FF.toInt()
    val white = 0xFFF0F2F4.toInt()
    val gray = 0xFF9AA1A8.toInt()

    c.drawColor(0xFF0A0B0D.toInt())

    var lo = bars.minOf { it.low }
    var hi = bars.maxOf { it.high }
    for (lv in supports + resistances) {
        if (lv < lo) lo = lv
        if (lv > hi) hi = lv
    }
    val pad = (hi - lo) * 0.06
    lo -= pad; hi += pad
    val n = bars.size
    val slot = w.toFloat() / n
    val bw = (slot * 0.6f).coerceAtMost(8f)
    fun y(v: Double): Float = ((hi - v) / (hi - lo) * h).toFloat()

    bars.forEachIndexed { i, b ->
        val x = i * slot + slot / 2
        val color = if (b.close >= b.open) up else down
        val wick = Paint().apply { color = color; strokeWidth = 1.5f }
        c.drawLine(x, y(b.high), x, y(b.low), wick)
        val yO = y(b.open); val yCl = y(b.close)
        val top = minOf(yO, yCl)
        val bh = abs(yCl - yO).coerceAtLeast(2f)
        val body = Paint().apply { color = color }
        c.drawRect(x - bw / 2, top, x + bw / 2, top + bh, body)
    }

    val tickFrom = w * 0.62f
    val dash = Paint().apply { color = down; strokeWidth = 2f }
    val dash2 = Paint().apply { color = up; strokeWidth = 2f }
    val text = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = white; textSize = 30f; textAlign = Paint.Align.RIGHT
    }
    fun label(v: Double) =
        if (v >= 1000) String.format(java.util.Locale.US, "%.0f", v)
        else String.format(java.util.Locale.US, "%.1f", v)

    fun ticks(list: List<Double>, paint: Paint) {
        for (v in list) {
            val yy = y(v)
            c.drawLine(tickFrom, yy, w.toFloat(), yy, paint)
            c.drawText(label(v), w - 20f, yy - 8f, text)
        }
    }
    ticks(supports, dash)
    ticks(resistances, dash2)
    return bmp
}

private fun saveImage(context: Context, bmp: Bitmap, name: String): Boolean {
    val png = ByteArrayOutputStream().use { bos ->
        bmp.compress(Bitmap.CompressFormat.PNG, 100, bos)
        bos.toByteArray()
    }
    return try {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val values = ContentValues().apply {
                put(MediaStore.Images.Media.DISPLAY_NAME,
                    "JPStock_${name}_${System.currentTimeMillis()}.png")
                put(MediaStore.Images.Media.MIME_TYPE, "image/png")
                put(MediaStore.Images.Media.RELATIVE_PATH,
                    Environment.DIRECTORY_PICTURES + "/JPStock")
                put(MediaStore.Images.Media.IS_PENDING, 1)
            }
            val resolver = context.contentResolver
            val uri = resolver.insert(
                MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values)
                ?: return false
            resolver.openOutputStream(uri)?.use { it.write(png) } ?: return false
            values.clear()
            values.put(MediaStore.Images.Media.IS_PENDING, 0)
            resolver.update(uri, values, null, null)
            true
        } else {
            val dir = context.getExternalFilesDir(Environment.DIRECTORY_PICTURES)
                ?: return false
            File(dir, "JPStock_${name}.png").writeBytes(png)
            true
        }
    } catch (_: Exception) {
        false
    }
}
