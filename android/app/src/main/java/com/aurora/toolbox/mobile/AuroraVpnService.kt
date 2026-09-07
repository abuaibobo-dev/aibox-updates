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
        val config = runCatching { ProxyConfig.parse(raw) }.getOrElse {
            setState(false, "连接失败：${it.message}")
            stopSelf()
            return
        }

        tun = Builder()
            .setSession("极光 SOCKS5 全局代理")
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
        val yaml = buildString {
            appendLine("misc:")
            appendLine("  task-stack-size: 24576")
            appendLine("tunnel:")
            appendLine("  mtu: 1500")
            appendLine("  icmp: 'reply'")
            appendLine("socks5:")
            appendLine("  address: '${config.host.yaml()}'")
            appendLine("  port: ${config.port}")
            appendLine("  udp: 'udp'")
            if (config.username.isNotEmpty()) appendLine("  username: '${config.username.yaml()}'")
            if (config.password.isNotEmpty()) appendLine("  password: '${config.password.yaml()}'")
            appendLine("mapdns:")
            appendLine("  address: 1.1.1.1")
            appendLine("  port: 53")
            appendLine("  network: 240.0.0.0")
            appendLine("  netmask: 240.0.0.0")
            appendLine("  cache-size: 10000")
        }
        val configFile = File(cacheDir, "hev-socks5-tunnel.yml").apply { writeText(yaml) }
        if (TProxyService.TProxyStartService(configFile.absolutePath, descriptor.fd)) {
            setState(true, "已连接 ${config.host}:${config.port}")
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
            NotificationChannel(CHANNEL_ID, "极光代理", NotificationManager.IMPORTANCE_LOW)
        )
        val openApp = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE
        )
        return Notification.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.stat_sys_warning)
            .setContentTitle("极光工作箱")
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
