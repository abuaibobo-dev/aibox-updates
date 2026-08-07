package com.aibox.app

import android.os.Bundle
import android.view.Gravity
import android.widget.*
import androidx.appcompat.app.AppCompatActivity
import org.json.JSONArray
import org.json.JSONObject

class NoteEditActivity : AppCompatActivity() {

    private var noteId = -1L
    private lateinit var etTitle: EditText
    private lateinit var etContent: EditText
    private lateinit var etTags: EditText
    private lateinit var cbPin: CheckBox
    private lateinit var db: NotebookDb

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        noteId = intent.getLongExtra("id", -1L)
        db = NotebookDb(this)

        val box = ScrollView(this)
        val col = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(16), dp(16), dp(16))
        }
        box.addView(col)

        etTitle = EditText(this).apply {
            hint = "标题"; setTextColor(0xFFECEFF4.toInt()); setHintTextColor(0xFF9AA5B1.toInt())
            textSize = 18f; background = GlassUi.input()
        }
        etContent = EditText(this).apply {
            hint = "内容（支持 Markdown 语法）"; setTextColor(0xFFECEFF4.toInt())
            setHintTextColor(0xFF9AA5B1.toInt()); textSize = 15f; minLines = 8
            gravity = Gravity.TOP; background = GlassUi.input()
        }
        etTags = EditText(this).apply {
            hint = "标签（空格分隔，如：工作 灵感）"; setTextColor(0xFFECEFF4.toInt())
            setHintTextColor(0xFF9AA5B1.toInt()); textSize = 14f; background = GlassUi.input()
        }
        cbPin = CheckBox(this).apply { text = "置顶"; setTextColor(0xFFECEFF4.toInt()) }
        col.addView(etTitle); col.addView(spacer())
        col.addView(etContent); col.addView(spacer())
        col.addView(etTags); col.addView(spacer())
        col.addView(cbPin)

        col.addView(TextView(this).apply {
            text = "AI 助手（调 DeepSeek 处理当前内容）"
            setTextColor(0xFF10A37F.toInt()); textSize = 13f
            setPadding(0, dp(10), 0, dp(4))
        })
        val aiRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        listOf("润色", "总结要点", "翻译成英语").forEach { name ->
            aiRow.addView(aiBtn(name) { aiAction(name) }, lp1())
        }
        col.addView(aiRow)

        val saveRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        saveRow.addView(btn("保存") { save() }, lp1())
        if (noteId != -1L) {
            saveRow.addView(btnDanger("删除") {
                db.delete(noteId); toast("已删除"); finish()
            }, lp1())
        }
        col.addView(saveRow)

        if (noteId != -1L) {
            val n = db.all().firstOrNull { it.id == noteId }
            if (n != null) {
                etTitle.setText(n.title); etContent.setText(n.content)
                etTags.setText(n.tags); cbPin.isChecked = n.pinned
            }
        }
        setContentView(box)
    }

    private fun aiAction(action: String) {
        val text = etContent.text.toString().trim()
        if (text.isEmpty()) { toast("内容为空"); return }
        val p = KeyManager.activeProvider(this)
        if (p.apiKey.isBlank()) { toast("请先配置 API Key"); return }
        val prompt = when (action) {
            "润色" -> "请润色下面的文字，保持原意，表达更通顺自然。只输出结果。"
            "总结要点" -> "请总结下面的内容，输出 3-5 条要点。只输出要点。"
            else -> "请把下面的内容翻译成英语。只输出译文。"
        }
        toast("$action 中…")
        val msgs = JSONArray().apply {
            put(JSONObject().apply { put("role", "system"); put("content", prompt) })
            put(JSONObject().apply { put("role", "user"); put("content", text) })
        }
        AiClient.chatOnce(p, msgs,
            onResult = { r, _ -> runOnUiThread { etContent.setText(r.trim()) } },
            onError = { e -> runOnUiThread { toast("⚠️ $e") } })
    }

    private fun save() {
        val title = etTitle.text.toString().trim().ifBlank { "无标题" }
        val content = etContent.text.toString().trim()
        if (noteId == -1L) db.insert(title, content, etTags.text.toString().trim())
        else db.update(noteId, title, content, etTags.text.toString().trim(), cbPin.isChecked)
        toast("已保存"); finish()
    }

    private fun spacer() = Space(this).apply { minimumHeight = dp(10) }
    private fun toast(s: String) = Toast.makeText(this, s, Toast.LENGTH_SHORT).show()
    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()
    private fun btn(s: String, onClick: () -> Unit) = TextView(this).apply {
        text = s; gravity = Gravity.CENTER; setTextColor(0xFF111418.toInt()); textSize = 15f
        setPadding(dp(8), dp(11), dp(8), dp(11))
        background = android.graphics.drawable.GradientDrawable().apply { cornerRadius = dp(10).toFloat(); setColor(0xFF10A37F.toInt()) }
        setOnClickListener { onClick() }
    }
    private fun btnDanger(s: String, onClick: () -> Unit) = TextView(this).apply {
        text = s; gravity = Gravity.CENTER; setTextColor(0xFFFFFFFF.toInt()); textSize = 15f
        setPadding(dp(8), dp(11), dp(8), dp(11))
        background = android.graphics.drawable.GradientDrawable().apply { cornerRadius = dp(10).toFloat(); setColor(0xFFC0392B.toInt()) }
        setOnClickListener { onClick() }
    }
    private fun aiBtn(s: String, onClick: () -> Unit) = TextView(this).apply {
        text = s; gravity = Gravity.CENTER; setTextColor(0xFF10A37F.toInt()); textSize = 13f
        setPadding(dp(6), dp(8), dp(6), dp(8))
        background = android.graphics.drawable.GradientDrawable().apply {
            cornerRadius = dp(8).toFloat(); setColor(0xFF1B222A.toInt())
        }
        setOnClickListener { onClick() }
    }
    private fun lp1() = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f).apply {
        setMargins(dp(3), dp(4), dp(3), dp(4))
    }
}
