package dev.abuaibobo.jpstock

import android.content.ContentValues
import android.content.Context
import android.graphics.Bitmap
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import android.widget.Toast
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.runtime.Composable
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.layer.ExperimentalGraphicsLayerApi
import androidx.compose.ui.graphics.layer.rememberGraphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import kotlinx.coroutines.launch
import java.io.ByteArrayOutputStream
import java.io.File

/**
 * Candlestick chart wrapped so a LONG PRESS captures the drawing and saves it
 * as a PNG image (Pictures/JPStock on Android 10+; app dir otherwise).
 */
@OptIn(ExperimentalGraphicsLayerApi::class)
@Composable
fun CapturableChart(
    bars: List<KLine>,
    modifier: Modifier = Modifier,
    supports: List<Double> = emptyList(),
    resistances: List<Double> = emptyList(),
    fileName: String = "chart",
) {
    val context = LocalContext.current
    val layer = rememberGraphicsLayer()
    val scope = rememberCoroutineScope()

    Box(
        modifier
            .graphicsLayer(layer)
            .drawWithContent { layer.record { this@drawWithContent.drawContent() } }
            .pointerInput(Unit) {
                detectTapGestures(onLongPress = {
                    scope.launch {
                        val ok = runCatching {
                            val bmp = layer.toImageBitmap().asAndroidBitmap()
                            saveImage(context, bmp, fileName)
                        }.getOrDefault(false)
                        Toast.makeText(
                            context,
                            if (ok) "已保存K线图到相册(JPStock)" else "保存失败",
                            Toast.LENGTH_SHORT
                        ).show()
                    }
                })
            }
    ) {
        CandlestickChart(bars = bars, modifier = Modifier.matchParentSize(),
            supports = supports, resistances = resistances)
    }
}

/** Persist a PNG. Android 10+ -> shared Pictures/JPStock (no permission). */
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
            val f = File(dir, "JPStock_${name}.png")
            f.writeBytes(png)
            true
        }
    } catch (_: Exception) {
        false
    }
}
