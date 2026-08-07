package com.aibox.app

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.IBinder
import android.os.PowerManager
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat

/**
 * 保活前台服务：
 * - 回复期间：通知栏"Synaps 正在回复…" + 持有 PARTIAL_WAKE_LOCK，
 *   防止切后台后进程被系统冻结/回收导致回复中断。
 * - 后台保活（设置里开启）：通知常驻，进程保持前台优先级，切走不报错。
 * START_STICKY：进程被杀后系统会尝试重启服务。
 */
class ChatForegroundService : Service() {

    private var wakeLock: PowerManager.WakeLock? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        createChannel()
        val keepalive = getSharedPreferences("settings", MODE_PRIVATE).getBoolean("keepalive", false)
        val n: Notification = NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_chat)
            .setContentTitle(if (keepalive) "Synaps 后台保活中" else "Synaps 正在回复…")
            .setContentText(if (keepalive) "通知常驻，可在设置中关闭" else "回复完成后通知自动消失")
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()
        startForeground(1001, n)
        // 回复模式需要 CPU 持续工作；纯保活模式不持锁省电
        if (keepalive) {
            releaseWakeLock()
        } else {
            acquireWakeLock()
        }
        return START_STICKY
    }

    private fun acquireWakeLock() {
        if (wakeLock == null) {
            val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
            wakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "synaps:chat").apply {
                setReferenceCounted(false)
                acquire()
            }
        }
    }

    private fun releaseWakeLock() {
        wakeLock?.let { if (it.isHeld) it.release() }
        wakeLock = null
    }

    override fun onDestroy() {
        releaseWakeLock()
        super.onDestroy()
    }

    private fun createChannel() {
        val mgr = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        val ch = NotificationChannel(CHANNEL_ID, "对话回复", NotificationManager.IMPORTANCE_LOW)
        mgr.createNotificationChannel(ch)
    }

    companion object {
        private const val CHANNEL_ID = "chat_reply"

        fun start(ctx: Context) {
            try {
                ContextCompat.startForegroundService(ctx, Intent(ctx, ChatForegroundService::class.java))
            } catch (_: Exception) {}
        }

        fun stop(ctx: Context) {
            try { ctx.stopService(Intent(ctx, ChatForegroundService::class.java)) } catch (_: Exception) {}
        }
    }
}
