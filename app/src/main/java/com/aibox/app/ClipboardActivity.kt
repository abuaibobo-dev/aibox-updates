package com.aibox.app

import android.content.ClipboardManager
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.view.Gravity
import android.widget.*
import androidx.appcompat.app.AppCompatActivity

class ClipboardActivity : AppCompatActivity() {

    private var text = ""

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(16), dp(16), dp(16))
        }
        box.addView(TextView(this).apply {
            text = "剪贴板助手"; setTextColor(0xFFECEFF4.toInt()); textSize = 20f
        })
        box.addView(TextView(this).apply {
            text = "复制任意内容后进入本页，自动识别：快递单号 / 手机号 / 网址 / 邮箱。"
            setTextColor(0xFF9AA5B1.toInt()); textSize = 13f
            setPadding(0, dp(4), 0, dp(12))
        })

        val tv = TextView(this).apply {
            setTextColor(0xFFECEFF4.toInt()); textSize = 15f
            setPadding(dp(10), dp(12), dp(10), dp(12))
            background = bg(0xFF1B222A.toInt())
        }
        box.addView(tv)

        box.addView(btn("重新读取剪贴板") { tv.text = readClipboard() })

        val actions = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        box.addView(actions)

        val refresh = {
            text = readClipboard()
            tv.text = text
            actions.removeAllViews()
            detect(text).forEach { (label, intent) ->
                actions.addView(btn(label) { startActivity(intent) })
            }
            if (text.isBlank()) actions.addView(TextView(this).apply {
                text = "剪贴板为空，先复制点内容再回来。"; setTextColor(0xFF9AA5B1.toInt()); textSize = 13f
            })
        }
        refresh()

        setContentView(box)
    }

    private fun readClipboard(): String {
        val cm = getSystemService(CLIPBOARD_SERVICE) as ClipboardManager
        return cm.primaryClip?.takeIf { it.itemCount > 0 }?.getItemAt(0)?.coerceToText(this)?.toString().orEmpty().trim()
    }

    private fun detect(t: String): List<Pair<String, Intent>> {
        if (t.isBlank()) return emptyList()
        val out = mutableListOf<Pair<String, Intent>>()
        if (Regex("^1[3-9]\\d{9}$").containsMatchIn(t))
            out.add("拨打该号码" to Intent(Intent.ACTION_DIAL, Uri.parse("tel:$t")))
        if (t.startsWith("http://") || t.startsWith("https://"))
            out.add("打开网址" to Intent(Intent.ACTION_VIEW, Uri.parse(t)))
        if (Regex("^[\\w.+-]+@[\\w-]+\\.[\\w.]+$").containsMatchIn(t))
            out.add("发邮件" to Intent(Intent.ACTION_SENDTO, Uri.parse("mailto:$t")))
        return out
    }

    private fun toast(s: String) = Toast.makeText(this, s, Toast.LENGTH_SHORT).show()
    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()
    private fun bg(c: Int) = if (c == 0xFF1B222A.toInt())
        GlassUi.panel(dp(16).toFloat())
    else
        GlassUi.solid(dp(16).toFloat(), c)
    private fun btn(s: String, onClick: () -> Unit) = TextView(this).apply {
        text = s; gravity = Gravity.CENTER; setTextColor(0xFF111418.toInt()); textSize = 15f
        setPadding(dp(8), dp(11), dp(8), dp(11))
        background = bg(0xFF10A37F.toInt())
        setOnClickListener { onClick() }
    }
}
