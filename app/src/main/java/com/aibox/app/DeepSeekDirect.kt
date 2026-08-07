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
        .put(JSONObject()
            .put("type", "function")
            .put("function", JSONObject()
                .put("name", "submit_build")
                .put("description", "把工作目录里的完整 Android 项目推送到云端仓库触发 GitHub Actions 构建（需先在 设置→GitHub Token 填令牌）。提交后必须用 check_build 轮询真实构建结果；未经 check_build 验证，禁止声称“构建成功”“APK 已生成”。只报告工具返回的真实状态。")
                .put("parameters", JSONObject()
                    .put("type", "object")
                    .put("properties", JSONObject()
                        .put("project_dir", JSONObject().put("type", "string").put("description", "项目根目录：相对工作目录路径（如 myapp）或绝对路径"))
                        .put("message", JSONObject().put("type", "string").put("description", "本次提交说明，一句话描述这个项目/改动"))
                    )
                    .put("required", JSONArray().put("project_dir"))
                )))
        .put(JSONObject()
            .put("type", "function")
            .put("function", JSONObject()
                .put("name", "check_build")
                .put("description", "查询云端 GitHub Actions 构建的真实状态（run_id 或 branch 至少填一个）。只有返回“构建成功且已发布”时才允许向用户提供 APK 直链；进行中/失败/未找到都要如实报告。")
                .put("parameters", JSONObject()
                    .put("type", "object")
                    .put("properties", JSONObject()
                        .put("run_id", JSONObject().put("type", "integer").put("description", "构建运行 ID（submit_build 返回里的 id）"))
                        .put("branch", JSONObject().put("type", "string").put("description", "提交分支名（如 agent-build-...），不填 run_id 时按最新一次运行查询"))
                    )
                    .put("required", JSONArray())
                )))
        .put(JSONObject()
            .put("type", "function")
            .put("function", JSONObject()
                .put("name", "manage_skills")
                .put("description", "管理 App 里的技能列表（实时同步到界面技能按钮，用户选中后会把它注入对话开头）。action=list 查看全部；action=add/set 新增或覆盖技能（需 name+prompt）；action=remove 删除技能。")
                .put("parameters", JSONObject()
                    .put("type", "object")
                    .put("properties", JSONObject()
                        .put("action", JSONObject().put("type", "string").put("description", "list / add / set / remove"))
                        .put("name", JSONObject().put("type", "string").put("description", "技能名称，如 周报生成器"))
                        .put("prompt", JSONObject().put("type", "string").put("description", "技能指令内容（add/set 时必填），用户选择该技能后会被注入到对话开头"))
                    )
                    .put("required", JSONArray().put("action"))
                )))
        .put(JSONObject()
            .put("type", "function")
            .put("function", JSONObject()
                .put("name", "schedule_reminder")
                .put("description", "定时提醒：设置一个 N 秒后的系统通知提醒（如 30秒后提醒我喝水）。用于定时任务/待办提醒。")
                .put("parameters", JSONObject()
                    .put("type", "object")
                    .put("properties", JSONObject()
                        .put("seconds", JSONObject().put("type", "integer").put("description", "多少秒后提醒（10~86400）"))
                        .put("text", JSONObject().put("type", "string").put("description", "提醒内容，简短具体"))
                    )
                    .put("required", JSONArray().put("seconds").put("text"))
                )))
        .put(JSONObject()
            .put("type", "function")
            .put("function", JSONObject()
                .put("name", "mcp_list_tools")
                .put("description", "列出 MCP 服务器提供的工具列表。server 留空时使用 设置→MCP 服务器 里配置的地址。")
                .put("parameters", JSONObject()
                    .put("type", "object")
                    .put("properties", JSONObject()
                        .put("server", JSONObject().put("type", "string").put("description", "MCP 服务器地址（http(s)://...），留空用设置里的默认地址"))
                    )
                    .put("required", JSONArray())
                )))
        .put(JSONObject()
            .put("type", "function")
            .put("function", JSONObject()
                .put("name", "mcp_call")
                .put("description", "调用 MCP 服务器上的一个工具（数据库查询、浏览器操作、系统指令等，取决于服务器提供什么）。server 留空时使用 设置→MCP 服务器 里配置的地址。")
                .put("parameters", JSONObject()
                    .put("type", "object")
                    .put("properties", JSONObject()
                        .put("server", JSONObject().put("type", "string").put("description", "MCP 服务器地址，留空用设置里的默认地址"))
                        .put("tool", JSONObject().put("type", "string").put("description", "要调用的工具名，如 execute_query"))
                        .put("input", JSONObject().put("type", "object").put("description", "工具参数对象，如 {\"query\":\"SELECT 1\"}"))
                    )
                    .put("required", JSONArray().put("tool"))
                )))
        .put(JSONObject()
            .put("type", "function")
            .put("function", JSONObject()
                .put("name", "shizuku_cmd")
                .put("description", "以 Shizuku/ADB 级权限执行一条系统命令（需用户在 设置→Shizuku 提权 里启动并授权）。适合 pm install 安装 APK、appops 授权、查看系统属性等。不是 root，无法改 seccomp 或写系统目录。")
                .put("parameters", JSONObject()
                    .put("type", "object")
                    .put("properties", JSONObject()
                        .put("cmd", JSONObject().put("type", "string").put("description", "要执行的系统命令，如 pm list packages -3"))
                    )
                    .put("required", JSONArray().put("cmd"))
                )))

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
                    // 缓冲本轮文字：工具轮里的旁白（"我先查看/正在下载"）直接丢弃，
                    // 只保留真实工具结果，杜绝"用嘴干活"；纯文字轮在轮末一次性输出。
                    val turnBuf = StringBuilder()
                    val turn = requestTurn(msgs, key) { d -> turnBuf.append(d) }
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
                    if (turnBuf.isNotEmpty()) onDelta(turnBuf.toString())
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
                "submit_build" -> {
                    val j = JSONObject(tc.arguments)
                    submitBuild(ctx, j.optString("project_dir"), j.optString("message"))
                }
                "check_build" -> {
                    val j = JSONObject(tc.arguments)
                    checkBuild(ctx, j.optString("branch"), j.optString("run_id"))
                }
                "manage_skills" -> {
                    val j = JSONObject(tc.arguments)
                    manageSkills(ctx, j.optString("action"), j.optString("name"), j.optString("prompt"))
                }
                "shizuku_cmd" -> {
                    val j = JSONObject(tc.arguments)
                    shizukuCmd(j.optString("cmd"))
                }
                "schedule_reminder" -> {
                    val j = JSONObject(tc.arguments)
                    scheduleReminder(ctx, j.optLong("seconds", 60), j.optString("text"))
                }
                "mcp_list_tools" -> {
                    val j = JSONObject(tc.arguments)
                    mcpListTools(ctx, j.optString("server"))
                }
                "mcp_call" -> {
                    val j = JSONObject(tc.arguments)
                    mcpCall(ctx, j.optString("server"), j.optString("tool"), j.optJSONObject("input") ?: JSONObject())
                }
                else -> "错误：未知工具 ${tc.name}"
            }
        } catch (e: Exception) {
            "工具执行失败：${e.javaClass.simpleName} ${e.message ?: ""}"
        }
    }

    /** 技能管理：list/add/set/remove，实时写回 skills.json 供界面读取 */
    private fun manageSkills(ctx: Context, action: String, name: String, prompt: String): String {
        val list = CodexEngine.loadSkills(ctx).toMutableList()
        return when (action.lowercase()) {
            "list" -> if (list.isEmpty()) "当前没有技能"
                else list.mapIndexed { i, (n, p) -> "${i + 1}. $n：${p.take(60)}" }.joinToString("\n")
            "add", "set" -> {
                if (name.isBlank() || prompt.isBlank()) "错误：add/set 需要 name 和 prompt"
                else {
                    list.removeAll { it.first == name }
                    list.add(name to prompt)
                    CodexEngine.saveSkills(ctx, list)
                    "已${if (action.lowercase() == "add") "添加" else "更新"}技能「$name」（共 ${list.size} 个）"
                }
            }
            "remove" -> {
                if (list.removeAll { it.first == name }) {
                    CodexEngine.saveSkills(ctx, list)
                    "已移除技能「$name」（共 ${list.size} 个）"
                } else "未找到技能「$name」"
            }
            else -> "错误：action 必须是 list/add/set/remove"
        }
    }

    /** 一键提交云端编译：推送到 GitHub 并报告真实状态。只返回已验证的事实，禁止声称构建成功。 */
    private fun submitBuild(ctx: Context, projectDirRaw: String, message: String): String {
        val token = CodexEngine.ghToken(ctx)
        if (token.isBlank()) return "错误：请先在 设置 里填写并保存 GitHub Token（用于推送代码触发云端编译）"
        val dir = resolvePath(ctx, projectDirRaw)
        if (dir == null || !dir.isDirectory()) return "错误：项目目录不存在或不是文件夹：$projectDirRaw"
        val skipDirs = setOf(".git", ".gradle", "build", ".idea", "__pycache__", ".kotlin")
        val files = mutableListOf<File>()
        dir.walkTopDown().forEach { f ->
            if (f.isDirectory) {
                if (f.name in skipDirs) return@forEach
            } else if (f.length() <= 2 * 1024 * 1024) {
                files.add(f)
            }
        }
        files.sortBy { it.absolutePath }
        if (files.isEmpty()) return "错误：项目目录里没有可提交的文件"
        val repo = "abuaibobo-dev/agent-builds"
        // 1) 先验证 token 与仓库真实可用（404=仓库不存在，401/403=token 无效）
        val (rc, _) = ghApiFull(repo, "", token, "GET")
        when (rc) {
            200 -> Unit
            401, 403 -> return "错误：GitHub Token 无效或无权限（HTTP $rc），请到 设置→GitHub Token 重新保存"
            404 -> return "错误：云端仓库 $repo 不存在（HTTP 404），无法提交"
            else -> return "错误：无法连接 GitHub（HTTP $rc），请稍后重试"
        }
        // 2) 检查项目里是否有 GitHub Actions 构建工作流
        val hasWorkflow = files.any {
            it.path.replace('\\', '/').startsWith(".github/workflows/") &&
                (it.name.endsWith(".yml") || it.name.endsWith(".yaml"))
        }
        val branch = "agent-build-" + System.currentTimeMillis()
        val defaultRef = ghApi(repo, "git/ref/heads/main", token, "GET")
            ?: return "错误：无法读取云端仓库 main 分支（token 是否有 repo 写权限？）"
        val sha = JSONObject(defaultRef).optJSONObject("object")?.optString("sha").orEmpty()
        if (sha.isEmpty()) return "错误：无法读取云端仓库分支"
        ghApi(repo, "git/refs", token, "POST", JSONObject().put("ref", "refs/heads/$branch").put("sha", sha).toString())
        var pushed = 0
        val commitMsg = message.ifBlank { "agent build" }
        for (f in files) {
            val rel = f.relativeTo(dir).path.replace('\\', '/')
            val b64 = android.util.Base64.encodeToString(f.readBytes(), android.util.Base64.NO_WRAP)
            val body = JSONObject().put("message", commitMsg).put("content", b64).put("branch", branch).toString()
            if (ghApi(repo, "contents/$rel", token, "PUT", body) != null) pushed++
        }
        if (pushed == 0) return "错误：文件推送失败（0/${files.size}），请检查 GitHub Token 权限"
        // 3) 提交后立刻查询真实构建运行
        val (runCode, runBody) = ghApiFull(repo, "actions/runs?branch=$branch&per_page=1", token, "GET")
        val run = if (runCode == 200 && runBody.isNotBlank()) {
            runCatching {
                val arr = JSONObject(runBody).optJSONArray("workflow_runs") ?: JSONArray()
                if (arr.length() > 0) arr.getJSONObject(0) else null
            }.getOrNull()
        } else null
        val pushedMsg = "已推送 $pushed/${files.size} 个文件到分支 $branch"
        if (run != null) {
            val runId = run.optLong("id")
            val status = run.optString("status")
            val url = run.optString("html_url")
            if (!hasWorkflow) {
                return "⚠️ $pushedMsg，但项目里没有 .github/workflows 构建工作流，不会生成 APK。\n" +
                    "构建运行：id=$runId status=$status $url\n" +
                    "如需 APK，先在工作目录创建 .github/workflows/build.yml 再重新提交。"
            }
            return "$pushedMsg。\n构建运行已出现：id=$runId status=$status\n$url\n" +
                "用 check_build 轮询真实结果；验证成功前不要声称 APK 已生成。"
        }
        return if (hasWorkflow) {
            "$pushedMsg，但 GitHub 尚未返回该分支的构建运行（Actions 可能未开启）。\n用 check_build(branch=$branch) 继续轮询真实状态。"
        } else {
            "⚠️ $pushedMsg，但项目里没有 .github/workflows 构建工作流，不会生成 APK。\n如需要 APK，先创建 .github/workflows/build.yml。"
        }
    }

    /** 轮询云端构建真实状态：只有 run conclusion=success 且 release 真实存在才给 APK 直链 */
    private fun checkBuild(ctx: Context, branch: String, runIdRaw: String): String {
        val token = CodexEngine.ghToken(ctx)
        if (token.isBlank()) return "错误：请先配置 GitHub Token"
        val repo = "abuaibobo-dev/agent-builds"
        val run = runIdRaw.trim().toLongOrNull()?.let { rid ->
            val (c, b) = ghApiFull(repo, "actions/runs/$rid", token, "GET")
            if (c == 200 && b.isNotBlank()) runCatching { JSONObject(b) }.getOrNull() else null
        } ?: run {
            val br = branch.trim()
            if (br.isEmpty()) return "错误：check_build 需要 run_id 或 branch 参数"
            val (c, b) = ghApiFull(repo, "actions/runs?branch=$br&per_page=1", token, "GET")
            if (c == 200 && b.isNotBlank()) {
                runCatching {
                    val arr = JSONObject(b).optJSONArray("workflow_runs") ?: JSONArray()
                    if (arr.length() > 0) arr.getJSONObject(0) else null
                }.getOrNull()
            } else null
        }
        if (run == null) return "查询失败：GitHub 未返回该构建（分支/run_id 不存在？）"
        val status = run.optString("status")
        val conclusion = run.optString("conclusion")
        val runId = run.optLong("id")
        val url = run.optString("html_url")
        if (status != "completed") {
            return "构建进行中：id=$runId status=$status\n$url\n（未完成，不要声称成功）"
        }
        if (conclusion != "success") {
            return "构建失败：id=$runId conclusion=$conclusion\n$url\n（没有 APK，需修复后重新提交）"
        }
        // 构建成功：再验证 release 真实存在才给直链
        val tag = run.optString("head_branch")
        val (rc, _) = ghApiFull(repo, "releases/tags/$tag", token, "GET")
        if (rc == 200) {
            return "✅ 构建成功且已发布：id=$runId\nAPK 直链：https://github.com/$repo/releases/download/$tag/app-debug.apk"
        }
        return "构建成功：id=$runId conclusion=success，但 release 尚未出现，继续轮询 check_build。"
    }

    /** GitHub API 请求：成功返回响应体，失败返回 null */
    private fun ghApi(repo: String, path: String, token: String, method: String, body: String? = null): String? {
        return ghApiFull(repo, path, token, method, body).let { (c, b) -> if (c in 200..299) b else null }
    }

    /** GitHub API 请求：返回 (HTTP 状态码, 响应体)，网络异常返回 (-1, "") */
    private fun ghApiFull(repo: String, path: String, token: String, method: String, body: String? = null): Pair<Int, String> {
        val url = "https://api.github.com/repos/$repo/$path"
        val b = Request.Builder().url(url)
            .header("Authorization", "Bearer $token")
            .header("Accept", "application/vnd.github+json")
            .header("User-Agent", "Synaps-Android")
        val req = when (method) {
            "POST" -> b.post((body ?: "{}").toRequestBody(JSON))
            "PUT" -> b.put((body ?: "{}").toRequestBody(JSON))
            else -> b.get()
        }.build()
        return try {
            client.newCall(req).execute().use { resp -> resp.code to (resp.body?.string().orEmpty()) }
        } catch (_: Exception) { -1 to "" }
    }

    /** Shizuku/ADB 级命令执行：需要用户在设置里启动并授权 Shizuku */
    private fun shizukuCmd(cmd: String): String {
        if (cmd.isBlank()) return "错误：cmd 不能为空"
        if (!rikka.shizuku.Shizuku.pingBinder()) {
            return "错误：Shizuku 未运行，请先启动 Shizuku（root 直接启动，无 root 用 adb/无线调试启动）"
        }
        if (rikka.shizuku.Shizuku.checkSelfPermission() != android.content.pm.PackageManager.PERMISSION_GRANTED) {
            return "错误：未授权，请到 设置→Shizuku 提权 点击授权"
        }
        return try {
            val binder = rikka.shizuku.Shizuku.getBinder()
                ?: return "错误：Shizuku 服务不可用（binder 为空），请回设置页重新授权"
            val svc = moe.shizuku.server.IShizukuService.Stub.asInterface(binder)
            val proc = svc.newProcess(arrayOf("sh", "-c", cmd + " 2>&1"), null, null)
            val out = StringBuilder()
            proc.inputStream?.let { pfd ->
                val r = java.io.BufferedReader(
                    java.io.InputStreamReader(android.os.ParcelFileDescriptor.AutoCloseInputStream(pfd))
                )
                Thread {
                    try {
                        var line = r.readLine()
                        while (line != null) { out.append(line).append('\n'); line = r.readLine() }
                    } catch (_: Exception) { } finally { try { r.close() } catch (_: Exception) { } }
                }.start()
            }
            val done = proc.waitForTimeout(TOOL_TIMEOUT_SEC * 1000L, "synaps")
            if (!done) { try { proc.destroy() } catch (_: Exception) { } }
            Thread.sleep(150)
            val text = out.toString().trim()
            val code = if (done) proc.exitValue() else -1
            if (text.isEmpty()) "(Shizuku 执行完成，无输出) 退出码 $code"
            else text.take(OUT_LIMIT) + (if (text.length > OUT_LIMIT) "\n…(输出已截断)" else "")
        } catch (e: Exception) {
            "Shizuku 执行失败：${e.javaClass.simpleName} ${e.message ?: ""}"
        }
    }

    /** 定时提醒：AlarmManager 触发系统通知 */
    private fun scheduleReminder(ctx: Context, seconds: Long, text: String): String {
        val t = text.trim()
        if (t.isBlank()) return "错误：提醒内容不能为空"
        val delay = seconds.coerceIn(10, 86400)
        val at = System.currentTimeMillis() + delay * 1000
        val pi = android.app.PendingIntent.getBroadcast(
            ctx, (System.currentTimeMillis() % 100000).toInt(),
            android.content.Intent(ctx, ReminderReceiver::class.java)
                .putExtra("text", t).putExtra("when", at),
            android.app.PendingIntent.FLAG_UPDATE_CURRENT or android.app.PendingIntent.FLAG_IMMUTABLE)
        val am = ctx.getSystemService(Context.ALARM_SERVICE) as android.app.AlarmManager
        try {
            am.setAndAllowWhileIdle(android.app.AlarmManager.RTC_WAKEUP, at, pi)
        } catch (_: Exception) {
            am.set(android.app.AlarmManager.RTC_WAKEUP, at, pi)
        }
        return "已设置提醒：${delay}秒后「$t」"
    }

    private fun mcpListTools(ctx: Context, server: String): String = mcpRequest(ctx, server, "tools/list", JSONObject())

    private fun mcpCall(ctx: Context, server: String, tool: String, input: JSONObject): String {
        if (tool.isBlank()) return "错误：tool 不能为空"
        return mcpRequest(ctx, server, "tools/call", JSONObject().put("name", tool).put("arguments", input))
    }

    /** MCP Streamable HTTP：initialize 建会话 → 调用 tools/list 或 tools/call，兼容纯 JSON 与 SSE 响应 */
    private fun mcpRequest(ctx: Context, server: String, method: String, params: JSONObject): String {
        val url = server.ifBlank {
            ctx.getSharedPreferences("settings", Context.MODE_PRIVATE).getString("mcp_server", "")?.trim().orEmpty()
        }
        if (url.isBlank()) return "错误：请先在 设置→MCP 服务器 填写地址，或在参数 server 中传入"
        return try {
            fun post(rpc: JSONObject, session: String? = null): okhttp3.Response {
                val b = Request.Builder().url(url)
                    .header("Accept", "application/json, text/event-stream")
                    .header("Content-Type", "application/json")
                    .header("MCP-Protocol-Version", "2025-06-18")
                if (session != null) b.header("Mcp-Session-Id", session)
                return client.newCall(b.post(rpc.toString().toRequestBody(JSON)).build()).execute()
            }
            var session: String? = null
            val init = JSONObject().put("jsonrpc", "2.0").put("id", 1).put("method", "initialize")
                .put("params", JSONObject()
                    .put("protocolVersion", "2025-06-18")
                    .put("capabilities", JSONObject())
                    .put("clientInfo", JSONObject().put("name", "Synaps").put("version", "2.5.14")))
            post(init).use { resp ->
                if (resp.code !in 200..299) return "MCP 连接失败（HTTP ${resp.code}）：${resp.body?.string().orEmpty().take(200)}"
                session = resp.header("Mcp-Session-Id")
                parseMcp(resp.body?.string().orEmpty(), "initialize")
            }
            try {
                post(JSONObject().put("jsonrpc", "2.0").put("method", "notifications/initialized"), session).close()
            } catch (_: Exception) { }
            val req = JSONObject().put("jsonrpc", "2.0").put("id", 2).put("method", method).put("params", params)
            post(req, session).use { resp ->
                if (resp.code !in 200..299) "MCP $method 失败（HTTP ${resp.code}）：${resp.body?.string().orEmpty().take(200)}"
                else parseMcp(resp.body?.string().orEmpty(), method)
            }
        } catch (e: Exception) {
            "MCP 请求失败：${e.javaClass.simpleName} ${e.message ?: ""}"
        }
    }

    /** 解析 MCP 响应：优先 JSON，SSE（data: {...}）则逐行拼 */
    private fun parseMcp(body: String, method: String): String {
        val text = if (body.contains("\"jsonrpc\"")) body else {
            body.lineSequence().filter { it.startsWith("data:") }.map { it.removePrefix("data:").trim() }
                .filter { it.isNotEmpty() && it != "[DONE]" }
                .joinToString("\n")
        }
        if (text.isBlank()) return "MCP 返回为空"
        return try {
            val j = JSONObject(text)
            if (j.has("error")) "MCP 错误：${j.getJSONObject("error").optString("message")}"
            else {
                val result = j.optJSONObject("result")
                if (result == null) text.take(OUT_LIMIT)
                else when (method) {
                    "tools/list" -> {
                        val arr = result.optJSONArray("tools") ?: JSONArray()
                        if (arr.length() == 0) "该 MCP 服务器没有可用工具"
                        else buildString {
                            for (i in 0 until arr.length()) {
                                val t = arr.getJSONObject(i)
                                append("- ").append(t.optString("name"))
                                if (t.has("description")) append("：").append(t.optString("description").take(80))
                                append('\n')
                            }
                        }.trim()
                    }
                    "tools/call" -> {
                        val content = result.optJSONArray("content") ?: JSONArray()
                        val sb = StringBuilder()
                        for (i in 0 until content.length()) {
                            val c = content.getJSONObject(i)
                            if (c.optString("type") == "text") sb.append(c.optString("text")) else sb.append(c.toString())
                            sb.append('\n')
                        }
                        val out = sb.toString().trim()
                        if (result.optBoolean("isError")) "MCP 工具返回错误：$out" else out.ifEmpty { "MCP 调用完成（无返回内容）" }
                    }
                    else -> text.take(OUT_LIMIT)
                }
            }
        } catch (_: Exception) {
            text.take(OUT_LIMIT)
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
            "回复规则（必须遵守）：像 Codex 一样回复——先给结论，再给要点（每行一条，最多 3 条）。极简，默认 1~3 句，用户明确要求详细才展开。不客套、不复述问题、不要铺垫和总结式废话、不重复已说内容。" +
            "执行策略（必须遵守）：接到任务后一口气干到交付再停。允许连续批量执行多个工具/命令，不要在每步之间停下来总结、确认或纠结下一步；只有任务完成、遇到阻塞性错误（同一操作连续失败≥3次）、或缺少必须由用户提供的信息时才停下。整轮完成后只给 1 句 20 字以内的重点总结（结果+关键信息），不重复命令内容。" + "诚实规则（必须遵守）：一切结论必须以工具返回的真实结果为准，禁止编造、猜测或美化。文件/项目/命令结果存在与否，先调用工具验证再说话；提交构建后，未经 check_build 验证成功并拿到真实 APK 直链，禁止声称“构建成功”“APK 已生成”“编译中”；工具没有返回证据时如实说“尚未验证/待确认”，绝不用编造的结果充数交差。" + "禁止“用嘴干活”（必须遵守）：执行任务期间不要用文字描述你将要或正在做的操作（如“我先查看目录”“正在下载”“我来写个脚本”）。需要执行就立刻调用对应工具，工具调用会自动显示在界面上；执行期间不输出任何旁白文字，只输出工具真实返回的结果和最终结论。宁可少说，不可虚报。" + "计划规则：多步任务开始执行前必须先调用 set_plan 声明 2~5 步计划（每步不超过15字），执行中每完成一步立即调用 plan_step 标记，全部完成调用 plan_done；文字回复里不要再重复计划内容，只需在最后给总结。单步任务不要调用这些工具。" +
            "不要主动附建议/补充/提示，除非用户明确要求或确有必要（各 1 句内）。" +
            "环境：手机沙盒（无 root）。python3/wget/sh/busybox 已装；已配本地代理+国内 pip 镜像，python/curl/pip 可在线联网装库；纯 Python 库全可用。" +
            "关键：引擎目录(\$PREFIX)与工作目录持久保存，装的东西重启后仍在；所有库/工具必须装到 \$PREFIX 或工作目录，不要用临时目录。" +
            "seccomp 说明：musl 静态编译的二进制与 C 库（含 numpy/scipy/pandas/jq/ripgrep/ffmpeg 的 musl 版）加载即被系统 seccomp 拦截（SIGSYS 31 被杀），App 无法放行，勿试。" +
            "破解路线：从 Termux 官方源 packages.termux.dev/apt/termux-main/pool/main/ 用 download_file 下载 bionic(Android 原生) 版 .deb 解压到 \$PREFIX/bionic/ 使用——bionic 动态二进制不受 seccomp 限制，numpy/pillow/jq/ffmpeg 都能跑；依赖（libc++/libopenblas/libjpeg 等）同样从该源下载。pip 装大库可直接在线装（已配清华镜像），也可 download_file 下 wheel。" +
            "文件读写支持绝对路径（/sdcard 需用户在系统设置授权所有文件访问）或相对工作目录。命令失败不重复超 3 次，连续失败改用 read_file/write_file 排查或直接说明。" +
            "打包能力：完整 Android 项目写好后（必须含 settings.gradle、根 build.gradle、app/build.gradle、AndroidManifest.xml、res、源码，确保可独立编译），调用 submit_build 提交（project_dir 传项目根目录），App 自动推送云端编译；云端环境 JDK17 + Gradle8.9 + compileSdk 34。提交成功会返回分支名，APK 直链格式固定为 https://github.com/abuaibobo-dev/agent-builds/releases/download/<分支名>/app-debug.apk，云端构建约 3~6 分钟，可用 http_get 访问 https://api.github.com/repos/abuaibobo-dev/agent-builds/releases/tags/<分支名> 轮询是否完成（200=完成，404=还没好）。" +
            "技能管理：用 manage_skills 查看/新增/更新/删除技能，技能会实时出现在 App 的技能按钮面板，用户选中后你的技能指令会被注入到该轮对话开头；可以主动把常用工作流注册成技能。" +
            "提权命令：shizuku_cmd 以 ADB 权限执行系统命令（需用户在 设置→Shizuku 提权 启动并授权），可 pm install 装 APK、appops 授权、查看系统属性；Shizuku 不是 root，装不了系统级二进制、改不了 seccomp。"))
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
