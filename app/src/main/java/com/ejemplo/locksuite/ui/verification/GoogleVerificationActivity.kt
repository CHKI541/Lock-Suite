package com.ejemplo.locksuite.ui.verification

import android.annotation.SuppressLint
import android.app.Activity
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.net.http.SslError
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.ViewGroup
import android.webkit.SslErrorHandler
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.OnBackPressedCallback
import androidx.activity.compose.setContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Lock
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
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import com.ejemplo.locksuite.mdm.GoogleVerificationPolicy

/**
 * GoogleVerificationActivity — Microvisor Confinado de Verificaciones de Google (B.92).
 *
 * Permite a los usuarios de LockSuite completar desafíos de verificación de identidad,
 * códigos QR (IAP / devicephoneverification) y autenticación en 2 pasos de Google
 * SIN necesidad de un navegador web general y con confinamiento estricto.
 */
class GoogleVerificationActivity : ComponentActivity() {

    private var activeWebView: WebView? = null
    private val mainHandler = Handler(Looper.getMainLooper())

    // Timeout de seguridad: cierra automáticamente tras 10 minutos
    private val safetyTimeoutRunnable = Runnable {
        if (!isFinishing && !isDestroyed) {
            Toast.makeText(this, "Tiempo de verificación agotado", Toast.LENGTH_SHORT).show()
            finish()
        }
    }

    private fun resetSafetyTimeout() {
        mainHandler.removeCallbacks(safetyTimeoutRunnable)
        mainHandler.postDelayed(safetyTimeoutRunnable, 10 * 60 * 1000L)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Programar timeout inicial de 10 min
        resetSafetyTimeout()

        // Extraer URL inicial del Intent si vino por escaneo de QR / Intent.ACTION_VIEW
        val initialUrl = extractVerificationUrl(intent)

        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                val wv = activeWebView
                if (wv != null && wv.canGoBack()) {
                    wv.goBack()
                } else {
                    finish()
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
                    GoogleVerificationScreen(
                        initialUrl = initialUrl,
                        onClose = { finish() },
                        onRegisterWebView = { activeWebView = it },
                        onResetTimeout = { resetSafetyTimeout() },
                        onVerificationComplete = {
                            Toast.makeText(this, "✅ Verificación de Google completada", Toast.LENGTH_LONG).show()
                            mainHandler.postDelayed({ finish() }, 2000L)
                        }
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
        if (newUrl != null && GoogleVerificationPolicy.isNavigationAllowed(newUrl)) {
            activeWebView?.loadUrl(newUrl)
        }
    }

    private fun extractVerificationUrl(intent: Intent?): String? {
        if (intent == null) return null
        val dataString = intent.dataString
        if (!dataString.isNullOrBlank() && GoogleVerificationPolicy.isNavigationAllowed(dataString)) {
            return dataString
        }
        val extraUrl = intent.getStringExtra("verification_url")
        if (!extraUrl.isNullOrBlank() && GoogleVerificationPolicy.isNavigationAllowed(extraUrl)) {
            return extraUrl
        }
        return null
    }

    override fun onDestroy() {
        super.onDestroy()
        mainHandler.removeCallbacks(safetyTimeoutRunnable)
        activeWebView?.let { wv ->
            wv.stopLoading()
            wv.clearHistory()
            wv.clearCache(true)
            (wv.parent as? ViewGroup)?.removeView(wv)
            wv.destroy()
        }
        activeWebView = null
    }
}

@Composable
fun GoogleVerificationScreen(
    initialUrl: String?,
    onClose: () -> Unit,
    onRegisterWebView: (WebView) -> Unit,
    onResetTimeout: () -> Unit,
    onVerificationComplete: () -> Unit
) {
    var currentUrl by remember { mutableStateOf(initialUrl) }
    var inputUrl by remember { mutableStateOf("") }
    var errorMessage by remember { mutableStateOf<String?>(null) }
    var progress by remember { mutableFloatStateOf(0f) }
    val focusManager = LocalFocusManager.current

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
                if (progress in 0.01f..0.99f) {
                    LinearProgressIndicator(
                        progress = { progress },
                        modifier = Modifier.fillMaxWidth(),
                        color = Color(0xFFFF9100),
                        trackColor = Color(0xFF2C2C2C)
                    )
                }
            }
        }

        // ── CONTENIDO PRINCIPAL: WEBVIEW O ENTRADA MANUAL ──
        if (currentUrl != null) {
            GoogleVerificationWebView(
                url = currentUrl!!,
                onProgressChanged = { progress = it },
                onRegisterWebView = onRegisterWebView,
                onVerificationComplete = onVerificationComplete
            )
        } else {
            // Pantalla de entrada manual o espera de QR
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(20.dp),
                contentAlignment = Alignment.Center
            ) {
                Card(
                    colors = CardDefaults.cardColors(containerColor = Color(0xFF1E1E1E)),
                    shape = RoundedCornerShape(16.dp),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(
                        modifier = Modifier.padding(24.dp),
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        Icon(
                            imageVector = Icons.Default.Lock,
                            contentDescription = null,
                            tint = Color(0xFFFF9100),
                            modifier = Modifier.size(48.dp)
                        )

                        Spacer(modifier = Modifier.height(16.dp))

                        Text(
                            text = "Verificación de Google",
                            color = Color.White,
                            fontSize = 20.sp,
                            fontWeight = FontWeight.Bold
                        )

                        Spacer(modifier = Modifier.height(8.dp))

                        Text(
                            text = "Escanea el código QR desde la cámara del celular o pega aquí el enlace de verificación proporcionado por Google.",
                            color = Color(0xFFB0B0B0),
                            fontSize = 14.sp,
                            lineHeight = 20.sp
                        )

                        Spacer(modifier = Modifier.height(20.dp))

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
                            keyboardActions = KeyboardActions(
                                onGo = {
                                    focusManager.clearFocus()
                                    val trimmed = inputUrl.trim()
                                    if (GoogleVerificationPolicy.isNavigationAllowed(trimmed)) {
                                        currentUrl = trimmed
                                        onResetTimeout()
                                    } else {
                                        errorMessage = "Enlace no válido o no autorizado."
                                    }
                                }
                            ),
                            colors = OutlinedTextFieldDefaults.colors(
                                focusedBorderColor = Color(0xFFFF9100),
                                unfocusedBorderColor = Color(0xFF424242),
                                focusedLabelColor = Color(0xFFFF9100),
                                cursorColor = Color(0xFFFF9100)
                            )
                        )

                        AnimatedVisibility(visible = errorMessage != null) {
                            Column {
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
                        }

                        Spacer(modifier = Modifier.height(20.dp))

                        Button(
                            onClick = {
                                focusManager.clearFocus()
                                val trimmed = inputUrl.trim()
                                if (GoogleVerificationPolicy.isNavigationAllowed(trimmed)) {
                                    currentUrl = trimmed
                                    onResetTimeout()
                                } else {
                                    errorMessage = "Enlace no válido. Debe pertenecer a accounts.google.com/devicephoneverification o desafío de inicio de sesión."
                                }
                            },
                            modifier = Modifier.fillMaxWidth(),
                            colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFFF9100)),
                            shape = RoundedCornerShape(8.dp)
                        ) {
                            Text(
                                text = "Iniciar Verificación",
                                color = Color.Black,
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

@SuppressLint("SetJavaScriptEnabled")
@Composable
fun GoogleVerificationWebView(
    url: String,
    onProgressChanged: (Float) -> Unit,
    onRegisterWebView: (WebView) -> Unit,
    onVerificationComplete: () -> Unit
) {
    AndroidView(
        modifier = Modifier.fillMaxSize(),
        factory = { context ->
            val webView = object : WebView(context) {
                override fun startActionMode(callback: android.view.ActionMode.Callback?): android.view.ActionMode? {
                    return wrapActionModeCallback(callback)?.let { super.startActionMode(it) }
                }

                override fun startActionMode(callback: android.view.ActionMode.Callback?, type: Int): android.view.ActionMode? {
                    return wrapActionModeCallback(callback)?.let { super.startActionMode(it, type) }
                }

                /**
                 * Intercepta el menú de selección de texto y conserva ÚNICAMENTE
                 * las opciones estándar del portapapeles (Copiar/Pegar) por su identificador
                 * numérico del sistema (R.id.copy, R.id.paste), eliminando de raíz
                 * "Buscar en la Web", "Compartir", etc. de forma independiente del idioma (P2-02).
                 */
                private fun wrapActionModeCallback(callback: android.view.ActionMode.Callback?): android.view.ActionMode.Callback? {
                    if (callback == null) return null
                    return object : android.view.ActionMode.Callback {
                        override fun onCreateActionMode(mode: android.view.ActionMode?, menu: android.view.Menu?): Boolean {
                            return callback.onCreateActionMode(mode, menu)
                        }

                        override fun onPrepareActionMode(mode: android.view.ActionMode?, menu: android.view.Menu?): Boolean {
                            val res = callback.onPrepareActionMode(mode, menu)
                            menu?.let { m ->
                                for (i in m.size() - 1 downTo 0) {
                                    val item = m.getItem(i)
                                    if (item.itemId !in ALLOWED_EDIT_ACTION_IDS) {
                                        m.removeItem(item.itemId)
                                    }
                                }
                            }
                            return res
                        }

                        override fun onActionItemClicked(mode: android.view.ActionMode?, item: android.view.MenuItem?): Boolean {
                            if (item?.itemId !in ALLOWED_EDIT_ACTION_IDS) {
                                return true // Consumir sin ejecutar búsqueda externa
                            }
                            return callback.onActionItemClicked(mode, item)
                        }

                        override fun onDestroyActionMode(mode: android.view.ActionMode?) {
                            callback.onDestroyActionMode(mode)
                        }
                    }
                }
            }

            webView.apply {
                layoutParams = ViewGroup.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.MATCH_PARENT
                )

                // ── CONFIGURACIÓN DE SEGURIDAD DEL WEBVIEW ──
                settings.apply {
                    javaScriptEnabled = true
                    domStorageEnabled = true
                    databaseEnabled = false
                    setSupportMultipleWindows(false)
                    allowFileAccess = false
                    allowContentAccess = false
                    allowFileAccessFromFileURLs = false
                    allowUniversalAccessFromFileURLs = false
                    // Mobile Chrome estándar para evitar error 403 / disallowed_useragent de Google
                    userAgentString = "Mozilla/5.0 (Linux; Android 13; Mobile) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Mobile Safari/537.36 LockSuiteVerification"
                    cacheMode = WebSettings.LOAD_DEFAULT
                    mixedContentMode = WebSettings.MIXED_CONTENT_NEVER_ALLOW
                }

                webViewClient = object : WebViewClient() {
                    override fun shouldOverrideUrlLoading(view: WebView?, request: WebResourceRequest?): Boolean {
                        val reqUrl = request?.url?.toString() ?: return true

                        // 1. OBLIGATORIO: Validar herméticamente antes de cualquier otra cosa (P1-02)
                        if (!GoogleVerificationPolicy.isNavigationAllowed(reqUrl)) {
                            Toast.makeText(context, "🚫 Navegación externa bloqueada por seguridad", Toast.LENGTH_SHORT).show()
                            return true
                        }

                        // 2. Solo si la navegación es válida, verificar si completó con éxito
                        if (GoogleVerificationPolicy.isVerificationSuccess(reqUrl)) {
                            onVerificationComplete()
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
                                return WebResourceResponse("text/plain", "UTF-8", 403, "Forbidden", emptyMap(), null)
                            }
                        } else {
                            // Subrecursos (scripts, css, imágenes)
                            if (!GoogleVerificationPolicy.isResourceAllowed(reqUrl)) {
                                return WebResourceResponse("text/plain", "UTF-8", 403, "Forbidden", emptyMap(), null)
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
                            Toast.makeText(context, "🚫 Navegación no autorizada detenida", Toast.LENGTH_SHORT).show()
                            (context as? Activity)?.finish()
                        }
                    }

                    override fun doUpdateVisitedHistory(view: WebView?, visitedUrl: String?, isReload: Boolean) {
                        super.doUpdateVisitedHistory(view, visitedUrl, isReload)
                        if (visitedUrl != null && !GoogleVerificationPolicy.isNavigationAllowed(visitedUrl)) {
                            view?.stopLoading()
                            (context as? Activity)?.finish()
                        }
                    }

                    override fun onPageFinished(view: WebView?, finishedUrl: String?) {
                        super.onPageFinished(view, finishedUrl)
                        if (GoogleVerificationPolicy.isVerificationSuccess(finishedUrl)) {
                            onVerificationComplete()
                        }
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

                    override fun onGeolocationPermissionsShowPrompt(origin: String?, callback: android.webkit.GeolocationPermissions.Callback?) {
                        callback?.invoke(origin, false, false)
                    }

                    override fun onShowFileChooser(
                        webView: WebView?,
                        filePathCallback: android.webkit.ValueCallback<Array<Uri>>?,
                        fileChooserParams: FileChooserParams?
                    ): Boolean {
                        filePathCallback?.onReceiveValue(null)
                        return false
                    }
                }

                onRegisterWebView(this)
                loadUrl(url)
            }
        }
    )
}
