package com.aibox.app

import android.app.ActivityManager
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager
import android.os.Build
import android.os.Environment
import android.os.StatFs
import android.os.SystemClock
import android.os.Bundle
import android.view.Gravity
import android.widget.*
import androidx.appcompat.app.AppCompatActivity
import java.io.File

class SysInfoActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(16), dp(16), dp(16))
        }
        box.addView(TextView(this).apply {
            text = "手机系统信息"; setTextColor(0xFFECEFF4.toInt()); textSize = 20f
            setPadding(0, 0, 0, dp(10))
        })

        val info = buildList()
        info.forEach { (k, v) ->
            val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
            val kt = TextView(this).apply {
                text = k; setTextColor(0xFF9AA5B1.toInt()); textSize = 13f
                setPadding(dp(12), dp(10), dp(6), dp(10))
            }
            val vt = TextView(this).apply {
                text = v; setTextColor(0xFFECEFF4.toInt()); textSize = 13f
                gravity = Gravity.END
                setPadding(dp(6), dp(10), dp(12), dp(10))
            }
            row.addView(kt, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
            row.addView(vt, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
            row.background = GlassUi.panel(dp(16).toFloat())
            val lp = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT)
            lp.setMargins(dp(2), dp(3), dp(2), dp(3))
            row.layoutParams = lp
            box.addView(row)
        }
        val scroll = ScrollView(this).apply { addView(box) }
        setContentView(scroll)
    }

    private fun buildList(): List<Pair<String, String>> {
        val out = mutableListOf<Pair<String, String>>()
        out.add("型号" to "${Build.MANUFACTURER} ${Build.MODEL}")
        out.add("品牌" to Build.BRAND)
        out.add("Android 版本" to "${Build.VERSION.RELEASE}（API ${Build.VERSION.SDK_INT}）")
        out.add("CPU 架构" to Build.SUPPORTED_ABIS.joinToString("、"))

        val am = getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
        val mem = ActivityManager.MemoryInfo()
        am.getMemoryInfo(mem)
        out.add("运行内存" to formatBytes(mem.totalMem))
        out.add("可用内存" to formatBytes(mem.availMem))

        val stat = StatFs(Environment.getDataDirectory().path)
        val total = stat.totalBytes.toFloat()
        val avail = stat.availableBytes.toFloat()
        out.add("存储总空间" to formatBytes(total.toLong()))
        out.add("存储可用" to formatBytes(avail.toLong()) + "（${(avail / total * 100).toInt()}%）")

        val bIntent = registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
        val level = bIntent?.getIntExtra(BatteryManager.EXTRA_LEVEL, -1) ?: -1
        val scale = bIntent?.getIntExtra(BatteryManager.EXTRA_SCALE, 100) ?: 100
        val temp = (bIntent?.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, 0) ?: 0) / 10.0
        out.add("电池电量" to "${if (level >= 0) level * 100 / scale else -1}%")
        out.add("电池温度" to "${temp}℃")

        val dm = resources.displayMetrics
        out.add("屏幕" to "${dm.widthPixels}×${dm.heightPixels}（${dm.densityDpi}dpi）")
        out.add("开机时间" to formatUptime(SystemClock.elapsedRealtime()))
        out.add("已安装应用" to "${packageManager.getInstalledApplications(0).size} 个")
        return out
    }

    private fun formatBytes(b: Long): String {
        val mb = b / 1024.0 / 1024.0
        return if (mb > 1024) String.format("%.1f GB", mb / 1024) else String.format("%.0f MB", mb)
    }

    private fun formatUptime(ms: Long): String {
        val s = ms / 1000
        val h = s / 3600; val m = (s % 3600) / 60
        return "${h}小时${m}分"
    }

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()
}
