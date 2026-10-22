package com.ejemplo.locksuite.ui.verification

import android.Manifest
import android.app.admin.DevicePolicyManager
import android.content.ComponentName
import android.content.Context
import android.content.pm.PackageManager
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.os.UserManager
import android.util.Log
import android.util.Size
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import androidx.camera.core.Preview
import androidx.camera.core.resolutionselector.ResolutionSelector
import androidx.camera.core.resolutionselector.ResolutionStrategy
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.ejemplo.locksuite.mdm.GoogleVerificationPolicy
import com.ejemplo.locksuite.util.QrLuminanceDecoder
import java.util.Locale
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.delay

/**
 * Escáner de QR propio del microvisor (22/10/2026, B.92).
 *
 * POR QUÉ NO ALCANZA CON LA CÁMARA DEL SISTEMA:
 *  - En Android 12+ un link web va al NAVEGADOR predeterminado salvo que la app esté
 *    aprobada para ese dominio. LockSuite no puede verificar `accounts.google.com`, así
 *    que la cámara abría el navegador suspendido ("app pausada por el administrador")
 *    y el `intent-filter` del Manifest nunca se usaba. Se puede aprobar a mano por ADB
 *    (`pm set-app-links-user-selection`, ver las instrucciones del 22/10), pero eso no
 *    escala a la flota.
 *  - Varios equipos de la flota (teclas, Go) no traen lector de QR en la cámara.
 *
 * Lo que hace: CameraX + ZXing ([QrLuminanceDecoder]), sin servicios de Google y sin
 * red. Lo único que acepta es un link que [GoogleVerificationPolicy.isNavigationAllowed]
 * deja pasar; cualquier otro QR se ignora con un aviso y se sigue escaneando. La
 * imagen no se guarda en ningún lado: se analiza el cuadro y se descarta.
 *
 * Permiso de cámara: LockSuite es Device Owner, así que se concede solo (mismo
 * mecanismo que `PolicyManager.autoGrantDeclaredPermissions`, B.55). Si la cámara está
 * deshabilitada por política del administrador, NO se toca: se avisa.
 */
private const val TAG = "LockSuiteVerif"

/** Entre intento e intento de lectura: en un Snapdragon 215 decodificar 720p cuesta 50-100 ms. */
private const val MIN_DECODE_INTERVAL_MS = 120L

/** Cada cuántos intentos se busca con más esfuerzo (y con colores invertidos). */
private const val TRY_HARDER_EVERY = 4

/** Para no repetir el aviso de "este QR no es de Google" en cada cuadro. */
private const val REJECT_NOTICE_MS = 2500L

@Composable
fun GoogleQrScannerScreen(
    onVerificationUrl: (String) -> Unit,
    onCancel: () -> Unit
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val currentOnUrl by rememberUpdatedState(onVerificationUrl)

    val blockedByPolicy = remember { isCameraBlockedByPolicy(context) }
    var hasPermission by remember { mutableStateOf(hasCameraPermission(context)) }
    var permissionAsked by remember { mutableStateOf(false) }
    var cameraError by remember { mutableStateOf<String?>(null) }
    var notice by remember { mutableStateOf<String?>(null) }

    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        hasPermission = granted || hasCameraPermission(context)
    }

    LaunchedEffect(blockedByPolicy) {
        if (blockedByPolicy || hasPermission) return@LaunchedEffect
        // 1) Device Owner: se lo concede a sí mismo, sin diálogo.
        if (selfGrantCameraIfDeviceOwner(context) && hasCameraPermission(context)) {
            hasPermission = true
            return@LaunchedEffect
        }
        // 2) Si no, el diálogo normal del sistema (una sola vez por pantalla).
        if (!permissionAsked) {
            permissionAsked = true
            try {
                permissionLauncher.launch(Manifest.permission.CAMERA)
            } catch (e: Exception) {
                cameraError = "No se pudo pedir el permiso de cámara."
            }
        }
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black)
    ) {
        when {
            blockedByPolicy -> ScannerMessage(
                text = "La cámara está bloqueada por el administrador de este celular.\n\n" +
                        "Pedile que la habilite un momento para escanear el código, o volvé " +
                        "y pegá el enlace de verificación a mano.",
                onCancel = onCancel
            )

            !hasPermission -> {
                val retry: () -> Unit = {
                    if (selfGrantCameraIfDeviceOwner(context) && hasCameraPermission(context)) {
                        hasPermission = true
                    } else {
                        try {
                            permissionLauncher.launch(Manifest.permission.CAMERA)
                        } catch (_: Exception) {
                        }
                    }
                }
                ScannerMessage(
                    text = if (permissionAsked) {
                        "LockSuite no tiene permiso para usar la cámara, así que no puede leer el código QR."
                    } else {
                        "Preparando la cámara…"
                    },
                    onCancel = onCancel,
                    onRetry = if (permissionAsked) retry else null
                )
            }

            cameraError != null -> ScannerMessage(
                text = cameraError ?: "",
                onCancel = onCancel
            )

            else -> {
                val previewView = remember {
                    PreviewView(context).apply {
                        scaleType = PreviewView.ScaleType.FILL_CENTER
                        // TextureView: es el modo que mejor convive con Compose y con
                        // equipos viejos (SurfaceView a veces queda negro dentro de AndroidView).
                        implementationMode = PreviewView.ImplementationMode.COMPATIBLE
                        keepScreenOn = true
                    }
                }

                AndroidView(
                    factory = { previewView },
                    modifier = Modifier.fillMaxSize()
                )

                DisposableEffect(lifecycleOwner) {
                    val mainHandler = Handler(Looper.getMainLooper())
                    val analysisExecutor: ExecutorService = Executors.newSingleThreadExecutor()
                    val delivered = AtomicBoolean(false)
                    // Si el usuario cancela antes de que CameraX termine de arrancar, el aviso
                    // llega igual: sin esta marca la cámara quedaba enganchada a la Activity
                    // (encendida, sin vista previa) hasta cerrar el microvisor.
                    val disposed = AtomicBoolean(false)
                    var provider: ProcessCameraProvider? = null
                    val providerFuture = ProcessCameraProvider.getInstance(context)

                    providerFuture.addListener({
                        if (disposed.get()) return@addListener
                        try {
                            val p = providerFuture.get()
                            provider = p

                            val selector = pickCamera(p)
                            if (selector == null) {
                                cameraError = "Este celular no tiene una cámara disponible."
                                return@addListener
                            }

                            val preview = Preview.Builder().build()
                            preview.setSurfaceProvider(previewView.surfaceProvider)

                            val analysis = ImageAnalysis.Builder()
                                .setResolutionSelector(
                                    ResolutionSelector.Builder()
                                        .setResolutionStrategy(
                                            ResolutionStrategy(
                                                Size(1280, 720),
                                                ResolutionStrategy.FALLBACK_RULE_CLOSEST_HIGHER_THEN_LOWER
                                            )
                                        )
                                        .build()
                                )
                                .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                                .build()

                            analysis.setAnalyzer(
                                analysisExecutor,
                                QrFrameAnalyzer(
                                    delivered = delivered,
                                    onAccepted = { url -> mainHandler.post { currentOnUrl(url) } },
                                    onRejected = { msg -> mainHandler.post { notice = msg } }
                                )
                            )

                            p.unbindAll()
                            p.bindToLifecycle(lifecycleOwner, selector, preview, analysis)
                        } catch (e: Exception) {
                            Log.w(TAG, "No se pudo abrir la cámara: ${e.javaClass.simpleName}: ${e.message}")
                            cameraError = "No se pudo abrir la cámara (${e.javaClass.simpleName}). " +
                                    "Cerrá otras apps que la estén usando y probá de nuevo."
                        }
                    }, ContextCompat.getMainExecutor(context))

                    onDispose {
                        disposed.set(true)
                        try {
                            provider?.unbindAll()
                        } catch (_: Exception) {
                        }
                        analysisExecutor.shutdown()
                        mainHandler.removeCallbacksAndMessages(null)
                    }
                }

                LaunchedEffect(notice) {
                    if (notice != null) {
                        delay(REJECT_NOTICE_MS + 2000L)
                        notice = null
                    }
                }

                ScannerOverlay(notice = notice, onCancel = onCancel)
            }
        }
    }
}

/**
 * Lee cada cuadro. Corre en su propio hilo (nunca en el principal) y SIEMPRE cierra el
 * cuadro: un `ImageProxy` sin cerrar frena la cámara entera.
 */
private class QrFrameAnalyzer(
    private val delivered: AtomicBoolean,
    private val onAccepted: (String) -> Unit,
    private val onRejected: (String) -> Unit
) : ImageAnalysis.Analyzer {

    private var buffer = ByteArray(0)
    private var lastAttemptAt = 0L
    private var attempts = 0
    private var lastRejectNoticeAt = 0L

    override fun analyze(image: ImageProxy) {
        try {
            if (delivered.get()) return
            val now = SystemClock.elapsedRealtime()
            if (now - lastAttemptAt < MIN_DECODE_INTERVAL_MS) return
            lastAttemptAt = now

            val plane = image.planes.firstOrNull() ?: return
            val width = image.width
            val height = image.height
            val rowStride = plane.rowStride
            if (width <= 0 || height <= 0 || rowStride < width) return

            val needed = rowStride * height
            if (buffer.size < needed) buffer = ByteArray(needed)
            val src = plane.buffer
            src.rewind()
            val count = minOf(src.remaining(), needed)
            if (count < rowStride * (height - 1) + width) return
            src.get(buffer, 0, count)

            attempts++
            val text = QrLuminanceDecoder.decode(
                buffer, rowStride, width, height,
                tryHarder = attempts % TRY_HARDER_EVERY == 0
            ) ?: return

            val trimmed = text.trim()
            if (GoogleVerificationPolicy.isNavigationAllowed(trimmed)) {
                if (delivered.compareAndSet(false, true)) {
                    Log.i(TAG, "QR aceptado: ${GoogleVerificationPolicy.describeForLog(trimmed)}")
                    onAccepted(trimmed)
                }
            } else if (now - lastRejectNoticeAt > REJECT_NOTICE_MS) {
                lastRejectNoticeAt = now
                Log.i(TAG, "QR ignorado: ${GoogleVerificationPolicy.describeForLog(trimmed)}")
                onRejected(rejectionMessage(trimmed))
            }
        } catch (e: Exception) {
            Log.w(TAG, "Error leyendo un cuadro: ${e.message}")
        } finally {
            image.close()
        }
    }
}

private fun rejectionMessage(text: String): String {
    val lower = text.lowercase(Locale.ROOT)
    return when {
        lower.startsWith("fido:") ->
            "Ese código es de una llave de acceso (passkey), no de la verificación por SMS. " +
                    "En la computadora elegí otra forma de verificar."
        lower.startsWith("https://accounts.google.com/") ->
            "Ese código es de Google, pero no es una verificación que este celular pueda abrir."
        else ->
            "Ese código QR no es de una verificación de Google. Apuntá al código que muestra la computadora."
    }
}

@Composable
private fun ScannerOverlay(notice: String?, onCancel: () -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.SpaceBetween
    ) {
        Text(
            text = "Apuntá al código QR que muestra Google en la computadora",
            color = Color.White,
            fontSize = 15.sp,
            fontWeight = FontWeight.Bold,
            textAlign = TextAlign.Center,
            modifier = Modifier
                .fillMaxWidth()
                .background(Color(0xAA000000), RoundedCornerShape(8.dp))
                .padding(10.dp)
        )

        Box(
            modifier = Modifier
                .size(220.dp)
                .border(3.dp, Color(0xFFFF9100), RoundedCornerShape(16.dp))
        )

        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            if (notice != null) {
                Text(
                    text = notice,
                    color = Color(0xFFFFD180),
                    fontSize = 13.sp,
                    textAlign = TextAlign.Center,
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(Color(0xCC000000), RoundedCornerShape(8.dp))
                        .padding(10.dp)
                )
                Spacer(modifier = Modifier.height(8.dp))
            }
            Button(
                onClick = onCancel,
                colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF424242)),
                shape = RoundedCornerShape(8.dp)
            ) {
                Text("Cancelar", color = Color.White)
            }
        }
    }
}

@Composable
private fun ScannerMessage(text: String, onCancel: () -> Unit, onRetry: (() -> Unit)? = null) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Icon(
            imageVector = Icons.Default.Warning,
            contentDescription = null,
            tint = Color(0xFFFF9100),
            modifier = Modifier.size(40.dp)
        )
        Spacer(modifier = Modifier.height(12.dp))
        Text(text = text, color = Color.White, fontSize = 15.sp, textAlign = TextAlign.Center)
        Spacer(modifier = Modifier.height(20.dp))
        if (onRetry != null) {
            Button(
                onClick = onRetry,
                colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFFF9100)),
                shape = RoundedCornerShape(8.dp)
            ) {
                Text("Reintentar", color = Color.Black, fontWeight = FontWeight.Bold)
            }
            Spacer(modifier = Modifier.height(8.dp))
        }
        Button(
            onClick = onCancel,
            colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF424242)),
            shape = RoundedCornerShape(8.dp)
        ) {
            Text("Volver", color = Color.White)
        }
    }
}

private fun pickCamera(provider: ProcessCameraProvider): CameraSelector? {
    val candidates = listOf(CameraSelector.DEFAULT_BACK_CAMERA, CameraSelector.DEFAULT_FRONT_CAMERA)
    for (selector in candidates) {
        try {
            if (provider.hasCamera(selector)) return selector
        } catch (_: Exception) {
        }
    }
    return null
}

private fun hasCameraPermission(context: Context): Boolean =
    ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) ==
            PackageManager.PERMISSION_GRANTED

/**
 * La cámara la puede apagar el administrador de dos formas: `setCameraDisabled` (la de
 * siempre) y, desde Android 14, la restricción `no_camera` (`DISALLOW_CAMERA`, oculta en
 * el SDK: por eso el literal, igual que en `PolicyManager`). Si cualquiera de las dos
 * está puesta, el escáner no la toca.
 */
private fun isCameraBlockedByPolicy(context: Context): Boolean = try {
    val dpm = context.getSystemService(Context.DEVICE_POLICY_SERVICE) as? DevicePolicyManager
    val um = context.getSystemService(Context.USER_SERVICE) as? UserManager
    (dpm?.getCameraDisabled(null) == true) || (um?.hasUserRestriction("no_camera") == true)
} catch (_: Exception) {
    false
}

private fun selfGrantCameraIfDeviceOwner(context: Context): Boolean = try {
    val dpm = context.getSystemService(Context.DEVICE_POLICY_SERVICE) as? DevicePolicyManager
    if (dpm != null && dpm.isDeviceOwnerApp(context.packageName)) {
        val admin = ComponentName(context, com.ejemplo.locksuite.receiver.DeviceAdminReceiver::class.java)
        dpm.setPermissionGrantState(
            admin,
            context.packageName,
            Manifest.permission.CAMERA,
            DevicePolicyManager.PERMISSION_GRANT_STATE_GRANTED
        )
    } else {
        false
    }
} catch (e: Exception) {
    Log.w(TAG, "No se pudo auto-conceder la cámara: ${e.message}")
    false
}
