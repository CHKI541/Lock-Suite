package com.ejemplo.locksuite.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.graphics.PixelFormat
import android.os.Build
import android.os.IBinder
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.widget.TextView
import androidx.core.app.NotificationCompat

/**
 * Servicio foreground que dibuja la marca de agua "מוגן" de forma persistente
 * en la esquina inferior derecha de la pantalla, por encima de todas las apps.
 *
 * El Watchdog y BootReceiver lo inician con startForegroundService si el modo
 * Kosher Launcher está activo. Si el servicio ya está corriendo, onStartCommand
 * simplemente lo ignora (no duplica la vista).
 */
class WatermarkService : Service() {

    private var windowManager: WindowManager? = null
    private var watermarkView: View? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()

        createNotificationChannel()
        startForeground(NOTIFICATION_ID, createNotification())

        addWatermarkView()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // Si ya existe la vista, no la volvemos a agregar (evita duplicados
        // cuando el Watchdog re-invoca startForegroundService cada 20s)
        if (watermarkView == null) {
            addWatermarkView()
        }
        return START_STICKY
    }

    private fun addWatermarkView() {
        try {
            windowManager = getSystemService(Context.WINDOW_SERVICE) as WindowManager

            val params = WindowManager.LayoutParams(
                WindowManager.LayoutParams.WRAP_CONTENT,
                WindowManager.LayoutParams.WRAP_CONTENT,
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
                } else {
                    @Suppress("DEPRECATION")
                    WindowManager.LayoutParams.TYPE_PHONE
                },
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                        WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                        WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                        WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
                PixelFormat.TRANSLUCENT
            ).apply {
                gravity = Gravity.BOTTOM or Gravity.END
                x = 24
                y = 16
            }

            val textView = TextView(this).apply {
                text = "מוגן"
                textSize = 10f
                setTextColor(Color.argb(60, 255, 255, 255)) // ~23% opacity white
                setShadowLayer(1.5f, 1f, 1f, Color.argb(40, 0, 0, 0))
            }
            watermarkView = textView

            windowManager?.addView(watermarkView, params)
        } catch (e: Exception) {
            android.util.Log.e("WatermarkService", "Error agregando vista de marca de agua", e)
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        watermarkView?.let { view ->
            try {
                windowManager?.removeView(view)
            } catch (e: Exception) {
                android.util.Log.w("WatermarkService", "Error removiendo marca de agua", e)
            }
        }
        watermarkView = null
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "Marca de Agua Kosher",
                NotificationManager.IMPORTANCE_MIN
            ).apply {
                description = "Mantiene visible el sello de protección del dispositivo"
                setShowBadge(false)
            }
            val manager = getSystemService(NotificationManager::class.java)
            manager?.createNotificationChannel(channel)
        }
    }

    private fun createNotification(): Notification {
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("Protección Kosher")
            .setContentText("Dispositivo Protegido")
            .setSmallIcon(android.R.drawable.ic_secure)
            .setPriority(NotificationCompat.PRIORITY_MIN)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .setOngoing(true)
            .build()
    }

    companion object {
        private const val NOTIFICATION_ID = 2002
        private const val CHANNEL_ID = "kosher_watermark_channel"
    }
}
