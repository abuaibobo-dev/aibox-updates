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
}
