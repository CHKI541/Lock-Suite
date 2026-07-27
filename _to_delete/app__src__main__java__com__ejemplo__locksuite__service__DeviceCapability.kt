package com.ejemplo.locksuite.service

import android.app.ActivityManager
import android.content.Context
import android.os.Build

object DeviceCapability {
    private const val MIN_RAM_MB = 3000L
    private val MIN_SDK = Build.VERSION_CODES.R // Android 11 (API 30)

    fun isEligibleForAIBlocking(context: Context): Boolean {
        if (Build.VERSION.SDK_INT < MIN_SDK) return false
        val am = context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
        val info = ActivityManager.MemoryInfo()
        am.getMemoryInfo(info)
        return (info.totalMem / (1024 * 1024)) >= MIN_RAM_MB
    }
}
