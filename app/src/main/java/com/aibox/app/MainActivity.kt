package com.aibox.app

import android.app.AlertDialog
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.Manifest
import android.content.ClipData
import android.content.ClipboardManager
import android.content.pm.PackageManager
import android.speech.RecognizerIntent
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import android.speech.tts.TextToSpeech
import java.util.Locale
import androidx.core.app.NotificationCompat
import androidx.core.content.FileProvider
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.View
import android.view.inputmethod.EditorInfo
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.HorizontalScrollView
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.app.AppCompatDelegate
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.core.graphics.drawable.RoundedBitmapDrawableFactory
import androidx.drawerlayout.widget.DrawerLayout
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import org.json.JSONObject
import android.provider.OpenableColumns
import java.io.File
import java.io.FileOutputStream

class MainActivity : AppCompatActivity() {

    /** 发送后、收到首个文本前的气泡占位；所有"空内容"判断都必须把它视为空 */
    private val AI_THINKING = "思考中…"

    private lateinit var db: ChatDb
    private val messages = mutableListOf<ChatMsg>()
    private val sessions = mutableListOf<SessionRow>()
    private val allSessions = mutableListOf<SessionRow>()
    private lateinit var adapter: MsgAdapter
    private lateinit var sessionAdapter: SessionAdapter
    private lateinit var drawer: DrawerLayout
    private lateinit var recycler: RecyclerView
    private lateinit var recyclerSessions: RecyclerView
    private lateinit var etInput: EditText
    private lateinit var btnSend: ImageButton
    private lateinit var btnEngine: TextView
    private lateinit var tvTitle: TextView
    private lateinit var tvEngineState: TextView
    private lateinit var tvTokens: TextView
    private lateinit var btnRefreshBalance: ImageButton
    private lateinit var layWelcome: LinearLayout
    private lateinit var attachScroll: HorizontalScrollView
    private lateinit var attachBar: LinearLayout
    private lateinit var btnAttach: ImageButton
    private lateinit var btnModel: TextView
    private lateinit var btnSkill: TextView
    private val pendingAttachments = mutableListOf<File>()
    private var currentSkill: String? = null
    // 流式回复 UI 节流：累积 delta，主线程合并刷新，避免每字符一次 notifyItemChanged 卡死
    private var pendingDelta = StringBuilder()
    private var deltaRefreshQueued = false
    private var quoteMsg: ChatMsg? = null
    private var tts: TextToSpeech? = null
    private val main = Handler(Looper.getMainLooper())
    private val REQ_ATTACH = 1001
    private val REQ_VOICE = 1002
    private val skills = listOf(
        "通用对话" to "用自然、简洁的中文回答，像朋友一样交流，不要堆砌格式。",
        "编程专家" to "以资深工程师身份回答，需要时给出可直接运行的代码并简要解释。",
        "文档撰写" to "以正式中文撰写或润色文档，结构清晰、语言精炼。",
        "翻译润色" to "在中文与英文之间准确翻译，并优化表达。"
    )

    private var currentSessionId: String? = null
    private var currentThreadId: String? = null
    private var proc: Process? = null
    private var busy = false
    private var openingEngine = false
    private var autoRetry = false
    private var failoverBudget = 0
    private var lastUserText = ""
    /** 当前 AI 回复气泡在消息列表中的位置（工具消息按时间线插到它之前） */
    private var asstIdx = -1
    /** 最近一次余额查询结果（完整文本，点击 chip 展示） */
    private var lastBalanceFull = ""
    /** 直连 DeepSeek 断流自动重试计数（每次真实发送重置） */
    private var dsRetries = 0
    private var dsRetrying = false
    /** 任务计划卡片：-1=未创建；planMsgIdx 指向 messages 中的卡片位置 */
    private var planMsgIdx = -1
    private val planSteps = mutableListOf<String>()
    private val planDone = mutableListOf<Boolean>()
    /** 用户正在手动滚动/拖动列表时，禁止自动滚动打断 */
    private var userScrolling = false
    /** 余额自动刷新定时器（60s） */
    private val balanceTicker = object : Runnable {
        override fun run() {
            refreshBalance()
            main.postDelayed(this, 60000L)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        AppCompatDelegate.setDefaultNightMode(getSharedPreferences("theme", MODE_PRIVATE).getInt("mode", AppCompatDelegate.MODE_NIGHT_NO))
        super.onCreate(savedInstanceState)
        CrashLog.install(applicationContext)
        AnrWatchdog.install(applicationContext)
        // 同步模型/证书是文件 IO，放到后台线程；且仅在引擎已就绪时做，
        // 避免与首次初始化的解压/写文件并发导致文件损坏
        if (CodexEngine.isInitialized(this)) {
            Thread {
                CodexEngine.syncModels(this)
                CodexEngine.syncCerts(this)
            }.start()
        }
        db = ChatDb(this)
        setContentView(R.layout.activity_main)
        (getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager)
            .createNotificationChannel(NotificationChannel("chat", "对话通知", NotificationManager.IMPORTANCE_LOW))
        val sPrefs = getSharedPreferences("settings", MODE_PRIVATE)
        // 引擎能力全开：首次启动一次性请求全部权限；之后仅补通知权限
        if (!sPrefs.getBoolean("asked_perms", false)) {
            sPrefs.edit().putBoolean("asked_perms", true).apply()
            val need = Perms.missing(this)
            if (need.isNotEmpty()) requestPermissions(need, 9002)
        } else if (android.os.Build.VERSION.SDK_INT >= 33 &&
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 9002)
        }
        // 首次启动引导"所有文件访问"（写 /sdcard 需系统级授权）
        if (!sPrefs.getBoolean("asked_files", false)) {
            sPrefs.edit().putBoolean("asked_files", true).apply()
            if (!CodexEngine.hasAllFilesAccess(this)) {
                android.app.AlertDialog.Builder(this)
                    .setTitle("开启全部文件访问")
                    .setMessage("读写 /sdcard 需要系统级“所有文件访问”授权（设置→特殊权限→所有文件访问）。不开启也能正常聊天，仅无法读写外部存储。")
                    .setPositiveButton("去授权") { _, _ ->
                        try {
                            startActivity(Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION, Uri.parse("package:$packageName")))
                        } catch (_: Exception) {}
                    }
                    .setNegativeButton("稍后", null)
                    .show()
            }
        }
        // 开启过"后台保活"则常驻前台服务，切后台不中断
        if (sPrefs.getBoolean("keepalive", false)) {
            ChatForegroundService.start(this)
        }
        // 首次启动申请电池优化白名单：防止息屏/切后台时进程被冻结导致"无回复/中断"
        if (!sPrefs.getBoolean("asked_battery", false)) {
            sPrefs.edit().putBoolean("asked_battery", true).apply()
            val pm = getSystemService(POWER_SERVICE) as android.os.PowerManager
            if (!pm.isIgnoringBatteryOptimizations(packageName)) {
                try {
                    startActivity(Intent(android.provider.Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:$packageName")))
                } catch (_: Exception) {}
            }
        }

        drawer = findViewById(R.id.drawerLayout)
        showCrashNoticeIfAny()
        // 侧栏头像：直接裁剪成圆形，避免覆盖/正方形露边
        runCatching {
            val av = findViewById<ImageView>(R.id.imgAvatarSide)
            val bmp = BitmapFactory.decodeResource(resources, R.drawable.avatar_side)
            if (bmp != null) {
                val d = RoundedBitmapDrawableFactory.create(resources, bmp)
                d.isCircular = true
                d.setAntiAlias(true)
                av.setImageDrawable(d)
            }
        }
        applyChatBackground()
        recycler = findViewById(R.id.recycler)
        recyclerSessions = findViewById(R.id.recyclerSessions)
        etInput = findViewById(R.id.etInput)
        btnSend = findViewById(R.id.btnSend)
        btnEngine = findViewById(R.id.btnEngine)
        tvTitle = findViewById(R.id.tvTitle)
        tvEngineState = findViewById(R.id.tvEngineState)
        tvTokens = findViewById(R.id.tvTokens)
        tvTokens.setOnClickListener {
            if (lastBalanceFull.isNotBlank()) {
                Toast.makeText(this, lastBalanceFull, Toast.LENGTH_LONG).show()
            }
            refreshBalance()
        }
        btnRefreshBalance = findViewById(R.id.btnRefreshBalance)
        btnRefreshBalance.setOnClickListener { refreshBalance() }
        refreshBalance()
        layWelcome = findViewById(R.id.layWelcome)
        attachScroll = findViewById(R.id.attachScroll)
        attachBar = findViewById(R.id.attachBar)
        val layInputBox = findViewById<LinearLayout>(R.id.layInputBox)
        etInput.setOnFocusChangeListener { _, has ->
            layInputBox.setBackgroundResource(if (has) R.drawable.bg_input_codex_focus else R.drawable.bg_input_codex)
        }
        findViewById<View>(R.id.btnCloseQuote).setOnClickListener {
            quoteMsg = null
            findViewById<View>(R.id.quoteBar).visibility = View.GONE
        }
        btnAttach = findViewById(R.id.btnAttach)
        btnModel = findViewById(R.id.btnModel)
        btnSkill = findViewById(R.id.btnSkill)

        btnAttach.setOnClickListener { pickAttachment() }
        btnModel.setOnClickListener { showModelPicker() }
        btnSkill.setOnClickListener { showSkillPicker() }
        refreshModelChip()

        adapter = MsgAdapter(
            messages,
            { pos -> showAiMenu(pos) },
            { pos -> showUserMenu(pos) },
            { pos ->
                if (messages[pos].content.isNotBlank()) {
                    (getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager)
                        .setPrimaryClip(ClipData.newPlainText("ai", messages[pos].content))
                    Toast.makeText(this, "已复制", Toast.LENGTH_SHORT).show()
                }
            }
        )
        recycler.layoutManager = LinearLayoutManager(this)
        recycler.adapter = adapter
        // 关闭条目动画：流式回复每字符 notifyItemChanged，默认淡入淡出动画会导致整屏闪烁
        recycler.itemAnimator = null
        // 手指在滚动/拖动时不自动滚底，避免"拉到底又跳走"被打断
        recycler.addOnScrollListener(object : RecyclerView.OnScrollListener() {
            override fun onScrollStateChanged(r: RecyclerView, newState: Int) {
                userScrolling = newState == RecyclerView.SCROLL_STATE_DRAGGING ||
                    newState == RecyclerView.SCROLL_STATE_SETTLING
            }
        })

        sessionAdapter = SessionAdapter(
            sessions,
            { s -> openSession(s) },
            { s ->
                db.deleteSession(s.id)
                if (s.id == currentSessionId) newChat()
                refreshSessions()
            },
            { s -> showSessionMenu(s) }
        )
        recyclerSessions.layoutManager = LinearLayoutManager(this)
        recyclerSessions.adapter = sessionAdapter
        findViewById<android.widget.EditText>(R.id.etSearch).addTextChangedListener(object : android.text.TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
            override fun afterTextChanged(s: android.text.Editable?) {
                val q = s?.toString()?.trim().orEmpty()
                sessions.clear()
                sessions.addAll(if (q.isEmpty()) allSessions else allSessions.filter { it.title.contains(q, ignoreCase = true) })
                sessionAdapter.notifyDataSetChanged()
            }
        })

        findViewById<View>(R.id.btnDrawer).setOnClickListener { drawer.openDrawer(Gravity.START) }
        findViewById<View>(R.id.btnNewChat).setOnClickListener { newChat() }
        findViewById<View>(R.id.btnMore).setOnClickListener { showChatMore() }
        val examples = listOf(
            R.id.btnEg1 to "帮我写一份本周的工作周报",
            R.id.btnEg2 to "用最简单的话解释什么是量子计算",
            R.id.btnEg3 to "帮我规划一次 3 天的成都旅行"
        )
        examples.forEach { (id, text) ->
            findViewById<View>(id).setOnClickListener {
                etInput.setText(text)
                send()
            }
        }
        // 点击设置：直接启动，不等待抽屉动画，避免卡顿/黑屏
        findViewById<View>(R.id.btnSettings).setOnClickListener {
            startActivity(Intent(this@MainActivity, SettingsActivity::class.java))
        }
        btnEngine.setOnClickListener {
            if (CodexEngine.isInitialized(this)) startActivity(Intent(this, SettingsActivity::class.java))
            else startEngine()
        }
        btnSend.setOnClickListener { send() }
        etInput.setOnEditorActionListener { _, actionId, _ ->
            if (actionId == EditorInfo.IME_ACTION_SEND) { send(); true } else false
        }
    }

    override fun onResume() {
        SplashActivity.enteredMain = true
        applyChatBackground()
        super.onResume()
        // applySharedFolder 内部可能执行 waitFor 等待 ln 命令，必须放后台线程，否则主线程可能被卡死
        if (CodexEngine.isInitialized(this)) {
            Thread { CodexEngine.applySharedFolder(this) }.start()
        }
        refreshEngineState()
        refreshSessions()
        refreshBalance()
        // 余额自动刷新：60s 一次，进页面运行、退页面停止
        main.removeCallbacks(balanceTicker)
        main.postDelayed(balanceTicker, 60000L)
        if (!CodexEngine.isInitialized(this)) {
            openingEngine = false
            startEngine()
        }
    }

    private fun startEngine() {
        startActivity(Intent(this, EngineActivity::class.java))
    }

    private fun refreshEngineState() {
        val ok = CodexEngine.isInitialized(this)
        btnEngine.text = if (ok) "引擎就绪" else "引擎未就绪"
        btnEngine.setTextColor(ContextCompat.getColor(this, R.color.text_primary))
        btnEngine.visibility = if (ok) View.GONE else View.VISIBLE
        tvEngineState.text = if (ok) "内嵌 Codex 智能体 · 已就绪" else "需要初始化引擎"
        tvEngineState.setTextColor(ContextCompat.getColor(this, R.color.text_secondary))
    }

    private fun refreshSessions() {
        // 数据库查询放到后台线程，避免历史会话多时主线程卡顿
        Thread {
            val list = runCatching {
                db.sessions().map { s ->
                    val last = db.lastMessage(s.id)
                    val preview = last?.content?.replace('\n', ' ')?.trim()?.take(32) ?: ""
                    s.copy(subtitle = preview, timeLabel = timeLabel(s.updated))
                }
            }.getOrDefault(emptyList())
            main.post {
                allSessions.clear(); allSessions.addAll(list)
                val q = findViewById<android.widget.EditText>(R.id.etSearch)?.text?.toString()?.trim().orEmpty()
                sessions.clear()
                sessions.addAll(if (q.isEmpty()) allSessions else allSessions.filter { it.title.contains(q, ignoreCase = true) })
                sessionAdapter.notifyDataSetChanged()
            }
        }.start()
    }

    private fun timeLabel(ts: Long): String {
        val now = System.currentTimeMillis()
        val cal = java.util.Calendar.getInstance().apply { timeInMillis = ts }
        val today = java.util.Calendar.getInstance()
        val fmt = java.text.SimpleDateFormat("HH:mm", java.util.Locale.getDefault())
        return when {
            ts <= 0 -> ""
            cal.get(java.util.Calendar.YEAR) == today.get(java.util.Calendar.YEAR) &&
                cal.get(java.util.Calendar.DAY_OF_YEAR) == today.get(java.util.Calendar.DAY_OF_YEAR) -> "今天 " + fmt.format(cal.time)
            cal.get(java.util.Calendar.YEAR) == today.get(java.util.Calendar.YEAR) &&
                cal.get(java.util.Calendar.DAY_OF_YEAR) == today.get(java.util.Calendar.DAY_OF_YEAR) - 1 -> "昨天"
            cal.get(java.util.Calendar.YEAR) == today.get(java.util.Calendar.YEAR) ->
                java.text.SimpleDateFormat("MM-dd", java.util.Locale.getDefault()).format(cal.time)
            else -> java.text.SimpleDateFormat("yyyy-MM-dd", java.util.Locale.getDefault()).format(cal.time)
        }
    }

    private fun showSessionMenu(s: SessionRow) {
        val items = mutableListOf("重命名")
        if (s.pinned) items.add("取消置顶") else items.add("置顶")
        items.add("删除会话")
        Ui.menu(this, s.title.take(16), items) { which ->
            when (items[which]) {
                "重命名" -> {
                    val input = EditText(this).apply {
                        setText(s.title)
                        setSelection(text.length)
                    }
                    android.app.AlertDialog.Builder(this)
                        .setTitle("重命名会话")
                        .setView(input)
                        .setPositiveButton("保存") { _, _ ->
                            val t = input.text.toString().trim()
                            if (t.isNotEmpty()) { db.renameTitle(s.id, t); refreshSessions() }
                        }
                        .setNegativeButton("取消", null)
                        .show()
                }
                "置顶" -> { db.setPinned(s.id, true); refreshSessions() }
                "取消置顶" -> { db.setPinned(s.id, false); refreshSessions() }
                "删除会话" -> {
                    db.deleteSession(s.id)
                    if (s.id == currentSessionId) newChat()
                    refreshSessions()
                }
            }
        }
    }

    private fun newChat() {
        currentSessionId = null
        currentThreadId = null
        messages.clear()
        adapter.notifyDataSetChanged()
        tvTitle.text = "新对话"
        refreshBalance()
        layWelcome.visibility = View.VISIBLE
        drawer.closeDrawers()
        busy = false
        updateSendBtn()
        resetPlan()
    }

    private fun openSession(s: SessionRow) {
        currentSessionId = s.id
        currentThreadId = s.id
        tvTitle.text = s.title.ifBlank { "未命名对话" }
        layWelcome.visibility = View.GONE
        drawer.closeDrawers()
        resetPlan()
        // 长对话的消息读取放到后台线程，避免点击会话卡顿
        Thread {
            val loaded = runCatching {
                db.messages(s.id).map { m ->
                    when (m.role) {
                        "user" -> ChatMsg("user", m.content)
                        "ai" -> ChatMsg("ai", m.content)
                        else -> ChatMsg("sys", m.content)
                    }
                }
            }.getOrDefault(emptyList())
            main.post {
                messages.clear()
                messages.addAll(loaded)
                adapter.notifyDataSetChanged()
                recycler.scrollToPosition(messages.size - 1)
            }
        }.start()
    }

    private fun send() {
        if (!dsRetrying) dsRetries = 0
        dsRetrying = false
        resetPlan()
        val text = etInput.text.toString().trim()
        if (text.isEmpty() || busy) return
        if (!CodexEngine.isInitialized(this)) {
            Toast.makeText(this, "请先初始化引擎", Toast.LENGTH_SHORT).show()
            startEngine()
            return
        }
        val key = CodexEngine.apiKey(this)
        if (key.isBlank()) {
            Toast.makeText(this, "请先在“设置”填写 DeepSeek API Key", Toast.LENGTH_LONG).show()
            startActivity(Intent(this, SettingsActivity::class.java))
            return
        }

        val preSend = ArrayList(messages)
        busy = true
        etInput.setText("")
        layWelcome.visibility = View.GONE
        CodexEngine.addUsage(this, text.length)
        var fullPrompt = text
        quoteMsg?.let { q ->
            fullPrompt = "（对方引用了一条消息：\"${q.content.take(200)}\"，请针对这条引用回复）\n\n$fullPrompt"
        }
        currentSkill?.let { sk ->
            skills.firstOrNull { it.first == sk }?.let { fullPrompt = "${it.second}\n\n$fullPrompt" }
        }
        if (pendingAttachments.isNotEmpty()) {
            val paths = pendingAttachments.joinToString("\n") { " - $it" }
            fullPrompt += "\n\n（本次消息附带以下文件，请先读取它们再回答：\n$paths\n）"
        }
        val sid = if (autoRetry) (currentSessionId ?: ensureSession(text)) else ensureSession(text)
        if (!autoRetry) {
            failoverBudget = CodexEngine.availableProviders(this).size - 1
            tvTitle.text = text.take(18)
            db.addMessage(sid, "user", text)
            messages.add(ChatMsg("user", text))
            adapter.notifyItemInserted(messages.size - 1)
        }
        lastUserText = text
        asstIdx = messages.size
        // 先给一个可见的"思考中"占位，避免发送后气泡空白让用户以为卡死
        messages.add(ChatMsg("ai", AI_THINKING))
        adapter.notifyItemInserted(asstIdx)
        scrollBottom()
        updateSendBtn()

        val sb = StringBuilder()
        val events = mutableListOf<Pair<String, String>>()
        // 超时策略：不是"总时长 30 秒"，而是"连续 120 秒没有任何输出"才结束。
        // DeepSeek 思考模型可能长时间无 content 分片（只有 reasoning），60 秒会误杀导致永远无回复。
        val idleMs = 180000L
        val noReply = Runnable {
            if (!busy) return@Runnable
            CodexEngine.stop(proc)
            main.post {
                busy = false
                updateSendBtn()
                autoRetry = false
                val tail = CodexEngine.runLog(this, 45)
                val reason = if (tail.startsWith("（暂无")) {
                    "引擎在 180 秒内没有任何输出（可能网络无法连接模型服务）。"
                } else {
                    "引擎连续 180 秒无输出，已强制结束。最近日志：\n" + tail.take(1400)
                }
                messages[asstIdx].content = "⚠️ $reason"
                adapter.notifyItemChanged(asstIdx)
            }
        }
        val armIdle = {
            main.removeCallbacks(noReply)
            main.postDelayed(noReply, idleMs)
        }
        armIdle()
        // 直连模式：DeepSeek 直接走官方 /chat/completions，绕开 codex 引擎与本地转接头。
        // 手机上 codex 引擎链路多次"200 后无输出"，直连是唯一完全可控的通道。
        if (CodexEngine.provider(this) == CodexEngine.PROVIDER_DEEPSEEK) {
            val history = messages.take(asstIdx).mapNotNull { m ->
                when (m.role) {
                    "user" -> "user" to m.content
                    "ai" -> "assistant" to m.content
                    else -> null
                }
            }
            val doneSave = Runnable {
                if (messages[asstIdx].content != AI_THINKING && messages[asstIdx].content.isNotBlank()) {
                    currentSessionId?.let { sid2 -> db.addMessage(sid2, "ai", messages[asstIdx].content) }
                    CodexEngine.addUsage(this, messages[asstIdx].content.length)
                    notifyDone(messages[asstIdx].content.take(80))
                }
                refreshSessions()
            }
            // 保活：回复期间通知栏常驻，防止切后台被系统冻结
            ChatForegroundService.start(this)
            DeepSeekDirect.chatWithTools(this, history, fullPrompt, key,
                onDelta = { d ->
                    synchronized(pendingDelta) { pendingDelta.append(d) }
                    if (!deltaRefreshQueued) {
                        deltaRefreshQueued = true
                        main.post {
                            deltaRefreshQueued = false
                            var chunk: String
                            synchronized(pendingDelta) { chunk = pendingDelta.toString(); pendingDelta.clear() }
                            if (chunk.isEmpty()) return@post
                            armIdle()
                            sb.append(chunk)
                            messages[asstIdx].content = sb.toString()
                            adapter.notifyItemChanged(asstIdx)
                            autoScrollBottom()
                        }
                    }
                },
                onUsage = {},
                onPlanStart = { args -> main.post { createPlanCard(args) } },
                onPlanStep = { n -> main.post { markPlanStep(n) } },
                onPlanDone = { main.post { finishPlan() } },
                onToolStart = { name, brief ->
                    main.post {
                        if (name == "set_plan" || name.startsWith("plan")) return@post
                        val label = when (name) {
                            "exec_command" -> "🔧 执行命令"
                            "read_file" -> "📄 读取文件"
                            "write_file" -> "✏️ 写入文件"
                            "web_search" -> "🌐 联网搜索"
                            "download_file" -> "⬇️ 下载文件"
                            "http_get" -> "🌐 请求网页"
                            else -> "🛠 $name"
                        }
                        appendToolMsg("⏳ $label 执行中…")
                    }
                },
                onTool = { name, brief ->
                    main.post {
                        if (name == "set_plan" || name.startsWith("plan")) return@post
                        val label = when (name) {
                            "exec_command" -> "🔧 执行命令"
                            "read_file" -> "📄 读取文件"
                            "write_file" -> "✏️ 写入文件"
                            "web_search" -> "🌐 联网搜索"
                            "download_file" -> "⬇️ 下载文件"
                            "http_get" -> "🌐 请求网页"
                            else -> "🛠 $name"
                        }
                        finishToolMsg("$label：$brief")
                    }
                },
                onDone = { _ ->
                    main.post {
                        main.removeCallbacks(noReply)
                        ChatForegroundService.stop(this@MainActivity)
                        busy = false
                        updateSendBtn()
                        doneSave.run()
                        refreshBalance()
                    }
                },
                onError = { e ->
                    main.post {
                        main.removeCallbacks(noReply)
                        ChatForegroundService.stop(this@MainActivity)
                        busy = false
                        updateSendBtn()
                        if (dsRetries < 2 && isNetError(e)) {
                            // 断流自动重试：恢复发送前快照，清掉中断痕迹，重新发送
                            dsRetries++
                            dsRetrying = true
                            messages.clear()
                            messages.addAll(preSend)
                            adapter.notifyDataSetChanged()
                            etInput.setText(lastUserText)
                            send()
                            return@post
                        }
                        if (messages[asstIdx].content == AI_THINKING || messages[asstIdx].content.isBlank()) {
                            messages[asstIdx].content = "⚠️ $e"
                            adapter.notifyItemChanged(asstIdx)
                            doneSave.run()
                        } else {
                            // 已收到部分内容后中断：追加提示，避免"说一半停住、无任何提示"
                            messages[asstIdx].content += "\n\n⚠️（回复中断：$e）"
                            adapter.notifyItemChanged(asstIdx)
                            doneSave.run()
                        }
                    }
                })
            return
        }
        proc = CodexEngine.send(this, currentThreadId, fullPrompt) { ev ->
            main.post {
                armIdle()
                when (ev.kind) {
                    "thread" -> {
                        val tid = ev.threadId
                        if (tid != null) {
                            currentThreadId = tid
                            db.renameSession(sid, tid)
                            currentSessionId = tid
                            refreshSessions()
                        }
                    }
                    "text" -> {
                        synchronized(pendingDelta) { pendingDelta.append(ev.text) }
                        if (!deltaRefreshQueued) {
                            deltaRefreshQueued = true
                            main.post {
                                deltaRefreshQueued = false
                                var chunk: String
                                synchronized(pendingDelta) { chunk = pendingDelta.toString(); pendingDelta.clear() }
                                if (chunk.isEmpty()) return@post
                                armIdle()
                                sb.append(chunk)
                                events.add("text" to chunk)
                                messages[asstIdx].content = sb.toString()
                                adapter.notifyItemChanged(asstIdx)
                                autoScrollBottom()
                            }
                        }
                    }
                    "reasoning" -> {
                        if (ev.text.isNotBlank()) {
                            events.add("reasoning" to ev.text)
                        }
                    }
                    "tool" -> {
                        events.add("tool" to ev.text)
                        appendToolMsg("🔧 ${ev.text}")
                    }
                    "error" -> {
                        messages[asstIdx].content = if (messages[asstIdx].content == AI_THINKING)
                            "⚠️ ${ev.text}" else (messages[asstIdx].content + "\n⚠️ ${ev.text}").trim()
                        adapter.notifyItemChanged(asstIdx)
                    }
                    "exit" -> {
                        main.removeCallbacks(noReply)
                        busy = false
                        updateSendBtn()
                        val emptyAi = messages[asstIdx].content.isBlank() || messages[asstIdx].content == AI_THINKING
                        if (emptyAi && failoverBudget > 0) {
                            val next = CodexEngine.nextProvider(this)
                            if (next != null) {
                                failoverBudget--
                                autoRetry = true
                                CodexEngine.saveProvider(this, next)
                                refreshModelChip()
                                // 切换供应商必须开新线程：续接会继承旧会话的工具/模型状态，导致同款失败
                                currentThreadId = null
                                messages.removeAt(asstIdx)
                                adapter.notifyItemRemoved(asstIdx)
                                etInput.setText(lastUserText)
                                Toast.makeText(this, "当前服务商无回复，已自动切换到 ${CodexEngine.providerLabel(next)} 重试", Toast.LENGTH_LONG).show()
                                send()
                                return@post
                            }
                        }
                        autoRetry = false
                        if (emptyAi && events.any { it.first == "reasoning" }) {
                            messages[asstIdx].content = "（已思考，未输出文本）"
                            adapter.notifyItemChanged(asstIdx)
                        }
                        if (emptyAi) {
                            val tail = CodexEngine.runLog(this, 45)
                            if (!tail.startsWith("（暂无")) {
                                messages[asstIdx].content = "⚠️ 引擎无回复。最近运行日志：\n" + tail.take(1200)
                                adapter.notifyItemChanged(asstIdx)
                            }
                        }
                        if (messages[asstIdx].content != AI_THINKING) {
                            currentSessionId?.let { sid2 -> db.addMessage(sid2, "ai", messages[asstIdx].content) }
                        }
                        refreshSessions()
                        if (messages[asstIdx].content.isNotBlank() && messages[asstIdx].content != AI_THINKING) {
                            CodexEngine.addUsage(this, messages[asstIdx].content.length)
                            notifyDone(messages[asstIdx].content.take(80))
                        }
                        refreshBalance()
                    }
                }
            }
        }
        if (proc == null) {
            busy = false
            messages[asstIdx].content = "⚠️ 引擎启动失败"
            adapter.notifyItemChanged(asstIdx)
            updateSendBtn()
        } else {
            clearAttachments()
            quoteMsg = null
            findViewById<View>(R.id.quoteBar).visibility = View.GONE
        }
    }

    private fun showUserMenu(pos: Int) {
        val msg = messages[pos].content
        if (msg.isBlank()) return
        Ui.menu(this, "消息操作", listOf("复制", "引用")) { which ->
            when (which) {
                0 -> (getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager)
                    .setPrimaryClip(ClipData.newPlainText("user", msg))
                1 -> showQuote(messages[pos])
            }
        }
    }

    private fun showQuote(m: ChatMsg) {
        quoteMsg = m
        findViewById<TextView>(R.id.tvQuote).text = "引用 ${if (m.role == "user") "你" else "AI"}：${m.content.take(40)}"
        findViewById<View>(R.id.quoteBar).visibility = View.VISIBLE
    }

    private fun showAiMenu(pos: Int) {
        val msg = messages[pos].content
        if (msg.isBlank()) return
        val userText = (pos - 1 downTo 0).firstOrNull { messages[it].role == "user" }?.let { messages[it].content } ?: ""
        val options = mutableListOf("复制", "引用", "朗读")
        if (userText.isNotBlank()) options.add(0, "重新生成")
        Ui.menu(this, "消息操作", options) { which ->
            when (options[which]) {
                "复制" -> (getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager)
                    .setPrimaryClip(ClipData.newPlainText("ai", msg))
                "引用" -> showQuote(messages[pos])
                "朗读" -> speak(msg)
                "重新生成" -> {
                    etInput.setText(userText)
                    send()
                }
            }
        }
    }

    private fun speak(text: String) {
        if (tts == null) {
            tts = TextToSpeech(this) { status ->
                if (status == TextToSpeech.SUCCESS) {
                    tts?.language = Locale.CHINESE
                    tts?.speak(text, TextToSpeech.QUEUE_FLUSH, null, "synaps")
                } else {
                    Toast.makeText(this, "语音引擎不可用", Toast.LENGTH_SHORT).show()
                }
            }
        } else {
            tts?.speak(text, TextToSpeech.QUEUE_FLUSH, null, "synaps")
        }
    }

    private fun exportChat() {
        if (messages.isEmpty()) {
            Toast.makeText(this, "没有可导出的对话", Toast.LENGTH_SHORT).show()
            return
        }
        val sb = StringBuilder()
        sb.append("# ").append(tvTitle.text).append("\n\n")
        for (m in messages) {
            when (m.role) {
                "user" -> sb.append("**你**\n\n").append(m.content).append("\n\n")
                "ai" -> sb.append("**AI**\n\n").append(m.content).append("\n\n")
                else -> sb.append("> ").append(m.content).append("\n\n")
            }
        }
        val dir = File(cacheDir, "export").apply { mkdirs() }
        val f = File(dir, "chat.md")
        f.writeText(sb.toString())
        val uri = FileProvider.getUriForFile(this, "com.aibox.app.fileprovider", f)
        val share = Intent(Intent.ACTION_SEND).apply {
            type = "text/markdown"
            putExtra(Intent.EXTRA_STREAM, uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        startActivity(Intent.createChooser(share, "导出对话"))
    }

    /** 顶栏"⋯"菜单 */
    private fun showChatMore() {
        val anchor = findViewById<View>(R.id.btnMore)
        val popup = android.widget.PopupMenu(this, anchor)
        popup.menuInflater.inflate(R.menu.menu_chat_more, popup.menu)
        popup.setOnMenuItemClickListener { item ->
            when (item.itemId) {
                R.id.act_clear -> {
                    if (messages.isEmpty()) {
                        Toast.makeText(this, "当前对话为空", Toast.LENGTH_SHORT).show()
                    } else {
                        Ui.confirm(this, "清空当前对话", "将清空当前会话的全部消息，且无法撤销。", "清空") { newChat() }
                    }
                    true
                }
                R.id.act_export -> { exportChat(); true }
                else -> false
            }
        }
        popup.show()
    }

    private fun notifyDone(preview: String) {
        val nm = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        val pi = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val n = NotificationCompat.Builder(this, "chat")
            .setSmallIcon(R.drawable.ic_bolt)
            .setContentTitle("回复完成")
            .setContentText(preview)
            .setAutoCancel(true)
            .setContentIntent(pi)
            .build()
        nm.notify(1001, n)
    }

    private fun startVoiceInput() {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            ActivityCompat.requestPermissions(this, arrayOf(Manifest.permission.RECORD_AUDIO), REQ_VOICE)
            return
        }
        try {
            val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
                putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                putExtra(RecognizerIntent.EXTRA_LANGUAGE, "zh-CN")
                putExtra(RecognizerIntent.EXTRA_PROMPT, "说话中…")
            }
            startActivityForResult(intent, REQ_VOICE)
        } catch (e: Exception) {
            Toast.makeText(this, "此设备不支持语音输入", Toast.LENGTH_SHORT).show()
        }
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == REQ_VOICE && grantResults.isNotEmpty() && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
            startVoiceInput()
        } else if (requestCode == REQ_VOICE) {
            Toast.makeText(this, "需要麦克风权限才能语音输入", Toast.LENGTH_SHORT).show()
        }
    }

    private fun pickAttachment() {
        val intent = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            type = "*/*"
        }
        startActivityForResult(intent, REQ_ATTACH)
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == REQ_ATTACH && resultCode == RESULT_OK) {
            data?.data?.let { saveAttachment(it) }
        }
        if (requestCode == REQ_VOICE && resultCode == RESULT_OK) {
            val words = data?.getStringArrayListExtra(RecognizerIntent.EXTRA_RESULTS).orEmpty()
            if (words.isNotEmpty()) {
                val cur = etInput.text.toString()
                etInput.setText(if (cur.isBlank()) words[0] else "$cur${words[0]}")
                etInput.setSelection(etInput.text.length)
            }
        }
    }

    private fun saveAttachment(uri: Uri) {
        try {
            var name = "file"
            contentResolver.query(uri, null, null, null, null)?.use { c ->
                if (c.moveToFirst()) {
                    val idx = c.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                    if (idx >= 0) name = c.getString(idx) ?: "file"
                }
            }
            val dest = File(CodexEngine.attachmentsDir(this), name)
            contentResolver.openInputStream(uri)?.use { input ->
                FileOutputStream(dest).use { out -> input.copyTo(out) }
            }
            pendingAttachments.add(dest)
            addAttachChip(dest)
            attachScroll.visibility = View.VISIBLE
            Toast.makeText(this, "已添加附件：$name", Toast.LENGTH_SHORT).show()
        } catch (e: Exception) {
            Toast.makeText(this, "附件读取失败：${e.message}", Toast.LENGTH_SHORT).show()
        }
    }

    private fun addAttachChip(file: File) {
        val density = resources.displayMetrics.density
        val chip = TextView(this).apply {
            text = "📎 ${file.name} ×"
            textSize = 12f
            setTextColor(ContextCompat.getColor(context, R.color.chip_codex_text))
            background = resources.getDrawable(R.drawable.bg_chip_dark)
            setPadding((12 * density).toInt(), (8 * density).toInt(), (12 * density).toInt(), (8 * density).toInt())
            tag = file
        }
        chip.setOnClickListener {
            pendingAttachments.remove(file)
            attachBar.removeView(chip)
            if (pendingAttachments.isEmpty()) attachScroll.visibility = View.GONE
        }
        val lp = LinearLayout.LayoutParams(LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT)
        lp.marginEnd = (8 * density).toInt()
        attachBar.addView(chip, lp)
    }

    private fun clearAttachments() {
        pendingAttachments.clear()
        attachBar.removeAllViews()
        attachScroll.visibility = View.GONE
    }

    private fun showModelPicker() {
        val names = CodexEngine.PROVIDER_LABELS.map { it.second }
        Ui.sheet(this, "选择大脑（服务商）", names) { which ->
            CodexEngine.saveProvider(this, CodexEngine.PROVIDER_LABELS[which].first)
            refreshModelChip()
            Toast.makeText(this, "已切换：${CodexEngine.PROVIDER_LABELS[which].second}", Toast.LENGTH_SHORT).show()
        }
        Unit
    }

    private fun refreshModelChip() {
        btnModel.text = when (CodexEngine.provider(this)) {
            CodexEngine.PROVIDER_DEEPSEEK -> "DeepSeek ▾"
            CodexEngine.PROVIDER_GROQ -> "Groq ▾"
            CodexEngine.PROVIDER_AGNES -> "Agnes ▾"
            else -> "GPT-4o ▾"
        }
    }

    private fun showSkillPicker() {
        val names = skills.map { it.first }.toList()
        Ui.sheet(this, "选择技能", names) { which ->
            currentSkill = skills[which].first
            btnSkill.text = "技能：${skills[which].first}"
            Toast.makeText(this, "技能：${skills[which].first}", Toast.LENGTH_SHORT).show()
        }
        Unit
    }

    /** 顶栏 chip + 刷新按钮：显示账户余额（token 消耗改为余额） */
    private fun refreshBalance() {
        val key = CodexEngine.apiKey(this)
        if (key.isBlank()) {
            tvTokens.visibility = View.GONE
            btnRefreshBalance.visibility = View.GONE
            return
        }
        Thread {
            val full = CodexEngine.fetchBalance(this, key)
            val short = shortBalance(full)
            main.post {
                lastBalanceFull = full
                tvTokens.visibility = View.VISIBLE
                btnRefreshBalance.visibility = View.VISIBLE
                tvTokens.text = short
            }
        }.start()
    }

    /** 把完整余额文本压成一行；失败显示"余额 ?" */
    private fun shortBalance(full: String): String {
        if (full.isBlank() || full.startsWith("查询失败") || full.startsWith("该服务商")) return "余额 ?"
        val curRaw = Regex("^([A-Za-z$¥￥]+)\\s*余额：").find(full)?.groupValues?.get(1) ?: "$"
        val cur = if (curRaw.equals("CNY", true)) "¥" else if (curRaw.contains("$") || curRaw.equals("USD", true)) "$" else curRaw
        val num = Regex("余额：([0-9.,]+)").find(full)?.groupValues?.get(1)
            ?: Regex("剩余额度：\$?([0-9.,]+)").find(full)?.groupValues?.get(1)
        return if (num != null) "余额 $cur$num" else "余额 ?"
    }

    /** 合并连续工具调用为一条可展开消息；按时间线插到 AI 回复气泡之前（先发生的命令在上，文字回复在下） */
    private fun appendToolMsg(line: String) {
        val ai = asstIdx
        if (ai > 0 && messages[ai - 1].role == "sys" && (messages[ai - 1].content.startsWith("🔧") || messages[ai - 1].content.startsWith("⏳"))) {
            messages[ai - 1].content = messages[ai - 1].content + "\n" + line
            adapter.notifyItemChanged(ai - 1)
        } else {
            messages.add(ai, ChatMsg("sys", line))
            asstIdx++
            adapter.notifyItemInserted(ai)
        }
        autoScrollBottom()
    }

    /** 工具执行完成：把最后一条 ⏳ 执行中消息替换为结果；没有则追加 */
    private fun finishToolMsg(line: String) {
        val ai = asstIdx
        if (ai > 0 && messages[ai - 1].role == "sys" && messages[ai - 1].content.startsWith("⏳")) {
            messages[ai - 1].content = line
            adapter.notifyItemChanged(ai - 1)
        } else {
            appendToolMsg(line)
        }
        autoScrollBottom()
    }

    /** 上次启动发生过崩溃时，进主页面弹一次提示，方便拿到堆栈而不是瞎猜 */
    private fun showCrashNoticeIfAny() {
        val prefs = getSharedPreferences("crash_notice", MODE_PRIVATE)
        val lastShown = prefs.getLong("last_shown", 0L)
        val f = File(filesDir, "crash.log")
        if (!f.exists()) return
        val mtime = f.lastModified()
        if (mtime <= lastShown) return
        prefs.edit().putLong("last_shown", mtime).apply()
        val stack = CrashLog.lastCrash(this) ?: return
        main.postDelayed({
            if (!isFinishing && !isDestroyed) {
                Ui.info(this, "上次异常退出", "应用上次发生了一次崩溃，已记录。可到设置→导出诊断日志提交。\n\n$stack")
            }
        }, 600)
    }

    override fun onPause() {
        super.onPause()
        main.removeCallbacks(balanceTicker)
    }

    override fun onDestroy() {
        super.onDestroy()
        main.removeCallbacks(balanceTicker)
        try { tts?.stop(); tts?.shutdown() } catch (_: Exception) {}
    }

    private fun ensureSession(title: String): String {
        currentSessionId?.let { return it }
        val id = "tmp-${System.currentTimeMillis()}"
        db.insertSession(id, title.take(30))
        currentSessionId = id
        return id
    }

    /** 重置任务计划状态（新发送/切会话时调用） */
    private fun resetPlan() {
        planMsgIdx = -1
        planSteps.clear()
        planDone.clear()
    }

    /** 引擎调用 set_plan 时创建计划卡片 */
    private fun createPlanCard(args: String) {
        val steps = runCatching {
            val arr = JSONObject(args).optJSONArray("steps")
            val list = mutableListOf<String>()
            if (arr != null) for (i in 0 until arr.length()) list.add(arr.optString(i).trim())
            list
        }.getOrDefault(emptyList()).filter { it.isNotBlank() }.take(5)
        if (steps.isEmpty()) return
        resetPlan()
        planSteps.addAll(steps)
        planDone.addAll(steps.map { false })
        val content = planSteps.mapIndexed { i, st -> "⬜ ${i + 1}. $st" }.joinToString("\n")
        val card = ChatMsg("plan", content)
        val pos = asstIdx.coerceAtMost(messages.size)
        messages.add(pos, card)
        asstIdx = pos + 1
        planMsgIdx = pos
        adapter.notifyItemInserted(pos)
        autoScrollBottom()
    }

    /** 引擎调用 plan_step 时勾选对应步骤 */
    private fun markPlanStep(n: Int) {
        if (planMsgIdx !in messages.indices || n < 1 || n > planSteps.size) return
        if (planDone[n - 1]) return
        planDone[n - 1] = true
        messages[planMsgIdx].content = planSteps.mapIndexed { i, st ->
            (if (planDone[i]) "✅" else "⬜") + " ${i + 1}. $st"
        }.joinToString("\n")
        adapter.notifyItemChanged(planMsgIdx)
    }

    /** 引擎调用 plan_done 时收尾 */
    private fun finishPlan() {
        if (planMsgIdx !in messages.indices) return
        if (messages[planMsgIdx].content.contains("🏁")) return
        messages[planMsgIdx].content += "\n🏁 完成"
        adapter.notifyItemChanged(planMsgIdx)
    }

    private fun scrollBottom() {
        recycler.post { recycler.smoothScrollToPosition(messages.size - 1) }
    }

    /** 网络类错误判定：用于断流自动重试 */
    private fun isNetError(msg: String): Boolean {
        val low = msg.lowercase()
        return low.contains("ioexception") || low.contains("sockettimeout") || low.contains("timeout") ||
            low.contains("eof") || low.contains("connect") || low.contains("reset") ||
            low.contains("http 5") || low.contains("unexpected") || low.contains("failed to connect")
    }

    /** 智能滚动：仅在用户停留在底部附近时自动滚到底，向上翻看历史时不打扰 */
    private fun autoScrollBottom() {
        if (userScrolling) return
        val lm = recycler.layoutManager as? androidx.recyclerview.widget.LinearLayoutManager ?: return
        val count = lm.itemCount
        if (count <= 0) return
        if (lm.findLastVisibleItemPosition() >= count - 3) {
            recycler.scrollToPosition(count - 1)
        }
    }

    private fun updateSendBtn() {
        btnSend.setImageResource(if (busy) R.drawable.ic_stop else R.drawable.ic_send_arrow)
        btnSend.setOnClickListener {
            if (busy) {
                CodexEngine.stop(proc)
                busy = false
                updateSendBtn()
            } else {
                send()
            }
        }
    }

    private fun applyChatBackground() {
        val img = findViewById<ImageView>(R.id.chatBg) ?: return
        val f = File(filesDir, "chat_bg.jpg")
        if (!f.exists()) {
            img.visibility = View.GONE
            return
        }
        // 图片解码放到后台线程，避免每次进页面卡主线程
        Thread {
            val bmp = decodeSampled(f)
            main.post {
                if (bmp != null) {
                    img.visibility = View.VISIBLE
                    img.setImageBitmap(bmp)
                } else {
                    img.visibility = View.GONE
                }
            }
        }.start()
    }

    private fun decodeSampled(f: File): Bitmap? {
        val o = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(f.absolutePath, o)
        var sample = 1
        while (o.outWidth / sample > 1600 || o.outHeight / sample > 1600) sample *= 2
        return BitmapFactory.decodeFile(f.absolutePath, BitmapFactory.Options().apply { inSampleSize = sample })
    }



}
