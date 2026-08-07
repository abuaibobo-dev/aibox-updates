package com.aibox.app

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationCompat

/** 定时提醒：AlarmManager 触发后发系统通知，点击回到 App */
class ReminderReceiver : BroadcastReceiver() {
    override fun onReceive(ctx: Context, intent: Intent) {
        val text = intent.getStringExtra("text") ?: "定时提醒"
        try {
            val nm = ctx.getSystemService(Context.NOTIFICATION_SERVICE) as android.app.NotificationManager
            val pi = PendingIntent.getActivity(
                ctx, 0, Intent(ctx, MainActivity::class.java),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
            val n = NotificationCompat.Builder(ctx, "chat")
                .setSmallIcon(R.drawable.ic_bolt)
                .setContentTitle("⏰ 定时提醒")
                .setContentText(text)
                .setAutoCancel(true)
                .setContentIntent(pi)
                .build()
            nm.notify(1002, n)
        } catch (_: Exception) { }
    }
}
