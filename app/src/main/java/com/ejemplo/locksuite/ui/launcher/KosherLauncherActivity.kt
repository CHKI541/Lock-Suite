package com.ejemplo.locksuite.ui.launcher

import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ResolveInfo
import android.os.BatteryManager
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

        setContent {
            LauncherScreen(
                batteryPercent = batteryPercent,
                isBluetoothEnabled = isBluetoothEnabled,
                currentTime = currentTime
            )
        }
    }

    override fun onResume() {
        super.onResume()
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
