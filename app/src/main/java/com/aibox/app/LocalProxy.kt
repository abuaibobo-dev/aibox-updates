package com.aibox.app

import java.io.BufferedReader
import java.io.InputStream
import java.io.InputStreamReader
import java.io.OutputStream
import java.net.HttpURLConnection
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.URL

/**
 * 本地 HTTP/HTTPS 代理：跑在 App 进程内（bionic libc，DNS 走系统 netd）。
 * 引擎的 python/curl/wget/pip 把 http_proxy/https_proxy 指到这里后，
 * 域名解析由 App 进程完成，彻底绕开沙盒内 musl 工具读不到 /etc/resolv.conf 的 DNS 限制。
 */
object LocalProxy {
    @Volatile private var server: ServerSocket? = null
    val port: Int get() = server?.localPort ?: 0

    @Synchronized
    fun start(): Int {
        val s = server
        if (s != null && !s.isClosed) return s.localPort
        val ss = ServerSocket(0, 64, InetAddress.getByName("127.0.0.1"))
        server = ss
        Thread({ acceptLoop(ss) }, "local-proxy").apply { isDaemon = true; start() }
        return ss.localPort
    }

    private fun acceptLoop(ss: ServerSocket) {
        while (!ss.isClosed) {
            try {
                val c = ss.accept()
                Thread({ handle(c) }, "proxy-conn").apply { isDaemon = true; start() }
            } catch (_: Exception) { break }
        }
    }

    private fun handle(c: Socket) {
        try {
            c.soTimeout = 300000
            val reader = BufferedReader(InputStreamReader(c.getInputStream(), Charsets.ISO_8859_1))
            val line = reader.readLine() ?: return
            val parts = line.split(" ")
            if (parts.size < 3) { c.close(); return }
            val method = parts[0]
            val target = parts[1]
            val host = parseHost(reader)
            if (method.equals("CONNECT", true)) {
                tunnel(c, target)
            } else {
                forward(c, method, target, host, reader)
            }
        } catch (_: Exception) {
            try { c.close() } catch (_: Exception) {}
        }
    }

    private fun parseHost(reader: BufferedReader): String {
        var host = ""
        try {
            while (true) {
                val h = reader.readLine() ?: break
                if (h.isEmpty()) break
                val i = h.indexOf(':')
                if (i > 0 && h.substring(0, i).trim().equals("Host", true)) {
                    host = h.substring(i + 1).trim()
                }
            }
        } catch (_: Exception) {}
        return host
    }

    /** HTTPS CONNECT 隧道：App 进程连目标（DNS 走 netd），双向转发字节 */
    private fun tunnel(c: Socket, target: String) {
        val hp = target.split(":")
        val name = hp[0]
        val pt = hp.getOrNull(1)?.toIntOrNull() ?: 443
        val up = Socket()
        try {
            up.connect(InetSocketAddress(InetAddress.getByName(name), pt), 20000)
        } catch (e: Exception) {
            try { c.getOutputStream().write("HTTP/1.1 502 Bad Gateway\r\n\r\n".toByteArray()) } catch (_: Exception) {}
            c.close(); return
        }
        try { c.getOutputStream().write("HTTP/1.1 200 Connection established\r\n\r\n".toByteArray()) } catch (_: Exception) { up.close(); c.close(); return }
        // 关键：泵线程必须阻塞等待，任一方向 EOF 时泵线程会关闭两端 socket。
        // 若提前关闭 socket，客户端 TLS 握手会立即 EOF（SSLEOFError）。
        val t1 = pump(c.getInputStream(), up.getOutputStream(), c, up)
        val t2 = pump(up.getInputStream(), c.getOutputStream(), c, up)
        try { t1.join() } catch (_: InterruptedException) {}
        try { t2.join() } catch (_: InterruptedException) {}
        try { up.close() } catch (_: Exception) {}
        try { c.close() } catch (_: Exception) {}
    }

    private fun pump(src: InputStream, dst: OutputStream, a: Socket, b: Socket): Thread {
        val t = Thread {
            try {
                val buf = ByteArray(16384)
                while (true) {
                    val n = src.read(buf)
                    if (n < 0) break
                    dst.write(buf, 0, n)
                    dst.flush()
                }
            } catch (_: Exception) {}
            try { b.close() } catch (_: Exception) {}
            try { a.close() } catch (_: Exception) {}
        }
        t.isDaemon = true
        t.start()
        return t
    }

    /** 普通 HTTP 转发：用 HttpURLConnection（App 进程 bionic，DNS 走 netd） */
    private fun forward(c: Socket, method: String, target: String, hostHeader: String, reader: BufferedReader) {
        val url = if (target.startsWith("http://") || target.startsWith("https://")) target else "http://$target"
        val conn = URL(url).openConnection() as HttpURLConnection
        try {
            conn.requestMethod = method.ifEmpty { "GET" }
            conn.connectTimeout = 20000
            conn.readTimeout = 300000
            conn.instanceFollowRedirects = false
            conn.setRequestProperty("Host", hostHeader.ifEmpty { URL(url).host })
            // 转发请求头（除 Proxy-* 和 Host）
            val headers = mutableListOf<Pair<String, String>>()
            try {
                var h = reader.readLine()
                while (!h.isNullOrEmpty()) {
                    val i = h.indexOf(':')
                    if (i > 0) {
                        val k = h.substring(0, i).trim()
                        val v = h.substring(i + 1).trim()
                        if (!k.startsWith("Proxy-", true) && !k.equals("Host", true)) headers.add(k to v)
                    }
                    h = reader.readLine()
                }
            } catch (_: Exception) {}
            headers.forEach { (k, v) -> conn.setRequestProperty(k, v) }
            if (method.equals("POST", true) || method.equals("PUT", true) || method.equals("PATCH", true)) {
                conn.doOutput = true
                val cl = conn.getRequestProperty("Content-Length")?.toIntOrNull()
                if (cl != null && cl > 0) {
                    val body = CharArray(cl)
                    var off = 0
                    while (off < cl) {
                        val n = reader.read(body, off, cl - off)
                        if (n < 0) break
                        off += n
                    }
                    conn.outputStream.use { it.write(String(body, 0, off).toByteArray(Charsets.UTF_8)) }
                }
            }
            val code = conn.responseCode
            val resp = if (code in 200..399) conn.inputStream else conn.errorStream
            val out = c.getOutputStream()
            val head = StringBuilder("HTTP/1.1 $code ${conn.responseMessage ?: ""}\r\n")
            conn.headerFields.forEach { (k, v) ->
                if (k != null && v.isNotEmpty()) head.append("$k: ${v.joinToString(", ")}\r\n")
            }
            head.append("Proxy-Agent: Synaps-LocalProxy/1.0\r\n\r\n")
            out.write(head.toString().toByteArray(Charsets.ISO_8859_1))
            if (resp != null) resp.use { input -> val buf = ByteArray(16384); while (true) { val n = input.read(buf); if (n < 0) break; out.write(buf, 0, n) } }
            out.flush()
        } catch (e: Exception) {
            try {
                val out = c.getOutputStream()
                out.write("HTTP/1.1 502 Bad Gateway\r\nContent-Length: 0\r\n\r\n".toByteArray())
                out.flush()
            } catch (_: Exception) {}
        } finally {
            conn.disconnect()
            try { c.close() } catch (_: Exception) {}
        }
    }
}
