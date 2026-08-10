package com.ejemplo.locksuite.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.PixelFormat
import android.graphics.RectF
import android.os.Build
import android.os.IBinder
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import androidx.core.app.NotificationCompat

/**
 * Servicio foreground que dibuja el sello circular de agua "מוגן" estilo NetFree
 * en la esquina inferior derecha de la pantalla de forma persistente.
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
        if (watermarkView == null) {
            addWatermarkView()
        }
        return START_STICKY
    }

    private fun addWatermarkView() {
        try {
            windowManager = getSystemService(Context.WINDOW_SERVICE) as WindowManager

            val sizePx = (56 * resources.displayMetrics.density).toInt()
            val params = WindowManager.LayoutParams(
                sizePx,
                sizePx,
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
                x = 16
                y = 16
            }

            watermarkView = CircularNetFreeWatermarkView(this)
            windowManager?.addView(watermarkView, params)
        } catch (e: Exception) {
            android.util.Log.e("WatermarkService", "Error agregando marca de agua circular", e)
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

    /**
     * Vista personalizada que dibuja el sello circular "מוגן" transparente (estilo NetFree)
     */
    private class CircularNetFreeWatermarkView(context: Context) : View(context) {

        private val outerCirclePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeWidth = 3f
            color = Color.argb(70, 180, 210, 240) // Translucent light slate/blue
        }

        private val innerCirclePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeWidth = 1.8f
            color = Color.argb(50, 180, 210, 240)
        }

        private val bannerPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.FILL
            color = Color.argb(45, 15, 25, 40)
        }

        private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.argb(100, 220, 235, 255)
            textAlign = Paint.Align.CENTER
            isFakeBoldText = true
            setShadowLayer(2f, 1f, 1f, Color.argb(80, 0, 0, 0))
        }

        override fun onDraw(canvas: Canvas) {
            super.onDraw(canvas)

            val cx = width / 2f
            val cy = height / 2f
            val radius = (width.coerceAtMost(height) / 2f) - 6f

            if (radius <= 0) return

            canvas.save()
            // Rotar ligeramente (-15 grados) para dar estética de sello estampado
            canvas.rotate(-15f, cx, cy)

            // Círculo exterior
            canvas.drawCircle(cx, cy, radius, outerCirclePaint)

            // Círculo interior
            canvas.drawCircle(cx, cy, radius * 0.82f, innerCirclePaint)

            // Ribbon/Banda horizontal central
            val bannerHeight = radius * 0.65f
            val bannerRect = RectF(cx - radius, cy - bannerHeight / 2f, cx + radius, cy + bannerHeight / 2f)
            canvas.drawRect(bannerRect, bannerPaint)

            // Texto "מוגן"
            textPaint.textSize = radius * 0.55f
            val textBaseline = cy - ((textPaint.descent() + textPaint.ascent()) / 2f)
            canvas.drawText("מוגן", cx, textBaseline, textPaint)

            canvas.restore()
        }
    }
}
