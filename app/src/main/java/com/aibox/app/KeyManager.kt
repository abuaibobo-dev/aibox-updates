package com.aibox.app

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

data class Provider(
    val id: String,
    val name: String,
    val baseUrl: String,
    val apiKey: String,
    val model: String,
    val active: Boolean
)

object KeyManager {
    private const val PREFS = "keys"
    private const val DATA = "providers"

    fun defaultProvider() = Provider(
        id = "deepseek",
        name = "DeepSeek",
        baseUrl = "https://api.deepseek.com",
        apiKey = "",
        model = "deepseek-chat",
        active = true
    )

    fun providers(ctx: Context): MutableList<Provider> {
        val raw = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(DATA, null)
        if (raw == null) return mutableListOf(defaultProvider())
        return try {
            val arr = JSONArray(raw)
            (0 until arr.length()).map { i ->
                val o = arr.getJSONObject(i)
                Provider(
                    id = o.optString("id"),
                    name = o.optString("name"),
                    baseUrl = o.optString("baseUrl"),
                    apiKey = o.optString("apiKey"),
                    model = o.optString("model"),
                    active = o.optBoolean("active")
                )
            }.toMutableList()
        } catch (e: Exception) {
            mutableListOf(defaultProvider())
        }
    }

    fun save(ctx: Context, list: List<Provider>) {
        val arr = JSONArray()
        list.forEach { p ->
            arr.put(JSONObject().apply {
                put("id", p.id); put("name", p.name); put("baseUrl", p.baseUrl)
                put("apiKey", p.apiKey); put("model", p.model); put("active", p.active)
            })
        }
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putString(DATA, arr.toString()).apply()
    }

    fun activeProvider(ctx: Context): Provider {
        val list = providers(ctx)
        return list.firstOrNull { it.active } ?: list.first()
    }
}
