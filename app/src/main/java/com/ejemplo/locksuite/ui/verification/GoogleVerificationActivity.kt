package com.ejemplo.locksuite.ui.verification

import android.annotation.SuppressLint
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.net.http.SslError
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.os.UserManager
import android.provider.Telephony
import android.util.Log
import android.view.View
import android.view.ViewGroup
import android.webkit.CookieManager
import android.webkit.PermissionRequest
import android.webkit.RenderProcessGoneDetail
import android.webkit.SslErrorHandler
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebSettings
import android.webkit.WebStorage
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.OnBackPressedCallback
import androidx.activity.compose.setContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.QrCodeScanner
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import com.ejemplo.locksuite.mdm.GoogleVerificationPolicy
import com.ejemplo.locksuite.mdm.Layer3Audit

/**
 * GoogleVerificationActivity — Microvisor Confinado de Verificaciones de Google (B.92).
 *
 * Permite a los usuarios de LockSuite completar desafíos de verificación de identidad,
 * códigos QR (IAP / devicephoneverification) y autenticación en 2 pasos de Google
 * SIN necesidad de un navegador web general y con confinamiento estricto.
 *
 * ═════════════════════════════════════════════════════════════════════════════
 * REVISIÓN DEL 22/10/2026 — lo que cambió y por qué (detalle en B.92 del contexto)
 * ═════════════════════════════════════════════════════════════════════════════
 * 1. **El SMS.** El flujo del QR termina con "Send SMS": Google arma un `sms:` con su
 *    número y el código, y el usuario lo tiene que mandar DESDE el celular. Antes se
 *    bloqueaba como navegación externa y la verificación no podía terminar nunca. Ahora
 *    se valida ([GoogleVerificationPolicy.parseSmsHandoff], solo desde una página de
 *    `/devicephoneverification`) y se abre Mensajes con el borrador; el usuario toca
 *    Enviar. No se manda nada solo.
 * 2. **El escáner propio** ([GoogleQrScannerScreen]): en Android 12+ la cámara del
 *    sistema no le pasa el link a LockSuite (va al navegador suspendido).
 * 3. **`onRenderProcessGone`.** Este WebView vive en el MISMO proceso que la VPN, el
 *    Watchdog y la Accesibilidad. Sin este método, si el sistema mata el renderizador
 *    por falta de memoria (lo normal en un equipo de 2 GB), Android mata el proceso
 *    ENTERO: se caían el filtro y el canal del panel por abrir una página de Google.
 * 4. Menú de selección: Chromium usa SUS propios ids para Copiar/Pegar (no los de
 *    `android.R.id`), así que el filtro viejo borraba también esas. Ahora se reconocen
 *    por el título que pone el propio Android en el idioma del equipo.
 * 5. `onShowFileChooser` devolvía `false` después de responder: Android responde otra
 *    vez por su cuenta (doble respuesta). Ahora devuelve `true`.
 * 6. Al cerrar se borran cookies y almacenamiento web: la sesión de Google no queda
 *    guardada adentro de LockSuite.
 * 7. Todo lo que se bloquea queda en `logcat -s LockSuiteVerif` y, resumido, en el
 *    registro de la Capa 3 que ve el panel (origen `verificacion-google`), sin query:
 *    nunca se anotan tokens ni el código del SMS.
 *
 * ⚠️ Este WebView NO pasa por la VPN de DNS (LockSuite se excluye de su propio túnel):
 * los filtros de este archivo y de [GoogleVerificationPolicy] son la única barrera.
 */
class GoogleVerificationActivity : ComponentActivity() {

    companion object {
        /** Abre directo el escáner (lo usa el widget "Verificar Google"). */
        const val EXTRA_START_SCAN = "com.ejemplo.locksuite.extra.START_QR_SCAN"

        /** URL ya conocida (desde otra pantalla de LockSuite). Se valida igual. */
        const val EXTRA_VERIFICATION_URL = "verification_url"

        private const val TAG = "LockSuiteVerif"
        private const val STATE_WEBVIEW = "verif_webview_state"
        private const val STATE_URL = "verif_url"
        private const val STATE_MODE = "verif_mode"
        private const val SAFETY_TIMEOUT_MS = 10 * 60 * 1000L
        private const val SMS_DEBOUNCE_MS = 2500L
        private const val BLOCK_TOAST_DEBOUNCE_MS = 2500L

        /**
         * Topes de renglones que una sola sesión del microvisor puede dejar en el registro
         * de la Capa 3 (que tiene 20 en total y lo comparten los nueve rebotes): una página
         * de Google pide decenas de subrecursos y no puede tapar todo lo demás.
         */
        private const val MAX_AUDIT_PER_SESSION = 8
        private const val MAX_AUDIT_RESOURCES_PER_SESSION = 3

        private const val AUDIT_ORIGIN = "verificacion-google"
    }

    enum class Mode { MENU, SCAN, WEB }

    private var activeWebView: WebView? = null
    private val mainHandler = Handler(Looper.getMainLooper())

    private val mode = mutableStateOf(Mode.MENU)
    private val webUrl = mutableStateOf<String?>(null)

    /** Estado del WebView guardado si el sistema mató el proceso (se usa una sola vez). */
    private var pendingWebViewState: Bundle? = null
    private var webViewEverCreated = false

    private var lastSmsHandoffAt = 0L
    private var lastBlockToastAt = 0L
    private var verificationCompleted = false
    private val auditLock = Any()
    private val auditedKeys = HashSet<String>()
    private var auditedResources = 0

    // Timeout de seguridad: cierra automáticamente tras 10 minutos
    private val safetyTimeoutRunnable = Runnable {
        if (!isFinishing && !isDestroyed) {
            Toast.makeText(this, "Tiempo de verificación agotado", Toast.LENGTH_SHORT).show()
            finish()
        }
    }

    private fun resetSafetyTimeout() {
        mainHandler.removeCallbacks(safetyTimeoutRunnable)
        mainHandler.postDelayed(safetyTimeoutRunnable, SAFETY_TIMEOUT_MS)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Programar timeout inicial de 10 min
        resetSafetyTimeout()

        // 1) Lo que trae el Intent (QR por la cámara del sistema, otra pantalla de LockSuite).
        // 2) Si el sistema mató el proceso con una verificación abierta, se retoma esa.
        val fromIntent = extractVerificationUrl(intent)
        val savedUrl = savedInstanceState?.getString(STATE_URL)
            ?.takeIf { GoogleVerificationPolicy.isNavigationAllowed(it) }
        val savedMode = savedInstanceState?.getString(STATE_MODE)
            ?.let { name -> Mode.entries.firstOrNull { it.name == name } }
        val savedWebState = savedInstanceState?.getBundle(STATE_WEBVIEW)

        when {
            savedMode == Mode.WEB && savedUrl != null -> {
                pendingWebViewState = savedWebState
                webUrl.value = savedUrl
                mode.value = Mode.WEB
            }
            fromIntent != null -> {
                webUrl.value = fromIntent
                mode.value = Mode.WEB
            }
            savedMode == Mode.SCAN || intent?.getBooleanExtra(EXTRA_START_SCAN, false) == true -> {
                mode.value = Mode.SCAN
            }
            else -> mode.value = Mode.MENU
        }

        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                when (mode.value) {
                    Mode.WEB -> {
                        val wv = activeWebView
                        if (wv != null && wv.canGoBack()) wv.goBack() else mode.value = Mode.MENU
                    }
                    Mode.SCAN -> mode.value = Mode.MENU
                    Mode.MENU -> finish()
                }
            }
        })

        setContent {
            MaterialTheme(
                colorScheme = darkColorScheme(
                    primary = Color(0xFFFF9100),
                    background = Color(0xFF121212),
                    surface = Color(0xFF1E1E1E),
                    onPrimary = Color.Black,
                    onBackground = Color.White,
                    onSurface = Color.White
                )
            ) {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background
                ) {
                    val currentMode by mode
                    val currentUrl by webUrl
                    GoogleVerificationScreen(
                        mode = currentMode,
                        url = currentUrl,
                        onClose = { finish() },
                        onStartScan = { mode.value = Mode.SCAN },
                        onBackToMenu = { mode.value = Mode.MENU },
                        onUrlReady = { url -> openVerification(url) },
                        webViewHost = webViewHost
                    )
                }
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        resetSafetyTimeout() // Reiniciar temporizador ante nuevo desafío (P2-04)
        val newUrl = extractVerificationUrl(intent)
        when {
            newUrl != null -> openVerification(newUrl)
            intent.getBooleanExtra(EXTRA_START_SCAN, false) -> mode.value = Mode.SCAN
        }
    }

    /**
     * Único punto de entrada a la página. Antes, si el microvisor estaba en la pantalla
     * de inicio (sin WebView) y llegaba un QR nuevo por `onNewIntent`, no pasaba nada:
     * se intentaba cargar en un WebView que no existía.
     */
    private fun openVerification(url: String) {
        if (!GoogleVerificationPolicy.isNavigationAllowed(url)) return
        resetSafetyTimeout()
        val wv = activeWebView
        if (mode.value == Mode.WEB && wv != null) {
            webUrl.value = url
            wv.loadUrl(url)
        } else {
            webUrl.value = url
            mode.value = Mode.WEB
        }
    }

    private fun extractVerificationUrl(intent: Intent?): String? {
        if (intent == null) return null
        val dataString = intent.dataString
        if (!dataString.isNullOrBlank() && GoogleVerificationPolicy.isNavigationAllowed(dataString)) {
            return dataString
        }
        val extraUrl = intent.getStringExtra(EXTRA_VERIFICATION_URL)
        if (!extraUrl.isNullOrBlank() && GoogleVerificationPolicy.isNavigationAllowed(extraUrl)) {
            return extraUrl
        }
        return null
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putString(STATE_MODE, mode.value.name)
        val wv = activeWebView
        val url = (wv?.url ?: webUrl.value)?.takeIf { GoogleVerificationPolicy.isNavigationAllowed(it) }
        if (url != null) outState.putString(STATE_URL, url)
        if (wv != null) {
            try {
                val b = Bundle()
                if (wv.saveState(b) != null) outState.putBundle(STATE_WEBVIEW, b)
            } catch (_: Exception) {
            }
        }
    }

    override fun onDestroy() {
        mainHandler.removeCallbacks(safetyTimeoutRunnable)
        activeWebView?.let { destroyWebView(it, removeFromParent = true) }
        activeWebView = null
        if (isFinishing && webViewEverCreated) {
            // La sesión de Google no queda guardada dentro de LockSuite.
            try {
                val cookies = CookieManager.getInstance()
                cookies.removeAllCookies { cookies.flush() }
                WebStorage.getInstance().deleteAllData()
            } catch (_: Exception) {
            }
        }
        super.onDestroy()
    }

    /**
     * Idempotente: lo pueden llamar Compose (`onRelease`, al salir de la pantalla) y
     * `onDestroy`, en cualquier orden. Desde Compose no se saca el WebView de su
     * contenedor: ese contenedor es de Compose y lo desarma él.
     */
    private fun destroyWebView(wv: WebView, removeFromParent: Boolean = false) {
        if (wv.tag == WEBVIEW_DESTROYED) return
        wv.tag = WEBVIEW_DESTROYED
        try {
            wv.stopLoading()
            wv.clearHistory()
            wv.clearCache(true)
        } catch (_: Exception) {
        }
        if (removeFromParent) {
            try {
                (wv.parent as? ViewGroup)?.removeView(wv)
            } catch (_: Exception) {
            }
        }
        try {
            wv.destroy()
        } catch (_: Exception) {
        }
    }

    // ── Lo que el WebView le pide a la Activity ───────────────────────────────

    private val webViewHost = object : VerificationWebViewHost {
        override fun takeSavedState(): Bundle? = pendingWebViewState.also { pendingWebViewState = null }

        override fun onWebViewCreated(webView: WebView) {
            activeWebView = webView
            webViewEverCreated = true
        }

        override fun onWebViewReleased(webView: WebView) {
            if (activeWebView === webView) activeWebView = null
            destroyWebView(webView)
        }

        override fun onVerificationComplete() {
            // Lo pueden avisar shouldOverrideUrlLoading y onPageFinished de la misma página.
            if (verificationCompleted) return
            verificationCompleted = true
            audit("verificación terminada", blocked = false)
            Toast.makeText(this@GoogleVerificationActivity, "✅ Verificación de Google completada", Toast.LENGTH_LONG).show()
            mainHandler.postDelayed({ finish() }, 2000L)
        }

        override fun onNavigationBlocked(url: String?, where: String) {
            logBlocked(url, where)
            if (Looper.myLooper() == Looper.getMainLooper()) {
                val now = SystemClock.elapsedRealtime()
                if (now - lastBlockToastAt > BLOCK_TOAST_DEBOUNCE_MS) {
                    lastBlockToastAt = now
                    Toast.makeText(this@GoogleVerificationActivity, "🚫 Navegación externa bloqueada por seguridad", Toast.LENGTH_SHORT).show()
                }
            }
        }

        override fun onResourceBlocked(url: String?, mainFrame: Boolean) {
            // Corre en un hilo del WebView: solo logcat y el registro (que es seguro entre hilos).
            if (mainFrame) {
                logBlocked(url, "página (antes de cargar)")
                return
            }
            val shown = GoogleVerificationPolicy.describeForLog(url)
            Log.i(TAG, "Subrecurso bloqueado: $shown")
            audit("subrecurso bloqueado: $shown", blocked = true, resource = true)
        }

        override fun onUnauthorizedPageStarted(url: String?) {
            logBlocked(url, "página")
            Toast.makeText(this@GoogleVerificationActivity, "🚫 Navegación no autorizada detenida", Toast.LENGTH_SHORT).show()
            finish()
        }

        override fun onSmsRequested(sms: GoogleVerificationPolicy.SmsHandoff) {
            handOffSms(sms)
        }

        override fun onInvalidSmsRequest(url: String?, reason: String) {
            // La forma (no el contenido): si Google cambia el formato, acá se ve cuál usó.
            val shape = GoogleVerificationPolicy.describeSmsShapeForLog(url)
            Log.w(TAG, "Pedido de SMS rechazado ($reason): $shape")
            audit("SMS rechazado: $reason · $shape", blocked = true)
            Toast.makeText(this@GoogleVerificationActivity, "🚫 Se ignoró un pedido de SMS que no es de la verificación de Google", Toast.LENGTH_LONG).show()
        }

        override fun onRendererGone(crashed: Boolean) {
            Log.w(TAG, "El renderizador del WebView se cerró (crash=$crashed)")
            audit(if (crashed) "el renderizador se cerró por un error" else "el sistema cerró el renderizador por falta de memoria", blocked = false)
            Toast.makeText(
                this@GoogleVerificationActivity,
                "La página de Google se cerró por falta de memoria. Volvé a escanear el código.",
                Toast.LENGTH_LONG
            ).show()
            // Sacar el WebView de la pantalla lo destruye (onRelease). No se lo vuelve a usar.
            activeWebView = null
            mode.value = Mode.MENU
        }

        override fun onCloseRequested() {
            finish()
        }
    }

    /**
     * Abre Mensajes con el SMS que armó Google. No se manda nada solo: el usuario ve el
     * número y el texto, y toca Enviar. Tampoco hace falta pedir SEND_SMS (que además es
     * un permiso que Play restringe).
     */
    private fun handOffSms(sms: GoogleVerificationPolicy.SmsHandoff) {
        val now = SystemClock.elapsedRealtime()
        if (now - lastSmsHandoffAt < SMS_DEBOUNCE_MS) return
        lastSmsHandoffAt = now

        val um = getSystemService(Context.USER_SERVICE) as? UserManager
        if (um?.hasUserRestriction(UserManager.DISALLOW_SMS) == true) {
            Log.w(TAG, "SMS de verificación pedido con DISALLOW_SMS puesto")
            audit("SMS pedido pero los SMS están bloqueados (DISALLOW_SMS)", blocked = true)
            Toast.makeText(
                this,
                "Los SMS están bloqueados en este celular. Para terminar la verificación, " +
                        "pedile al administrador que los habilite un momento.",
                Toast.LENGTH_LONG
            ).show()
            return
        }

        val intent = Intent(Intent.ACTION_SENDTO, Uri.fromParts("smsto", sms.recipient, null)).apply {
            putExtra("sms_body", sms.body)
            val smsApp = try {
                Telephony.Sms.getDefaultSmsPackage(this@GoogleVerificationActivity)
            } catch (_: Exception) {
                null
            }
            if (!smsApp.isNullOrBlank()) setPackage(smsApp)
        }

        try {
            startActivity(intent)
            Log.i(TAG, "SMS de verificación entregado a Mensajes (${sms.body.length} caracteres)")
            audit("SMS de verificación abierto en Mensajes", blocked = false)
            Toast.makeText(
                this,
                "Se abrió Mensajes con el SMS de Google ya escrito. Envialo sin cambiar nada y volvé a la computadora.",
                Toast.LENGTH_LONG
            ).show()
        } catch (e: ActivityNotFoundException) {
            audit("SMS pedido pero no hay app de Mensajes", blocked = true)
            Toast.makeText(this, "No hay una app de Mensajes para enviar el SMS de verificación.", Toast.LENGTH_LONG).show()
        } catch (e: Exception) {
            Log.w(TAG, "No se pudo abrir Mensajes: ${e.message}")
            Toast.makeText(this, "No se pudo abrir Mensajes para enviar el SMS de verificación.", Toast.LENGTH_LONG).show()
        }
    }

    private fun logBlocked(url: String?, where: String) {
        val shown = GoogleVerificationPolicy.describeForLog(url)
        Log.w(TAG, "Bloqueado ($where): $shown")
        audit("$where bloqueada: $shown", blocked = true)
    }

    /**
     * Al registro de la Capa 3 (lo ve el panel), con tope por sesión para no taparlo. Las
     * repeticiones exactas no cuentan: `Layer3Audit` ya las agrupa.
     */
    private fun audit(motivo: String, blocked: Boolean, resource: Boolean = false) {
        synchronized(auditLock) {
            if (motivo !in auditedKeys) {
                if (auditedKeys.size >= MAX_AUDIT_PER_SESSION) return
                if (resource) {
                    if (auditedResources >= MAX_AUDIT_RESOURCES_PER_SESSION) return
                    auditedResources++
                }
                auditedKeys.add(motivo)
            }
        }
        Layer3Audit.anotar(applicationContext, AUDIT_ORIGIN, packageName, "microvisor", motivo, blocked)
    }
}

private const val WEBVIEW_DESTROYED = "locksuite_verif_destroyed"

/** Lo que el WebView necesita de la Activity. Una interfaz para no pasarle diez lambdas. */
interface VerificationWebViewHost {
    fun takeSavedState(): Bundle?
    fun onWebViewCreated(webView: WebView)
    fun onWebViewReleased(webView: WebView)
    fun onVerificationComplete()
    fun onNavigationBlocked(url: String?, where: String)
    fun onResourceBlocked(url: String?, mainFrame: Boolean)
    fun onUnauthorizedPageStarted(url: String?)
    fun onSmsRequested(sms: GoogleVerificationPolicy.SmsHandoff)
    fun onInvalidSmsRequest(url: String?, reason: String)
    fun onRendererGone(crashed: Boolean)
    fun onCloseRequested()
}

@Composable
fun GoogleVerificationScreen(
    mode: GoogleVerificationActivity.Mode,
    url: String?,
    onClose: () -> Unit,
    onStartScan: () -> Unit,
    onBackToMenu: () -> Unit,
    onUrlReady: (String) -> Unit,
    webViewHost: VerificationWebViewHost
) {
    var progress by remember { mutableFloatStateOf(0f) }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(Color(0xFF121212))
    ) {
        // ── TOP BAR NATIVO CON INDICADOR DE ENTORNO SEGURO ──
        Surface(
            color = Color(0xFF1A1A1A),
            tonalElevation = 4.dp,
            modifier = Modifier.fillMaxWidth()
        ) {
            Column {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 12.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    IconButton(onClick = onClose) {
                        Icon(
                            imageVector = Icons.Default.Close,
                            contentDescription = "Cerrar",
                            tint = Color.White
                        )
                    }

                    Spacer(modifier = Modifier.width(8.dp))

                    Column(modifier = Modifier.weight(1f)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(
                                imageVector = Icons.Default.Lock,
                                contentDescription = null,
                                tint = Color(0xFF00E676),
                                modifier = Modifier.size(16.dp)
                            )
                            Spacer(modifier = Modifier.width(6.dp))
                            Text(
                                text = "Verificación Segura",
                                color = Color.White,
                                fontSize = 16.sp,
                                fontWeight = FontWeight.Bold
                            )
                        }
                        Text(
                            text = "accounts.google.com (Confinado)",
                            color = Color(0xFF9E9E9E),
                            fontSize = 12.sp
                        )
                    }

                    Box(
                        modifier = Modifier
                            .background(Color(0xFF263238), RoundedCornerShape(4.dp))
                            .padding(horizontal = 8.dp, vertical = 4.dp)
                    ) {
                        Text(
                            text = "KOSHER",
                            color = Color(0xFFFFB300),
                            fontSize = 11.sp,
                            fontWeight = FontWeight.Bold
                        )
                    }
                }

                // Barra de progreso de carga web
                if (mode == GoogleVerificationActivity.Mode.WEB && progress in 0.01f..0.99f) {
                    LinearProgressIndicator(
                        progress = { progress },
                        modifier = Modifier.fillMaxWidth(),
                        color = Color(0xFFFF9100),
                        trackColor = Color(0xFF2C2C2C)
                    )
                }
            }
        }

        // ── CONTENIDO: WEBVIEW, ESCÁNER O PANTALLA DE INICIO ──
        when {
            mode == GoogleVerificationActivity.Mode.WEB && url != null -> GoogleVerificationWebView(
                url = url,
                onProgressChanged = { progress = it },
                host = webViewHost
            )

            mode == GoogleVerificationActivity.Mode.SCAN -> GoogleQrScannerScreen(
                onVerificationUrl = onUrlReady,
                onCancel = onBackToMenu
            )

            else -> VerificationStartPanel(
                onStartScan = onStartScan,
                onUrlReady = onUrlReady
            )
        }
    }
}

@Composable
private fun VerificationStartPanel(
    onStartScan: () -> Unit,
    onUrlReady: (String) -> Unit
) {
    var inputUrl by remember { mutableStateOf("") }
    var errorMessage by remember { mutableStateOf<String?>(null) }
    var showManual by remember { mutableStateOf(false) }
    val focusManager = LocalFocusManager.current
    val scanFocus = remember { FocusRequester() }

    // En los equipos de teclas no hay dónde tocar: el botón de escanear arranca con foco.
    LaunchedEffect(Unit) {
        try {
            scanFocus.requestFocus()
        } catch (_: Exception) {
        }
    }

    val submitManual: () -> Unit = {
        focusManager.clearFocus()
        val trimmed = inputUrl.trim()
        if (GoogleVerificationPolicy.isNavigationAllowed(trimmed)) {
            onUrlReady(trimmed)
        } else {
            errorMessage = "Enlace no válido. Debe pertenecer a accounts.google.com/devicephoneverification o desafío de inicio de sesión."
        }
    }

    // Con scroll: en el CAT S22 Flip (≈320×426 dp) la tarjeta no entra entera.
    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(20.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Card(
            colors = CardDefaults.cardColors(containerColor = Color(0xFF1E1E1E)),
            shape = RoundedCornerShape(16.dp),
            modifier = Modifier.fillMaxWidth()
        ) {
            Column(
                modifier = Modifier.padding(20.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Icon(
                    imageVector = Icons.Default.QrCodeScanner,
                    contentDescription = null,
                    tint = Color(0xFFFF9100),
                    modifier = Modifier.size(48.dp)
                )

                Spacer(modifier = Modifier.height(12.dp))

                Text(
                    text = "Verificación de Google",
                    color = Color.White,
                    fontSize = 20.sp,
                    fontWeight = FontWeight.Bold
                )

                Spacer(modifier = Modifier.height(8.dp))

                Text(
                    text = "Cuando la computadora muestre «Verificá tu información para continuar» " +
                            "con un código QR, escanealo con este botón (no con la cámara del celular).",
                    color = Color(0xFFB0B0B0),
                    fontSize = 14.sp,
                    lineHeight = 20.sp
                )

                Spacer(modifier = Modifier.height(16.dp))

                Button(
                    onClick = onStartScan,
                    modifier = Modifier
                        .fillMaxWidth()
                        .focusRequester(scanFocus),
                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFFF9100)),
                    shape = RoundedCornerShape(8.dp)
                ) {
                    Icon(
                        imageVector = Icons.Default.QrCodeScanner,
                        contentDescription = null,
                        tint = Color.Black,
                        modifier = Modifier.size(20.dp)
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = "Escanear código QR",
                        color = Color.Black,
                        fontWeight = FontWeight.Bold
                    )
                }

                Spacer(modifier = Modifier.height(12.dp))

                Text(
                    text = "Después Google te va a pedir mandar un SMS desde este celular: se abre " +
                            "Mensajes con el texto ya escrito; tocá Enviar sin cambiar nada. " +
                            "Puede tener el costo de un SMS común.",
                    color = Color(0xFF9E9E9E),
                    fontSize = 12.sp,
                    lineHeight = 17.sp
                )

                Spacer(modifier = Modifier.height(8.dp))

                TextButton(onClick = { showManual = !showManual }) {
                    Text(
                        text = if (showManual) "Ocultar enlace manual" else "¿No anda la cámara? Pegar el enlace",
                        color = Color(0xFFFFB74D),
                        fontSize = 13.sp
                    )
                }

                AnimatedVisibility(visible = showManual) {
                    Column {
                        OutlinedTextField(
                            value = inputUrl,
                            onValueChange = {
                                inputUrl = it
                                errorMessage = null
                            },
                            label = { Text("Enlace de verificación de Google") },
                            placeholder = { Text("https://accounts.google.com/...") },
                            singleLine = true,
                            modifier = Modifier.fillMaxWidth(),
                            keyboardOptions = KeyboardOptions(
                                keyboardType = KeyboardType.Uri,
                                imeAction = ImeAction.Go
                            ),
                            keyboardActions = KeyboardActions(onGo = { submitManual() }),
                            colors = OutlinedTextFieldDefaults.colors(
                                focusedBorderColor = Color(0xFFFF9100),
                                unfocusedBorderColor = Color(0xFF424242),
                                focusedLabelColor = Color(0xFFFF9100),
                                cursorColor = Color(0xFFFF9100)
                            )
                        )

                        if (errorMessage != null) {
                            Spacer(modifier = Modifier.height(8.dp))
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Icon(
                                    imageVector = Icons.Default.Warning,
                                    contentDescription = null,
                                    tint = Color(0xFFFF5252),
                                    modifier = Modifier.size(16.dp)
                                )
                                Spacer(modifier = Modifier.width(6.dp))
                                Text(
                                    text = errorMessage ?: "",
                                    color = Color(0xFFFF5252),
                                    fontSize = 12.sp
                                )
                            }
                        }

                        Spacer(modifier = Modifier.height(12.dp))

                        Button(
                            onClick = submitManual,
                            modifier = Modifier.fillMaxWidth(),
                            colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF424242)),
                            shape = RoundedCornerShape(8.dp)
                        ) {
                            Text(
                                text = "Abrir enlace",
                                color = Color.White,
                                fontWeight = FontWeight.Bold
                            )
                        }
                    }
                }
            }
        }
    }
}

// IDs estándar del sistema Android para acciones de edición en el portapapeles
private val ALLOWED_EDIT_ACTION_IDS = setOf(
    android.R.id.copy,
    android.R.id.paste,
    android.R.id.cut,
    android.R.id.selectAll
)

/**
 * Títulos de Cortar/Copiar/Pegar/Seleccionar todo en el idioma del equipo, tal como
 * los define Android. Chromium arma su menú con SUS propios ids
 * (`select_action_menu_copy`…) pero con ESTOS títulos, así que comparar el título es
 * lo único que reconoce sus ítems en cualquier idioma sin mantener una lista de palabras.
 */
private fun editActionTitles(context: Context): Set<String> {
    val out = HashSet<String>()
    for (res in intArrayOf(android.R.string.copy, android.R.string.paste, android.R.string.cut, android.R.string.selectAll)) {
        try {
            out.add(context.getString(res).trim())
        } catch (_: Exception) {
        }
    }
    return out
}

/**
 * Un ítem del menú de selección sobrevive solo si es de edición del portapapeles. Los que
 * traen un `Intent` (Traducir, Buscar, "Abrir con…", las acciones inteligentes de
 * `textAssist`) abren OTRA app, que es exactamente lo que no puede pasar acá.
 */
private fun isAllowedEditItem(item: android.view.MenuItem, allowedTitles: Set<String>): Boolean {
    if (item.intent != null) return false
    if (item.itemId == android.R.id.textAssist) return false
    if (item.itemId in ALLOWED_EDIT_ACTION_IDS) return true
    val title = item.title?.toString()?.trim() ?: return false
    return title in allowedTitles
}

@SuppressLint("SetJavaScriptEnabled")
@Composable
fun GoogleVerificationWebView(
    url: String,
    onProgressChanged: (Float) -> Unit,
    host: VerificationWebViewHost
) {
    AndroidView(
        modifier = Modifier.fillMaxSize(),
        factory = { context ->
            val editTitles = editActionTitles(context)

            val webView = object : WebView(context) {
                override fun startActionMode(callback: android.view.ActionMode.Callback?): android.view.ActionMode? {
                    return wrapActionModeCallback(callback)?.let { super.startActionMode(it) }
                }

                override fun startActionMode(callback: android.view.ActionMode.Callback?, type: Int): android.view.ActionMode? {
                    return wrapActionModeCallback(callback)?.let { super.startActionMode(it, type) }
                }

                /**
                 * Intercepta el menú de selección de texto y conserva ÚNICAMENTE
                 * las opciones del portapapeles (Copiar/Pegar/Cortar/Seleccionar todo),
                 * eliminando de raíz "Buscar en la Web", "Compartir", "Traducir", etc.
                 * de forma independiente del idioma (P2-02; corregido el 22/10).
                 */
                private fun wrapActionModeCallback(callback: android.view.ActionMode.Callback?): android.view.ActionMode.Callback? {
                    if (callback == null) return null
                    // Callback2 (no Callback a secas): así la barra flotante sigue apareciendo
                    // al lado del texto elegido y no arriba de todo (onGetContentRect).
                    return object : android.view.ActionMode.Callback2() {
                        override fun onGetContentRect(mode: android.view.ActionMode?, view: View?, outRect: android.graphics.Rect?) {
                            if (callback is android.view.ActionMode.Callback2) {
                                callback.onGetContentRect(mode, view, outRect)
                            } else {
                                super.onGetContentRect(mode, view, outRect)
                            }
                        }

                        override fun onCreateActionMode(mode: android.view.ActionMode?, menu: android.view.Menu?): Boolean {
                            val res = callback.onCreateActionMode(mode, menu)
                            menu?.let { filterMenu(it) }
                            return res
                        }

                        override fun onPrepareActionMode(mode: android.view.ActionMode?, menu: android.view.Menu?): Boolean {
                            val res = callback.onPrepareActionMode(mode, menu)
                            menu?.let { filterMenu(it) }
                            return res
                        }

                        override fun onActionItemClicked(mode: android.view.ActionMode?, item: android.view.MenuItem?): Boolean {
                            if (item == null || !isAllowedEditItem(item, editTitles)) {
                                return true // Consumir sin ejecutar búsqueda externa
                            }
                            return callback.onActionItemClicked(mode, item)
                        }

                        override fun onDestroyActionMode(mode: android.view.ActionMode?) {
                            callback.onDestroyActionMode(mode)
                        }
                    }
                }

                private fun filterMenu(m: android.view.Menu) {
                    for (i in m.size() - 1 downTo 0) {
                        if (i >= m.size()) continue
                        val item = m.getItem(i)
                        if (!isAllowedEditItem(item, editTitles)) {
                            // Se oculta en vez de borrar por id: Chromium puede repetir ids
                            // y `removeItem` se lleva el PRIMERO con ese id, no este.
                            item.isVisible = false
                            item.isEnabled = false
                        }
                    }
                }
            }

            webView.apply {
                layoutParams = ViewGroup.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.MATCH_PARENT
                )

                // Autocompletar de Google: su menú tiene "Administrar contraseñas", que
                // abre el Administrador de contraseñas de Google. Acá no hace falta.
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    importantForAutofill = View.IMPORTANT_FOR_AUTOFILL_NO_EXCLUDE_DESCENDANTS
                }

                // ── CONFIGURACIÓN DE SEGURIDAD DEL WEBVIEW ──
                settings.apply {
                    javaScriptEnabled = true
                    domStorageEnabled = true
                    databaseEnabled = false
                    setSupportMultipleWindows(false)
                    javaScriptCanOpenWindowsAutomatically = false
                    allowFileAccess = false
                    allowContentAccess = false
                    allowFileAccessFromFileURLs = false
                    allowUniversalAccessFromFileURLs = false
                    setGeolocationEnabled(false)
                    // Chrome para Android con la versión REAL del WebView del equipo (antes
                    // fija en Chrome/120 y con una marca propia). Ver chromeLikeUserAgent.
                    userAgentString = GoogleVerificationPolicy.chromeLikeUserAgent(
                        try {
                            WebSettings.getDefaultUserAgent(context)
                        } catch (_: Exception) {
                            null
                        }
                    )
                    cacheMode = WebSettings.LOAD_DEFAULT
                    mixedContentMode = WebSettings.MIXED_CONTENT_NEVER_ALLOW
                }

                webViewClient = object : WebViewClient() {
                    override fun shouldOverrideUrlLoading(view: WebView?, request: WebResourceRequest?): Boolean {
                        val req = request ?: return true
                        val reqUrl = req.url?.toString() ?: return true
                        val scheme = req.url?.scheme?.lowercase(java.util.Locale.ROOT)

                        // 0. El SMS de la verificación por QR: no es navegación, es un pedido
                        //    aparte que solo se acepta desde /devicephoneverification. Puede
                        //    venir como `sms:`/`smsto:` o como un `intent:` de SMS; cualquier
                        //    otro `intent:` sigue bloqueado más abajo, como siempre.
                        val intentSms = if (scheme == "intent") GoogleVerificationPolicy.parseSmsHandoff(reqUrl) else null
                        if (scheme == "sms" || scheme == "smsto" || intentSms != null) {
                            if (!GoogleVerificationPolicy.isSmsHandoffContext(view?.url)) {
                                host.onInvalidSmsRequest(reqUrl, "fuera de la verificación por QR")
                                return true
                            }
                            val sms = intentSms ?: GoogleVerificationPolicy.parseSmsHandoff(reqUrl)
                            if (sms == null) {
                                host.onInvalidSmsRequest(reqUrl, "formato no válido")
                            } else {
                                host.onSmsRequested(sms)
                            }
                            return true
                        }

                        // 1. OBLIGATORIO: Validar herméticamente antes de cualquier otra cosa (P1-02)
                        if (!GoogleVerificationPolicy.isNavigationAllowed(reqUrl)) {
                            host.onNavigationBlocked(reqUrl, if (req.isForMainFrame) "navegación" else "marco")
                            return true
                        }

                        // 2. Solo si la navegación es válida, verificar si completó con éxito
                        if (req.isForMainFrame && GoogleVerificationPolicy.isVerificationSuccess(reqUrl)) {
                            host.onVerificationComplete()
                            return false // Permitir renderizar la pantalla de éxito en accounts.google.com
                        }

                        // 3. Permitir dentro del WebView
                        return false
                    }

                    override fun shouldInterceptRequest(view: WebView?, request: WebResourceRequest?): WebResourceResponse? {
                        val reqUrl = request?.url?.toString() ?: return null

                        // Defensa en profundidad (P1-04): si es el frame principal
                        if (request.isForMainFrame) {
                            if (!GoogleVerificationPolicy.isNavigationAllowed(reqUrl)) {
                                host.onResourceBlocked(reqUrl, mainFrame = true)
                                return forbidden()
                            }
                        } else {
                            // Subrecursos (scripts, css, imágenes, XHR, marcos)
                            if (!GoogleVerificationPolicy.isResourceAllowed(reqUrl)) {
                                host.onResourceBlocked(reqUrl, mainFrame = false)
                                return forbidden()
                            }
                        }

                        return super.shouldInterceptRequest(view, request)
                    }

                    override fun onReceivedSslError(view: WebView?, handler: SslErrorHandler?, error: SslError?) {
                        // NUNCA ignorar errores SSL
                        handler?.cancel()
                        Toast.makeText(context, "Error de seguridad SSL al conectar con Google", Toast.LENGTH_SHORT).show()
                    }

                    override fun onPageStarted(view: WebView?, startingUrl: String?, favicon: android.graphics.Bitmap?) {
                        super.onPageStarted(view, startingUrl, favicon)
                        // Guardia contra redirecciones POST o internas no capturadas por shouldOverrideUrlLoading (P1-04)
                        if (startingUrl != null && !GoogleVerificationPolicy.isNavigationAllowed(startingUrl)) {
                            view?.stopLoading()
                            host.onUnauthorizedPageStarted(startingUrl)
                        }
                    }

                    override fun doUpdateVisitedHistory(view: WebView?, visitedUrl: String?, isReload: Boolean) {
                        super.doUpdateVisitedHistory(view, visitedUrl, isReload)
                        if (visitedUrl != null && !GoogleVerificationPolicy.isNavigationAllowed(visitedUrl)) {
                            view?.stopLoading()
                            host.onUnauthorizedPageStarted(visitedUrl)
                        }
                    }

                    override fun onPageFinished(view: WebView?, finishedUrl: String?) {
                        super.onPageFinished(view, finishedUrl)
                        if (GoogleVerificationPolicy.isVerificationSuccess(finishedUrl)) {
                            host.onVerificationComplete()
                        }
                    }

                    /**
                     * El renderizador murió (crash o el sistema lo mató por memoria). Si no se
                     * devuelve `true`, Android mata el proceso de LockSuite ENTERO — que es el
                     * mismo de la VPN y de la Accesibilidad. Desde API 26.
                     */
                    override fun onRenderProcessGone(view: WebView?, detail: RenderProcessGoneDetail?): Boolean {
                        val crashed = try {
                            detail?.didCrash() == true
                        } catch (_: Exception) {
                            false
                        }
                        host.onRendererGone(crashed)
                        return true
                    }
                }

                webChromeClient = object : WebChromeClient() {
                    override fun onProgressChanged(view: WebView?, newProgress: Int) {
                        onProgressChanged(newProgress / 100f)
                    }

                    override fun onCreateWindow(view: WebView?, isDialog: Boolean, isUserGesture: Boolean, resultMsg: android.os.Message?): Boolean {
                        // Bloquea estrictamente popups y window.open
                        return false
                    }

                    override fun onCloseWindow(window: WebView?) {
                        // La página de Google pidió cerrarse (window.close()).
                        host.onCloseRequested()
                    }

                    override fun onGeolocationPermissionsShowPrompt(origin: String?, callback: android.webkit.GeolocationPermissions.Callback?) {
                        callback?.invoke(origin, false, false)
                    }

                    override fun onPermissionRequest(request: PermissionRequest?) {
                        // Cámara/micrófono para la página: nunca. (LockSuite ahora tiene
                        // permiso de cámara por el escáner; la página no lo hereda.)
                        try {
                            request?.deny()
                        } catch (_: Exception) {
                        }
                    }

                    override fun onShowFileChooser(
                        webView: WebView?,
                        filePathCallback: android.webkit.ValueCallback<Array<Uri>>?,
                        fileChooserParams: FileChooserParams?
                    ): Boolean {
                        // Cancelar Y devolver true: con `false` Android responde otra vez por
                        // su cuenta y la página recibe dos respuestas.
                        filePathCallback?.onReceiveValue(null)
                        return true
                    }
                }

                host.onWebViewCreated(this)

                val saved = host.takeSavedState()
                val restored = if (saved != null) {
                    try {
                        restoreState(saved) != null
                    } catch (_: Exception) {
                        false
                    }
                } else {
                    false
                }
                if (!restored) loadUrl(url)
            }
        },
        onRelease = { wv -> host.onWebViewReleased(wv) }
    )
}

private fun forbidden(): WebResourceResponse =
    WebResourceResponse("text/plain", "UTF-8", 403, "Forbidden", emptyMap(), null)
