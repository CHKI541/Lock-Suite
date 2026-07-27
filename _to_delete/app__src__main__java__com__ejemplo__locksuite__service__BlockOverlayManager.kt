package com.ejemplo.locksuite.service

import android.accessibilityservice.AccessibilityService
import android.content.Context
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.Rect
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.View
import android.view.WindowManager

class BlockOverlayManager(private val service: AccessibilityService) {

    private val windowManager =
        service.getSystemService(Context.WINDOW_SERVICE) as WindowManager
    private val activeOverlays = mutableMapOf<String, View>()
    private val mainHandler = Handler(Looper.getMainLooper())

    fun blockRegion(key: String, rect: Rect, blockTouches: Boolean = false) {
        if (rect.isEmpty) return
        mainHandler.post {
            val existing = activeOverlays[key]
            if (existing != null) {
                updatePosition(existing, rect)
                return@post
            }
            val overlayView = View(service).apply { setBackgroundColor(Color.BLACK) }
            
            var flags = WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
            if (!blockTouches) {
                flags = flags or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
            }
            
            val params = WindowManager.LayoutParams(
                rect.width(), rect.height(),
                WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
                flags,
                PixelFormat.TRANSLUCENT
            ).apply {
                gravity = Gravity.TOP or Gravity.START
                x = rect.left
                y = rect.top
            }
            try {
                windowManager.addView(overlayView, params)
                activeOverlays[key] = overlayView
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }
    }

    fun clearStaleRegions(prefix: String, currentKeys: Set<String>) {
        mainHandler.post {
            val stale = activeOverlays.keys.filter { it.startsWith(prefix) && it !in currentKeys }
            stale.forEach { key ->
                activeOverlays.remove(key)?.let { view ->
                    try {
                        windowManager.removeView(view)
                    } catch (e: Exception) {
                        e.printStackTrace()
                    }
                }
            }
        }
    }

    fun clearAll() {
        mainHandler.post {
            activeOverlays.values.forEach { view ->
                try {
                    windowManager.removeView(view)
                } catch (e: Exception) {
                    e.printStackTrace()
                }
            }
            activeOverlays.clear()
        }
    }

    private fun updatePosition(view: View, rect: Rect) {
        val params = view.layoutParams as WindowManager.LayoutParams
        params.x = rect.left
        params.y = rect.top
        params.width = rect.width()
        params.height = rect.height()
        try {
            windowManager.updateViewLayout(view, params)
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }
}
