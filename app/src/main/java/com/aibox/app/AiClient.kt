package com.aibox.app

import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.util.concurrent.TimeUnit

object AiClient {
    private val client = OkHttpClient.Builder()
        .connectTimeout(20, TimeUnit.SECONDS)
        .readTimeout(180, TimeUnit.SECONDS)
        .build()

    /** 流式对话：逐段回调内容，最后回调整体文本与 usage */
    fun chatStream(p: Provider, messages: JSONArray, onDelta: (String) -> Unit, onDone: (String, JSONObject?) -> Unit, onError: (String) -> Unit) {
        val body = JSONObject().apply {
            put("model", p.model)
            put("messages", messages)
            put("stream", true)
        }.toString()
        request(p, body, object : Callback {
            override fun onDone(full: String, usage: JSONObject?) = onDone(full, usage)
            override fun onDelta(d: String) = onDelta(d)
            override fun onError(msg: String) = onError(msg)
        }, streaming = true)
    }

    /** 单次对话：一次性返回结果 */
    fun chatOnce(p: Provider, messages: JSONArray, onResult: (String, JSONObject?) -> Unit, onError: (String) -> Unit) {
        val body = JSONObject().apply {
            put("model", p.model)
            put("messages", messages)
            put("stream", false)
        }.toString()
        request(p, body, object : Callback {
            override fun onDone(full: String, usage: JSONObject?) = onResult(full, usage)
            override fun onDelta(d: String) {}
            override fun onError(msg: String) = onError(msg)
        }, streaming = false)
    }

    /** 测试连通性 */
    fun test(p: Provider, onOk: (String) -> Unit, onError: (String) -> Unit) {
        val messages = JSONArray().put(JSONObject().apply { put("role", "user"); put("content", "ping") })
        val body = JSONObject().apply {
            put("model", p.model); put("messages", messages); put("stream", false); put("max_tokens", 5)
        }.toString()
        request(p, body, object : Callback {
            override fun onDone(full: String, usage: JSONObject?) = onOk(full.take(80))
            override fun onDelta(d: String) {}
            override fun onError(msg: String) = onError(msg)
        }, streaming = false)
    }

    private interface Callback {
        fun onDelta(d: String)
        fun onDone(full: String, usage: JSONObject?)
        fun onError(msg: String)
    }

    private fun request(p: Provider, body: String, cb: Callback, streaming: Boolean) {
        val url = p.baseUrl.trimEnd('/') + "/chat/completions"
        val req = Request.Builder()
            .url(url)
            .header("Authorization", "Bearer ${p.apiKey}")
            .header("Content-Type", "application/json")
            .post(body.toRequestBody("application/json; charset=utf-8".toMediaType()))
            .build()
        Thread {
            try {
                client.newCall(req).execute().use { resp ->
                    if (!resp.isSuccessful) {
                        val err = resp.body?.string().orEmpty().take(300)
                        cb.onError("HTTP ${resp.code}：$err")
                        return@use
                    }
                    if (streaming) {
                        val source = resp.body!!.source()
                        val sb = StringBuilder()
                        var usage: JSONObject? = null
                        while (true) {
                            val line = source.readUtf8Line() ?: break
                            if (!line.startsWith("data:")) continue
                            val data = line.removePrefix("data:").trim()
                            if (data == "[DONE]") break
                            try {
                                val j = JSONObject(data)
                                if (j.has("usage")) usage = j.getJSONObject("usage")
                                val delta = j.optJSONArray("choices")?.optJSONObject(0)?.optJSONObject("delta")?.optString("content")
                                if (!delta.isNullOrEmpty()) { sb.append(delta); cb.onDelta(delta) }
                            } catch (_: Exception) {}
                        }
                        cb.onDone(sb.toString(), usage)
                    } else {
                        val j = JSONObject(resp.body!!.string())
                        val content = j.optJSONArray("choices")?.optJSONObject(0)?.optJSONObject("message")?.optString("content").orEmpty()
                        cb.onDone(content, j.optJSONObject("usage"))
                    }
                }
            } catch (e: IOException) {
                cb.onError(e.message ?: "网络错误")
            } catch (e: Exception) {
                cb.onError(e.message ?: "解析错误")
            }
        }.start()
    }
}
