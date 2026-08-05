package com.aibox.app

import android.app.Dialog
import android.content.Context
import android.graphics.drawable.GradientDrawable
import androidx.core.content.ContextCompat
import android.view.Gravity
import android.view.Window
import android.widget.LinearLayout
import android.widget.TextView

/** 统一的圆角灰白弹窗，替代系统 AlertDialog */
object Ui {

    private fun dp(ctx: Context, v: Float): Int = (v * ctx.resources.displayMetrics.density).toInt()

    private fun rounded(ctx: Context, fillRes: Int, strokeRes: Int, radius: Int): GradientDrawable =
        GradientDrawable().apply {
            cornerRadius = radius.toFloat()
            setColor(ContextCompat.getColor(ctx, fillRes))
            setStroke(1, ContextCompat.getColor(ctx, strokeRes))
        }

    private fun base(ctx: Context): Pair<Dialog, LinearLayout> {
        val d = Dialog(ctx)
        d.requestWindowFeature(Window.FEATURE_NO_TITLE)
        val root = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(ctx, 20f), dp(ctx, 18f), dp(ctx, 20f), dp(ctx, 14f))
            background = rounded(ctx, R.color.dialog_bg, R.color.dialog_stroke, dp(ctx, 22f))
        }
        d.setContentView(root, LinearLayout.LayoutParams(dp(ctx, 300f), LinearLayout.LayoutParams.WRAP_CONTENT))
        d.window?.setBackgroundDrawableResource(android.R.color.transparent)
        return d to root
    }

    private fun title(ctx: Context, text: String): TextView = TextView(ctx).apply {
        this.text = text
        setTextColor(ContextCompat.getColor(ctx, R.color.dialog_title))
        textSize = 16f
        isAllCaps = false
        setPadding(0, 0, 0, dp(ctx, 10f))
    }

    private fun item(ctx: Context, text: String, onClick: () -> Unit): TextView = TextView(ctx).apply {
        this.text = text
        setTextColor(ContextCompat.getColor(ctx, R.color.dialog_text))
        textSize = 14f
        gravity = Gravity.CENTER_VERTICAL
        setPadding(dp(ctx, 12f), dp(ctx, 13f), dp(ctx, 12f), dp(ctx, 13f))
        background = rounded(ctx, R.color.dialog_item, R.color.dialog_stroke, dp(ctx, 12f))
        setOnClickListener { onClick() }
    }

    private fun button(ctx: Context, text: String, onClick: () -> Unit): TextView = TextView(ctx).apply {
        this.text = text
        setTextColor(ContextCompat.getColor(ctx, R.color.dialog_title))
        textSize = 14f
        gravity = Gravity.CENTER
        setPadding(0, dp(ctx, 12f), 0, dp(ctx, 12f))
        background = rounded(ctx, R.color.dialog_btn, R.color.dialog_stroke, dp(ctx, 14f))
        setOnClickListener { onClick() }
    }

    /** 单选列表弹窗 */
    fun sheet(ctx: Context, title: String, items: List<String>, onPick: (Int) -> Unit) {
        val (d, root) = base(ctx)
        root.addView(title(ctx, title))
        items.forEachIndexed { i, it ->
            val tv = item(ctx, it) { d.dismiss(); onPick(i) }
            tv.setPadding(dp(ctx, 12f), dp(ctx, 12f), dp(ctx, 12f), dp(ctx, 12f))
            val lp = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT)
            lp.topMargin = dp(ctx, 6f)
            root.addView(tv, lp)
        }
        d.show()
    }

    /** 操作菜单弹窗（带取消） */
    fun menu(ctx: Context, title: String, items: List<String>, onPick: (Int) -> Unit) {
        val (d, root) = base(ctx)
        root.addView(title(ctx, title))
        items.forEachIndexed { i, it ->
            val tv = item(ctx, it) { d.dismiss(); onPick(i) }
            val lp = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT)
            lp.topMargin = dp(ctx, 6f)
            root.addView(tv, lp)
        }
        val cancel = button(ctx, "取消") { d.dismiss() }
        val lp = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT)
        lp.topMargin = dp(ctx, 10f)
        root.addView(cancel, lp)
        d.show()
    }

    /** 信息弹窗 */
    fun info(ctx: Context, title: String, message: String) {
        val (d, root) = base(ctx)
        root.addView(title(ctx, title))
        root.addView(TextView(ctx).apply {
            text = message
            setTextColor(ContextCompat.getColor(ctx, R.color.dialog_text))
            textSize = 13f
            setLineSpacing(4f, 1f)
            setPadding(0, 0, 0, dp(ctx, 4f))
        })
        val ok = button(ctx, "知道了") { d.dismiss() }
        val lp = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT)
        lp.topMargin = dp(ctx, 14f)
        root.addView(ok, lp)
        d.show()
    }

    /** 确认弹窗 */
    fun confirm(ctx: Context, title: String, message: String, okText: String, onOk: () -> Unit) {
        val (d, root) = base(ctx)
        root.addView(title(ctx, title))
        root.addView(TextView(ctx).apply {
            text = message
            setTextColor(ContextCompat.getColor(ctx, R.color.dialog_text))
            textSize = 13f
            setLineSpacing(4f, 1f)
        })
        val row = LinearLayout(ctx).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(0, dp(ctx, 14f), 0, 0)
        }
        val cancel = button(ctx, "取消") { d.dismiss() }
        val ok = button(ctx, okText) { d.dismiss(); onOk() }
        val lp1 = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        val lp2 = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        lp2.marginStart = dp(ctx, 10f)
        row.addView(cancel, lp1)
        row.addView(ok, lp2)
        root.addView(row)
        d.show()
    }
}
