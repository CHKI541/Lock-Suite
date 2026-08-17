package com.ejemplo.locksuite.service

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.AccessibilityServiceInfo
import android.accessibilityservice.AccessibilityService.TakeScreenshotCallback
import android.accessibilityservice.AccessibilityService.ScreenshotResult
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.Rect
import android.net.Uri
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.os.SystemClock
import android.util.Log
import android.view.Display
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import android.widget.Toast
import com.ejemplo.locksuite.mdm.PolicyManager
import com.ejemplo.locksuite.mdm.WebViewBlockManager
import com.ejemplo.locksuite.mdm.ImageBlockManager
import com.ejemplo.locksuite.util.PrefsHelper
import com.ejemplo.locksuite.util.UpdateFlowManager
import com.ejemplo.locksuite.util.PlayButtonFinder
import com.ejemplo.locksuite.util.PlayUpdateSessionWatcher
import java.util.concurrent.Executors

/**
 * Capa 3 — Servicio de Accesibilidad.
 *
 * ADVERTENCIA DE RENDIMIENTO PARA QUIEN EDITE ESTE ARCHIVO
 * ────────────────────────────────────────────────────────
 * `onAccessibilityEvent` se ejecuta EN EL HILO PRINCIPAL y el sistema lo llama
 * hasta ~10 veces por segundo (notificationTimeout = 100 ms) mientras el usuario
 * usa el teléfono. Todo lo que se agregue en el camino directo del evento se paga
 * multiplicado por diez, cada segundo, con la pantalla encendida. Reglas de la casa:
 *
 *   • Nada de `new` en el camino caliente: ni PolicyManager, ni getSystemService,
 *     ni concatenación de strings para logs. Todo cacheado en campos.
 *   • Nada de consultas al PackageManager por evento. Se cachean con TTL.
 *   • Todo recorrido del árbol de nodos lleva tope de profundidad Y tope de nodos.
 *   • Los logs de diagnóstico van detrás de `if (VERBOSE)`. Como VERBOSE es una
 *     constante `false`, R8 elimina por completo el bloque en la versión final.
 *   • El snapshot de configuración se lee una vez por ráfaga desde SharedPreferences,
 *     no por evento individual.
 */
class LockSuiteAccessibilityService : AccessibilityService() {

    companion object {
        @Volatile var instance: LockSuiteAccessibilityService? = null
        private const val TAG = "LockSuite_WV"

        /**
         * Logs de diagnóstico del camino caliente. Dejar en `false` para producción.
         * Al ser `const`, el compilador de Kotlin y R8 eliminan por completo los
         * bloques `if (VERBOSE) { ... }` — no queda ni la rama ni el string.
         * (El proyecto tiene `buildConfig = false`, por eso no se usa BuildConfig.DEBUG.)
         */
        private const val VERBOSE = false

        private const val SETTINGS_PKG = "com.android.settings"
        private const val LOCKSUITE_PKG = "com.ejemplo.locksuite"
        private const val DEBOUNCE_MS = 300L
        /**
         * Debounce del RE-ESCANEO del árbol durante el scroll.
         *
         * Ojo con este número: desde el 17/8 los recuadros ya no dependen de él para
         * seguir al contenido. El evento de scroll trae su propio delta en píxeles
         * (`getScrollDeltaX/Y`), así que los recuadros se corren ese delta al instante,
         * en cada evento, gratis — sin tocar el árbol de nodos. Este debounce solo
         * gobierna cada cuánto se hace el recorrido REAL que corrige la posición.
         *
         * Antes era 50 ms (20 recorridos por segundo) porque el recorrido era la única
         * forma de mover un recuadro. Ahora 90 ms alcanza y sobra: se ve igual de pegado
         * y se recorre el árbol la mitad de veces. Subilo si el scroll se siente pesado
         * en algún equipo; no hace falta bajarlo para ganar fluidez.
         */
        private const val IMAGE_SCROLL_DEBOUNCE_MS = 90L

        /**
         * Cuánto silencio de eventos de scroll se espera para dar el desplazamiento por
         * terminado y quitar el tapado estricto del contenedor (modo estricto opcional).
         */
        private const val STRICT_SCROLL_SETTLE_MS = 220L
        private const val PKG_WHATSAPP = "com.whatsapp"
        private const val PKG_WHATSAPP_BUSINESS = "com.whatsapp.w4b"
        private const val PKG_PLAY_STORE = "com.android.vending"

        /** Vida útil del cache de "qué paquetes son navegadores". */
        private const val BROWSER_CACHE_TTL_MS = 10 * 60 * 1000L

        /** Vida útil del snapshot de flags, como red de seguridad además del listener. */
        private const val FLAGS_MAX_AGE_MS = 3_000L

        /** Topes de seguridad para cualquier recorrido del árbol de accesibilidad. */
        private const val MAX_TREE_DEPTH = 40
        private const val MAX_NODES_PER_SCAN = 2_500

        /** Cada cuánto se puede re-armar la escalera de reintentos de WebView. */
        private const val WEBVIEW_REARM_MS = 2_000L

        /** Pausa tras un rebote fallido en Mercado Pago, para no encadenar "atrás". */
        private const val MP_BACKOFF_MS = 4_000L

        /** Pausa tras tener que forzar HOME desde el menú de Accesibilidad de Ajustes. */
        private const val ACC_BOUNCE_BACKOFF_MS = 3_000L

        // ── Flujo de actualización por Play Store ──
        /** Mínimo entre dos relanzamientos de Play Store si el usuario se va a otra app. */
        private const val STORE_RELAUNCH_MIN_MS = 1_500L
        /** Mínimo entre dos clics automáticos, para no encadenar toques sobre la misma pantalla. */
        private const val UPDATE_CLICK_COOLDOWN_MS = 1_500L
        /** Con "Abrir" en pantalla y sin haber hecho nada, se da por ya actualizada pasado esto. */
        private const val UPDATE_UP_TO_DATE_GRACE_MS = 3_000L
        /** Sin haber podido clickear ni ver progreso pasado esto, se aborta y se destapa la pantalla. */
        private const val UPDATE_STALL_MS = 75_000L
        /** Cada cuánto el flujo vuelve a mirar la pantalla, sin depender de eventos. */
        private const val UPDATE_TICK_MS = 700L
        /** Cuánto se espera tras apretar un candidato antes de probar el siguiente. */
        private const val CANDIDATE_WAIT_MS = 2_500L
        /** Cuántos candidatos distintos se prueban antes de rendirse. */
        private const val MAX_CANDIDATES = 6
        /** Cuántos padres se suben como máximo buscando quién acepta el clic. */
        private const val CLICK_PARENT_DEPTH = 6

        // Paquetes que actúan como renderizadores de WebView del sistema
        private val WEBVIEW_PROVIDER_PACKAGES = setOf(
            "com.google.android.webview",
            "com.android.webview",
            "com.android.chrome",
            "com.google.android.apps.chrome"
        )

        // Paquetes de navegadores conocidos
        private val KNOWN_BROWSER_PACKAGES = setOf(
            "com.android.chrome",
            "com.chrome.beta",
            "org.mozilla.firefox",
            "org.mozilla.focus",
            "com.opera.browser",
            "com.opera.mini.native",
            "com.microsoft.emmx",
            "com.sec.android.app.sbrowser",
            "com.brave.browser",
            "com.duckduckgo.mobile.android",
            "com.android.browser",
            "com.UCMobile.intl",
            "com.kiwibrowser.browser",
            "com.android.htmlviewer"
        )

        private const val PKG_MERCADOPAGO = "com.mercadopago.wallet"

        // ──────────────────────────────────────────────────────────────
        // Detección de la sección "Ofertas" de Mercado Pago
        //
        // Antes acá había una sola lista de 14 palabras sueltas y bastaba con que
        // CUALQUIERA apareciera en CUALQUIER nodo de la pantalla para rebotar al
        // usuario. Palabras como "beneficio", "descuento" o "supermercado" están
        // en la pantalla de inicio de Mercado Pago y en varios flujos de pago, así
        // que el servicio también expulsaba al usuario de pantallas legítimas.
        //
        // Ahora hay tres niveles:
        //   FUERTE  → una sola coincidencia alcanza (son títulos de sección propios).
        //   DÉBIL   → hacen falta DOS palabras distintas para considerarlo promociones.
        //   VIEW ID → identificadores de vista de la propia app, sin ambigüedad.
        //
        // Además, dentro de una pantalla WebView de Mercado Pago (donde casi no hay
        // texto accesible) alcanza con UNA palabra débil: es la red de seguridad para
        // que la sección de ofertas real, que se renderiza como web, no se escape.
        //
        // Todas las cadenas van en minúscula y SIN tildes: el texto de pantalla se
        // normaliza con foldAccents() antes de comparar, así "promoción" y "promocion"
        // matchean igual. No agregar acá cadenas con tilde: nunca coincidirían.
        // ──────────────────────────────────────────────────────────────
        private val MP_OFFERS_STRONG = listOf(
            "novedades y ofertas",
            "ofertas y descuentos",
            "descuentos y promociones",
            "beneficios y descuentos",
            "cupones de descuento",
            "mercado puntos",
            "tus beneficios",
            "mis beneficios",
            "tus descuentos",
            "canjea tus puntos"
        )

        private val MP_OFFERS_WEAK = listOf(
            "oferta", "ofertas",
            "promocion", "promociones",
            "descuento", "descuentos",
            "cupon", "cupones",
            "beneficio", "beneficios",
            "recompensa", "recompensas",
            "reintegro", "reintegros",
            "supermercado", "puntos"
        )

        private val MP_OFFERS_VIEW_ID_HINTS = listOf(
            "offers", "offer_", "discounts", "promos", "promotions",
            "loyalty", "benefits", "coupon", "mercadopuntos", "deals"
        )
    }

    private val mainHandler = Handler(Looper.getMainLooper())
    private var lastCheckedAt = 0L
    /** Último reposicionamiento de recuadros de imágenes por scroll (elapsedRealtime). */
    private var lastImageScrollAt = 0L

    private val whatsappScanRunnable = Runnable {
        val f = flags()
        if (f.waStatus || f.waChannels) {
            scanForUpdatesTab(f.waStatus, f.waChannels)
        }
    }

    // Stack de paquetes activos: [0] = el más reciente que NO es browser/webview-provider
    private val appPackageStack = ArrayDeque<String>(5)

    // Guardas de rebote separadas por función. Antes había UNA sola compartida, así que
    // un bloqueo de WhatsApp en curso hacía que se perdiera en silencio un bloqueo de
    // Mercado Pago o de WebView que ocurriera en esos 700 ms.
    @Volatile private var webViewBlockInProgress = false
    @Volatile private var waBlockInProgress = false
    @Volatile private var mpBlockInProgress = false

    // Componentes del Bloqueador de Imágenes
    lateinit var overlayManager: BlockOverlayManager
    private lateinit var aiGate: AIContentGate
    private val bgExecutor = Executors.newSingleThreadExecutor()

    private var lastAiScanAt = 0L
    private val aiScanIntervalMs = 900L

    private val gridCols = 4
    private val gridRows = 6
    private val skinRatioThreshold = 0.06f

    // ──────────────────────────────────────────────
    // Servicios del sistema cacheados
    //
    // getSystemService() no es gratis: hace una búsqueda por nombre y, la primera vez,
    // una llamada al ServiceManager. Antes se llamaba en cada evento.
    // ──────────────────────────────────────────────
    private val powerManager: PowerManager by lazy {
        getSystemService(Context.POWER_SERVICE) as PowerManager
    }

    /**
     * Instancia única de PolicyManager. Antes se construía una nueva
     * (`PolicyManager(applicationContext)`) en CADA evento y en cada runnable de
     * escaneo — hasta cuatro por evento. Su constructor resuelve el DevicePolicyManager
     * y arma un ComponentName, así que eran decenas de objetos por segundo tirados a la
     * basura, con la presión de GC que eso implica.
     *
     * Cachear la instancia NO congela la configuración: PolicyManager lee las
     * SharedPreferences en cada getter, así que sigue viendo los cambios al instante.
     */
    private val policyManager: PolicyManager by lazy { PolicyManager(applicationContext) }

    private val mdmPrefs: SharedPreferences by lazy { PrefsHelper.getMdmPrefs(applicationContext) }

    // ──────────────────────────────────────────────
    // Snapshot de configuración
    //
    // Cada evento leía entre 3 y 8 booleanos de SharedPreferences. Ahora se leen una
    // vez y se guardan; el snapshot se invalida solo cuando algo escribe en las prefs
    // (listener) y, por las dudas, se vuelve a leer si tiene más de FLAGS_MAX_AGE_MS.
    // ──────────────────────────────────────────────
    private class Flags(
        val suspended: Boolean,
        val waStatus: Boolean,
        val waChannels: Boolean,
        val mpOffers: Boolean,
        val settingsEvasion: Boolean,
        val installBlocked: Boolean,
        val playStoreSuspended: Boolean,
        val vendingHidden: Boolean,
        val vendingSuspended: Boolean,
        val updateInProgress: Boolean,
        val updatingPkg: String?,
        /** "Tapado estricto al desplazar": tapa el contenedor entero mientras dura el fling. */
        val strictScroll: Boolean,
        /** Rebotar al usuario si entra al menú de Accesibilidad de Ajustes. */
        val accSettingsBounce: Boolean,
        val takenAt: Long
    )

    @Volatile private var cachedFlags: Flags? = null

    // Se guarda como campo porque SharedPreferences mantiene los listeners con
    // referencias débiles: si no lo retenemos, el recolector se lo lleva y dejamos
    // de enterarnos de los cambios de configuración.
    private val prefsListener =
        SharedPreferences.OnSharedPreferenceChangeListener { _, _ -> cachedFlags = null }

    private fun flags(): Flags {
        val cached = cachedFlags
        if (cached != null && SystemClock.elapsedRealtime() - cached.takenAt < FLAGS_MAX_AGE_MS) {
            return cached
        }
        val p = mdmPrefs
        val fresh = Flags(
            suspended = policyManager.isLockSuiteSuspended(),
            waStatus = policyManager.isWhatsAppBlockStatusEnabled(),
            waChannels = policyManager.isWhatsAppBlockChannelsEnabled(),
            mpOffers = policyManager.isMercadoPagoBlockOffersAccessibilityEnabled(),
            settingsEvasion = p.getBoolean("settings_evasion_enabled", false),
            installBlocked = policyManager.isInstallAppsBlocked(),
            playStoreSuspended = policyManager.isPlayStoreSuspended(),
            vendingHidden = p.getBoolean("hide_$PKG_PLAY_STORE", false),
            vendingSuspended = p.getBoolean("suspend_$PKG_PLAY_STORE", false),
            updateInProgress = p.getBoolean("mdm_install_in_progress", false),
            updatingPkg = p.getString("updating_package", null),
            strictScroll = p.getBoolean("image_block_strict_scroll", false),
            accSettingsBounce = p.getBoolean("acc_protect_bounce_settings", false),
            takenAt = SystemClock.elapsedRealtime()
        )
        cachedFlags = fresh
        return fresh
    }

    // ──────────────────────────────────────────────
    // Cache de clasificación de paquetes
    //
    // isBrowserPackage() hacía un packageManager.queryIntentActivities(MATCH_ALL) —
    // una llamada IPC que enumera TODAS las actividades capaces de abrir https en el
    // equipo — por cada paquete desconocido. Y se la llamaba desde trackPackage(), o
    // sea EN CADA EVENTO DE ACCESIBILIDAD. Era, de lejos, el mayor consumo de CPU del
    // servicio. Ahora se consulta una sola vez cada BROWSER_CACHE_TTL_MS y se guarda
    // el conjunto completo; la comprobación por evento pasa a ser un lookup O(1).
    // ──────────────────────────────────────────────
    @Volatile private var browserPkgCache: Set<String> = emptySet()
    private var browserPkgCacheAt = 0L

    private val sysInputCache = HashMap<String, Boolean>(32)

    private fun browserPackages(): Set<String> {
        val now = SystemClock.elapsedRealtime()
        if (browserPkgCacheAt != 0L && now - browserPkgCacheAt < BROWSER_CACHE_TTL_MS) {
            return browserPkgCache
        }
        val resolved = try {
            val intent = Intent(Intent.ACTION_VIEW, Uri.parse("https://www.google.com"))
            val list = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                packageManager.queryIntentActivities(
                    intent,
                    PackageManager.ResolveInfoFlags.of(PackageManager.MATCH_ALL.toLong())
                )
            } else {
                @Suppress("DEPRECATION")
                packageManager.queryIntentActivities(intent, PackageManager.MATCH_ALL)
            }
            list.mapTo(HashSet()) { it.activityInfo.packageName }
        } catch (e: Exception) {
            emptySet<String>()
        }
        browserPkgCache = resolved
        browserPkgCacheAt = now
        return resolved
    }

    private fun isBrowserPackage(packageName: String): Boolean {
        if (KNOWN_BROWSER_PACKAGES.contains(packageName)) return true
        return browserPackages().contains(packageName)
    }

    private val updateTickRunnable = object : Runnable {
        override fun run() {
            val ctx = applicationContext
            val pkg = UpdateFlowManager.currentPackage(ctx)
            if (pkg.isNullOrBlank() || !UpdateFlowManager.isRunning(ctx)) {
                return   // sin re-encolar: el flujo terminó
            }
            try {
                scanAndAct(pkg)
            } catch (e: Exception) {
                Log.w(TAG, "tick de actualización: ${e.message}")
            }
            mainHandler.postDelayed(this, UPDATE_TICK_MS)
        }
    }

    /** La llama UpdateFlowManager.start(). */
    fun startUpdateTicker() {
        mainHandler.removeCallbacks(updateTickRunnable)
        mainHandler.post(updateTickRunnable)
    }

    /** La llama UpdateFlowManager.finish(). Tiene que estar en TODOS los caminos de salida. */
    fun stopUpdateTicker() {
        mainHandler.removeCallbacks(updateTickRunnable)
    }

    // ──────────────────────────────────────────────
    // Configuración del servicio
    // ──────────────────────────────────────────────
    override fun onServiceConnected() {
        // Modificar el serviceInfo existente para preservar capacidades cargadas del XML (canTakeScreenshot)
        val info = serviceInfo ?: AccessibilityServiceInfo()
        info.eventTypes = AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED or
                          AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED or
                          AccessibilityEvent.TYPE_VIEW_SELECTED or
                          // Necesario para que los recuadros de imágenes sigan al
                          // contenido durante el scroll en vez de quedarse en el lugar.
                          AccessibilityEvent.TYPE_VIEW_SCROLLED
        info.feedbackType = AccessibilityServiceInfo.FEEDBACK_GENERIC
        info.flags = info.flags or
                     AccessibilityServiceInfo.FLAG_REPORT_VIEW_IDS or
                     AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS or
                     AccessibilityServiceInfo.FLAG_INCLUDE_NOT_IMPORTANT_VIEWS
        info.notificationTimeout = 100
        serviceInfo = info

        // IMPORTANTE: overlayManager se inicializa ANTES de publicar `instance`.
        // PackageReceiver hace `instance?.overlayManager`, y si `instance` quedaba
        // publicado primero había una ventana real en la que ese acceso reventaba con
        // UninitializedPropertyAccessException.
        overlayManager = BlockOverlayManager(this)
        aiGate = AIContentGate(applicationContext)

        mdmPrefs.registerOnSharedPreferenceChangeListener(prefsListener)
        cachedFlags = null

        val p = PrefsHelper.getMdmPrefs(this)
        if (!p.contains("accessibility_protection_enabled")) {
            p.edit().putBoolean("accessibility_protection_enabled", true).apply()
            try { policyManager.applyAccessibilityProtection(true) } catch (e: Exception) { }
        }

        instance = this

        Log.i(TAG, "✅ LockSuiteAccessibilityService conectado (Programmatic config + XML capabilities)")
    }

    // ──────────────────────────────────────────────
    // Evento principal
    //
    // Orden deliberado: primero lo que descarta el evento con el menor costo posible,
    // después lo caro. Cada `return` temprano ahorra trabajo diez veces por segundo.
    // ──────────────────────────────────────────────
    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (!::overlayManager.isInitialized) return
        val ev = event ?: return
        val packageName = ev.packageName?.toString() ?: return

        // Ignorar nuestra propia app
        if (packageName == LOCKSUITE_PKG) return

        // Filtro de tipo de evento adelantado: es una comparación de enteros y descarta
        // el evento antes de tocar SharedPreferences o el PackageManager.
        val eventType = ev.eventType
        if (eventType != AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED &&
            eventType != AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED &&
            eventType != AccessibilityEvent.TYPE_VIEW_SELECTED &&
            eventType != AccessibilityEvent.TYPE_VIEW_SCROLLED) return

        val f = flags()

        // ──────────────────────────────────────────────
        // LockSuite SUSPENDIDO
        //
        // El administrador levantó todas las restricciones a propósito (modo
        // "como si LockSuite no estuviera"). Mientras dure la suspensión, este
        // servicio no bloquea, no rebota, no automatiza y no dibuja nada: lo
        // único que hace es asegurarse de no dejar ninguna ventana negra suya
        // colgada en pantalla de antes de la suspensión.
        // ──────────────────────────────────────────────
        if (f.suspended) {
            if (overlayManager.isBlockingMessageVisible()) {
                overlayManager.hideBlockingMessageOverlay()
            }
            if (overlayManager.hasRegions("")) {
                overlayManager.clearAll()
            }
            return
        }

        // Bloqueo y automatización durante actualización de apps (UPDATE_APP)
        // NOTA: este check va ANTES del check de pantalla apagada, porque UPDATE_APP
        // puede llegar con la pantalla apagada y necesitamos procesar los eventos.
        val updatingPkg = f.updatingPkg
        if (f.updateInProgress && !updatingPkg.isNullOrBlank()) {
            // Asegurar que la pantalla esté encendida durante la actualización
            ensureScreenOnForUpdate()
            if (packageName == PKG_PLAY_STORE) {
                handlePlayStoreAutoUpdate(ev, updatingPkg)
                return
            } else if (packageName != "com.google.android.gms" &&
                packageName != "com.google.android.packageinstaller" &&
                packageName != "com.android.packageinstaller" &&
                packageName != "com.android.systemui") {
                // La actualización sigue en curso pero al frente hay otra app.
                //
                // Dos correcciones respecto de la versión anterior:
                //
                //  1. El overlay se dibuja TAMBIÉN acá. Antes solo se dibujaba
                //     dentro de handlePlayStoreAutoUpdate(), o sea únicamente
                //     cuando el evento venía de Play Store: si el usuario salía a
                //     otra app veía esa app sin tapar.
                //
                //  2. El relanzamiento de Play Store está limitado a uno cada
                //     STORE_RELAUNCH_MIN_MS. Antes se relanzaba en CADA evento
                //     (el sistema los manda hasta diez veces por segundo) y encima
                //     con FLAG_ACTIVITY_CLEAR_TASK, así que la tarea de Play Store
                //     se destruía y recreaba sin parar y la descarga no llegaba a
                //     arrancar nunca. Es la causa más probable del síntoma "la app
                //     no se actualiza". Ahora openStore() usa solo NEW_TASK, que
                //     trae la tarea existente al frente en vez de recrearla.
                UpdateFlowManager.showOverlay(
                    applicationContext,
                    updatingPkg,
                    UpdateFlowManager.currentStage(applicationContext)
                )
                val nowRelaunch = SystemClock.elapsedRealtime()
                if (nowRelaunch - lastStoreRelaunchAt > STORE_RELAUNCH_MIN_MS) {
                    lastStoreRelaunchAt = nowRelaunch
                    UpdateFlowManager.openStore(applicationContext, updatingPkg)
                }
                return
            }
        }

        // Garantizar que no consuma batería si la pantalla está inactiva (apagada)
        // Esto va DESPUÉS del check de UPDATE_APP, que sí necesita funcionar con pantalla apagada
        if (!powerManager.isInteractive) {
            return
        }

        // ── Scroll: mantener los recuadros de imágenes pegados al contenido ──
        // TYPE_VIEW_SCROLLED se emite muchísimas veces durante un desplazamiento y no
        // dispara los demás bloqueos (WhatsApp/MP/WebView/Ajustes), así que se maneja
        // aparte y barato. Detalle en handleScrollEvent().
        if (eventType == AccessibilityEvent.TYPE_VIEW_SCROLLED) {
            handleScrollEvent(ev, packageName, f)
            return
        }

        // Bloqueo estricto de Play Store fuera del flujo de actualización
        if (!f.updateInProgress && packageName == PKG_PLAY_STORE) {
            val shouldBlock = f.installBlocked || f.playStoreSuspended ||
                f.vendingHidden || f.vendingSuspended
            if (shouldBlock) {
                Log.w(TAG, "🚫 Intento no autorizado de abrir Google Play Store. Bloqueando y regresando a Home...")
                overlayManager.hideBlockingMessageOverlay()
                performGlobalAction(GLOBAL_ACTION_HOME)
                policyManager.restoreInstallRestrictions()
                return
            }
        }

        // ── Actualizar stack de paquetes de apps reales ──
        trackPackage(packageName)

        // ── Bloqueo de Estados y Canales en WhatsApp ──
        if ((f.waStatus || f.waChannels) &&
            (packageName == PKG_WHATSAPP || packageName == PKG_WHATSAPP_BUSINESS)) {
            handleWhatsAppBlocking(eventType, ev, f.waStatus, f.waChannels)
        }

        // ── Bloqueo de Ofertas en Mercado Pago ──
        if (f.mpOffers && packageName == PKG_MERCADOPAGO) {
            handleMercadoPagoBlocking(eventType)
        }

        // Debounce para CONTENT_CHANGED (se dispara muy seguido)
        if (eventType == AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED) {
            val now = SystemClock.elapsedRealtime()
            if (now - lastCheckedAt < DEBOUNCE_MS) return
            lastCheckedAt = now
        }

        if (VERBOSE) {
            Log.d(TAG, "EVENT pkg=$packageName type=${eventType.toEventName()} stack=${appPackageStack.toList()}")
        }

        // ── Bloqueo de WebView ──
        handleWebViewBlocking(packageName, eventType)

        // ── Rebote del menú de Accesibilidad de Ajustes ──
        // Va ANTES de la anti-evasión genérica porque es más específico y más barato:
        // en el caso normal (95 % de las pantallas de Ajustes) se resuelve mirando el
        // nombre de la clase de la ventana, sin tocar el árbol.
        if (f.accSettingsBounce && isSettingsPackage(packageName)) {
            if (handleAccessibilitySettingsBounce(ev, eventType)) return
        }

        // ── Anti-evasión en Ajustes ──
        if (f.settingsEvasion && packageName == SETTINGS_PKG) {
            handleSettingsAntiEvasion()
        }

        // ── Códigos secretos en el marcador ──
        if (packageName.contains("dialer", ignoreCase = true) ||
            packageName.contains("phone", ignoreCase = true) ||
            packageName.contains("contact", ignoreCase = true)) {
            handleDialerIntercept()
        }

        // ── Bloqueo de Imágenes por App (Capa 1 y Capa 2) ──
        handleImageBlocking(packageName)
    }

    // ──────────────────────────────────────────────
    // Camino rápido de scroll
    //
    // El problema que resuelve (reportado por el dueño con captura): el recuadro negro
    // "tarda en enganchar y queda en su lugar cuando bajás". Antes la ÚNICA forma de
    // mover un recuadro era volver a recorrer el árbol de nodos y volver a pedirle al
    // sistema que reubicara la ventana. Recorrer el árbol cuesta milisegundos, así que
    // solo se podía hacer unas pocas veces por segundo — y mientras tanto el contenido
    // ya se había movido. Por definición el recuadro llegaba tarde.
    //
    // Ahora hay dos velocidades:
    //
    //   RÁPIDA (cada evento, microsegundos): el propio evento de scroll trae cuántos
    //   píxeles se desplazó el contenido (`getScrollDeltaX/Y`, API 28+). Con ese número
    //   se corren TODOS los recuadros de una, en memoria, y se repinta la capa. No hay
    //   recorrido de árbol ni llamada al WindowManager.
    //
    //   LENTA (cada IMAGE_SCROLL_DEBOUNCE_MS): el recorrido real, que corrige la
    //   posición y descubre imágenes nuevas que entraron a pantalla.
    //
    // En equipos con Android 8 (sin `getScrollDelta*`) no hay camino rápido y queda solo
    // el recorrido con debounce, que es exactamente el comportamiento anterior.
    // ──────────────────────────────────────────────
    private var lastScrollPkg: String? = null

    private val strictScrollSettleRunnable = Runnable {
        overlayManager.setStrictCover(null)
        lastScrollPkg?.let { handleImageBlocking(it) }
    }

    private fun handleScrollEvent(ev: AccessibilityEvent, packageName: String, f: Flags) {
        if (f.updateInProgress) return
        // Chequeo O(1): si no hay nada tapado, este evento no nos interesa.
        if (overlayManager.regionCount() == 0 && !overlayManager.hasStrictCover()) return
        lastScrollPkg = packageName

        // 1. Adelanto por delta del propio evento.
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            val dx = ev.scrollDeltaX
            val dy = ev.scrollDeltaY
            // -1 es el valor centinela de "el emisor no informó el delta".
            if (dx != -1 || dy != -1) {
                overlayManager.translateRegions(
                    if (dx == -1) 0 else dx,
                    if (dy == -1) 0 else dy
                )
            }
        }

        // 2. Tapado estricto opcional mientras dura el desplazamiento.
        if (f.strictScroll) {
            val src = ev.source
            if (src != null) {
                try {
                    val bounds = Rect()
                    src.getBoundsInScreen(bounds)
                    if (!bounds.isEmpty) overlayManager.setStrictCover(bounds)
                } finally {
                    src.recycle()
                }
            }
            mainHandler.removeCallbacks(strictScrollSettleRunnable)
            mainHandler.postDelayed(strictScrollSettleRunnable, STRICT_SCROLL_SETTLE_MS)
        }

        // 3. Corrección real, con debounce.
        val nowScroll = SystemClock.elapsedRealtime()
        if (nowScroll - lastImageScrollAt < IMAGE_SCROLL_DEBOUNCE_MS) return
        lastImageScrollAt = nowScroll
        handleImageBlocking(packageName)
    }

    // ──────────────────────────────────────────────
    // Lógica de Bloqueo de Imágenes por App
    // ──────────────────────────────────────────────
    private fun handleImageBlocking(packageName: String) {
        val isProvider = packageName == "com.google.android.webview" || packageName == "com.android.webview"
        val activePkg = if (isProvider) (getOriginApp() ?: packageName) else packageName
        val isMaps = activePkg == "com.google.android.apps.maps"
        val mapsBlocking = isMaps && ImageBlockManager.isMapsImageBlockingEnabled(applicationContext)

        val mode = if (mapsBlocking) "both" else ImageBlockManager.getMode(applicationContext, activePkg)

        // 1. Capa 1: Bloqueo por Nodos
        if (mode == "layer1" || mode == "both") {
            runLayer1NodeBlocking(activePkg)
        } else if (overlayManager.hasRegions("layer1:")) {
            // Solo se llama si hay algo que limpiar. Antes se llamaba siempre, lo que
            // encolaba un Runnable en el hilo principal diez veces por segundo aunque
            // el bloqueo de imágenes estuviera apagado en todas las apps.
            overlayManager.clearStaleRegions("layer1:", emptySet())
        }

        // 2. Capa 2: Bloqueo por IA
        val isGlobalAi = ImageBlockManager.isGlobalAiEnabled(applicationContext)
        val isEligible = DeviceCapability.isEligibleForAIBlocking(applicationContext)
        val runAi = ((mode == "layer2" || mode == "both") && isGlobalAi && isEligible) || (mapsBlocking && isGlobalAi && isEligible)

        if (runAi) {
            scheduleAiScanIfDue(activePkg, mapsBlocking)
        } else if (overlayManager.hasRegions("layer2:")) {
            overlayManager.clearStaleRegions("layer2:", emptySet())
        }
    }

    // ──────────────────────────────────────────────
    // Capa 1: Escaneo de Nodos
    // ──────────────────────────────────────────────
    private val visualNodeClassNames = setOf(
        "android.widget.ImageView", "android.widget.VideoView",
        "android.view.SurfaceView", "android.view.TextureView", "android.webkit.WebView"
    )

    /**
     * Ruta del nodo dentro del árbol (índice de hijo en cada nivel). Se usa para armar
     * una clave ESTABLE por cada región tapada.
     *
     * Antes la clave era "layer1:<clase>:<x>,<y>", o sea que incluía la posición. Al
     * hacer scroll, cada imagen cambiaba de posición → cambiaba de clave → el sistema
     * DESTRUÍA la ventana negra y creaba otra, en cada escaneo. addView/removeView son
     * las operaciones más caras del WindowManager, y ese ciclo es exactamente lo que se
     * veía como parpadeo y se sentía "pesado" al desplazarse. Con la ruta como identidad,
     * la misma ventana se limita a moverse (updateViewLayout), o ni eso si no se movió.
     */
    private val pathStack = IntArray(MAX_TREE_DEPTH + 2)
    private var nodeBudget = 0

    private fun runLayer1NodeBlocking(activePkg: String) {
        val root = rootInActiveWindow ?: return
        try {
            val rootPkg = root.packageName?.toString() ?: ""
            if (rootPkg != activePkg && isSystemOrInputPackage(rootPkg)) {
                // Ignorar escaneo si la ventana activa es del sistema o teclado, para no borrar overlays
                return
            }
            val foundKeys = mutableSetOf<String>()
            nodeBudget = MAX_NODES_PER_SCAN
            scanNode(root, 0, foundKeys)
            overlayManager.clearStaleRegions("layer1:", foundKeys)
        } finally {
            root.recycle()
        }
    }

    private fun buildLayer1Key(depth: Int): String {
        val sb = StringBuilder(16)
        sb.append("layer1:")
        for (i in 0 until depth) {
            sb.append(pathStack[i])
            sb.append('.')
        }
        return sb.toString()
    }

    private fun scanNode(node: AccessibilityNodeInfo, depth: Int, foundKeys: MutableSet<String>) {
        if (depth > MAX_TREE_DEPTH) return
        if (nodeBudget-- <= 0) return

        val className = node.className?.toString()
        if (className != null && className in visualNodeClassNames) {
            val rect = Rect()
            node.getBoundsInScreen(rect)
            // Solo tapar lo que realmente se ve: un nodo con área nula o fuera de la
            // pantalla no necesita ventana negra, y crear una era trabajo puro perdido.
            if (!rect.isEmpty && node.isVisibleToUser) {
                val key = buildLayer1Key(depth)
                foundKeys.add(key)
                overlayManager.blockRegion(key, rect)
            }
            // No hace falta descender: el nodo ya quedó tapado entero, y sus hijos
            // (por ejemplo las imágenes dentro de un WebView) están debajo del mismo
            // recuadro negro. Esto recorta buena parte del árbol en pantallas con web.
            return
        }

        val childCount = node.childCount
        for (i in 0 until childCount) {
            val child = node.getChild(i) ?: continue
            try {
                pathStack[depth] = i
                scanNode(child, depth + 1, foundKeys)
            } finally {
                child.recycle()
            }
        }
    }

    // ──────────────────────────────────────────────
    // Capa 2: Escaneo de Pantalla con IA
    // ──────────────────────────────────────────────
    private fun scheduleAiScanIfDue(targetPackageName: String, isMapsStrict: Boolean) {
        val now = SystemClock.elapsedRealtime()
        val interval = if (isMapsStrict) 400L else aiScanIntervalMs
        if (now - lastAiScanAt < interval) return
        lastAiScanAt = now
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return

        takeScreenshot(
            Display.DEFAULT_DISPLAY,
            bgExecutor,
            object : TakeScreenshotCallback {
                override fun onSuccess(result: ScreenshotResult) {
                    val hwBitmap = Bitmap.wrapHardwareBuffer(result.hardwareBuffer, result.colorSpace)
                    result.hardwareBuffer.close()
                    val bitmap = hwBitmap?.copy(Bitmap.Config.ARGB_8888, false)
                    hwBitmap?.recycle()
                    if (bitmap != null) {
                        // Volver a verificar que seguimos en la misma app para evitar falsos positivos
                        val currentRoot = rootInActiveWindow
                        val currentPkg = currentRoot?.packageName?.toString() ?: ""
                        currentRoot?.recycle()
                        if (currentPkg == targetPackageName || isSystemOrInputPackage(currentPkg)) {
                            processScreenshotByGrid(bitmap, isMapsStrict)
                        } else {
                            bitmap.recycle()
                            if (overlayManager.hasRegions("layer2:")) {
                                overlayManager.clearStaleRegions("layer2:", emptySet())
                            }
                        }
                    }
                }
                override fun onFailure(errorCode: Int) {
                    // ERROR_TAKE_SCREENSHOT_INTERVAL_TIME_SHORT es normal
                }
            }
        )
    }

    private fun processScreenshotByGrid(fullScreenBitmap: Bitmap, isMapsStrict: Boolean) {
        bgExecutor.execute {
            val skinMap = TileSkinMap.computeSkinRatioPerTile(fullScreenBitmap, gridCols, gridRows)
            val tileW = fullScreenBitmap.width / gridCols
            val tileH = fullScreenBitmap.height / gridRows
            val currentAiKeys = mutableSetOf<String>()
            val threshold = if (isMapsStrict) 0.02f else skinRatioThreshold

            for (row in 0 until gridRows) {
                for (col in 0 until gridCols) {
                    if (skinMap[row][col] < threshold) continue

                    val tileRect = Rect(
                        col * tileW, row * tileH,
                        if (col == gridCols - 1) fullScreenBitmap.width else (col + 1) * tileW,
                        if (row == gridRows - 1) fullScreenBitmap.height else (row + 1) * tileH
                    )
                    val crop = safeCrop(fullScreenBitmap, tileRect) ?: continue
                    // Usar strictMode = true en AIPersonDetector para que busque siluetas corporales completas
                    val localRegions = aiGate.detectRegions(crop, strictMode = true)
                    crop.recycle()

                    localRegions.forEach { detected ->
                        // 1. Trasladar coordenadas de celda a pantalla completa
                        val screenRect = Rect(
                            tileRect.left + detected.rect.left, tileRect.top + detected.rect.top,
                            tileRect.left + detected.rect.right, tileRect.top + detected.rect.bottom
                        )
                        val screenRegion = DetectedRegion(screenRect, detected.source)

                        // 2. Expandir el área (para ocultar figura/cuerpo)
                        val expandedRect = RegionExpander.expand(
                            screenRegion, fullScreenBitmap.width, fullScreenBitmap.height
                        )

                        val key = "layer2:${expandedRect.left},${expandedRect.top},${expandedRect.right},${expandedRect.bottom}"
                        currentAiKeys.add(key)
                        overlayManager.blockRegion(key, expandedRect)
                    }
                }
            }
            overlayManager.clearStaleRegions("layer2:", currentAiKeys)
            fullScreenBitmap.recycle()
        }
    }

    private fun safeCrop(bitmap: Bitmap, rect: Rect): Bitmap? = try {
        val left = rect.left.coerceIn(0, bitmap.width - 1)
        val top = rect.top.coerceIn(0, bitmap.height - 1)
        val width = rect.width().coerceAtMost(bitmap.width - left)
        val height = rect.height().coerceAtMost(bitmap.height - top)
        if (width <= 0 || height <= 0) null else Bitmap.createBitmap(bitmap, left, top, width, height)
    } catch (e: Exception) { null }

    // ──────────────────────────────────────────────
    // Rastreo de paquetes
    // ──────────────────────────────────────────────
    private fun isSystemOrInputPackage(pkg: String): Boolean {
        if (pkg.isEmpty()) return false
        // Memorizado: la respuesta para un paquete dado nunca cambia, y calcularla
        // implicaba un lowercase() (que asigna un String nuevo) más ocho contains().
        sysInputCache[pkg]?.let { return it }
        val lower = pkg.lowercase()
        val result = pkg == LOCKSUITE_PKG ||
               pkg == "com.android.systemui" ||
               lower.contains("inputmethod") ||
               lower.contains("latin") ||
               lower.contains("gboard") ||
               lower.contains("swiftkey") ||
               lower.contains("keyboard") ||
               lower.contains("ime")
        if (sysInputCache.size < 256) sysInputCache[pkg] = result
        return result
    }

    private fun trackPackage(packageName: String) {
        // Orden a propósito: primero los dos chequeos que son lookups en HashSet en
        // memoria, y recién después el que puede tocar el cache de navegadores.
        if (WEBVIEW_PROVIDER_PACKAGES.contains(packageName)) return
        if (isSystemOrInputPackage(packageName)) return
        if (appPackageStack.firstOrNull() == packageName) return
        if (isBrowserPackage(packageName)) return

        appPackageStack.addFirst(packageName)
        if (appPackageStack.size > 5) appPackageStack.removeLast()
    }

    private fun getOriginApp(): String? = appPackageStack.firstOrNull()

    // ──────────────────────────────────────────────
    // Lógica principal de bloqueo de WebView
    // ──────────────────────────────────────────────
    private fun handleWebViewBlocking(packageName: String, eventType: Int) {
        if (WEBVIEW_PROVIDER_PACKAGES.contains(packageName)) {
            val originApp = getOriginApp()
            if (VERBOSE) Log.d(TAG, "WebView provider detectado. originApp=$originApp")
            if (originApp != null && WebViewBlockManager.isBlocked(this, originApp)) {
                Log.w(TAG, "🚫 Bloqueando WebView de $originApp (provider=$packageName)")
                triggerBlock(originApp)
            }
            return
        }

        if (isBrowserPackage(packageName)) {
            val originApp = getOriginApp()
            if (VERBOSE) Log.d(TAG, "Browser detectado. originApp=$originApp")
            if (originApp != null && originApp != packageName && WebViewBlockManager.isBlocked(this, originApp)) {
                Log.w(TAG, "🚫 Bloqueando Custom Tab/Browser de $originApp (browser=$packageName)")
                triggerBlock(originApp)
            }
            return
        }

        if (!WebViewBlockManager.isBlocked(this, packageName)) return
        checkAndBlockWebViewInTree(packageName, eventType)
    }

    private val webViewRetryRunnables = mutableListOf<Runnable>()
    private var lastRetryArmPkg: String? = null
    private var lastRetryArmAt = 0L

    private fun checkAndBlockWebViewInTree(packageName: String, eventType: Int) {
        // Verificación inmediata sobre la ventana actual.
        val root = rootInActiveWindow
        if (root != null) {
            if (isWindowFromPackage(root, packageName)) {
                val found = containsWebView(root)
                if (VERBOSE) Log.d(TAG, "Verificación inmediata pkg=$packageName webViewFound=$found")
                if (found) {
                    root.recycle()
                    triggerBlock(packageName)
                    return
                }
            }
            root.recycle()
        }

        // La escalera de reintentos existe para atrapar WebViews que cargan tarde.
        // Antes se cancelaba y se volvía a armar en CADA evento: mientras la pantalla
        // tuviera actividad, se rearmaban tres recorridos completos del árbol una y
        // otra vez. Ahora se arma cuando cambia la ventana, o como mucho una vez cada
        // WEBVIEW_REARM_MS por paquete.
        val now = SystemClock.elapsedRealtime()
        val windowChanged = eventType == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED
        val staleArm = lastRetryArmPkg != packageName || now - lastRetryArmAt > WEBVIEW_REARM_MS
        if (!windowChanged && !staleArm) return

        lastRetryArmPkg = packageName
        lastRetryArmAt = now

        // Cancelar reintentos previos acumulados para no sobrecargar la CPU con escaneos redundantes
        webViewRetryRunnables.forEach { mainHandler.removeCallbacks(it) }
        webViewRetryRunnables.clear()

        listOf(250L, 800L, 1800L).forEach { delay ->
            val retryRunnable = object : Runnable {
                override fun run() {
                    webViewRetryRunnables.remove(this)
                    if (webViewBlockInProgress) return
                    val current = rootInActiveWindow ?: return
                    val currentPkg = current.packageName?.toString() ?: run {
                        current.recycle()
                        return
                    }
                    val relevantPkg = currentPkg == packageName ||
                                      WEBVIEW_PROVIDER_PACKAGES.contains(currentPkg)

                    if (!relevantPkg) {
                        current.recycle()
                        return
                    }

                    val found = containsWebView(current)
                    current.recycle()
                    if (found) {
                        Log.w(TAG, "🚫 WebView detectado con retraso de ${delay}ms para $packageName")
                        triggerBlock(packageName)
                    }
                }
            }
            webViewRetryRunnables.add(retryRunnable)
            mainHandler.postDelayed(retryRunnable, delay)
        }
    }

    private fun isWindowFromPackage(root: AccessibilityNodeInfo, packageName: String): Boolean {
        val windowPkg = root.packageName?.toString() ?: return false
        return windowPkg == packageName || WEBVIEW_PROVIDER_PACKAGES.contains(windowPkg)
    }

    private fun triggerBlock(packageName: String) {
        if (webViewBlockInProgress) return
        webViewBlockInProgress = true

        Log.w(TAG, "🛑 triggerBlock para $packageName")
        Toast.makeText(this, "Navegador interno bloqueado por políticas del MDM", Toast.LENGTH_SHORT).show()
        performGlobalAction(GLOBAL_ACTION_BACK)

        mainHandler.postDelayed({
            val current = rootInActiveWindow
            if (current != null) {
                val currentPkg = current.packageName?.toString() ?: ""
                val stillThere = currentPkg == packageName ||
                                 (WEBVIEW_PROVIDER_PACKAGES.contains(currentPkg) && getOriginApp() == packageName)
                val hasWebView = containsWebView(current)

                if (VERBOSE) {
                    Log.d(TAG, "PostBack check: currentPkg=$currentPkg stillThere=$stillThere hasWebView=$hasWebView")
                }

                current.recycle()

                if (stillThere && hasWebView) {
                    Log.w(TAG, "🏠 WebView persiste, forzando HOME")
                    performGlobalAction(GLOBAL_ACTION_HOME)
                }
            }
            webViewBlockInProgress = false
        }, 700)
    }

    private fun containsWebView(node: AccessibilityNodeInfo): Boolean {
        nodeBudget = MAX_NODES_PER_SCAN
        return containsWebViewInternal(node, depth = 0)
    }

    private fun containsWebViewInternal(node: AccessibilityNodeInfo, depth: Int): Boolean {
        if (depth > 30) return false
        if (nodeBudget-- <= 0) return false

        val className = node.className?.toString()?.lowercase() ?: ""
        val nodePkg   = node.packageName?.toString()?.lowercase() ?: ""

        val isWebView =
            className.contains("webview") ||
            className.contains("webkit")  ||
            className.contains("chromium")||
            className.contains("renderframe") ||
            className.contains("xwalk")   ||
            className.contains("smtt")    ||
            className.contains("geckoview") ||
            nodePkg == "com.google.android.webview" ||
            nodePkg == "com.android.webview"        ||
            nodePkg == "com.android.chrome"

        if (isWebView) {
            if (VERBOSE) Log.i(TAG, "  🔍 WebView encontrado: class=$className pkg=$nodePkg depth=$depth")
            return true
        }

        val childCount = node.childCount
        for (i in 0 until childCount) {
            val child = node.getChild(i) ?: continue
            val found = containsWebViewInternal(child, depth + 1)
            child.recycle()
            if (found) return true
        }
        return false
    }

    // ──────────────────────────────────────────────
    // Anti-evasión en Ajustes
    //
    // Antes se recorría el árbol DOS veces enteras: una buscando "locksuite" y otra
    // buscando las 17 acciones peligrosas. Ahora es un solo recorrido que junta las
    // dos señales y corta apenas tiene ambas.
    // ──────────────────────────────────────────────
    private val dangerousSettingsActions = listOf(
        "desactivar", "turn off", "disable",
        "forzar detención", "force stop", "deshabilitar",
        "quitar administrador", "quitar admin", "desinstalar", "uninstall",
        "הסר", "אלץ עצירה", "עצירה כפויה", "השבת", "ביטול", "הסרת התקנה",
        // La última palabra en yiddish tenía una "n" latina suelta al final
        // ("אומאינסטאלירn") en vez de la nun final hebrea ("ן"): como la coincidencia
        // es por substring sobre texto real de pantalla, esa entrada nunca podía
        // matchear nada y quedaba muerta.
        "אפשטעלן", "דעאקטיקירן", "אומאינסטאלירן"
    )

    private val lockSuiteSettingsMarkers = listOf("locksuite", "lock suite", LOCKSUITE_PKG)

    private class SettingsScanResult {
        var inLockSuite = false
        var dangerous = false
        val done: Boolean get() = inLockSuite && dangerous
    }

    // ──────────────────────────────────────────────
    // Rebote del menú de Accesibilidad de Ajustes  (switch: acc_protect_bounce_settings)
    //
    // Qué es y qué NO es. Esto NO impide desactivar la Accesibilidad: es la primera
    // barrera, no la última. Alguien que reinicie el equipo y corra a Ajustes tiene
    // una ventana en la que este servicio todavía no está levantado y por lo tanto no
    // puede rebotar nada. Por eso va acompañado de las otras tres protecciones (aviso
    // insistente, suspensión de apps y arranque protegido), que sí cubren esa ventana.
    // Lo que sí logra: que el usuario común no llegue nunca a la pantalla del
    // interruptor, y que llegar ahí sea molesto a propósito.
    //
    // Detección, en orden de costo:
    //   1. Nombre de clase de la ventana con "accessibilit" — gratis. Cubre a los
    //      fabricantes que usan una actividad dedicada (Samsung, Xiaomi, etc.).
    //   2. Título de pantalla contra una lista de palabras en 10 idiomas, con tildes
    //      normalizadas y coincidencia por palabra completa. Hace falta porque el
    //      Android puro mete casi todas las sub-pantallas de Ajustes dentro de una
    //      única clase genérica (`com.android.settings.SubSettings`), así que el
    //      nombre de clase no alcanza. El recorrido está topeado a 12 niveles y 400
    //      nodos: los títulos están arriba de todo, no hace falta bajar más.
    //
    // Con sesión de administrador abierta (PIN ingresado hace menos de 5 minutos) no
    // rebota: si no, el propio administrador no podría apagar el servicio a propósito.
    // ──────────────────────────────────────────────
    private val ACCESSIBILITY_TITLE_WORDS = listOf(
        "accesibilidad",      // es
        "accessibility",      // en
        "acessibilidade",     // pt
        "accessibilite",      // fr (sin tildes: el texto se normaliza antes)
        "accessibilita",      // it
        "barrierefreiheit",   // de
        "toegankelijkheid",   // nl
        "נגישות",             // he
        "צוגענגלעכקייט",       // yi
        "ulaşılabilirlik"     // tr
    )

    private var accBounceInProgress = false
    private var accBounceBackoffUntil = 0L
    private var lastAccBounceScanAt = 0L

    private fun isSettingsPackage(pkg: String): Boolean =
        pkg == SETTINGS_PKG || pkg.endsWith(".settings")

    /** Devuelve true si rebotó (y por lo tanto el evento ya está atendido). */
    private fun handleAccessibilitySettingsBounce(ev: AccessibilityEvent, eventType: Int): Boolean {
        val now = SystemClock.elapsedRealtime()
        if (accBounceInProgress || now < accBounceBackoffUntil) return false
        // El administrador con sesión abierta puede entrar a propósito.
        if (com.ejemplo.locksuite.security.SessionManager.isActive()) return false

        // 1. Nombre de clase (gratis).
        val cls = ev.className?.toString()
        var isAccessibilityScreen = cls != null && cls.contains("accessibilit", ignoreCase = true)

        // 2. Título de pantalla. Solo al cambiar de ventana, o como mucho una vez por
        //    segundo: sin este freno el recorrido se repetiría con cada CONTENT_CHANGED.
        if (!isAccessibilityScreen) {
            val windowChanged = eventType == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED
            if (!windowChanged && now - lastAccBounceScanAt < 1_000L) return false
            lastAccBounceScanAt = now

            val root = rootInActiveWindow ?: return false
            try {
                nodeBudget = 400
                isAccessibilityScreen = hasAccessibilityTitle(root, 0)
            } finally {
                root.recycle()
            }
        }

        if (!isAccessibilityScreen) return false

        accBounceInProgress = true
        Log.w(TAG, "🚫 Menú de Accesibilidad de Ajustes: rebotando al usuario.")
        performGlobalAction(GLOBAL_ACTION_BACK)

        mainHandler.postDelayed({
            var stillThere = false
            val current = rootInActiveWindow
            if (current != null) {
                val pkg = current.packageName?.toString() ?: ""
                if (isSettingsPackage(pkg)) {
                    nodeBudget = 400
                    stillThere = hasAccessibilityTitle(current, 0)
                }
                current.recycle()
            }
            if (stillThere) {
                // Un "atrás" no alcanzó (pasa cuando la pantalla es la raíz de su tarea).
                // Se sale del todo y se deja un descanso para no encadenar acciones.
                Log.w(TAG, "🏠 El menú de Accesibilidad persiste: forzando HOME.")
                performGlobalAction(GLOBAL_ACTION_HOME)
                accBounceBackoffUntil = SystemClock.elapsedRealtime() + ACC_BOUNCE_BACKOFF_MS
            }
            accBounceInProgress = false
        }, 600)

        return true
    }

    private fun hasAccessibilityTitle(node: AccessibilityNodeInfo, depth: Int): Boolean {
        if (depth > 12) return false
        if (nodeBudget-- <= 0) return false

        val text = node.text
        if (text != null && text.isNotEmpty()) {
            val folded = foldAccents(text)
            if (ACCESSIBILITY_TITLE_WORDS.any { containsWholeWord(folded, it) }) return true
        }
        val desc = node.contentDescription
        if (desc != null && desc.isNotEmpty()) {
            val folded = foldAccents(desc)
            if (ACCESSIBILITY_TITLE_WORDS.any { containsWholeWord(folded, it) }) return true
        }

        val childCount = node.childCount
        for (i in 0 until childCount) {
            val child = node.getChild(i) ?: continue
            try {
                if (hasAccessibilityTitle(child, depth + 1)) return true
            } finally {
                child.recycle()
            }
        }
        return false
    }

    private fun handleSettingsAntiEvasion() {
        val root = rootInActiveWindow ?: return
        val result = SettingsScanResult()
        nodeBudget = MAX_NODES_PER_SCAN
        scanSettingsNode(root, 0, result)
        root.recycle()

        if (result.inLockSuite && result.dangerous) {
            performGlobalAction(GLOBAL_ACTION_BACK)
            Toast.makeText(this, "Acción denegada por políticas de seguridad de LockSuite MDM", Toast.LENGTH_LONG).show()
            val loginIntent = Intent(this, com.ejemplo.locksuite.ui.auth.LoginActivity::class.java).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
            }
            startActivity(loginIntent)
        }
    }

    private fun scanSettingsNode(node: AccessibilityNodeInfo, depth: Int, out: SettingsScanResult) {
        if (out.done || depth > MAX_TREE_DEPTH) return
        if (nodeBudget-- <= 0) return

        val text = node.text?.toString()?.lowercase()
        val desc = node.contentDescription?.toString()?.lowercase()

        if (text != null || desc != null) {
            if (!out.inLockSuite) {
                out.inLockSuite = lockSuiteSettingsMarkers.any {
                    (text != null && text.contains(it)) || (desc != null && desc.contains(it))
                }
            }
            if (!out.dangerous) {
                out.dangerous = dangerousSettingsActions.any {
                    (text != null && text.contains(it)) || (desc != null && desc.contains(it))
                }
            }
            if (out.done) return
        }

        val childCount = node.childCount
        for (i in 0 until childCount) {
            val child = node.getChild(i) ?: continue
            try {
                scanSettingsNode(child, depth + 1, out)
            } finally {
                child.recycle()
            }
            if (out.done) return
        }
    }

    // ──────────────────────────────────────────────
    // Interceptor del marcador (códigos secretos)
    // ──────────────────────────────────────────────
    private fun handleDialerIntercept() {
        val root = rootInActiveWindow ?: return
        nodeBudget = MAX_NODES_PER_SCAN
        val isOpenCode    = searchNodeByText(root, listOf("*#*#1234#*#*", "*#*#1234#*#"), 0)
        nodeBudget = MAX_NODES_PER_SCAN
        val isEmergencyCode = searchNodeByText(root, listOf("*#*#9999#*#*", "*#*#9999#*#"), 0)
        root.recycle()

        if (isOpenCode) {
            performGlobalAction(GLOBAL_ACTION_HOME)
            val intent = Intent(this, com.ejemplo.locksuite.ui.auth.LoginActivity::class.java).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
            }
            startActivity(intent)
        } else if (isEmergencyCode) {
            performGlobalAction(GLOBAL_ACTION_HOME)
            val intent = Intent(this, com.ejemplo.locksuite.ui.emergency.EmergencyActivity::class.java).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
            }
            startActivity(intent)
        }
    }

    private fun searchNodeByText(root: AccessibilityNodeInfo, keywords: List<String>, depth: Int): Boolean {
        if (depth > MAX_TREE_DEPTH) return false
        if (nodeBudget-- <= 0) return false

        val text = root.text?.toString()?.lowercase() ?: ""
        val desc = root.contentDescription?.toString()?.lowercase() ?: ""
        if (keywords.any { text.contains(it) || desc.contains(it) }) return true
        val childCount = root.childCount
        for (i in 0 until childCount) {
            val child = root.getChild(i) ?: continue
            val found = searchNodeByText(child, keywords, depth + 1)
            child.recycle()
            if (found) return true
        }
        return false
    }

    private fun Int.toEventName(): String = when (this) {
        AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED   -> "STATE_CHANGED"
        AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED -> "CONTENT_CHANGED"
        AccessibilityEvent.TYPE_VIEW_SCROLLED          -> "SCROLLED"
        AccessibilityEvent.TYPE_VIEW_SELECTED          -> "VIEW_SELECTED"
        else -> "OTHER($this)"
    }

    private var lastWaWindowClassName: String? = null

    private enum class WhatsAppRestrictedContent { STATUS, CHANNEL, NONE }

    // ──────────────────────────────────────────────────────────────
    // Detección de contenido restringido de WhatsApp
    //
    // ⚠️ "mediaview" ESTABA ACÁ Y TAPABA DE MÁS (corregido el 17/8/2026).
    // `com.whatsapp.MediaView` es el visor de fotos y videos de las CONVERSACIONES
    // NORMALES, no el de Estados. Con "mediaview" en esta lista, activar "bloquear
    // Estados" también impedía abrir una foto que te manda un contacto en un chat
    // común — que es justamente el uso legítimo de la app. El visor de Estados real
    // es `com.whatsapp.status.playback.StatusPlaybackActivity`, que ya queda cubierto
    // por "status".
    //
    // Los Canales de WhatsApp se llaman "newsletter" internamente; ese es el nombre
    // que aparece en las clases de actividad, en cualquier idioma.
    // ──────────────────────────────────────────────────────────────
    private val STATUS_ACTIVITY_HINTS = listOf("status", "stories")
    private val CHANNEL_ACTIVITY_HINTS = listOf("newsletter", "channel")

    // Etiquetas de pestaña, en minúscula y SIN tildes: el texto de pantalla se
    // normaliza con foldAccents() antes de comparar (igual que en Mercado Pago).
    // Antes solo estaban en español e inglés, así que en un equipo en hebreo, ídish o
    // portugués el bloqueo de la pestaña no funcionaba en absoluto.
    private val UPDATES_TAB_LABELS = listOf(
        "novedades", "actualizaciones", "estados",          // es
        "updates", "status",                                // en
        "novidades", "atualizacoes",                        // pt
        "actualites", "mises a jour",                       // fr
        "aktuelles", "neuigkeiten",                         // de
        "aggiornamenti",                                    // it
        "canales", "channels", "canais", "chaines", "kanale",
        "עדכונים", "סטטוס", "ערוצים",                        // he
        "דערהייַנטיקונגען"                                    // yi
    )

    private val CHATS_TAB_LABELS = listOf(
        "Chats", "Conversaciones", "Conversas", "Unterhaltungen",
        "Discussions", "Chat", "צ'אטים", "שמועסן"
    )

    private fun classifyWhatsAppContent(className: String?): WhatsAppRestrictedContent {
        val c = className?.lowercase() ?: return WhatsAppRestrictedContent.NONE
        return when {
            STATUS_ACTIVITY_HINTS.any { c.contains(it) } -> WhatsAppRestrictedContent.STATUS
            CHANNEL_ACTIVITY_HINTS.any { c.contains(it) } -> WhatsAppRestrictedContent.CHANNEL
            else -> WhatsAppRestrictedContent.NONE
        }
    }

    private fun handleWhatsAppBlocking(
        eventType: Int,
        event: AccessibilityEvent,
        blockStatus: Boolean,
        blockChannels: Boolean
    ) {
        if (eventType == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) {
            val className = event.className?.toString() ?: ""
            lastWaWindowClassName = className
            val category = classifyWhatsAppContent(className)
            if (category == WhatsAppRestrictedContent.STATUS && blockStatus) {
                triggerWhatsAppBlock("Estado")
            } else if (category == WhatsAppRestrictedContent.CHANNEL && blockChannels) {
                triggerWhatsAppBlock("Canal")
            }
            // Escaneo inmediato al cambiar de actividad/pantalla
            scanForUpdatesTab(blockStatus, blockChannels)
        }

        if (eventType == AccessibilityEvent.TYPE_VIEW_SELECTED) {
            // Escaneo inmediato al tocar una pestaña/vista
            scanForUpdatesTab(blockStatus, blockChannels)
        }

        if (eventType == AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED) {
            // Debounce de 400ms para evitar ráfagas de escaneos al desplazarse o actualizarse la vista
            mainHandler.removeCallbacks(whatsappScanRunnable)
            mainHandler.postDelayed(whatsappScanRunnable, 400)
        }
    }

    /**
     * ¿Este nodo, o alguno de sus ancestros cercanos, está marcado como seleccionado?
     *
     * Es como se reconoce la pestaña activa: WhatsApp pone `isSelected` sobre el
     * contenedor de la pestaña, no sobre la etiqueta de texto.
     *
     * A diferencia de la versión anterior (`isNodeOrParentSelected`), esta NO recicla
     * el nodo que recibe: durante un recorrido, ese nodo es propiedad de quien llama y
     * reciclarlo dejaba un puntero muerto en manos del recorrido.
     */
    private fun isSelectedNodeOrAncestor(node: AccessibilityNodeInfo): Boolean {
        if (node.isSelected) return true
        var current = node.parent
        var depth = 0
        while (current != null && depth < 4) {
            val selected = current.isSelected
            val parent = current.parent
            current.recycle()
            if (selected) return true
            current = parent
            depth++
        }
        return false
    }

    private fun clickNodeOrClickableParent(node: AccessibilityNodeInfo?): Boolean {
        var current = node
        var depth = 0
        while (current != null && depth < 5) {
            val parent = current.parent
            var clicked = false
            if (current.isClickable) {
                clicked = current.performAction(AccessibilityNodeInfo.ACTION_CLICK)
            }
            current.recycle()
            if (clicked) return true
            current = parent
            depth++
        }
        return false
    }

    private class WaTabScanState {
        var onUpdatesTab = false
    }

    /**
     * Rebote de la pestaña "Novedades" completa.
     *
     * DOS CORRECCIONES DEL 17/8/2026 — las dos eran de las que el dueño pidió mirar
     * ("que no tape de más ni de menos"):
     *
     *  1. TAPABA DE MÁS. En WhatsApp moderno, Estados y Canales viven JUNTOS en la
     *     misma pestaña "Novedades". Esta función recibía `blockStatus` y
     *     `blockChannels` y NO LOS USABA NUNCA: bastaba con tener uno de los dos
     *     interruptores encendido para que al usuario lo sacaran de la pestaña
     *     entera, o sea también de la mitad que sí tenía permitida. Ahora la pestaña
     *     completa solo se rebota si están bloqueadas LAS DOS cosas. Si el
     *     administrador bloqueó una sola, la pestaña se puede abrir y el bloqueo
     *     actúa recién al abrir el estado o el canal concreto, por el nombre de la
     *     actividad (`classifyWhatsAppContent`), que sí distingue una cosa de la otra.
     *
     *  2. TAPABA DE MENOS Y COSTABA CARO. Se hacía un `findAccessibilityNodeInfosByText`
     *     por cada etiqueta: seis búsquedas completas del árbol en el caso normal
     *     (el negativo, que es el 99 % de las veces), y solo en español e inglés — en
     *     un equipo en hebreo, ídish o portugués no bloqueaba nada. Ahora es UN solo
     *     recorrido, con tope de profundidad y de nodos, que compara contra la lista
     *     de etiquetas en diez idiomas ya normalizada.
     */
    private fun scanForUpdatesTab(blockStatus: Boolean, blockChannels: Boolean) {
        if (!blockStatus || !blockChannels) return

        val root = rootInActiveWindow ?: return
        try {
            val rootPkg = root.packageName?.toString() ?: ""
            if (rootPkg != PKG_WHATSAPP && rootPkg != PKG_WHATSAPP_BUSINESS) return

            val state = WaTabScanState()
            nodeBudget = MAX_NODES_PER_SCAN
            scanWhatsAppTabNode(root, 0, state)
            if (state.onUpdatesTab) {
                redirectAwayFromUpdatesTab(root)
            }
        } finally {
            root.recycle()
        }
    }

    private fun scanWhatsAppTabNode(node: AccessibilityNodeInfo, depth: Int, state: WaTabScanState) {
        if (state.onUpdatesTab || depth > MAX_TREE_DEPTH) return
        if (nodeBudget-- <= 0) return

        var matched = false
        val text = node.text
        if (text != null && text.isNotEmpty()) {
            val folded = foldAccents(text)
            matched = UPDATES_TAB_LABELS.any { containsWholeWord(folded, it) }
        }
        if (!matched) {
            val desc = node.contentDescription
            if (desc != null && desc.isNotEmpty()) {
                val folded = foldAccents(desc)
                matched = UPDATES_TAB_LABELS.any { containsWholeWord(folded, it) }
            }
        }
        // La coincidencia sola no alcanza: la palabra "Estados" también aparece en el
        // cuerpo de un mensaje. Lo que identifica la pestaña ACTIVA es `isSelected`.
        if (matched && isSelectedNodeOrAncestor(node)) {
            state.onUpdatesTab = true
            return
        }

        val childCount = node.childCount
        for (i in 0 until childCount) {
            val child = node.getChild(i) ?: continue
            try {
                scanWhatsAppTabNode(child, depth + 1, state)
            } finally {
                child.recycle()
            }
            if (state.onUpdatesTab) return
        }
    }

    private fun redirectAwayFromUpdatesTab(root: AccessibilityNodeInfo) {
        var chatsNode: AccessibilityNodeInfo? = null
        for (label in CHATS_TAB_LABELS) {
            if (chatsNode != null) break
            for (node in root.findAccessibilityNodeInfosByText(label)) {
                if (chatsNode == null) chatsNode = node else node.recycle()
            }
        }

        val clickOk = clickNodeOrClickableParent(chatsNode)
        if (!clickOk) {
            performGlobalAction(GLOBAL_ACTION_BACK)
        }
    }

    private fun triggerWhatsAppBlock(type: String) {
        if (waBlockInProgress) return
        waBlockInProgress = true
        mainHandler.post {
            Toast.makeText(applicationContext, "$type bloqueado por políticas del MDM", Toast.LENGTH_SHORT).show()
        }
        performGlobalAction(GLOBAL_ACTION_BACK)
        mainHandler.postDelayed({
            val currentRoot = rootInActiveWindow
            val currentClassName = currentRoot?.className?.toString() ?: lastWaWindowClassName ?: ""
            currentRoot?.recycle()
            if (classifyWhatsAppContent(currentClassName) != WhatsAppRestrictedContent.NONE) {
                performGlobalAction(GLOBAL_ACTION_HOME)
            }
            waBlockInProgress = false
        }, 700)
    }

    // ──────────────────────────────────────────────
    // Bloqueo de Ofertas en Mercado Pago
    // ──────────────────────────────────────────────
    private val mercadoPagoScanRunnable = Runnable {
        if (flags().mpOffers) {
            scanForMercadoPagoOffers()
        }
    }

    private var mpFailedBounces = 0
    private var mpBackoffUntil = 0L

    private fun handleMercadoPagoBlocking(eventType: Int) {
        if (eventType == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED ||
            eventType == AccessibilityEvent.TYPE_VIEW_SELECTED) {
            scanForMercadoPagoOffers()
        }

        if (eventType == AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED) {
            mainHandler.removeCallbacks(mercadoPagoScanRunnable)
            mainHandler.postDelayed(mercadoPagoScanRunnable, 350)
        }
    }

    private fun scanForMercadoPagoOffers() {
        if (mpBlockInProgress) return
        val root = rootInActiveWindow ?: return
        val rootPkg = root.packageName?.toString() ?: ""

        if (rootPkg == PKG_MERCADOPAGO || WEBVIEW_PROVIDER_PACKAGES.contains(rootPkg)) {
            val containsOffersNode = detectMercadoPagoOffers(root)
            root.recycle()

            if (containsOffersNode) {
                triggerMercadoPagoBlock()
            }
        } else {
            root.recycle()
        }
    }

    /**
     * Decide si la pantalla que se está mostrando es realmente la sección de ofertas
     * / promociones de Mercado Pago.
     *
     * Reglas (ver comentario de MP_OFFERS_STRONG arriba para el porqué):
     *   • un identificador de vista propio de ofertas    → bloquea
     *   • una frase fuerte (título de sección)           → bloquea
     *   • dos palabras débiles DISTINTAS                 → bloquea
     *   • pantalla WebView + una palabra débil           → bloquea (red de seguridad)
     *   • en cualquier otro caso                         → NO bloquea
     */
    private fun detectMercadoPagoOffers(root: AccessibilityNodeInfo): Boolean {
        val state = MpScanState()
        nodeBudget = MAX_NODES_PER_SCAN
        scanMercadoPagoNode(root, 0, state)

        if (state.strongHit) return true
        if (state.weakHits.size >= 2) return true
        if (state.inWebkitPage && state.weakHits.isNotEmpty()) return true
        return false
    }

    private class MpScanState {
        var strongHit = false
        var inWebkitPage = false
        val weakHits = HashSet<String>(4)
    }

    private fun scanMercadoPagoNode(node: AccessibilityNodeInfo, depth: Int, state: MpScanState) {
        if (state.strongHit || depth > MAX_TREE_DEPTH) return
        if (nodeBudget-- <= 0) return

        val className = node.className?.toString()
        if (className != null &&
            (className.contains("WebkitPageActivity", ignoreCase = true) ||
             className.contains("mlwebkit", ignoreCase = true))) {
            // Ya no bloquea por sí solo: solo baja el umbral a una palabra débil.
            // Antes, CUALQUIER pantalla web de Mercado Pago (incluidos flujos de pago
            // y de ayuda) se consideraba "ofertas" y expulsaba al usuario.
            state.inWebkitPage = true
        }

        val viewId = node.viewIdResourceName
        if (viewId != null) {
            val v = viewId.lowercase()
            if (MP_OFFERS_VIEW_ID_HINTS.any { v.contains(it) }) {
                state.strongHit = true
                return
            }
        }

        val rawText = node.text
        val rawDesc = node.contentDescription
        if (rawText != null && rawText.isNotEmpty()) {
            if (matchMpText(foldAccents(rawText), state)) return
        }
        if (rawDesc != null && rawDesc.isNotEmpty()) {
            if (matchMpText(foldAccents(rawDesc), state)) return
        }

        val childCount = node.childCount
        for (i in 0 until childCount) {
            val child = node.getChild(i) ?: continue
            try {
                scanMercadoPagoNode(child, depth + 1, state)
            } finally {
                child.recycle()
            }
            if (state.strongHit) return
        }
    }

    /** Devuelve true si encontró una señal fuerte (corta el recorrido). */
    private fun matchMpText(folded: String, state: MpScanState): Boolean {
        if (MP_OFFERS_STRONG.any { folded.contains(it) }) {
            state.strongHit = true
            return true
        }
        for (w in MP_OFFERS_WEAK) {
            if (state.weakHits.contains(w)) continue
            if (containsWholeWord(folded, w)) state.weakHits.add(w)
        }
        return false
    }

    /**
     * Coincidencia por palabra completa. Sin esto, "puntos" matchea dentro de
     * "puntos de venta" pero también dentro de cualquier palabra que la contenga,
     * y una sola palabra suelta alcanzaba para expulsar al usuario de la pantalla.
     */
    private fun containsWholeWord(haystack: String, word: String): Boolean {
        var i = haystack.indexOf(word)
        while (i >= 0) {
            val beforeOk = i == 0 || !haystack[i - 1].isLetterOrDigit()
            val end = i + word.length
            val afterOk = end >= haystack.length || !haystack[end].isLetterOrDigit()
            if (beforeOk && afterOk) return true
            i = haystack.indexOf(word, i + 1)
        }
        return false
    }

    /**
     * Pasa a minúsculas y saca las tildes en un solo recorrido de caracteres.
     * Sin esto, "promoción" nunca coincidía con la palabra "promocion" de la lista
     * (la comparación es por substring literal), así que media lista estaba muerta.
     * Se evita java.text.Normalizer a propósito: es bastante más caro y esto corre
     * sobre cada texto de cada nodo.
     */
    private fun foldAccents(cs: CharSequence): String {
        val sb = StringBuilder(cs.length)
        for (c in cs) {
            val lc = c.lowercaseChar()
            sb.append(
                when (lc) {
                    'á', 'à', 'ä', 'â', 'ã' -> 'a'
                    'é', 'è', 'ë', 'ê' -> 'e'
                    'í', 'ì', 'ï', 'î' -> 'i'
                    'ó', 'ò', 'ö', 'ô', 'õ' -> 'o'
                    'ú', 'ù', 'ü', 'û' -> 'u'
                    else -> lc
                }
            )
        }
        return sb.toString()
    }

    /**
     * Rebote de la sección de ofertas.
     *
     * Antes esto era un GLOBAL_ACTION_BACK a ciegas: no se verificaba si había servido,
     * y como el escaneo vuelve a los 350 ms, si el "atrás" no salía de la sección se
     * encadenaban rebotes hasta sacar al usuario de Mercado Pago por completo. Ahora:
     * un "atrás", se verifica, un segundo intento, y recién entonces HOME — más una
     * pausa de MP_BACKOFF_MS para que no quede girando.
     */
    private fun triggerMercadoPagoBlock() {
        val now = SystemClock.elapsedRealtime()
        if (mpBlockInProgress || now < mpBackoffUntil) return
        mpBlockInProgress = true

        mainHandler.post {
            Toast.makeText(applicationContext, "🚫 Sección de Ofertas restringida por LockSuite", Toast.LENGTH_SHORT).show()
        }
        performGlobalAction(GLOBAL_ACTION_BACK)

        mainHandler.postDelayed({
            val current = rootInActiveWindow
            val stillOnOffers = if (current == null) {
                false
            } else {
                val pkg = current.packageName?.toString() ?: ""
                val relevant = pkg == PKG_MERCADOPAGO || WEBVIEW_PROVIDER_PACKAGES.contains(pkg)
                val offers = relevant && detectMercadoPagoOffers(current)
                current.recycle()
                offers
            }

            if (stillOnOffers) {
                mpFailedBounces++
                if (mpFailedBounces >= 2) {
                    Log.w(TAG, "🏠 La sección de ofertas persiste tras dos intentos, forzando HOME")
                    performGlobalAction(GLOBAL_ACTION_HOME)
                    mpFailedBounces = 0
                    mpBackoffUntil = SystemClock.elapsedRealtime() + MP_BACKOFF_MS
                }
            } else {
                mpFailedBounces = 0
            }
            mpBlockInProgress = false
        }, 700)
    }

    // ──────────────────────────────────────────────
    // Automatización del flujo de actualización por Google Play
    //
    // Reescrito el 16/8/2026. Qué estaba mal antes (y por qué la app no se
    // actualizaba y la pantalla negra no se iba):
    //
    //  1. El emparejamiento de botones usaba `contains` sobre palabras muy
    //     cortas ("ok", "yes", "sí", "open", "install") y aceptaba CUALQUIER
    //     nodo con texto, clickeable o no. En una pantalla de Play Store eso
    //     matchea decenas de nodos que no son botones: el servicio subía al
    //     primer padre clickeable y hacía clic en cosas al azar. "Installing"
    //     contiene "install", así que mientras descargaba volvía a "hacer clic
    //     en Actualizar" sobre la fila de progreso, que es donde Play Store
    //     pone el botón de cancelar la descarga. Ahora el emparejamiento es por
    //     IGUALDAD exacta contra listas cerradas, con acentos normalizados.
    //
    //  2. No había ninguna detección de "ya está descargando": se seguía
    //     clickeando encima del progreso. Ahora, si se detecta progreso, el
    //     ciclo no toca nada y solo informa la etapa.
    //
    //  3. La única forma de terminar era el paso C (aparece "Abrir" y no hay
    //     "Actualizar"). Si Play Store mostraba cualquier otra cosa —un error,
    //     un pedido de iniciar sesión, una pantalla en blanco— no terminaba
    //     nunca y el overlay quedaba fijo hasta el watchdog de 10 minutos, que
    //     además no sacaba el overlay. Ahora hay tres salidas más: el
    //     versionCode del paquete cambió (la señal más confiable de todas),
    //     el freno por estancamiento de UPDATE_STALL_MS, y el botón Cancelar.
    // ──────────────────────────────────────────────

    private var updateSessionPkg: String? = null
    private var updateSessionStartTime = 0L
    private var updateSessionTreeSeenAt = 0L
    private var updateSessionLastClickAt = 0L
    private var updateSessionCandidatesTried = 0
    private val updateSessionTriedKeys = HashSet<String>(8)
    private var lastStoreRelaunchAt = 0L

    /** Invalida el snapshot de flags. La usa UpdateFlowManager al arrancar/terminar. */
    fun invalidateFlagsCache() {
        cachedFlags = null
    }

    fun resetUpdateSession() {
        updateSessionPkg = null
        updateSessionStartTime = 0L
        updateSessionTreeSeenAt = 0L
        updateSessionLastClickAt = 0L
        updateSessionCandidatesTried = 0
        updateSessionTriedKeys.clear()
        lastStoreRelaunchAt = 0L
        releaseUpdateWakeLock()
    }

    /**
     * Firma estable de un nodo entre escaneos. Los AccessibilityNodeInfo se recrean
     * en cada escaneo, así que no sirve compararlos por identidad para saber si un
     * candidato ya se probó.
     */
    private fun candidateKey(c: PlayButtonFinder.Candidate): String {
        val id = c.node.viewIdResourceName ?: ""
        val txt = c.node.text?.toString() ?: c.node.contentDescription?.toString() ?: ""
        return "$id|${c.top}|${c.left}|$txt"
    }

    /**
     * Busca la ventana de Play Store entre todas las interactivas.
     * `rootInActiveWindow` no sirve solo: con el overlay de accesibilidad arriba
     * puede devolver null o el árbol del overlay en vez del de la tienda.
     */
    private fun findPlayStoreRoot(): AccessibilityNodeInfo? {
        try {
            for (window in windows) {
                val windowRoot = window.root ?: continue
                if (windowRoot.packageName?.toString() == PKG_PLAY_STORE) return windowRoot
            }
        } catch (e: Exception) {
            Log.w(TAG, "Error buscando la ventana de Play Store: ${e.message}")
        }
        val root = rootInActiveWindow ?: return null
        return if (root.packageName?.toString() == PKG_PLAY_STORE) root else null
    }

    /**
     * Un ciclo del flujo. Lo llama el tick cada UPDATE_TICK_MS y también cada evento
     * de accesibilidad de Play Store (el evento es un despertador extra, no la única
     * fuente — ese era el bug).
     */
    private fun scanAndAct(updatingPkg: String) {
        val ctx = applicationContext
        val now = SystemClock.elapsedRealtime()

        if (updateSessionPkg != updatingPkg) {
            resetUpdateSession()
            updateSessionPkg = updatingPkg
            updateSessionStartTime = now
        }

        UpdateFlowManager.showOverlay(
            ctx, updatingPkg,
            UpdateFlowManager.currentStage(ctx)
        )

        // ── 1. ¿Ya se instaló? Señal concluyente, no depende de la pantalla ──
        if (UpdateFlowManager.targetAlreadyUpdated(ctx)) {
            UpdateFlowManager.setStage(ctx, UpdateFlowManager.STAGE_FINISHING,
                "✓ Actualización completada.")
            UpdateFlowManager.finish(ctx, UpdateFlowManager.RESULT_UPDATED, null)
            return
        }

        // ── 2. ¿Play Store ya está descargando? (por PackageInstaller) ──
        val livePct = PlayUpdateSessionWatcher.currentProgressFor(ctx, updatingPkg)
        val pct = if (livePct >= 0) livePct else PlayUpdateSessionWatcher.lastProgress
        if (pct >= 0 || PlayUpdateSessionWatcher.sawSession) {
            val progressText = if (pct >= 0) "Descargando... $pct%" else
                (UpdateFlowManager.currentDetail(ctx) ?: "Descargando actualización...")
            UpdateFlowManager.setStage(ctx, UpdateFlowManager.STAGE_DOWNLOADING, progressText)
            return
        }

        // ── 3. Árbol de Play Store ──
        val root = findPlayStoreRoot()
        if (root == null) {
            UpdateFlowManager.setStage(ctx, UpdateFlowManager.STAGE_WAITING_STORE,
                "Esperando a Google Play...")
            if (now - updateSessionStartTime > 6_000L &&
                now - lastStoreRelaunchAt > STORE_RELAUNCH_MIN_MS
            ) {
                lastStoreRelaunchAt = now
                UpdateFlowManager.openStore(ctx, updatingPkg)
            }
            return
        }
        if (updateSessionTreeSeenAt == 0L) updateSessionTreeSeenAt = now

        // Escanear el árbol de nodos de Play Store
        val dm = resources.displayMetrics
        val scan = PlayButtonFinder.scan(root, dm.widthPixels, dm.heightPixels)
        UpdateFlowManager.reportDebugLabels(ctx, scan.debugLabels)

        // Si Play Store muestra una barra de progreso, ya está descargando
        if (scan.sawProgressBar) {
            val progressText = UpdateFlowManager.currentDetail(ctx) ?: "Descargando actualización..."
            UpdateFlowManager.setStage(ctx, UpdateFlowManager.STAGE_DOWNLOADING, progressText)
            return
        }

        val cooledDown = now - updateSessionLastClickAt > CANDIDATE_WAIT_MS

        // ── 4. Diálogo de confirmación ──
        if (updateSessionCandidatesTried > 0 && scan.dialogs.isNotEmpty() && cooledDown) {
            val d = scan.dialogs.first()
            UpdateFlowManager.setStage(ctx, UpdateFlowManager.STAGE_CONFIRMING,
                "Confirmando descarga...")
            if (performClickOnNode(d.node)) {
                updateSessionLastClickAt = now
                Log.i(TAG, "Clic en diálogo: ${d.reason}")
            }
            return
        }

        // ── 5. ¿Ya estaba al día? ──
        val hasDefiniteUpdate = scan.actions.any { it.score >= 80 }
        val hasOpenButton = scan.opens.isNotEmpty()

        // Caso A: Hay botón "Abrir" y no hay botón "Actualizar" con score alto
        if (!hasDefiniteUpdate && hasOpenButton && updateSessionTreeSeenAt > 0L &&
            now - updateSessionTreeSeenAt > 1500L
        ) {
            val label = UpdateFlowManager.appLabel(ctx, updatingPkg)
            Log.i(TAG, "App $updatingPkg ya está al día (botón Abrir visible, sin Actualizar)")
            UpdateFlowManager.setStage(ctx, UpdateFlowManager.STAGE_FINISHING)
            UpdateFlowManager.finish(ctx, UpdateFlowManager.RESULT_UP_TO_DATE,
                "$label ya está actualizada.")
            return
        }

        // Caso B: No hay ningún botón reconocible tras 3.5s
        if (!hasDefiniteUpdate && scan.actions.isEmpty() && !hasOpenButton &&
            updateSessionTreeSeenAt > 0L && now - updateSessionTreeSeenAt > 3500L
        ) {
            val label = UpdateFlowManager.appLabel(ctx, updatingPkg)
            Log.i(TAG, "App $updatingPkg: no se encontró botón de actualización tras 3.5s")
            UpdateFlowManager.setStage(ctx, UpdateFlowManager.STAGE_FINISHING)
            UpdateFlowManager.finish(ctx, UpdateFlowManager.RESULT_UP_TO_DATE,
                "$label ya está actualizada.")
            return
        }

        // ── 6. Probar el próximo candidato a "Actualizar" ──
        if (cooledDown && updateSessionCandidatesTried < MAX_CANDIDATES) {
            val next = scan.actions.firstOrNull { candidateKey(it) !in updateSessionTriedKeys }
            if (next != null) {
                updateSessionTriedKeys.add(candidateKey(next))
                updateSessionCandidatesTried++
                UpdateFlowManager.setStage(ctx, UpdateFlowManager.STAGE_LOOKING_BUTTON,
                    "Iniciando descarga...")
                if (performClickOnNode(next.node)) {
                    updateSessionLastClickAt = now
                    Log.i(TAG, "Candidato ${updateSessionCandidatesTried}/${MAX_CANDIDATES}: ${next.reason}")
                }
                return
            }
        }

        // ── 7. Freno por estancamiento ──
        if (now - updateSessionStartTime > UPDATE_STALL_MS) {
            Log.w(TAG, "Actualización estancada para $updatingPkg. Labels: ${scan.debugLabels}")
            UpdateFlowManager.finish(ctx, UpdateFlowManager.RESULT_ERROR,
                "No se pudo actualizar ${UpdateFlowManager.appLabel(ctx, updatingPkg)}.")
            return
        }

        UpdateFlowManager.setStage(ctx, UpdateFlowManager.STAGE_LOOKING_BUTTON,
            "Buscando actualización...")
    }

    private fun handlePlayStoreAutoUpdate(event: AccessibilityEvent, updatingPkg: String) {
        scanAndAct(updatingPkg)
    }

    /**
     * Wake lock de la actualización. Antes se creaba uno NUEVO cada vez que llegaba un
     * evento con la pantalla apagada y nunca se liberaba a mano: quedaban wake locks
     * apilados venciendo solos a los 15 s. Ahora hay uno solo, reutilizado y liberado.
     */
    private var updateWakeLock: PowerManager.WakeLock? = null

    private fun ensureScreenOnForUpdate() {
        try {
            if (powerManager.isInteractive) return
            val existing = updateWakeLock
            if (existing != null && existing.isHeld) return

            @Suppress("DEPRECATION")
            val wakeLock = powerManager.newWakeLock(
                PowerManager.SCREEN_BRIGHT_WAKE_LOCK or PowerManager.ACQUIRE_CAUSES_WAKEUP or PowerManager.ON_AFTER_RELEASE,
                "LockSuite:UpdateWakeLock"
            )
            wakeLock.acquire(15_000L)
            updateWakeLock = wakeLock
        } catch (e: Exception) {
            Log.w(TAG, "No se pudo encender pantalla para actualización: ${e.message}")
        }
    }

    private fun releaseUpdateWakeLock() {
        try {
            updateWakeLock?.let { if (it.isHeld) it.release() }
        } catch (e: Exception) {
            // ignorado: el wake lock puede haber vencido solo
        }
        updateWakeLock = null
    }

    /**
     * Sube por el árbol buscando un nodo que acepte el clic.
     * El recorrido está topeado en CLICK_PARENT_DEPTH niveles: sin tope, un
     * texto suelto podía terminar clickeando el contenedor de toda la pantalla.
     */
    private fun performClickOnNode(node: AccessibilityNodeInfo?): Boolean {
        if (node == null) return false
        try {
            if (node.isClickable && node.performAction(AccessibilityNodeInfo.ACTION_CLICK)) return true

            var current: AccessibilityNodeInfo? = node.parent
            var level = 0
            while (current != null && level < CLICK_PARENT_DEPTH) {
                if (current.isClickable && current.performAction(AccessibilityNodeInfo.ACTION_CLICK)) return true
                current = current.parent
                level++
            }

            node.performAction(AccessibilityNodeInfo.ACTION_FOCUS)
            if (node.performAction(AccessibilityNodeInfo.ACTION_CLICK)) return true
        } catch (e: Exception) {
            Log.e(TAG, "Error en performClickOnNode: ${e.message}")
        }
        return false
    }

    override fun onInterrupt() {
        Log.w(TAG, "⚠️ LockSuiteAccessibilityService interrumpido")
    }

    override fun onDestroy() {
        super.onDestroy()
        instance = null
        try {
            mdmPrefs.unregisterOnSharedPreferenceChangeListener(prefsListener)
        } catch (e: Exception) {
            // ignorado
        }
        mainHandler.removeCallbacks(whatsappScanRunnable)
        mainHandler.removeCallbacks(mercadoPagoScanRunnable)
        mainHandler.removeCallbacks(updateTickRunnable)
        mainHandler.removeCallbacks(strictScrollSettleRunnable)
        webViewRetryRunnables.forEach { mainHandler.removeCallbacks(it) }
        webViewRetryRunnables.clear()
        releaseUpdateWakeLock()
        if (::overlayManager.isInitialized) {
            overlayManager.clearAll()
        }
        AIContentGate.releaseAll()
        bgExecutor.shutdown()
    }
}
