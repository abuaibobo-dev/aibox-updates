package com.aibox.app

import android.content.Intent
import android.media.MediaPlayer
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.widget.FrameLayout
import android.widget.VideoView
import androidx.appcompat.app.AppCompatActivity

class SplashActivity : AppCompatActivity() {

    private var moved = false
    private val handler = Handler(Looper.getMainLooper())

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_splash)

        val video = findViewById<VideoView>(R.id.videoSplash)
        video.setVideoURI(Uri.parse("android.resource://$packageName/${R.raw.splash_video}"))
        video.setOnPreparedListener { mp ->
            mp.isLooping = false
            mp.setVolume(0f, 0f)
            centerCrop(video, mp)
            video.start()
        }
        video.setOnCompletionListener { go() }
        video.setOnErrorListener { _, _, _ -> go(); true }
        // 3.5 秒后自动进入首页
        handler.postDelayed({ go() }, 3500)
    }

    override fun onDestroy() {
        handler.removeCallbacksAndMessages(null)
        super.onDestroy()
    }

    private fun centerCrop(video: VideoView, mp: MediaPlayer) {
        val vw = video.width
        val vh = video.height
        if (vw <= 0 || vh <= 0 || mp.videoWidth <= 0 || mp.videoHeight <= 0) return
        val vRatio = mp.videoWidth.toFloat() / mp.videoHeight.toFloat()
        val sRatio = vw.toFloat() / vh.toFloat()
        val lp = video.layoutParams as FrameLayout.LayoutParams
        if (vRatio > sRatio) {
            // 视频更宽：按屏幕高度铺满，宽度等比放大（裁剪左右）
            lp.height = FrameLayout.LayoutParams.MATCH_PARENT
            lp.width = (vh * vRatio).toInt()
        } else {
            // 视频更高：按屏幕宽度铺满，高度等比放大（裁剪上下）
            lp.width = FrameLayout.LayoutParams.MATCH_PARENT
            lp.height = (vw / vRatio).toInt()
        }
        video.layoutParams = lp
    }

    private fun go() {
        if (moved) return
        moved = true
        handler.removeCallbacksAndMessages(null)
        startActivity(Intent(this, MainActivity::class.java))
        finish()
        overridePendingTransition(android.R.anim.fade_in, android.R.anim.fade_out)
    }
}
