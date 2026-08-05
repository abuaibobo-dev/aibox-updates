package com.aibox.app

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.IBinder
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat

/**
 * 回复期间的保活前台服务：通知栏常驻"Synaps 正在回复…"，
 * 防止切后台后进程被系统冻结/回收导致回复中断。
 */
class ChatForegroundService : Service() {

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        createChannel()
        val n: Notification = NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_chat)
            .setContentTitle("Synaps 正在回复…")
            .setContentText("请稍候，回复完成后通知自动消失")
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()
        startForeground(1001, n)
        return START_NOT_STICKY
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
