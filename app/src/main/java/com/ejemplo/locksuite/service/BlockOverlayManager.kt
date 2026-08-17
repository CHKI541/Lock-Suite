package com.ejemplo.locksuite.service

import android.accessibilityservice.AccessibilityService
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.PixelFormat
import android.graphics.Rect
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.Gravity
import android.view.View
import android.view.WindowManager

/**
 * Tapado visual de regiones de pantalla (imágenes, video, WebViews).
 *
 * ────────────────────────────────────────────────────────────────────────────
 * REESCRITO EL 17/8/2026 — de N ventanas a UNA capa de canvas
 * ────────────────────────────────────────────────────────────────────────────
 *
 * Cómo era antes y por qué se sentía mal:
 *
 *   Cada imagen tapada era su PROPIA ventana del sistema (`WindowManager.addView`
 *   con un `View` negro). Para diez imágenes en pantalla, diez ventanas. Mover un
 *   recuadro un píxel significaba `updateViewLayout()`, que es una llamada IPC al
 *   WindowManagerService: el proceso de la app le pide al sistema que reubique una
 *   superficie, el sistema recalcula el orden de composición y el compositor rehace
 *   la capa. Multiplicado por diez recuadros y por cada frame de scroll, eso es
 *   cientos de IPC por segundo. Ese era el "queda en su lugar cuando bajás y se
 *   mueve a los saltos": los recuadros iban literalmente por un camino más lento
 *   que el contenido que tenían que tapar, y además cada uno llegaba por separado
 *   (se veían desincronizados entre sí).
 *
 * Cómo es ahora:
 *
 *   Hay UNA sola ventana transparente a pantalla completa. Los recuadros son
 *   rectángulos dibujados en su canvas. Mover todos los recuadros = cambiar unos
 *   números en memoria y llamar a `invalidate()`: cero IPC, un solo frame, todos
 *   los recuadros se mueven juntos y en el mismo instante. Es la misma técnica que
 *   usa cualquier app para animar contenido propio, y por eso se siente fluido.
 *
 *   Aclaración honesta, porque fue el pedido textual del dueño: Android NO permite
 *   dibujar dentro de la imagen de otra app. La superficie de WhatsApp (o de la que
 *   sea) es de ese proceso y es intocable — por diseño del sistema, no por una
 *   limitación de esta app. Lo más cerca que se puede estar es una capa propia
 *   encima que se mueva EXACTAMENTE con el contenido, que es lo que hace esto.
 *
 * Dos cosas más que hacen que se sienta "pegado":
 *
 *   1. `translateRegions(dx, dy)` — cuando llega un evento de scroll, los recuadros
 *      se corren por el delta que informa el propio evento, SIN volver a recorrer el
 *      árbol de nodos (que es lo caro). El recorrido real se hace después, con calma,
 *      para corregir. O sea: se adelanta y después se ajusta.
 *   2. `setStrictCover()` — modo opcional (interruptor "Tapado estricto al desplazar"):
 *      mientras dura un desplazamiento rápido se tapa el contenedor entero, y al
 *      frenar vuelven los recuadros exactos. Cero fugas a cambio de tapar de más
 *      durante el movimiento. Apagado por defecto.
 *
 * Costo de batería: la ventana solo existe si hay al menos una región que tapar; si
 * no hay ninguna, se quita y el compositor deja de tener una capa extra. `invalidate()`
 * se llama únicamente si algo cambió de verdad.
 */
class BlockOverlayManager(private val service: AccessibilityService) {

    private companion object {
        /** Cuánto se espera antes de borrar una región cuyo nodo dejó de verse. */
        private const val GRACE_MS = 350L

        /** Lista vacía compartida, para no asignar un array nuevo cada vez que se limpia. */
        private val EMPTY_RECTS = emptyArray<Rect>()

        /** Relleno de los recuadros. Opaco a propósito. */
        private const val FILL_COLOR = 0xFF0A0A0C.toInt()

        /** Borde tenue, para que se lea como un panel puesto a propósito y no como un glitch. */
        private const val EDGE_COLOR = 0xFF1E2430.toInt()

        /** A partir de este tamaño (px) el recuadro lleva el ícono de "contenido oculto". */
        private const val GLYPH_MIN_PX = 220
    }

    private val windowManager =
        service.getSystemService(Context.WINDOW_SERVICE) as WindowManager

    private val mainHandler = Handler(Looper.getMainLooper())

    // ──────────────────────────────────────────────────────────────
    // Estado de las regiones
    //
    // `regions` y `staged` se tocan desde el hilo principal (Capa 1, nodos) y desde
    // el executor de fondo (Capa 2, IA), así que van bajo lock. `drawList` es la
    // foto inmutable que lee onDraw() en el hilo de UI sin tomar ningún lock.
    // ──────────────────────────────────────────────────────────────
    private val lock = Any()
    private val regions = LinkedHashMap<String, Rect>(32)
    private val staged = HashMap<String, Rect>(32)
    private val pendingRemoval = HashMap<String, Long>(8)

    @Volatile private var drawList: Array<Rect> = EMPTY_RECTS
    @Volatile private var strictCover: Rect? = null

    private var overlayView: RegionOverlayView? = null
    private var sweepScheduled = false

    private val sweepRunnable = Runnable {
        sweepScheduled = false
        sweepExpired()
    }

    /** Ejecuta ya mismo si estamos en el hilo principal; si no, lo encola. */
    private inline fun onMain(crossinline block: () -> Unit) {
        if (Looper.myLooper() == Looper.getMainLooper()) block() else mainHandler.post { block() }
    }

    // ──────────────────────────────────────────────────────────────
    // API pública de regiones (misma firma que la versión de ventanas,
    // para no tocar a quien la llama)
    // ──────────────────────────────────────────────────────────────

    /** ¿Hay al menos una región activa cuya clave empieza con [prefix]? */
    fun hasRegions(prefix: String): Boolean {
        synchronized(lock) {
            if (prefix.isEmpty()) return regions.isNotEmpty()
            return regions.keys.any { it.startsWith(prefix) }
        }
    }

    /** Cantidad de regiones activas. Sirve para decidir caminos rápidos sin recorrer nada. */
    fun regionCount(): Int = synchronized(lock) { regions.size }

    /**
     * Registra una región a tapar. NO dibuja todavía: queda en el área de preparación
     * y se aplica entera en [clearStaleRegions]. Así todas las regiones de un escaneo
     * aparecen en el mismo frame en vez de una por una.
     */
    fun blockRegion(key: String, rect: Rect, blockTouches: Boolean = false) {
        if (rect.isEmpty) return
        synchronized(lock) {
            // Copia defensiva: quien llama reutiliza el Rect para el nodo siguiente.
            staged[key] = Rect(rect)
        }
    }

    /**
     * Aplica el escaneo: las claves de [currentKeys] quedan visibles con la posición
     * que se preparó, y las que tenían este prefijo y ya no aparecen entran en período
     * de gracia (GRACE_MS) antes de borrarse, para que un escaneo que falla puntualmente
     * no destape la imagen uno o dos frames.
     */
    fun clearStaleRegions(prefix: String, currentKeys: Set<String>) {
        var changed = false
        var anyPending = false
        val now = SystemClock.uptimeMillis()

        synchronized(lock) {
            // 1. Volcar lo preparado que corresponde a este prefijo.
            val it = staged.entries.iterator()
            while (it.hasNext()) {
                val e = it.next()
                if (!e.key.startsWith(prefix)) continue
                it.remove()
                if (e.key !in currentKeys) continue
                val previous = regions.put(e.key, e.value)
                pendingRemoval.remove(e.key)
                if (previous == null || previous != e.value) changed = true
            }

            // 2. Marcar como ausentes las que ya no están.
            for (key in regions.keys) {
                if (!key.startsWith(prefix)) continue
                if (key in currentKeys) {
                    pendingRemoval.remove(key)
                } else {
                    if (pendingRemoval[key] == null) pendingRemoval[key] = now
                    anyPending = true
                }
            }
        }

        if (changed) rebuildAndInvalidate()
        // sweepExpired() repinta por su cuenta si borró algo; su resultado no se usa acá.
        sweepExpired()
        if (anyPending) onMain { scheduleSweep() }
    }

    /**
     * Camino rápido de scroll: corre TODAS las regiones por el delta informado por el
     * evento de accesibilidad, sin recorrer el árbol de nodos.
     *
     * Es la diferencia entre "el recuadro va atrás de la imagen" y "el recuadro va con
     * la imagen": recorrer el árbol cuesta milisegundos y solo se puede hacer unas
     * pocas veces por segundo; esto cuesta microsegundos y se puede hacer en cada frame.
     * El recorrido real corrige la posición un instante después.
     */
    fun translateRegions(dx: Int, dy: Int) {
        if (dx == 0 && dy == 0) return
        var any = false
        synchronized(lock) {
            if (regions.isEmpty()) return
            for (r in regions.values) {
                r.offset(-dx, -dy)
                any = true
            }
        }
        if (any) rebuildAndInvalidate()
    }

    /**
     * Modo estricto: tapa [rect] entero (el contenedor que se está desplazando) hasta
     * que se lo limpie con `setStrictCover(null)`. Se usa mientras dura un
     * desplazamiento rápido, cuando el administrador prefiere no arriesgar ni un frame.
     */
    fun setStrictCover(rect: Rect?) {
        val current = strictCover
        if (rect == null && current == null) return
        if (rect != null && current == rect) return
        strictCover = if (rect == null) null else Rect(rect)
        rebuildAndInvalidate()
    }

    fun hasStrictCover(): Boolean = strictCover != null

    /** Destruye las regiones cuyo período de gracia venció. Devuelve true si algo cambió. */
    private fun sweepExpired(): Boolean {
        var changed = false
        synchronized(lock) {
            if (pendingRemoval.isEmpty()) return false
            val now = SystemClock.uptimeMillis()
            val it = pendingRemoval.entries.iterator()
            while (it.hasNext()) {
                val e = it.next()
                if (now - e.value < GRACE_MS) continue
                it.remove()
                if (regions.remove(e.key) != null) changed = true
            }
            if (pendingRemoval.isNotEmpty()) onMain { scheduleSweep() }
        }
        if (changed) rebuildAndInvalidate()
        return changed
    }

    private fun scheduleSweep() {
        if (sweepScheduled) return
        sweepScheduled = true
        mainHandler.postDelayed(sweepRunnable, GRACE_MS)
    }

    // ──────────────────────────────────────────────────────────────
    // La capa de dibujo
    // ──────────────────────────────────────────────────────────────

    private fun rebuildAndInvalidate() {
        // Foto inmutable de las regiones. onDraw() la lee sin tomar ningún lock: dibujar
        // corre en el hilo de UI y no puede quedarse esperando a un escaneo de la IA que
        // está trabajando en otro hilo.
        val snapshot: Array<Rect> = synchronized(lock) {
            if (regions.isEmpty()) {
                EMPTY_RECTS
            } else {
                val arr = Array(regions.size) { Rect() }
                var i = 0
                for (r in regions.values) arr[i++].set(r)
                arr
            }
        }
        drawList = snapshot

        onMain {
            val needed = snapshot.isNotEmpty() || strictCover != null
            if (needed) {
                ensureOverlayAttached()
                overlayView?.invalidate()
            } else {
                detachOverlay()
            }
        }
    }

    private fun ensureOverlayAttached() {
        if (overlayView != null) return
        val view = RegionOverlayView(service)
        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            // NOT_TOUCHABLE es imprescindible: la capa cubre toda la pantalla, así que
            // sin esto el usuario no podría tocar nada de la app de abajo.
            WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                // Coordenadas absolutas de pantalla: hacen que lo que dibujamos coincida
                // con lo que devuelve AccessibilityNodeInfo.getBoundsInScreen(), sin
                // corrimiento por barra de estado ni por el notch.
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS or
                WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = 0
            y = 0
        }
        try {
            windowManager.addView(view, params)
            overlayView = view
        } catch (e: Exception) {
            android.util.Log.w("LockSuite_Overlay", "No se pudo crear la capa de tapado: ${e.message}")
        }
    }

    private fun detachOverlay() {
        val view = overlayView ?: return
        overlayView = null
        try {
            windowManager.removeView(view)
        } catch (e: Exception) {
            // ignorado: puede haber sido quitada por el sistema
        }
    }

    private inner class RegionOverlayView(ctx: Context) : View(ctx) {

        private val fill = Paint().apply {
            color = FILL_COLOR
            style = Paint.Style.FILL
            isAntiAlias = false
        }
        private val edge = Paint().apply {
            color = EDGE_COLOR
            style = Paint.Style.STROKE
            strokeWidth = 1f * ctx.resources.displayMetrics.density
            isAntiAlias = false
        }
        private val glyph = Paint().apply {
            color = 0xFF39424F.toInt()
            style = Paint.Style.STROKE
            strokeWidth = 2f * ctx.resources.displayMetrics.density
            isAntiAlias = true
        }

        init {
            setWillNotDraw(false)
            isClickable = false
            isFocusable = false
            // ⚠️ IMPRESCINDIBLE, y es la diferencia con la versión de ventanas chicas.
            //
            // Esta capa ocupa TODA la pantalla. Si apareciera en el árbol de
            // accesibilidad, `rootInActiveWindow` podría devolverla a ella en vez de la
            // app de abajo: el escaneo no encontraría ninguna imagen, se borrarían todas
            // las regiones, la capa se quitaría, el escaneo siguiente volvería a
            // encontrar las imágenes y la capa se crearía otra vez. Un ciclo de
            // parpadeo alimentado por el propio filtro.
            //
            // Con esto la ventana existe para el compositor pero es invisible para el
            // sistema de accesibilidad. (Hay una segunda red: `runLayer1NodeBlocking()`
            // corta si el paquete de la ventana raíz es el nuestro. Las dos, porque el
            // costo de equivocarse acá es un parpadeo permanente en pantalla.)
            importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS
        }

        override fun onDraw(canvas: Canvas) {
            strictCover?.let { cover ->
                canvas.drawRect(cover, fill)
            }
            val list = drawList
            for (r in list) {
                canvas.drawRect(r, fill)
                canvas.drawRect(
                    r.left + 0.5f, r.top + 0.5f, r.right - 0.5f, r.bottom - 0.5f, edge
                )
                // Ícono de "contenido oculto" solo en recuadros grandes: en una miniatura
                // de 40 px sería ruido, y dibujarlo en todos costaría de más.
                if (r.width() >= GLYPH_MIN_PX && r.height() >= GLYPH_MIN_PX) {
                    val cx = r.exactCenterX()
                    val cy = r.exactCenterY()
                    val radius = (minOf(r.width(), r.height()) * 0.10f).coerceAtMost(44f)
                    canvas.drawCircle(cx, cy, radius, glyph)
                    canvas.drawLine(
                        cx - radius * 0.9f, cy + radius * 0.9f,
                        cx + radius * 0.9f, cy - radius * 0.9f,
                        glyph
                    )
                }
            }
        }
    }

    // ──────────────────────────────────────────────
    // Pantalla negra de actualizacion de apps
    //
    // Esto es una ventana APARTE de la capa de regiones: es opaca, a pantalla
    // completa y tiene que absorber el 100% de los toques, así que no comparte
    // nada con lo de arriba.
    //
    // Cambios del 16/8/2026:
    //  • Titulo y subtitulo actualizables por separado, para poder ir contando
    //    en que etapa esta la actualizacion en vez de un texto fijo.
    //  • Boton "Cancelar" opcional. El fondo sigue consumiendo el 100% de los
    //    toques (setOnTouchListener del contenedor), pero los hijos reciben el
    //    toque antes que el padre, asi que el boton si responde.
    //  • showBlockingMessageOverlay() ya no se va en silencio si el overlay ya
    //    existe: refresca los textos.
    // ──────────────────────────────────────────────

    private var blockingCancelable = false
    private var blockingCancelAction: (() -> Unit)? = null
    private var blockingView: android.widget.LinearLayout? = null

    fun isBlockingMessageVisible(): Boolean = blockingView != null

    @JvmOverloads
    fun showBlockingMessageOverlay(
        title: String,
        subtitle: String? = null,
        cancelable: Boolean = false,
        onCancel: (() -> Unit)? = null
    ) {
        onMain {
            blockingCancelAction = onCancel

            val existing = blockingView
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
                removeBlockingView()
            }
            blockingCancelable = cancelable

            val density = service.resources.displayMetrics.density
            fun dp(value: Int): Int = (value * density).toInt()

            val view = android.widget.LinearLayout(service).apply {
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
                windowManager.addView(view, params)
                blockingView = view
                android.util.Log.i("LockSuite_Overlay", "Overlay de actualizacion mostrado (cancelable=$cancelable)")
            } catch (e: Exception) {
                android.util.Log.e("LockSuite_Overlay", "Error al agregar overlay de actualizacion: ${e.message}", e)
            }
        }
    }

    /** Actualiza titulo y/o subtitulo de la pantalla negra si esta visible. */
    fun updateBlockingMessage(title: String?, subtitle: String?) {
        onMain {
            val overlay = blockingView ?: return@onMain
            if (title != null) overlay.findViewWithTag<android.widget.TextView>("title")?.text = title
            if (subtitle != null) overlay.findViewWithTag<android.widget.TextView>("subtitle")?.text = subtitle
        }
    }

    fun updateBlockingMessageSubtitle(subtitle: String) = updateBlockingMessage(null, subtitle)

    fun hideBlockingMessageOverlay() {
        onMain {
            blockingCancelAction = null
            blockingCancelable = false
            removeBlockingView()
        }
    }

    private fun removeBlockingView() {
        val view = blockingView ?: return
        blockingView = null
        try {
            windowManager.removeView(view)
        } catch (e: Exception) {
            // ignorado
        }
    }

    /** Saca todo de pantalla: regiones, tapado estricto y la pantalla de actualización. */
    fun clearAll() {
        synchronized(lock) {
            regions.clear()
            staged.clear()
            pendingRemoval.clear()
        }
        drawList = EMPTY_RECTS
        strictCover = null
        onMain {
            mainHandler.removeCallbacks(sweepRunnable)
            sweepScheduled = false
            detachOverlay()
            removeBlockingView()
            blockingCancelAction = null
            blockingCancelable = false
        }
    }
}
