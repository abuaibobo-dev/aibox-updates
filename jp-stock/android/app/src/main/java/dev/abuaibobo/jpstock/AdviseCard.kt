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

    fun tp(size: Float, color: Int, bold: Boolean = false) =
        TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
            this.color = color; textSize = size
            if (bold) typeface = android.graphics.Typeface.DEFAULT_BOLD
        }

    val smallC = 0xFF9AA3AD.toInt()
    val goldC = 0xFFD9B24A.toInt()
    val blueC = 0xFF63A8FF.toInt()
    val whiteC = 0xFFF4F6F9.toInt()
    val textC = 0xFFD7DDE3.toInt()
    val footC = 0xFF6E7681.toInt()

    fun fv(v: Double?) = when {
        v == null -> ""
        v >= 1000 -> String.format(Locale.US, "%,.0f", v)
        else -> String.format(Locale.US, "%.1f", v)
    }

    val date = SimpleDateFormat("yyyy/MM/dd", Locale.US).format(Date())

    // ---- build text blocks: (paint, text) ----
    val blocks = ArrayList<Pair<TextPaint, String>>()
    blocks.add(tp(28f, smallC) to "JPStock ・ 銘柄カード（$date）")
    blocks.add(tp(56f, whiteC, bold = true) to "${p.name}　（${p.code}）")
    blocks.add(tp(30f, smallC) to (p.industry ?: ""))
    blocks.add(tp(44f, goldC, bold = true) to "現在値 ${fv(p.price)}円")
    blocks.add(tp(2f, 0) to "")  // spacer
    fun actionBlock(label: String, v1: Double?, v2: Double?) {
        if (v1 == null) return
        val vv = fv(v1) + (if (v2 != null) " 〜 " + fv(v2) else "")
        blocks.add(tp(36f, blueC, bold = true) to "$label ")
        // append value onto same paint won't recolor; draw as separate line instead
        blocks.add(tp(36f, whiteC) to vv)
    }
    actionBlock("買い目安", p.buyLow, p.buyHigh)
    actionBlock("損切り", p.stop, null)
    actionBlock("利確目標", p.t1, p.t2)
    blocks.add(tp(2f, 0) to "")
    blocks.add(tp(32f, textC) to (p.pitchJa ?: ""))
    blocks.add(tp(2f, 0) to "")
    blocks.add(tp(24f, footC) to "JPStock — 分析情報提供資料（投資判断はお客様自身で）")

    // ---- measure total height (two-pass) ----
    val layouts = ArrayList<StaticLayout>()
    var total = pad * 2f
    for ((pt, txt) in blocks) {
        val sl = StaticLayout.Builder.obtain(txt, 0, txt.length, pt, textW)
            .setAlignment(Layout.Alignment.ALIGN_NORMAL)
            .setLineSpacing(0f, 1.28f)
            .setIncludePad(false)
            .build()
        layouts.add(sl)
        total += sl.height + 18f   // inter-block gap
    }
    val h = (total + 30).toInt()

    val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
    val c = Canvas(bmp)
    c.drawRect(0f, 0f, w.toFloat(), h.toFloat(), bg)

    var y = pad.toFloat()
    for (i in blocks.indices) {
        val sl = layouts[i]
        c.save()
        c.translate(pad.toFloat(), y)
        sl.draw(c)
        c.restore()
        y += sl.height + 18f
    }

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
