package com.ejemplo.locksuite.service

import android.app.ActivityManager
import android.content.Context
import android.os.Build

object DeviceCapability {
    private const val MIN_RAM_MB = 3000L
    private val MIN_SDK = Build.VERSION_CODES.R // Android 11 (API 30)

    /**
     * 27/9/2026 (batería). La versión de Android y la memoria TOTAL del equipo no cambian
     * mientras el proceso vive, así que la respuesta se calcula una vez. Antes esto se
     * llamaba desde el servicio de accesibilidad en cada evento, y cada llamada era un
     * `getMemoryInfo()` al sistema.
     */
    @Volatile private var elegible: Boolean? = null

    fun isEligibleForAIBlocking(context: Context): Boolean {
        elegible?.let { return it }
        val calculado = if (Build.VERSION.SDK_INT < MIN_SDK) {
            false
        } else {
            val am = context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
            val info = ActivityManager.MemoryInfo()
            am.getMemoryInfo(info)
            (info.totalMem / (1024 * 1024)) >= MIN_RAM_MB
        }
        elegible = calculado
        return calculado
    }
}
