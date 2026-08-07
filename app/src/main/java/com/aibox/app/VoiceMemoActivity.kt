package com.aibox.app

import android.app.Activity
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Intent
import android.os.Bundle
import android.speech.RecognizerIntent
import android.view.Gravity
import android.widget.*
import androidx.appcompat.app.AppCompatActivity
import org.json.JSONArray
import org.json.JSONObject
import java.util.Locale

class VoiceMemoActivity : AppCompatActivity() {

    private lateinit var tvText: TextView
    private var lastText = ""

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(16), dp(16), dp(16))
        }
        box.addView(TextView(this).apply {
            text = "语音速记"; setTextColor(0xFFECEFF4.toInt()); textSize = 20f
        })
        box.addView(TextView(this).apply {
            text = "点击按钮说话，说完自动转文字；可让 AI 整理成要点后存记事本。"
            setTextColor(0xFF9AA5B1.toInt()); textSize = 13f
            setPadding(0, dp(4), 0, dp(12))
        })

        tvText = TextView(this).apply {
            setTextColor(0xFFECEFF4.toInt()); textSize = 15f; minHeight = dp(120)
            setPadding(dp(10), dp(12), dp(10), dp(12))
            background = bg(0xFF1B222A.toInt())
        }
        box.addView(tvText)

        box.addView(btn("🎤 开始说话") { startListening() })

        val actions = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        actions.addView(btn("AI 整理") { aiCleanup() }, lp1())
        actions.addView(btn("存记事本") {
            if (lastText.isBlank()) { toast("先录音"); return@btn }
            NotebookDb(this).insert(if (lastText.length > 20) lastText.take(20) + "…" else lastText, lastText, "语音")
            toast("已存入记事本"); lastText = ""; tvText.text = ""
        }, lp1())
        actions.addView(btn("复制") {
            val cm = getSystemService(CLIPBOARD_SERVICE) as ClipboardManager
            cm.setPrimaryClip(ClipData.newPlainText("voice", lastText)); toast("已复制")
        }, lp1())
        box.addView(actions)
        setContentView(box)
    }

    private fun startListening() {
        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, Locale.getDefault().toString())
            putExtra(RecognizerIntent.EXTRA_PROMPT, "请开始说话…")
        }
        try { startActivityForResult(intent, 1001) } catch (e: Exception) { toast("设备不支持语音识别") }
    }

    @Deprecated("Deprecated in Java")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == 1001 && resultCode == Activity.RESULT_OK) {
            val res = data?.getStringArrayListExtra(RecognizerIntent.EXTRA_RESULTS)
            if (!res.isNullOrEmpty()) { lastText = res[0]; tvText.text = lastText }
        }
    }

    private fun aiCleanup() {
        if (lastText.isBlank()) { toast("先录音"); return }
        val p = KeyManager.activeProvider(this)
        if (p.apiKey.isBlank()) { toast("请先配置 API Key"); return }
        tvText.text = "整理中…"
        val msgs = JSONArray().apply {
            put(JSONObject().apply { put("role", "system"); put("content", "把下面的口语语音整理成通顺的要点，去掉口水话，保留信息。") })
            put(JSONObject().apply { put("role", "user"); put("content", lastText) })
        }
        AiClient.chatOnce(p, msgs,
            onResult = { r, _ -> runOnUiThread { lastText = r.trim(); tvText.text = lastText } },
            onError = { e -> runOnUiThread { tvText.text = lastText; toast("⚠️ $e") } })
    }

    private fun toast(s: String) = Toast.makeText(this, s, Toast.LENGTH_SHORT).show()
    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()
    private fun bg(c: Int) = if (c == 0xFF1B222A.toInt())
        GlassUi.panel(dp(16).toFloat())
    else
        GlassUi.solid(dp(16).toFloat(), c)
    private fun btn(s: String, onClick: () -> Unit) = TextView(this).apply {
        text = s; gravity = Gravity.CENTER; setTextColor(0xFF111418.toInt()); textSize = 15f
        setPadding(dp(8), dp(12), dp(8), dp(12))
        background = bg(0xFF10A37F.toInt())
        setOnClickListener { onClick() }
    }
    private fun lp1() = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply {
        setMargins(dp(3), dp(6), dp(3), dp(6))
    }
}
