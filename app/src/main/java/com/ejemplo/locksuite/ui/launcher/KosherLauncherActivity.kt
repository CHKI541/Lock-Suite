package com.ejemplo.locksuite.ui.launcher

import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ResolveInfo
import android.os.BatteryManager
import android.os.Build
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.OnBackPressedCallback
import androidx.activity.compose.setContent
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Android
import androidx.compose.material.icons.filled.BatteryFull
import androidx.compose.material.icons.filled.Bluetooth
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.graphics.drawable.toBitmap
import com.ejemplo.locksuite.mdm.AppController
import java.text.SimpleDateFormat
import java.util.*

data class AppItem(
    val packageName: String,
    val label: String,
    val launchIntent: Intent,
    val kosherIcon: KosherAppIcon,
    val resolveInfo: ResolveInfo?
)

class KosherLauncherActivity : ComponentActivity() {

    private var batteryPercent by mutableIntStateOf(100)
    private var isBluetoothEnabled by mutableStateOf(false)
    private var currentTime by mutableStateOf("")

    // ─────────────────────────────────────────────────────────────────────────
    // MODO TELÉFONO DE TECLAS  (2/9/2026)
    //
    // Pedido del dueño: un modo kiosco al estilo KeyLauncher, manejado con botones, con
    // opción de apagar el táctil. El estado vive acá y no en Compose porque las teclas
    // físicas llegan por `onKeyDown()` de la Activity — ver el comentario de cabecera de
    // NokiaKeypadScreen para el porqué completo.
    // ─────────────────────────────────────────────────────────────────────────

    /** ¿Está el menú abierto? Si no, se ve la pantalla de inicio con el reloj. */
    private var nokiaEnMenu by mutableStateOf(false)

    /** Índice absoluto de la app seleccionada dentro de la lista completa. */
    private var nokiaSeleccion by mutableIntStateOf(0)

    /** Lista de apps que el modo teclas está mostrando. La llena la pantalla al componer. */
    private var nokiaApps: List<AppItem> = emptyList()

    private var nokiaFecha by mutableStateOf("")

    /**
     * Momento en que empezó una pulsación sostenida en la esquina superior derecha.
     *
     * ⚠️ ES LA SALIDA DE EMERGENCIA DEL MODO SIN TÁCTIL, Y NO HAY QUE SACARLA.
     *
     * Con el táctil apagado, un equipo **sin teclas físicas** —o sea cualquier celular
     * táctil común— queda manejable solo desde el panel web. Si además se cae la red, no
     * hay forma de recuperarlo en la mano. Por eso se conserva un gesto deliberadamente
     * incómodo (mantener 3 segundos en la esquina superior derecha) que abre la pantalla
     * de administrador: es imposible de hacer sin querer y suficiente para no quedarse
     * afuera. Está documentado a propósito: una salida de emergencia secreta que nadie
     * recuerda no sirve de nada.
     */
    private var toqueEsquinaDesde = 0L

    private val batteryReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            val level = intent.getIntExtra(BatteryManager.EXTRA_LEVEL, -1)
            val scale = intent.getIntExtra(BatteryManager.EXTRA_SCALE, -1)
            batteryPercent = if (level != -1 && scale != -1 && scale > 0) {
                (level * 100 / scale.toFloat()).toInt()
            } else {
                100
            }
        }
    }

    private val bluetoothReceiver = object : BroadcastReceiver() {
        @SuppressLint("MissingPermission")
        override fun onReceive(context: Context, intent: Intent) {
            if (intent.action == BluetoothAdapter.ACTION_STATE_CHANGED) {
                val state = intent.getIntExtra(BluetoothAdapter.EXTRA_STATE, BluetoothAdapter.ERROR)
                isBluetoothEnabled = (state == BluetoothAdapter.STATE_ON)
            }
        }
    }

    private var clockTimer: Timer? = null

    @SuppressLint("MissingPermission")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Ocultar la barra de estado únicamente en la ventana del launcher (la barra inferior de navegación queda 100% funcional)
        androidx.core.view.WindowCompat.setDecorFitsSystemWindows(window, false)
        val insetsController = androidx.core.view.WindowCompat.getInsetsController(window, window.decorView)
        insetsController.hide(androidx.core.view.WindowInsetsCompat.Type.statusBars())
        insetsController.systemBarsBehavior = androidx.core.view.WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE

        // Evitar que se cierre la actividad por el botón back — el launcher debe quedar fijo

        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                // No hacer nada
            }
        })

        // Consultar estado inicial de Bluetooth
        try {
            @Suppress("DEPRECATION")
            val bluetoothAdapter = BluetoothAdapter.getDefaultAdapter()
            isBluetoothEnabled = bluetoothAdapter?.isEnabled ?: false
        } catch (e: Exception) {
            e.printStackTrace()
        }

        // Actualizar la hora inmediatamente
        updateTime()

        // Modo teléfono de teclas: pantalla completamente distinta, mismo launcher.
        // Se decide acá y no dentro de LauncherScreen para no meter una segunda interfaz
        // entera dentro de una función que ya es grande.
        val prefsLauncher = com.ejemplo.locksuite.util.PrefsHelper.getMdmPrefs(this)
        if (prefsLauncher.getBoolean("nokia_keypad_mode", false)) {
            // La lista de apps se arma UNA vez acá, no dentro del `setContent`: cargarla en
            // la composición sería un efecto colateral que se repetiría en cada
            // recomposición (y cada carga enumera todas las actividades del equipo).
            actualizarFechaNokia()
            nokiaApps = cargarAppsNokia()
            val touchOn = prefsLauncher.getBoolean("nokia_touch_enabled", true)
            setContent {
                NokiaKeypadScreen(
                    apps = nokiaApps,
                    enMenu = nokiaEnMenu,
                    seleccion = nokiaSeleccion,
                    pagina = if (nokiaApps.isEmpty()) 0 else nokiaSeleccion / 9,
                    hora = currentTime,
                    fecha = nokiaFecha,
                    bateria = batteryPercent,
                    touchHabilitado = touchOn
                )
            }
        } else {
            setContent {
                LauncherScreen(
                    batteryPercent = batteryPercent,
                    isBluetoothEnabled = isBluetoothEnabled,
                    currentTime = currentTime
                )
            }
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // MODO TELÉFONO DE TECLAS — carga de apps, teclado y bloqueo del táctil
    // ─────────────────────────────────────────────────────────────────────────

    /**
     * Apps visibles en el modo teclas.
     *
     * Se apoya en la MISMA regla que el launcher normal (apps con actividad de lanzador,
     * menos las de sistema, menos las ocultas o suspendidas por el MDM). Repetir el
     * criterio con otra lógica habría hecho que un equipo mostrara cosas distintas en cada
     * modo, que es el tipo de diferencia que después nadie entiende de dónde sale.
     */
    private fun cargarAppsNokia(): List<AppItem> {
        return try {
            val pm = packageManager
            val appController = AppController(this)
            val mainIntent = Intent(Intent.ACTION_MAIN, null).apply {
                addCategory(Intent.CATEGORY_LAUNCHER)
            }
            @Suppress("DEPRECATION")
            val resueltas = pm.queryIntentActivities(mainIntent, 0)
            val ocultasDelSistema = setOf(
                packageName, "com.android.systemui", "com.android.providers.telephony",
                "com.android.packageinstaller", "com.google.android.packageinstaller",
                "com.google.android.gms", "com.google.android.gsf"
            )
            resueltas.mapNotNull { ri ->
                val pkg = ri.activityInfo.packageName
                if (pkg in ocultasDelSistema) return@mapNotNull null
                try {
                    if (appController.isAppHidden(pkg) || appController.isAppSuspended(pkg)) {
                        return@mapNotNull null
                    }
                } catch (_: Exception) {}
                val label = ri.loadLabel(pm).toString()
                val intent = pm.getLaunchIntentForPackage(pkg) ?: return@mapNotNull null
                AppItem(pkg, label, intent, AppIconMapper.getMapping(pkg, label), ri)
            }.distinctBy { it.packageName }
                .sortedBy { NokiaIconSet.para(it.packageName, it.label).label.lowercase() }
        } catch (e: Exception) {
            e.printStackTrace()
            emptyList()
        }
    }

    private fun actualizarFechaNokia() {
        nokiaFecha = try {
            SimpleDateFormat("EEEE d 'de' MMMM", Locale("es", "AR")).format(Date())
                .replaceFirstChar { it.uppercase() }
        } catch (e: Exception) {
            ""
        }
    }

    private fun abrirSeleccionNokia() {
        val app = nokiaApps.getOrNull(nokiaSeleccion) ?: return
        try {
            startActivity(app.launchIntent.apply { addFlags(Intent.FLAG_ACTIVITY_NEW_TASK) })
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    /**
     * Teclas del modo teléfono.
     *
     * Se maneja en `onKeyDown` y no con el foco de Compose a propósito (ver el comentario
     * de cabecera de `NokiaKeypadScreen`).
     *
     * Un detalle que importa: **el movimiento de la cruceta NO envuelve entre páginas por
     * accidente**. La selección es un índice absoluto sobre la lista completa, así que
     * bajar en la última fila pasa a la página siguiente de forma natural, y arriba en la
     * primera fila no hace nada en vez de saltar al final. En un menú de teclas, un salto
     * inesperado de página es lo que hace que el usuario se pierda.
     */
    override fun onKeyDown(keyCode: Int, event: android.view.KeyEvent?): Boolean {
        val prefs = com.ejemplo.locksuite.util.PrefsHelper.getMdmPrefs(this)
        if (!prefs.getBoolean("nokia_keypad_mode", false)) {
            return super.onKeyDown(keyCode, event)
        }
        val total = nokiaApps.size

        // Dígitos 1..9: atajo directo a la casilla de esa posición en la página actual.
        if (keyCode in android.view.KeyEvent.KEYCODE_1..android.view.KeyEvent.KEYCODE_9) {
            if (!nokiaEnMenu) nokiaEnMenu = true
            val enPagina = keyCode - android.view.KeyEvent.KEYCODE_1
            val destino = (nokiaSeleccion / 9) * 9 + enPagina
            if (destino < total) {
                nokiaSeleccion = destino
                abrirSeleccionNokia()
            }
            return true
        }

        when (keyCode) {
            android.view.KeyEvent.KEYCODE_DPAD_CENTER,
            android.view.KeyEvent.KEYCODE_ENTER -> {
                if (nokiaEnMenu) abrirSeleccionNokia() else nokiaEnMenu = true
                return true
            }
            android.view.KeyEvent.KEYCODE_DPAD_UP -> {
                if (nokiaEnMenu && nokiaSeleccion - 3 >= 0) nokiaSeleccion -= 3
                return true
            }
            android.view.KeyEvent.KEYCODE_DPAD_DOWN -> {
                if (nokiaEnMenu && nokiaSeleccion + 3 < total) nokiaSeleccion += 3
                else if (!nokiaEnMenu) nokiaEnMenu = true
                return true
            }
            android.view.KeyEvent.KEYCODE_DPAD_LEFT -> {
                if (nokiaEnMenu && nokiaSeleccion > 0) nokiaSeleccion -= 1
                return true
            }
            android.view.KeyEvent.KEYCODE_DPAD_RIGHT -> {
                if (nokiaEnMenu && nokiaSeleccion + 1 < total) nokiaSeleccion += 1
                return true
            }
            android.view.KeyEvent.KEYCODE_SOFT_LEFT,
            android.view.KeyEvent.KEYCODE_MENU -> {
                if (nokiaEnMenu) abrirSeleccionNokia() else nokiaEnMenu = true
                return true
            }
            android.view.KeyEvent.KEYCODE_SOFT_RIGHT,
            android.view.KeyEvent.KEYCODE_BACK -> {
                // Atrás vuelve al inicio; desde el inicio no hace nada (el launcher es fijo).
                nokiaEnMenu = false
                return true
            }
        }
        return super.onKeyDown(keyCode, event)
    }

    /**
     * Bloqueo del táctil dentro del launcher.
     *
     * ⚠️ ALCANCE REAL, DICHO SIN ADORNOS: **esto apaga el táctil de ESTA pantalla, no del
     * equipo.** Android no le da a una app —ni siquiera a un Device Owner— ninguna forma de
     * desactivar el digitalizador para todo el sistema; eso necesita permisos de plataforma
     * o root. O sea: con esto encendido, el launcher solo responde a teclas, pero si el
     * usuario logra abrir otra app, dentro de esa app el táctil funciona.
     *
     * Lo que sí lo convierte en un modo de teclas de verdad es combinarlo con el kiosco
     * (Lock Task): con los dos encendidos, el equipo solo abre las apps de la lista y el
     * launcher no responde al dedo. Están pensados para usarse juntos.
     *
     * La salida de emergencia (3 segundos en la esquina superior derecha) está explicada
     * en el campo `toqueEsquinaDesde`.
     */
    override fun dispatchTouchEvent(ev: android.view.MotionEvent?): Boolean {
        val prefs = com.ejemplo.locksuite.util.PrefsHelper.getMdmPrefs(this)
        val modoTeclas = prefs.getBoolean("nokia_keypad_mode", false)
        val touchOn = prefs.getBoolean("nokia_touch_enabled", true)
        if (!modoTeclas || touchOn || ev == null) {
            return super.dispatchTouchEvent(ev)
        }

        // Salida de emergencia: pulsación sostenida en la esquina superior derecha.
        val enEsquina = ev.x > resources.displayMetrics.widthPixels * 0.8f &&
            ev.y < resources.displayMetrics.heightPixels * 0.15f
        when (ev.actionMasked) {
            android.view.MotionEvent.ACTION_DOWN ->
                toqueEsquinaDesde = if (enEsquina) android.os.SystemClock.elapsedRealtime() else 0L
            android.view.MotionEvent.ACTION_MOVE ->
                if (!enEsquina) toqueEsquinaDesde = 0L
            android.view.MotionEvent.ACTION_UP -> {
                val sostenido = toqueEsquinaDesde > 0L &&
                    android.os.SystemClock.elapsedRealtime() - toqueEsquinaDesde >= 3000L
                toqueEsquinaDesde = 0L
                if (sostenido) {
                    try {
                        startActivity(
                            Intent(this, com.ejemplo.locksuite.ui.auth.LoginActivity::class.java)
                                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                        )
                    } catch (e: Exception) {
                        e.printStackTrace()
                    }
                }
            }
        }
        // Se consume el evento: el táctil queda inerte para el uso normal.
        return true
    }

    override fun onResume() {
        super.onResume()

        // ── KIOSCO REAL DEL SO (Lock Task, 2/9/2026) ──
        //
        // Copiado de A Bloq (`KioskActivity`). Con el interruptor encendido, esta Activity
        // se ancla: a partir de acá el sistema **solo deja abrir los paquetes de la lista
        // blanca** (ver PolicyManager.applyKioskLockTask), y lo hace cumplir Android, no
        // LockSuite. Sin esto, la lista de apps permitidas del launcher es solo una
        // decisión de interfaz: una notificación o un enlace profundo alcanza para abrir
        // cualquier app.
        //
        // Va en onResume() y no en onCreate() a propósito: si el sistema saca la Activity
        // del anclaje por cualquier motivo (una actualización, un fallo del sistema), al
        // volver a primer plano se vuelve a anclar sola.
        //
        // Se comprueba `lockTaskModeState` antes de llamar: `startLockTask()` estando ya
        // anclado tira excepción en algunas versiones.
        try {
            val pm = com.ejemplo.locksuite.mdm.PolicyManager(this)
            if (pm.isKioskLockTaskEnabled() && !pm.isLockSuiteSuspended()) {
                val am = getSystemService(Context.ACTIVITY_SERVICE) as android.app.ActivityManager
                val yaAnclada = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                    am.lockTaskModeState != android.app.ActivityManager.LOCK_TASK_MODE_NONE
                } else {
                    @Suppress("DEPRECATION")
                    am.isInLockTaskMode
                }
                if (!yaAnclada) {
                    startLockTask()
                }
            }
        } catch (e: Exception) {
            // Nunca dejar que esto impida que la pantalla de inicio se muestre: un launcher
            // que no arranca es un equipo inutilizable.
            e.printStackTrace()
        }

        // Registrar receivers
        registerReceiver(batteryReceiver, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
        registerReceiver(bluetoothReceiver, IntentFilter(BluetoothAdapter.ACTION_STATE_CHANGED))

        // Iniciar actualizaciones del reloj
        startClockUpdates()
    }

    override fun onPause() {
        super.onPause()
        // Detener timer del reloj para no consumir recursos cuando no se ve
        clockTimer?.cancel()
        clockTimer = null

        try {
            unregisterReceiver(batteryReceiver)
        } catch (_: Exception) {}
        try {
            unregisterReceiver(bluetoothReceiver)
        } catch (_: Exception) {}
    }

    private fun updateTime() {
        val sdf = SimpleDateFormat("HH:mm", Locale.getDefault())
        currentTime = sdf.format(Date())
    }

    private fun startClockUpdates() {
        clockTimer?.cancel()
        clockTimer = Timer().also { timer ->
            timer.scheduleAtFixedRate(object : TimerTask() {
                override fun run() {
                    updateTime()
                }
            }, 0, 30_000L) // Cada 30 segundos es suficiente para HH:mm
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun LauncherScreen(
    batteryPercent: Int,
    isBluetoothEnabled: Boolean,
    currentTime: String
) {
    val context = LocalContext.current
    val pm = context.packageManager
    val appController = remember { AppController(context) }

    // Cargar lista de apps visibles
    val appsList = remember {
        val mainIntent = Intent(Intent.ACTION_MAIN, null).apply {
            addCategory(Intent.CATEGORY_LAUNCHER)
        }
        @Suppress("DEPRECATION")
        val resolved = pm.queryIntentActivities(mainIntent, 0)

        // Set de paquetes del sistema que no deben mostrarse en el launcher
        val systemHiddenPackages = setOf(
            context.packageName,          // LockSuite mismo
            "com.android.settings",       // Ajustes nativos
            "com.android.systemui",
            "com.android.providers.telephony",
            "com.android.packageinstaller",
            "com.google.android.packageinstaller",
            "com.android.phone",
            "com.google.android.gms",     // Google Play Services
            "com.google.android.gsf"      // Google Services Framework
        )

        val list = resolved.mapNotNull { resolveInfo ->
            val pkg = resolveInfo.activityInfo.packageName
            // Excluir paquetes del sistema que no sirven al usuario
            if (pkg in systemHiddenPackages) {
                return@mapNotNull null
            }
            // Excluir apps ocultas/suspendidas por el MDM
            try {
                if (appController.isAppHidden(pkg) || appController.isAppSuspended(pkg)) {
                    return@mapNotNull null
                }
            } catch (_: Exception) {}

            val label = resolveInfo.loadLabel(pm).toString()
            val kosherIcon = AppIconMapper.getMapping(pkg, label)
            val launchIntent = pm.getLaunchIntentForPackage(pkg) ?: return@mapNotNull null

            // Excluir lectores de PDF del escritorio (solo se abrirán al pulsar un .pdf en Archivos)
            val pkgLower = pkg.lowercase()
            val labelLower = label.lowercase()
            if (kosherIcon.label == "Lector PDF" || pkgLower.contains("pdf") || labelLower.contains("pdf")) {
                return@mapNotNull null
            }

            AppItem(pkg, kosherIcon.label, launchIntent, kosherIcon, resolveInfo)
        }.toMutableList()

        // Añadir nuestros propios Ajustes Kosher al final
        val settingsIcon = AppIconMapper.getMapping("com.ejemplo.locksuite.settings", "Ajustes")
        val customSettingsIntent = Intent(context, KosherSettingsActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK
        }
        list.add(
            AppItem(
                "com.ejemplo.locksuite.settings",
                "Ajustes",
                customSettingsIntent,
                settingsIcon,
                null
            )
        )

        // Ordenar según prioridad solicitada por el usuario:
        // Grupo 1: Galería, Archivos, Música, Grabadora
        // Grupo 2: Calendario, Calculadora, Reloj, Notas
        // Grupo 3: Resto de las aplicaciones
        // Grupo 4: Ajustes y Bluetooth (al final de todo)
        fun getAppSortingWeight(item: AppItem): Int {
            val lbl = item.label
            val pkg = item.packageName.lowercase()
            if (pkg == "com.ejemplo.locksuite.settings" || lbl == "Ajustes" || 
                pkg.contains("bluetooth") || lbl.lowercase().contains("bluetooth")) {
                return 4
            }
            if (lbl == "Galería" || lbl == "Archivos" || lbl == "Música" || lbl == "Grabadora") {
                return 1
            }
            if (lbl == "Calendario" || lbl == "Calculadora" || lbl == "Reloj" || lbl == "Notas") {
                return 2
            }
            return 3
        }

        list.sortWith(Comparator { a, b ->
            val wA = getAppSortingWeight(a)
            val wB = getAppSortingWeight(b)
            if (wA != wB) {
                wA.compareTo(wB)
            } else {
                a.label.compareTo(b.label, ignoreCase = true)
            }
        })
        list

    }

    val itemsPerPage = 4
    val pagesCount = remember(appsList) {
        maxOf(1, (appsList.size + itemsPerPage - 1) / itemsPerPage)
    }
    val pagerState = rememberPagerState(pageCount = { pagesCount })

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(
                androidx.compose.ui.graphics.Brush.verticalGradient(
                    colors = listOf(
                        Color(0xFF151821), // Slate/Gris azulado oscuro
                        Color(0xFF090A0F)  // Negro pizarra
                    )
                )
            )
    ) {
        // ─── BARRA DE ESTADO PERSONALIZADA ───
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .height(36.dp)
                .background(Color(0x15FFFFFF)) // Traslucidez elegante
                .padding(horizontal = 12.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Text(
                text = currentTime,
                color = Color.White,
                fontSize = 13.sp,
                fontWeight = FontWeight.Bold
            )

            Row(verticalAlignment = Alignment.CenterVertically) {
                if (isBluetoothEnabled) {
                    Icon(
                        imageVector = Icons.Filled.Bluetooth,
                        contentDescription = null,
                        tint = Color(0xFF00B0FF),
                        modifier = Modifier.size(14.dp)
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                }
                Text(
                    text = "$batteryPercent%",
                    color = Color.White,
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Medium
                )
                Spacer(modifier = Modifier.width(4.dp))
                // Indicador gráfico de batería
                Box(
                    modifier = Modifier
                        .size(width = 20.dp, height = 10.dp)
                        .clip(RoundedCornerShape(2.dp))
                        .background(Color(0xFF444444))
                ) {
                    Box(
                        modifier = Modifier
                            .fillMaxHeight()
                            .fillMaxWidth(batteryPercent / 100f)
                            .background(
                                when {
                                    batteryPercent > 50 -> Color(0xFF4CAF50)
                                    batteryPercent > 20 -> Color(0xFFFF9800)
                                    else -> Color(0xFFF44336)
                                }
                            )
                    )
                }
            }
        }

        // ─── CUADRÍCULA DE APPS ───
        if (appsList.isEmpty()) {
            Box(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth(),
                contentAlignment = Alignment.Center
            ) {
                Text("No hay aplicaciones disponibles", color = Color.Gray, fontSize = 14.sp)
            }
        } else {
            Box(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth()
            ) {
                HorizontalPager(
                    state = pagerState,
                    modifier = Modifier.fillMaxSize()
                ) { pageIndex ->
                    val startIdx = pageIndex * itemsPerPage
                    val pageItems = appsList.subList(
                        startIdx.coerceAtMost(appsList.size),
                        (startIdx + itemsPerPage).coerceAtMost(appsList.size)
                    )

                    Column(
                        modifier = Modifier
                            .fillMaxSize()
                            .padding(vertical = 16.dp),
                        verticalArrangement = Arrangement.SpaceEvenly
                    ) {
                        for (row in 0..1) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceEvenly
                            ) {
                                for (col in 0..1) {
                                    val index = row * 2 + col
                                    if (index < pageItems.size) {
                                        AppLauncherItem(app = pageItems[index])
                                    } else {
                                        // Espaciador vacío para mantener la cuadrícula simétrica
                                        Spacer(modifier = Modifier.size(width = 110.dp, height = 110.dp))
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }

        // ─── INDICADOR DE PÁGINAS ───
        if (pagesCount > 1) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(bottom = 12.dp),
                horizontalArrangement = Arrangement.Center,
                verticalAlignment = Alignment.CenterVertically
            ) {
                repeat(pagesCount) { i ->
                    val active = pagerState.currentPage == i
                    Box(
                        modifier = Modifier
                            .size(if (active) 8.dp else 6.dp)
                            .clip(CircleShape)
                            .background(if (active) Color(0xFFFF9100) else Color.DarkGray)
                    )
                    if (i < pagesCount - 1) {
                        Spacer(modifier = Modifier.width(6.dp))
                    }
                }
            }
        }
    }
}

@Composable
fun AppLauncherItem(app: AppItem) {
    val context = LocalContext.current
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = Modifier
            .width(110.dp)
            .clickable {
                try {
                    context.startActivity(app.launchIntent)
                } catch (e: Exception) {
                    Toast
                        .makeText(context, "Error abriendo ${app.label}", Toast.LENGTH_SHORT)
                        .show()
                }
            }
            .padding(vertical = 4.dp)
    ) {
        Card(
            modifier = Modifier.size(72.dp),
            shape = RoundedCornerShape(20.dp),
            colors = CardDefaults.cardColors(containerColor = Color.Transparent)
        ) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(
                        brush = Brush.verticalGradient(
                            colors = listOf(
                                app.kosherIcon.gradientStart,
                                app.kosherIcon.gradientEnd
                            )
                        )
                    ),
                contentAlignment = Alignment.Center
            ) {
                if (app.kosherIcon.isCustom) {
                    // Icono vectorial minimalista
                    Icon(
                        imageVector = app.kosherIcon.icon,
                        contentDescription = app.label,
                        tint = Color.White,
                        modifier = Modifier.size(36.dp)
                    )
                } else {
                    // Icono nativo de la app (apps no mapeadas)
                    val pm = context.packageManager
                    val iconBitmap = remember(app.packageName) {
                        try {
                            app.resolveInfo?.loadIcon(pm)?.toBitmap()
                        } catch (_: Exception) {
                            null
                        }
                    }
                    if (iconBitmap != null) {
                        Image(
                            bitmap = iconBitmap.asImageBitmap(),
                            contentDescription = app.label,
                            modifier = Modifier.size(38.dp)
                        )
                    } else {
                        Icon(
                            imageVector = Icons.Filled.Android,
                            contentDescription = app.label,
                            tint = Color.White,
                            modifier = Modifier.size(36.dp)
                        )
                    }
                }
            }
        }
        Spacer(modifier = Modifier.height(6.dp))
        Text(
            text = app.label,
            color = Color.White,
            fontSize = 11.sp,
            fontWeight = FontWeight.Medium,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            textAlign = TextAlign.Center,
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 4.dp)
        )
    }
}
