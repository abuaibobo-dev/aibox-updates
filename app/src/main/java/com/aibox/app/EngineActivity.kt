package com.aibox.app

import android.app.AlertDialog
import android.os.Bundle
import android.view.animation.AnimationUtils
import android.view.View
import android.widget.Button
import android.widget.EditText
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity

class EngineActivity : AppCompatActivity() {

    private lateinit var etKey: EditText
    private lateinit var btnStart: Button
    private lateinit var btnReset: Button
    private lateinit var progress: ProgressBar
    private lateinit var tvStatus: TextView
    private var running = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_engine)

        etKey = findViewById(R.id.etKey)
        btnStart = findViewById(R.id.btnStart)
        btnReset = findViewById(R.id.btnReset)
        progress = findViewById(R.id.progress)
        tvStatus = findViewById(R.id.tvStatus)

        etKey.setText(CodexEngine.apiKey(this))
        etKey.hint = when (CodexEngine.provider(this)) {
            CodexEngine.PROVIDER_GROQ -> "gsk_…"
            CodexEngine.PROVIDER_DEEPSEEK -> "sk-…"
            else -> "sk-or-…"
        }

        btnStart.setOnClickListener {
            if (running) return@setOnClickListener
            startInit()
        }

        // 图标：用 RoundedBitmapDrawable 裁成圆形，避免方形图片盖住圆形外框
        runCatching {
            val logo = findViewById<android.widget.ImageView>(R.id.imgLogo)
            val bmp = android.graphics.BitmapFactory.decodeResource(resources, R.drawable.avatar_side)
            if (bmp != null) {
                val d = androidx.core.graphics.drawable.RoundedBitmapDrawableFactory.create(resources, bmp)
                d.isCircular = true
                d.setAntiAlias(true)
                logo.setImageDrawable(d)
            }
        }
        findViewById<android.widget.ImageView>(R.id.imgLogo)?.startAnimation(
            AnimationUtils.loadAnimation(this, R.anim.pulse)
        )

        btnReset.setOnClickListener {
            if (running) return@setOnClickListener
            Ui.confirm(this, "清除引擎数据并重试", "将删除已下载的引擎文件（约 170MB），然后重新下载安装。适合初始化失败或引擎损坏时使用。", "清除并重试") {
                clearAndInit()
            }
        }
    }

    private fun startInit() {
        val key = etKey.text.toString().trim()
        if (key.isBlank() || key.length < 12) {
            Toast.makeText(this, "请输入完整有效的 API Key", Toast.LENGTH_LONG).show()
            return
        }
        CodexEngine.saveApiKey(this, key)
        runInit(key)
    }

    private fun clearAndInit() {
        CodexEngine.clearEngineData(this)
        val key = etKey.text.toString().trim()
        if (key.isBlank() || key.length < 12) {
            Toast.makeText(this, "请先填写完整有效的 API Key", Toast.LENGTH_LONG).show()
            return
        }
        CodexEngine.saveApiKey(this, key)
        runInit(key)
    }

    private fun runInit(key: String) {
        running = true
        btnStart.isEnabled = false
        btnReset.isEnabled = false
        progress.visibility = View.VISIBLE
        progress.isIndeterminate = false
        progress.progress = 0
        tvStatus.text = "开始初始化…"
        CodexEngine.init(this, key,
            onStatus = { st, pct ->
                runOnUiThread {
                    tvStatus.text = st
                    if (pct != null) {
                        progress.isIndeterminate = false
                        progress.progress = pct
                    } else {
                        // 解压/安装/验证等阶段没有百分比，用动态转圈表示正在工作
                        progress.isIndeterminate = true
                    }
                }
            },
            onDone = { ok, msg ->
                runOnUiThread {
                    running = false
                    btnStart.isEnabled = true
                    btnReset.isEnabled = true
                    progress.visibility = View.GONE
                    if (ok) {
                        tvStatus.text = "✓ $msg"
                        CodexEngine.applySharedFolder(this)
                        Toast.makeText(this, "引擎初始化完成", Toast.LENGTH_LONG).show()
                        Thread.sleep(600)
                        finish()
                    } else {
                        tvStatus.text = "✗ 初始化失败：$msg\n\n${CodexEngine.diagnose(this)}"
                        Toast.makeText(this, "初始化失败，可尝试下方“清除数据并重试”", Toast.LENGTH_LONG).show()
                    }
                }
            }
        )
    }
}
