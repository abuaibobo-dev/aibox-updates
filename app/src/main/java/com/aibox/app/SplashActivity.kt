package com.aibox.app

import android.content.Intent
import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity

/**
 * 启动页：不播视频、不等待，直接进入主页面。
 * 热启动（进程内已有主界面）同样直接进入，不重复加载。
 */
class SplashActivity : AppCompatActivity() {

    companion object {
        /** 进程内标记：MainActivity 已展示过（用户没大退）。 */
        @Volatile var enteredMain = false
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        go()
    }

    private fun go() {
        startActivity(Intent(this, MainActivity::class.java))
        finish()
        overridePendingTransition(android.R.anim.fade_in, android.R.anim.fade_out)
    }
}
