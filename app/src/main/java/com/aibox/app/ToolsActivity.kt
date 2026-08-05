package com.aibox.app

import android.content.Intent
import android.graphics.Typeface
import android.os.Bundle
import android.view.Gravity
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity

class ToolsActivity : AppCompatActivity() {

    private val tools = listOf(
        "识图 OCR" to OcrActivity::class.java,
        "翻译" to TranslateActivity::class.java,
        "记事本" to NotebookActivity::class.java,
        "语音速记" to VoiceMemoActivity::class.java,
        "剪贴板助手" to ClipboardActivity::class.java,
        "系统信息" to SysInfoActivity::class.java,
        "Key 管理" to KeysActivity::class.java
    )

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_tools)

        val lay = findViewById<LinearLayout>(R.id.layTools)
        tools.forEach { (name, cls) ->
            val tv = TextView(this)
            tv.text = name
            tv.textSize = 15f
            tv.setTextColor(resources.getColor(R.color.text_primary))
            tv.setTypeface(null, Typeface.BOLD)
            tv.gravity = Gravity.CENTER_VERTICAL
            tv.setPadding(24, 18, 24, 18)
            tv.background = resources.getDrawable(R.drawable.bg_card)
            val lp = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            )
            lp.topMargin = 10
            tv.layoutParams = lp
            tv.setOnClickListener { startActivity(Intent(this, cls)) }
            lay.addView(tv)
        }
    }
}
