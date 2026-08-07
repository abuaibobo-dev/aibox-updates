package com.aibox.app

import android.content.ClipData
import android.content.ClipboardManager
import android.os.Bundle
import android.speech.tts.TextToSpeech
import android.view.Gravity
import android.widget.*
import androidx.appcompat.app.AppCompatActivity
import org.json.JSONArray
import org.json.JSONObject
import java.util.Locale

class TranslateActivity : AppCompatActivity(), TextToSpeech.OnInitListener {

    private lateinit var etInput: EditText
    private lateinit var spLang: Spinner
    private lateinit var tvResult: TextView
    private var tts: TextToSpeech? = null

    private val langs = listOf("中文", "英语", "日语", "韩语", "法语", "德语", "俄语", "西班牙语", "泰语")

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        tts = TextToSpeech(this, this)

        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(16), dp(16), dp(16))
        }
        box.addView(title("翻译"))
        etInput = EditText(this).apply {
            setTextColor(0xFFECEFF4.toInt()); textSize = 16f
            minLines = 3; maxLines = 8
            setHintTextColor(0xFF9AA5B1.toInt()); hint = "输入或粘贴要翻译的内容…"
            setBackgroundColor(0xFF1B222A.toInt())
        }
        box.addView(etInput)

        spLang = Spinner(this)
        spLang.adapter = ArrayAdapter(this, android.R.layout.simple_spinner_dropdown_item, langs)
        box.addView(spLang)

        box.addView(btn("翻译") {
            val text = etInput.text.toString().trim()
            if (text.isEmpty()) { toast("先输入内容"); return@btn }
            tvResult.text = "翻译中…"
            val target = spLang.selectedItem.toString()
            val p = KeyManager.activeProvider(this)
            if (p.apiKey.isBlank()) { toast("请先配置 API Key"); tvResult.text = ""; return@btn }
            val msgs = JSONArray().apply {
                put(JSONObject().apply { put("role", "system"); put("content", "你是专业翻译。把用户输入翻译成$target，只输出译文，不要解释。") })
                put(JSONObject().apply { put("role", "user"); put("content", text) })
            }
            AiClient.chatOnce(p, msgs,
                onResult = { r, _ -> runOnUiThread { tvResult.text = r.trim() } },
                onError = { e -> runOnUiThread { tvResult.text = "⚠️ $e" } })
        })

        tvResult = TextView(this).apply {
            setTextColor(0xFFECEFF4.toInt()); textSize = 15f
            setPadding(dp(10), dp(12), dp(10), dp(12))
            background = bg(0xFF1B222A.toInt())
        }
        box.addView(tvResult)

        val actions = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        actions.addView(btn("朗读") { speak(tvResult.text.toString()) }, lp1())
        actions.addView(btn("复制") {
            val cm = getSystemService(CLIPBOARD_SERVICE) as ClipboardManager
            cm.setPrimaryClip(ClipData.newPlainText("trans", tvResult.text.toString()))
            toast("已复制")
        }, lp1())
        box.addView(actions)

        setContentView(box)
        intent.getStringExtra("text")?.let { etInput.setText(it) }
    }

    private fun speak(text: String) {
        if (text.isBlank()) return
        tts?.speak(text, TextToSpeech.QUEUE_FLUSH, null, "t1")
    }

    override fun onInit(status: Int) {}

    override fun onDestroy() { tts?.stop(); tts?.shutdown(); super.onDestroy() }

    private fun toast(s: String) = Toast.makeText(this, s, Toast.LENGTH_SHORT).show()
    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()
    private fun bg(c: Int) = if (c == 0xFF1B222A.toInt())
        GlassUi.panel(dp(16).toFloat())
    else
        GlassUi.solid(dp(16).toFloat(), c)
    private fun title(s: String) = TextView(this).apply {
        text = s; setTextColor(0xFFECEFF4.toInt()); textSize = 20f
        setPadding(0, 0, 0, dp(10))
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
