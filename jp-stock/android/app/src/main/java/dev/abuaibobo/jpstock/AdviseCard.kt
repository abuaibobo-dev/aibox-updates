package dev.abuaibobo.jpstock

import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import android.text.Layout
import android.text.StaticLayout
import android.text.TextPaint
import java.io.ByteArrayOutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Draws a client-facing "stock card" image (JP) locally and saves it, then
 * returns its content Uri for sharing (LINE / X / email).
 */
fun generateAdviseCard(context: Context, p: Pick): Uri? {
    val w = 1080
    val pad = 64
    val textW = w - pad * 2

    val bg = Paint().apply { color = 0xFF101318.toInt() }
    val white = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xFFF2F4F7.toInt(); textSize = 40f
    }
    val gray = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xFF9AA3AD.toInt(); textSize = 30f
    }
    val small = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xFF8A929C.toInt(); textSize = 26f
    }
    val gold = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xFFD9B24A.toInt(); textSize = 40f; typeface = android.graphics.Typeface.DEFAULT_BOLD
    }
    val head = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xFFFFFFFF.toInt(); textSize = 56f; typeface = android.graphics.Typeface.DEFAULT_BOLD
    }
    val accent = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xFF63A8FF.toInt(); textSize = 34f; typeface = android.graphics.Typeface.DEFAULT_BOLD
    }

    val body = p.pitchJa
    fun fv(v: Double?) = when {
        v == null -> ""
        v >= 1000 -> String.format(Locale.US, "%,.0f", v)
        else -> String.format(Locale.US, "%.1f", v)
    }

    // estimate height: header zone + action rows + wrapped body
    val estBody = StaticLayout.Builder.obtain(body, 0, body.length, small, textW)
        .setAlignment(Layout.Alignment.ALIGN_NORMAL).build()
    val h = 420 + 90 * 3 + estBody.height + 260

    val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
    val c = Canvas(bmp)
    c.drawRect(0f, 0f, w.toFloat(), h.toFloat(), bg)

    var y = pad + 20f
    fun line(text: String, tp: TextPaint) {
        val sl = StaticLayout.Builder.obtain(text, 0, text.length, tp, textW)
            .setAlignment(Layout.Alignment.ALIGN_NORMAL).build()
        c.save(); c.translate(pad.toFloat(), y); sl.draw(c); c.restore()
        y += sl.height + 8f
    }

    val date = SimpleDateFormat("yyyy/MM/dd", Locale.US).format(Date())
    line("JPStock ・ 銘柄カード（$date）", small)
    y += 6f
    line("${p.name}　（${p.code}）", head)
    line(p.industry ?: "", small)
    y += 4f
    line("現在値 ${fv(p.price)}円", gold)
    y += 8f

    // action rows
    fun actionRow(label: String, v1: Double?, v2: Double?) {
        if (v1 == null) return
        val t = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
            color = 0xFFF2F4F7.toInt(); textSize = 34f
        }
        val lab = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
            color = 0xFF63A8FF.toInt(); textSize = 34f; typeface = android.graphics.Typeface.DEFAULT_BOLD
        }
        c.save(); c.translate(pad.toFloat(), y)
        c.drawText("$label ", 0f, 0f, lab)
        c.drawText(fv(v1) + (if (v2 != null) " 〜 " + fv(v2) else ""),
            lab.measureText("$label ") + 12f, 0f, t)
        c.restore()
        y += 48f
    }
    actionRow("買い目安", p.buyLow, p.buyHigh)
    actionRow("損切り", p.stop, null)
    actionRow("利確目標", p.t1, p.t2)
    y += 10f

    // body (pitchJa) starting from its own first line
    val bodyPaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
        color = 0xFFD7DDE3.toInt(); textSize = 32f
    }
    val sl = StaticLayout.Builder.obtain(body, 0, body.length, bodyPaint, textW)
        .setAlignment(Layout.Alignment.ALIGN_NORMAL).build()
    c.save(); c.translate(pad.toFloat(), y); sl.draw(c); c.restore()
    y += sl.height + 24f
    c.drawText("JPStock — 分析情報提供資料（投資判断はお客様自身で）",
        pad.toFloat(), y, TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
            color = 0xFF6E7681.toInt(); textSize = 24f
        })

    val ok = saveCard(context, bmp, p.code)
    return ok
}

private fun saveCard(context: Context, bmp: Bitmap, code: String): Uri? {
    val png = ByteArrayOutputStream().use { bos ->
        bmp.compress(Bitmap.CompressFormat.PNG, 100, bos); bos.toByteArray()
    }
    return try {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val values = ContentValues().apply {
                put(MediaStore.Images.Media.DISPLAY_NAME,
                    "JPStock_${code}_card.png")
                put(MediaStore.Images.Media.MIME_TYPE, "image/png")
                put(MediaStore.Images.Media.RELATIVE_PATH,
                    Environment.DIRECTORY_PICTURES + "/JPStock")
                put(MediaStore.Images.Media.IS_PENDING, 1)
            }
            val r = context.contentResolver
            val uri = r.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values)
                ?: return null
            r.openOutputStream(uri)?.use { it.write(png) } ?: return null
            values.clear(); values.put(MediaStore.Images.Media.IS_PENDING, 0)
            r.update(uri, values, null, null)
            uri
        } else {
            val dir = context.getExternalFilesDir(Environment.DIRECTORY_PICTURES) ?: return null
            java.io.File(dir, "JPStock_${code}_card.png").writeBytes(png)
            null // <Q: no shareable content uri; caller still saved to app dir
        }
    } catch (_: Exception) {
        null
    }
}

/** Fire the system share sheet for an image Uri (LINE/X/mail). */
fun shareImageUri(context: Context, uri: Uri?) {
    if (uri == null) return
    val intent = Intent(Intent.ACTION_SEND).apply {
        type = "image/png"
        putExtra(Intent.EXTRA_STREAM, uri)
        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    }
    context.startActivity(Intent.createChooser(intent, "共有（お客様へ）"))
}
