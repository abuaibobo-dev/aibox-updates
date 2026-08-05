package com.aibox.app

import android.graphics.drawable.GradientDrawable

/** 玻璃拟态统一控件：半透明深色面板 + 圆角 + 描边 */
object GlassUi {

    /** 半透明玻璃面板 */
    fun panel(radiusPx: Float): GradientDrawable = GradientDrawable().apply {
        cornerRadius = radiusPx
        setColor(0x40131C29.toInt())
        setStroke(1, 0x2E8B9DC3.toInt())
    }

    /** 玻璃输入框 */
    fun input(): GradientDrawable = GradientDrawable().apply {
        cornerRadius = 24f
        setColor(0x26283853.toInt())
        setStroke(1, 0x2E8B9DC3.toInt())
    }

    /** 实色圆角块（按钮等，带描边） */
    fun solid(radiusPx: Float, color: Int): GradientDrawable = GradientDrawable().apply {
        cornerRadius = radiusPx
        setColor(color)
        setStroke(1, 0x4D9CDCFE.toInt())
    }
}
