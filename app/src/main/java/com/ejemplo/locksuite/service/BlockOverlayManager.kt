package com.ejemplo.locksuite.service

import android.accessibilityservice.AccessibilityService
import android.content.Context
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.Rect
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.Gravity
import android.view.View
import android.view.WindowManager

/**
 * Administra las ventanas negras (overlays) que tapan regiones de la pantalla.
 *
 * Notas de rendimiento (optimización 16/8/2026):
 *
 *  1. Las llamadas que ya vienen del hilo principal se ejecutan EN EL ACTO, sin pasar
 *     por `Handler.post`. Los eventos de accesibilidad llegan en el hilo principal, así
 *     que antes cada tapado perdía un frame completo (~16 ms) de más. Ahora el overlay
 *     aparece en el mismo frame en que se detecta la imagen.
 *
 *  2. `updateViewLayout` es una llamada al WindowManager del sistema (IPC). Antes se
 *     invocaba en cada escaneo aunque la región no se hubiera movido ni un píxel. Ahora
 *     se guarda el último rectángulo aplicado y se omite la llamada si no cambió.
 *
 *  3. Período de gracia al quitar overlays: si un escaneo puntual no encuentra un nodo
 *     que sí sigue en pantalla (pasa seguido mientras se hace scroll o durante una
 *     animación), antes el overlay se destruía y se volvía a crear al escaneo siguiente,
 *     lo que se ve como un parpadeo y deja la imagen destapada uno o dos frames. Ahora
 *     una región que desaparece queda "en observación" GRACE_MS y solo se destruye si
 *     sigue ausente al vencerse ese plazo.
 */
class BlockOverlayManager(private val service: AccessibilityService) {

    private companion object {
        /** Cuánto se espera antes de destruir un overlay cuyo nodo dejó de verse. */
        private const val GRACE_MS = 350L

        /** Clave del overlay de pantalla completa usado durante una actualizacion. */
        const val BLOCK_KEY = "blocking_message"
    }

    private val windowManager =
        service.getSystemService(Context.WINDOW_SERVICE) as WindowManager
    private val activeOverlays = mutableMapOf<String, View>()

    /** Último rectángulo realmente aplicado a cada overlay, para no repetir el IPC. */
    private val appliedRects = mutableMapOf<String, Rect>()

    /** Overlays que dejaron de verse y están en período de gracia (clave -> momento). */
    private val pendingRemoval = mutableMapOf<String, Long>()

    private val mainHandler = Handler(Looper.getMainLooper())
    private var sweepScheduled = false

    private val sweepRunnable = Runnable {
        sweepScheduled = false
        sweepExpired()
    }

    /** Ejecuta ya mismo si estamos en el hilo principal; si no, lo encola. */
    private inline fun onMain(crossinline block: () -> Unit) {
        if (Looper.myLooper() == Looper.getMainLooper()) block() else mainHandler.post { block() }
    }

    /**
     * Indica si hay al menos un overlay activo cuya clave empieza con [prefix].
     * Permite al servicio evitar llamar a [clearStaleRegions] cuando no hay nada que limpiar.
     */
    fun hasRegions(prefix: String): Boolean {
        // activeOverlays solo se toca desde el hilo principal; esta consulta se hace
        // desde el mismo hilo (evento de accesibilidad), así que es segura.
        return activeOverlays.keys.any { it.startsWith(prefix) }
    }

    fun blockRegion(key: String, rect: Rect, blockTouches: Boolean = false) {
        if (rect.isEmpty) return
        // Copia defensiva: quien llama puede reutilizar el Rect para el nodo siguiente.
        val target = Rect(rect)
        onMain {
            // Si estaba marcado para eliminarse y volvió a aparecer, lo rescatamos.
            pendingRemoval.remove(key)

            val existing = activeOverlays[key]
            if (existing != null) {
                updatePosition(key, existing, target)
                return@onMain
            }
            val overlayView = View(service).apply { setBackgroundColor(Color.BLACK) }

            var flags = WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                    // Coordenadas absolutas de pantalla, para que coincidan con las que
                    // devuelve AccessibilityNodeInfo.getBoundsInScreen() y el recuadro
                    // negro quede exactamente encima de la imagen, sin corrimiento por
                    // la barra de estado ni por el notch.
                    WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                    WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS
            if (!blockTouches) {
                flags = flags or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
            }

            val params = WindowManager.LayoutParams(
                target.width(), target.height(),
                WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
                flags,
                PixelFormat.TRANSLUCENT
            ).apply {
                gravity = Gravity.TOP or Gravity.START
                x = target.left
                y = target.top
            }
            try {
                windowManager.addView(overlayView, params)
                activeOverlays[key] = overlayView
                appliedRects[key] = target
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }
    }

    /**
     * Marca como ausentes los overlays con este prefijo que no estén en [currentKeys].
     * No los destruye en el acto: les da GRACE_MS por si reaparecen en el escaneo siguiente.
     */
    fun clearStaleRegions(prefix: String, currentKeys: Set<String>) {
        onMain {
            val now = SystemClock.uptimeMillis()
            var anyPending = false

            for (key in activeOverlays.keys) {
                if (!key.startsWith(prefix)) continue
                if (key in currentKeys) {
                    pendingRemoval.remove(key)
                } else {
                    if (pendingRemoval[key] == null) pendingRemoval[key] = now
                    anyPending = true
                }
            }

            sweepExpired()
            if (anyPending) scheduleSweep()
        }
    }

    /** Destruye los overlays cuyo período de gracia ya venció. */
    private fun sweepExpired() {
        if (pendingRemoval.isEmpty()) return
        val now = SystemClock.uptimeMillis()
        val expired = pendingRemoval.entries
            .filter { now - it.value >= GRACE_MS }
            .map { it.key }
        if (expired.isEmpty()) {
            scheduleSweep()
            return
        }
        expired.forEach { key ->
            pendingRemoval.remove(key)
            removeOverlay(key)
        }
        if (pendingRemoval.isNotEmpty()) scheduleSweep()
    }

    private fun scheduleSweep() {
        if (sweepScheduled) return
        sweepScheduled = true
        mainHandler.postDelayed(sweepRunnable, GRACE_MS)
    }

    private fun removeOverlay(key: String) {
        appliedRects.remove(key)
        activeOverlays.remove(key)?.let { view ->
            try {
                windowManager.removeView(view)
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }
    }

    fun clearAll() {
        onMain {
            mainHandler.removeCallbacks(sweepRunnable)
            sweepScheduled = false
            pendingRemoval.clear()
            appliedRects.clear()
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

    // ──────────────────────────────────────────────
    // Pantalla negra de actualizacion de apps
    //
    // Cambios del 16/8/2026:
    //  • Titulo y subtitulo actualizables por separado, para poder ir contando
    //    en que etapa esta la actualizacion en vez de un texto fijo.
    //  • Boton "Cancelar" opcional. El fondo sigue consumiendo el 100% de los
    //    toques (setOnTouchListener del contenedor), pero los hijos reciben el
    //    toque antes que el padre, asi que el boton si responde.
    //  • showBlockingMessageOverlay() ya no se va en silencio si el overlay ya
    //    existe: refresca los textos. Antes, como se llamaba en cada evento de
    //    Play Store con el mismo texto de siempre, el estado nunca cambiaba.
    // ──────────────────────────────────────────────

    private var blockingCancelable = false
    private var blockingCancelAction: (() -> Unit)? = null

    fun isBlockingMessageVisible(): Boolean = activeOverlays.containsKey(BLOCK_KEY)

    @JvmOverloads
    fun showBlockingMessageOverlay(
        title: String,
        subtitle: String? = null,
        cancelable: Boolean = false,
        onCancel: (() -> Unit)? = null
    ) {
        onMain {
            blockingCancelAction = onCancel

            val existing = activeOverlays[BLOCK_KEY] as? android.widget.LinearLayout
            if (existing != null) {
                if (blockingCancelable == cancelable) {
                    // Mismo formato: solo refrescar textos, sin recrear la ventana.
                    existing.findViewWithTag<android.widget.TextView>("title")?.text = title
                    if (subtitle != null) {
                        existing.findViewWithTag<android.widget.TextView>("subtitle")?.text = subtitle
                    }
                    return@onMain
                }
                // Cambio si es cancelable o no: hay que rearmar la vista.
                removeOverlay(BLOCK_KEY)
            }
            blockingCancelable = cancelable

            val density = service.resources.displayMetrics.density
            fun dp(value: Int): Int = (value * density).toInt()

            val overlayView = android.widget.LinearLayout(service).apply {
                orientation = android.widget.LinearLayout.VERTICAL
                gravity = Gravity.CENTER
                setBackgroundColor(Color.parseColor("#090A0F")) // opaco 100%
                isClickable = true
                isFocusable = true
                setPadding(dp(24), dp(48), dp(24), dp(48))
                // Consume cualquier toque que no haya agarrado un hijo (el boton).
                setOnTouchListener { _, _ -> true }

                addView(android.widget.TextView(service).apply {
                    tag = "title"
                    text = title
                    setTextColor(Color.WHITE)
                    textSize = 22f
                    gravity = Gravity.CENTER
                    setPadding(dp(16), 0, dp(16), dp(12))
                    typeface = android.graphics.Typeface.DEFAULT_BOLD
                })

                addView(android.widget.TextView(service).apply {
                    tag = "subtitle"
                    text = subtitle ?: "Por favor, no toque la pantalla..."
                    setTextColor(Color.parseColor("#8E9AA8"))
                    textSize = 15f
                    gravity = Gravity.CENTER
                    setPadding(dp(16), 0, dp(16), dp(24))
                })

                addView(android.widget.ProgressBar(service).apply {
                    isIndeterminate = true
                })

                if (cancelable) {
                    addView(android.widget.Button(service).apply {
                        tag = "cancel"
                        text = "Cancelar"
                        textSize = 15f
                        setTextColor(Color.WHITE)
                        // El fondo va ANTES del padding: cambiar el background de
                        // una View puede reescribir el padding, así que al revés
                        // el botón quedaba sin márgenes internos.
                        setBackgroundColor(Color.parseColor("#33405A"))
                        setPadding(dp(28), dp(10), dp(28), dp(10))
                        setOnClickListener {
                            // Desarmar de inmediato para que un doble toque no
                            // dispare la cancelacion dos veces.
                            isEnabled = false
                            text = "Cancelando..."
                            val action = blockingCancelAction
                            blockingCancelAction = null
                            try {
                                action?.invoke()
                            } catch (e: Exception) {
                                android.util.Log.e("LockSuite_Overlay", "Error al cancelar: ${e.message}", e)
                            }
                        }
                    }, android.widget.LinearLayout.LayoutParams(
                        android.widget.LinearLayout.LayoutParams.WRAP_CONTENT,
                        android.widget.LinearLayout.LayoutParams.WRAP_CONTENT
                    ).apply { topMargin = dp(36) })
                }
            }

            val params = WindowManager.LayoutParams(
                WindowManager.LayoutParams.MATCH_PARENT,
                WindowManager.LayoutParams.MATCH_PARENT,
                WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                    WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                    WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
                PixelFormat.OPAQUE
            ).apply {
                gravity = Gravity.CENTER
            }

            try {
                windowManager.addView(overlayView, params)
                activeOverlays[BLOCK_KEY] = overlayView
                android.util.Log.i("LockSuite_Overlay", "Overlay de actualizacion mostrado (cancelable=$cancelable)")
            } catch (e: Exception) {
                android.util.Log.e("LockSuite_Overlay", "Error al agregar overlay de actualizacion: ${e.message}", e)
            }
        }
    }

    /** Actualiza titulo y/o subtitulo de la pantalla negra si esta visible. */
    fun updateBlockingMessage(title: String?, subtitle: String?) {
        onMain {
            val overlay = activeOverlays[BLOCK_KEY] as? android.widget.LinearLayout ?: return@onMain
            if (title != null) overlay.findViewWithTag<android.widget.TextView>("title")?.text = title
            if (subtitle != null) overlay.findViewWithTag<android.widget.TextView>("subtitle")?.text = subtitle
        }
    }

    fun updateBlockingMessageSubtitle(subtitle: String) = updateBlockingMessage(null, subtitle)

    fun hideBlockingMessageOverlay() {
        onMain {
            pendingRemoval.remove(BLOCK_KEY)
            blockingCancelAction = null
            blockingCancelable = false
            removeOverlay(BLOCK_KEY)
        }
    }

    private fun updatePosition(key: String, view: View, rect: Rect) {
        // Si la región no se movió ni cambió de tamaño, no hay nada que hacer.
        // Esto evita un IPC al WindowManager por cada imagen en cada escaneo.
        if (appliedRects[key] == rect) return

        val params = view.layoutParams as? WindowManager.LayoutParams ?: return
        params.x = rect.left
        params.y = rect.top
        params.width = rect.width()
        params.height = rect.height()
        try {
            windowManager.updateViewLayout(view, params)
            appliedRects[key] = rect
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }
}
