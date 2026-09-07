package com.aurora.toolbox.mobile

import android.net.Uri
import android.util.Base64
import org.json.JSONArray
import org.json.JSONObject

data class ProxyProfile(val protocol: String, val host: String, val port: Int, val outbound: JSONObject?, val name: String = "")

object SingBoxConfig {
    fun parse(rawValue: String): ProxyProfile {
        val raw = rawValue.trim().substringBefore('{').removeSuffix("proxy").trim()
            .replace(Regex("@\\[(\\d{1,3}(?:\\.\\d{1,3}){3})]"), "@$1")
        return when (raw.substringBefore(':').lowercase()) {
            "socks", "socks5", "socks5h" -> ProxyConfig.parse(raw).let { ProxyProfile("socks5", it.host, it.port, null) }
            "vmess" -> vmess(raw.removePrefix("vmess://"))
            "vless" -> standardUri(raw, "vless")
            "trojan" -> standardUri(raw, "trojan")
            "ss" -> shadowsocks(raw)
            "http", "https" -> http(raw)
            "hysteria", "hysteria2", "hy2" -> advancedUri(raw, "hysteria2")
            "tuic" -> advancedUri(raw, "tuic")
            "anytls" -> advancedUri(raw, "anytls")
            "ssh" -> advancedUri(raw, "ssh")
            "shadowtls" -> advancedUri(raw, "shadowtls")
            "naive" -> advancedUri(raw, "naive")
            "snell" -> advancedUri(raw, "snell")
            "wireguard", "wg" -> error("已识别 WireGuard；请导入标准 sing-box JSON 配置")
            else -> error("不支持的节点格式")
        }
    }

    fun fullConfig(profile: ProxyProfile): String {
        require(profile.outbound != null) { "SOCKS5 不需要 sing-box 转换" }
        return JSONObject()
            .put("log", JSONObject().put("level", "info").put("timestamp", true))
            .put("inbounds", JSONArray().put(JSONObject()
                .put("type", "socks").put("tag", "local-in")
                .put("listen", "127.0.0.1").put("listen_port", 20808)))
            .put("outbounds", JSONArray().put(profile.outbound.put("tag", "proxy")))
            .put("route", JSONObject().put("final", "proxy").put("auto_detect_interface", true))
            .toString(2)
    }

    private fun vmess(data: String): ProxyProfile {
        val json = JSONObject(String(decodeBase64(data)))
        val host = json.getString("add")
        val port = json.getString("port").toInt()
        val outbound = JSONObject().put("type", "vmess").put("server", host).put("server_port", port)
            .put("uuid", json.getString("id")).put("security", json.optString("scy", "auto"))
            .put("alter_id", json.optString("aid", "0").toInt())
        addTransport(outbound, json.optString("net"), json.optString("path"), json.optString("host"))
        if (json.optString("tls") == "tls") outbound.put("tls", JSONObject().put("enabled", true).put("server_name", json.optString("sni", host)))
        return ProxyProfile("vmess", host, port, outbound)
    }

    private fun standardUri(raw: String, type: String): ProxyProfile {
        val uri = Uri.parse(raw)
        val host = uri.host?.trim('[', ']') ?: error("缺少服务器地址")
        val port = uri.port.takeIf { it in 1..65535 } ?: error("端口无效")
        val credential = Uri.decode(uri.encodedUserInfo.orEmpty()).substringBefore(':')
        require(credential.isNotBlank()) { "缺少认证信息" }
        val outbound = JSONObject().put("type", type).put("server", host).put("server_port", port)
        if (type == "vless") outbound.put("uuid", credential) else outbound.put("password", credential)
        uri.getQueryParameter("flow")?.takeIf { it.isNotBlank() }?.let { outbound.put("flow", it) }
        val transport = uri.getQueryParameter("type").orEmpty()
        addTransport(outbound, transport, uri.getQueryParameter("path").orEmpty(), uri.getQueryParameter("host").orEmpty())
        val security = uri.getQueryParameter("security").orEmpty()
        if (security == "tls" || security == "reality") {
            val tls = JSONObject().put("enabled", true).put("server_name", uri.getQueryParameter("sni") ?: host)
            uri.getQueryParameter("fp")?.takeIf { it.isNotBlank() }?.let { tls.put("utls", JSONObject().put("enabled", true).put("fingerprint", it)) }
            if (security == "reality") tls.put("reality", JSONObject().put("enabled", true)
                .put("public_key", uri.getQueryParameter("pbk").orEmpty()).put("short_id", uri.getQueryParameter("sid").orEmpty()))
            outbound.put("tls", tls)
        }
        return ProxyProfile(type, host, port, outbound, Uri.decode(uri.fragment.orEmpty()))
    }

    private fun shadowsocks(raw: String): ProxyProfile {
        var body = raw.removePrefix("ss://").substringBefore('#')
        if (!body.contains('@')) body = String(decodeBase64(body))
        val userPart = body.substringBeforeLast('@')
        val serverPart = body.substringAfterLast('@')
        val credentials = if (userPart.contains(':')) userPart else String(decodeBase64(userPart))
        val method = Uri.decode(credentials.substringBefore(':'))
        val password = Uri.decode(credentials.substringAfter(':'))
        val host = serverPart.substringBeforeLast(':').trim('[', ']')
        val port = serverPart.substringAfterLast(':').substringBefore('?').toInt()
        val outbound = JSONObject().put("type", "shadowsocks").put("server", host).put("server_port", port)
            .put("method", method).put("password", password)
        return ProxyProfile("ss", host, port, outbound, Uri.decode(raw.substringAfter('#', "")))
    }

    private fun http(raw: String): ProxyProfile {
        val uri = Uri.parse(raw)
        val host = uri.host?.trim('[', ']') ?: error("缺少服务器地址")
        val port = uri.port.takeIf { it in 1..65535 } ?: if (uri.scheme == "https") 443 else 80
        val parts = Uri.decode(uri.encodedUserInfo.orEmpty()).split(':', limit = 2)
        val outbound = JSONObject().put("type", "http").put("server", host).put("server_port", port)
        if (parts.firstOrNull().orEmpty().isNotBlank()) outbound.put("username", parts[0])
        if (parts.size > 1) outbound.put("password", parts[1])
        if (uri.scheme == "https") outbound.put("tls", JSONObject().put("enabled", true).put("server_name", host))
        return ProxyProfile("http", host, port, outbound, Uri.decode(uri.fragment.orEmpty()))
    }

    private fun advancedUri(raw: String, type: String): ProxyProfile {
        val uri = Uri.parse(raw)
        val host = uri.host?.trim('[', ']') ?: error("缺少服务器地址")
        val port = uri.port.takeIf { it in 1..65535 } ?: error("端口无效")
        val userInfo = Uri.decode(uri.encodedUserInfo.orEmpty()).split(':', limit = 2)
        val secret = userInfo.firstOrNull().orEmpty()
        require(secret.isNotBlank()) { "缺少认证信息" }
        val outbound = JSONObject().put("type", type).put("server", host).put("server_port", port)
        when (type) {
            "tuic" -> { outbound.put("uuid", secret); outbound.put("password", userInfo.getOrElse(1) { uri.getQueryParameter("password").orEmpty() }) }
            "ssh" -> { outbound.put("user", secret); userInfo.getOrNull(1)?.let { outbound.put("password", it) } }
            else -> outbound.put("password", secret)
        }
        if (type in listOf("hysteria2", "tuic", "anytls", "shadowtls", "naive")) {
            val tls = JSONObject().put("enabled", true).put("server_name", uri.getQueryParameter("sni") ?: host)
            if (uri.getQueryParameter("insecure") == "1" || uri.getQueryParameter("allowInsecure") == "1") tls.put("insecure", true)
            outbound.put("tls", tls)
        }
        uri.getQueryParameter("obfs")?.let { outbound.put("obfs", JSONObject().put("type", it).put("password", uri.getQueryParameter("obfs-password").orEmpty())) }
        return ProxyProfile(type, host, port, outbound, Uri.decode(uri.fragment.orEmpty()))
    }

    private fun addTransport(outbound: JSONObject, network: String, path: String, host: String) {
        if (network == "ws") outbound.put("transport", JSONObject().put("type", "ws").put("path", path.ifBlank { "/" })
            .put("headers", JSONObject().apply { if (host.isNotBlank()) put("Host", host) }))
        if (network == "grpc") outbound.put("transport", JSONObject().put("type", "grpc").put("service_name", path.trim('/')))
        if (network == "http" || network == "h2") outbound.put("transport", JSONObject().put("type", "http")
            .put("path", path.ifBlank { "/" }).apply { if (host.isNotBlank()) put("host", JSONArray().put(host)) })
    }

    private fun decodeBase64(value: String): ByteArray {
        val normalized = value.substringBefore('?').replace('-', '+').replace('_', '/')
        val padded = normalized + "=".repeat((4 - normalized.length % 4) % 4)
        return Base64.decode(padded, Base64.DEFAULT)
    }
}
