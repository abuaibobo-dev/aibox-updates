package com.aurora.toolbox.mobile

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.net.VpnService
import android.os.ParcelFileDescriptor
import hev.sockstun.TProxyService
import java.io.File
import java.net.InetSocketAddress
import java.net.Socket

class AuroraVpnService : VpnService() {
    private var tun: ParcelFileDescriptor? = null
    private var singBox: Process? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_DISCONNECT) {
            stopTunnel()
            return START_NOT_STICKY
        }
        runCatching {
            startForeground(NOTIFICATION_ID, notification("正在连接代理…"))
            Thread { runCatching { startTunnel() }.onFailure { failSafely(it) } }.start()
        }.onFailure { failSafely(it) }
        return START_NOT_STICKY
    }

    private fun startTunnel() {
        if (tun != null) return
        @Suppress("DEPRECATION")
        val prefs = getSharedPreferences(PREFS, Context.MODE_MULTI_PROCESS)
        val raw = prefs.getString(KEY_NODE, "").orEmpty()
        val profile = runCatching { SingBoxConfig.parse(raw) }.getOrElse {
            setState(false, "连接失败：${it.message}")
            stopSelf()
            return
        }

        if (profile.protocol == "socks5") {
            val check = ProxyProbe.check(ProxyConfig.parse(raw))
            if (check != null) {
                setState(false, "连接失败：$check")
                stopForeground(STOP_FOREGROUND_REMOVE)
                stopSelf()
                return
            }
            setState(false, "节点验证成功，正在建立 VPN…")
        }

        tun = Builder()
            .setSession("ORVYN 智能代理")
            .setBlocking(false)
            .setMtu(8500)
            .addAddress("198.18.0.1", 32)
            .addRoute("0.0.0.0", 0)
            .addDnsServer("198.18.0.2")
            .addDisallowedApplication(packageName)
            .establish()

        val descriptor = tun ?: run {
            setState(false, "未获得 Android VPN 权限")
            stopSelf()
            return
        }
        val socksTarget = if (profile.protocol == "socks5") {
            ProxyConfig.parse(raw)
        } else {
            val executable = File(applicationInfo.nativeLibraryDir, "libsingbox.so")
            if (!executable.exists()) {
                setState(false, "当前 CPU 架构缺少 sing-box 内核")
                runCatching { descriptor.close() }
                tun = null
                stopSelf()
                return
            }
            val singConfig = File(filesDir, "sing-box.json").apply {
                writeText(SingBoxConfig.fullConfig(profile))
            }
            singBox = ProcessBuilder(executable.absolutePath, "run", "-c", singConfig.absolutePath)
                .redirectErrorStream(true)
                .redirectOutput(File(cacheDir, "sing-box.log"))
                .start()
            ProxyConfig("127.0.0.1", 20808)
        }
        val yaml = buildString {
            appendLine("misc:")
            appendLine("  task-stack-size: 81920")
            appendLine("tunnel:")
            appendLine("  mtu: 8500")
            appendLine("  icmp: 'reply'")
            appendLine("socks5:")
            appendLine("  address: '${socksTarget.host.yaml()}'")
            appendLine("  port: ${socksTarget.port}")
            appendLine("  udp: 'udp'")
            if (socksTarget.username.isNotEmpty()) appendLine("  username: '${socksTarget.username.yaml()}'")
            if (socksTarget.password.isNotEmpty()) appendLine("  password: '${socksTarget.password.yaml()}'")
            appendLine("mapdns:")
            appendLine("  address: 1.1.1.1")
            appendLine("  port: 53")
            appendLine("  network: 240.0.0.0")
            appendLine("  netmask: 240.0.0.0")
            appendLine("  cache-size: 10000")
        }
        val configFile = File(cacheDir, "hev-socks5-tunnel.yml").apply { writeText(yaml) }
        val started = runCatching { TProxyService.TProxyStartService(configFile.absolutePath, descriptor.fd) }.getOrElse {
            failSafely(it); return
        }
        if (started) {
            setState(true, "${profile.protocol.uppercase()} 已连接 ${profile.host}:${profile.port}")
            val manager = getSystemService(NotificationManager::class.java)
            manager.notify(NOTIFICATION_ID, notification("全局代理已连接"))
        } else {
            setState(false, "代理内核启动失败")
            runCatching { descriptor.close() }
            tun = null
            stopSelf()
        }
    }

    private fun failSafely(error: Throwable) {
        val safeName = error.javaClass.simpleName
        val safeMessage = error.message?.take(180).orEmpty()
        setState(false, "连接失败：$safeName${if (safeMessage.isNotBlank()) " · $safeMessage" else ""}")
        if (tun != null) runCatching { TProxyService.TProxyStopService() }
        runCatching { singBox?.destroy() }; singBox = null
        runCatching { tun?.close() }; tun = null
        runCatching { stopForeground(STOP_FOREGROUND_REMOVE) }
        stopSelf()
    }

    private fun stopTunnel() {
        if (tun != null) runCatching { TProxyService.TProxyStopService() }
        singBox?.destroy()
        singBox = null
        runCatching { tun?.close() }
        tun = null
        setState(false, "已断开")
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    override fun onRevoke() {
        stopTunnel()
        super.onRevoke()
    }

    override fun onDestroy() {
        if (tun != null) stopTunnel()
        super.onDestroy()
    }

    private fun notification(message: String): Notification {
        val manager = getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(
            NotificationChannel(CHANNEL_ID, "ORVYN 代理", NotificationManager.IMPORTANCE_LOW)
        )
        val openApp = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE
        )
        return Notification.Builder(this, CHANNEL_ID)
            .setSmallIcon(com.aurora.toolbox.mobile.R.drawable.ic_status)
            .setContentTitle("ORVYN")
            .setContentText(message)
            .setContentIntent(openApp)
            .setOngoing(true)
            .build()
    }

    private fun setState(connected: Boolean, message: String) {
        @Suppress("DEPRECATION")
        getSharedPreferences(PREFS, Context.MODE_MULTI_PROCESS).edit()
            .putBoolean(KEY_CONNECTED, connected)
            .putString(KEY_STATUS, message)
            .apply()
    }

    private fun String.yaml() = replace("'", "''")

    companion object {
        const val ACTION_CONNECT = "com.aurora.toolbox.mobile.CONNECT"
        const val ACTION_DISCONNECT = "com.aurora.toolbox.mobile.DISCONNECT"
        const val PREFS = "proxy"
        const val KEY_NODE = "last"
        const val KEY_CONNECTED = "connected"
        const val KEY_STATUS = "status"
        private const val CHANNEL_ID = "aurora_proxy"
        private const val NOTIFICATION_ID = 208
    }
}

private object ProxyProbe {
    fun check(config: ProxyConfig): String? = runCatching {
        Socket().use { socket ->
            socket.connect(InetSocketAddress(config.host, config.port), 7000)
            socket.soTimeout = 7000
            val input = socket.getInputStream()
            val output = socket.getOutputStream()
            val useAuth = config.username.isNotEmpty()
            output.write(if (useAuth) byteArrayOf(5, 1, 2) else byteArrayOf(5, 1, 0)); output.flush()
            require(input.read() == 5) { "SOCKS 握手响应无效" }
            val method = input.read()
            require(method != 0xFF) { "服务器不接受可用的认证方式" }
            if (method == 2) {
                val user = config.username.toByteArray(Charsets.UTF_8)
                val pass = config.password.toByteArray(Charsets.UTF_8)
                require(user.size <= 255 && pass.size <= 255) { "账号或密码过长" }
                output.write(byteArrayOf(1, user.size.toByte())); output.write(user)
                output.write(byteArrayOf(pass.size.toByte())); output.write(pass); output.flush()
                require(input.read() == 1 && input.read() == 0) { "SOCKS 用户名或密码错误" }
            } else require(method == 0) { "服务器返回未知认证方式：$method" }
            output.write(byteArrayOf(5, 1, 0, 1, 1, 1, 1, 1, 1, -69)); output.flush()
            require(input.read() == 5) { "SOCKS CONNECT 响应无效" }
            val reply = input.read()
            require(reply == 0) { "代理服务器拒绝转发（代码 $reply）" }
        }
        null
    }.getOrElse { error -> when (error) {
        is java.net.SocketTimeoutException -> "服务器连接超时"
        is java.net.ConnectException -> "服务器端口不可达"
        else -> error.message ?: error.javaClass.simpleName
    } }
}
