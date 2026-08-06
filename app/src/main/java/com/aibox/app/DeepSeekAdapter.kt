package com.aibox.app

import android.content.Context
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedReader
import java.io.InputStreamReader
import java.io.OutputStream
import java.net.ServerSocket
import java.net.Socket
import java.io.File
import java.util.concurrent.TimeUnit

/**
 * 本地 DeepSeek 转接头（纯 DeepSeek 独占版核心）。
 *
 * codex 引擎以 wire_api=responses 向 http://127.0.0.1:<port>/responses 发请求，
 * 本适配器实时翻译成 DeepSeek 标准 /chat/completions（流式），
 * 再把 DeepSeek 的 SSE 逐块翻译回 Responses SSE 喂给引擎。
 *
 * 关键翻译点：
 * 1) 工具：拦截所有 type=custom / namespace / web_search，只透传 type=function；
 * 2) input(Responses) <-> messages(Chat)，developer/instructions -> system，
 *    function_call / function_call_output <-> assistant.tool_calls / tool 消息；
 * 3) 流式输出：content delta -> output_text.delta；tool_calls delta -> function_call_arguments.delta。
 */
object DeepSeekAdapter {

    private const val PREF_PORT = "port"
    private const val DEFAULT_PORT = 8765
    private val JSON = "application/json; charset=utf-8".toMediaType()
    private val client = OkHttpClient.Builder()
        .connectTimeout(20, TimeUnit.SECONDS)
        .readTimeout(45, TimeUnit.SECONDS)
        .writeTimeout(120, TimeUnit.SECONDS)
        .build()

    @Volatile private var port = -1
    @Volatile var lastRequestAt: Long = 0; private set
    @Volatile var lastError: String? = null; private set
    @Volatile var lastOk: Boolean = false; private set

    /** 启动本地服务；返回实际监听端口，失败返回 -1 */
    fun start(ctx: Context): Int {
        synchronized(this) {
            if (port > 0) return port
            val sp = ctx.getSharedPreferences("deepseek_adapter", Context.MODE_PRIVATE)
            val first = sp.getInt(PREF_PORT, DEFAULT_PORT)
            var server: ServerSocket? = null
            for (p in first until first + 6) {
                try { server = ServerSocket(p); port = p; break } catch (_: Exception) {}
            }
            if (server == null) return -1
            sp.edit().putInt(PREF_PORT, port).apply()
            val srv = server
            Thread({
                while (port > 0) {
                    try {
                        val sock = srv.accept()
                        Thread({ handle(ctx, sock) }, "adapter-conn").start()
                    } catch (_: Exception) { break }
                }
            }, "deepseek-adapter").start()
            return port
        }
    }

    fun port(): Int = port

    // ---------------- HTTP 最小实现 ----------------

    private fun handle(ctx: Context, sock: Socket) {
        try {
            sock.soTimeout = 600000
            val reader = BufferedReader(InputStreamReader(sock.getInputStream(), Charsets.UTF_8))
            val reqLine = reader.readLine() ?: return
            val parts = reqLine.split(" ")
            if (parts.size < 3) return
            val method = parts[0]; val path = parts[1]
            var len = 0
            while (true) {
                val h = reader.readLine() ?: return
                if (h.isEmpty()) break
                if (h.startsWith("Content-Length:", true)) len = h.substringAfter(':').trim().toIntOrNull() ?: 0
            }
            val body = CharArray(len).let { c -> var r = 0; while (r < len) { val n = reader.read(c, r, len - r); if (n < 0) break; r += n }; String(c, 0, r) }
            val out = sock.getOutputStream()
            if (method == "POST" && path.endsWith("/responses")) {
                handleResponses(ctx, body, out)
            } else {
                writeHead(out, 404, "application/json", "{\"error\":\"not found\"}".toByteArray())
            }
        } catch (_: Exception) {
        } finally {
            try { sock.close() } catch (_: Exception) {}
        }
    }

    private fun writeHead(out: OutputStream, code: Int, ct: String, body: ByteArray) {
        out.write(("HTTP/1.1 $code ${if (code == 200) "OK" else "Error"}\r\nContent-Type: $ct\r\nContent-Length: ${body.size}\r\nConnection: close\r\n\r\n").toByteArray())
        out.write(body)
        out.flush()
    }

    private fun sse(out: OutputStream, obj: JSONObject) {
        out.write(("data: " + obj.toString() + "\n\n").toByteArray(Charsets.UTF_8))
        out.flush()
    }

    // ---------------- 请求翻译：Responses -> Chat ----------------

    private fun translateRequest(req: JSONObject): JSONObject {
        val msgs = JSONArray()
        val instructions = req.optString("instructions")
        if (instructions.isNotBlank()) msgs.put(JSONObject().put("role", "system").put("content", instructions))

        var pendingCalls = JSONArray()
        val input = req.optJSONArray("input") ?: JSONArray()
        for (i in 0 until input.length()) {
            val item = input.getJSONObject(i)
            when (item.optString("type")) {
                "message" -> {
                    var role = item.optString("role")
                    val content = buildString {
                        val parts = item.optJSONArray("content") ?: JSONArray()
                        for (j in 0 until parts.length()) {
                            val p = parts.getJSONObject(j)
                            when (p.optString("type")) {
                                "input_text", "output_text" -> { if (isNotEmpty()) append("\n"); append(p.optString("text")) }
                                "input_image" -> { if (isNotEmpty()) append("\n"); append("[图片附件：DeepSeek 文本模型不支持图片，已省略]") }
                            }
                        }
                    }
                    if (role == "developer") role = "system"
                    when (role) {
                        "system" -> {
                            if (msgs.length() > 0 && msgs.getJSONObject(msgs.length() - 1).optString("role") == "system") {
                                val last = msgs.getJSONObject(msgs.length() - 1)
                                last.put("content", last.optString("content") + "\n\n" + content)
                            } else {
                                msgs.put(JSONObject().put("role", "system").put("content", content))
                            }
                        }
                        "assistant" -> {
                            val m = JSONObject().put("role", "assistant").put("content", content)
                            if (pendingCalls.length() > 0) {
                                val tcs = JSONArray()
                                for (k in 0 until pendingCalls.length()) {
                                    val c = pendingCalls.getJSONObject(k)
                                    tcs.put(JSONObject()
                                        .put("id", c.optString("call_id"))
                                        .put("type", "function")
                                        .put("function", JSONObject().put("name", c.optString("name")).put("arguments", c.optString("arguments"))))
                                }
                                m.put("tool_calls", tcs)
                                pendingCalls = JSONArray()
                            }
                            msgs.put(m)
                        }
                        else -> msgs.put(JSONObject().put("role", "user").put("content", content))
                    }
                }
                "function_call" -> pendingCalls.put(item)
                "function_call_output" -> msgs.put(JSONObject()
                    .put("role", "tool")
                    .put("tool_call_id", item.optString("call_id"))
                    .put("content", item.optString("output")))
            }
        }

        val tools = JSONArray()
        val reqTools = req.optJSONArray("tools") ?: JSONArray()
        for (i in 0 until reqTools.length()) {
            val t = reqTools.getJSONObject(i)
            if (t.optString("type") == "function") {
                tools.put(JSONObject()
                    .put("type", "function")
                    .put("function", JSONObject()
                        .put("name", t.optString("name"))
                        .put("description", t.optString("description"))
                        .put("parameters", t.optJSONObject("parameters") ?: JSONObject())))
            }
        }

        val model = req.optString("model").ifBlank { "deepseek-chat" }
        val body = JSONObject()
            .put("model", model)
            .put("stream", true)
            .put("messages", msgs)
        // v2.2.8-debug：强制禁用所有工具（tools=[]），只测纯文本。
        // 若纯文本能回复，说明问题在工具翻译层；若仍不回，说明上游 SSE 结构不匹配。
        val mt = req.opt("max_output_tokens")
        if (mt != null && mt != JSONObject.NULL) body.put("max_tokens", mt)
        return body
    }

    // ---------------- 上游请求 + 流式回译 ----------------

    private fun handleResponses(ctx: Context, body: String, out: OutputStream) {
        val req = try { JSONObject(body) } catch (e: Exception) {
            writeHead(out, 400, JSON.toString(), "{\"error\":\"bad json\"}".toByteArray()); return
        }
        val chat = translateRequest(req)
        val key = CodexEngine.keyForProvider(ctx, CodexEngine.PROVIDER_DEEPSEEK)
        if (key.isBlank()) {
            writeHead(out, 401, JSON.toString(), "{\"error\":\"missing DeepSeek API Key\"}".toByteArray()); return
        }
        val httpReq = Request.Builder()
            .url("https://api.deepseek.com/chat/completions")
            .header("Authorization", "Bearer $key")
            .post(chat.toString().toRequestBody(JSON))
            .build()
        lastRequestAt = System.currentTimeMillis()
        appendLog(ctx, "adapter: 收到引擎请求 model=${req.optString("model")} tools=${req.optJSONArray("tools")?.length() ?: 0} msgs=${chat.optJSONArray("messages")?.length() ?: 0}")
        val resp = try { client.newCall(httpReq).execute() } catch (e: Exception) {
            lastError = e.message
            lastOk = false
            appendLog(ctx, "adapter: 上游连接失败 ${e.message}")
            writeHead(out, 502, JSON.toString(), "{\"error\":\"${e.message}\"}".toByteArray()); return
        }
        resp.use { r ->
            if (r.code !in 200..299) {
                lastError = "deepseek http ${r.code}: ${r.body?.string().orEmpty().take(200)}"
                lastOk = false
                appendLog(ctx, "adapter: DeepSeek 返回 ${r.code} ${lastError}")
                writeHead(out, r.code, JSON.toString(), "{\"error\":\"${lastError}\"}".toByteArray())
                return
            }
            lastOk = true
            lastError = null
            appendLog(ctx, "adapter: DeepSeek 200，开始转发流")
            out.write(("HTTP/1.1 200 OK\r\nContent-Type: text/event-stream; charset=utf-8\r\nConnection: close\r\n\r\n").toByteArray())
            out.flush()
            val rid = "resp_" + System.currentTimeMillis()
            val outputItems = JSONArray()
            // 立即发 response.created：让引擎确认流已建立，避免首个 chunk 延迟时被 App 看门狗误杀
            sse(out, JSONObject().put("type", "response.created").put("response", JSONObject()
                .put("id", rid).put("object", "response").put("status", "in_progress").put("model", req.optString("model"))
                .put("output", JSONArray()).put("tools", JSONArray()).put("tool_choice", "auto")
                .put("max_output_tokens", JSONObject.NULL).put("temperature", 1).put("top_p", 1)
                .put("parallel_tool_calls", true).put("metadata", JSONObject()).put("truncation", "disabled")
                .put("store", false).put("usage", JSONObject.NULL)))

            var cur: JSONObject? = null                    // 当前文本输出项
            var textAcc = StringBuilder()                  // 当前文本累积
            val fns = java.util.TreeMap<Int, JSONObject>() // tool_call index -> {id, call_id, name, args}
            var inputTokens = 0
            var outputTokens = 0

            // 完成当前文本项：补发 done 事件并写入 outputItems
            fun flushText() {
                cur?.let { c ->
                    val text = textAcc.toString()
                    sse(out, JSONObject().put("type", "response.output_text.done").put("item_id", c.optString("id"))
                        .put("output_index", c.optInt("idx")).put("content_index", 0).put("text", text))
                    sse(out, JSONObject().put("type", "response.content_part.done").put("item_id", c.optString("id"))
                        .put("output_index", c.optInt("idx")).put("content_index", 0)
                        .put("part", JSONObject().put("type", "output_text").put("text", text).put("annotations", JSONArray())))
                    sse(out, JSONObject().put("type", "response.output_item.done").put("output_index", c.optInt("idx"))
                        .put("item", JSONObject().put("id", c.optString("id")).put("type", "message").put("role", "assistant").put("status", "completed")
                            .put("content", JSONArray().put(JSONObject().put("type", "output_text").put("text", text).put("annotations", JSONArray())))))
                    outputItems.put(JSONObject().put("id", c.optString("id")).put("type", "message").put("role", "assistant").put("status", "completed")
                        .put("content", JSONArray().put(JSONObject().put("type", "output_text").put("text", text).put("annotations", JSONArray()))))
                    cur = null
                    textAcc = StringBuilder()
                }
            }

            // 完成全部函数调用项（按 index 顺序）
            fun flushFunctions() {
                for (entry in fns) {
                    val f = entry.value
                    val args = f.optString("args")
                    val idx = f.optInt("idx")
                    sse(out, JSONObject().put("type", "response.function_call_arguments.done")
                        .put("item_id", f.optString("id")).put("output_index", idx).put("arguments", args))
                    sse(out, JSONObject().put("type", "response.output_item.done").put("output_index", idx)
                        .put("item", JSONObject().put("id", f.optString("id")).put("type", "function_call").put("status", "completed")
                            .put("call_id", f.optString("call_id")).put("name", f.optString("name")).put("arguments", args)))
                    outputItems.put(JSONObject().put("id", f.optString("id")).put("type", "function_call").put("status", "completed")
                        .put("call_id", f.optString("call_id")).put("name", f.optString("name")).put("arguments", args))
                }
                fns.clear()
            }

            // 首个文本分片到来时创建输出项
            fun ensureTextItem() {
                if (cur != null) return
                val idx = outputItems.length()
                val item = JSONObject().put("id", "msg_$idx").put("idx", idx)
                cur = item
                sse(out, JSONObject().put("type", "response.output_item.added").put("output_index", idx)
                    .put("item", JSONObject().put("id", item.optString("id")).put("type", "message").put("role", "assistant").put("status", "in_progress")
                        .put("content", JSONArray().put(JSONObject().put("type", "output_text").put("text", "").put("annotations", JSONArray())))))
                sse(out, JSONObject().put("type", "response.content_part.added").put("item_id", item.optString("id"))
                    .put("output_index", idx).put("content_index", 0)
                    .put("part", JSONObject().put("type", "output_text").put("text", "").put("annotations", JSONArray())))
            }

            // v2.2.8-debug：gotFirst 表示"收到过任何 data 数据块"（不要求有内容）
            val gotFirst = java.util.concurrent.atomic.AtomicBoolean(false)
            val firstRaw = java.util.concurrent.atomic.AtomicBoolean(false)
            val firstDelta = java.util.concurrent.atomic.AtomicBoolean(false)
            val firstChunkTimer = java.util.Timer("adapter-first-chunk", false)
            firstChunkTimer.schedule(object : java.util.TimerTask() {
                override fun run() {
                    if (!gotFirst.get()) {
                        lastError = "DeepSeek 3 秒未返回任何数据块"
                        lastOk = false
                        appendLog(ctx, "adapter: 3s 无数据块，已向引擎报错")
                        try {
                            sse(out, JSONObject().put("type", "error").put("message", "DeepSeek 3 秒未返回任何数据块，请重试"))
                        } catch (_: Exception) {}
                        try { out.close() } catch (_: Exception) {}
                    }
                }
            }, 3000L)

            val reader = BufferedReader(InputStreamReader(r.body!!.byteStream(), Charsets.UTF_8))
            try {
                while (true) {
                    val line = reader.readLine() ?: break
                    val t = line.trim()
                    if (!t.startsWith("data:")) continue
                    val data = t.substring(5).trim()
                    if (data == "[DONE]") break
                    val chunk = try { JSONObject(data) } catch (_: Exception) { continue }
                    gotFirst.set(true)
                    if (firstRaw.compareAndSet(false, true)) {
                        appendLog(ctx, "adapter: 首个上游数据块: " + data.take(200))
                    }
                    // 上游可能在 200 里直接返回错误体（{error:...}），识别并转发给引擎，避免静默挂起
                    val upstreamErr = chunk.optJSONObject("error")
                    if (upstreamErr != null) {
                        val msg = upstreamErr.optString("message").ifBlank { upstreamErr.toString() }
                        lastError = "DeepSeek 错误: $msg"
                        lastOk = false
                        appendLog(ctx, "adapter: 上游错误体: $msg")
                        try { sse(out, JSONObject().put("type", "error").put("message", "DeepSeek 错误：$msg")) } catch (_: Exception) {}
                        break
                    }
                    val usage = chunk.optJSONObject("usage")
                    if (usage != null) {
                        inputTokens = usage.optInt("prompt_tokens")
                        outputTokens = usage.optInt("completion_tokens")
                    }
                    val choices = chunk.optJSONArray("choices")
                    if (choices == null || choices.length() == 0) continue
                    val ch = choices.getJSONObject(0)
                    val delta = ch.optJSONObject("delta")
                    if (delta == null) {
                        // 无 delta 的数据块也发个点，证明流在动
                        ensureTextItem()
                        textAcc.append(".")
                        sse(out, JSONObject().put("type", "response.output_text.delta").put("item_id", cur!!.optString("id"))
                            .put("output_index", cur!!.optInt("idx")).put("content_index", 0).put("delta", "."))
                        continue
                    }

                    // 文本：DeepSeek 思考模型先发 reasoning_content 再发 content，两者都作为正文输出，保证引擎必然收到文本
                    val content = delta.optString("content")
                    val reasoning = delta.optString("reasoning_content")
                    if (content.isNotEmpty() || reasoning.isNotEmpty()) {
                        if (firstDelta.compareAndSet(false, true)) {
                            appendLog(ctx, "adapter: 收到首个文本分片（${if (content.isNotEmpty()) "content" else "reasoning"}）")
                        }
                        ensureTextItem()
                        val piece = if (content.isNotEmpty()) content else reasoning
                        textAcc.append(piece)
                        sse(out, JSONObject().put("type", "response.output_text.delta").put("item_id", cur!!.optString("id"))
                            .put("output_index", cur!!.optInt("idx")).put("content_index", 0).put("delta", piece))
                    } else {
                        // v2.2.8-debug：空 delta 也发 "."，强制暴露流是否在动
                        if (firstDelta.compareAndSet(false, true)) appendLog(ctx, "adapter: 首个分片为空内容（已发保活点）")
                        ensureTextItem()
                        textAcc.append(".")
                        sse(out, JSONObject().put("type", "response.output_text.delta").put("item_id", cur!!.optString("id"))
                            .put("output_index", cur!!.optInt("idx")).put("content_index", 0).put("delta", "."))
                    }

                    // 工具调用：按 index 匹配分片（后续分片可能没有 id，只有 index），不再按 id 断流
                    val tcs = delta.optJSONArray("tool_calls")
                    if (tcs != null) {
                        if (firstDelta.compareAndSet(false, true)) appendLog(ctx, "adapter: 收到工具调用分片")
                        flushText()
                        for (k in 0 until tcs.length()) {
                            val tc = tcs.getJSONObject(k)
                            val idx = tc.optInt("index", -1).let { if (it >= 0) it else k }
                            val fnPart = tc.optJSONObject("function")
                            var f = fns[idx]
                            if (f == null) {
                                val oIdx = outputItems.length()
                                val name = fnPart?.optString("name").orEmpty()
                                val cid = tc.optString("id").ifEmpty { "call_$idx" }
                                f = JSONObject().put("id", "fc_$oIdx").put("idx", oIdx).put("call_id", cid).put("name", name).put("args", "")
                                fns[idx] = f
                                sse(out, JSONObject().put("type", "response.output_item.added").put("output_index", oIdx)
                                    .put("item", JSONObject().put("id", f.optString("id")).put("type", "function_call").put("status", "in_progress")
                                        .put("call_id", cid).put("name", name).put("arguments", "")))
                            }
                            val ff = f!!
                            val arg = fnPart?.optString("arguments").orEmpty()
                            if (arg.isNotEmpty()) {
                                ff.put("args", ff.optString("args") + arg)
                                sse(out, JSONObject().put("type", "response.function_call_arguments.delta")
                                    .put("item_id", ff.optString("id")).put("output_index", ff.optInt("idx")).put("delta", arg))
                            }
                        }
                    }

                    val fr = ch.opt("finish_reason")
                    if (fr != null && fr != JSONObject.NULL && fr.toString() != "null") {
                        flushText()
                        flushFunctions()
                    }
                }
            } catch (e: Exception) {
                lastError = "上游流中断：${e.message ?: e.javaClass.simpleName}"
                lastOk = false
                appendLog(ctx, "adapter: 上游流中断 ${lastError}")
                try {
                    sse(out, JSONObject().put("type", "error").put("message", "转接头上游中断：${e.message ?: e.javaClass.simpleName}"))
                } catch (_: Exception) {}
            }
            try { firstChunkTimer.cancel() } catch (_: Exception) {}
            try { flushText() } catch (_: Exception) {}
            try { flushFunctions() } catch (_: Exception) {}
            // 上游 200 但整个流没有任何内容：明确报错而不是静默结束（否则引擎无输出被 App 看门狗误杀）
            if (outputItems.length() == 0 && !gotFirst.get() && lastError == null) {
                lastError = "DeepSeek 返回 200 但未产生任何内容"
                lastOk = false
                appendLog(ctx, "adapter: DeepSeek 返回 200 但无任何内容，已向引擎报错")
                try {
                    sse(out, JSONObject().put("type", "error").put("message", "DeepSeek 返回 200 但未产生任何内容，请稍后重试或换用 deepseek-chat 模型"))
                } catch (_: Exception) {}
            }
            try {
                sse(out, JSONObject().put("type", "response.completed").put("response", JSONObject()
                    .put("id", rid).put("object", "response").put("status", "completed").put("model", req.optString("model"))
                    .put("output", outputItems)
                    .put("usage", JSONObject().put("input_tokens", inputTokens).put("output_tokens", outputTokens).put("total_tokens", inputTokens + outputTokens))))
                out.flush()
                appendLog(ctx, "adapter: 流结束，共 ${outputItems.length()} 个输出项，首分片=${if (firstDelta.get()) "已收到" else "无"}")
            } catch (_: Exception) {}
        }
    }

    private fun appendLog(ctx: Context, msg: String) {
        CodexEngine.appendRunLog(ctx, "\n[转接头] $msg\n")
    }
}
