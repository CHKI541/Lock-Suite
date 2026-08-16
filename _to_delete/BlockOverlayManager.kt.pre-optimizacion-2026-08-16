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

    fun showBlockingMessageOverlay(message: String) {
        mainHandler.post {
            val key = "blocking_message"
            if (activeOverlays.containsKey(key)) return@post
            
            val overlayView = android.widget.LinearLayout(service).apply {
                orientation = android.widget.LinearLayout.VERTICAL
                gravity = Gravity.CENTER
                setBackgroundColor(Color.parseColor("#090A0F")) // 100% solid opaque background
                isClickable = true
                isFocusable = true
                setOnTouchListener { _, _ -> true } // Consume 100% of touches so nothing passes through
                
                // Add message text view
                addView(android.widget.TextView(service).apply {
                    text = message
                    setTextColor(Color.WHITE)
                    textSize = 22f
                    gravity = Gravity.CENTER
                    setPadding(80, 80, 80, 40)
                    typeface = android.graphics.Typeface.DEFAULT_BOLD
                })
                
                // Add status subtitle text view
                addView(android.widget.TextView(service).apply {
                    tag = "subtitle"
                    text = "Por favor, no toque la pantalla..."
                    setTextColor(Color.parseColor("#8E9AA8"))
                    textSize = 15f
                    gravity = Gravity.CENTER
                    setPadding(80, 0, 80, 80)
                })
                
                // Add progress bar
                addView(android.widget.ProgressBar(service).apply {
                    isIndeterminate = true
                })
            }
            
            val params = WindowManager.LayoutParams(
                WindowManager.LayoutParams.MATCH_PARENT,
                WindowManager.LayoutParams.MATCH_PARENT,
                WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or 
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or 
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
                PixelFormat.TRANSLUCENT
            ).apply {
                gravity = Gravity.CENTER
            }
            
            try {
                windowManager.addView(overlayView, params)
                activeOverlays[key] = overlayView
                android.util.Log.i("LockSuite_Overlay", "✅ Overlay de bloqueo de actualización mostrado con éxito")
            } catch (e: Exception) {
                android.util.Log.e("LockSuite_Overlay", "❌ Error al agregar overlay de actualización: ${e.message}", e)
            }
        }
    }

    fun updateBlockingMessageSubtitle(subtitle: String) {
        mainHandler.post {
            val key = "blocking_message"
            val overlay = activeOverlays[key] as? android.widget.LinearLayout ?: return@post
            val subtitleView = overlay.findViewWithTag<android.widget.TextView>("subtitle")
            subtitleView?.text = subtitle
        }
    }

    fun hideBlockingMessageOverlay() {
        mainHandler.post {
            val key = "blocking_message"
            activeOverlays.remove(key)?.let { view ->
                try {
                    windowManager.removeView(view)
                } catch (e: Exception) {
                    e.printStackTrace()
                }
            }
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
