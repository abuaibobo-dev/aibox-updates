package com.aibox.app

import android.content.Context
import android.os.Handler
import android.os.Looper
import java.io.File
import java.util.Date

/**
 * 主线程看门狗：主线程每收到一次心跳就刷新 lastHeartbeat。
 * 每 2 秒检查一次：若主线程已超过 4 秒没处理心跳，判定为疑似卡死（ANR 前兆），
 * 立刻把主线程堆栈写入 crash.log（最多每 10 秒写一次），
 * 下次启动时主页弹窗展示，用于精确定位卡死位置。
 */
object AnrWatchdog {

    private const val HEARTBEAT_MS = 2000L
    private const val ANR_MS = 4000L
    private const val DUMP_INTERVAL_MS = 10000L

    @Volatile private var lastHeartbeat = 0L
    @Volatile private var lastDumpAt = 0L
    @Volatile private var installed = false

    fun install(ctx: Context) {
        synchronized(this) {
            if (installed) return
            installed = true
        }
        val main = Handler(Looper.getMainLooper())
        lastHeartbeat = System.currentTimeMillis()
        main.post { lastHeartbeat = System.currentTimeMillis() }
        Thread({
            while (true) {
                try { Thread.sleep(HEARTBEAT_MS) } catch (_: InterruptedException) { break }
                val now = System.currentTimeMillis()
                // 主线程超过 ANR_MS 没处理心跳 => 卡死
                if (now - lastHeartbeat > ANR_MS && now - lastDumpAt > DUMP_INTERVAL_MS) {
                    lastDumpAt = now
                    val stack = Looper.getMainLooper().thread.stackTrace
                        .joinToString("\n") { "    at $it" }
                    runCatching {
                        File(ctx.filesDir, "crash.log").appendText(
                            "\n[ANR-WATCHDOG] " + Date() + " 主线程疑似卡死(>${ANR_MS}ms)\n" + stack + "\n"
                        )
                    }
                }
                main.post { lastHeartbeat = System.currentTimeMillis() }
            }
        }, "anr-watchdog").start()
    }
}
