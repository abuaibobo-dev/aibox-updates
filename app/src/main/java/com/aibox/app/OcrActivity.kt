package com.aibox.app

import android.Manifest
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.Bundle
import androidx.activity.result.PickVisualMediaRequest
import android.provider.MediaStore
import android.view.Gravity
import android.widget.*
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.FileProvider
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.chinese.ChineseTextRecognizerOptions
import java.io.File

class OcrActivity : AppCompatActivity() {

    private var cameraFile: File? = null
    private lateinit var ivPreview: ImageView
    private lateinit var tvResult: TextView

    private val pickImage = registerForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
        if (uri != null) handleUri(uri) else toast("未选择图片")
    }

    private val takePicture = registerForActivityResult(ActivityResultContracts.TakePicture()) { ok ->
        val f = cameraFile
        if (ok && f != null) handleUri(Uri.fromFile(f)) else toast("拍照取消")
    }

    private val requestCamera = registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (granted) {
            val dir = File(cacheDir, "camera").apply { mkdirs() }
            cameraFile = File(dir, "shot_${System.currentTimeMillis()}.jpg")
            val uri = FileProvider.getUriForFile(this, "com.aibox.app.fileprovider", cameraFile!!)
            takePicture.launch(uri)
        } else toast("需要相机权限")
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val root = ScrollView(this)
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(16), dp(16), dp(16))
        }
        root.addView(box)

        box.addView(title("识图提取"))
        box.addView(hint("拍照或选图，先本地提取文字，再交给 AI 翻译 / 总结 / 整理。DeepSeek 不能直接看图，所以采用“提取文字”路线。"))

        ivPreview = ImageView(this).apply {
            adjustViewBounds = true
            maxHeight = dp(260)
            visibility = android.view.View.GONE
        }
        box.addView(ivPreview)

        val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        row.addView(btn("拍照") { requestCamera.launch(Manifest.permission.CAMERA) }, lp1())
        row.addView(btn("从相册选图") { pickImage.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)) }, lp1())
        box.addView(row)

        tvResult = TextView(this).apply {
            setTextColor(0xFFECEFF4.toInt())
            textSize = 15f
            setPadding(dp(10), dp(12), dp(10), dp(12))
            background = bg(0xFF1B222A.toInt())
        }
        box.addView(tvResult)

        val actions = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        actions.addView(btn("复制") { copy(tvResult.text.toString()) }, lp1())
        actions.addView(btn("翻译") {
            val t = tvResult.text.toString()
            if (t.isBlank()) toast("先提取文字") else startActivity(Intent(this, TranslateActivity::class.java).putExtra("text", t))
        }, lp1())
        actions.addView(btn("存记事本") {
            val t = tvResult.text.toString()
            if (t.isBlank()) toast("先提取文字") else {
                NotebookDb(this).insert(if (t.length > 20) t.take(20) + "…" else t, t, "识图")
                toast("已存入记事本")
            }
        }, lp1())
        box.addView(actions)

        setContentView(root)
    }

    private fun handleUri(uri: Uri) {
        try {
            val bmp = decode(uri)
            ivPreview.setImageBitmap(bmp)
            ivPreview.visibility = android.view.View.VISIBLE
            tvResult.text = "识别中…"
            val client = TextRecognition.getClient(ChineseTextRecognizerOptions.Builder().build())
            client.process(InputImage.fromBitmap(bmp, 0))
                .addOnSuccessListener { result ->
                    val text = result.text
                    tvResult.text = if (text.isBlank()) "未识别到文字" else text
                    client.close()
                }
                .addOnFailureListener { e ->
                    tvResult.text = "识别失败：${e.message}"
                    client.close()
                }
        } catch (e: Exception) {
            toast("图片读取失败：${e.message}")
        }
    }

    private fun decode(uri: Uri): Bitmap {
        val resolver = contentResolver
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        resolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, bounds) }
        var sample = 1
        var maxDim = maxOf(bounds.outWidth, bounds.outHeight)
        while (maxDim > 2048) { sample *= 2; maxDim /= 2 }
        val opts = BitmapFactory.Options().apply { inSampleSize = sample }
        return resolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, opts) } ?: throw Exception("无法解码图片")
    }

    private fun copy(text: String) {
        if (text.isBlank()) { toast("没有可复制的内容"); return }
        val cm = getSystemService(CLIPBOARD_SERVICE) as ClipboardManager
        cm.setPrimaryClip(ClipData.newPlainText("ocr", text))
        toast("已复制")
    }

    private fun toast(s: String) = Toast.makeText(this, s, Toast.LENGTH_SHORT).show()
    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()
    private fun bg(c: Int) = if (c == 0xFF1B222A.toInt())
        GlassUi.panel(dp(16).toFloat())
    else
        GlassUi.solid(dp(16).toFloat(), c)
    private fun title(s: String) = TextView(this).apply {
        text = s; setTextColor(0xFFECEFF4.toInt()); textSize = 20f
        setPadding(0, 0, 0, dp(8))
    }
    private fun hint(s: String) = TextView(this).apply {
        text = s; setTextColor(0xFF9AA5B1.toInt()); textSize = 13f
        setPadding(0, 0, 0, dp(12))
    }
    private fun btn(s: String, onClick: () -> Unit) = TextView(this).apply {
        text = s; setTextColor(0xFF111418.toInt()); textSize = 14f
        gravity = Gravity.CENTER
        setPadding(dp(8), dp(10), dp(8), dp(10))
        background = bg(0xFF10A37F.toInt())
        setOnClickListener { onClick() }
    }
    private fun lp1() = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply {
        setMargins(dp(3), dp(6), dp(3), dp(6))
    }
}
