package dev.abuaibobo.jpstock

import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import android.text.Layout
import android.text.StaticLayout
import android.text.TextPaint
import java.io.ByteArrayOutputStream
import java.util.Locale

/**
 * Renders a client-facing "stock card" (JP) as an image and saves it to the
 * gallery; returns the content Uri for sharing. Layout is drawn top-to-bottom
 * on a tall scratch canvas, then cropped to the used height (no overlaps).
 */
fun generateAdviseCard(context: Context, p: Pick): Uri? {
    val W = 1080
    val outer = 44f
    val cw = W - outer * 2f        // card content width
    val cx = outer
    val padX = 36f
    val textW = cw - padX * 2f

    val cBg = 0xFF0B0E12.toInt()
    val cCard = 0xFF151C25.toInt()
    val cLine = 0xFF2A333E.toInt()
    val cWhite = 0xFFF6F8FA.toInt()
    val cDim = 0xFF9AA6B3.toInt()
    val cGold = 0xFFE9BC57.toInt()
    val cAcc = 0xFF5FA6FF.toInt()
    val cUp = 0xFFFF5A5F.toInt()
    val cDn = 0xFF46A3FF.toInt()

    fun tp(size: Float, color: Int, bold: Boolean = false) =
        TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
            this.color = color
            textSize = size
            if (bold) typeface = android.graphics.Typeface.DEFAULT_BOLD
        }

    fun sl(text: String, pt: TextPaint): StaticLayout =
        StaticLayout.Builder.obtain(text, 0, text.length, pt, textW.toInt())
            .setAlignment(Layout.Alignment.ALIGN_NORMAL)
            .setLineSpacing(0f, 1.32f)
            .setIncludePad(false)
            .build()

    fun rr(c: Canvas, x0: Float, y0: Float, x1: Float, y1: Float, r: Float, fill: Int) {
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = fill }
        val path = Path().apply { addRoundRect(RectF(x0, y0, x1, y1), r, r, Path.Direction.CW) }
        c.drawPath(path, paint)
    }

    fun fv(v: Double?) = when {
        v == null -> ""
        v >= 1000 -> String.format(Locale.US, "%,.0f", v)
        else -> String.format(Locale.US, "%.1f", v)
    }

    val MAX_H = 3600
    val bmp = Bitmap.createBitmap(W, MAX_H, Bitmap.Config.ARGB_8888)
    val c = Canvas(bmp)
    c.drawColor(cBg)

    var y = 96f
    val gap = 28f
    val vPad = 26f

    // ---- header ----
    val namePt = tp(66f, cWhite, bold = true)
    val codePt = tp(34f, cDim)
    val nameSl = sl(p.name, namePt)
    val codeLine = "${p.code}　・　${p.industry ?: ""}"
    val codeSl = sl(codeLine, codePt)
    val headerH = 24f + nameSl.height + 12f + codeSl.height
    rr(c, cx, y, cx + 12f, y + headerH, 6f, cAcc)          // accent bar
    c.save(); c.translate(cx + 40f, y); nameSl.draw(c); c.restore()
    c.save(); c.translate(cx + 40f, y + 24f + nameSl.height + 10f)
    codeSl.draw(c); c.restore()
    y += headerH + gap

    // ---- price card ----
    val capPt = tp(30f, cDim)
    val pricePt = tp(70f, cGold, bold = true)
    val priceTxt = "${fv(p.price)} 円"
    val priceSl = sl(priceTxt, pricePt)
    val priceCardH = vPad + capPt.textSize + 10f + priceSl.height + vPad
    rr(c, cx, y, cx + cw, y + priceCardH, 24f, cCard)
    c.save(); c.translate(cx + padX, y + vPad)
    c.drawText("現在値", 0f, 0f, capPt)
    c.restore()
    c.save(); c.translate(cx + padX, y + vPad + capPt.textSize + 12f)
    priceSl.draw(c); c.restore()
    y += priceCardH + gap

    // ---- action rows (each its own card) ----
    data class R(val label: String, val valTxt: String, val col: Int)
    val rows = mutableListOf<R>()
    if (p.buyLow != null) rows.add(
        R("買い目安", fv(p.buyLow) + (if (p.buyHigh != null) " 〜 " + fv(p.buyHigh) else ""), cDn))
    if (p.stop != null) rows.add(R("損切り", fv(p.stop), cUp))
    if (p.t1 != null) rows.add(
        R("利確目標", fv(p.t1) + (if (p.t2 != null) " 〜 " + fv(p.t2) else ""), cGold))
    for (r in rows) {
        val labPt = tp(36f, r.col, bold = true)
        val valPt = tp(40f, cWhite, bold = true)
        val rowH = vPad * 2 + valPt.textSize
        rr(c, cx, y, cx + cw, y + rowH, 20f, cCard)
        c.drawText(r.label, cx + padX, y + vPad + 34f, labPt)
        val rightPt = tp(40f, cWhite, bold = true).apply { textAlign = Paint.Align.RIGHT }
        c.drawText(r.valTxt, cx + cw - padX, y + vPad + 34f, rightPt)
        y += rowH + 14f
    }
    y += gap - 14f

    // separator
    rr(c, cx + padX, y, cx + cw - padX, y + 2f, 1f, cLine)
    y += gap

    // ---- body text in a card ----
    val bodyTxt = p.pitchJa ?: ""
    val bodyPt = tp(34f, cWhite)
    val bodySl = sl(bodyTxt, bodyPt)
    val bodyH = vPad * 2 + bodySl.height
    rr(c, cx, y, cx + cw, y + bodyH, 24f, cCard)
    c.save(); c.translate(cx + padX, y + vPad); bodySl.draw(c); c.restore()
    y += bodyH + 90f

    // crop to used height
    val usedH = minOf(y.toInt(), MAX_H)
    val cropped = Bitmap.createBitmap(bmp, 0, 0, W, usedH)
    return saveCard(context, cropped, p.code)
}

private fun saveCard(context: Context, bmp: Bitmap, code: String): Uri? {
    val png = ByteArrayOutputStream().use { bos ->
        bmp.compress(Bitmap.CompressFormat.PNG, 100, bos); bos.toByteArray()
    }
    return try {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val values = ContentValues().apply {
                put(MediaStore.Images.Media.DISPLAY_NAME, "JPStock_${code}_card.png")
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
            null
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
