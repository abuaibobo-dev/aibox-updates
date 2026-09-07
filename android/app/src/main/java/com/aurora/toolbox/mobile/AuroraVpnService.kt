package com.aurora.toolbox.mobile

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.net.VpnService
import android.os.ParcelFileDescriptor
import hev.htproxy.TProxyService
import java.io.File

class AuroraVpnService : VpnService() {
    private var tun: ParcelFileDescriptor? = null
    private var singBox: Process? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_DISCONNECT) {
            stopTunnel()
            return START_NOT_STICKY
        }
        startForeground(NOTIFICATION_ID, notification("正在连接代理…"))
        startTunnel()
        return START_STICKY
    }

    private fun startTunnel() {
        if (tun != null || TProxyService.TProxyIsRunning()) return
        val prefs = getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val raw = prefs.getString(KEY_NODE, "").orEmpty()
        val profile = runCatching { SingBoxConfig.parse(raw) }.getOrElse {
            setState(false, "连接失败：${it.message}")
            stopSelf()
            return
        }

        tun = Builder()
            .setSession("ORVYN 智能代理")
            .setMtu(1500)
            .addAddress("198.18.0.1", 15)
            .addRoute("0.0.0.0", 0)
            .addDnsServer("1.1.1.1")
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
            appendLine("  task-stack-size: 24576")
            appendLine("tunnel:")
            appendLine("  mtu: 1500")
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
        if (TProxyService.TProxyStartService(configFile.absolutePath, descriptor.fd)) {
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

    private fun stopTunnel() {
        if (TProxyService.TProxyIsRunning()) TProxyService.TProxyStopService()
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
        getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
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
