package com.aibox.app

import android.content.Context
import android.util.Log
import java.io.File
import java.util.Date

object CrashLog {
    fun install(ctx: Context) {
        Thread.setDefaultUncaughtExceptionHandler { _, e ->
            try {
                val f = File(ctx.filesDir, "crash.log")
                f.appendText(Date().toString() + "\n" + e.stackTraceToString() + "\n\n")
            } catch (_: Exception) {}
            Log.e("AIToolbox", "未捕获异常", e)
        }
    }

    /** 取最后一次崩溃的堆栈（crash.log 按块追加，取最后一块） */
    fun lastCrash(ctx: Context): String? {
        return try {
            val f = File(ctx.filesDir, "crash.log")
            if (!f.exists()) return null
            val text = f.readText()
            val blocks = text.split("\n\n").filter { it.isNotBlank() }
            blocks.lastOrNull()?.take(900)
        } catch (_: Exception) { null }
    }
}
