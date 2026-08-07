package com.aibox.app

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.content.ContextCompat

/** 引擎能力全开：所有可授予的运行时危险权限，一次性请求 */
object Perms {

    val RUNTIME: List<String> = buildList {
        add(Manifest.permission.ACCESS_FINE_LOCATION)
        add(Manifest.permission.ACCESS_COARSE_LOCATION)
        add(Manifest.permission.READ_CONTACTS)
        add(Manifest.permission.WRITE_CONTACTS)
        add(Manifest.permission.READ_CALENDAR)
        add(Manifest.permission.WRITE_CALENDAR)
        add(Manifest.permission.READ_PHONE_STATE)
        add(Manifest.permission.READ_SMS)
        add(Manifest.permission.CAMERA)
        add(Manifest.permission.RECORD_AUDIO)
        add(Manifest.permission.POST_NOTIFICATIONS)
        if (Build.VERSION.SDK_INT <= Build.VERSION_CODES.S_V2) {
            add(Manifest.permission.READ_EXTERNAL_STORAGE)
        }
        if (Build.VERSION.SDK_INT <= Build.VERSION_CODES.P) {
            add(Manifest.permission.WRITE_EXTERNAL_STORAGE)
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            add(Manifest.permission.BLUETOOTH_CONNECT)
            add(Manifest.permission.BLUETOOTH_SCAN)
        }
    }

    /** 尚未授予的权限（用于一次性请求） */
    fun missing(ctx: Context): Array<String> =
        RUNTIME.filter { ContextCompat.checkSelfPermission(ctx, it) != PackageManager.PERMISSION_GRANTED }.toTypedArray()

    /** 特殊权限（无法走运行时弹窗，需跳系统设置）是否已就绪 */
    fun specialNotReady(ctx: Context): List<String> {
        val out = mutableListOf<String>()
        if (!CodexEngine.hasAllFilesAccess(ctx)) out.add("所有文件访问")
        if (!ctx.packageManager.canRequestPackageInstalls()) out.add("安装未知应用")
        return out
    }
}
