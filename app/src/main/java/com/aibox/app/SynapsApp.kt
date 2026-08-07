package com.aibox.app

import android.app.Application

/** 应用入口：尽早安装崩溃处理器，连 Activity 构造阶段/后台线程的崩溃都能写入 crash.log */
class SynapsApp : Application() {
    override fun onCreate() {
        super.onCreate()
        CrashLog.install(this)
        AnrWatchdog.install(this)
    }
}
