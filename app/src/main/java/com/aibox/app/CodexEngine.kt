package com.aibox.app

import android.content.Context
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.os.Handler
import android.os.Looper
import androidx.preference.PreferenceManager
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import android.provider.DocumentsContract
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import java.util.zip.GZIPInputStream
import java.util.zip.ZipInputStream

data class EngineEvent(val kind: String, val text: String = "", val threadId: String? = null)

object CodexEngine {

    const val VERSION = "0.147.0"
    const val PROVIDER_DEEPSEEK = "deepseek"
    const val PROVIDER_OPENROUTER = "openrouter"
    const val PROVIDER_GROQ = "groq"
    const val PROVIDER_AGNES = "agnes"
    const val PROVIDER_OPENAI = "openai"
    const val PROVIDER_LOCAL = "local" // 本地模型（LiteRT/Qwen3 占位，推理后端后续补齐）

    /** 实测确认 /responses 严格校验、拒绝 custom 工具（apply_patch）的供应商。
     *  DeepSeek 走本地转接头（DeepSeekAdapter），custom 工具必被剥离，故引擎侧一并关闭，
     *  避免模型被提示"可用 apply_patch"却调用失败。 */
    private val APPLY_PATCH_DISABLED = setOf(PROVIDER_AGNES, PROVIDER_GROQ, PROVIDER_DEEPSEEK)

    val PROVIDER_LABELS = listOf(
        PROVIDER_OPENAI to "OpenAI（GPT-5 · Codex 同款）",
        PROVIDER_OPENROUTER to "OpenRouter（GPT-4o-mini）",
        PROVIDER_DEEPSEEK to "DeepSeek（本地转接头）",
        PROVIDER_AGNES to "Agnes（聚合 · 已实测可用）",
        PROVIDER_GROQ to "Groq（实验性）",
        PROVIDER_LOCAL to "本地·千问3（离线）"
    )
    private const val URL_BOOTSTRAP = "https://github.com/abuaibobo-dev/aibox-updates/releases/download/v2.0.1/bootstrap-aarch64.zip"
    private const val URL_TOOLS = "https://github.com/abuaibobo-dev/aibox-updates/releases/download/tools-v1/aibox-static-tools-aarch64.tar.gz"
    private const val URL_CODEX = "https://github.com/abuaibobo-dev/aibox-updates/releases/download/v2.0.1/codex-bin.gz"
    private const val PREFIX_MARK = "/data/data/com.termux/files/usr"

    private val client = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(300, TimeUnit.SECONDS)
        .writeTimeout(120, TimeUnit.SECONDS)
        .build()

    data class Paths(
        val prefix: File, val home: File, val codexHome: File, val work: File,
        val bin: File, val lib: File, val tmp: File, val codexBin: File,
        val cache: File, val config: File, val models: File
    )

    /** 子进程执行结果：output 为空表示执行失败/超时/无输出 */
    private data class RunResult(val output: String?, val exitCode: Int, val detail: String)

    @Volatile
    private var lastCheck: RunResult? = null

    fun paths(ctx: Context): Paths {
        val root = File(ctx.filesDir, "codex")
        val prefix = File(root, "prefix")
        val home = File(root, "home")
        return Paths(
            prefix = prefix,
            home = home,
            codexHome = File(home, ".codex"),
            work = File(root, "work"),
            bin = File(prefix, "bin"),
            lib = File(prefix, "lib"),
            tmp = File(prefix, "tmp"),
            codexBin = File(prefix, "bin/codex"),
            cache = File(root, "cache"),
            config = File(home, ".codex/config.toml"),
            models = File(home, ".codex/models.json")
        )
    }

    fun isInitialized(ctx: Context): Boolean {
        val p = paths(ctx)
        return p.codexBin.exists() && File(p.bin, "bash").exists() &&
            p.config.exists() && p.models.exists() && engineOk(ctx).exists()
    }

    /** 自检通过标记：init 验证成功后写入，避免“文件在但引擎跑不起来”时被误判为就绪 */
    private fun engineOk(ctx: Context): File = File(ctx.filesDir, "codex/engine.ok")

    fun provider(ctx: Context): String =
        PreferenceManager.getDefaultSharedPreferences(ctx).getString("provider", PROVIDER_OPENROUTER) ?: PROVIDER_OPENROUTER

    fun saveProvider(ctx: Context, p: String) {
        PreferenceManager.getDefaultSharedPreferences(ctx).edit().putString("provider", p).apply()
        try { if (paths(ctx).config.exists()) applyConfig(ctx) } catch (_: Exception) {}
    }

    private fun keyPref(provider: String): String = when (provider) {
        PROVIDER_DEEPSEEK -> "ds_key"
        PROVIDER_GROQ -> "gsk_key"
        PROVIDER_AGNES -> "agnes_key"
        PROVIDER_OPENAI -> "openai_key"
        PROVIDER_LOCAL -> "local_key"
        else -> "or_key"
    }

    fun providerModel(provider: String): String = when (provider) {
        PROVIDER_DEEPSEEK -> "deepseek-v4-flash"
        PROVIDER_GROQ -> "llama-3.3-70b-versatile"
        PROVIDER_AGNES -> "agnes-2.5-flash"
        PROVIDER_OPENAI -> "gpt-5"
        PROVIDER_LOCAL -> "qwen3-1.7b"
        else -> "openai/gpt-4o-mini"
    }

    fun providerBaseUrl(provider: String): String = when (provider) {
        PROVIDER_DEEPSEEK -> "https://api.deepseek.com"
        PROVIDER_GROQ -> "https://api.groq.com/openai/v1"
        PROVIDER_AGNES -> "https://apihub.agnes-ai.cn/v1"
        PROVIDER_OPENAI -> "https://api.openai.com/v1"
        PROVIDER_LOCAL -> "http://127.0.0.1:11434/v1"
        else -> "https://openrouter.ai/api/v1"
    }

    fun providerLabel(provider: String): String =
        PROVIDER_LABELS.firstOrNull { it.first == provider }?.second ?: provider

    fun apiKey(ctx: Context): String = keyForProvider(ctx, provider(ctx))

    fun keyForProvider(ctx: Context, p: String): String =
        CryptoKey.decrypt(ctx, PreferenceManager.getDefaultSharedPreferences(ctx).getString(keyPref(p), "") ?: "").trim()

    /** 已保存了 Key 的服务商（可用于自动切换） */
    fun availableProviders(ctx: Context): List<String> =
        PROVIDER_LABELS.map { it.first }.filter { keyForProvider(ctx, it).isNotBlank() }

    /** 当前服务商的下一个可用服务商（失败时自动切换） */
    fun nextProvider(ctx: Context): String? {
        val avail = availableProviders(ctx)
        if (avail.size < 2) return null
        val cur = provider(ctx)
        val idx = avail.indexOf(cur)
        if (idx < 0) return avail.firstOrNull()
        return if (idx < avail.size - 1) avail[idx + 1] else null
    }

    fun saveApiKey(ctx: Context, key: String) {
        PreferenceManager.getDefaultSharedPreferences(ctx).edit().putString(keyPref(provider(ctx)), CryptoKey.encrypt(ctx, key.trim())).apply()
        try { if (paths(ctx).config.exists()) applyConfig(ctx) } catch (_: Exception) {}
    }

    private const val PREFS_GH = "github"

    /** GitHub Token：引擎 push 代码/发布直链用（本地加密保存） */
    fun ghToken(ctx: Context): String =
        CryptoKey.decrypt(ctx, ctx.getSharedPreferences(PREFS_GH, Context.MODE_PRIVATE).getString("token", "") ?: "").trim()

    fun saveGhToken(ctx: Context, token: String) {
        ctx.getSharedPreferences(PREFS_GH, Context.MODE_PRIVATE)
            .edit().putString("token", CryptoKey.encrypt(ctx, token.trim())).apply()
    }

    // ---------- 技能（动态：引擎通过 manage_skills 实时增删改，UI 每次打开实时读取） ----------

    fun skillsFile(ctx: Context): File = File(ctx.filesDir, "codex/skills.json")

    fun loadSkills(ctx: Context): List<Pair<String, String>> {
        val f = skillsFile(ctx)
        if (f.exists()) {
            try {
                val arr = JSONArray(f.readText())
                val list = mutableListOf<Pair<String, String>>()
                for (i in 0 until arr.length()) {
                    val o = arr.optJSONObject(i) ?: continue
                    val n = o.optString("name").trim()
                    val p = o.optString("prompt").trim()
                    if (n.isNotEmpty() && p.isNotEmpty()) list.add(n to p)
                }
                if (list.isNotEmpty()) return list
            } catch (_: Exception) {}
        }
        val defaults = listOf(
            "通用对话" to "用自然、简洁的中文回答，像朋友一样交流，不要堆砌格式。",
            "编程专家" to "以资深工程师身份回答，需要时给出可直接运行的代码并简要解释。",
            "文档撰写" to "以正式中文撰写或润色文档，结构清晰、语言精炼。",
            "翻译润色" to "在中文与英文之间准确翻译，并优化表达。"
        )
        saveSkills(ctx, defaults)
        return defaults
    }

    fun saveSkills(ctx: Context, list: List<Pair<String, String>>) {
        val arr = JSONArray()
        for ((n, p) in list) arr.put(JSONObject().put("name", n).put("prompt", p))
        val f = skillsFile(ctx)
        f.parentFile?.mkdirs()
        f.writeText(arr.toString())
    }

    fun skillPrompt(ctx: Context, name: String): String? =
        loadSkills(ctx).firstOrNull { it.first == name }?.second

    /** 每次启动用内置最新 models.json 覆盖旧文件，避免旧版（枚举值不合法）导致引擎配置 fallback */
    fun syncModels(ctx: Context) {
        val p = paths(ctx)
        try {
            val want = ctx.assets.open("models.json").use { it.readBytes() }
            if (!p.models.exists() || p.models.readBytes().contentEquals(want).not()) {
                p.models.parentFile?.mkdirs()
                p.models.writeBytes(want)
            }
        } catch (_: Exception) {}
    }

    /** 把内置 CA 证书同步到引擎环境：bootstrap 里没有证书，curl/apt https 全部握手失败 */
    fun syncCerts(ctx: Context) {
        val p = paths(ctx)
        try {
            val want = ctx.assets.open("cacert.pem").use { it.readBytes() }
            val f = File(p.prefix, "etc/ssl/certs/ca-certificates.crt")
            if (!f.exists() || f.readBytes().contentEquals(want).not()) {
                f.parentFile?.mkdirs()
                f.writeBytes(want)
                // Termux 风格：同时提供 openssl 使用的默认位置
                runCatching { java.nio.file.Files.createSymbolicLink(
                    File(p.prefix, "etc/ssl/cert.pem").toPath(),
                    f.toPath()
                ) }
            }
            // git 免密推送脚本：从环境变量 GITHUB_TOKEN 读取，避免 token 落盘
            val ask = File(p.bin, "git-askpass.sh")
            ask.writeText("#!/bin/sh\necho \"${'$'}GITHUB_TOKEN\"\n")
            runCatching { android.system.Os.chmod(ask.absolutePath, 0x1ED) }
        } catch (_: Exception) {}
    }

    /** 用当前 provider + key 重写 config.toml（切换服务商无需重下引擎） */
    fun applyConfig(ctx: Context) {
        val p = paths(ctx)
        if (!p.config.parentFile!!.exists()) return
        val key = apiKey(ctx)
        if (key.isBlank()) return
        writeConfig(ctx, p, key, provider(ctx))
    }

    /** 用已保存的 key 验证连接；DeepSeek 发真实 ping（chat/completions），返回状态码与错误原文 */
    fun testConnection(ctx: Context, key: String): String {
        if (provider(ctx) == PROVIDER_LOCAL) return "本地·千问3 模型已接入（占位）。推理后端上线后即可离线对话"
        if (provider(ctx) == PROVIDER_DEEPSEEK) return testDeepSeekPing(key)
        val url = when (provider(ctx)) {
            PROVIDER_GROQ -> "https://api.groq.com/openai/v1/models"
            PROVIDER_AGNES -> "https://apihub.agnes-ai.cn/v1/models"
            else -> "https://openrouter.ai/api/v1/models"
        }
        return try {
            val req = Request.Builder()
                .url(url)
                .header("User-Agent", "Synaps/2.0")
                .header("Authorization", "Bearer $key")
                .build()
            client.newCall(req).execute().use { resp ->
                val body = resp.body?.string().orEmpty()
                if (resp.code in 200..299) {
                    "连接成功（HTTP ${resp.code}）：${providerLabel(provider(ctx))} 可用"
                } else {
                    "连接失败（HTTP ${resp.code}）：${body.take(200)}"
                }
            }
        } catch (e: Exception) {
            "连接失败：${e.javaClass.simpleName} ${e.message ?: ""}"
        }
    }

    private fun testDeepSeekPing(key: String): String {
        return try {
            val body = JSONObject()
                .put("model", "deepseek-chat")
                .put("max_tokens", 8)
                .put("messages", JSONArray().put(JSONObject().put("role", "user").put("content", "ping")))
            val req = Request.Builder()
                .url("https://api.deepseek.com/chat/completions")
                .header("Authorization", "Bearer $key")
                .header("Content-Type", "application/json")
                .post(body.toString().toRequestBody("application/json; charset=utf-8".toMediaType()))
                .build()
            client.newCall(req).execute().use { resp ->
                val text = resp.body?.string().orEmpty()
                if (resp.code in 200..299) {
                    "连接成功（HTTP ${resp.code}）：DeepSeek 可用"
                } else {
                    "连接失败（HTTP ${resp.code}）：${text.take(300)}"
                }
            }
        } catch (e: Exception) {
            "连接失败：${e.javaClass.simpleName} ${e.message ?: ""}"
        }
    }

    /** 查询账户余额/额度（按服务商） */
    fun fetchBalance(ctx: Context, key: String): String {
        return when (provider(ctx)) {
            PROVIDER_DEEPSEEK -> fetchDeepSeekBalance(key)
            PROVIDER_OPENROUTER -> fetchOpenRouterCredit(key)
            else -> "该服务商不提供余额查询接口，请到官网控制台查看用量。"
        }
    }

    private fun fetchDeepSeekBalance(key: String): String {
        return try {
            val req = Request.Builder()
                .url("https://api.deepseek.com/user/balance")
                .header("User-Agent", "Synaps/2.0")
                .header("Authorization", "Bearer $key")
                .build()
            client.newCall(req).execute().use { resp ->
                val body = resp.body?.string().orEmpty()
                if (resp.code in 200..299) {
                    val j = JSONObject(body)
                    val infos = j.optJSONArray("balance_infos")
                    val sb = StringBuilder()
                    if (infos != null) {
                        for (i in 0 until infos.length()) {
                            val o = infos.getJSONObject(i)
                            sb.append(o.optString("currency")).append(" 余额：").append(o.optString("total_balance"))
                            sb.append("（赠送 ").append(o.optString("granted_balance"))
                                .append(" / 充值 ").append(o.optString("topped_up_balance")).append("）\n")
                        }
                    }
                    if (sb.isEmpty()) "查询成功，但账户暂无余额信息" else sb.toString().trim()
                } else {
                    "查询失败（HTTP ${resp.code}）：${body.take(120)}"
                }
            }
        } catch (e: Exception) {
            "查询失败：${e.javaClass.simpleName} ${e.message ?: ""}"
        }
    }

    private fun fetchOpenRouterCredit(key: String): String {
        return try {
            val req = Request.Builder()
                .url("https://openrouter.ai/api/v1/auth/key")
                .header("User-Agent", "Synaps/2.0")
                .header("Authorization", "Bearer $key")
                .build()
            client.newCall(req).execute().use { resp ->
                val body = resp.body?.string().orEmpty()
                if (resp.code in 200..299) {
                    val j = JSONObject(body).optJSONObject("data")
                    val label = j?.optString("label")?.takeIf { it.isNotBlank() } ?: "OpenRouter 账户"
                    val limit = j?.optDouble("limit") ?: 0.0
                    val usage = j?.optDouble("usage") ?: 0.0
                    val credits = (limit - usage).coerceAtLeast(0.0)
                    "账户：$label\n剩余额度：$${"%.4f".format(credits)} / 总额 $${"%.2f".format(limit)}"
                } else {
                    "查询失败（HTTP ${resp.code}）：${body.take(120)}"
                }
            }
        } catch (e: Exception) {
            "查询失败：${e.javaClass.simpleName} ${e.message ?: ""}"
        }
    }

    /** Token 用量统计（按字符数估算，仅本地参考） */
    private const val PREFS_USAGE = "usage"

    fun addUsage(ctx: Context, chars: Int) {
        if (chars <= 0) return
        val sp = ctx.getSharedPreferences(PREFS_USAGE, Context.MODE_PRIVATE)
        val today = java.text.SimpleDateFormat("yyyy-MM-dd", java.util.Locale.US).format(java.util.Date())
        val isToday = sp.getString("date", "") == today
        sp.edit()
            .putLong("total", sp.getLong("total", 0) + chars / 2)
            .putLong("today", (if (isToday) sp.getLong("today", 0) else 0) + chars / 2)
            .putString("date", today)
            .apply()
    }

    fun usageInfo(ctx: Context): String {
        val sp = ctx.getSharedPreferences(PREFS_USAGE, Context.MODE_PRIVATE)
        val total = sp.getLong("total", 0)
        val today = sp.getLong("today", 0)
        return "今日约 $today token，累计约 $total token（按字数估算，仅供参考）"
    }

    fun engineInfo(ctx: Context): String {
        val p = paths(ctx)
        if (!isInitialized(ctx)) return "引擎未安装"
        return "Codex $VERSION"
    }

    @Volatile private var dirCache = -1L
    @Volatile private var dirCacheAt = 0L

    /** 引擎目录大小；60 秒内重复调用直接返回缓存，避免反复遍历 1.6GB 目录造成磁盘 IO 风暴卡顿 */
    fun dirSize(ctx: Context): Long {
        val now = System.currentTimeMillis()
        if (dirCache >= 0 && now - dirCacheAt < 60000) return dirCache
        val root = File(ctx.filesDir, "codex")
        if (!root.exists()) return 0
        var total = 0L
        root.walkBottomUp().forEach { if (it.isFile) total += it.length() }
        dirCache = total
        dirCacheAt = now
        return total
    }

    /** 一键安装常用开发工具链（python/git/node/gcc/make 等，装到应用目录，无需 root） */
    /**
     * 安装静态编译工具链（绕开 apt：termux 二进制编译期写死 /data/data/com.termux 路径，apt 在此环境不可用）。
     * 包内含：真静态 BusyBox（自带 wget/sh/tar 等 300+ 命令）+ musl Python 3.12 + ld-musl loader。
     * 全部为可重定位文件，解压即用，不依赖任何硬编码管道。
     */
    fun installToolchain(ctx: Context, onStatus: (String) -> Unit, onDone: (Boolean, String) -> Unit) {
        Thread {
            val p = paths(ctx)
            val log = { m: String -> appendRunLog(ctx, "\n[工具链 ${System.currentTimeMillis()}] $m") }
            try {
                val tar = File(p.cache, "static-tools.tar.gz")
                log("开始安装，prefix=${p.prefix.absolutePath}")
                onStatus("正在下载静态工具链（约 26MB）…")
                p.cache.mkdirs()
                download(URL_TOOLS, tar) { cur, total ->
                    onStatus("下载静态工具链 ${cur / 1024 / 1024}/${total / 1024 / 1024} MB")
                }
                log("下载完成 size=${tar.length()}")
                onStatus("正在解压到引擎目录…")
                untar(tar, p.prefix)
                log("解压完成")
                // 软链到 bin，确保 PATH 里直接可用。
                // 注意：busybox 是包内真实文件，绝不能删除重建（自引用软链会把工具链弄坏），
                // 只对 wget/sh 建指向 busybox 的软链。
                val bin = p.bin
                fun link(name: String, target: String) {
                    val f = File(bin, name)
                    if (f.exists() && java.nio.file.Files.isSymbolicLink(f.toPath())) f.delete()
                    if (!f.exists()) {
                        java.nio.file.Files.createSymbolicLink(f.toPath(), java.nio.file.Paths.get(target))
                    }
                }
                link("wget", "busybox")
                link("sh", "busybox")
                link("python3", "../lib/python/bin/python3.12")
                link("python", "../lib/python/bin/python3.12")
                // 关键：python3.12 与 pip 必须可执行（untar 不保留执行位，直接 exec 会 EACCES）
                listOf(
                    File(bin, "busybox"),
                    File(p.prefix, "lib/ld-musl-aarch64.so.1"),
                    File(p.prefix, "lib/python/bin/python3.12"),
                    File(p.prefix, "lib/python/bin/pip"),
                    File(p.prefix, "lib/python/bin/pip3"),
                    File(p.prefix, "lib/python/bin/pip3.12")
                ).forEach { f ->
                    if (f.exists()) runCatching { android.system.Os.chmod(f.absolutePath, 0x1ED) }
                }
                // 验证可用性：用干净环境（去掉 termux-exec 的 LD_PRELOAD，避免 bionic 库注入 musl 进程）
                val bb = File(bin, "busybox")
                val v = runBin(ctx, bb, listOf("busybox", "--help"), 15, p, preload = false)
                val py = File(p.prefix, "lib/python/bin/python3.12")
                val pyOk = runBin(ctx, File(p.lib, "ld-musl-aarch64.so.1"),
                    listOf("--library-path", p.prefix.absolutePath + "/lib/python/lib", py.absolutePath, "--version"), 20, p, preload = false)
                val bbOk = v.output?.contains("BusyBox") == true
                val pythonOk = pyOk.output?.contains("Python") == true
                log("验证 busybox=${v.output?.take(60) ?: v.detail} python=${pyOk.output?.take(60) ?: pyOk.detail}")
                onDone(bbOk && pythonOk,
                    if (bbOk && pythonOk) "静态工具链就绪：BusyBox + Python 3.12"
                    else "部分验证失败：busybox=${v.output?.take(80) ?: v.detail} python=${pyOk.output?.take(80) ?: pyOk.detail}")
            } catch (e: Exception) {
                log("安装失败：${e.message ?: e.javaClass.simpleName}")
                onDone(false, "安装失败：${e.message ?: e.javaClass.simpleName}")
            }
        }.start()
    }

    /** 删除全部引擎数据（清除后需重新下载安装） */
    fun clearEngineData(ctx: Context) {
        try { File(ctx.filesDir, "codex").deleteRecursively() } catch (_: Exception) {}
    }

    /** 追加引擎日志（stdout + stderr）；超过 8MB 时先裁剪保留末尾 2MB，防止日志无限增长拖慢主线程 */
    fun appendRunLog(ctx: Context, text: String) {
        try {
            val f = File(ctx.filesDir, "codex/run.log")
            f.parentFile?.mkdirs()
            if (f.exists() && f.length() > 8L * 1024 * 1024) {
                val keep = 2L * 1024 * 1024
                val raf = java.io.RandomAccessFile(f, "rw")
                try {
                    raf.seek(f.length() - keep)
                    val tail = ByteArray(keep.toInt())
                    var off = 0
                    while (off < tail.size) {
                        val n = raf.read(tail, off, tail.size - off)
                        if (n < 0) break
                        off += n
                    }
                    raf.seek(0)
                    raf.write(tail, 0, off)
                    raf.setLength(off.toLong())
                } finally {
                    raf.close()
                }
            }
            f.appendText(text)
        } catch (_: Exception) {}
    }

    fun runLog(ctx: Context, maxLines: Int = 60): String {
        return try {
            val f = File(ctx.filesDir, "codex/run.log")
            if (!f.exists()) return "（暂无运行日志，请先发送一条消息）"
            val tailBytes = 256L * 1024
            val text = if (f.length() > tailBytes) {
                val raf = java.io.RandomAccessFile(f, "r")
                try {
                    raf.seek(f.length() - tailBytes)
                    val buf = ByteArray(tailBytes.toInt())
                    var off = 0
                    while (off < buf.size) {
                        val n = raf.read(buf, off, buf.size - off)
                        if (n < 0) break
                        off += n
                    }
                    String(buf, 0, off, Charsets.UTF_8).trimStart('\n', '\r')
                } finally {
                    raf.close()
                }
            } else f.readText()
            text.lines().takeLast(maxLines).joinToString("\n")
        } catch (e: Exception) {
            "读取日志失败：${e.message}"
        }
    }

    /** 诊断信息，用于初始化失败时排查 */
    fun diagnose(ctx: Context): String {
        val p = paths(ctx)
        val sb = StringBuilder()
        sb.append("App 版本：").append(BuildConfig.VERSION_NAME).append('\n')
        sb.append("引擎版本：Codex ").append(VERSION).append('\n')
        sb.append("engine 目录存在：").append(File(ctx.filesDir, "codex").exists()).append('\n')
        sb.append("codex 文件：").append(p.codexBin.exists())
        if (p.codexBin.exists()) sb.append("，可执行=").append(p.codexBin.canExecute())
        sb.append('\n')
        val bash = File(p.bin, "bash")
        sb.append("bash 文件：").append(bash.exists())
        if (bash.exists()) sb.append("，可执行=").append(bash.canExecute())
        sb.append('\n')
        sb.append("config：").append(p.config.exists()).append("，models：").append(p.models.exists())
        sb.append("，自检标记：").append(engineOk(ctx).exists()).append('\n')
        val lc = lastCheck
        sb.append("上次自检：").append(lc?.let {
            "退出码 ${it.exitCode}，${it.detail}，输出：${it.output?.take(120) ?: "（空）"}"
        } ?: "无").append('\n')
        sb.append("引擎占用：约 ").append(dirSize(ctx) / 1024 / 1024).append(" MB")
        return sb.toString()
    }

    fun setModel(ctx: Context, slug: String) {
        val p = paths(ctx)
        if (!p.config.exists()) return
        val c = p.config.readText().replace(Regex("(?m)^model = \".*\"$"), "model = \"$slug\"")
        p.config.writeText(c)
    }

    fun currentModel(ctx: Context): String {
        val p = paths(ctx)
        if (!p.config.exists()) return providerModel(provider(ctx))
        return Regex("(?m)^model = \"([^\"]*)\"").find(p.config.readText())?.groupValues?.get(1) ?: providerModel(provider(ctx))
    }

    /** 附件目录：发送时引擎可在 danger-full-access 下读取 */
    fun attachmentsDir(ctx: Context): File = File(paths(ctx).work, "attachments").apply { mkdirs() }

    private const val PREFS_ENGINE = "engine"

    fun engineTag(ctx: Context): String =
        ctx.getSharedPreferences(PREFS_ENGINE, Context.MODE_PRIVATE).getString("tag", "v2.0.1") ?: "v2.0.1"

    private fun saveEngineTag(ctx: Context, tag: String) {
        ctx.getSharedPreferences(PREFS_ENGINE, Context.MODE_PRIVATE).edit().putString("tag", tag).apply()
    }

    /** 检查引擎是否有新版本 */
    fun checkUpdate(ctx: Context): String {
        return try {
            val req = Request.Builder()
                .url("https://api.github.com/repos/abuaibobo-dev/aibox-updates/releases/latest")
                .header("User-Agent", "Synaps/2.0")
                .build()
            client.newCall(req).execute().use { resp ->
                if (resp.code !in 200..299) return@use "检查失败（HTTP ${resp.code}）"
                val j = JSONObject(resp.body?.string().orEmpty())
                val tag = j.optString("tag_name", "")
                val assets = j.optJSONArray("assets")
                var hasBin = false
                if (assets != null) for (i in 0 until assets.length()) {
                    if (assets.getJSONObject(i).optString("name") == "codex-bin.gz") hasBin = true
                }
                val cur = engineTag(ctx)
                when {
                    !hasBin -> "已是最新（$tag，无新引擎包）"
                    tag == cur -> "已是最新引擎（$tag）"
                    else -> "发现新引擎：$tag（当前 $cur），点击更新"
                }
            }
        } catch (e: Exception) {
            "检查失败：${e.message ?: e.javaClass.simpleName}"
        }
    }

    /** 后台下载并替换引擎 */
    fun updateEngine(ctx: Context, onStatus: (String) -> Unit, onDone: (Boolean, String) -> Unit) {
        Thread {
            try {
                val p = paths(ctx)
                val req = Request.Builder()
                    .url("https://api.github.com/repos/abuaibobo-dev/aibox-updates/releases/latest")
                    .header("User-Agent", "Synaps/2.0")
                    .build()
                var url = ""
                var tag = "latest"
                client.newCall(req).execute().use { resp ->
                    if (resp.code !in 200..299) throw IOException("检查失败（HTTP ${resp.code}）")
                    val j = JSONObject(resp.body?.string().orEmpty())
                    tag = j.optString("tag_name", "latest")
                    val assets = j.optJSONArray("assets")
                    if (assets != null) for (i in 0 until assets.length()) {
                        val a = assets.getJSONObject(i)
                        if (a.optString("name") == "codex-bin.gz") url = a.optString("browser_download_url")
                    }
                    if (url.isEmpty()) throw IOException("最新版本没有引擎更新包")
                }
                onStatus("正在下载新引擎…")
                val gz = File(p.cache, "codex.bin.gz")
                download(url, gz) { cur, total ->
                    onStatus("下载新引擎 ${cur / 1024 / 1024}/${total / 1024 / 1024} MB")
                }
                onStatus("正在安装…")
                val tmp = File(p.cache, "codex.bin.new")
                gunzip(gz, tmp)
                if (!isElf(tmp)) throw IOException("下载文件校验失败，已停止")
                val old = File(p.prefix, "bin/codex.old")
                try { if (p.codexBin.exists()) p.codexBin.renameTo(old) } catch (_: Exception) {}
                try { tmp.renameTo(p.codexBin) } catch (e: Exception) {
                    if (!tmp.renameTo(p.codexBin)) {
                        tmp.copyTo(p.codexBin, overwrite = true)
                    }
                }
                chmod(p.codexBin, 0x1C0)
                onStatus("正在验证新引擎…")
                val v = version(ctx)
                if (v == null) {
                    if (p.codexBin.exists()) p.codexBin.delete()
                    if (old.exists()) old.renameTo(p.codexBin)
                    throw IOException("新引擎验证失败，已回滚到旧版")
                }
                try { old.delete() } catch (_: Exception) {}
                saveEngineTag(ctx, tag)
                onDone(true, "引擎已更新：$v")
            } catch (e: Exception) {
                onDone(false, e.message ?: "更新失败")
            }
        }.start()
    }

    fun version(ctx: Context): String? {
        val p = paths(ctx)
        val r = runBin(ctx, p.codexBin, listOf("--version"), 45, p)
        lastCheck = r
        return r.output
    }

    /** 带超时与错误捕获的执行；output 为空即执行失败。preload=false 时不注入 termux-exec 的 LD_PRELOAD */
    private fun runBin(ctx: Context, bin: File, args: List<String>, timeoutSec: Long, p: Paths, preload: Boolean = true): RunResult {
        return try {
            val pb = ProcessBuilder(listOf(bin.absolutePath) + args)
            val e = env(ctx, p).toMutableMap()
            if (!preload) e.remove("LD_PRELOAD")
            pb.environment().putAll(e)
            pb.redirectErrorStream(true)
            val proc = pb.start()
            val out = AtomicReference<String>()
            val reader = Thread { out.set(proc.inputStream.bufferedReader().readText()) }
            reader.start()
            if (!proc.waitFor(timeoutSec, TimeUnit.SECONDS)) {
                proc.destroyForcibly()
                RunResult(null, -1, "执行超时（${timeoutSec}s）")
            } else {
                reader.join(2000)
                RunResult(out.get()?.trim()?.takeIf { it.isNotBlank() }, proc.exitValue(), "退出码 ${proc.exitValue()}")
            }
        } catch (e: Exception) {
            RunResult(null, -1, "${e.javaClass.simpleName}: ${e.message ?: ""}")
        }
    }

    /** 检查文件是否为有效的 ELF 可执行文件（防止下载/解压损坏） */
    private fun isElf(f: File): Boolean {
        if (f.length() < 4) return false
        return try {
            val head = ByteArray(4)
            FileInputStream(f).use { ins ->
                var off = 0
                while (off < 4) {
                    val n = ins.read(head, off, 4 - off)
                    if (n < 0) break
                    off += n
                }
            }
            head[0] == 0x7F.toByte() && head[1] == 'E'.code.toByte() &&
                head[2] == 'L'.code.toByte() && head[3] == 'F'.code.toByte()
        } catch (_: Exception) { false }
    }

    /** 后台线程执行初始化 */
    fun init(ctx: Context, key: String, onStatus: (String, Int?) -> Unit, onDone: (Boolean, String) -> Unit) {
        Thread {
            try {
                val p = paths(ctx)
                // 每次初始化都从干净状态开始（保留 cache 里的下载缓存，避免重复下载）
                p.prefix.deleteRecursively()
                p.tmp.deleteRecursively()
                listOf(p.prefix, p.home, p.codexHome, p.work, p.cache, p.tmp).forEach { it.mkdirs() }

                // 预检存储空间：引擎 + 缓存约需 1GB 出头，空间不足时先提示而不是下载一半失败
                val st = android.os.StatFs(ctx.filesDir.absolutePath)
                val freeMB = st.availableBytes / 1024 / 1024
                if (freeMB < 1200) {
                    throw IOException("存储空间不足（剩余约 $freeMB MB，初始化需约 1.2GB），请先清理手机空间")
                }

                onStatus("正在下载运行环境（约 30MB）…", null)
                val bootZip = File(p.cache, "bootstrap.zip")
                download(URL_BOOTSTRAP, bootZip) { cur, total ->
                    onStatus("下载运行环境 ${cur / 1024 / 1024}/${total / 1024 / 1024} MB", if (total > 0) (cur * 100 / total).toInt() else null)
                }
                onStatus("正在解压运行环境…", null)
                unzip(bootZip, p.prefix)
                applySymlinks(p.prefix)
                relocatePrefix(p.prefix)
                fixPermissions(p.prefix)
                // 写入 CA 证书：curl/apt 的 https 才能握手成功
                syncCerts(ctx)
                // apt 直接使用真实 prefix，无需 -o Dir= 逐个覆盖
                writeAptPrefixConfig(p)

                // 先用一个小二进制验证本机能否执行引擎目录里的程序，避免白下载几百 MB
                onStatus("正在验证运行环境…", null)
                val bash = File(p.bin, "bash")
                if (bash.exists()) {
                    val br = runBin(ctx, bash, listOf("--version"), 20, p)
                    if (br.output == null) {
                        throw IOException("运行环境无法执行（${br.detail}）。若反复出现，可能是手机限制应用目录执行二进制，可先“清除数据并重试”")
                    }
                }

                onStatus("正在下载 Codex 引擎（约 41MB）…", null)
                val gz = File(p.cache, "codex.bin.gz")
                download(URL_CODEX, gz) { cur, total ->
                    onStatus("下载 Codex 引擎 ${cur / 1024 / 1024}/${total / 1024 / 1024} MB", if (total > 0) (cur * 100 / total).toInt() else null)
                }
                onStatus("正在安装 Codex 引擎…", null)
                gunzip(gz, p.codexBin)
                chmod(p.codexBin, 0x1C0) // rwx------
                if (!isElf(p.codexBin)) {
                    gz.delete() // 缓存损坏，下次重新下载
                    throw IOException("引擎文件校验失败（不是有效的 ELF 可执行文件），已清除缓存，请重试")
                }

                onStatus("正在写入模型配置…", null)
                writeConfig(ctx, p, key, provider(ctx))
                ctx.assets.open("models.json").use { input ->
                    FileOutputStream(p.models).use { out -> input.copyTo(out) }
                }

                onStatus("正在验证引擎…", null)
                val v = version(ctx)
                if (v == null) {
                    val lc = lastCheck
                    val why = lc?.let {
                        it.detail + if (it.output.isNullOrEmpty()) "" else "｜输出：${it.output.take(200)}"
                    } ?: "未知错误"
                    throw IOException("引擎自检失败：$why")
                }
                try { engineOk(ctx).writeText(v) } catch (_: Exception) {}
                saveEngineTag(ctx, "v2.0.1")
                onDone(true, "引擎就绪：$v")
            } catch (e: Exception) {
                onDone(false, e.message ?: "初始化失败")
            }
        }.start()
    }

    /** 启动一轮对话；threadId 为空时开启新线程，否则续接 */
    fun send(ctx: Context, threadId: String?, prompt: String, onEvent: (EngineEvent) -> Unit): Process? {
        val p = paths(ctx)
        if (!isInitialized(ctx)) return null
        if (provider(ctx) == PROVIDER_DEEPSEEK) {
            // 确保本地转接头已监听，否则 base_url 配置可能回退到直连 /responses
            val ap = DeepSeekAdapter.start(ctx)
            if (ap <= 0) return null
            try {
                val cfgBase = runCatching {
                    Regex("(?m)^base_url = \"([^\"]*)\"").find(paths(ctx).config.readText())?.groupValues?.get(1) ?: "?"
                }.getOrDefault("?")
                appendRunLog(ctx, "\n[App] provider=${provider(ctx)} base_url=$cfgBase adapterPort=$ap keyLen=${apiKey(ctx).length}\n")
            } catch (_: Exception) {}
        }
        // 执行规则包裹：无论走哪个模型，任务型请求都要"真做"，不许只用文字描述动作
        val finalPrompt = "（执行规则：需要实际操作/写文件/查资料/下载/编译的任务，请立即调用工具执行，禁止只用文字描述你将做什么或假装已经做了；执行期间不输出旁白文字。完成后再给简短结论。）\n\n$prompt"
        val args = mutableListOf(p.codexBin.absolutePath)
        // per-vendor 白名单：只有实测确认不兼容 custom 工具的供应商才关 apply_patch，
        // 其余（含未来新增）默认开启完整工具能力。
        val noApplyPatch = if (provider(ctx) in APPLY_PATCH_DISABLED) listOf("-c", "features.apply_patch_tool=false") else emptyList()
        // 最大权限：danger-full-access 沙盒 + 跳过一切审批（App 外层的 Android 沙盒仍生效）
        val bypass = listOf("--dangerously-bypass-approvals-and-sandbox")
        if (threadId.isNullOrEmpty()) {
            args += listOf("exec", "--json", "-s", "danger-full-access", "-C", p.work.absolutePath, "--skip-git-repo-check") + bypass + noApplyPatch + listOf(finalPrompt)
        } else {
            // resume 续接：不传 -s/-C（部分引擎版本 resume 子命令不解析，会话本身记录了工作目录与沙箱）
            args += listOf("exec", "--json", "--skip-git-repo-check") + bypass + noApplyPatch + listOf("resume", threadId, finalPrompt)
        }
        val pb = ProcessBuilder(args)
        pb.environment().putAll(env(ctx, p))
        val proc = try {
            pb.start()
        } catch (e: Exception) {
            // 引擎实际无法启动：清除就绪标记，让下次进入引导重新初始化
            engineOk(ctx).delete()
            return null
        }
        // 关键：立即关闭 stdin。codex 会一直等待 stdin 数据，
        // 不关闭会导致进程卡在 "Reading additional input from stdin..." 永不开始。
        try { proc.outputStream.close() } catch (_: Exception) {}

        try {
            appendRunLog(ctx, "\n===== ${java.text.SimpleDateFormat("yyyy-MM-dd HH:mm:ss", java.util.Locale.US).format(java.util.Date())} | ${provider(ctx)} | $prompt =====".take(160) + "\n")
        } catch (_: Exception) {}

        Thread {
            val reader = proc.inputStream.bufferedReader()
            var line: String?
            while (true) {
                line = reader.readLine() ?: break
                appendRunLog(ctx, line + "\n")
                parse(line)?.let { onEvent(it) }
            }
            reader.close()
            proc.waitFor()
            appendRunLog(ctx, "===== exit=${proc.exitValue()} =====\n")
            onEvent(EngineEvent("exit", "exit=${proc.exitValue()}"))
        }.start()

        Thread {
            try {
                val err = proc.errorStream
                val buf = ByteArray(1024)
                while (true) {
                    val n = err.read(buf)
                    if (n < 0) break
                    appendRunLog(ctx, String(buf, 0, n))
                }
            } catch (_: Exception) {}
        }.start()
        return proc
    }

    fun stop(proc: Process?) {
        try { proc?.destroy(); proc?.waitFor(2, TimeUnit.SECONDS); proc?.destroyForcibly() } catch (_: Exception) {}
    }

    // ---------------- 内部实现 ----------------

    internal fun env(ctx: Context, p: Paths, preload: Boolean = true): Map<String, String> {
        val suPaths = listOf("/system/bin", "/system/xbin", "/sbin", "/su/bin", "/vendor/bin", "/system/sbin")
        val base = System.getenv("PATH") ?: suPaths.joinToString(":")
        val ca = File(p.prefix, "etc/ssl/certs/ca-certificates.crt").absolutePath
        val gh = ghToken(ctx)
        val deployRepo = "abuaibobo-dev/aibox-updates"
        // 放开所有引擎目录写权限：prefix/home/work/cache/tmp 统一可写，/tmp 在 Android 不可写，用引擎 tmp
        try {
            listOf(p.prefix, p.home, p.codexHome, p.work, p.cache, p.tmp).forEach { d ->
                d.mkdirs()
                runCatching { android.system.Os.chmod(d.absolutePath, 0x1FF) }
            }
        } catch (_: Exception) {}
        // Android 无 /etc/resolv.conf，musl 工具 DNS 会失败；写入可写副本供工具链使用
        try {
            val rc = File(p.prefix, "etc/resolv.conf")
            rc.parentFile?.mkdirs()
            rc.writeText("nameserver 223.5.5.5\nnameserver 119.29.29.29\nnameserver 8.8.8.8\n")
            runCatching { android.system.Os.chmod(rc.absolutePath, 0x1A4) }
        } catch (_: Exception) {}
        // termux-exec：通过 LD_PRELOAD + TERMUX__PREFIX 把 /data/data/com.termux/... 重定向到实际 prefix
        val termuxExec = File(p.lib, "libtermux-exec-ld-preload.so").takeIf { it.exists() }
            ?: File(p.lib, "libtermux-exec-direct-ld-preload.so").takeIf { it.exists() }
        return buildMap {
            put("HOME", p.home.absolutePath)
            put("CODEX_HOME", p.codexHome.absolutePath)
            put("PREFIX", p.prefix.absolutePath)
            put("TERMUX__PREFIX", p.prefix.absolutePath)
            put("PATH", "${p.bin.absolutePath}:$base")
            put("LD_LIBRARY_PATH", p.lib.absolutePath)
            put("TMPDIR", p.tmp.absolutePath)
            if (preload) termuxExec?.let { put("LD_PRELOAD", it.absolutePath) }
            put("TERM", "xterm-256color")
            put("NO_COLOR", "1")
            put("SSL_CERT_FILE", ca)
            put("SSL_CERT_DIR", File(p.prefix, "etc/ssl/certs").absolutePath)
            put("CURL_CA_BUNDLE", ca)
            put("REQUESTS_CA_BUNDLE", ca)
            put("GIT_SSL_CAINFO", ca)
            put("GITHUB_TOKEN", gh)
            put("GH_TOKEN", gh)
            put("DEPLOY_REPO", deployRepo)
            // git 推送免密：用 token 做 basic auth
            put("GIT_ASKPASS", p.bin.absolutePath + "/git-askpass.sh")
            // 本地 HTTP/HTTPS 代理：DNS 由 App 进程解析（系统 netd），绕开沙盒 musl 工具 DNS 限制
            val proxyUrl = "http://127.0.0.1:${LocalProxy.start()}"
            put("http_proxy", proxyUrl)
            put("https_proxy", proxyUrl)
            put("HTTP_PROXY", proxyUrl)
            put("HTTPS_PROXY", proxyUrl)
            put("ALL_PROXY", proxyUrl)
            put("no_proxy", "127.0.0.1,localhost,::1")
            put("NO_PROXY", "127.0.0.1,localhost,::1")
            // pip 走国内镜像提速（经本地代理转发，DNS 由 App 解析）
            put("PIP_INDEX_URL", "https://pypi.tuna.tsinghua.edu.cn/simple")
            put("PIP_TRUSTED_HOST", "pypi.tuna.tsinghua.edu.cn")
            put("PIP_DISABLE_PIP_VERSION_CHECK", "1")
        }
    }

    private fun parse(line: String): EngineEvent? {
        return try {
            val j = JSONObject(line)
            // 安全取值：JSON null / 缺失 → 空串，避免 org.json optString 把 null 渲染成 "null" 文本
            fun txt(o: JSONObject, k: String): String {
                val v = o.opt(k)
                return if (v == null || v == JSONObject.NULL) "" else o.optString(k).trim()
            }
            when (j.optString("type")) {
                "thread.started" -> EngineEvent("thread", threadId = j.optString("thread_id").ifEmpty { null })
                "item.completed" -> {
                    val item = j.optJSONObject("item") ?: return null
                    when (item.optString("type")) {
                        "agent_message" -> EngineEvent("text", txt(item, "text"))
                        "agent_reasoning" -> EngineEvent("reasoning", txt(item, "text"))
                        "command_execution" -> EngineEvent("tool", item.optString("command").take(140))
                        else -> null
                    }
                }
                "turn.completed" -> EngineEvent("turn_done")
                "error" -> EngineEvent("error", txt(j, "message"))
                "turn.failed" -> EngineEvent("error", j.optJSONObject("error")?.optString("message") ?: "回合失败")
                else -> null
            }
        } catch (_: Exception) { null }
    }

    /** 带断点续传与自动重试的下载；已下载完整时直接跳过 */
    private fun download(url: String, dest: File, onProgress: (Long, Long) -> Unit) {
        var last: Exception? = null
        repeat(4) { attempt ->
            try {
                downloadOnce(url, dest, onProgress)
                return
            } catch (e: Exception) {
                last = e
                if (attempt < 3) Thread.sleep(1500L * (attempt + 1))
            }
        }
        throw last ?: IOException("下载失败")
    }

    private fun downloadOnce(url: String, dest: File, onProgress: (Long, Long) -> Unit) {
        val existing = if (dest.exists()) dest.length() else 0L
        val req = if (existing > 0)
            Request.Builder().url(url).header("User-Agent", "Synaps/2.0").header("Range", "bytes=$existing-").build()
        else
            Request.Builder().url(url).header("User-Agent", "Synaps/2.0").build()
        client.newCall(req).execute().use { resp ->
            if (resp.code == 416) return // 本地文件已完整
            if (resp.code !in 200..299 && resp.code != 206) throw IOException("HTTP ${resp.code} from $url")
            val resumed = resp.code == 206
            val body = resp.body ?: throw IOException("无响应内容")
            val bodyLen = body.contentLength()
            val total = if (bodyLen > 0) existing + bodyLen else 0L
            FileOutputStream(dest, resumed).use { out ->
                val buf = ByteArray(256 * 1024)
                var count = if (resumed) existing else 0L
                var lastPct = -1
                body.byteStream().use { input ->
                    while (true) {
                        val n = input.read(buf)
                        if (n < 0) break
                        out.write(buf, 0, n)
                        count += n
                        val pct = if (total > 0) (count * 100 / total).toInt() else -1
                        if (pct != lastPct) {
                            lastPct = pct
                            onProgress(count, total)
                        }
                    }
                }
                out.flush()
                onProgress(count, total)
            }
            // 校验下载完整性：大小不符说明服务端或续传异常，清掉缓存下次重下
            if (total > 0 && dest.length() != total) {
                dest.delete()
                throw IOException("下载不完整（期望 $total，实际 ${dest.length()}），已清除缓存重试")
            }
        }
    }


    private fun copyWithProgress(input: InputStream, dest: File, total: Long, onProgress: (Long, Long) -> Unit) {
        FileOutputStream(dest).use { out ->
            val buf = ByteArray(128 * 1024)
            var count = 0L
            while (true) {
                val n = input.read(buf)
                if (n < 0) break
                out.write(buf, 0, n)
                count += n
                onProgress(count, total)
            }
        }
    }

    private fun unzip(zip: File, destDir: File) {
        ZipInputStream(FileInputStream(zip)).use { zis ->
            val buf = ByteArray(128 * 1024)
            var entry = zis.nextEntry
            while (entry != null) {
                val outFile = File(destDir, entry.name)
                if (entry.isDirectory) {
                    outFile.mkdirs()
                } else {
                    outFile.parentFile?.mkdirs()
                    FileOutputStream(outFile).use { out ->
                        while (true) { val n = zis.read(buf); if (n < 0) break; out.write(buf, 0, n) }
                    }
                    // Termux 同款权限处理：二进制 0700，其余 0644
                    if (isBinaryPath(entry.name)) chmod(outFile, 0x1C0) else chmod(outFile, 0x1A4)
                }
                zis.closeEntry()
                entry = zis.nextEntry
            }
        }
    }

    private fun isBinaryPath(name: String): Boolean =
        name.startsWith("bin/") || name.startsWith("libexec/") ||
            name == "lib/apt/apt-helper" || name.startsWith("lib/apt/methods/")

    /** 统一修正 prefix 内所有权限：目录 0755，二进制 0700 */
    private fun fixPermissions(prefix: File) {
        prefix.walkBottomUp().forEach { f ->
            try {
                if (f.isDirectory) chmod(f, 0x1ED)
                else if (isBinaryPath(f.relativeTo(prefix).path)) chmod(f, 0x1C0)
            } catch (_: Exception) {}
        }
    }

    private fun chmod(f: File, mode: Int) {
        android.system.Os.chmod(f.absolutePath, mode)
    }

    private fun applySymlinks(prefix: File) {
        val f = File(prefix, "SYMLINKS.txt")
        if (!f.exists()) return
        for (line in f.readLines()) {
            val i = line.indexOf('←')
            if (i <= 0) continue
            val target = line.substring(0, i).trim()
            var link = line.substring(i + 1).trim()
            if (link.isEmpty()) continue
            if (link.startsWith("./")) link = link.substring(2)
            val linkFile = File(prefix, link)
            if (linkFile.exists()) continue
            val parent = linkFile.parentFile ?: continue
            try {
                parent.mkdirs()
                val t = if (target.startsWith("/data/data/com.termux"))
                    prefix.absolutePath + target.removePrefix(PREFIX_MARK)
                else
                    File(linkFile.parentFile, target).absolutePath
                java.nio.file.Files.createSymbolicLink(linkFile.toPath(), java.nio.file.Paths.get(t))
            } catch (_: Exception) {
                try {
                    Runtime.getRuntime().exec(arrayOf("ln", "-s", target, linkFile.absolutePath)).waitFor()
                } catch (_: Exception) {}
            }
        }
    }


    /**
     * Termux bootstrap 内部把安装路径硬编码为 /data/data/com.termux/files/usr，
     * 而 App 实际解压到应用私有目录（/data/user/0/com.aibox.app/files/codex/prefix）。
     * 这里把文本文件（脚本 shebang、apt/dpkg 配置等）里的旧路径全部重写为实际 prefix，
     * 否则引擎内 apt、curl 等会去找不存在的旧目录。ELF 二进制不处理：
     * termux 程序运行时靠 $PREFIX 环境变量定位自身（env() 已设置）。
     */
    private fun relocatePrefix(prefix: File) {
        val oldMark = PREFIX_MARK
        val target = prefix.absolutePath
        if (oldMark == target) return
        prefix.walkTopDown().forEach { f ->
            if (!f.isFile) return@forEach
            if (f.length() <= 0 || f.length() > 8 * 1024 * 1024) return@forEach
            try {
                if (isElf(f)) return@forEach
                // 只处理文本：含 NUL 字节视为二进制（.gz/.so 等），跳过避免损坏
                val bytes = f.readBytes()
                if (bytes.any { it.toInt() == 0 }) return@forEach
                val text = String(bytes, Charsets.UTF_8)
                if (text.contains(oldMark)) {
                    f.writeText(text.replace(oldMark, target), Charsets.UTF_8)
                }
            } catch (_: Exception) {}
        }
    }


    /** 写 apt 配置：Dir 指向实际 prefix，apt/dpkg 不再依赖 /data/data/com.termux 旧路径 */
    private fun writeAptPrefixConfig(p: Paths) {
        try {
            val dir = File(p.prefix, "etc/apt/apt.conf.d")
            dir.mkdirs()
            val f = File(dir, "00-app-prefix.conf")
            f.writeText("Dir \"" + p.prefix.absolutePath + "\";\n")
            android.system.Os.chmod(dir.absolutePath, 0x1ED)
            android.system.Os.chmod(f.absolutePath, 0x1A4)
        } catch (_: Exception) {}
    }

    /** Android 11+ 是否已授予"所有文件访问" */
    fun hasAllFilesAccess(ctx: Context): Boolean =
        Build.VERSION.SDK_INT < Build.VERSION_CODES.R || Environment.isExternalStorageManager()

    fun sharedFolderUri(ctx: Context): String? =
        ctx.getSharedPreferences("ui", Context.MODE_PRIVATE).getString("shared_tree", null)

    /** 把 SAF 目录授权映射为真实路径（仅支持 primary: 内置存储） */
    fun sharedFolderPath(ctx: Context): String? {
        val uriStr = sharedFolderUri(ctx) ?: return null
        return runCatching {
            val uri = Uri.parse(uriStr)
            val id = DocumentsContract.getTreeDocumentId(uri)
            if (id.startsWith("primary:")) {
                val rel = id.removePrefix("primary:")
                File(Environment.getExternalStorageDirectory(), rel).absolutePath
            } else null
        }.getOrNull()
    }

    /** 把用户授权的文件夹软链到引擎工作目录 work/shared，引擎 shell 即可读写 */
    fun applySharedFolder(ctx: Context) {
        val p = paths(ctx)
        val link = File(p.work, "shared")
        runCatching { if (link.exists()) link.delete() }
        val target = sharedFolderPath(ctx) ?: return
        runCatching {
            java.nio.file.Files.createSymbolicLink(link.toPath(), java.nio.file.Paths.get(target))
        }.onFailure {
            runCatching { Runtime.getRuntime().exec(arrayOf("ln", "-s", target, link.absolutePath)).waitFor() }
        }
    }

    private fun gunzip(gz: File, dest: File) {
        dest.parentFile?.mkdirs()
        GZIPInputStream(FileInputStream(gz)).use { input ->
            FileOutputStream(dest).use { out -> input.copyTo(out) }
        }
    }

    /** 解压 tar.gz 到 destDir（保留软链）；包内结构为 prefix/...，与引擎 prefix 合并 */
    private fun untar(tgz: File, destDir: File) {
        java.util.zip.GZIPInputStream(FileInputStream(tgz)).use { gz ->
            org.apache.commons.compress.archivers.tar.TarArchiveInputStream(gz).use { tar ->
                val buf = ByteArray(128 * 1024)
                var entry: org.apache.commons.compress.archivers.tar.TarArchiveEntry? =
                    tar.nextEntry as? org.apache.commons.compress.archivers.tar.TarArchiveEntry
                while (entry != null) {
                    val name = entry.name
                    // 剥掉包内顶层 prefix/ 目录，合并进 destDir
                    val rel = if (name.startsWith("prefix/")) name.removePrefix("prefix/") else name
                    val outFile = File(destDir, rel)
                    if (entry.isDirectory) {
                        outFile.mkdirs()
                    } else if (entry.isSymbolicLink) {
                        outFile.parentFile?.mkdirs()
                        try {
                            outFile.delete()
                            java.nio.file.Files.createSymbolicLink(
                                outFile.toPath(),
                                java.nio.file.Paths.get(entry.linkName)
                            )
                        } catch (_: Exception) {}
                    } else {
                        outFile.parentFile?.mkdirs()
                        FileOutputStream(outFile).use { out ->
                            while (true) { val n = tar.read(buf); if (n < 0) break; out.write(buf, 0, n) }
                        }
                        // 保留 tar 里的执行位（Android 解压不会自动带 +x，直接 exec 会 EACCES）
                        val mode = entry.mode and 0x1FF
                        if (mode != 0) {
                            runCatching { android.system.Os.chmod(outFile.absolutePath, mode) }
                        }
                    }
                    entry = tar.nextEntry as? org.apache.commons.compress.archivers.tar.TarArchiveEntry
                }
            }
        }
    }

    private fun writeConfig(ctx: Context, p: Paths, key: String, provider: String) {
        p.config.parentFile?.mkdirs()
        val model = providerModel(provider)
        val baseUrl = if (provider == PROVIDER_DEEPSEEK) {
            // DeepSeek 专用：经本地转接头翻译为 /chat/completions（codex 只支持 wire_api=responses）
            val ap = DeepSeekAdapter.start(ctx)
            if (ap > 0) "http://127.0.0.1:$ap" else providerBaseUrl(provider)
        } else providerBaseUrl(provider)
        val modelId = if (provider == PROVIDER_DEEPSEEK) "deepseek-chat" else null
        val sb = StringBuilder()
        sb.append("model = \"").append(model).append("\"\n")
        sb.append("model_provider = \"").append(provider).append("\"\n")
        sb.append("preferred_auth_method = \"apikey\"\n")
        sb.append("forced_login_method = \"api\"\n")
        sb.append("model_reasoning_effort = \"low\"\n")
        sb.append("model_catalog_json = \"").append(p.models.absolutePath).append("\"\n\n")
        sb.append("[model_providers.").append(provider).append("]\n")
        sb.append("name = \"").append(provider).append("\"\n")
        sb.append("base_url = \"").append(baseUrl).append("\"\n")
        sb.append("wire_api = \"responses\"\n")
        if (modelId != null) sb.append("model_id = \"").append(modelId).append("\"\n")
        sb.append("experimental_bearer_token = \"").append(key).append("\"\n")
        p.config.writeText(sb.toString())
    }
}
