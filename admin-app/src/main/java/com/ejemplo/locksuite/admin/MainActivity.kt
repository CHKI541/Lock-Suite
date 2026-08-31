package com.ejemplo.locksuite.admin

import android.annotation.SuppressLint
import android.content.ActivityNotFoundException
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Color
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.Uri
import android.net.http.SslError
import android.os.Build
import android.os.Bundle
import android.os.Message
import android.util.Log
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.webkit.CookieManager
import android.webkit.GeolocationPermissions
import android.webkit.PermissionRequest
import android.webkit.RenderProcessGoneDetail
import android.webkit.SslErrorHandler
import android.webkit.ValueCallback
import android.webkit.WebChromeClient
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Button
import android.widget.ProgressBar
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.swiperefreshlayout.widget.SwipeRefreshLayout
import java.io.ByteArrayInputStream

/**
 * Cáscara nativa del panel web de LockSuite, pensada para administrar desde un celular
 * que NO tiene navegador (celular kosher).
 *
 * ─────────────────────────────────────────────────────────────────────────────
 * REGLA DE ORO DE ESTE ARCHIVO — leer antes de tocar nada
 * ─────────────────────────────────────────────────────────────────────────────
 * Un WebView SIN restricciones es un navegador completo, solo que sin barra de
 * direcciones. Con un solo enlace externo (o un redirect, o un iframe) el equipo deja
 * de ser kosher. Por eso la navegación NO es "permitir todo menos lo prohibido" sino
 * al revés: **lista blanca cerrada**, y todo lo que no esté explícitamente listado se
 * bloquea.
 *
 * Hay DOS listas blancas y son distintas a propósito:
 *
 *   1. [NAV_ALLOWED_HOSTS]      — a qué páginas puede NAVEGAR el WebView (lo que el
 *                                 usuario "ve" como estar en un sitio). Muy corta.
 *   2. [RESOURCE_ALLOWED_*]     — de dónde puede bajar subrecursos (JS, XHR, imágenes,
 *                                 fuentes). Más larga, pero cargar una imagen de un
 *                                 dominio NO es poder navegar a ese dominio.
 *
 * Un host que está solo en la lista 2 no se puede visitar: si la página intenta
 * navegar ahí, [WebViewClient.shouldOverrideUrlLoading] lo corta igual.
 *
 * Todo lo que se bloquea queda logueado con el tag [TAG]. Si algo del panel deja de
 * funcionar (típicamente el login con Google, que toca varios dominios de Google),
 * el diagnóstico es de diez segundos:
 *
 *     adb logcat -s LockSuiteAdmin
 *
 * y el host que aparezca en "BLOQUEADO" se agrega a la lista que corresponda.
 * NO desactivar el filtro entero para "probar si era eso".
 */
class MainActivity : AppCompatActivity() {

    companion object {
        private const val TAG = "LockSuiteAdmin"

        private const val PANEL_HOST = "locksuite-nueva.web.app"
        private const val ADMIN_PANEL_URL = "https://$PANEL_HOST"

        /**
         * Hosts a los que se permite NAVEGAR. Coincidencia EXACTA, nunca por sufijo:
         * un sufijo tipo ".google.com" abriría media web (support.google.com,
         * news.google.com, translate.google.com…), que es exactamente lo que hay que
         * evitar.
         *
         * - locksuite-nueva.web.app / .firebaseapp.com : el panel (Firebase Hosting
         *   sirve el mismo contenido en los dos dominios; el segundo además hospeda
         *   el "auth handler" del login de Firebase).
         * - accounts.google.com : necesario para "Iniciar sesión con Google".
         *   Sus enlaces de pie de página (Ayuda / Privacidad / Condiciones) apuntan a
         *   support.google.com y policies.google.com, que NO están en esta lista, así
         *   que quedan bloqueados y desde ahí no se sale a la web abierta.
         */
        private val NAV_ALLOWED_HOSTS = setOf(
            "locksuite-nueva.web.app",
            "locksuite-nueva.firebaseapp.com",
            "accounts.google.com"
        )

        /** Subrecursos: hosts exactos. */
        private val RESOURCE_ALLOWED_HOSTS = setOf(
            "www.gstatic.com",                                  // SDK de Firebase
            "apis.google.com",                                  // iframe de Firebase Auth
            "sendcommandv8-687828714595.us-central1.run.app",   // Cloud Function sendCommandV8
            "upload.wikimedia.org"                              // íconos de apps del panel
        )

        /**
         * Subrecursos: sufijos. Cada uno cubre un conjunto de endpoints de Google/Firebase
         * que cambian de nombre según la región o el servicio, y ninguno es un sitio
         * navegable (además la navegación va por [NAV_ALLOWED_HOSTS], no por acá).
         */
        private val RESOURCE_ALLOWED_SUFFIXES = listOf(
            ".googleapis.com",        // identitytoolkit, securetoken, firebaseinstallations
            ".firebaseio.com",        // Realtime Database (también el WebSocket)
            ".firebasedatabase.app",
            ".cloudfunctions.net",
            ".gstatic.com",           // ssl.gstatic.com / fonts.gstatic.com (login de Google)
            ".googleusercontent.com"  // foto de perfil en el selector de cuenta
        )

        /**
         * Alto (en dp) de la franja superior desde la que se permite arrancar el
         * gesto de "deslizar para recargar". Ver [setupSwipeRefresh].
         */
        private const val PULL_TO_REFRESH_ZONE_DP = 64
    }

    private lateinit var webView: WebView
    private lateinit var swipeRefresh: SwipeRefreshLayout
    private lateinit var progressBar: ProgressBar
    /** Contenedor de la pantalla "sin conexión" (un ScrollView, ver activity_main.xml). */
    private lateinit var layoutOffline: View
    private lateinit var btnRetry: Button

    private var backPressedTime: Long = 0
    private var hasError: Boolean = false

    /** El WebView ya fue destruido (onDestroy, o el renderer se murió). No tocarlo más. */
    private var webViewDestroyed: Boolean = false

    /** Y del ACTION_DOWN del último toque sobre el WebView, en píxeles. */
    private var lastTouchDownY: Float = Float.MAX_VALUE

    private val pullZonePx: Int by lazy {
        (PULL_TO_REFRESH_ZONE_DP * resources.displayMetrics.density).toInt()
    }

    /** Callback pendiente de <input type="file"> (importar preset). */
    private var pendingFileCallback: ValueCallback<Array<Uri>>? = null

    private val filePickerLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        val callback = pendingFileCallback
        pendingFileCallback = null
        callback?.onReceiveValue(
            WebChromeClient.FileChooserParams.parseResult(result.resultCode, result.data)
        )
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Ciclo de vida
    // ─────────────────────────────────────────────────────────────────────────

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // El WebView de Android vive en la app "Android System WebView", que se puede
        // desactivar, estar actualizándose, o —en un equipo administrado por el propio
        // LockSuite— quedar suspendida. En cualquiera de esos casos inflar el layout
        // lanza y la app se cierra sola sin decir por qué. Preferimos un cartel.
        try {
            setContentView(R.layout.activity_main)
        } catch (e: Exception) {
            Log.e(TAG, "No se pudo inflar el layout (¿Android System WebView desactivado?)", e)
            Toast.makeText(this, R.string.webview_missing, Toast.LENGTH_LONG).show()
            finish()
            return
        }

        webView = findViewById(R.id.webView)
        swipeRefresh = findViewById(R.id.swipeRefreshLayout)
        progressBar = findViewById(R.id.progressBar)
        layoutOffline = findViewById(R.id.layoutOffline)
        btnRetry = findViewById(R.id.btnRetry)

        setupWebView()
        setupSwipeRefresh()
        setupBackHandler()

        btnRetry.setOnClickListener {
            layoutOffline.visibility = View.GONE
            webView.visibility = View.VISIBLE
            hasError = false
            loadPanel()
        }

        // restoreState() devuelve null cuando el Bundle guardado se perdió o quedó
        // truncado (Android tiene un tope de ~1 MB por Bundle y el historial de un
        // WebView lo puede pasar). Sin este chequeo, al rotar la pantalla o al volver
        // después de que el sistema mate el proceso, la app quedaba en NEGRO PARA
        // SIEMPRE porque nadie llamaba a loadUrl().
        val restored = savedInstanceState?.let { webView.restoreState(it) }
        if (restored == null) {
            loadPanel()
        }
    }

    override fun onResume() {
        super.onResume()
        if (!webViewDestroyed) webView.onResume()
    }

    override fun onPause() {
        if (!webViewDestroyed) {
            webView.onPause()
            // La sesión de Firebase Auth viaja en cookies + almacenamiento local.
            // flush() las baja a disco ya: si el sistema mata el proceso sin avisar
            // (habitual en equipos de poca RAM), sin esto habría que volver a loguearse.
            CookieManager.getInstance().flush()
        }
        super.onPause()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        if (!webViewDestroyed) {
            webView.saveState(outState)
        }
    }

    override fun onDestroy() {
        destroyWebView()
        super.onDestroy()
    }

    private fun destroyWebView() {
        if (webViewDestroyed || !this::webView.isInitialized) return
        webViewDestroyed = true
        pendingFileCallback?.onReceiveValue(null)
        pendingFileCallback = null
        webView.stopLoading()
        // Sacarlo del árbol ANTES de destruirlo: destruir un WebView que todavía está
        // adjunto a una ventana deja el compositor con una capa colgada.
        (webView.parent as? ViewGroup)?.removeView(webView)
        webView.destroy()
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Deslizar para recargar
    // ─────────────────────────────────────────────────────────────────────────

    @SuppressLint("ClickableViewAccessibility")
    private fun setupSwipeRefresh() {
        swipeRefresh.setColorSchemeColors(
            Color.parseColor("#F1C40F"), // Accent gold
            Color.parseColor("#2C5282")  // Navy light
        )
        swipeRefresh.setProgressBackgroundColorSchemeColor(Color.parseColor("#1E3E62"))

        swipeRefresh.setOnRefreshListener {
            hasError = false
            webView.reload()
        }

        webView.setOnTouchListener { _, event ->
            if (event.actionMasked == MotionEvent.ACTION_DOWN) lastTouchDownY = event.y
            false // no consumimos: el WebView sigue recibiendo el toque normalmente
        }

        // ⚠️ ESTE BLOQUE ARREGLA UN BUG REAL, NO ES DECORACIÓN.
        //
        // El panel dibuja el detalle del dispositivo dentro de .sidebar-content, que es
        // un contenedor con `overflow-y: auto` DENTRO de una barra `position: fixed`.
        // En celular esa barra ocupa el 100% del ancho, o sea que casi todo lo que uno
        // manipula está dentro de un scroll propio de CSS… y ese scroll NO mueve
        // webView.scrollY, que se queda clavado en 0.
        //
        // La comprobación anterior era `swipeRefresh.isEnabled = webView.scrollY == 0`:
        // con el panel de un dispositivo abierto daba SIEMPRE true, así que cualquier
        // arrastre hacia abajo para subir en la lista disparaba una recarga completa de
        // la página. Es decir: el gesto principal de la app rompía la app.
        //
        // Solución sin depender de JavaScript ni de puentes: el gesto de recargar solo
        // se arma si el dedo BAJÓ dentro de los primeros PULL_TO_REFRESH_ZONE_DP dp de
        // la pantalla — que en el panel es la cabecera de la barra lateral, un área que
        // no tiene scroll propio. Adentro del contenido, deslizar es siempre deslizar.
        //
        // La firma es "canChildScrollUp": devolver true significa "el hijo todavía puede
        // desplazarse hacia arriba", o sea NO dispares el refresh.
        swipeRefresh.setOnChildScrollUpCallback { _, _ ->
            webViewDestroyed || webView.scrollY > 0 || lastTouchDownY > pullZonePx
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // WebView
    // ─────────────────────────────────────────────────────────────────────────

    @SuppressLint("SetJavaScriptEnabled")
    @Suppress("DEPRECATION")
    private fun setupWebView() {
        val settings = webView.settings

        settings.javaScriptEnabled = true      // el panel es una SPA, sin esto no hay nada
        settings.domStorageEnabled = true      // Firebase Auth guarda la sesión acá
        settings.databaseEnabled = true

        // Ajuste visual para pantallas chicas
        settings.useWideViewPort = true
        settings.loadWithOverviewMode = true
        settings.setSupportZoom(false)
        settings.builtInZoomControls = false
        settings.displayZoomControls = false
        settings.textZoom = 100

        settings.cacheMode = WebSettings.LOAD_DEFAULT

        // ── Endurecimiento ───────────────────────────────────────────────────
        // Nada de contenido http:// dentro de una página https://.
        settings.mixedContentMode = WebSettings.MIXED_CONTENT_NEVER_ALLOW
        // El panel es 100% remoto: la app nunca necesita leer archivos locales ni
        // proveedores de contenido. Cerrar esto elimina toda la familia de ataques
        // "file:// dentro del WebView".
        settings.allowFileAccess = false
        settings.allowContentAccess = false
        settings.allowFileAccessFromFileURLs = false
        settings.allowUniversalAccessFromFileURLs = false
        // Nada de ventanas nuevas: window.open() y target="_blank" quedan resueltos
        // DENTRO del mismo WebView, o sea que pasan por shouldOverrideUrlLoading y por
        // la lista blanca. Si se permitieran ventanas múltiples, una ventana nueva
        // podría abrirse sin pasar por ese filtro.
        settings.setSupportMultipleWindows(false)
        settings.javaScriptCanOpenWindowsAutomatically = false
        // Ubicación: el panel no la usa y no tiene por qué pedirla.
        settings.setGeolocationEnabled(false)
        // Nada de audio/video que arranque solo.
        settings.mediaPlaybackRequiresUserGesture = true
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            settings.safeBrowsingEnabled = true
        }

        // El User-Agent por defecto de un WebView contiene "; wv". Google rechaza el
        // login OAuth con "403 disallowed_useragent" cuando lo ve, así que se saca.
        // Se agrega además un token propio para que el panel pueda saber que está
        // corriendo dentro de la app (lo usa para elegir login por redirección en vez
        // de por popup — ver app.js). NO cambiar el nombre del token sin cambiarlo
        // también allá.
        val defaultUa = settings.userAgentString ?: ""
        settings.userAgentString = defaultUa.replace("; wv", "") + " LockSuiteAdminApp"

        val cookieManager = CookieManager.getInstance()
        cookieManager.setAcceptCookie(true)
        // Necesario para el login con Google: el handler de Firebase Auth vive en
        // firebaseapp.com y el panel en web.app, o sea que son terceros entre sí.
        cookieManager.setAcceptThirdPartyCookies(webView, true)

        webView.setBackgroundColor(Color.parseColor("#0B192C"))
        webView.isVerticalScrollBarEnabled = true
        webView.isHorizontalScrollBarEnabled = false

        webView.webChromeClient = PanelChromeClient()
        webView.webViewClient = PanelWebViewClient()

        // Descargas: bloqueadas a propósito. En un celular sin navegador, poder bajar
        // archivos arbitrarios es una vía de escape (y un .apk bajado a mano saltea el
        // control de la Tienda administrada). Los presets se exportan desde la PC.
        webView.setDownloadListener { url, _, _, mimeType, _ ->
            Log.w(TAG, "BLOQUEADO (descarga): mime=$mimeType url=${shortUrl(url)}")
            toast(getString(R.string.blocked_download))
        }
    }

    private inner class PanelChromeClient : WebChromeClient() {

        override fun onProgressChanged(view: WebView?, newProgress: Int) {
            if (newProgress < 100) {
                if (layoutOffline.visibility != View.VISIBLE) {
                    progressBar.visibility = View.VISIBLE
                    progressBar.progress = newProgress
                }
            } else {
                progressBar.visibility = View.GONE
                swipeRefresh.isRefreshing = false
            }
        }

        /**
         * Con setSupportMultipleWindows(false) esto no debería llamarse nunca, pero si
         * una versión de WebView decide llamarlo igual, la respuesta es "no".
         */
        override fun onCreateWindow(
            view: WebView?,
            isDialog: Boolean,
            isUserGesture: Boolean,
            resultMsg: Message?
        ): Boolean {
            Log.w(TAG, "BLOQUEADO: intento de abrir una ventana nueva")
            return false
        }

        override fun onGeolocationPermissionsShowPrompt(
            origin: String?,
            callback: GeolocationPermissions.Callback?
        ) {
            callback?.invoke(origin, false, false)
        }

        /** Cámara y micrófono: el panel no los usa. Denegar sin preguntar. */
        override fun onPermissionRequest(request: PermissionRequest?) {
            Log.w(TAG, "BLOQUEADO (permiso web): ${request?.resources?.joinToString()}")
            request?.deny()
        }

        /**
         * <input type="file"> del panel (importar preset .locksuite). Abre el selector
         * de documentos del sistema, que no es un navegador: no rompe el confinamiento.
         */
        override fun onShowFileChooser(
            webView: WebView?,
            filePathCallback: ValueCallback<Array<Uri>>?,
            fileChooserParams: FileChooserParams?
        ): Boolean {
            if (!isNavigationAllowed(webView?.url)) {
                filePathCallback?.onReceiveValue(null)
                return true
            }
            // Si había uno pendiente, cerrarlo: dejar un ValueCallback sin responder
            // deja el <input> del panel colgado para siempre.
            pendingFileCallback?.onReceiveValue(null)
            pendingFileCallback = filePathCallback

            val intent = fileChooserParams?.createIntent()
            if (intent == null) {
                pendingFileCallback = null
                filePathCallback?.onReceiveValue(null)
                return true
            }
            return try {
                filePickerLauncher.launch(intent)
                true
            } catch (e: ActivityNotFoundException) {
                Log.w(TAG, "No hay app para elegir archivos", e)
                pendingFileCallback = null
                filePathCallback?.onReceiveValue(null)
                toast(getString(R.string.no_file_picker))
                true
            }
        }
    }

    private inner class PanelWebViewClient : WebViewClient() {

        override fun onPageStarted(view: WebView?, url: String?, favicon: Bitmap?) {
            super.onPageStarted(view, url, favicon)
            hasError = false
            progressBar.visibility = View.VISIBLE
        }

        override fun onPageFinished(view: WebView?, url: String?) {
            super.onPageFinished(view, url)
            progressBar.visibility = View.GONE
            swipeRefresh.isRefreshing = false
            if (!hasError) {
                layoutOffline.visibility = View.GONE
                webView.visibility = View.VISIBLE
            }
        }

        // ── El filtro kosher ─────────────────────────────────────────────────

        override fun shouldOverrideUrlLoading(view: WebView?, request: WebResourceRequest?): Boolean {
            val url = request?.url?.toString()
            if (isNavigationAllowed(url)) return false
            Log.w(TAG, "BLOQUEADO (navegación): ${shortUrl(url)}")
            // Solo avisamos si el usuario intentó ir a algún lado a propósito; un iframe
            // bloqueado no merece un cartel.
            if (request?.isForMainFrame == true) toast(getString(R.string.blocked_navigation))
            return true
        }

        /**
         * Variante vieja de la API. Con minSdk 24 el sistema usa siempre la de arriba,
         * pero algunos WebView de fabricante llaman a esta. Cuesta cuatro líneas y
         * cierra la duda.
         */
        @Suppress("DEPRECATION", "OVERRIDE_DEPRECATION")
        override fun shouldOverrideUrlLoading(view: WebView?, url: String?): Boolean {
            if (isNavigationAllowed(url)) return false
            Log.w(TAG, "BLOQUEADO (navegación, API vieja): ${shortUrl(url)}")
            toast(getString(R.string.blocked_navigation))
            return true
        }

        /**
         * Segunda barrera. shouldOverrideUrlLoading solo ve navegaciones; esto ve TODA
         * petición (scripts, XHR, fetch, imágenes, iframes). Sin esto, un script del
         * panel comprometido podría filtrar datos a un tercero aunque no se pueda
         * "navegar" ahí.
         *
         * OJO: corre en un hilo secundario. Nada de tocar vistas acá.
         */
        override fun shouldInterceptRequest(
            view: WebView?,
            request: WebResourceRequest?
        ): WebResourceResponse? {
            val url = request?.url?.toString() ?: return null
            if (isResourceAllowed(url)) return null // null = que siga el camino normal
            Log.w(TAG, "BLOQUEADO (recurso): ${shortUrl(url)}")
            return WebResourceResponse(
                "text/plain",
                "utf-8",
                403,
                "Blocked by LockSuite Admin",
                emptyMap(),
                ByteArrayInputStream(ByteArray(0))
            )
        }

        // ── Errores ──────────────────────────────────────────────────────────

        override fun onReceivedError(
            view: WebView?,
            request: WebResourceRequest?,
            error: WebResourceError?
        ) {
            super.onReceivedError(view, request, error)
            if (request?.isForMainFrame == true) {
                hasError = true
                showOfflineScreen()
            }
        }

        override fun onReceivedHttpError(
            view: WebView?,
            request: WebResourceRequest?,
            errorResponse: WebResourceResponse?
        ) {
            super.onReceivedHttpError(view, request, errorResponse)
            // Un 500 del hosting dejaba una pantalla en blanco sin explicación.
            if (request?.isForMainFrame == true) {
                Log.w(TAG, "HTTP ${errorResponse?.statusCode} en ${shortUrl(request.url?.toString())}")
                hasError = true
                showOfflineScreen()
            }
        }

        /**
         * NUNCA llamar a handler.proceed(). Un certificado inválido en el panel de
         * administración es, por definición, alguien en el medio: el panel manda
         * comandos de MDM y tokens de sesión.
         */
        override fun onReceivedSslError(view: WebView?, handler: SslErrorHandler?, error: SslError?) {
            Log.e(TAG, "BLOQUEADO (TLS): error=${error?.primaryError} url=${shortUrl(error?.url)}")
            handler?.cancel()
            hasError = true
            toast(getString(R.string.blocked_ssl))
            showOfflineScreen()
        }

        /**
         * El proceso del renderizador se murió (típico por falta de memoria en equipos
         * económicos, justo el perfil del "celular kosher"). Si nadie devuelve true acá,
         * Android se lleva puesto el proceso de la app entera y el usuario ve un cierre
         * inesperado sin explicación.
         */
        override fun onRenderProcessGone(view: WebView?, detail: RenderProcessGoneDetail?): Boolean {
            Log.e(TAG, "El proceso del WebView murió; recreando la pantalla")
            if (isFinishing || isDestroyed) return true
            destroyWebView()
            toast(getString(R.string.webview_recovered))
            window.decorView.post { if (!isFinishing && !isDestroyed) recreate() }
            return true
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Lista blanca
    // ─────────────────────────────────────────────────────────────────────────

    /** ¿Puede el WebView NAVEGAR a esta URL? */
    private fun isNavigationAllowed(url: String?): Boolean {
        if (url == null) return false
        val uri = try { Uri.parse(url) } catch (e: Exception) { return false }
        val scheme = uri.scheme?.lowercase()

        // about:blank lo usa el propio WebView al arrancar y al limpiar.
        if (scheme == "about") return true

        // Cualquier esquema que no sea https queda afuera. Eso incluye, a propósito:
        //   http     → sin cifrar
        //   intent:  → salta a OTRA app (el vector clásico para abrir Chrome)
        //   market:  → Play Store
        //   tel:/sms:/mailto: → el panel no los necesita
        //   file:/content:/blob:/data:/javascript: → carga local o inyección
        if (scheme != "https") return false

        val host = uri.host?.lowercase() ?: return false
        return host in NAV_ALLOWED_HOSTS
    }

    /** ¿Puede la página bajar este subrecurso? */
    private fun isResourceAllowed(url: String?): Boolean {
        if (url == null) return false
        val uri = try { Uri.parse(url) } catch (e: Exception) { return false }
        when (uri.scheme?.lowercase()) {
            "https", "wss" -> Unit
            // Los genera la propia página en memoria, no salen a la red.
            "data", "blob", "about" -> return true
            else -> return false
        }
        val host = uri.host?.lowercase() ?: return false
        if (host in NAV_ALLOWED_HOSTS || host in RESOURCE_ALLOWED_HOSTS) return true
        return RESOURCE_ALLOWED_SUFFIXES.any { host.endsWith(it) }
    }

    /** Para el log: host + ruta, sin query (ahí viajan tokens de sesión). */
    private fun shortUrl(url: String?): String {
        if (url == null) return "(null)"
        return try {
            val uri = Uri.parse(url)
            "${uri.scheme}://${uri.host}${uri.path ?: ""}"
        } catch (e: Exception) {
            "(url no parseable)"
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Carga / estado
    // ─────────────────────────────────────────────────────────────────────────

    private fun loadPanel() {
        if (!isNetworkAvailable()) {
            showOfflineScreen()
            return
        }
        hasError = false
        layoutOffline.visibility = View.GONE
        webView.visibility = View.VISIBLE
        webView.loadUrl(ADMIN_PANEL_URL)
    }

    private fun showOfflineScreen() {
        progressBar.visibility = View.GONE
        swipeRefresh.isRefreshing = false
        webView.visibility = View.GONE
        layoutOffline.visibility = View.VISIBLE
    }

    private fun isNetworkAvailable(): Boolean {
        val cm = getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager ?: return false
        val network = cm.activeNetwork ?: return false
        val capabilities = cm.getNetworkCapabilities(network) ?: return false
        return capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
    }

    private fun toast(message: String) {
        Toast.makeText(this, message, Toast.LENGTH_SHORT).show()
    }

    private fun setupBackHandler() {
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                if (layoutOffline.visibility == View.VISIBLE) {
                    finish()
                    return
                }
                if (!webViewDestroyed && webView.canGoBack()) {
                    webView.goBack()
                    return
                }
                val currentTime = System.currentTimeMillis()
                if (currentTime - backPressedTime < 2000) {
                    finish()
                } else {
                    backPressedTime = currentTime
                    toast(getString(R.string.press_back_again))
                }
            }
        })
    }
}
