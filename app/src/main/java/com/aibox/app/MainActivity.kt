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
    private lateinit var layWelcome: LinearLayout
    private lateinit var attachScroll: HorizontalScrollView
    private lateinit var attachBar: LinearLayout
    private lateinit var btnAttach: ImageButton
    private lateinit var btnModel: TextView
    private lateinit var btnSkill: TextView
    private val pendingAttachments = mutableListOf<File>()
    private var currentSkill: String? = null
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

    override fun onCreate(savedInstanceState: Bundle?) {
        AppCompatDelegate.setDefaultNightMode(getSharedPreferences("theme", MODE_PRIVATE).getInt("mode", AppCompatDelegate.MODE_NIGHT_NO))
        super.onCreate(savedInstanceState)
        CrashLog.install(applicationContext)
        CodexEngine.syncModels(this)
        CodexEngine.syncCerts(this)
        db = ChatDb(this)
        setContentView(R.layout.activity_main)
        (getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager)
            .createNotificationChannel(NotificationChannel("chat", "对话通知", NotificationManager.IMPORTANCE_LOW))
        if (android.os.Build.VERSION.SDK_INT >= 33 &&
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 9002)
        }

        drawer = findViewById(R.id.drawerLayout)
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
        if (CodexEngine.isInitialized(this)) CodexEngine.applySharedFolder(this)
        refreshEngineState()
        refreshSessions()
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
        val list = db.sessions().map { s ->
            val last = db.lastMessage(s.id)
            val preview = last?.content?.replace('\n', ' ')?.trim()?.take(32) ?: ""
            s.copy(subtitle = preview, timeLabel = timeLabel(s.updated))
        }
        allSessions.clear(); allSessions.addAll(list)
        val q = findViewById<android.widget.EditText>(R.id.etSearch)?.text?.toString()?.trim().orEmpty()
        sessions.clear()
        sessions.addAll(if (q.isEmpty()) allSessions else allSessions.filter { it.title.contains(q, ignoreCase = true) })
        sessionAdapter.notifyDataSetChanged()
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
        tvTokens.visibility = View.GONE
        layWelcome.visibility = View.VISIBLE
        drawer.closeDrawers()
        busy = false
        updateSendBtn()
    }

    private fun openSession(s: SessionRow) {
        currentSessionId = s.id
        currentThreadId = s.id
        messages.clear()
        db.messages(s.id).forEach { m ->
            messages.add(when (m.role) {
                "user" -> ChatMsg("user", m.content)
                "ai" -> ChatMsg("ai", m.content)
                else -> ChatMsg("sys", m.content)
            })
        }
        adapter.notifyDataSetChanged()
        tvTitle.text = s.title.ifBlank { "未命名对话" }
        layWelcome.visibility = View.GONE
        drawer.closeDrawers()
        recycler.scrollToPosition(messages.size - 1)
    }

    private fun send() {
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
        val asstIdx = messages.size
        // 先给一个可见的"思考中"占位，避免发送后气泡空白让用户以为卡死
        messages.add(ChatMsg("ai", AI_THINKING))
        adapter.notifyItemInserted(asstIdx)
        scrollBottom()
        updateSendBtn()

        val sb = StringBuilder()
        val events = mutableListOf<Pair<String, String>>()
        // 超时策略：不是"总时长 30 秒"，而是"连续 120 秒没有任何输出"才结束。
        // DeepSeek 思考模型可能长时间无 content 分片（只有 reasoning），60 秒会误杀导致永远无回复。
        val idleMs = 120000L
        val noReply = Runnable {
            if (!busy) return@Runnable
            CodexEngine.stop(proc)
            main.post {
                busy = false
                updateSendBtn()
                autoRetry = false
                val tail = CodexEngine.runLog(this, 45)
                val reason = if (tail.startsWith("（暂无")) {
                    "引擎在 120 秒内没有任何输出（可能网络无法连接模型服务）。"
                } else {
                    "引擎连续 120 秒无输出，已强制结束。最近日志：\n" + tail.take(1400)
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
                    main.post {
                        armIdle()
                        sb.append(d)
                        messages[asstIdx].content = sb.toString()
                        adapter.notifyItemChanged(asstIdx)
                        scrollBottom()
                    }
                },
                onUsage = { total ->
                    main.post {
                        tvTokens.visibility = View.VISIBLE
                        tvTokens.text = "⚡ " + String.format("%,d", total)
                    }
                },
                onTool = { name, brief ->
                    main.post {
                        val label = when (name) {
                            "exec_command" -> "🔧 执行命令"
                            "read_file" -> "📄 读取文件"
                            "write_file" -> "✏️ 写入文件"
                            "web_search" -> "🌐 联网搜索"
                            else -> "🛠 $name"
                        }
                        messages.add(ChatMsg("sys", "$label：$brief"))
                        adapter.notifyItemInserted(messages.size - 1)
                        scrollBottom()
                    }
                },
                onDone = { _ ->
                    main.post {
                        main.removeCallbacks(noReply)
                        ChatForegroundService.stop(this@MainActivity)
                        busy = false
                        updateSendBtn()
                        doneSave.run()
                    }
                },
                onError = { e ->
                    main.post {
                        main.removeCallbacks(noReply)
                        ChatForegroundService.stop(this@MainActivity)
                        busy = false
                        updateSendBtn()
                        if (messages[asstIdx].content == AI_THINKING || messages[asstIdx].content.isBlank()) {
                            messages[asstIdx].content = "⚠️ $e"
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
                        sb.append(ev.text)
                        events.add("text" to ev.text)
                        messages[asstIdx].content = sb.toString()
                        adapter.notifyItemChanged(asstIdx)
                        scrollBottom()
                    }
                    "reasoning" -> {
                        if (ev.text.isNotBlank()) {
                            events.add("reasoning" to ev.text)
                        }
                    }
                    "tool" -> {
                        events.add("tool" to ev.text)
                        messages.add(ChatMsg("sys", "🔧 ${ev.text}"))
                        adapter.notifyItemInserted(messages.size - 1)
                        scrollBottom()
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
                    tts?.speak(text, TextToSpeech.QUEUE_FLUSH, null, "aibox")
                } else {
                    Toast.makeText(this, "语音引擎不可用", Toast.LENGTH_SHORT).show()
                }
            }
        } else {
            tts?.speak(text, TextToSpeech.QUEUE_FLUSH, null, "aibox")
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

    override fun onDestroy() {
        super.onDestroy()
        try { tts?.stop(); tts?.shutdown() } catch (_: Exception) {}
    }

    private fun ensureSession(title: String): String {
        currentSessionId?.let { return it }
        val id = "tmp-${System.currentTimeMillis()}"
        db.insertSession(id, title.take(30))
        currentSessionId = id
        return id
    }

    private fun scrollBottom() {
        recycler.post { recycler.smoothScrollToPosition(messages.size - 1) }
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
        if (f.exists()) {
            img.visibility = View.VISIBLE
            img.setImageBitmap(decodeSampled(f))
        } else {
            img.visibility = View.GONE
        }
    }

    private fun decodeSampled(f: File): Bitmap? {
        val o = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(f.absolutePath, o)
        var sample = 1
        while (o.outWidth / sample > 1600 || o.outHeight / sample > 1600) sample *= 2
        return BitmapFactory.decodeFile(f.absolutePath, BitmapFactory.Options().apply { inSampleSize = sample })
    }



}
