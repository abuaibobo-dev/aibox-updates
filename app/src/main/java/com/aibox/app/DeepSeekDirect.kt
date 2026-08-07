package com.aibox.app

import android.content.Context
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.util.Timer
import java.util.TimerTask
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/**
 * 直连 DeepSeek 标准 /chat/completions（绕开 codex 引擎与本地转接头）。
 *
 * - 流式；15 秒内未收到首个数据块时自动降级为非流式再试一次。
 * - 支持工具调用循环：exec_command / read_file / write_file，
 *   DeepSeek 返回 tool_calls -> App 执行 -> 回填 -> 继续，直到输出文本。
 */
object DeepSeekDirect {

    private const val API = "https://api.deepseek.com/chat/completions"
    private const val MODEL = "deepseek-chat"
    private const val MAX_ROUNDS = 60
    private const val TOOL_TIMEOUT_SEC = 300L
    private const val OUT_LIMIT = 16000
    private val JSON = "application/json; charset=utf-8".toMediaType()
    private val client = OkHttpClient.Builder()
        .connectTimeout(20, TimeUnit.SECONDS)
        .readTimeout(240, TimeUnit.SECONDS)
        .writeTimeout(60, TimeUnit.SECONDS)
        .build()

    data class ToolCall(val id: String, val name: String, val arguments: String)
    data class TurnResult(val text: String, val toolCalls: List<ToolCall>, val usageTokens: Long)

    private fun tools(): JSONArray = JSONArray()
        .put(JSONObject()
            .put("type", "function")
            .put("function", JSONObject()
                .put("name", "web_search")
                .put("description", "联网搜索互联网，返回相关网页的标题、链接和摘要。适合查询最新资讯、事实、文档等。")
                .put("parameters", JSONObject()
                    .put("type", "object")
                    .put("properties", JSONObject()
                        .put("query", JSONObject().put("type", "string").put("description", "搜索关键词，尽量简洁明确"))
                    )
                    .put("required", JSONArray().put("query"))
                )))
        .put(JSONObject()
            .put("type", "function")
            .put("function", JSONObject()
                .put("name", "exec_command")
                .put("description", "在手机环境中执行一条 bash 命令并返回输出。注意：Python/wget 等工具 DNS 可能受限，需要联网获取内容或下载文件时优先使用 http_get / download_file 工具。")
                .put("parameters", JSONObject()
                    .put("type", "object")
                    .put("properties", JSONObject()
                        .put("cmd", JSONObject().put("type", "string").put("description", "要执行的命令，例如 ls -la"))
                    )
                    .put("required", JSONArray().put("cmd"))
                )))
        .put(JSONObject()
            .put("type", "function")
            .put("function", JSONObject()
                .put("name", "read_file")
                .put("description", "读取文本文件内容。支持绝对路径（如 /sdcard/...）或相对工作目录的路径。")
                .put("parameters", JSONObject()
                    .put("type", "object")
                    .put("properties", JSONObject()
                        .put("path", JSONObject().put("type", "string").put("description", "文件路径，绝对路径（如 /sdcard/notes/a.txt）或相对工作目录路径"))
                    )
                    .put("required", JSONArray().put("path"))
                )))
        .put(JSONObject()
            .put("type", "function")
            .put("function", JSONObject()
                .put("name", "write_file")
                .put("description", "写入/覆盖一个文本文件。支持绝对路径（如 /sdcard/...）或相对工作目录的路径。")
                .put("parameters", JSONObject()
                    .put("type", "object")
                    .put("properties", JSONObject()
                        .put("path", JSONObject().put("type", "string").put("description", "文件路径，绝对路径（如 /sdcard/notes/a.txt）或相对工作目录路径"))
                        .put("content", JSONObject().put("type", "string").put("description", "文件完整内容"))
                    )
                    .put("required", JSONArray().put("path").put("content"))
                )))
        .put(JSONObject()
            .put("type", "function")
            .put("function", JSONObject()
                .put("name", "download_file")
                .put("description", "由 App 直接联网下载文件到本地路径（绕开沙盒 DNS 限制）。可下载 pip wheel、静态编译工具、源码包、模型文件等任意 URL。")
                .put("parameters", JSONObject()
                    .put("type", "object")
                    .put("properties", JSONObject()
                        .put("url", JSONObject().put("type", "string").put("description", "完整下载地址，如 https://pypi.org/packages/.../xxx.whl"))
                        .put("path", JSONObject().put("type", "string").put("description", "保存路径，绝对路径（如 /sdcard/...）或相对工作目录路径"))
                    )
                    .put("required", JSONArray().put("url").put("path"))
                )))
        .put(JSONObject()
            .put("type", "function")
            .put("function", JSONObject()
                .put("name", "http_get")
                .put("description", "由 App 直接请求一个 URL 并返回响应文本（绕开沙盒 DNS 限制）。适合获取 API 数据、HTML 页面、JSON、pip 索引页面等。")
                .put("parameters", JSONObject()
                    .put("type", "object")
                    .put("properties", JSONObject()
                        .put("url", JSONObject().put("type", "string").put("description", "完整 URL，如 https://pypi.org/pypi/requests/json"))
                    )
                    .put("required", JSONArray().put("url"))
                )))
        .put(JSONObject()
            .put("type", "function")
            .put("function", JSONObject()
                .put("name", "set_plan")
                .put("description", "任务需要多个步骤完成时，在开始执行前调用一次，声明计划步骤。单步任务不要调用。调用后不要再在文字里重复计划内容。")
                .put("parameters", JSONObject()
                    .put("type", "object")
                    .put("properties", JSONObject()
                        .put("steps", JSONObject().put("type", "array").put("items", JSONObject().put("type", "string")).put("description", "2~5 个步骤，每步一句关键动作（不超过15字）"))
                    )
                    .put("required", JSONArray().put("steps"))
                )))
        .put(JSONObject()
            .put("type", "function")
            .put("function", JSONObject()
                .put("name", "plan_step")
                .put("description", "每完成计划中的一步后调用，标记该步骤完成。")
                .put("parameters", JSONObject()
                    .put("type", "object")
                    .put("properties", JSONObject()
                        .put("step", JSONObject().put("type", "integer").put("description", "已完成的步骤编号，从 1 开始"))
                    )
                    .put("required", JSONArray().put("step"))
                )))
        .put(JSONObject()
            .put("type", "function")
            .put("function", JSONObject()
                .put("name", "plan_done")
                .put("description", "全部步骤完成后调用一次，宣告任务结束。")
                .put("parameters", JSONObject().put("type", "object").put("properties", JSONObject()).put("required", JSONArray()))
                ))

    /** 带工具循环的对话入口 */
    fun chatWithTools(ctx: Context, history: List<Pair<String, String>>, prompt: String, key: String,
                      onDelta: (String) -> Unit, onToolStart: (String, String) -> Unit = { _, _ -> },
                      onTool: (String, String) -> Unit,
                      onUsage: (Long) -> Unit = {},
                      onPlanStart: (String) -> Unit = {}, onPlanStep: (Int) -> Unit = {}, onPlanDone: () -> Unit = {},
                      onDone: (String) -> Unit, onError: (String) -> Unit) {
        Thread {
            val msgs = buildMessages(history, prompt)
            try {
                var round = 0
                var totalUsed = 0L
                while (true) {
                    round++
                    if (round > MAX_ROUNDS) {
                        onError("工具循环超过 $MAX_ROUNDS 轮仍未结束，已停止")
                        return@Thread
                    }
                    val turn = requestTurn(msgs, key) { d -> onDelta(d) }
                    totalUsed += turn.usageTokens
                    if (turn.usageTokens > 0) onUsage(totalUsed)
                    if (turn.toolCalls.isNotEmpty()) {
                        // 把 assistant 的工具调用声明写回历史
                        val tcs = JSONArray()
                        for (tc in turn.toolCalls) {
                            tcs.put(JSONObject()
                                .put("id", tc.id)
                                .put("type", "function")
                                .put("function", JSONObject().put("name", tc.name).put("arguments", tc.arguments)))
                        }
                        msgs.put(JSONObject().put("role", "assistant").put("content", "").put("tool_calls", tcs))
                        for (tc in turn.toolCalls) {
                            when (tc.name) {
                                "set_plan" -> onPlanStart(tc.arguments)
                                "plan_step" -> runCatching { JSONObject(tc.arguments).optInt("step", 0) }.getOrNull()?.let { onPlanStep(it) }
                                "plan_done" -> onPlanDone()
                            }
                            onToolStart(tc.name, describeTool(tc, "…"))
                            val result = if (tc.name == "set_plan" || tc.name == "plan_step" || tc.name == "plan_done") "ok" else executeTool(ctx, tc)
                            onTool(tc.name, describeTool(tc, result))
                            msgs.put(JSONObject()
                                .put("role", "tool")
                                .put("tool_call_id", tc.id)
                                .put("content", result.take(OUT_LIMIT)))
                        }
                        continue
                    }
                    onDone(turn.text)
                    return@Thread
                }
            } catch (e: Exception) {
                onError("请求失败：${e.javaClass.simpleName} ${e.message ?: ""}")
            }
        }.start()
    }

    private fun describeTool(tc: ToolCall, result: String): String {
        val brief = result.replace('\n', ' ').trim().take(80)
        return when (tc.name) {
            "exec_command" -> try {
                val j = JSONObject(tc.arguments)
                j.optString("cmd").take(60) + if (brief.isNotEmpty()) " → $brief" else ""
            } catch (_: Exception) { tc.arguments.take(60) }
            else -> tc.arguments.take(60)
        }
    }

    /** 单轮请求：流式，首包 15s 超时自动降级非流式 */
    private fun requestTurn(msgs: JSONArray, key: String, onDelta: (String) -> Unit): TurnResult {
        val streamed = tryStream(msgs, key, onDelta)
        if (streamed != null) return streamed
        return nonStream(msgs, key, onDelta)
    }

    private fun tryStream(msgs: JSONArray, key: String, onDelta: (String) -> Unit): TurnResult? {
        val body = JSONObject().put("model", MODEL).put("stream", true)
            .put("messages", msgs).put("tools", tools()).put("tool_choice", "auto").toString()
        val req = Request.Builder().url(API)
            .header("Authorization", "Bearer $key")
            .header("Content-Type", "application/json")
            .post(body.toRequestBody(JSON))
            .build()
        val first = AtomicBoolean(false)
        client.newCall(req).execute().use { resp ->
            if (resp.code !in 200..299) {
                val err = resp.body?.string().orEmpty().take(300)
                throw IOException("DeepSeek HTTP ${resp.code}：$err")
            }
            val timer = Timer("ds-first-chunk", false)
            timer.schedule(object : TimerTask() {
                override fun run() { if (!first.get()) { try { resp.close() } catch (_: Exception) {} } }
            }, 15000L)
            try {
                val source = resp.body!!.source()
                val sb = StringBuilder()
                var used = 0L
                val calls = LinkedHashMap<Int, Triple<String, String, StringBuilder>>() // index -> (id,name,args)
                var line: String?
                while (true) {
                    line = source.readUtf8Line()
                    if (line == null) {
                        if (!first.get()) return null  // 首包超时，降级
                        break
                    }
                    val t = line.trim()
                    if (!t.startsWith("data:")) continue
                    val data = t.substring(5).trim()
                    if (data == "[DONE]") break
                    val chunk = try { JSONObject(data) } catch (_: Exception) { continue }
                    val choices = chunk.optJSONArray("choices")
                    val usageObj = chunk.optJSONObject("usage")
                    if (usageObj != null) {
                        val t = usageObj.optLong("total_tokens")
                        if (t > 0) used = t
                    }
                    if (choices == null || choices.length() == 0) continue
                    val delta = choices.getJSONObject(0).optJSONObject("delta") ?: continue
                    val content = delta.optString("content")
                    if (content.isNotEmpty()) {
                        first.set(true)
                        sb.append(content)
                        onDelta(content)
                    }
                    val tcs = delta.optJSONArray("tool_calls")
                    if (tcs != null) {
                        first.set(true)
                        for (i in 0 until tcs.length()) {
                            val tc = tcs.getJSONObject(i)
                            val idx = tc.optInt("index", -1).let { if (it >= 0) it else i }
                            val fn = tc.optJSONObject("function")
                            val entry = calls.getOrPut(idx) {
                                Triple(tc.optString("id").ifEmpty { "call_$idx" }, fn?.optString("name").orEmpty(), StringBuilder())
                            }
                            val arg = fn?.optString("arguments").orEmpty()
                            if (arg.isNotEmpty()) entry.third.append(arg)
                        }
                    }
                }
                timer.cancel()
                if (!first.get()) return null
                val toolCalls = calls.values.map { ToolCall(it.first, it.second, it.third.toString()) }
                return TurnResult(sb.toString(), toolCalls, used)
            } catch (e: Exception) {
                timer.cancel()
                if (!first.get()) return null
                throw e
            }
        }
    }

    private fun nonStream(msgs: JSONArray, key: String, onDelta: (String) -> Unit): TurnResult {
        val body = JSONObject().put("model", MODEL).put("stream", false)
            .put("messages", msgs).put("tools", tools()).put("tool_choice", "auto").toString()
        val req = Request.Builder().url(API)
            .header("Authorization", "Bearer $key")
            .header("Content-Type", "application/json")
            .post(body.toRequestBody(JSON))
            .build()
        client.newCall(req).execute().use { resp ->
            if (resp.code !in 200..299) {
                val err = resp.body?.string().orEmpty().take(300)
                throw IOException("DeepSeek HTTP ${resp.code}：$err")
            }
            val j = JSONObject(resp.body!!.string())
            val choices = j.optJSONArray("choices")
            if (choices == null || choices.length() == 0) throw IOException("DeepSeek 返回空响应")
            val msg = choices.getJSONObject(0).optJSONObject("message") ?: JSONObject()
            val text = msg.optString("content")
            if (text.isNotBlank()) {
                onDelta(text)
            }
            val tcs = msg.optJSONArray("tool_calls")
            val calls = mutableListOf<ToolCall>()
            if (tcs != null) for (i in 0 until tcs.length()) {
                val tc = tcs.getJSONObject(i)
                val fn = tc.optJSONObject("function")
                calls.add(ToolCall(
                    tc.optString("id").ifEmpty { "call_$i" },
                    fn?.optString("name").orEmpty(),
                    fn?.optString("arguments").orEmpty()))
            }
            if (text.isBlank() && calls.isEmpty()) throw IOException("DeepSeek 返回空内容")
            val usage = j.optJSONObject("usage")
            val used = usage?.optLong("total_tokens") ?: 0L
            return TurnResult(text, calls, used)
        }
    }

    // ---------------- 工具执行 ----------------

    private fun workDir(ctx: Context): File =
        File(ctx.filesDir, "codex/work").apply { mkdirs() }

    private fun executeTool(ctx: Context, tc: ToolCall): String {
        return try {
            when (tc.name) {
                "exec_command" -> {
                    val j = JSONObject(tc.arguments)
                    runShell(ctx, j.optString("cmd"))
                }
                "read_file" -> {
                    val j = JSONObject(tc.arguments)
                    val f = resolvePath(ctx, j.optString("path"))
                    if (f == null) "错误：路径为空"
                    else if (!f.exists()) "错误：文件不存在 ${f.absolutePath}"
                    else f.readText().take(OUT_LIMIT)
                }
                "write_file" -> {
                    val j = JSONObject(tc.arguments)
                    val f = resolvePath(ctx, j.optString("path"))
                    if (f == null) "错误：路径为空"
                    else {
                        f.parentFile?.mkdirs()
                        f.writeText(j.optString("content"))
                        "已写入 ${f.absolutePath}（${f.length()} 字节）"
                    }
                }
                "download_file" -> {
                    val j = JSONObject(tc.arguments)
                    val url = j.optString("url").trim()
                    val f = resolvePath(ctx, j.optString("path"))
                    if (url.isBlank()) "错误：url 不能为空"
                    else if (f == null) "错误：路径为空"
                    else {
                        f.parentFile?.mkdirs()
                        val req = Request.Builder().url(url)
                            .header("User-Agent", "Mozilla/5.0 (Linux; Android 10)")
                            .header("Accept", "*/*")
                            .build()
                        client.newCall(req).execute().use { resp ->
                            if (resp.code !in 200..299) {
                                "下载失败（HTTP ${resp.code}）：${resp.body?.string().orEmpty().take(200)}"
                            } else {
                                val len = resp.body?.byteStream()?.use { input ->
                                    f.outputStream().use { out -> input.copyTo(out) }
                                } ?: -1L
                                "已下载到 ${f.absolutePath}（$len 字节）"
                            }
                        }
                    }
                }
                "http_get" -> {
                    val j = JSONObject(tc.arguments)
                    val url = j.optString("url").trim()
                    if (url.isBlank()) "错误：url 不能为空"
                    else {
                        val req = Request.Builder().url(url)
                            .header("User-Agent", "Mozilla/5.0 (Linux; Android 10)")
                            .build()
                        client.newCall(req).execute().use { resp ->
                            if (resp.code !in 200..299) "请求失败（HTTP ${resp.code}）：${resp.body?.string().orEmpty().take(200)}"
                            else resp.body?.string().orEmpty().take(OUT_LIMIT)
                        }
                    }
                }
                "web_search" -> {
                    val j = JSONObject(tc.arguments)
                    webSearch(j.optString("query"))
                }
                else -> "错误：未知工具 ${tc.name}"
            }
        } catch (e: Exception) {
            "工具执行失败：${e.javaClass.simpleName} ${e.message ?: ""}"
        }
    }

    /** DuckDuckGo HTML 搜索：解析前 5 条结果的标题/链接/摘要 */
    private fun webSearch(query: String): String {
        if (query.isBlank()) return "错误：搜索关键词为空"
        val url = "https://html.duckduckgo.com/html/?q=" + java.net.URLEncoder.encode(query.trim(), "UTF-8")
        val req = Request.Builder().url(url)
            .header("User-Agent", "Mozilla/5.0 (Linux; Android 10)")
            .build()
        val html = client.newCall(req).execute().use { resp ->
            if (resp.code !in 200..299) return@use "搜索失败（HTTP ${resp.code}）"
            resp.body?.string().orEmpty()
        }
        if (html.startsWith("搜索失败")) return html
        val linkRe = Regex("""class="result__a"[^>]*href="([^"]+)"[^>]*>(.*?)</a>""", RegexOption.DOT_MATCHES_ALL)
        val snipRe = Regex("""class="result__snippet"[^>]*>(.*?)</a>""", RegexOption.DOT_MATCHES_ALL)
        val links = linkRe.findAll(html).take(5).toList()
        if (links.isEmpty()) return "未找到相关结果：$query"
        val snips = snipRe.findAll(html).take(5).toList()
        val sb = StringBuilder("“$query”的搜索结果：\n")
        links.forEachIndexed { i, m ->
            val title = cleanHtml(m.groupValues[2])
            val href = decodeDdgUrl(m.groupValues[1])
            val snip = if (i < snips.size) cleanHtml(snips[i].groupValues[1]) else ""
            sb.append("${i + 1}. ").append(title).append('\n')
            sb.append("   ").append(href).append('\n')
            if (snip.isNotEmpty()) sb.append("   ").append(snip).append('\n')
        }
        return sb.toString().take(OUT_LIMIT)
    }

    private fun cleanHtml(s: String): String {
        val noTag = Regex("""<[^>]+>""").replace(s, "").trim()
        return android.text.Html.fromHtml(noTag, android.text.Html.FROM_HTML_MODE_LEGACY).toString().trim()
    }

    private fun decodeDdgUrl(href: String): String {
        val u = if (href.startsWith("//duckduckgo.com/l/?uddg=")) {
            href.substringAfter("uddg=").substringBefore("&")
        } else href
        return try { java.net.URLDecoder.decode(u, "UTF-8") } catch (_: Exception) { u }
    }

    /** 放开路径限制：绝对路径直接用（App 可访问的外部存储等），相对路径基于工作目录 */
    private fun resolvePath(ctx: Context, raw: String): File? {
        if (raw.isBlank()) return null
        val work = workDir(ctx).absoluteFile
        val f = if (raw.startsWith("/")) File(raw) else File(work, raw.trimStart('/'))
        return f.absoluteFile
    }

    private fun runShell(ctx: Context, cmd: String): String {
        val p = CodexEngine.paths(ctx)
        val bash = File(p.bin, "bash")
        if (!bash.exists()) return "错误：运行环境未安装（bash 不存在），请先初始化引擎"
        val pb = ProcessBuilder(bash.absolutePath, "-c", cmd)
        // 完整继承引擎环境：termux-exec 路径重定向、CA 证书、GitHub token、可写 TMPDIR 等
        pb.environment().putAll(CodexEngine.env(ctx, p, preload = false))
        pb.directory(workDir(ctx))
        pb.redirectErrorStream(true)
        return try {
            val proc = pb.start()
            val out = StringBuilder()
            val reader = Thread {
                proc.inputStream.bufferedReader().use { r ->
                    var line: String?
                    while (true) {
                        line = r.readLine() ?: break
                        if (out.length < OUT_LIMIT * 2) out.append(line).append('\n')
                    }
                }
            }
            reader.start()
            val finished = proc.waitFor(TOOL_TIMEOUT_SEC, TimeUnit.SECONDS)
            reader.join(2000)
            if (!finished) {
                proc.destroyForcibly()
                "命令超时（${TOOL_TIMEOUT_SEC}s），已终止：$cmd"
            } else {
                val text = out.toString().trim()
                if (text.isEmpty()) "(命令执行完成，无输出) 退出码 ${proc.exitValue()}"
                else text.take(OUT_LIMIT) + if (text.length > OUT_LIMIT) "\n…(输出已截断)" else ""
            }
        } catch (e: Exception) {
            "命令启动失败：${e.javaClass.simpleName} ${e.message ?: ""}"
        }
    }

    /** 组装 messages：合并连续同角色消息，过滤系统/占位内容 */
    private fun buildMessages(history: List<Pair<String, String>>, prompt: String): JSONArray {
        val out = JSONArray()
        // 简洁回复约束：直给答案、不客套、不重复问题，除非用户要求详细
        out.put(JSONObject().put("role", "system").put("content",
            "回复规则（必须遵守）：极简。默认 1~3 句，用户明确要求详细才展开。不客套、不复述问题、不要铺垫和总结式废话、不重复已说内容。能用列表就用短列表（不超过 3 项）。" +
            "执行策略（必须遵守）：接到任务后一口气干到交付再停。允许连续批量执行多个工具/命令，不要在每步之间停下来总结、确认或纠结下一步；只有任务完成、遇到阻塞性错误（同一操作连续失败≥3次）、或缺少必须由用户提供的信息时才停下。整轮完成后只给 1 句 20 字以内的重点总结（结果+关键信息），不重复命令内容。" + "计划规则：多步任务开始执行前必须先调用 set_plan 声明 2~5 步计划（每步不超过15字），执行中每完成一步立即调用 plan_step 标记，全部完成调用 plan_done；文字回复里不要再重复计划内容，只需在最后给总结。单步任务不要调用这些工具。" +
            "不要主动附建议/补充/提示，除非用户明确要求或确有必要（各 1 句内）。" +
            "环境：手机沙盒（无 root）。python3/wget/sh/busybox 已装；已配本地代理+国内 pip 镜像，python/curl/pip 可在线联网装库；纯 Python 库全可用。" +
            "关键：引擎目录(\$PREFIX)与工作目录持久保存，装的东西重启后仍在；所有库/工具必须装到 \$PREFIX 或工作目录，不要用临时目录。" +
            "seccomp 说明：musl 静态编译的二进制与 C 库（含 numpy/scipy/pandas/jq/ripgrep/ffmpeg 的 musl 版）加载即被系统 seccomp 拦截（SIGSYS 31 被杀），App 无法放行，勿试。" +
            "破解路线：从 Termux 官方源 packages.termux.dev/apt/termux-main/pool/main/ 用 download_file 下载 bionic(Android 原生) 版 .deb 解压到 \$PREFIX/bionic/ 使用——bionic 动态二进制不受 seccomp 限制，numpy/pillow/jq/ffmpeg 都能跑；依赖（libc++/libopenblas/libjpeg 等）同样从该源下载。pip 装大库可直接在线装（已配清华镜像），也可 download_file 下 wheel。" +
            "文件读写支持绝对路径（/sdcard 需用户在系统设置授权所有文件访问）或相对工作目录。命令失败不重复超 3 次，连续失败改用 read_file/write_file 排查或直接说明。"))
        fun append(role: String, content: String) {
            if (content.isBlank()) return
            val c = content.trim()
            if (c.startsWith("⚠️")) return
            if (c == "思考中…") return
            if (out.length() > 0) {
                val last = out.getJSONObject(out.length() - 1)
                if (last.optString("role") == role) {
                    last.put("content", last.optString("content") + "\n\n" + c)
                    return
                }
            }
            out.put(JSONObject().put("role", role).put("content", c))
        }
        var firstUser = history.isEmpty()
        for ((role, content) in history) {
            if (!firstUser && role == "assistant") {
                out.put(JSONObject().put("role", "user").put("content", "继续"))
                firstUser = true
            }
            append(role, content)
            firstUser = true
        }
        append("user", prompt)
        if (out.length() == 0) out.put(JSONObject().put("role", "user").put("content", prompt))
        return out
    }
}
