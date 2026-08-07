package com.aibox.app

import android.Manifest
import android.app.AlertDialog
import android.content.Intent
import android.content.pm.PackageManager
import androidx.activity.result.contract.ActivityResultContracts
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.widget.Button
import android.widget.EditText
import android.widget.TextView
import android.widget.Toast
import java.io.File
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.app.AppCompatDelegate
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import rikka.shizuku.Shizuku

class SettingsActivity : AppCompatActivity() {

    private lateinit var etKey: EditText
    private lateinit var tvEngineStatus: TextView
    private lateinit var tvEnginePath: TextView
    private lateinit var btnSaveKey: Button
    private lateinit var btnProvider: TextView
    private lateinit var btnTest: Button
    private lateinit var btnBalance: Button
    private lateinit var btnRecharge: Button
    private lateinit var tvBalance: TextView
    private lateinit var tvUsage: TextView
    private lateinit var tvTheme: TextView
    private lateinit var btnTheme: Button
    private lateinit var tvBg: TextView
    private lateinit var tvSandboxStatus: TextView
    private lateinit var btnBattery: Button
    private lateinit var btnUpdate: Button
    private lateinit var tvUpdate: TextView
    private lateinit var btnReinit: Button
    private lateinit var btnClear: Button
    private lateinit var btnToolchain: Button
    private lateinit var btnExportLog: Button
    private lateinit var etGhToken: EditText
    private lateinit var tvShizukuStatus: TextView
    private lateinit var btnShizuku: Button
    private lateinit var etMcp: EditText
    private lateinit var btnSaveMcp: Button

    override fun onCreate(savedInstanceState: Bundle?) {
        AppCompatDelegate.setDefaultNightMode(getSharedPreferences("theme", MODE_PRIVATE).getInt("mode", AppCompatDelegate.MODE_NIGHT_NO))
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_settings)

        etKey = findViewById(R.id.etKey)
        tvEngineStatus = findViewById(R.id.tvEngineStatus)
        tvEnginePath = findViewById(R.id.tvEnginePath)
        btnSaveKey = findViewById(R.id.btnSaveKey)
        btnProvider = findViewById(R.id.btnProvider)
        btnTest = findViewById(R.id.btnTest)
        btnBalance = findViewById(R.id.btnBalance)
        btnRecharge = findViewById(R.id.btnRecharge)
        tvBalance = findViewById(R.id.tvBalance)
        tvUsage = findViewById(R.id.tvUsage)
        tvTheme = findViewById(R.id.tvTheme)
        btnTheme = findViewById(R.id.btnTheme)
        btnUpdate = findViewById(R.id.btnUpdate)
        tvUpdate = findViewById(R.id.tvUpdate)
        refreshThemeLabel()

        btnUpdate.setOnClickListener {
            btnUpdate.isEnabled = false
            btnUpdate.text = "检查中…"
            tvUpdate.text = "正在连接 GitHub…"
            Thread {
                val r = CodexEngine.checkUpdate(this)
                runOnUiThread {
                    btnUpdate.isEnabled = true
                    btnUpdate.text = "检查引擎更新"
                    tvUpdate.text = r
                }
            }.start()
        }

        tvUpdate.setOnClickListener {
            if (!tvUpdate.text.contains("发现新引擎")) return@setOnClickListener
            btnUpdate.isEnabled = false
            btnUpdate.text = "更新中…"
            CodexEngine.updateEngine(this,
                onStatus = { st -> runOnUiThread { tvUpdate.text = st } },
                onDone = { ok, msg ->
                    runOnUiThread {
                        btnUpdate.isEnabled = true
                        btnUpdate.text = "检查引擎更新"
                        tvUpdate.text = if (ok) "✓ $msg" else "✗ $msg"
                        Toast.makeText(this, if (ok) "引擎已更新" else "更新失败：$msg", Toast.LENGTH_LONG).show()
                        refreshEngine()
                    }
                }
            )
        }

        tvBg = findViewById(R.id.tvBg)
        refreshBgLabel()
        val bgPicker = registerForActivityResult(ActivityResultContracts.GetContent()) { uri ->
            if (uri != null) {
                runCatching {
                    val f = File(filesDir, "chat_bg.jpg")
                    contentResolver.openInputStream(uri)?.use { input ->
                        f.outputStream().use { out -> input.copyTo(out) }
                    }
                }
                refreshBgLabel()
                Toast.makeText(this, "已设置聊天背景", Toast.LENGTH_SHORT).show()
            }
        }
        findViewById<android.widget.Button>(R.id.btnBgPick).setOnClickListener { bgPicker.launch("image/*") }
        findViewById<android.widget.Button>(R.id.btnBgReset).setOnClickListener {
            File(filesDir, "chat_bg.jpg").delete()
            refreshBgLabel()
            Toast.makeText(this, "已恢复默认背景", Toast.LENGTH_SHORT).show()
        }

        tvSandboxStatus = findViewById(R.id.tvSandboxStatus)
        val folderPicker = registerForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
            if (uri != null) {
                runCatching {
                    contentResolver.takePersistableUriPermission(
                        uri,
                        Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
                    )
                }
                getSharedPreferences("ui", MODE_PRIVATE).edit().putString("shared_tree", uri.toString()).apply()
                CodexEngine.applySharedFolder(this)
                refreshSandboxStatus()
                Toast.makeText(this, "已授权，引擎可在 work/shared 里访问该文件夹", Toast.LENGTH_LONG).show()
            }
        }
        findViewById<android.widget.Button>(R.id.btnFolder).setOnClickListener { folderPicker.launch(null) }
        findViewById<android.widget.Button>(R.id.btnFolderClear).setOnClickListener {
            getSharedPreferences("ui", MODE_PRIVATE).edit().remove("shared_tree").apply()
            CodexEngine.applySharedFolder(this)
            refreshSandboxStatus()
            Toast.makeText(this, "已取消文件夹授权", Toast.LENGTH_SHORT).show()
        }
        val swKeepalive = findViewById<androidx.appcompat.widget.SwitchCompat>(R.id.swKeepalive)
        swKeepalive.isChecked = getSharedPreferences("settings", MODE_PRIVATE).getBoolean("keepalive", false)
        swKeepalive.setOnCheckedChangeListener { _, checked ->
            getSharedPreferences("settings", MODE_PRIVATE).edit().putBoolean("keepalive", checked).apply()
            if (checked) ChatForegroundService.start(this) else ChatForegroundService.stop(this)
        }
        btnBattery = findViewById(R.id.btnBattery)
        btnBattery.setOnClickListener {
            val pm = getSystemService(POWER_SERVICE) as android.os.PowerManager
            if (!pm.isIgnoringBatteryOptimizations(packageName)) {
                try {
                    startActivity(Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:$packageName")))
                } catch (e: Exception) {
                    Toast.makeText(this, "请到系统设置→应用→Synaps→电池 关闭“电池优化”", Toast.LENGTH_LONG).show()
                }
            } else {
                Toast.makeText(this, "已在电池优化白名单内", Toast.LENGTH_SHORT).show()
            }
        }
        findViewById<android.widget.Button>(R.id.btnInstallPerm).setOnClickListener {
            try {
                startActivity(Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:$packageName")))
            } catch (e: Exception) {
                Toast.makeText(this, "请到 系统设置→应用→Synaps→安装未知应用 手动开启", Toast.LENGTH_LONG).show()
            }
        }
        findViewById<android.widget.Button>(R.id.btnAllFiles).setOnClickListener {
            if (CodexEngine.hasAllFilesAccess(this)) {
                Toast.makeText(this, "已拥有所有文件访问权限", Toast.LENGTH_SHORT).show()
            } else if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) {
                ActivityCompat.requestPermissions(
                    this,
                    arrayOf(Manifest.permission.READ_EXTERNAL_STORAGE, Manifest.permission.WRITE_EXTERNAL_STORAGE),
                    9001
                )
            } else {
                try {
                    startActivity(
                        Intent(
                            Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION,
                            Uri.parse("package:$packageName")
                        )
                    )
                } catch (e: Exception) {
                    Toast.makeText(this, "请到系统设置→应用→Synaps→权限 手动开启“所有文件访问”", Toast.LENGTH_LONG).show()
                }
            }
        }

        btnTheme.setOnClickListener {
            Ui.sheet(this, "外观", listOf("深色", "浅色", "跟随系统")) { i ->
                val mode = intArrayOf(
                    AppCompatDelegate.MODE_NIGHT_YES,
                    AppCompatDelegate.MODE_NIGHT_NO,
                    AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM
                )[i]
                getSharedPreferences("theme", MODE_PRIVATE).edit().putInt("mode", mode).apply()
                AppCompatDelegate.setDefaultNightMode(mode)
                recreate()
            }
        }
        btnReinit = findViewById(R.id.btnReinit)
        btnClear = findViewById(R.id.btnClear)
        btnToolchain = findViewById(R.id.btnToolchain)
        btnToolchain.setOnClickListener {
            if (!CodexEngine.isInitialized(this)) {
                Toast.makeText(this, "请先初始化引擎", Toast.LENGTH_LONG).show()
                return@setOnClickListener
            }
            btnToolchain.isEnabled = false
            btnToolchain.text = "安装中…"
            CodexEngine.installToolchain(this,
                onStatus = { st -> runOnUiThread { btnToolchain.text = st.take(30) } },
                onDone = { ok, msg ->
                    runOnUiThread {
                        btnToolchain.isEnabled = true
                        btnToolchain.text = "安装静态工具链（python/wget/sh）"
                        Toast.makeText(this, if (ok) "工具链安装完成" else "安装失败：$msg", Toast.LENGTH_LONG).show()
                    }
                }
            )
        }

        etKey.setText(CodexEngine.apiKey(this))
        btnProvider.text = CodexEngine.providerLabel(CodexEngine.provider(this))

        btnProvider.setOnClickListener {
            val names = CodexEngine.PROVIDER_LABELS.map { it.second }
            Ui.sheet(this, "选择大脑（服务商）", names) { i ->
                CodexEngine.saveProvider(this, CodexEngine.PROVIDER_LABELS[i].first)
                btnProvider.text = CodexEngine.providerLabel(CodexEngine.provider(this))
                etKey.setText(CodexEngine.apiKey(this))
                etKey.hint = when (CodexEngine.provider(this)) {
                    CodexEngine.PROVIDER_GROQ -> "gsk_…"
                    CodexEngine.PROVIDER_DEEPSEEK -> "sk-…"
                    CodexEngine.PROVIDER_AGNES -> "sk-…"
                    CodexEngine.PROVIDER_OPENAI -> "sk-…（OpenAI 官方 Key）"
                    else -> "sk-or-…"
                }
                refreshEngine()
                Toast.makeText(this, "已切换服务商，请确认 Key 后保存", Toast.LENGTH_SHORT).show()
            }
        }

        refreshEngine()

        btnSaveKey.setOnClickListener {
            val key = etKey.text.toString().trim()
            if (key.isBlank() || key.length < 12) {
                Toast.makeText(this, "请输入完整有效的 API Key", Toast.LENGTH_LONG).show()
                return@setOnClickListener
            }
            CodexEngine.saveApiKey(this, key)
            Toast.makeText(this, "已保存（${CodexEngine.providerLabel(CodexEngine.provider(this))}）", Toast.LENGTH_SHORT).show()
            refreshEngine()
        }

        etGhToken = findViewById(R.id.etGhToken)
        etGhToken.setText(CodexEngine.ghToken(this))

        etMcp = findViewById(R.id.etMcp)
        etMcp.setText(getSharedPreferences("settings", MODE_PRIVATE).getString("mcp_server", ""))
        btnSaveMcp = findViewById(R.id.btnSaveMcp)
        btnSaveMcp.setOnClickListener {
            val v = etMcp.text.toString().trim()
            getSharedPreferences("settings", MODE_PRIVATE).edit().putString("mcp_server", v).apply()
            Toast.makeText(this, if (v.isBlank()) "已清空 MCP 地址" else "已保存 MCP 地址", Toast.LENGTH_SHORT).show()
        }

        tvShizukuStatus = findViewById(R.id.tvShizukuStatus)
        btnShizuku = findViewById(R.id.btnShizuku)
        Shizuku.addRequestPermissionResultListener { requestCode, _ ->
            if (requestCode == SHIZUKU_REQ) refreshShizuku()
        }
        btnShizuku.setOnClickListener {
            if (!Shizuku.pingBinder()) {
                Toast.makeText(this, "请先启动 Shizuku（root 直接启动；无 root 用 adb 或无线调试启动）", Toast.LENGTH_LONG).show()
                return@setOnClickListener
            }
            if (Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED) {
                Toast.makeText(this, "Shizuku 已授权", Toast.LENGTH_SHORT).show()
            } else {
                Shizuku.requestPermission(SHIZUKU_REQ)
            }
        }
        refreshShizuku()
        findViewById<android.widget.Button>(R.id.btnSaveGh).setOnClickListener {
            val t = etGhToken.text.toString().trim()
            if (t.isBlank()) {
                Toast.makeText(this, "请输入 GitHub Token", Toast.LENGTH_LONG).show()
                return@setOnClickListener
            }
            CodexEngine.saveGhToken(this, t)
            Toast.makeText(this, "GitHub Token 已保存，引擎推送代码时可自动发布直链", Toast.LENGTH_LONG).show()
        }

        tvUsage.text = CodexEngine.usageInfo(this)

        btnBalance.setOnClickListener {
            val key = CodexEngine.apiKey(this)
            if (key.isBlank()) {
                Toast.makeText(this, "请先保存 API Key", Toast.LENGTH_LONG).show()
                return@setOnClickListener
            }
            btnBalance.isEnabled = false
            btnBalance.text = "查询中…"
            Thread {
                val r = CodexEngine.fetchBalance(this, key)
                runOnUiThread {
                    btnBalance.isEnabled = true
                    btnBalance.text = "查询余额"
                    tvBalance.text = r
                }
            }.start()
        }

        btnRecharge.setOnClickListener {
            val url = when (CodexEngine.provider(this)) {
                CodexEngine.PROVIDER_DEEPSEEK -> "https://platform.deepseek.com/top_up"
                CodexEngine.PROVIDER_GROQ -> "https://console.groq.com/billing"
                CodexEngine.PROVIDER_AGNES -> "https://platform.agnes-ai.com"
                else -> "https://openrouter.ai/settings/credits"
            }
            try {
                startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
            } catch (e: Exception) {
                Toast.makeText(this, "无法打开浏览器：${e.message}", Toast.LENGTH_SHORT).show()
            }
        }

        btnTest.setOnClickListener {
            val key = etKey.text.toString().trim()
            if (key.isBlank()) {
                Toast.makeText(this, "请先填写 API Key", Toast.LENGTH_LONG).show()
                return@setOnClickListener
            }
            btnTest.isEnabled = false
            btnTest.text = "测试中…"
            Thread {
                val r = CodexEngine.testConnection(this, key)
                runOnUiThread {
                    btnTest.isEnabled = true
                    btnTest.text = "测试连接"
                    Ui.info(this, "连接测试", r)
                }
            }.start()
        }

        btnReinit.setOnClickListener {
            val key = etKey.text.toString().trim()
            if (key.isBlank()) {
                Toast.makeText(this, "请先填写 API Key", Toast.LENGTH_LONG).show()
                return@setOnClickListener
            }
            btnReinit.isEnabled = false
            tvEngineStatus.text = "正在重新初始化…"
            CodexEngine.init(this, key,
                onStatus = { st, _ -> runOnUiThread { tvEngineStatus.text = st } },
                onDone = { ok, msg ->
                    runOnUiThread {
                        btnReinit.isEnabled = true
                        tvEngineStatus.text = if (ok) "✓ $msg" else "✗ $msg"
                        Toast.makeText(this, if (ok) "引擎已就绪" else "初始化失败：$msg", Toast.LENGTH_LONG).show()
                    }
                }
            )
        }

        btnClear.setOnClickListener {
            Ui.confirm(this, "清除引擎数据", "将删除全部已下载的引擎文件（约 170MB），之后需要重新初始化。", "删除") {
                CodexEngine.clearEngineData(this)
                Toast.makeText(this, "已清除，请重新初始化", Toast.LENGTH_SHORT).show()
                refreshEngine()
            }
        }

        btnExportLog = findViewById(R.id.btnExportLog)
        btnExportLog.setOnClickListener { exportEngineLog() }

        tvEnginePath.setOnClickListener {
            val v = tvEnginePath.text
            tvEnginePath.text = "$v\n正在生成诊断…"
            Thread {
                val d = CodexEngine.diagnose(this)
                runOnUiThread { tvEnginePath.text = v }
                runOnUiThread { Ui.info(this, "引擎诊断", d) }
            }.start()
        }

        findViewById<TextView>(R.id.tvAbout).text = aboutText()
    }

    private fun exportEngineLog() {
        try {
            val dir = File(cacheDir, "export").apply { mkdirs() }
            val log = File(filesDir, "codex/run.log")
            val crash = File(filesDir, "crash.log")
            val f = File(dir, "run.log")
            val sb = StringBuilder()
            sb.append("===== 崩溃日志 =====\n")
            sb.append(if (crash.exists()) crash.readText() else "（无崩溃记录）")
            sb.append("\n\n===== 引擎运行日志 =====\n")
            sb.append(if (log.exists()) log.readText() else "（暂无引擎日志）")
            f.writeText(sb.toString())
            val uri = FileProvider.getUriForFile(this, "com.aibox.app.fileprovider", f)
            val share = Intent(Intent.ACTION_SEND).apply {
                type = "text/plain"
                putExtra(Intent.EXTRA_STREAM, uri)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            startActivity(Intent.createChooser(share, "导出诊断日志（含崩溃记录）"))
        } catch (e: Exception) {
            Toast.makeText(this, "导出失败：${e.message}", Toast.LENGTH_SHORT).show()
        }
    }

    private fun refreshBgLabel() {
        tvBg.text = "聊天背景：" + if (File(filesDir, "chat_bg.jpg").exists()) "自定义" else "默认"
    }

    override fun onResume() {
        super.onResume()
        refreshSandboxStatus()
        refreshBatteryLabel()
        refreshShizuku()
        // 引擎能力全开：仅首次进入设置页请求一次全部权限（避免每次进设置都弹窗干扰操作）
        val sp = getSharedPreferences("settings", MODE_PRIVATE)
        if (!sp.getBoolean("settings_asked_perms", false)) {
            sp.edit().putBoolean("settings_asked_perms", true).apply()
            val need = Perms.missing(this)
            if (need.isNotEmpty()) requestPermissions(need, 9003)
        }
    }

    private fun refreshSandboxStatus() {
        val allFiles = if (CodexEngine.hasAllFilesAccess(this)) "已授权全部文件" else "未授权"
        val folder = CodexEngine.sharedFolderPath(this) ?: CodexEngine.sharedFolderUri(this)
        tvSandboxStatus.text = "文件访问：$allFiles\n授权文件夹：${folder ?: "无"}"
    }

    private fun refreshBatteryLabel() {
        val pm = getSystemService(POWER_SERVICE) as android.os.PowerManager
        btnBattery.text = if (pm.isIgnoringBatteryOptimizations(packageName)) "电池优化：已加入白名单" else "加入电池优化白名单"
    }

    private fun refreshThemeLabel() {
        val mode = getSharedPreferences("theme", MODE_PRIVATE).getInt("mode", AppCompatDelegate.MODE_NIGHT_NO)
        tvTheme.text = "当前外观：" + when (mode) {
            AppCompatDelegate.MODE_NIGHT_NO -> "浅色"
            AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM -> "跟随系统"
            else -> "深色"
        }
    }

    private fun refreshShizuku() {
        val tv = tvShizukuStatus ?: return
        if (!Shizuku.pingBinder()) {
            tv.text = "未运行：请先启动 Shizuku（root 或 adb/无线调试）"
        } else if (Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED) {
            tv.text = "✅ 已授权：引擎可用 shizuku_cmd 执行 ADB 级命令"
        } else {
            tv.text = "运行中，未授权：点下方按钮弹出授权"
        }
    }

    companion object {
        private const val SHIZUKU_REQ = 1000
    }

    private fun refreshEngine() {
        val ok = CodexEngine.isInitialized(this)
        val ver = CodexEngine.engineInfo(this)
        tvEngineStatus.text = if (ok) "状态：已就绪（$ver）" else "状态：未安装"
        val path = "${filesDir}/codex"
        // 目录遍历可能很大（引擎约 1.6GB），放到后台线程，避免点击设置时主线程卡死/ANR
        tvEnginePath.text = "引擎目录：$path\n占用空间：计算中…\n服务商：${CodexEngine.providerLabel(CodexEngine.provider(this))}"
        Thread {
            val mb = CodexEngine.dirSize(this) / 1024 / 1024
            runOnUiThread {
                val text = "引擎目录：$path\n占用空间：约 $mb MB\n服务商：${CodexEngine.providerLabel(CodexEngine.provider(this))}"
                val sp = android.text.SpannableStringBuilder(text)
                val sizeText = "$mb MB"
                val idx = text.indexOf(sizeText)
                if (idx > 0) {
                    sp.setSpan(
                        android.text.style.ForegroundColorSpan(ContextCompat.getColor(this, R.color.warning)),
                        idx, idx + sizeText.length, android.text.Spanned.SPAN_EXCLUSIVE_EXCLUSIVE
                    )
                }
                if (CodexEngine.provider(this) == CodexEngine.PROVIDER_GROQ) {
                    sp.append("\nGroq 接口适配中，急用请切换至 OpenRouter")
                }
                tvEnginePath.text = sp
            }
        }.start()
        refreshAdapterStatus()
    }

    private fun refreshAdapterStatus() {
        val tv = findViewById<TextView>(R.id.tvAdapter) ?: return
        val isDs = CodexEngine.provider(this) == CodexEngine.PROVIDER_DEEPSEEK
        if (!isDs) {
            tv.text = "转接头：当前供应商无需本地转接头"
            tv.setTextColor(ContextCompat.getColor(this, R.color.text_secondary))
            return
        }
        val port = DeepSeekAdapter.start(this)
        if (port <= 0) {
            tv.text = "转接头：❌ 启动失败（端口被占用）"
            tv.setTextColor(ContextCompat.getColor(this, R.color.danger_text))
            return
        }
        val last = DeepSeekAdapter.lastRequestAt
        val err = DeepSeekAdapter.lastError
        val state = if (DeepSeekAdapter.lastOk) "✅" else "●"
        tv.text = if (last > 0) {
            "转接头：$state 运行中（端口 $port）· 上次请求 " +
                java.text.SimpleDateFormat("HH:mm:ss", java.util.Locale.getDefault()).format(java.util.Date(last)) +
                (if (err != null) "\n最近错误：$err" else "")
        } else {
            "转接头：● 运行中（端口 $port）· 尚无请求（发一条消息即启用）"
        }
        tv.setTextColor(ContextCompat.getColor(this, if (DeepSeekAdapter.lastOk) R.color.text_secondary else R.color.warning))
    }

    private fun aboutText(): String {
        val v = try { packageManager.getPackageInfo(packageName, 0).versionName } catch (e: Exception) { "?" }
        return "Synaps v$v\n" +
            "内嵌 Codex 引擎 ${CodexEngine.VERSION}\n\n" +
            "这是一个真正自主的智能体：能思考、能执行命令、能下载依赖、能自己修复代码，全部在手机本机运行，无需 Termux。\n\n" +
            "当前服务商：${CodexEngine.providerLabel(CodexEngine.provider(this))}。"
    }
}
