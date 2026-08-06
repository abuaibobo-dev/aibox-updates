package com.aibox.app

import android.content.Context
import android.os.Handler
import android.os.Looper
import java.io.File
import java.util.Date

/**
 * 主线程看门狗：每 2 秒向主线程投递一次心跳。
 * 若超过 4 秒主线程仍未处理，判定为疑似卡死（ANR 前兆），
 * 立刻把主线程堆栈写入 crash.log，下次启动时主页弹窗展示，便于精确定位而不是瞎猜。
 */
object AnrWatchdog {

    private const val HEARTBEAT_MS = 2000L
    private const val ANR_MS = 4000L

    @Volatile private var lastHeartbeat = 0L
    @Volatile private var installed = false

    fun install(ctx: Context) {
        synchronized(this) {
            if (installed) return
            installed = true
        }
        val main = Handler(Looper.getMainLooper())
        Thread({
            while (true) {
                val sent = System.currentTimeMillis()
                main.post { lastHeartbeat = System.currentTimeMillis() }
                try { Thread.sleep(HEARTBEAT_MS) } catch (_: InterruptedException) { break }
                val now = System.currentTimeMillis()
                // 主线程超过 ANR_MS 没处理我们的心跳 => 卡死
                if (lastHeartbeat < sent && now - sent > ANR_MS) {
                    val stack = Looper.getMainLooper().thread.stackTrace
                        .joinToString("\n") { "    at $it" }
                    runCatching {
                        File(ctx.filesDir, "crash.log").appendText(
                            "\n[ANR-WATCHDOG] " + Date() + " 主线程疑似卡死(>${ANR_MS}ms)\n" + stack + "\n"
                        )
                    }
                }
            }
        }, "anr-watchdog").start()
    }
}
