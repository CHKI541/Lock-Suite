package com.ejemplo.locksuite.ui.dashboard

import android.content.ComponentName
import android.content.Context
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.ejemplo.locksuite.mdm.AppController
import com.ejemplo.locksuite.mdm.InstalledAppInfo
import com.ejemplo.locksuite.mdm.PolicyManager
import com.ejemplo.locksuite.security.SessionManager
import com.ejemplo.locksuite.ui.kiosk.KioskLauncherActivity
import com.ejemplo.locksuite.util.FirebaseDeviceSync
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class DashboardActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Si veníamos de modo kiosco (Lock Task), liberamos la UI del sistema
        // mientras el administrador gestiona la configuración. No es un error
        // si no estábamos en Lock Task Mode — por eso el try/catch.
        try {
            stopLockTask()
        } catch (e: Exception) {
            // Normal si no había una tarea bloqueada activa.
        }

        // Reporta el estado actual al panel remoto cada vez que se abre el Dashboard.
        FirebaseDeviceSync.syncDeviceInfo(this)

        setContent { DashboardScreen() }
    }

    override fun onUserLeaveHint() {
        super.onUserLeaveHint()
        if (!isChangingConfigurations) {
            SessionManager.endSession()
        }
    }
}

@Composable
fun DashboardScreen() {
    var selectedTab by remember { mutableStateOf(0) }
    val tabs = listOf("Políticas", "Aplicaciones", "Servicios")

    val navyDark = Color(0xFF0B192C)
    val navyMedium = Color(0xFF1E3E62)
    val accentOrange = Color(0xFFF1C40F)

    Column(modifier = Modifier.fillMaxSize().background(navyDark)) {
        Text(
            "LockSuite Kosher — Panel de administración",
            color = Color.White,
            fontSize = 18.sp,
            fontWeight = FontWeight.Bold,
            modifier = Modifier.padding(20.dp)
        )

        TabRow(
            selectedTabIndex = selectedTab,
            containerColor = navyMedium,
            contentColor = accentOrange
        ) {
            tabs.forEachIndexed { index, title ->
                Tab(
                    selected = selectedTab == index,
                    onClick = { selectedTab = index },
                    text = { Text(title) }
                )
            }
        }

        when (selectedTab) {
            0 -> PoliciesTabContent()
            1 -> AppManagerTabContent()
            2 -> ServicesTabContent()
        }
    }
}

// ────────────────────────────────────────────────────────────
// TAB 1: POLÍTICAS
// ────────────────────────────────────────────────────────────

private data class PolicyItem(
    val label: String,
    val prefKey: String,
    val onToggle: (PolicyManager, Boolean) -> Unit
)

@Composable
fun PoliciesTabContent() {
    val context = LocalContext.current
    val policyManager = remember { PolicyManager(context) }

    val policies = remember {
        listOf(
            PolicyItem("Bloquear instalación de apps", "install_apps_blocked") { pm, v -> pm.setInstallAppsBlocked(v) },
            PolicyItem("Bloquear desinstalación de apps", "uninstall_apps_blocked") { pm, v -> pm.setUninstallAppsBlocked(v) },
            PolicyItem("Bloquear configuración de Wi-Fi", "wifi_config_blocked") { pm, v -> pm.setWifiConfigBlocked(v) },
            PolicyItem("Bloquear Bluetooth", "bluetooth_blocked") { pm, v -> pm.setBluetoothBlocked(v) },
            PolicyItem("Bloquear configuración de VPN", "vpn_config_blocked") { pm, v -> pm.setVpnConfigBlocked(v) },
            PolicyItem("Bloquear tethering / anclaje", "tethering_blocked") { pm, v -> pm.setTetheringBlocked(v) },
            PolicyItem("Bloquear restablecimiento de fábrica", "factory_reset_blocked") { pm, v -> pm.setFactoryResetBlocked(v) },
            PolicyItem("Bloquear arranque en modo seguro", "safe_boot_blocked") { pm, v -> pm.setSafeBootBlocked(v) },
            PolicyItem("Bloquear depuración USB (ADB)", "adb_blocked") { pm, v -> pm.setAdbBlocked(v) },
            PolicyItem("Deshabilitar cámara", "camera_disabled") { pm, v -> pm.setCameraDisabled(v) },
            PolicyItem("Deshabilitar capturas de pantalla", "screen_capture_disabled") { pm, v -> pm.setScreenCaptureDisabled(v) }
        )
    }

    LazyColumn(
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
        modifier = Modifier.fillMaxSize()
    ) {
        items(policies, key = { it.prefKey }) { policy ->
            PolicySwitchRow(
                label = policy.label,
                checked = policyManager.isRestrictionEnabled(policy.prefKey),
                onCheckedChange = { newValue -> policy.onToggle(policyManager, newValue) }
            )
        }
    }
}

/**
 * CORRECCIÓN: la versión anterior copiaba `isChecked` a un `remember`
 * local y lo resincronizaba a mano con un `LaunchedEffect(refreshKey)`
 * externo — un patrón de estado duplicado frágil: si el valor real
 * cambiaba por otra vía sin que `refreshKey` se actualizara, el switch
 * quedaba mostrando un valor viejo. Acá se elimina la copia local: el
 * padre (la única fuente de verdad) es quien decide qué valor mostrar,
 * y el switch solo emite el evento de cambio hacia arriba.
 */
@Composable
fun PolicySwitchRow(label: String, checked: Boolean, onCheckedChange: (Boolean) -> Unit) {
    val scope = rememberCoroutineScope()
    var localOverride by remember { mutableStateOf<Boolean?>(null) }
    val displayedValue = localOverride ?: checked

    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(label, color = Color.White, fontSize = 14.sp, modifier = Modifier.weight(1f))
        Switch(
            checked = displayedValue,
            onCheckedChange = { newValue ->
                localOverride = newValue
                scope.launch {
                    onCheckedChange(newValue)
                    localOverride = null // vuelve a confiar en el valor real del padre
                }
            }
        )
    }
}

// ────────────────────────────────────────────────────────────
// TAB 2: APLICACIONES
// ────────────────────────────────────────────────────────────

@Composable
fun AppManagerTabContent() {
    val context = LocalContext.current
    val appController = remember { AppController(context) }
    val policyManager = remember { PolicyManager(context) }

    var apps by remember { mutableStateOf<List<InstalledAppInfo>>(emptyList()) }
    var isLoading by remember { mutableStateOf(true) }
    var allowedPackages by remember { mutableStateOf(policyManager.getAllowedPackages()) }

    // CORRECCIÓN: antes esto corría síncrono en el hilo principal dentro de
    // LaunchedEffect — getUserApps() recorre todas las apps instaladas y
    // decodifica cada ícono, lo cual puede trabar la UI (jank/ANR) en
    // dispositivos con muchas apps. Ahora corre en Dispatchers.IO.
    LaunchedEffect(Unit) {
        apps = withContext(Dispatchers.IO) { appController.getUserApps() }
        isLoading = false
    }

    if (isLoading) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            CircularProgressIndicator(color = Color(0xFFF1C40F))
        }
        return
    }

    LazyColumn(contentPadding = PaddingValues(16.dp), modifier = Modifier.fillMaxSize()) {
        item {
            Text(
                "Apps permitidas en modo kiosco: ${allowedPackages.size}",
                color = Color(0xFFF1C40F),
                fontSize = 13.sp,
                modifier = Modifier.padding(bottom = 12.dp)
            )
        }
        items(apps, key = { it.packageName }) { app ->
            AppRowItem(
                app = app,
                isAllowedInKiosk = allowedPackages.contains(app.packageName),
                onToggleKioskAllowed = { allow ->
                    val updated = if (allow) allowedPackages + app.packageName else allowedPackages - app.packageName
                    allowedPackages = updated
                    policyManager.setAllowedPackages(updated)
                },
                onHideToggle = { hidden -> appController.setAppHidden(app.packageName, hidden) },
                onSuspendToggle = { suspended -> appController.setAppSuspended(app.packageName, suspended) }
            )
        }
    }
}

@Composable
fun AppRowItem(
    app: InstalledAppInfo,
    isAllowedInKiosk: Boolean,
    onToggleKioskAllowed: (Boolean) -> Unit,
    onHideToggle: (Boolean) -> Unit,
    onSuspendToggle: (Boolean) -> Unit
) {
    var hidden by remember(app.packageName) { mutableStateOf(false) }
    var suspended by remember(app.packageName) { mutableStateOf(false) }

    Card(
        colors = CardDefaults.cardColors(containerColor = Color(0xFF1E3E62)),
        shape = RoundedCornerShape(12.dp),
        modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp)
    ) {
        Column(modifier = Modifier.padding(12.dp)) {
            Text(app.label, color = Color.White, fontSize = 14.sp, fontWeight = FontWeight.Bold)
            Text(app.packageName, color = Color.LightGray, fontSize = 11.sp)
            Spacer(modifier = Modifier.height(8.dp))

            StatusToggleRow("Permitida en kiosco", isAllowedInKiosk, onToggleKioskAllowed)
            StatusToggleRow("Ocultar", hidden) { hidden = it; onHideToggle(it) }
            StatusToggleRow("Suspender", suspended) { suspended = it; onSuspendToggle(it) }
        }
    }
}

@Composable
private fun StatusToggleRow(label: String, checked: Boolean, onCheckedChange: (Boolean) -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(label, color = Color.LightGray, fontSize = 12.sp)
        Switch(
            checked = checked,
            onCheckedChange = onCheckedChange,
            modifier = Modifier.height(24.dp)
        )
    }
}

// ────────────────────────────────────────────────────────────
// TAB 3: SERVICIOS
//
// CAMBIO IMPORTANTE: se eliminó por completo el switch "Modo Stealth"
// que ocultaba el ícono de la app (local y remotamente vía Firebase).
// En su lugar, esta pantalla ahora muestra el estado real de forma
// transparente — incluyendo, a propósito, que el ícono está siempre
// visible.
// ────────────────────────────────────────────────────────────

@Composable
fun ServicesTabContent() {
    val context = LocalContext.current
    val policyManager = remember { PolicyManager(context) }

    Column(modifier = Modifier.fillMaxSize().padding(20.dp)) {
        Card(
            colors = CardDefaults.cardColors(containerColor = Color(0xFF1E3E62)),
            shape = RoundedCornerShape(12.dp),
            modifier = Modifier.fillMaxWidth()
        ) {
            Column(modifier = Modifier.padding(16.dp)) {
                Text("Estado de LockSuite Kosher", color = Color(0xFFF1C40F), fontSize = 16.sp, fontWeight = FontWeight.Bold)
                Spacer(modifier = Modifier.height(12.dp))

                StatusLabelRow("Device Owner", if (policyManager.isDeviceOwnerApp()) "Activo" else "No configurado")
                StatusLabelRow("Ícono de la app", "Siempre visible")
                StatusLabelRow(
                    "Modo Kiosco",
                    if (policyManager.isKioskModeEnabled())
                        "Activo (${policyManager.getAllowedPackages().size} apps permitidas)"
                    else "No configurado"
                )
                StatusLabelRow("Servicio Watchdog", "Activo")
            }
        }

        Spacer(modifier = Modifier.height(16.dp))

        Button(
            onClick = {
                if (policyManager.isKioskModeEnabled()) {
                    policyManager.registerKioskLauncher(
                        ComponentName(context, KioskLauncherActivity::class.java)
                    )
                }
            },
            colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFF1C40F), contentColor = Color(0xFF0B192C)),
            shape = RoundedCornerShape(12.dp),
            modifier = Modifier.fillMaxWidth()
        ) {
            Text("Aplicar Modo Kiosco como pantalla de inicio", fontWeight = FontWeight.Bold)
        }

        Spacer(modifier = Modifier.height(8.dp))
        Text(
            "Configurá primero qué apps están permitidas en la pestaña \"Aplicaciones\".",
            color = Color.LightGray,
            fontSize = 12.sp
        )
    }
}

@Composable
private fun StatusLabelRow(label: String, value: String) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Text(label, color = Color.LightGray, fontSize = 13.sp)
        Text(value, color = Color.White, fontSize = 13.sp, fontWeight = FontWeight.Bold)
    }
}
