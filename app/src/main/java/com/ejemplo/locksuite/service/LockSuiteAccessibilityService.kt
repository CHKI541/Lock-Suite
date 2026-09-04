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
import com.ejemplo.locksuite.mdm.CaptivePortalPolicy
import com.ejemplo.locksuite.mdm.GoogleAccountWebPolicy
import com.ejemplo.locksuite.mdm.PhotoPickerPolicy
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

        // ── Correcciones del 3/9/2026 (CAT S22 Flip / Android 11) ──
        //
        // El equipo del reporte es un Snapdragon 215 con 2 GB de RAM y una pantalla
        // de 480×640. Los tres números de abajo son los que estaban calibrados
        // contra un teléfono normal y no contra ese.

        /**
         * Cuánto se espera a que la ficha de Play Store TERMINE DE DIBUJARSE antes
         * de sacar cualquier conclusión.
         *
         * Antes había un solo número, 3,5 s, contados desde que aparecía la VENTANA
         * de Play Store. Pero la ventana existe apenas arranca la Activity, cuando
         * la ficha todavía es una pantalla en blanco: en un equipo lento la ficha
         * tarda entre 4 y 8 s en tener botones. O sea que el flujo concluía "no hay
         * ningún botón" sobre una pantalla que todavía no había dibujado ninguno, y
         * cerraba informando "la app ya está actualizada". Un falso éxito, y encima
         * el que hace que el dueño busque el problema donde no está.
         *
         * Ahora hay dos condiciones y las dos tienen que cumplirse: que haya pasado
         * este tiempo Y que la ficha tenga contenido dibujado (`Result.rendered`).
         */
        private const val UPDATE_CARD_RENDER_MS = 9_000L

        /**
         * Tope duro para dejar de esperar a que la ficha se dibuje. Si pasó esto y
         * la pantalla sigue vacía, el problema no es la lentitud: es que Play Store
         * no está mostrando la ficha (sin red, sin cuenta, ficha inexistente).
         */
        private const val UPDATE_CARD_GIVEUP_MS = 30_000L

        /**
         * Cuánto puede quedarse la descarga sin avanzar un solo punto porcentual
         * antes de darla por trabada. Cubre el caso "Play Store dejó la descarga
         * esperando Wi-Fi" y el caso "no hay espacio y el sistema abortó la sesión".
         * Antes no existía ningún tope acá: el ciclo salía por un `return` que
         * estaba ANTES del freno por estancamiento, así que la única salida era el
         * watchdog de 10 minutos.
         */
        private const val DOWNLOAD_STALL_MS = 150_000L

        /**
         * En una pantalla chica la fila de botones puede quedar debajo del borde
         * visible, y Android no instancia en el árbol de accesibilidad lo que no
         * está dibujado: el botón "Actualizar" simplemente no existe para nosotros.
         * Cuando la ficha ya está dibujada y no aparece ningún candidato, se baja
         * un poco antes de rendirse. Tope bajo a propósito: bajar de más lleva a
         * los carruseles de "apps similares", que es el bug 4 de B.9.
         */
        private const val MAX_UPDATE_SCROLLS = 3
        private const val SCROLL_COOLDOWN_MS = 1_200L

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
            "com.android.htmlviewer",
            "com.google.android.googlequicksearchbox" // App de Google / Asistente / Lens (Punto 1)
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
        /** Rebotar las pantallas web de la cuenta de Google (historial, actividad). */
        val googleAccountWeb: Boolean,
        /** Modo estricto: rebota TAMBIÉN "Gestionar tu cuenta de Google" entera. */
        val googleAccountStrict: Boolean,
        /** Rebotar el selector de foto de contacto / Google Illustrations / Google Fotos. */
        val contactPhotoPicker: Boolean,
        /** Vigilar la ventana de "Iniciar sesión en la red" (portal cautivo). */
        val captivePortalGuard: Boolean,
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
            // Encendido por defecto: ver el comentario de PolicyManager sobre por qué
            // este interruptor es la excepción a "todo apagado de fábrica".
            googleAccountWeb = p.getBoolean("block_google_account_web", true),
            // Por defecto NORMAL: el modo estricto rebota la pantalla entera de la
            // cuenta y eso dejó al dueño sin poder administrarla (ver GoogleAccountWebPolicy).
            googleAccountStrict = GoogleAccountWebPolicy.isStrict(
                p.getString(GoogleAccountWebPolicy.KEY_MODE, GoogleAccountWebPolicy.MODE_NORMAL)
            ),
            contactPhotoPicker = p.getBoolean("block_contact_photo_picker", true),
            captivePortalGuard = p.getBoolean(CaptivePortalPolicy.KEY_ENABLED, true),
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

        // Resolver ya las señales que dependen del equipo y del idioma (componente de
        // la pantalla de Accesibilidad y su título localizado). Ver
        // refreshAccessibilitySignals().
        try { refreshAccessibilitySignals(force = true) } catch (e: Exception) { }

        // Aviso INSTANTÁNEO y certero de que la accesibilidad volvió.
        //
        // Este es el momento exacto en que el servicio empieza a funcionar, así que no
        // hace falta esperar a que ningún ciclo lo descubra ni pasar por el antirrebote:
        // se reconcilia acá mismo y las apps se liberan al toque. Junto con el aviso de
        // onDestroy() y el ContentObserver del Watchdog, el ciclo periódico queda como
        // red de seguridad y no como el mecanismo principal.
        try {
            com.ejemplo.locksuite.util.AccessibilityEnforcer.reconcileNow(applicationContext)
            com.ejemplo.locksuite.util.BootGate.tick(applicationContext)
        } catch (e: Exception) {
            Log.w(TAG, "Reconciliación al conectar: ${e.message}")
        }

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

        // ── Ajustes de la cuenta de Google (historial de YouTube, Mi Actividad) ──
        //
        // Costo en el camino caliente: un booleano ya cacheado, una comparación de
        // enteros y a lo sumo tres comparaciones de string sobre el nombre del
        // paquete. Solo al CAMBIAR DE VENTANA, que es cuando aparece una pantalla
        // nueva — no en los CONTENT_CHANGED, que son los que llegan diez por segundo.
        if (f.googleAccountWeb &&
            eventType == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED &&
            GoogleAccountWebPolicy.isCandidatePackage(packageName)
        ) {
            if (handleGoogleAccountWebBounce(ev, packageName, f.googleAccountStrict)) return
        }

        // ── Selector de foto de contacto / Google Illustrations (switch: block_contact_photo_picker) ──
        if (f.contactPhotoPicker && eventType == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) {
            if (handleContactPhotoPickerBounce(ev.className?.toString(), packageName)) return
        }

        // ── Ventana de "Iniciar sesión en la red" (portal cautivo) ──
        // Ver mdm/CaptivePortalPolicy.kt: esta ventana esquiva la VPN por diseño, así
        // que la Capa 2 no puede hacer NADA ahí. Esto es lo único que puede.
        if (f.captivePortalGuard && eventType == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) {
            updateCaptivePortalState(packageName, ev.className?.toString())
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

        // ── Rebote de Licencias y Términos Legales en Ajustes (Punto D) ──
        //
        // 4/9 (tarde): tenía un GLOBAL_ACTION_BACK a ciegas, sin antirrebote, sin
        // excepción para el administrador y sin verificar nada. Eso es exactamente la
        // forma del bug de B.15 punto 1 ("rebota mucho más cosas, casi no se puede
        // abrir Ajustes"), que costó una sesión entera. Ahora tiene las dos guardas
        // mínimas que tienen todos los demás rebotes del archivo.
        if (eventType == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED &&
            (isSettingsPackage(packageName) || packageName == GoogleAccountWebPolicy.PKG_GMS)
        ) {
            val cls = ev.className?.toString() ?: ""
            if (isLegalOrLicenseScreen(cls) &&
                SystemClock.elapsedRealtime() >= legalBounceBackoffUntil &&
                !com.ejemplo.locksuite.security.SessionManager.isActive()
            ) {
                legalBounceBackoffUntil = SystemClock.elapsedRealtime() + LEGAL_BOUNCE_BACKOFF_MS
                Log.w(TAG, "🚫 Pantalla legal/licencias detectada: rebotando al usuario ($cls).")
                performGlobalAction(GLOBAL_ACTION_BACK)
                return
            }
        }

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

        // Portal cautivo: mientras esa ventana está al frente se tapan sus imágenes.
        // Queda el texto y los formularios —o sea que iniciar sesión sigue andando—
        // pero deja de ser un visor de contenido. Es lo único que la Capa 1 puede
        // aportar ahí, porque la Capa 2 no ve esa ventana (ver CaptivePortalPolicy).
        // El chequeo es una comparación de strings contra un campo ya cacheado.
        val portalCautivo = captiveOpenedAt != 0L &&
            CaptivePortalPolicy.isCaptivePortalWindow(activePkg, null)

        val mode = when {
            mapsBlocking -> "both"
            portalCautivo -> "layer1"
            else -> ImageBlockManager.getMode(applicationContext, activePkg)
        }

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
    // ⚠️ RESCRITO EL 18/8/2026 — la versión anterior TAPABA MUCHÍSIMO DE MÁS.
    //
    // Qué hacía mal: recorría TODO el árbol de la ventana buscando la palabra
    // "Accesibilidad" contra una lista en diez idiomas. Pero la pantalla PRINCIPAL de
    // Ajustes tiene "Accesibilidad" como una fila más de su menú... igual que la
    // pantalla de búsqueda, y que varias sub-pantallas. Resultado: rebotaba al usuario
    // de casi cualquier pantalla de Ajustes. Reporte textual del dueño: *"rebota mucho
    // más cosas, casi no se puede abrir la app de ajustes"*. Exacto.
    //
    // Y además la lista de palabras era inútil justo en el caso que importa: el dueño
    // señaló que *"si el usuario cambia el idioma a uno raro se evade todo"*. Una lista
    // de diez idiomas no cubre los ciento y pico que soporta Android.
    //
    // Ahora la detección NO LEE PALABRAS NUESTRAS. Tres señales, todas independientes
    // del idioma, de más barata a más cara:
    //
    //   1. COMPONENTE EXACTO. Se le pregunta al sistema qué actividad atiende
    //      `Settings.ACTION_ACCESSIBILITY_SETTINGS`. Devuelve el nombre de clase real
    //      de ESTE equipo (AOSP, Samsung, Xiaomi, el que sea) y no cambia con el
    //      idioma. Comparar `ev.className` contra eso es exacto y gratis.
    //
    //   2. TÍTULO LOCALIZADO PEDIDO A LA PROPIA APP DE AJUSTES. Se leen sus recursos
    //      (`getResourcesForApplication` + `getIdentifier("accessibility_settings")`) y
    //      se obtiene el título EXACTO en el idioma que el equipo tenga puesto ahora,
    //      sea el que sea. Es la respuesta al problema del idioma: en vez de que
    //      nosotros traduzcamos, le preguntamos a Ajustes cómo se dice en su idioma.
    //      Y se compara SOLO contra el título de la ventana, nunca contra el árbol.
    //
    //   3. NUESTRA PROPIA ETIQUETA + UN INTERRUPTOR. La pantalla donde realmente se
    //      apaga el servicio muestra el nombre de nuestra app junto a un `Switch`.
    //      Nuestro nombre no se traduce, así que esa combinación identifica la pantalla
    //      en cualquier idioma sin depender de Ajustes.
    //
    // La primera línea de defensa real contra el cambio de idioma, igual, es el
    // interruptor "Bloquear cambio de idioma" (`DISALLOW_CONFIG_LOCALE`) que se agregó
    // el 18/8. Esto es la segunda.

    private var accBounceInProgress = false
    private var accBounceBackoffUntil = 0L

    /** Cache de las señales que dependen del equipo y del idioma actual. */
    private var accSettingsClasses: Set<String> = emptySet()
    private var accSettingsTitles: Set<String> = emptySet()
    private var accOwnLabelFolded: String = ""
    private var accSignalsAt = 0L

    /** Cada cuánto se vuelven a resolver componente y título localizado. */
    private val ACC_SIGNALS_TTL_MS = 10 * 60 * 1000L

    private fun isSettingsPackage(pkg: String): Boolean =
        pkg == SETTINGS_PKG || pkg.endsWith(".settings")

    /**
     * Resuelve —y cachea— el nombre de clase de la pantalla de Accesibilidad y su
     * título en el idioma actual del equipo. Se rehace cada 10 minutos, y también
     * cuando cambia la configuración (ver `onConfigurationChanged`), que es
     * exactamente lo que pasa cuando el usuario cambia el idioma.
     */
    private fun refreshAccessibilitySignals(force: Boolean = false) {
        val now = SystemClock.elapsedRealtime()
        if (!force && accSignalsAt != 0L && now - accSignalsAt < ACC_SIGNALS_TTL_MS) return
        accSignalsAt = now

        val classes = HashSet<String>(4)
        val titles = HashSet<String>(4)

        // 1. ¿Qué actividad atiende "abrir los ajustes de accesibilidad"?
        try {
            val intent = Intent(android.provider.Settings.ACTION_ACCESSIBILITY_SETTINGS)
            val info = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                packageManager.resolveActivity(intent, PackageManager.ResolveInfoFlags.of(0L))
            } else {
                @Suppress("DEPRECATION")
                packageManager.resolveActivity(intent, 0)
            }
            info?.activityInfo?.let { ai ->
                classes.add(ai.name)
                // Muchos fabricantes exponen un alias `Settings$XxxActivity` y ejecutan
                // otra clase por debajo; guardar las dos formas cuesta nada.
                ai.targetActivity?.let { classes.add(it) }
            }
        } catch (e: Exception) {
            Log.w(TAG, "No se pudo resolver la actividad de Accesibilidad: ${e.message}")
        }

        // 2. El título, en el idioma que el equipo tenga puesto AHORA, pedido a la
        //    propia app de Ajustes. `accessibility_settings` es el nombre del recurso
        //    en AOSP y lo respetan los fabricantes; si no existe, simplemente no se
        //    agrega ninguna señal de título y quedan las otras dos.
        for (pkg in listOf(SETTINGS_PKG, "com.samsung.android.settings")) {
            try {
                val res = packageManager.getResourcesForApplication(pkg)
                for (name in listOf("accessibility_settings", "accessibility_settings_title")) {
                    val id = res.getIdentifier(name, "string", pkg)
                    if (id != 0) {
                        val value = res.getString(id)
                        if (!value.isNullOrBlank()) titles.add(foldAccents(value).trim())
                    }
                }
            } catch (e: Exception) {
                // Normal: ese paquete puede no existir en este equipo.
            }
        }

        // 3. Nuestra propia etiqueta, que no se traduce.
        accOwnLabelFolded = try {
            val label = packageManager.getApplicationLabel(applicationInfo)?.toString() ?: ""
            foldAccents(label).trim()
        } catch (e: Exception) {
            "locksuite"
        }

        accSettingsClasses = classes
        accSettingsTitles = titles
        if (VERBOSE) {
            Log.d(TAG, "Señales de Accesibilidad: clases=$classes titulos=$titles etiqueta=$accOwnLabelFolded")
        }
    }

    /**
     * El usuario cambió el idioma (o el tamaño de fuente, o rotó). Lo que importa acá
     * es el idioma: los títulos cacheados quedaron en el idioma anterior.
     */
    override fun onConfigurationChanged(newConfig: android.content.res.Configuration) {
        super.onConfigurationChanged(newConfig)
        refreshAccessibilitySignals(force = true)
    }

    /** Devuelve true si rebotó (y por lo tanto el evento ya está atendido). */
    private fun handleAccessibilitySettingsBounce(ev: AccessibilityEvent, eventType: Int): Boolean {
        // Solo al cambiar de ventana. Una sub-pantalla de Ajustes SIEMPRE llega con un
        // WINDOW_STATE_CHANGED; mirar también los CONTENT_CHANGED era trabajo repetido
        // y multiplicaba las chances de un falso positivo.
        if (eventType != AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) return false

        val now = SystemClock.elapsedRealtime()
        if (accBounceInProgress || now < accBounceBackoffUntil) return false
        // El administrador con sesión abierta puede entrar a propósito.
        if (com.ejemplo.locksuite.security.SessionManager.isActive()) return false

        refreshAccessibilitySignals()
        if (!isAccessibilitySettingsScreen(ev)) return false

        accBounceInProgress = true
        Log.w(TAG, "🚫 Menú de Accesibilidad de Ajustes: rebotando al usuario.")
        performGlobalAction(GLOBAL_ACTION_BACK)

        mainHandler.postDelayed({
            // Verificación: si el "atrás" sirvió, no hay nada más que hacer. Solo se
            // fuerza HOME si seguimos en la MISMA pantalla, y con backoff, para no
            // encadenar acciones y sacar al usuario de Ajustes por completo sin motivo.
            var stillThere = false
            val current = rootInActiveWindow
            if (current != null) {
                val pkg = current.packageName?.toString() ?: ""
                if (isSettingsPackage(pkg)) {
                    stillThere = hasOwnLabelWithSwitch(current)
                }
                current.recycle()
            }
            if (stillThere) {
                Log.w(TAG, "🏠 La pantalla del servicio persiste: forzando HOME.")
                performGlobalAction(GLOBAL_ACTION_HOME)
                accBounceBackoffUntil = SystemClock.elapsedRealtime() + ACC_BOUNCE_BACKOFF_MS
            }
            accBounceInProgress = false
        }, 600)

        return true
    }

    /** Las tres señales, de la más barata a la más cara. */
    private fun isAccessibilitySettingsScreen(ev: AccessibilityEvent): Boolean {
        // ── Señal 1: componente exacto ──
        val cls = ev.className?.toString()
        if (cls != null) {
            if (cls in accSettingsClasses) return true
            // Red de seguridad para fabricantes con actividad propia y nombre parlante.
            // NO alcanza sola en Android puro (usa `SubSettings` para casi todo), pero
            // cuando aparece es inequívoca.
            if (cls.contains("AccessibilitySettings", ignoreCase = true) ||
                cls.contains("AccessibilityMenu", ignoreCase = true)
            ) return true
        }

        // ── Señal 2: título de la ventana contra el título localizado real ──
        // Ojo: SOLO el título, no el árbol. Ese fue el bug que rebotaba media app de
        // Ajustes, porque "Accesibilidad" aparece como fila en el menú principal.
        if (accSettingsTitles.isNotEmpty()) {
            val evTitle = ev.text?.firstOrNull()?.toString()
            if (evTitle != null) {
                val folded = foldAccents(evTitle).trim()
                if (folded in accSettingsTitles) return true
            }
        }

        // ── Señal 3: nuestra etiqueta junto a un interruptor ──
        // Es la pantalla donde realmente se apaga el servicio. Independiente del idioma
        // porque el nombre de nuestra app no se traduce.
        val root = rootInActiveWindow ?: return false
        return try {
            hasOwnLabelWithSwitch(root)
        } finally {
            root.recycle()
        }
    }

    private class LabelSwitchScan {
        var sawLabel = false
        var sawSwitch = false
        val done: Boolean get() = sawLabel && sawSwitch
    }

    /**
     * ¿La ventana muestra el nombre de NUESTRA app y además un interruptor?
     *
     * Esa combinación solo se da en pantallas de Ajustes que gobiernan LockSuite: la
     * del servicio de accesibilidad, la de información de la app, la de notificaciones.
     * Rebotar de todas ellas es deseable en un MDM. Y no depende de ningún idioma.
     */
    private fun hasOwnLabelWithSwitch(root: AccessibilityNodeInfo): Boolean {
        if (accOwnLabelFolded.isEmpty()) return false
        val state = LabelSwitchScan()
        nodeBudget = 600
        scanLabelSwitchNode(root, 0, state)
        return state.done
    }

    private fun scanLabelSwitchNode(node: AccessibilityNodeInfo, depth: Int, out: LabelSwitchScan) {
        if (out.done || depth > 14) return
        if (nodeBudget-- <= 0) return

        if (!out.sawSwitch) {
            val cn = node.className?.toString()
            if (cn != null &&
                (cn.endsWith("Switch") || cn.endsWith("ToggleButton") ||
                 cn.endsWith("SwitchCompat") || cn.endsWith("CheckBox"))
            ) out.sawSwitch = true
        }

        if (!out.sawLabel) {
            val text = node.text
            if (text != null && text.isNotEmpty() &&
                containsWholeWord(foldAccents(text), accOwnLabelFolded)
            ) out.sawLabel = true
        }
        if (!out.sawLabel) {
            val desc = node.contentDescription
            if (desc != null && desc.isNotEmpty() &&
                containsWholeWord(foldAccents(desc), accOwnLabelFolded)
            ) out.sawLabel = true
        }
        if (out.done) return

        val childCount = node.childCount
        for (i in 0 until childCount) {
            val child = node.getChild(i) ?: continue
            try {
                scanLabelSwitchNode(child, depth + 1, out)
            } finally {
                child.recycle()
            }
            if (out.done) return
        }
    }

    /**
     * Detección de pantallas de Licencias de Código Abierto y Términos Legales (Punto D).
     * Evita que el usuario abra las listas de licencias con hipervínculos web.
     */
    private var legalBounceBackoffUntil = 0L
    private val LEGAL_BOUNCE_BACKOFF_MS = 3_000L

    private fun isLegalOrLicenseScreen(cls: String): Boolean {
        if (cls.isEmpty()) return false
        val lower = cls.lowercase()
        return lower.contains("license") ||
               lower.contains("legalsettings") ||
               lower.contains("opensource") ||
               lower.contains("copyright")
    }

    // ──────────────────────────────────────────────
    // Ajustes de la cuenta de Google  (switch: block_google_account_web)   [4/9/2026]
    //
    // Hallazgo del dueño: Ajustes → Google → Gestionar tu cuenta → Datos y privacidad
    // → Historial de YouTube muestra los videos vistos, con miniatura, DENTRO de
    // Ajustes. El porqué completo y los límites están en `mdm/GoogleAccountWebPolicy.kt`.
    //
    // Esta es la mitad de Capa 3: rebota antes de que la pantalla se vea. La mitad que
    // de verdad cierra es la de Capa 2 (los dominios), porque no depende ni del idioma,
    // ni del fabricante, ni de que este servicio esté prendido.
    //
    // Detección por NOMBRE DE CLASE de la ventana y nada más. Ni un solo texto de
    // pantalla: cambiar el idioma del equipo (B.19) no evade esto. Es la regla de B.19
    // punto 3 — "si hay una señal estructural, usarla antes que una palabra".
    // ──────────────────────────────────────────────

    private var gAccBounceInProgress = false
    private var gAccBounceBackoffUntil = 0L
    private val gAccBounceBackoffMs = 4_000L

    /** Clases de Play services ya anotadas, para no reescribir la preferencia. */
    private val gAccSeenClasses = HashSet<String>(16)
    private val gAccMaxSeen = 12

    /** Devuelve true si rebotó (y por lo tanto el evento ya está atendido). */
    private fun handleGoogleAccountWebBounce(
        ev: AccessibilityEvent,
        packageName: String,
        strict: Boolean
    ): Boolean {
        val cls = ev.className?.toString()
        if (!GoogleAccountWebPolicy.isAccountWebClass(cls, strict)) {
            recordUnknownGoogleClass(packageName, cls)
            return false
        }

        val now = SystemClock.elapsedRealtime()
        if (gAccBounceInProgress || now < gAccBounceBackoffUntil) return false
        // El administrador con sesión abierta entra a propósito (mismo criterio que el
        // rebote del menú de Accesibilidad): si no, no podría ni revisar la cuenta del
        // equipo que administra.
        if (com.ejemplo.locksuite.security.SessionManager.isActive()) return false

        gAccBounceInProgress = true
        Log.w(TAG, "🚫 Ajustes/actividad de la cuenta de Google ($cls): rebotando al usuario.")
        mainHandler.post {
            Toast.makeText(
                applicationContext,
                "🚫 Ajustes de la cuenta de Google restringidos por LockSuite",
                Toast.LENGTH_SHORT
            ).show()
        }
        performGlobalAction(GLOBAL_ACTION_BACK)

        mainHandler.postDelayed({
            // Verificación, igual que en Mercado Pago: un "atrás" a ciegas encadena
            // rebotes. Y acá hay un motivo extra — esta pantalla es un WebView, así
            // que "atrás" puede navegar DENTRO de la página en vez de cerrarla.
            //
            // Solo se escala a HOME si seguimos dentro de Play services. Si el "atrás"
            // nos devolvió a Ajustes, funcionó: no hay que sacar al usuario de Ajustes
            // por completo, que fue exactamente el sobre-bloqueo de B.15 punto 1.
            var stillInGms = false
            val current = rootInActiveWindow
            if (current != null) {
                stillInGms = current.packageName?.toString() == GoogleAccountWebPolicy.PKG_GMS
                current.recycle()
            }
            if (stillInGms) {
                Log.w(TAG, "🏠 La pantalla de la cuenta persiste: forzando HOME.")
                performGlobalAction(GLOBAL_ACTION_HOME)
                gAccBounceBackoffUntil = SystemClock.elapsedRealtime() + gAccBounceBackoffMs
            }
            gAccBounceInProgress = false
        }, 700)

        return true
    }

    /**
     * Anota las clases de Play services que se vieron y no se supieron clasificar.
     *
     * Play services reparte sus pantallas en módulos que se actualizan solos (Chimera),
     * así que el nombre de clase de hoy puede no ser el de mañana. Sin este dato, un
     * equipo donde el rebote no dispare es una sesión entera de adivinanza — que es
     * exactamente lo que pasó con `debugLabels` en B.41.
     *
     * Acotado a propósito: solo Play services (la app de Ajustes tiene cientos de
     * clases y no aportan nada), solo hasta `gAccMaxSeen` clases nuevas por proceso, y
     * con un HashSet en memoria adelante para que a partir de la segunda vez sea un
     * lookup y no una escritura.
     */
    private fun recordUnknownGoogleClass(packageName: String, className: String?) {
        if (packageName != GoogleAccountWebPolicy.PKG_GMS) return
        if (className.isNullOrEmpty()) return
        if (gAccSeenClasses.size >= gAccMaxSeen) return
        if (!gAccSeenClasses.add(className)) return
        try {
            mdmPrefs.edit()
                .putString("google_account_web_seen", gAccSeenClasses.joinToString(","))
                .apply()
        } catch (e: Exception) {
            Log.w(TAG, "No se pudo anotar la clase de Play services: ${e.message}")
        }
    }

    // ──────────────────────────────────────────────
    // Selector de fotos e ilustraciones de contactos (switch: block_contact_photo_picker)
    //
    // Permite bloquear el selector de fotos de Google Contacts y Google Profile Photo Picker
    // que da acceso a Google Fotos y al catálogo abierto de Google Illustrations.
    // Viene ENCENDIDO de fábrica.
    // ──────────────────────────────────────────────

    private var contactPhotoBounceInProgress = false
    private var contactPhotoBounceBackoffUntil = 0L
    private val contactPhotoBounceBackoffMs = 2_000L

    /**
     * ¿Alguna de las últimas ventanas fue una app de contactos?
     *
     * Se mira el stack y no solo la anterior porque el camino real puede tener un
     * salto en el medio: Contactos → selector → catálogo de ilustraciones. Con tres
     * niveles alcanza y sigue siendo un recorrido de tres strings en memoria.
     */
    private fun cameFromContacts(): Boolean {
        var i = 0
        for (pkg in appPackageStack) {
            if (PhotoPickerPolicy.isContactsPackage(pkg)) return true
            if (++i >= 3) break
        }
        return false
    }

    /**
     * Devuelve true si rebotó (y por lo tanto el evento ya está atendido).
     *
     * ⚠️ NO tiene excepción por sesión de administrador, y es a propósito: esa era la
     * causa número uno del "a veces no rebota" que reportó el dueño. La sesión dura
     * 5 minutos desde que se ingresa el PIN, que es exactamente lo que hay que hacer
     * para encender el interruptor y después ir a probarlo — o sea que el rebote se
     * apagaba solo justo mientras alguien lo probaba. Es el mismo bug que B.15
     * primera corrección puntos 3 y 4 ("no estaban rotas: estaban calladas"). Y acá
     * no hace falta la excepción: un administrador no necesita abrir un selector de
     * fotos. **No volver a agregarla.**
     */
    private fun handleContactPhotoPickerBounce(className: String?, packageName: String): Boolean {
        if (!PhotoPickerPolicy.shouldBounce(packageName, className, cameFromContacts())) {
            recordUnmatchedPickerClass(packageName, className)
            return false
        }

        val now = SystemClock.elapsedRealtime()
        if (contactPhotoBounceInProgress || now < contactPhotoBounceBackoffUntil) return false

        contactPhotoBounceInProgress = true
        contactPhotoBounceBackoffUntil = now + contactPhotoBounceBackoffMs
        Log.w(TAG, "🚫 Selector de foto detectado ($className en $packageName): rebotando.")
        mainHandler.post {
            Toast.makeText(
                applicationContext,
                "🚫 Selección de foto bloqueada por LockSuite",
                Toast.LENGTH_SHORT
            ).show()
        }
        performGlobalAction(GLOBAL_ACTION_BACK)

        mainHandler.postDelayed({
            contactPhotoBounceInProgress = false
        }, 900L)
        return true
    }

    /**
     * Anota las clases que se vieron en paquetes de selector/contactos y NO se
     * clasificaron. Es el dato con el que se calibra en un equipo donde el rebote no
     * dispare, en vez de adivinar — la misma idea que `debugLabels` de B.41 y que
     * `googleAccountWebSeenClasses` de B.43. **Sin esto, "a veces no rebota" es una
     * sesión entera de adivinanza**, que es exactamente lo que pasó.
     */
    private val pickerSeenClasses = HashSet<String>(16)
    private val pickerMaxSeen = 12

    private fun recordUnmatchedPickerClass(packageName: String, className: String?) {
        if (className.isNullOrEmpty()) return
        if (!PhotoPickerPolicy.isRelevantPackage(packageName)) return
        if (pickerSeenClasses.size >= pickerMaxSeen) return
        if (!pickerSeenClasses.add(className)) return
        try {
            mdmPrefs.edit()
                .putString("photo_picker_seen", pickerSeenClasses.joinToString(","))
                .apply()
        } catch (e: Exception) {
            Log.w(TAG, "No se pudo anotar la clase del selector: ${e.message}")
        }
    }

    // ──────────────────────────────────────────────
    // Portal cautivo  (switch: captive_portal_guard)   [5/9/2026]
    //
    // Leer primero el comentario de cabecera de `mdm/CaptivePortalPolicy.kt`: esa
    // ventana llama a `bindProcessToNetwork()` y esquiva la VPN por diseño, así que
    // la Capa 2 no puede filtrar NADA ahí. Esto es lo único que sí puede.
    // ──────────────────────────────────────────────

    /** elapsedRealtime en que se vio la ventana del portal por primera vez, o 0. */
    private var captiveOpenedAt = 0L
    private var captiveBounceInProgress = false

    private val captiveTickRunnable = object : Runnable {
        override fun run() {
            if (captiveOpenedAt == 0L) return  // sin re-encolar: la ventana ya no está
            val abierta = SystemClock.elapsedRealtime() - captiveOpenedAt

            // 1. La red validó: la ventana ya no tiene razón de existir.
            if (abierta >= CaptivePortalPolicy.VALIDATED_GRACE_MS && isNetworkValidated()) {
                closeCaptivePortal("la red ya está conectada")
                return
            }
            // 2. Tope duro.
            if (abierta >= CaptivePortalPolicy.MAX_OPEN_MS) {
                closeCaptivePortal("se agotó el tiempo de inicio de sesión")
                return
            }
            mainHandler.postDelayed(this, CaptivePortalPolicy.TICK_MS)
        }
    }

    private fun updateCaptivePortalState(packageName: String, className: String?) {
        val esPortal = CaptivePortalPolicy.isCaptivePortalWindow(packageName, className)
        if (esPortal) {
            if (captiveOpenedAt == 0L) {
                captiveOpenedAt = SystemClock.elapsedRealtime()
                captiveBounceInProgress = false
                try {
                    val p = mdmPrefs
                    p.edit()
                        .putInt("captive_portal_opens", p.getInt("captive_portal_opens", 0) + 1)
                        .putLong("captive_portal_last_open_at", System.currentTimeMillis())
                        .apply()
                } catch (e: Exception) {
                    Log.w(TAG, "No se pudo anotar la apertura del portal: ${e.message}")
                }
                Log.i(TAG, "Portal cautivo abierto: vigilando (tope ${CaptivePortalPolicy.MAX_OPEN_MS} ms).")
                mainHandler.removeCallbacks(captiveTickRunnable)
                mainHandler.postDelayed(captiveTickRunnable, CaptivePortalPolicy.TICK_MS)
            }
        } else if (captiveOpenedAt != 0L) {
            // Se fue a otra ventana: cerrar el ciclo y sumar el tiempo que estuvo abierta.
            finishCaptivePortalSession()
        }
    }

    private fun finishCaptivePortalSession() {
        val abierta = if (captiveOpenedAt == 0L) 0L else SystemClock.elapsedRealtime() - captiveOpenedAt
        captiveOpenedAt = 0L
        mainHandler.removeCallbacks(captiveTickRunnable)
        if (abierta <= 0L) return
        try {
            val p = mdmPrefs
            p.edit()
                .putLong("captive_portal_total_ms", p.getLong("captive_portal_total_ms", 0L) + abierta)
                .apply()
        } catch (e: Exception) {
            Log.w(TAG, "No se pudo anotar el tiempo del portal: ${e.message}")
        }
    }

    private fun closeCaptivePortal(motivo: String) {
        if (captiveBounceInProgress) return
        captiveBounceInProgress = true
        Log.w(TAG, "🚫 Cerrando la ventana del portal cautivo: $motivo.")
        mainHandler.post {
            Toast.makeText(
                applicationContext,
                "🚫 Ventana de la red cerrada por LockSuite: $motivo",
                Toast.LENGTH_LONG
            ).show()
        }
        performGlobalAction(GLOBAL_ACTION_HOME)
        finishCaptivePortalSession()
    }

    /**
     * ¿La red Wi-Fi ya está validada (o sea, el login del portal funcionó)?
     *
     * Se recorren las redes y se descartan las de transporte VPN. **No se usa
     * `cm.activeNetwork` a secas, a propósito:** con la VPN levantada eso devuelve la
     * red del propio túnel, que es exactamente la trampa que causó la causa 1 de B.18
     * (el resolutor terminaba siendo `fd00::1` y todo daba timeout). La señal buena
     * son dos banderas del sistema sobre la red física: VALIDATED puesta y
     * CAPTIVE_PORTAL sacada.
     */
    private fun isNetworkValidated(): Boolean {
        return try {
            val cm = getSystemService(Context.CONNECTIVITY_SERVICE)
                as android.net.ConnectivityManager
            @Suppress("DEPRECATION")
            for (net in cm.allNetworks) {
                val caps = cm.getNetworkCapabilities(net) ?: continue
                if (caps.hasTransport(android.net.NetworkCapabilities.TRANSPORT_VPN)) continue
                if (!caps.hasTransport(android.net.NetworkCapabilities.TRANSPORT_WIFI)) continue
                val validada = caps.hasCapability(
                    android.net.NetworkCapabilities.NET_CAPABILITY_VALIDATED
                )
                val siguePortal = caps.hasCapability(
                    android.net.NetworkCapabilities.NET_CAPABILITY_CAPTIVE_PORTAL
                )
                if (validada && !siguePortal) return true
            }
            false
        } catch (e: Exception) {
            Log.w(TAG, "No se pudo consultar el estado de la red: ${e.message}")
            false
        }
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
    /** Primera vez que la ficha tuvo contenido dibujado (no una pantalla en blanco). */
    private var updateSessionRenderedAt = 0L
    private var updateSessionScrolls = 0
    private var updateSessionLastScrollAt = 0L

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
        updateSessionRenderedAt = 0L
        updateSessionScrolls = 0
        updateSessionLastScrollAt = 0L
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
     * Tamaño real de la ventana de Play Store.
     *
     * 3/9/2026: antes se usaba `resources.displayMetrics` del propio servicio, que
     * es lo que el documento de diseño del 16/8 (§1.3) ya marcaba como poco
     * confiable: desde un Service no siempre refleja el área utilizable, y en
     * pantallas raras (un flip de 2,8", una pantalla partida) puede no tener nada
     * que ver con la ventana que estamos escaneando. Las coordenadas de los nodos
     * vienen de `getBoundsInScreen()`, así que lo correcto es medir contra la
     * ventana misma.
     */
    private val storeBoundsRect = Rect()
    private fun playStoreBounds(root: AccessibilityNodeInfo): Pair<Int, Int> {
        try {
            root.getBoundsInScreen(storeBoundsRect)
            val w = storeBoundsRect.width()
            val h = storeBoundsRect.height()
            // Un tamaño absurdo (0, o una franja) significa que el nodo raíz no
            // representa la ventana: se cae a las métricas del sistema.
            if (w > 100 && h > 100) return w to h
        } catch (e: Exception) {
            // sigue al camino de abajo
        }
        val dm = resources.displayMetrics
        return dm.widthPixels to dm.heightPixels
    }

    /**
     * Traduce lo que se reconoció en pantalla a un resultado con motivo.
     * Devuelve null si no hay nada anormal que reportar.
     *
     * Todo esto es nuevo el 3/9/2026: antes, cualquiera de estas pantallas caía en
     * el mismo saco de "no reconocí ningún botón" y el flujo cerraba con
     * RESULT_UP_TO_DATE, o sea informando que la app ya estaba actualizada. Es la
     * peor forma de fallar que puede tener este flujo, porque manda al dueño a
     * buscar el problema a otro lado.
     */
    private fun diagnosisResult(
        ctx: Context,
        updatingPkg: String,
        diagnosis: PlayButtonFinder.Diagnosis,
        evidence: String?
    ): Triple<String, String, String>? {
        val label = UpdateFlowManager.appLabel(ctx, updatingPkg)
        val ev = if (evidence.isNullOrBlank()) "" else " [$evidence]"
        return when (diagnosis) {
            PlayButtonFinder.Diagnosis.NO_SPACE -> {
                val space = UpdateFlowManager.checkSpace(ctx, updatingPkg)
                val libres = if (space.freeMb == Long.MAX_VALUE) "?" else space.freeMb.toString()
                Triple(
                    UpdateFlowManager.RESULT_NO_SPACE,
                    "No hay espacio para actualizar $label. Quedan $libres MB libres. Liberá espacio y volvé a intentar.",
                    "Play Store informó falta de espacio (libres=${libres}MB)$ev"
                )
            }
            PlayButtonFinder.Diagnosis.NEED_SIGN_IN -> Triple(
                UpdateFlowManager.RESULT_NEEDS_ACCOUNT,
                "Google Play pide iniciar sesión. Este equipo no tiene una cuenta de Google configurada, así que no puede actualizar por la tienda.",
                "Play Store pidió iniciar sesión$ev"
            )
            PlayButtonFinder.Diagnosis.NOT_COMPATIBLE -> Triple(
                UpdateFlowManager.RESULT_NOT_AVAILABLE,
                "Google Play dice que $label no es compatible con este equipo.",
                "Play Store informó incompatibilidad$ev"
            )
            PlayButtonFinder.Diagnosis.NOT_FOUND -> Triple(
                UpdateFlowManager.RESULT_NOT_AVAILABLE,
                "Google Play no encuentra la ficha de $label en este equipo.",
                "Ficha inexistente en Play Store$ev"
            )
            PlayButtonFinder.Diagnosis.NETWORK_ERROR -> Triple(
                UpdateFlowManager.RESULT_STORE_ERROR,
                "Google Play no tiene conexión. Revisá la red y volvé a intentar.",
                "Play Store informó error de red$ev"
            )
            PlayButtonFinder.Diagnosis.WAITING_NETWORK -> Triple(
                UpdateFlowManager.RESULT_STORE_ERROR,
                "Google Play dejó la descarga esperando Wi-Fi. Conectá el equipo a Wi-Fi y volvé a intentar.",
                "Descarga en espera de Wi-Fi$ev"
            )
            PlayButtonFinder.Diagnosis.STORE_ERROR -> Triple(
                UpdateFlowManager.RESULT_STORE_ERROR,
                "Google Play devolvió un error. Probá de nuevo más tarde.",
                "Error de Play Store$ev"
            )
            PlayButtonFinder.Diagnosis.NONE -> null
        }
    }

    /**
     * Cierra el flujo con la mejor explicación disponible. El orden importa: se
     * mide el espacio ANTES de creerle a la pantalla, porque medir es concluyente
     * y leer la pantalla es una heurística.
     */
    private fun finishUpdateWithBestReason(
        ctx: Context,
        updatingPkg: String,
        scan: PlayButtonFinder.Result?,
        fallbackResult: String,
        fallbackMessage: String,
        fallbackReason: String
    ) {
        val label = UpdateFlowManager.appLabel(ctx, updatingPkg)

        // 1. Medición dura: ¿alcanza el espacio? Es la causa que se confirmó en el
        //    equipo del 3/9 y la única que se puede afirmar sin leer la pantalla.
        val space = UpdateFlowManager.checkSpace(ctx, updatingPkg)
        if (!space.ok) {
            UpdateFlowManager.setStage(ctx, UpdateFlowManager.STAGE_FINISHING)
            UpdateFlowManager.finish(
                ctx, UpdateFlowManager.RESULT_NO_SPACE,
                "No hay espacio para actualizar $label. Quedan ${space.freeMb} MB libres y hacen falta ${space.neededMb} MB.",
                "Espacio insuficiente medido al cerrar: libres=${space.freeMb}MB, necesarios=${space.neededMb}MB"
            )
            return
        }

        // 2. Lo que dijo la pantalla de Play Store.
        val diag = scan?.let { diagnosisResult(ctx, updatingPkg, it.diagnosis, it.diagnosisEvidence) }
        if (diag != null) {
            UpdateFlowManager.setStage(ctx, UpdateFlowManager.STAGE_FINISHING)
            UpdateFlowManager.finish(ctx, diag.first, diag.second, diag.third)
            return
        }

        // 3. Lo que haya quedado. Se adjuntan las etiquetas vistas, que es lo que
        //    permite diagnosticar el equipo desde el panel sin pedir un ADB.
        val labels = scan?.debugLabels.orEmpty()
        val reason = if (labels.isEmpty()) fallbackReason
        else "$fallbackReason | botones vistos: ${labels.joinToString(" · ").take(200)}"
        UpdateFlowManager.setStage(ctx, UpdateFlowManager.STAGE_FINISHING)
        UpdateFlowManager.finish(ctx, fallbackResult, fallbackMessage, reason)
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
        //
        // ⚠️ CORREGIDO EL 3/9/2026 — ACÁ ESTABA EL CUELGUE DE 10 MINUTOS.
        //
        // Este bloque salía con `return` apenas `sawSession` valía true. Y
        // `sawSession` no vuelve nunca a false. O sea que si Play Store creaba la
        // sesión y el sistema después la abortaba —falta de espacio es la causa
        // clásica, y es la que se midió en el equipo el 3/9— el ciclo salía por acá
        // en CADA tick, mostrando "Descargando..." para siempre. El freno por
        // estancamiento del paso 7 está más abajo en esta misma función: no se
        // alcanzaba nunca. La única salida era el watchdog de 10 minutos, con un
        // mensaje genérico que no decía nada.
        //
        // Ahora el estado "descargando" tiene su propio reloj, y no puede quedarse
        // ahí sin avanzar.
        val livePct = PlayUpdateSessionWatcher.currentProgressFor(ctx, updatingPkg)
        val pct = if (livePct >= 0) livePct else PlayUpdateSessionWatcher.lastProgress
        if (pct >= 0 || PlayUpdateSessionWatcher.sawSession) {
            val sessionAlive = livePct >= 0 ||
                PlayUpdateSessionWatcher.hasActiveSessionFor(ctx, updatingPkg)
            val failedAt = PlayUpdateSessionWatcher.sessionFailedAt

            // (a) El sistema abortó la sesión y no hay ninguna otra viva. Se le dan
            //     unos segundos de gracia porque Play Store parte las descargas
            //     grandes en varias sesiones y descarta las intermedias.
            if (failedAt > 0L && !sessionAlive && now - failedAt > 6_000L) {
                Log.w(TAG, "Sesión de instalación abortada y sin reemplazo para $updatingPkg")
                finishUpdateWithBestReason(
                    ctx, updatingPkg, safeScanPlayStore(),
                    UpdateFlowManager.RESULT_ERROR,
                    "La instalación de ${UpdateFlowManager.appLabel(ctx, updatingPkg)} fue interrumpida por el sistema.",
                    "PackageInstaller informó onFinished(success=false) y no quedó ninguna sesión activa"
                )
                return
            }

            // (b) Arrancó pero no avanza. Cubre "esperando Wi-Fi", "descarga
            //     pausada" y el equipo que se quedó sin espacio a mitad de camino.
            val lastMove = maxOf(
                PlayUpdateSessionWatcher.lastProgressAt,
                PlayUpdateSessionWatcher.sawSessionAt
            )
            if (lastMove > 0L && now - lastMove > DOWNLOAD_STALL_MS) {
                Log.w(TAG, "Descarga sin avance por ${(now - lastMove) / 1000}s para $updatingPkg")
                finishUpdateWithBestReason(
                    ctx, updatingPkg, safeScanPlayStore(),
                    UpdateFlowManager.RESULT_ERROR,
                    "La descarga de ${UpdateFlowManager.appLabel(ctx, updatingPkg)} no avanzó. Revisá la conexión y el espacio libre.",
                    "Descarga estancada en ${if (pct >= 0) "$pct%" else "sin progreso"} durante ${(now - lastMove) / 1000}s"
                )
                return
            }

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
            // Si Play Store nunca aparece, el freno por estancamiento tiene que
            // poder actuar igual. Antes este `return` también lo salteaba.
            if (now - updateSessionStartTime > UPDATE_STALL_MS) {
                finishUpdateWithBestReason(
                    ctx, updatingPkg, null,
                    UpdateFlowManager.RESULT_STORE_ERROR,
                    "Google Play no se abrió en este equipo.",
                    "La ventana de Play Store nunca apareció en ${UPDATE_STALL_MS / 1000}s"
                )
            }
            return
        }
        if (updateSessionTreeSeenAt == 0L) updateSessionTreeSeenAt = now

        // Escanear el árbol de nodos de Play Store, midiendo contra la ventana real
        val (screenW, screenH) = playStoreBounds(root)
        val scan = PlayButtonFinder.scan(root, screenW, screenH, resources.displayMetrics.density)
        UpdateFlowManager.reportDebugLabels(ctx, scan.debugLabels)

        if (scan.rendered && updateSessionRenderedAt == 0L) updateSessionRenderedAt = now

        // Si Play Store muestra una barra de progreso, ya está descargando
        if (scan.sawProgressBar) {
            val progressText = UpdateFlowManager.currentDetail(ctx) ?: "Descargando actualización..."
            UpdateFlowManager.setStage(ctx, UpdateFlowManager.STAGE_DOWNLOADING, progressText)
            return
        }

        // ── 3-bis. Pantalla de error de Play Store reconocida ──
        //
        // Va antes de cualquier clic: en la pantalla de "sin espacio" el único
        // botón grande abre el administrador de almacenamiento del sistema, y
        // apretarlo saca al usuario de la tienda con la pantalla todavía tapada.
        if (scan.diagnosis != PlayButtonFinder.Diagnosis.NONE) {
            val diag = diagnosisResult(ctx, updatingPkg, scan.diagnosis, scan.diagnosisEvidence)
            if (diag != null) {
                Log.w(TAG, "Play Store: ${scan.diagnosis} para $updatingPkg (${scan.diagnosisEvidence})")
                UpdateFlowManager.setStage(ctx, UpdateFlowManager.STAGE_FINISHING)
                UpdateFlowManager.finish(ctx, diag.first, diag.second, diag.third)
                return
            }
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

        // ── 5. Probar el próximo candidato a "Actualizar" ──
        //
        // Este paso SUBIÓ de lugar el 3/9/2026: antes venía después de las dos
        // salidas por "ya estaba al día", así que en una ficha que tardaba en
        // dibujarse el flujo se iba por la salida falsa sin haber apretado nada.
        // Primero se intenta actualizar; recién si no hay nada que apretar se
        // razona sobre por qué.
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

        val hasDefiniteUpdate = scan.actions.any { it.score >= 80 }
        val hasOpenButton = scan.opens.isNotEmpty()

        // ── 6. La ficha todavía no terminó de dibujarse ──
        //
        // ⚠️ ESTA ES LA CORRECCIÓN CENTRAL DEL 3/9/2026.
        //
        // Antes, las dos salidas de "ya estaba al día" se disparaban a los 1,5 s y
        // a los 3,5 s contados desde que aparecía la VENTANA de Play Store. Pero la
        // ventana existe apenas arranca la Activity, cuando la ficha todavía está
        // en blanco. En un Snapdragon 215 con 2 GB de RAM, la ficha tarda entre 4 y
        // 8 s en tener botones: el flujo concluía "no hay ningún botón" sobre una
        // pantalla vacía y cerraba diciendo "la app ya está actualizada". El equipo
        // volvía al inicio, sin actualizar y sin ningún error. Es exactamente el
        // síntoma reportado, y explica por qué en un teléfono rápido funcionaba.
        //
        // Ahora no se saca NINGUNA conclusión sobre una pantalla que no terminó de
        // dibujarse: hacen falta contenido dibujado Y tiempo transcurrido.
        val renderedFor = if (updateSessionRenderedAt > 0L) now - updateSessionRenderedAt else 0L
        val waitedForCard = now - updateSessionTreeSeenAt
        if (!scan.rendered && waitedForCard < UPDATE_CARD_GIVEUP_MS) {
            UpdateFlowManager.setStage(ctx, UpdateFlowManager.STAGE_WAITING_STORE,
                "Esperando a Google Play...")
            return
        }

        // ── 7. Bajar la pantalla: en un equipo chico el botón puede estar abajo ──
        //
        // Android no instancia en el árbol de accesibilidad lo que no está
        // dibujado. En una pantalla de 2,8" la fila [Desinstalar] [Actualizar]
        // puede quedar debajo del borde visible, y entonces para nosotros no
        // existe. Un par de desplazamientos cortos la traen. Tope bajo a propósito:
        // seguir bajando lleva a los carruseles de "apps similares", que es el bug
        // 4 de B.9 (apretar el "Instalar" de OTRA app).
        //
        // Solo mientras NO se haya apretado nada todavía: después de un clic, la
        // fila de botones se convierte en una fila de progreso y quedaría igual de
        // "sin candidatos", pero ahí desplazar sería moverle la pantalla a una
        // descarga que ya arrancó.
        if (updateSessionCandidatesTried == 0 &&
            !hasDefiniteUpdate && !hasOpenButton && scan.rendered &&
            updateSessionScrolls < MAX_UPDATE_SCROLLS &&
            now - updateSessionLastScrollAt > SCROLL_COOLDOWN_MS
        ) {
            val scrollable = scan.scrollable
            if (scrollable != null) {
                updateSessionScrolls++
                updateSessionLastScrollAt = now
                UpdateFlowManager.setStage(ctx, UpdateFlowManager.STAGE_LOOKING_BUTTON,
                    "Buscando actualización...")
                try {
                    scrollable.performAction(AccessibilityNodeInfo.ACTION_SCROLL_FORWARD)
                    Log.i(TAG, "Desplazando la ficha ($updateSessionScrolls/$MAX_UPDATE_SCROLLS) para encontrar el botón")
                } catch (e: Exception) {
                    Log.w(TAG, "No se pudo desplazar la ficha: ${e.message}")
                }
                return
            }
        }

        // ── 8. ¿Ya estaba al día? ──
        //
        // Solo se afirma con la ficha DIBUJADA y estable. "Abrir" sin "Actualizar"
        // en una ficha completa es la señal legítima de que no hay actualización.
        if (!hasDefiniteUpdate && hasOpenButton &&
            updateSessionRenderedAt > 0L && renderedFor > UPDATE_UP_TO_DATE_GRACE_MS
        ) {
            val label = UpdateFlowManager.appLabel(ctx, updatingPkg)
            Log.i(TAG, "App $updatingPkg ya está al día (botón Abrir visible, sin Actualizar)")
            UpdateFlowManager.setStage(ctx, UpdateFlowManager.STAGE_FINISHING)
            UpdateFlowManager.finish(ctx, UpdateFlowManager.RESULT_UP_TO_DATE,
                "$label ya está actualizada.", "Ficha con 'Abrir' y sin 'Actualizar'")
            return
        }

        // ── 9. La ficha se dibujó y no hay NINGÚN botón que reconozcamos ──
        //
        // Esto ya NO se informa como "ya está actualizada". Una ficha dibujada sin
        // ningún botón no es una app al día: es una pantalla que no entendimos. Se
        // cierra con un error honesto y con las etiquetas de lo que sí se vio, que
        // es lo que permite agregar el idioma o el caso que falte sin pedir un ADB.
        // La condición `candidatesTried == 0` es esencial: si ya se apretó algo, la
        // fila de botones es ahora una fila de progreso y también se ve "sin
        // candidatos" — cerrar acá abortaría una descarga que arrancó bien. Ese
        // caso lo cubre el freno por estancamiento del paso 10, que sí espera.
        if (updateSessionCandidatesTried == 0 && scan.actions.isEmpty() && !hasOpenButton &&
            (renderedFor > UPDATE_CARD_RENDER_MS || waitedForCard > UPDATE_CARD_GIVEUP_MS)
        ) {
            Log.w(TAG, "Ficha de $updatingPkg sin botones reconocibles. Labels: ${scan.debugLabels}")
            finishUpdateWithBestReason(
                ctx, updatingPkg, scan,
                UpdateFlowManager.RESULT_NOT_AVAILABLE,
                "No se encontró el botón de actualizar de ${UpdateFlowManager.appLabel(ctx, updatingPkg)} en Google Play.",
                "Ficha dibujada (${scan.renderedNodes} nodos con texto) sin candidatos reconocibles"
            )
            return
        }

        // ── 10. Freno por estancamiento ──
        if (now - updateSessionStartTime > UPDATE_STALL_MS) {
            Log.w(TAG, "Actualización estancada para $updatingPkg. Labels: ${scan.debugLabels}")
            finishUpdateWithBestReason(
                ctx, updatingPkg, scan,
                UpdateFlowManager.RESULT_ERROR,
                "No se pudo actualizar ${UpdateFlowManager.appLabel(ctx, updatingPkg)}.",
                "Estancado ${UPDATE_STALL_MS / 1000}s tras probar $updateSessionCandidatesTried candidatos"
            )
            return
        }

        UpdateFlowManager.setStage(ctx, UpdateFlowManager.STAGE_LOOKING_BUTTON,
            "Buscando actualización...")
    }

    /**
     * Escaneo puntual de la pantalla de Play Store para adjuntar un diagnóstico a
     * un cierre. Devuelve null si la ventana ya no está: cerrar sin diagnóstico es
     * aceptable, quedarse colgado no.
     */
    private fun safeScanPlayStore(): PlayButtonFinder.Result? {
        return try {
            val root = findPlayStoreRoot() ?: return null
            val (w, h) = playStoreBounds(root)
            PlayButtonFinder.scan(root, w, h, resources.displayMetrics.density)
        } catch (e: Exception) {
            null
        }
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

    /** Que no quede el ciclo del portal cautivo girando si el servicio se va. */
    private fun stopCaptiveWatch() {
        mainHandler.removeCallbacks(captiveTickRunnable)
        captiveOpenedAt = 0L
    }

    override fun onInterrupt() {
        Log.w(TAG, "⚠️ LockSuiteAccessibilityService interrumpido")
    }

    override fun onUnbind(intent: Intent?): Boolean {
        instance = null
        val appCtx = applicationContext
        try {
            Thread({
                try {
                    Thread.sleep(300)
                    com.ejemplo.locksuite.util.AccessibilityEnforcer.reconcileNow(appCtx)
                } catch (e: Exception) {
                    Log.w(TAG, "Reconciliación al desvincular: ${e.message}")
                }
            }, "LockSuiteAccUnbind").start()
        } catch (e: Exception) {
            Log.w(TAG, "No se pudo lanzar la reconciliación de desvinculación: ${e.message}")
        }
        return super.onUnbind(intent)
    }

    override fun onDestroy() {
        super.onDestroy()
        instance = null
        stopCaptiveWatch()

        // Aviso INSTANTÁNEO de que la accesibilidad se cayó. Es la señal más confiable
        // que existe —la da el propio servicio que se está muriendo— y llega antes que
        // cualquier lectura de preferencias del sistema. Se hace en un hilo aparte
        // porque enumerar y suspender apps no puede correr en el hilo principal de un
        // servicio que se está destruyendo.
        val appCtx = applicationContext
        try {
            Thread({
                try {
                    // Una pausa corta antes de reconciliar. `onDestroy()` también se
                    // llama cuando el sistema simplemente re-vincula el servicio (por
                    // ejemplo al actualizar la app), y en ese caso vuelve solo en unos
                    // cientos de milisegundos. Sin esta pausa, cada re-vinculación
                    // suspendería todas las apps del equipo para liberarlas enseguida:
                    // otra fuente del vaivén, y de las peores. Con la pausa,
                    // `reconcileNow()` vuelve a preguntarle al sistema y, si el servicio
                    // ya volvió, no hace nada.
                    Thread.sleep(800)
                    com.ejemplo.locksuite.util.AccessibilityEnforcer.reconcileNow(appCtx)
                } catch (e: Exception) {
                    Log.w(TAG, "Reconciliación al destruir: ${e.message}")
                }
            }, "LockSuiteAccOff").start()
        } catch (e: Exception) {
            Log.w(TAG, "No se pudo lanzar la reconciliación de salida: ${e.message}")
        }
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
