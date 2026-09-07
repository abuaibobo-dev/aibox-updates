package com.aurora.toolbox.mobile

import android.net.Uri
import android.util.Base64

data class ProxyConfig(
    val host: String,
    val port: Int,
    val username: String = "",
    val password: String = ""
) {
    companion object {
        fun parse(rawValue: String): ProxyConfig {
            var raw = rawValue.trim()
            raw = raw.substringBefore('{').removeSuffix("proxy").trim()
            if (raw.startsWith("socks://", true)) {
                val payload = raw.substringAfter("://").substringBefore('#')
                    .replace('-', '+').replace('_', '/')
                val padded = payload + "=".repeat((4 - payload.length % 4) % 4)
                raw = "socks5://" + String(Base64.decode(padded, Base64.DEFAULT))
            }
            if (raw.startsWith("socks5h://", true)) raw = "socks5://" + raw.substringAfter("://")
            raw = raw.replace(Regex("@\\[(\\d{1,3}(?:\\.\\d{1,3}){3})]"), "@$1")
            val uri = Uri.parse(raw)
            require(uri.scheme.equals("socks5", true)) { "不是可识别的 SOCKS 节点" }
            val host = uri.host?.trim('[', ']')?.takeIf { it.isNotBlank() }
                ?: error("缺少服务器地址")
            val port = uri.port.takeIf { it in 1..65535 } ?: error("端口无效")
            val userInfo = uri.encodedUserInfo.orEmpty().split(':', limit = 2)
            return ProxyConfig(
                host,
                port,
                Uri.decode(userInfo.getOrElse(0) { "" }),
                Uri.decode(userInfo.getOrElse(1) { "" })
            )
        }
    }
}
