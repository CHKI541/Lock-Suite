package com.ejemplo.locksuite.service

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.AccessibilityServiceInfo
import android.accessibilityservice.AccessibilityService.TakeScreenshotCallback
import android.accessibilityservice.AccessibilityService.ScreenshotResult
import android.content.Context
import android.content.Intent
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
import com.ejemplo.locksuite.mdm.WebViewBlockManager
import com.ejemplo.locksuite.mdm.ImageBlockManager
import com.ejemplo.locksuite.util.PrefsHelper
import java.util.concurrent.Executors

class LockSuiteAccessibilityService : AccessibilityService() {

    companion object {
        private const val TAG = "LockSuite_WV"
        private const val SETTINGS_PKG = "com.android.settings"
        private const val LOCKSUITE_PKG = "com.ejemplo.locksuite"
        private const val DEBOUNCE_MS = 300L
        private const val PKG_WHATSAPP = "com.whatsapp"
        private const val PKG_WHATSAPP_BUSINESS = "com.whatsapp.w4b"

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
        private val MP_OFFERS_KEYWORDS = listOf(
            "oferta", "ofertas", "promocion", "promociones", "descuento", "descuentos",
            "cupon", "cupones", "beneficio", "beneficios", "mercado puntos", "recompensa",
            "novedades y ofertas", "supermercado"
        )
    }

    private val mainHandler = Handler(Looper.getMainLooper())
    private var lastCheckedAt = 0L

    private val whatsappScanRunnable = object : Runnable {
        override fun run() {
            val policyManager = com.ejemplo.locksuite.mdm.PolicyManager(applicationContext)
            val blockStatus = policyManager.isWhatsAppBlockStatusEnabled()
            val blockChannels = policyManager.isWhatsAppBlockChannelsEnabled()
            if (blockStatus || blockChannels) {
                scanForUpdatesTab(blockStatus, blockChannels)
            }
        }
    }

    // Stack de paquetes activos: [0] = el más reciente que NO es browser/webview-provider
    private val appPackageStack = ArrayDeque<String>(3)

    @Volatile private var blockInProgress = false

    // Componentes del Bloqueador de Imágenes
    private lateinit var overlayManager: BlockOverlayManager
    private lateinit var aiGate: AIContentGate
    private val bgExecutor = Executors.newSingleThreadExecutor()

    private var lastAiScanAt = 0L
    private val aiScanIntervalMs = 900L

    private val gridCols = 4
    private val gridRows = 6
    private val skinRatioThreshold = 0.06f

    // ──────────────────────────────────────────────
    // Configuración del servicio
    // ──────────────────────────────────────────────
    override fun onServiceConnected() {
        // Modificar el serviceInfo existente para preservar capacidades cargadas del XML (canTakeScreenshot)
        val info = serviceInfo ?: AccessibilityServiceInfo()
        info.eventTypes = AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED or
                          AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED
        info.feedbackType = AccessibilityServiceInfo.FEEDBACK_GENERIC
        info.flags = info.flags or
                     AccessibilityServiceInfo.FLAG_REPORT_VIEW_IDS or 
                     AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS or
                     AccessibilityServiceInfo.FLAG_INCLUDE_NOT_IMPORTANT_VIEWS
        info.notificationTimeout = 100
        serviceInfo = info

        overlayManager = BlockOverlayManager(this)
        aiGate = AIContentGate(applicationContext)

        Log.i(TAG, "✅ LockSuiteAccessibilityService conectado (Programmatic config + XML capabilities)")
    }

    // ──────────────────────────────────────────────
    // Evento principal
    // ──────────────────────────────────────────────
    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (!::overlayManager.isInitialized) return
        val ev = event ?: return
        val packageName = ev.packageName?.toString() ?: return

        // Ignorar nuestra propia app
        if (packageName == LOCKSUITE_PKG) return

        // Garantizar que no consuma batería si la pantalla está inactiva (apagada)
        val powerManager = getSystemService(Context.POWER_SERVICE) as PowerManager
        if (!powerManager.isInteractive) {
            return
        }

        val eventType = ev.eventType
        if (eventType != AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED &&
            eventType != AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED &&
            eventType != AccessibilityEvent.TYPE_VIEW_SELECTED) return

        // ── Actualizar stack de paquetes de apps reales ──
        trackPackage(packageName)

        // ── Bloqueo de Estados y Canales en WhatsApp ──
        if (packageName == PKG_WHATSAPP || packageName == PKG_WHATSAPP_BUSINESS) {
            handleWhatsAppBlocking(packageName, ev)
        }

        // ── Bloqueo de Ofertas en Mercado Pago ──
        if (packageName == PKG_MERCADOPAGO) {
            handleMercadoPagoBlocking(packageName, ev)
        }

        // Debounce para CONTENT_CHANGED (se dispara muy seguido)
        if (eventType == AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED) {
            val now = System.currentTimeMillis()
            if (now - lastCheckedAt < DEBOUNCE_MS) return
            lastCheckedAt = now
        }

        Log.d(TAG, "EVENT pkg=$packageName type=${eventType.toEventName()} stack=${appPackageStack.toList()}")

        // ── Bloqueo de WebView ──
        handleWebViewBlocking(packageName)

        // ── Anti-evasión en Ajustes ──
        val isEvasionEnabled = PrefsHelper.getMdmPrefs(this).getBoolean("settings_evasion_enabled", false)
        if (isEvasionEnabled && packageName == SETTINGS_PKG) {
            handleSettingsAntiEvasion(ev)
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
        } else {
            overlayManager.clearStaleRegions("layer1:", emptySet())
        }

        // 2. Capa 2: Bloqueo por IA
        val isGlobalAi = ImageBlockManager.isGlobalAiEnabled(applicationContext)
        val isEligible = DeviceCapability.isEligibleForAIBlocking(applicationContext)
        val runAi = ((mode == "layer2" || mode == "both") && isGlobalAi && isEligible) || (mapsBlocking && isGlobalAi && isEligible)

        if (runAi) {
            scheduleAiScanIfDue(activePkg, mapsBlocking)
        } else {
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

    private fun runLayer1NodeBlocking(activePkg: String) {
        val root = rootInActiveWindow ?: return
        val rootPkg = root.packageName?.toString() ?: ""
        if (rootPkg != activePkg && isSystemOrInputPackage(rootPkg)) {
            // Ignorar escaneo si la ventana activa es del sistema o teclado, para no borrar overlays
            return
        }
        val foundKeys = mutableSetOf<String>()
        scanNode(root, foundKeys)
        overlayManager.clearStaleRegions("layer1:", foundKeys)
    }

    private fun scanNode(node: AccessibilityNodeInfo, foundKeys: MutableSet<String>) {
        val className = node.className?.toString()
        if (className != null && className in visualNodeClassNames) {
            val rect = Rect()
            node.getBoundsInScreen(rect)
            if (!rect.isEmpty) {
                val key = "layer1:$className:${rect.left},${rect.top}"
                foundKeys.add(key)
                overlayManager.blockRegion(key, rect)
            }
        }
        for (i in 0 until node.childCount) {
            node.getChild(i)?.let { scanNode(it, foundKeys) }
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
                        if (currentPkg == targetPackageName || isSystemOrInputPackage(currentPkg)) {
                            processScreenshotByGrid(bitmap, isMapsStrict)
                        } else {
                            bitmap.recycle()
                            overlayManager.clearStaleRegions("layer2:", emptySet())
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
        val lower = pkg.lowercase()
        return pkg == LOCKSUITE_PKG || 
               pkg == "com.android.systemui" || 
               lower.contains("inputmethod") || 
               lower.contains("latin") || 
               lower.contains("gboard") || 
               lower.contains("swiftkey") || 
               lower.contains("keyboard") || 
               lower.contains("ime")
    }

    private fun trackPackage(packageName: String) {
        val isProvider = WEBVIEW_PROVIDER_PACKAGES.contains(packageName)
        val isBrowser = isBrowserPackage(packageName)
        val isSysOrInput = isSystemOrInputPackage(packageName)

        if (!isProvider && !isBrowser && !isSysOrInput) {
            // Es una app real: agregar al stack si es diferente al tope
            if (appPackageStack.firstOrNull() != packageName) {
                appPackageStack.addFirst(packageName)
                if (appPackageStack.size > 5) appPackageStack.removeLast()
            }
        }
    }

    private fun getOriginApp(): String? = appPackageStack.firstOrNull()

    // ──────────────────────────────────────────────
    // Lógica principal de bloqueo de WebView
    // ──────────────────────────────────────────────
    private fun handleWebViewBlocking(packageName: String) {
        if (WEBVIEW_PROVIDER_PACKAGES.contains(packageName)) {
            val originApp = getOriginApp()
            Log.d(TAG, "WebView provider detectado. originApp=$originApp")
            if (originApp != null && WebViewBlockManager.isBlocked(this, originApp)) {
                Log.w(TAG, "🚫 Bloqueando WebView de $originApp (provider=$packageName)")
                triggerBlock(originApp)
            }
            return
        }

        if (isBrowserPackage(packageName)) {
            val originApp = getOriginApp()
            Log.d(TAG, "Browser detectado. originApp=$originApp")
            if (originApp != null && originApp != packageName && WebViewBlockManager.isBlocked(this, originApp)) {
                Log.w(TAG, "🚫 Bloqueando Custom Tab/Browser de $originApp (browser=$packageName)")
                triggerBlock(originApp)
            }
            return
        }

        if (!WebViewBlockManager.isBlocked(this, packageName)) return
        checkAndBlockWebViewInTree(packageName, immediate = true)
    }

    private fun checkAndBlockWebViewInTree(packageName: String, immediate: Boolean) {
        if (immediate) {
            val root = rootInActiveWindow
            if (root != null) {
                if (isWindowFromPackage(root, packageName)) {
                    val found = containsWebView(root)
                    Log.d(TAG, "Verificación inmediata pkg=$packageName webViewFound=$found")
                    if (found) {
                        root.recycle()
                        triggerBlock(packageName)
                        return
                    }
                }
                root.recycle()
            }
        }

        listOf(200L, 500L, 900L, 1500L, 2500L).forEach { delay ->
            mainHandler.postDelayed({
                if (blockInProgress) return@postDelayed
                val current = rootInActiveWindow ?: return@postDelayed
                val currentPkg = current.packageName?.toString() ?: run {
                    current.recycle()
                    return@postDelayed
                }
                val relevantPkg = currentPkg == packageName ||
                                  WEBVIEW_PROVIDER_PACKAGES.contains(currentPkg)

                if (!relevantPkg) {
                    current.recycle()
                    return@postDelayed
                }

                val found = containsWebView(current)
                Log.d(TAG, "Reintento +${delay}ms pkg=$packageName currentPkg=$currentPkg webViewFound=$found")
                current.recycle()
                if (found) {
                    Log.w(TAG, "🚫 WebView detectado con retraso de ${delay}ms para $packageName")
                    triggerBlock(packageName)
                }
            }, delay)
        }
    }

    private fun isWindowFromPackage(root: AccessibilityNodeInfo, packageName: String): Boolean {
        val windowPkg = root.packageName?.toString() ?: return false
        return windowPkg == packageName || WEBVIEW_PROVIDER_PACKAGES.contains(windowPkg)
    }

    private fun triggerBlock(packageName: String) {
        if (blockInProgress) return
        blockInProgress = true

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

                Log.d(TAG, "PostBack check: currentPkg=$currentPkg stillThere=$stillThere hasWebView=$hasWebView")

                current.recycle()

                if (stillThere && hasWebView) {
                    Log.w(TAG, "🏠 WebView persiste, forzando HOME")
                    performGlobalAction(GLOBAL_ACTION_HOME)
                }
            }
            blockInProgress = false
        }, 700)
    }

    private fun containsWebView(node: AccessibilityNodeInfo): Boolean {
        return containsWebViewInternal(node, depth = 0)
    }

    private fun containsWebViewInternal(node: AccessibilityNodeInfo, depth: Int): Boolean {
        if (depth > 30) return false

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
            Log.i(TAG, "  🔍 WebView encontrado: class=$className pkg=$nodePkg depth=$depth")
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

    private fun isBrowserPackage(packageName: String): Boolean {
        if (KNOWN_BROWSER_PACKAGES.contains(packageName)) return true
        return try {
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
            list.any { it.activityInfo.packageName == packageName }
        } catch (e: Exception) {
            false
        }
    }

    // ──────────────────────────────────────────────
    // Anti-evasión en Ajustes
    // ──────────────────────────────────────────────
    private fun handleSettingsAntiEvasion(event: AccessibilityEvent) {
        val root = rootInActiveWindow ?: return
        val isInLockSuiteSettings = searchNodeByText(root, listOf("locksuite", "lock suite", LOCKSUITE_PKG))
        if (!isInLockSuiteSettings) {
            root.recycle()
            return
        }

        val dangerousActions = listOf(
            "desactivar", "turn off", "disable",
            "forzar detención", "force stop", "deshabilitar",
            "quitar administrador", "quitar admin", "desinstalar", "uninstall",
            "הסר", "אלץ עצירה", "עצירה כפויה", "השבת", "ביטול", "הסרת התקנה",
            "אפשטעלן", "דעאקטיקירן", "אומאינסטאלירn"
        )

        val hasDangerousAction = searchNodeByText(root, dangerousActions)
        root.recycle()

        if (hasDangerousAction) {
            performGlobalAction(GLOBAL_ACTION_BACK)
            Toast.makeText(this, "Acción denegada por políticas de seguridad de LockSuite MDM", Toast.LENGTH_LONG).show()
            val loginIntent = Intent(this, com.ejemplo.locksuite.ui.auth.LoginActivity::class.java).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
            }
            startActivity(loginIntent)
        }
    }

    // ──────────────────────────────────────────────
    // Interceptor del marcador (códigos secretos)
    // ──────────────────────────────────────────────
    private fun handleDialerIntercept() {
        val root = rootInActiveWindow ?: return
        val isOpenCode    = searchNodeByText(root, listOf("*#*#1234#*#*", "*#*#1234#*#"))
        val isEmergencyCode = searchNodeByText(root, listOf("*#*#9999#*#*", "*#*#9999#*#"))
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

    private fun searchNodeByText(root: AccessibilityNodeInfo, keywords: List<String>): Boolean {
        val text = root.text?.toString()?.lowercase() ?: ""
        val desc = root.contentDescription?.toString()?.lowercase() ?: ""
        if (keywords.any { text.contains(it) || desc.contains(it) }) return true
        val childCount = root.childCount
        for (i in 0 until childCount) {
            val child = root.getChild(i) ?: continue
            val found = searchNodeByText(child, keywords)
            child.recycle()
            if (found) return true
        }
        return false
    }

    private fun Int.toEventName(): String = when (this) {
        AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED   -> "STATE_CHANGED"
        AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED -> "CONTENT_CHANGED"
        else -> "OTHER($this)"
    }

    private var lastWaWindowClassName: String? = null

    private enum class WhatsAppRestrictedContent { STATUS, CHANNEL, NONE }

    private val STATUS_ACTIVITY_HINTS = listOf("status", "stories", "mediaview")
    private val CHANNEL_ACTIVITY_HINTS = listOf("newsletter", "channel")

    private val UPDATES_TAB_LABELS = listOf("Novedades", "Updates", "Estados", "Status", "Canales", "Channels")
    private val CHATS_TAB_LABELS = listOf("Chats", "Conversaciones")

    private fun classifyWhatsAppContent(className: String?): WhatsAppRestrictedContent {
        val c = className?.lowercase() ?: return WhatsAppRestrictedContent.NONE
        return when {
            STATUS_ACTIVITY_HINTS.any { c.contains(it) } -> WhatsAppRestrictedContent.STATUS
            CHANNEL_ACTIVITY_HINTS.any { c.contains(it) } -> WhatsAppRestrictedContent.CHANNEL
            else -> WhatsAppRestrictedContent.NONE
        }
    }

    private fun handleWhatsAppBlocking(packageName: String, event: AccessibilityEvent) {
        val policyManager = com.ejemplo.locksuite.mdm.PolicyManager(applicationContext)
        val blockStatus = policyManager.isWhatsAppBlockStatusEnabled()
        val blockChannels = policyManager.isWhatsAppBlockChannelsEnabled()

        if (!blockStatus && !blockChannels) return

        val eventType = event.eventType
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

    private fun isNodeOrParentSelected(node: AccessibilityNodeInfo?): Boolean {
        var current = node
        var depth = 0
        while (current != null && depth < 5) {
            if (current.isSelected) return true
            current = current.parent
            depth++
        }
        return false
    }

    private fun clickNodeOrClickableParent(node: AccessibilityNodeInfo?): Boolean {
        var current = node
        var depth = 0
        while (current != null && depth < 5) {
            if (current.isClickable) {
                if (current.performAction(AccessibilityNodeInfo.ACTION_CLICK)) {
                    return true
                }
            }
            current = current.parent
            depth++
        }
        return false
    }

    private fun scanForUpdatesTab(blockStatus: Boolean, blockChannels: Boolean) {
        val root = rootInActiveWindow ?: return
        val rootPkg = root.packageName?.toString() ?: ""
        if (rootPkg != PKG_WHATSAPP && rootPkg != PKG_WHATSAPP_BUSINESS) return

        val enTabRestringida = UPDATES_TAB_LABELS.any { label ->
            root.findAccessibilityNodeInfosByText(label).any { isNodeOrParentSelected(it) }
        }
        if (enTabRestringida) {
            redirectAwayFromUpdatesTab(root)
        }
    }

    private fun redirectAwayFromUpdatesTab(root: AccessibilityNodeInfo) {
        val chatsNode = CHATS_TAB_LABELS
            .flatMap { root.findAccessibilityNodeInfosByText(it) }
            .firstOrNull()

        val clickOk = clickNodeOrClickableParent(chatsNode)
        if (!clickOk) {
            performGlobalAction(GLOBAL_ACTION_BACK)
        }
    }

    private fun triggerWhatsAppBlock(type: String) {
        if (blockInProgress) return
        blockInProgress = true
        mainHandler.post {
            Toast.makeText(applicationContext, "$type bloqueado por políticas del MDM", Toast.LENGTH_SHORT).show()
        }
        performGlobalAction(GLOBAL_ACTION_BACK)
        mainHandler.postDelayed({
            val currentRoot = rootInActiveWindow
            val currentClassName = currentRoot?.className?.toString() ?: lastWaWindowClassName ?: ""
            if (classifyWhatsAppContent(currentClassName) != WhatsAppRestrictedContent.NONE) {
                performGlobalAction(GLOBAL_ACTION_HOME)
            }
            blockInProgress = false
        }, 700)
    }

    private val mercadoPagoScanRunnable = object : Runnable {
        override fun run() {
            val policyManager = com.ejemplo.locksuite.mdm.PolicyManager(applicationContext)
            if (policyManager.isMercadoPagoBlockOffersEnabled()) {
                scanForMercadoPagoOffers()
            }
        }
    }

    private fun handleMercadoPagoBlocking(packageName: String, event: AccessibilityEvent) {
        val policyManager = com.ejemplo.locksuite.mdm.PolicyManager(applicationContext)
        if (!policyManager.isMercadoPagoBlockOffersEnabled()) return

        val eventType = event.eventType
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
        val root = rootInActiveWindow ?: return
        val rootPkg = root.packageName?.toString() ?: ""
        if (rootPkg != PKG_MERCADOPAGO && !WEBVIEW_PROVIDER_PACKAGES.contains(rootPkg)) {
            root.recycle()
            return
        }

        val containsOffersNode = checkNodeTreeForOffers(root)
        root.recycle()

        if (containsOffersNode) {
            triggerMercadoPagoBlock()
        }
    }

    private fun checkNodeTreeForOffers(node: AccessibilityNodeInfo?): Boolean {
        if (node == null) return false
        val text = node.text?.toString()?.lowercase() ?: ""
        val contentDesc = node.contentDescription?.toString()?.lowercase() ?: ""
        val viewId = node.viewIdResourceName?.lowercase() ?: ""

        val combined = "$text $contentDesc $viewId"

        if (node.isSelected || node.isFocused) {
            if (MP_OFFERS_KEYWORDS.any { combined.contains(it) }) {
                return true
            }
        }

        if (combined.contains("ofertas") || combined.contains("promociones") || combined.contains("mercadolibre.com")) {
            if (node.className?.toString()?.contains("WebView", ignoreCase = true) == true ||
                node.className?.toString()?.contains("TextView", ignoreCase = true) == true && (node.parent?.isSelected == true || node.isFocused)) {
                return true
            }
        }

        val childCount = node.childCount
        for (i in 0 until childCount) {
            val child = node.getChild(i)
            if (child != null) {
                val found = checkNodeTreeForOffers(child)
                child.recycle()
                if (found) return true
            }
        }
        return false
    }

    private fun triggerMercadoPagoBlock() {
        if (blockInProgress) return
        blockInProgress = true
        mainHandler.post {
            Toast.makeText(applicationContext, "🚫 Sección de Ofertas restringida por LockSuite", Toast.LENGTH_SHORT).show()
        }
        performGlobalAction(GLOBAL_ACTION_BACK)
        mainHandler.postDelayed({
            blockInProgress = false
        }, 700)
    }

    override fun onInterrupt() {
        Log.w(TAG, "⚠️ LockSuiteAccessibilityService interrumpido")
    }

    override fun onDestroy() {
        super.onDestroy()
        if (::overlayManager.isInitialized) {
            overlayManager.clearAll()
        }
        AIContentGate.releaseAll()
        bgExecutor.shutdown()
    }
}
