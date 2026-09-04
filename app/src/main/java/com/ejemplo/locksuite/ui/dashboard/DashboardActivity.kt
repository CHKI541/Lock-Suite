package com.ejemplo.locksuite.ui.dashboard

import android.app.Activity
import android.app.admin.DevicePolicyManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.*
import androidx.compose.material3.TabRowDefaults.tabIndicatorOffset
import androidx.compose.ui.draw.scale
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.graphics.drawable.toBitmap
import com.ejemplo.locksuite.mdm.AppController
import com.ejemplo.locksuite.mdm.AppInfoData
import com.ejemplo.locksuite.mdm.PolicyManager
import com.ejemplo.locksuite.security.SessionManager
import com.ejemplo.locksuite.util.PrefsHelper
import com.ejemplo.locksuite.util.LocaleManager
import com.google.firebase.database.FirebaseDatabase
import com.ejemplo.locksuite.dns.*
import com.ejemplo.locksuite.LockSuiteApplication

class DashboardActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        
        // Proteger actividad contra capturas de pantalla/grabaciones (H12)
        window.addFlags(android.view.WindowManager.LayoutParams.FLAG_SECURE)
        
        // Verificar que la sesión esté activa, de lo contrario volver a LoginActivity
        if (!SessionManager.isActive()) {
            finish()
            return
        }

        setContent {
            DashboardScreen(
                onLogout = {
                    SessionManager.closeSession()
                    finish()
                }
            )
        }
        
        // Sincronizar estado actual a Firebase
        syncStateToFirebase()

        // Solicitar ignorar optimizaciones de batería para asegurar el watchdog (H19)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            val powerManager = getSystemService(Context.POWER_SERVICE) as android.os.PowerManager
            if (!powerManager.isIgnoringBatteryOptimizations(packageName)) {
                try {
                    @Suppress("BatteryLife")
                    val intent = Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS).apply {
                        data = Uri.parse("package:$packageName")
                    }
                    startActivity(intent)
                } catch (e: Exception) {
                    e.printStackTrace()
                }
            }
        }
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == 1024 && resultCode == Activity.RESULT_OK) {
            try {
                // 1. Iniciar servicio de la VPN
                val startServiceIntent = Intent(this, com.ejemplo.locksuite.service.KosherVpnService::class.java)
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    startForegroundService(startServiceIntent)
                } else {
                    startService(startServiceIntent)
                }
                
                // 2. Activar automáticamente el bloqueo y modo Lockdown de la VPN
                val policyManager = com.ejemplo.locksuite.mdm.PolicyManager(this)
                policyManager.setVpnConfigBlocked(true)
                
                Toast.makeText(
                    this, 
                    "VPN activa y configuración de red bloqueada automáticamente (Lockdown).", 
                    Toast.LENGTH_LONG
                ).show()
            } catch (e: Exception) {
                e.printStackTrace()
                Toast.makeText(this, "Error al configurar VPN permanente: ${e.message}", Toast.LENGTH_SHORT).show()
            }
        }
    }

    override fun onResume() {
        super.onResume()
        if (!SessionManager.isActive()) {
            finish()
        } else {
            SessionManager.updateInteraction()
        }
    }

    override fun onUserInteraction() {
        super.onUserInteraction()
        SessionManager.updateInteraction()
    }

    override fun onUserLeaveHint() {
        super.onUserLeaveHint()
        // Cerrar sesión solo cuando el usuario minimiza intencionalmente la app
        // (no cuando rota la pantalla ni cambia a otra actividad interna)
        if (!isChangingConfigurations) {
            SessionManager.closeSession()
            finish()
        }
    }

    private fun syncStateToFirebase() {
        val deviceId = Settings.Secure.getString(contentResolver, Settings.Secure.ANDROID_ID)
        val dpm = getSystemService(Context.DEVICE_POLICY_SERVICE) as DevicePolicyManager
        val adminComponent = ComponentName(this, com.ejemplo.locksuite.receiver.DeviceAdminReceiver::class.java)
        val isDeviceOwner = dpm.isDeviceOwnerApp(packageName)

        val data = mapOf(
            "model" to Build.MODEL,
            "isDeviceOwner" to isDeviceOwner,
            "lastSeen" to System.currentTimeMillis()
        )
        try {
            FirebaseDatabase.getInstance()
                .getReference("devices/$deviceId/info")
                .updateChildren(data)
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DashboardScreen(onLogout: () -> Unit) {
    val context = LocalContext.current
    var selectedTab by remember { mutableIntStateOf(0) }

    LaunchedEffect(Unit) {
        try {
            val deviceId = android.provider.Settings.Secure.getString(context.contentResolver, android.provider.Settings.Secure.ANDROID_ID)
            com.google.firebase.database.FirebaseDatabase.getInstance()
                .getReference("devices/$deviceId/deviceName")
                .get()
                .addOnSuccessListener { snap ->
                    val fbName = snap.getValue(String::class.java)
                    if (!fbName.isNullOrEmpty()) {
                        val prefs = com.ejemplo.locksuite.util.PrefsHelper.getMdmPrefs(context)
                        if (prefs.getString("device_name", "") != fbName) {
                            prefs.edit().putString("device_name", fbName).apply()
                            com.ejemplo.locksuite.util.FirebaseDeviceSync.syncDeviceInfo(context)
                        }
                    }
                }
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    var showReauthDialogForPermissions by remember { mutableStateOf(false) }
    var showReauthDialogForUninstall by remember { mutableStateOf(false) }
    var reauthPinInput by remember { mutableStateOf("") }
    var reauthError by remember { mutableStateOf("") }

    val navyDark = Color(0xFF0B192C)
    val navyMedium = Color(0xFF1E3E62)
    val accentOrange = Color(0xFFF1C40F)

    if (showReauthDialogForPermissions || showReauthDialogForUninstall) {
        val actionText = if (showReauthDialogForPermissions) "revocar todos los privilegios" else "desinstalar la aplicación"
        AlertDialog(
            onDismissRequest = {
                showReauthDialogForPermissions = false
                showReauthDialogForUninstall = false
                reauthPinInput = ""
                reauthError = ""
            },
            title = {
                Text("Re-autenticación Requerida", color = Color.White, fontSize = 18.sp, fontWeight = FontWeight.Bold)
            },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(
                        "Para $actionText, ingrese el PIN de administrador para confirmar su identidad.",
                        color = Color.LightGray,
                        fontSize = 13.sp
                    )
                    OutlinedTextField(
                        value = reauthPinInput,
                        onValueChange = {
                            reauthPinInput = it
                            reauthError = ""
                        },
                        label = { Text("PIN de Administrador", color = Color.White.copy(alpha = 0.8f)) },
                        visualTransformation = androidx.compose.ui.text.input.PasswordVisualTransformation(),
                        singleLine = true,
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedBorderColor = accentOrange,
                            unfocusedBorderColor = Color.White.copy(alpha = 0.5f),
                            focusedLabelColor = accentOrange,
                            unfocusedLabelColor = Color.White,
                            focusedTextColor = Color.White,
                            unfocusedTextColor = Color.White
                        ),
                        modifier = Modifier.fillMaxWidth()
                    )
                    if (reauthError.isNotEmpty()) {
                        Text(reauthError, color = Color.Red, fontSize = 12.sp, fontWeight = FontWeight.Bold)
                    }
                }
            },
            confirmButton = {
                Button(
                    onClick = {
                        if (com.ejemplo.locksuite.security.PinManager.verifyPin(context, reauthPinInput)) {
                            com.ejemplo.locksuite.security.PinManager.resetAttempts(context)
                            val dpm = context.getSystemService(Context.DEVICE_POLICY_SERVICE) as android.app.admin.DevicePolicyManager
                            val pm = context.packageManager
                            val adminComponent = ComponentName(context, com.ejemplo.locksuite.receiver.DeviceAdminReceiver::class.java)
                            val aliasComponent = ComponentName(context, "com.ejemplo.locksuite.LauncherAlias")

                            if (showReauthDialogForPermissions) {
                                showReauthDialogForPermissions = false
                                try {
                                    val policyManager = com.ejemplo.locksuite.mdm.PolicyManager(context)
                                    policyManager.clearAllRestrictions()
                                    pm.setComponentEnabledSetting(
                                        aliasComponent,
                                        PackageManager.COMPONENT_ENABLED_STATE_ENABLED,
                                        PackageManager.DONT_KILL_APP
                                    )
                                    if (dpm.isDeviceOwnerApp(context.packageName)) {
                                        dpm.clearDeviceOwnerApp(context.packageName)
                                    }
                                    dpm.removeActiveAdmin(adminComponent)
                                    com.ejemplo.locksuite.security.SessionManager.closeSession()
                                    Toast.makeText(context, "Permisos MDM revocados con éxito.", Toast.LENGTH_LONG).show()
                                    (context as? Activity)?.finishAffinity()
                                } catch (e: Exception) {
                                    e.printStackTrace()
                                    Toast.makeText(context, "Error: ${e.message}", Toast.LENGTH_LONG).show()
                                }
                            } else if (showReauthDialogForUninstall) {
                                showReauthDialogForUninstall = false
                                try {
                                    val policyManager = com.ejemplo.locksuite.mdm.PolicyManager(context)
                                    policyManager.clearAllRestrictions()
                                    pm.setComponentEnabledSetting(
                                        aliasComponent,
                                        PackageManager.COMPONENT_ENABLED_STATE_ENABLED,
                                        PackageManager.DONT_KILL_APP
                                    )
                                    if (dpm.isDeviceOwnerApp(context.packageName)) {
                                        dpm.clearDeviceOwnerApp(context.packageName)
                                    }
                                    dpm.removeActiveAdmin(adminComponent)
                                    com.ejemplo.locksuite.security.SessionManager.closeSession()
                                    @Suppress("DEPRECATION")
                                    val uninstallIntent = Intent(Intent.ACTION_UNINSTALL_PACKAGE).apply {
                                        data = Uri.parse("package:${context.packageName}")
                                        putExtra(Intent.EXTRA_RETURN_RESULT, true)
                                    }
                                    context.startActivity(uninstallIntent)
                                    (context as? Activity)?.finish()
                                } catch (e: Exception) {
                                    e.printStackTrace()
                                    Toast.makeText(context, "Error: ${e.message}", Toast.LENGTH_LONG).show()
                                }
                            }
                            reauthPinInput = ""
                        } else {
                            reauthPinInput = ""
                            when (val status = com.ejemplo.locksuite.security.PinManager.recordFailedAttempt(context)) {
                                is com.ejemplo.locksuite.security.LockoutStatus.LockedOut -> {
                                    showReauthDialogForPermissions = false
                                    showReauthDialogForUninstall = false
                                    com.ejemplo.locksuite.security.SessionManager.closeSession()
                                    (context as? Activity)?.finish()
                                }
                                is com.ejemplo.locksuite.security.LockoutStatus.Warning -> {
                                    reauthError = "PIN incorrecto. Intentos restantes: ${status.remainingAttempts}"
                                }
                            }
                        }
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = accentOrange, contentColor = navyDark)
                ) {
                    Text("Confirmar", fontWeight = FontWeight.Bold)
                }
            },
            dismissButton = {
                TextButton(
                    onClick = {
                        showReauthDialogForPermissions = false
                        showReauthDialogForUninstall = false
                        reauthPinInput = ""
                        reauthError = ""
                    }
                ) {
                    Text("Cancelar", color = Color.White)
                }
            },
            containerColor = navyMedium
        )
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { 
                    Text(
                        LocaleManager.t("Panel de Administración"), 
                        color = Color.White,
                        fontWeight = FontWeight.Bold,
                        fontSize = 20.sp
                    ) 
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = navyDark,
                    titleContentColor = Color.White
                ),
                actions = {
                    IconButton(onClick = onLogout) {
                        Icon(
                            imageVector = Icons.Default.Lock,
                            contentDescription = "Cerrar Sesión",
                            tint = accentOrange
                        )
                    }
                }
            )
        },
        containerColor = navyDark
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
        ) {
            ScrollableTabRow(
                selectedTabIndex = selectedTab,
                containerColor = navyDark,
                contentColor = accentOrange,
                edgePadding = 0.dp,
                indicator = { tabPositions ->
                    TabRowDefaults.SecondaryIndicator(
                        modifier = Modifier.tabIndicatorOffset(tabPositions[selectedTab]),
                        color = accentOrange
                    )
                }
            ) {
                Tab(
                    selected = selectedTab == 0,
                    onClick = { selectedTab = 0 },
                    text = { Text(LocaleManager.t("Políticas"), color = if (selectedTab == 0) accentOrange else Color.Gray) }
                )
                Tab(
                    selected = selectedTab == 1,
                    onClick = { selectedTab = 1 },
                    text = { Text(LocaleManager.t("Aplicaciones"), color = if (selectedTab == 1) accentOrange else Color.Gray) }
                )
                Tab(
                    selected = selectedTab == 2,
                    onClick = { selectedTab = 2 },
                    text = { Text(LocaleManager.t("Servicios"), color = if (selectedTab == 2) accentOrange else Color.Gray) }
                )
                Tab(
                    selected = selectedTab == 3,
                    onClick = { selectedTab = 3 },
                    text = { Text(LocaleManager.t("Presets"), color = if (selectedTab == 3) accentOrange else Color.Gray) }
                )
                Tab(
                    selected = selectedTab == 4,
                    onClick = { selectedTab = 4 },
                    text = { Text(LocaleManager.t("DNS"), color = if (selectedTab == 4) accentOrange else Color.Gray) }
                )
            }

            when (selectedTab) {
                0 -> PoliciesTabContent(context)
                1 -> AppManagerTabContent(context)
                2 -> ServicesTabContent(
                    context = context,
                    onTriggerPermissionsReauth = { showReauthDialogForPermissions = true },
                    onTriggerUninstallReauth = { showReauthDialogForUninstall = true }
                )
                3 -> PresetsTabContent(context)
                4 -> DnsActivityTabContent(context)
            }
        }
    }
}

@Composable
fun PoliciesTabContent(context: Context) {
    val policyManager = remember { PolicyManager(context) }
    var refreshKey by remember { mutableIntStateOf(0) }
    
    val prefs = remember { com.ejemplo.locksuite.util.PrefsHelper.getMdmPrefs(context) }
    var deviceNameInput by remember { mutableStateOf(prefs.getString("device_name", "") ?: "") }

    val isSuspendedNow = remember(refreshKey) { policyManager.isLockSuiteSuspended() }
    val suspendedSince = remember(refreshKey) { policyManager.getLockSuiteSuspendedAt() }
    var pendingSuspendConfirm by remember { mutableStateOf(false) }

    val dpm = context.getSystemService(Context.DEVICE_POLICY_SERVICE) as DevicePolicyManager
    val isDeviceOwner = dpm.isDeviceOwnerApp(context.packageName)

    val launcher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.GetContent()
    ) { uri: Uri? ->
        uri?.let {
            val error = com.ejemplo.locksuite.util.ApkInstaller.installApk(context, it)
            if (error != null) {
                Toast.makeText(context, error, Toast.LENGTH_LONG).show()
            } else {
                Toast.makeText(context, "Iniciando instalación programática...", Toast.LENGTH_SHORT).show()
            }
        }
    }

    LaunchedEffect(refreshKey) {
        try {
            com.ejemplo.locksuite.util.FirebaseDeviceSync.syncDeviceInfo(context)
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        // ──────────────────────────────────────────────
        // Suspensión temporal de LockSuite
        //
        // Va primero y con colores propios a propósito: es el único interruptor
        // de esta pantalla que apaga TODO lo demás, y un equipo que quedó
        // suspendido por olvido es un equipo sin ninguna protección.
        // ──────────────────────────────────────────────
        item {
            Card(
                colors = CardDefaults.cardColors(
                    containerColor = if (isSuspendedNow) Color(0xFF7F3B12) else Color(0xFF1E3E62)
                ),
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(12.dp)
            ) {
                Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text(
                        if (isSuspendedNow) "⚠ LockSuite SUSPENDIDO" else "Suspender LockSuite",
                        color = if (isSuspendedNow) Color.White else Color(0xFFF1C40F),
                        fontWeight = FontWeight.Bold,
                        fontSize = 16.sp
                    )
                    Text(
                        if (isSuspendedNow)
                            "Todas las restricciones están levantadas y todas las aplicaciones desbloqueadas. El equipo está funcionando como si LockSuite no estuviera instalado — incluido poder desinstalarlo o restaurarlo de fábrica. Desactivá la suspensión para que todo vuelva exactamente a como estaba."
                        else
                            "Levanta temporalmente TODAS las restricciones y desbloquea todas las aplicaciones, como si LockSuite no estuviera instalado. Al desactivarla, todo vuelve exactamente a como estaba: no hace falta reconfigurar nada.",
                        color = Color.White.copy(alpha = 0.8f),
                        fontSize = 12.sp
                    )
                    if (isSuspendedNow && suspendedSince > 0L) {
                        Text(
                            "Suspendido desde: " + java.text.SimpleDateFormat("dd/MM/yyyy HH:mm", java.util.Locale.getDefault())
                                .format(java.util.Date(suspendedSince)),
                            color = Color.White.copy(alpha = 0.7f),
                            fontSize = 11.sp
                        )
                    }
                    PolicySwitchRow(
                        label = "Suspensión activa",
                        isChecked = isSuspendedNow,
                        onCheckedChange = { wantSuspend ->
                            if (wantSuspend) {
                                // Confirmación obligatoria: es destructivo en el
                                // sentido de que deja el equipo completamente abierto.
                                pendingSuspendConfirm = true
                                false // el switch se mueve recién si el admin confirma
                            } else {
                                val ok = policyManager.setLockSuiteSuspended(false)
                                if (ok) {
                                    Toast.makeText(context, "Suspensión desactivada. Restricciones restauradas.", Toast.LENGTH_LONG).show()
                                }
                                refreshKey++
                                ok
                            }
                        }
                    )
                }
            }
        }

        item {
            Card(
                colors = CardDefaults.cardColors(containerColor = Color(0xFF1E3E62)),
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(12.dp)
            ) {
                Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text("Identificación del Dispositivo", color = Color(0xFFF1C40F), fontWeight = FontWeight.Bold, fontSize = 16.sp)
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        OutlinedTextField(
                            value = deviceNameInput,
                            onValueChange = { deviceNameInput = it },
                            placeholder = { Text("Nombre del dispositivo", color = Color.Gray) },
                            singleLine = true,
                            colors = OutlinedTextFieldDefaults.colors(
                                focusedBorderColor = Color(0xFFF1C40F),
                                unfocusedBorderColor = Color.White.copy(alpha = 0.5f),
                                focusedTextColor = Color.White,
                                unfocusedTextColor = Color.White
                            ),
                            modifier = Modifier.weight(1f)
                        )
                        Button(
                            onClick = {
                                // pushDeviceName() en vez de "guardar la preferencia y
                                // sincronizar": desde el 2/9/2026 la sincronización normal
                                // ya NO manda el nombre (mandarlo era lo que borraba el
                                // nombre puesto desde el panel — ver
                                // FirebaseDeviceSync.reconcileDeviceName). Este botón es
                                // el único lugar donde el celular tiene que ganarle al
                                // panel, así que usa la vía explícita.
                                com.ejemplo.locksuite.util.FirebaseDeviceSync.pushDeviceName(context, deviceNameInput)
                                android.widget.Toast.makeText(context, "Nombre guardado con éxito", android.widget.Toast.LENGTH_SHORT).show()
                            },
                            colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFF1C40F), contentColor = Color(0xFF0B192C))
                        ) {
                            Text("Guardar", fontWeight = FontWeight.Bold)
                        }
                    }
                }
            }
        }

        item {
            Card(
                colors = CardDefaults.cardColors(containerColor = Color(0xFF1E3E62)),
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(12.dp)
            ) {
                Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text("Instalar / Actualizar APK Permitida", color = Color(0xFFF1C40F), fontWeight = FontWeight.Bold, fontSize = 16.sp)
                    Text(
                        "Si tienes el archivo APK de una aplicación permitida en tu almacenamiento, puedes seleccionarlo aquí para instalarlo o actualizarlo, incluso si el bloqueo de APKs está activo.",
                        color = Color.White.copy(alpha = 0.7f),
                        fontSize = 12.sp
                    )
                    Button(
                        onClick = {
                            try {
                                launcher.launch("application/vnd.android.package-archive")
                            } catch (e: Exception) {
                                Toast.makeText(context, "Error al abrir selector de archivos: ${e.message}", Toast.LENGTH_LONG).show()
                            }
                        },
                        colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFF1C40F), contentColor = Color(0xFF0B192C)),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text("Seleccionar e Instalar APK", fontWeight = FontWeight.Bold)
                    }
                }
            }
        }

        item {
            Card(
                colors = CardDefaults.cardColors(containerColor = Color(0xFF1E3E62)),
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(12.dp)
            ) {
                Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text("Actualizar LockSuite", color = Color(0xFFF1C40F), fontWeight = FontWeight.Bold, fontSize = 16.sp)
                    Text(
                        "Comprueba si hay una nueva versión del sistema LockSuite MDM e instálala de forma inmediata.",
                        color = Color.White.copy(alpha = 0.7f),
                        fontSize = 12.sp
                    )
                    val coroutineScope = rememberCoroutineScope()
                    Button(
                        onClick = {
                            coroutineScope.launch {
                                val error = com.ejemplo.locksuite.util.SelfUpdater.checkAndPerformUpdate(context, true)
                                if (error != null) {
                                    Toast.makeText(context, error, Toast.LENGTH_LONG).show()
                                }
                            }
                        },
                        colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFF1C40F), contentColor = Color(0xFF0B192C)),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text("Buscar Actualizaciones", fontWeight = FontWeight.Bold)
                    }
                }
            }
        }

        if (!isDeviceOwner) {
            item {
                Card(
                    colors = CardDefaults.cardColors(containerColor = Color(0xFF7B241C)),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Row(
                        modifier = Modifier.padding(16.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(Icons.Default.Info, "Advertencia", tint = Color.White)
                        Spacer(modifier = Modifier.width(12.dp))
                        Text(
                            "ADVERTENCIA: La app no está configurada como Device Owner. Configure mediante ADB para activar los bloqueos.",
                            color = Color.White,
                            fontSize = 13.sp,
                            fontWeight = FontWeight.Bold
                        )
                    }
                }
            }
        }

        // Grupo 1: Políticas de Sistema
        item {
            PolicyGroupCard(title = "Políticas de Sistema (Device Owner)") {
                PolicySwitchRow(
                    label = "Bloquear Restauración de Fábrica (Ajustes + recovery en Samsung/Knox)",
                    isChecked = remember(refreshKey) { policyManager.isRestrictionEnabled(android.os.UserManager.DISALLOW_FACTORY_RESET) },
                    onCheckedChange = { policyManager.setFactoryResetBlocked(it).also { refreshKey++ } }
                )
                PolicySwitchRow(
                    label = "Bloquear Flasheo (Odin / Download mode — solo Samsung/Knox)",
                    isChecked = remember(refreshKey) { policyManager.isFlashingBlocked() },
                    onCheckedChange = { policyManager.setFlashingBlocked(it).also { refreshKey++ } }
                )
                PolicySwitchRow(
                    label = "Bloquear Instalación de Apps",
                    isChecked = remember(refreshKey) { policyManager.isInstallAppsBlocked() },
                    onCheckedChange = { policyManager.setInstallAppsBlocked(it).also { refreshKey++ } }
                )
                PolicySwitchRow(
                    label = "Bloquear Desinstalación de Apps",
                    isChecked = remember(refreshKey) { policyManager.isRestrictionEnabled(android.os.UserManager.DISALLOW_UNINSTALL_APPS) },
                    onCheckedChange = { policyManager.setUninstallAppsBlocked(it).also { refreshKey++ } }
                )
                PolicySwitchRow(
                    label = "Bloquear ADB y Opciones de Desarrollador",
                    isChecked = remember(refreshKey) { policyManager.isRestrictionEnabled(android.os.UserManager.DISALLOW_DEBUGGING_FEATURES) },
                    onCheckedChange = { policyManager.setDebuggingFeaturesBlocked(it).also { refreshKey++ } }
                )
                PolicySwitchRow(
                    label = "Bloquear Cambio de Usuario",
                    isChecked = remember(refreshKey) { policyManager.isRestrictionEnabled(android.os.UserManager.DISALLOW_USER_SWITCH) },
                    onCheckedChange = { policyManager.setUserSwitchBlocked(it).also { refreshKey++ } }
                )
                PolicySwitchRow(
                    label = "Bloquear Modificación de Cuentas",
                    isChecked = remember(refreshKey) { policyManager.isRestrictionEnabled(android.os.UserManager.DISALLOW_MODIFY_ACCOUNTS) },
                    onCheckedChange = { policyManager.setModifyAccountsBlocked(it).also { refreshKey++ } }
                )
                PolicySwitchRow(
                    label = "Bloquear Reinicio Seguro (Safe Boot)",
                    isChecked = remember(refreshKey) { policyManager.isRestrictionEnabled(android.os.UserManager.DISALLOW_SAFE_BOOT) },
                    onCheckedChange = { policyManager.setSafeBootBlocked(it).also { refreshKey++ } }
                )
                PolicySwitchRow(
                    label = "Bloquear Orígenes Desconocidos (APK)",
                    isChecked = remember(refreshKey) { policyManager.isRestrictionEnabled(android.os.UserManager.DISALLOW_INSTALL_UNKNOWN_SOURCES) },
                    onCheckedChange = { policyManager.setUnknownSourcesBlocked(it).also { refreshKey++ } }
                )
                PolicySwitchRow(
                    label = "Bloquear Ajustes de Red / WiFi / Datos",
                    isChecked = remember(refreshKey) { policyManager.isRestrictionEnabled(android.os.UserManager.DISALLOW_CONFIG_WIFI) },
                    onCheckedChange = { policyManager.setWifiConfigBlocked(it).also { refreshKey++ } }
                )
            }
        }

        // Grupo 2: Control de Hardware y Pantalla
        item {
            PolicyGroupCard(title = "Control de Hardware y Pantalla") {
                PolicySwitchRow(
                    label = "Deshabilitar Cámara Física",
                    isChecked = remember(refreshKey) { policyManager.isCameraDisabled() },
                    onCheckedChange = { policyManager.setCameraDisabled(it).also { refreshKey++ } }
                )
                PolicySwitchRow(
                    label = "Bloquear Capturas de Pantalla (Screenshots)",
                    isChecked = remember(refreshKey) { policyManager.isScreenCaptureBlocked() },
                    onCheckedChange = { policyManager.setScreenCaptureBlocked(it).also { refreshKey++ } }
                )
                PolicySwitchRow(
                    label = "Bloquear Barra de Notificaciones (Android 9+)",
                    isChecked = remember(refreshKey) { policyManager.isStatusBarDisabled() },
                    onCheckedChange = { policyManager.setStatusBarDisabled(it).also { refreshKey++ } }
                )
                PolicySwitchRow(
                    label = "Activar Modo Launcher MP3 Kosher",
                    isChecked = remember(refreshKey) { policyManager.isKosherLauncherEnabled() },
                    onCheckedChange = { isChecked ->
                        if (isChecked && !android.provider.Settings.canDrawOverlays(context)) {
                            try {
                                val intent = Intent(
                                    android.provider.Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                                    Uri.parse("package:${context.packageName}")
                                )
                                context.startActivity(intent)
                                Toast.makeText(context, "Por favor, concede el permiso de superposición para la marca de agua", Toast.LENGTH_LONG).show()
                            } catch (e: Exception) {
                                try {
                                    val intent = Intent(android.provider.Settings.ACTION_MANAGE_OVERLAY_PERMISSION)
                                    context.startActivity(intent)
                                } catch (ex: Exception) {
                                    ex.printStackTrace()
                                }
                            }
                        }
                        policyManager.setKosherLauncherEnabled(isChecked).also { refreshKey++ }
                    }
                )
                PolicySwitchRow(
                    label = "Deshabilitar Pantalla de Bloqueo (Keyguard)",
                    isChecked = remember(refreshKey) { policyManager.isKeyguardDisabled() },
                    onCheckedChange = { policyManager.setKeyguardDisabled(it).also { refreshKey++ } }
                )

                PolicySwitchRow(
                    label = "Bloquear Ajustes de Volumen",
                    isChecked = remember(refreshKey) { policyManager.isRestrictionEnabled(android.os.UserManager.DISALLOW_ADJUST_VOLUME) },
                    onCheckedChange = { policyManager.setAdjustVolumeBlocked(it).also { refreshKey++ } }
                )
                PolicySwitchRow(
                    label = "Bloquear Controles de Aplicación (Ajustes)",
                    isChecked = remember(refreshKey) { policyManager.isRestrictionEnabled(android.os.UserManager.DISALLOW_APPS_CONTROL) },
                    onCheckedChange = { policyManager.setAppsControlBlocked(it).also { refreshKey++ } }
                )
            }
        }

        // Grupo 3: Control de Conectividad
        item {
            PolicyGroupCard(title = "Control de Conectividad") {
                PolicySwitchRow(
                    label = "Bloquear Bluetooth",
                    isChecked = remember(refreshKey) { policyManager.isRestrictionEnabled(android.os.UserManager.DISALLOW_BLUETOOTH) },
                    onCheckedChange = { policyManager.setBluetoothBlocked(it).also { refreshKey++ } }
                )
                PolicySwitchRow(
                    label = "Bloquear Envío de Archivos Bluetooth",
                    isChecked = remember(refreshKey) { policyManager.isRestrictionEnabled(android.os.UserManager.DISALLOW_BLUETOOTH_SHARING) },
                    onCheckedChange = { policyManager.setBluetoothSharingBlocked(it).also { refreshKey++ } }
                )
                PolicySwitchRow(
                    label = "Bloquear Medios Externos (USB OTG/SD)",
                    isChecked = remember(refreshKey) { policyManager.isRestrictionEnabled(android.os.UserManager.DISALLOW_MOUNT_PHYSICAL_MEDIA) },
                    onCheckedChange = { policyManager.setExternalMediaBlocked(it).also { refreshKey++ } }
                )
                PolicySwitchRow(
                    label = "Bloquear Zona WiFi / Compartir Internet",
                    isChecked = remember(refreshKey) { policyManager.isRestrictionEnabled(android.os.UserManager.DISALLOW_CONFIG_TETHERING) },
                    onCheckedChange = { policyManager.setTetheringBlocked(it).also { refreshKey++ } }
                )
                PolicySwitchRow(
                    label = "Bloquear Configuración de VPN",
                    isChecked = remember(refreshKey) { policyManager.isRestrictionEnabled(android.os.UserManager.DISALLOW_CONFIG_VPN) },
                    onCheckedChange = { policyManager.setVpnConfigBlocked(it).also { refreshKey++ } }
                )
                PolicySwitchRow(
                    label = "Bloquear Internet Completo (WiFi y Datos)",
                    isChecked = remember(refreshKey) { policyManager.isInternetBlocked() },
                    onCheckedChange = { policyManager.setInternetBlocked(it).also { refreshKey++ } }
                )
                PolicySwitchRow(
                    label = "Deshabilitar Navegadores de Internet (Chrome, Firefox, etc.)",
                    isChecked = remember(refreshKey) { policyManager.areBrowsersSuspended() },
                    onCheckedChange = { policyManager.setBrowsersSuspended(it).also { refreshKey++ } }
                )
                PolicySwitchRow(
                    label = "Deshabilitar WebView del Sistema (Bloqueo Global)",
                    isChecked = remember(refreshKey) { policyManager.isSystemWebViewSuspended() },
                    onCheckedChange = { policyManager.setSystemWebViewSuspended(it).also { refreshKey++ } }
                )
                PolicySwitchRow(
                    label = "Bloquear Anuncios en todo el Dispositivo (Global)",
                    isChecked = remember(refreshKey) { policyManager.isAdBlockingEnabled() },
                    onCheckedChange = { policyManager.setAdBlockingEnabled(it).also { refreshKey++ } }
                )
                PolicySwitchRow(
                    label = "Bloquear GIFs y Stickers en Gboard (Tenor)",
                    isChecked = remember(refreshKey) { policyManager.isGifsBlocked() },
                    onCheckedChange = { policyManager.setGifsBlocked(it).also { refreshKey++ } }
                )
            }
        }

        // ─────────────────────────────────────────────────────────────────────
        // Grupo 3-bis: PROTECCIONES DE ACCESIBILIDAD  (17/8/2026)
        //
        // La Capa 3 (filtro visual: imágenes, Estados/Canales de WhatsApp, ofertas
        // de Mercado Pago) depende del servicio de Accesibilidad, y ese servicio se
        // puede apagar desde Ajustes del sistema. Android NO ofrece ninguna API para
        // impedirlo — no existe `DISALLOW_CONFIG_ACCESSIBILITY` y un Device Owner no
        // puede escribir `ENABLED_ACCESSIBILITY_SERVICES`. Es deliberado de Google.
        //
        // Estos cuatro interruptores son las cuatro barreras posibles. Van apagados
        // por defecto porque cada uno tiene un costo de comodidad distinto; el
        // administrador elige cuánto pagar. El interruptor maestro es "Protección de
        // Accesibilidad" (`accessibility_protection_enabled`, encendido por defecto):
        // con ese apagado, ninguno de estos hace nada.
        // ─────────────────────────────────────────────────────────────────────
        item {
            PolicyGroupCard(title = "Protecciones de Accesibilidad") {
                // Va PRIMERO porque es la más importante de la sección: sin esto,
                // cambiar el idioma del equipo deja mudo a cualquier filtro que compare
                // texto de pantalla (ofertas de Mercado Pago, anti-evasión de Ajustes).
                PolicySwitchRow(
                    label = "Bloquear cambio de idioma del sistema",
                    isChecked = remember(refreshKey) { policyManager.isLocaleChangeBlocked() },
                    onCheckedChange = { policyManager.setLocaleChangeBlocked(it).also { refreshKey++ } }
                )
                // 4/9/2026 — el agujero que encontró el dueño: el historial de YouTube
                // y Mi Actividad se ven DENTRO de Ajustes, sin ningún navegador.
                // Es el único de esta sección que viene ENCENDIDO de fábrica: lo que
                // abre no tiene uso legítimo en un equipo kosher, y un interruptor
                // apagado por defecto solo protege a quien se acuerde de encenderlo.
                PolicySwitchRow(
                    label = "Bloquear ajustes de la cuenta de Google (historial y actividad)",
                    isChecked = remember(refreshKey) { policyManager.isGoogleAccountWebBlocked() },
                    onCheckedChange = { policyManager.setGoogleAccountWebBlocked(it).also { refreshKey++ } }
                )
                PolicySwitchRow(
                    label = "Bloquear selector de fotos e ilustraciones de contactos",
                    isChecked = remember(refreshKey) { policyManager.isContactPhotoPickerBlocked() },
                    onCheckedChange = { policyManager.setContactPhotoPickerBlocked(it).also { refreshKey++ } }
                )
                PolicySwitchRow(
                    label = "Protección de Accesibilidad (maestro)",
                    isChecked = remember(refreshKey) { policyManager.isAccessibilityProtectionEnabled() },
                    onCheckedChange = { policyManager.setAccessibilityProtection(it).also { refreshKey++ } }
                )
                PolicySwitchRow(
                    label = "Rebotar el menú de Accesibilidad de Ajustes",
                    isChecked = remember(refreshKey) { policyManager.isAccBounceSettingsEnabled() },
                    onCheckedChange = { policyManager.setAccBounceSettings(it).also { refreshKey++ } }
                )
                PolicySwitchRow(
                    label = "Aviso insistente si la Accesibilidad está apagada",
                    isChecked = remember(refreshKey) { policyManager.isAccNagEnabled() },
                    onCheckedChange = { policyManager.setAccNag(it).also { refreshKey++ } }
                )
                PolicySwitchRow(
                    label = "Suspender TODAS las apps mientras esté apagada",
                    isChecked = remember(refreshKey) { policyManager.isAccSuspendAllEnabled() },
                    onCheckedChange = { policyManager.setAccSuspendAll(it).also { refreshKey++ } }
                )
                PolicySwitchRow(
                    label = "Arranque protegido: esperar también a la Accesibilidad",
                    isChecked = remember(refreshKey) { policyManager.isBootGateWaitAccessibilityEnabled() },
                    onCheckedChange = { policyManager.setBootGateWaitAccessibility(it).also { refreshKey++ } }
                )
            }
        }

        // ─────────────────────────────────────────────────────────────────────
        // Grupo 3-ter: ARRANQUE PROTEGIDO Y FILTRO VISUAL  (17/8/2026)
        // ─────────────────────────────────────────────────────────────────────
        item {
            PolicyGroupCard(title = "Arranque protegido y filtro visual") {
                PolicySwitchRow(
                    label = "Arranque protegido (cerrar la red hasta que el filtro esté listo)",
                    isChecked = remember(refreshKey) { policyManager.isBootGateEnabled() },
                    onCheckedChange = { policyManager.setBootGateEnabled(it).also { refreshKey++ } }
                )
                PolicySwitchRow(
                    label = "Imágenes: tapado estricto al desplazar",
                    isChecked = remember(refreshKey) { policyManager.isImageBlockStrictScrollEnabled() },
                    onCheckedChange = { policyManager.setImageBlockStrictScroll(it).also { refreshKey++ } }
                )
            }
        }

        // Grupo 4: Opciones Avanzadas de Aplicaciones y Mercado Pago
        item {
            PolicyGroupCard(title = "Opciones Avanzadas de Aplicaciones y Mercado Pago") {
                PolicySwitchRow(
                    label = "Ocultar icono al suspender aplicaciones",
                    isChecked = remember(refreshKey) { policyManager.isHideSuspendedApps() },
                    onCheckedChange = { 
                        policyManager.setHideSuspendedApps(it)
                        refreshKey++
                        true
                    }
                )
                PolicySwitchRow(
                    label = "Mercado Pago: Bloquear Ofertas (por Accesibilidad)",
                    isChecked = remember(refreshKey) { policyManager.isMercadoPagoBlockOffersAccessibilityEnabled() },
                    onCheckedChange = { 
                        policyManager.setMercadoPagoBlockOffersAccessibility(it)
                        refreshKey++
                        true
                    }
                )
                PolicySwitchRow(
                    label = "Mercado Pago: Bloquear Ofertas (por VPN DNS)",
                    isChecked = remember(refreshKey) { policyManager.isMercadoPagoBlockOffersVpnEnabled() },
                    onCheckedChange = { 
                        policyManager.setMercadoPagoBlockOffersVpn(it)
                        refreshKey++
                        true
                    }
                )
                PolicySwitchRow(
                    label = "Bloqueo de Mercado Libre en Mercado Pago",
                    isChecked = remember(refreshKey) { policyManager.isMercadoLibreInMpBlocked() },
                    onCheckedChange = { 
                        policyManager.setMercadoLibreInMpBlocked(it)
                        refreshKey++
                        true
                    }
                )
            }
        }
    }

    if (pendingSuspendConfirm) {
        AlertDialog(
            onDismissRequest = { pendingSuspendConfirm = false },
            containerColor = Color(0xFF0B192C),
            title = { Text("¿Suspender LockSuite?", color = Color.White, fontWeight = FontWeight.Bold) },
            text = {
                Text(
                    "Se van a levantar TODAS las restricciones y a desbloquear todas las aplicaciones de este equipo, incluidas las protecciones que impiden desinstalar LockSuite y restaurar de fábrica.\n\n" +
                        "Mientras dure la suspensión el equipo queda sin ninguna protección. Al desactivarla, todo vuelve exactamente a como estaba.",
                    color = Color.White.copy(alpha = 0.85f),
                    fontSize = 13.sp
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    pendingSuspendConfirm = false
                    val ok = policyManager.setLockSuiteSuspended(true)
                    Toast.makeText(
                        context,
                        if (ok) "LockSuite suspendido. El equipo quedó sin restricciones." else "No se pudo suspender LockSuite.",
                        Toast.LENGTH_LONG
                    ).show()
                    refreshKey++
                }) {
                    Text("Sí, suspender", color = Color(0xFFE67E22), fontWeight = FontWeight.Bold)
                }
            },
            dismissButton = {
                TextButton(onClick = { pendingSuspendConfirm = false }) {
                    Text("Cancelar", color = Color.White.copy(alpha = 0.7f))
                }
            }
        )
    }
}


@Composable
fun PolicyGroupCard(title: String, content: @Composable ColumnScope.() -> Unit) {
    Card(
        colors = CardDefaults.cardColors(containerColor = Color(0xFF1E3E62)),
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(12.dp)
    ) {
        Column(
            modifier = Modifier.padding(16.dp)
        ) {
            Text(
                text = LocaleManager.t(title),
                color = Color(0xFFF1C40F),
                fontSize = 16.sp,
                fontWeight = FontWeight.Bold,
                modifier = Modifier.padding(bottom = 12.dp)
            )
            content()
        }
    }
}

@Composable
fun PolicySwitchRow(
    label: String,
    isChecked: Boolean,
    onCheckedChange: (Boolean) -> Boolean   // devuelve true si el DPM aceptó el cambio
) {
    // Estado local para dar feedback visual instantáneo; se revierte si el DPM rechaza
    var checked by remember(isChecked) { mutableStateOf(isChecked) }

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 6.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = LocaleManager.t(label),
            color = Color.White,
            fontSize = 14.sp,
            modifier = Modifier.weight(1f)
        )
        Switch(
            checked = checked,
            onCheckedChange = { newValue ->
                checked = newValue          // movimiento optimista
                val accepted = onCheckedChange(newValue)
                if (!accepted) checked = !newValue  // revertir si falló
            },
            colors = SwitchDefaults.colors(
                checkedThumbColor = Color(0xFF0B192C),
                checkedTrackColor = Color(0xFFF1C40F),
                uncheckedThumbColor = Color.Gray,
                uncheckedTrackColor = Color(0xFF0B192C)
            )
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AppManagerTabContent(context: Context) {
    val appController = remember { AppController(context) }
    var appsList by remember { mutableStateOf<List<AppInfoData>>(emptyList()) }
    var searchQuery by remember { mutableStateOf("") }
    var isLoading by remember { mutableStateOf(false) }
    var selectedFilter by remember { mutableStateOf("Todas") }
    var appToUninstall by remember { mutableStateOf<AppInfoData?>(null) }
    val scope = rememberCoroutineScope()

    fun refreshApps() {
        isLoading = true
        scope.launch(Dispatchers.IO) {
            val list = appController.getUserApps()
            withContext(Dispatchers.Main) {
                appsList = list
                isLoading = false
                com.ejemplo.locksuite.util.FirebaseDeviceSync.syncDeviceInfo(context)
            }
        }
    }

    LaunchedEffect(Unit) {
        refreshApps()
    }

    DisposableEffect(context) {
        val receiver = object : android.content.BroadcastReceiver() {
            override fun onReceive(context: Context?, intent: Intent?) {
                refreshApps()
            }
        }
        val filter = android.content.IntentFilter("com.ejemplo.locksuite.ACTION_APP_UNINSTALLED")
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            context.registerReceiver(receiver, filter, Context.RECEIVER_NOT_EXPORTED)
        } else {
            context.registerReceiver(receiver, filter)
        }
        onDispose {
            context.unregisterReceiver(receiver)
        }
    }

    // Filtrar por texto Y por categoría
    val filteredApps = remember(appsList, searchQuery, selectedFilter) {
        appsList.filter { app ->
            val matchesSearch = app.label.lowercase().contains(searchQuery.lowercase()) ||
                    app.packageName.lowercase().contains(searchQuery.lowercase())
            val matchesFilter = when (selectedFilter) {
                "Todas" -> true
                "Bloqueadas" -> app.isHidden || app.isSuspended || app.isWebViewBlocked || app.imageBlockingMode != "none"
                "Usuario" -> app.appType == "Usuario"
                "Sistema" -> app.appType == "Sistema"
                "Preinstaladas" -> app.appType == "Preinstalada"
                else -> true
            }
            matchesSearch && matchesFilter
        }
    }

    // Modal de confirmación de desinstalación
    if (appToUninstall != null) {
        AlertDialog(
            onDismissRequest = { appToUninstall = null },
            title = { Text("Desinstalar Aplicación") },
            text = { Text("¿Está seguro de que desea desinstalar ${appToUninstall?.label} (${appToUninstall?.packageName}) de forma remota y silenciosa?") },
            confirmButton = {
                TextButton(
                    onClick = {
                        appToUninstall?.let { app ->
                            scope.launch(Dispatchers.IO) {
                                val success = appController.uninstallApp(app.packageName)
                                withContext(Dispatchers.Main) {
                                    if (success) {
                                        Toast.makeText(context, "Iniciando desinstalación...", Toast.LENGTH_SHORT).show()
                                    } else {
                                        Toast.makeText(context, "Fallo al iniciar desinstalación", Toast.LENGTH_SHORT).show()
                                    }
                                }
                            }
                        }
                        appToUninstall = null
                    },
                    colors = ButtonDefaults.textButtonColors(contentColor = Color.Red)
                ) {
                    Text("Desinstalar")
                }
            },
            dismissButton = {
                TextButton(onClick = { appToUninstall = null }) {
                    Text("Cancelar")
                }
            }
        )
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp)
    ) {
        // Buscador
        OutlinedTextField(
            value = searchQuery,
            onValueChange = { searchQuery = it },
            placeholder = { Text("Buscar aplicación...", color = Color.Gray) },
            leadingIcon = { Icon(Icons.Default.Search, "Buscar", tint = Color.Gray) },
            colors = OutlinedTextFieldDefaults.colors(
                focusedBorderColor = Color(0xFFF1C40F),
                unfocusedBorderColor = Color(0xFF1E3E62),
                focusedContainerColor = Color(0xFF1E3E62).copy(alpha = 0.3f),
                unfocusedContainerColor = Color(0xFF1E3E62).copy(alpha = 0.3f),
                focusedTextColor = Color.White,
                unfocusedTextColor = Color.White
            ),
            modifier = Modifier
                .fillMaxWidth()
                .padding(bottom = 12.dp),
            shape = RoundedCornerShape(12.dp)
        )

        // Fila de chips de filtro (Scrolleable horizontalmente)
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .horizontalScroll(rememberScrollState())
                .padding(bottom = 12.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            val blockedCount = remember(appsList) {
                appsList.count { it.isHidden || it.isSuspended || it.isWebViewBlocked || it.imageBlockingMode != "none" }
            }
            val filterOptions = remember(blockedCount) {
                listOf(
                    "Todas" to "Todas",
                    "Bloqueadas" to "Bloqueadas ($blockedCount)",
                    "Usuario" to "Usuario",
                    "Sistema" to "Sistema",
                    "Preinstaladas" to "Preinstaladas"
                )
            }
            filterOptions.forEach { (filterKey, filterLabel) ->
                val isSelected = selectedFilter == filterKey
                FilterChip(
                    selected = isSelected,
                    onClick = { selectedFilter = filterKey },
                    label = { Text(filterLabel, fontSize = 12.sp) },
                    colors = FilterChipDefaults.filterChipColors(
                        selectedContainerColor = Color(0xFFF1C40F),
                        selectedLabelColor = Color(0xFF0B192C),
                        containerColor = Color(0xFF1E3E62).copy(alpha = 0.5f),
                        labelColor = Color.White
                    )
                )
            }
        }

        if (isLoading) {
            Box(
                modifier = Modifier.fillMaxSize(),
                contentAlignment = Alignment.Center
            ) {
                CircularProgressIndicator(color = Color(0xFFF1C40F))
            }
        } else {
            val normalApps = filteredApps.filter { !it.isCritical }
            val criticalApps = filteredApps.filter { it.isCritical }

            LazyColumn(
                verticalArrangement = Arrangement.spacedBy(8.dp),
                modifier = Modifier.weight(1f)
            ) {
                // Sección de Apps Administrables
                if (normalApps.isNotEmpty()) {
                    item {
                        Text(
                            text = "Aplicaciones Administrables",
                            color = Color(0xFFF1C40F),
                            fontWeight = FontWeight.Bold,
                            fontSize = 14.sp,
                            modifier = Modifier.padding(vertical = 8.dp)
                        )
                    }
                    items(normalApps, key = { it.packageName }) { app ->
                        AppRowItem(
                            app = app,
                            onHideChange = { hide, onComplete ->
                                scope.launch(Dispatchers.IO) {
                                    val success = appController.hideApp(app.packageName, hide)
                                    withContext(Dispatchers.Main) {
                                        onComplete(success)
                                        if (success) {
                                            refreshApps()
                                        } else {
                                            Toast.makeText(context, "Fallo al ocultar aplicación", Toast.LENGTH_SHORT).show()
                                        }
                                    }
                                }
                            },
                            onSuspendChange = { suspend, onComplete ->
                                scope.launch(Dispatchers.IO) {
                                    val success = appController.suspendApp(app.packageName, suspend)
                                    withContext(Dispatchers.Main) {
                                        onComplete(success)
                                        if (success) {
                                            refreshApps()
                                        } else {
                                            Toast.makeText(context, "Fallo al suspender aplicación", Toast.LENGTH_SHORT).show()
                                        }
                                    }
                                }
                            },
                            onWebViewChange = { block, onComplete ->
                                 scope.launch(Dispatchers.IO) {
                                     val success = com.ejemplo.locksuite.mdm.WebViewBlockManager.setBlocked(context, app.packageName, block)
                                     withContext(Dispatchers.Main) {
                                         onComplete(success)
                                         if (success && block) {
                                             // Asegurarnos de que KosherVpnService esté corriendo al bloquear WebView
                                             try {
                                                 val prepareIntent = android.net.VpnService.prepare(context)
                                                 if (prepareIntent != null) {
                                                     // Pedir confirmación de conexión VPN al usuario (solo la primera vez)
                                                     if (context is Activity) {
                                                         context.startActivityForResult(prepareIntent, 1024)
                                                     }
                                                 } else {
                                                     // Permiso ya concedido, arrancar servicio directo
                                                     val startServiceIntent = Intent(context, com.ejemplo.locksuite.service.KosherVpnService::class.java)
                                                     if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                                                         context.startForegroundService(startServiceIntent)
                                                     } else {
                                                         context.startService(startServiceIntent)
                                                     }
                                                     
                                                     // Bloquear la configuración de VPN automáticamente para que no la desactive
                                                     val policyManager = com.ejemplo.locksuite.mdm.PolicyManager(context)
                                                     policyManager.setVpnConfigBlocked(true)
                                                     
                                                     Toast.makeText(
                                                         context,
                                                         "VPN activa y configuración bloqueada automáticamente (Lockdown).",
                                                         Toast.LENGTH_LONG
                                                     ).show()
                                                 }
                                             } catch (e: Exception) {
                                                 e.printStackTrace()
                                             }
                                         }
                                         refreshApps()
                                     }
                                 }
                             },
                             onInternetChange = { block ->
                                scope.launch(Dispatchers.IO) {
                                    val policyManager = com.ejemplo.locksuite.mdm.PolicyManager(context)
                                    policyManager.setPerAppInternetBlocked(app.packageName, block)
                                    refreshApps()
                                }
                            },
                            onUninstallClick = {
                                appToUninstall = app
                            }
                        )
                    }
                }

                // Sección de Apps Críticas
                if (criticalApps.isNotEmpty()) {
                    item {
                        Text(
                            text = "Herramientas Críticas del Sistema (No Bloqueables)",
                            color = Color.LightGray,
                            fontWeight = FontWeight.Bold,
                            fontSize = 14.sp,
                            modifier = Modifier.padding(top = 16.dp, bottom = 8.dp)
                        )
                    }
                    items(criticalApps, key = { it.packageName }) { app ->
                        AppRowItem(
                            app = app,
                            onHideChange = { _, onComplete -> onComplete(false) },
                            onSuspendChange = { _, onComplete -> onComplete(false) },
                            onWebViewChange = { _, onComplete -> onComplete(false) },
                            onInternetChange = { _ -> },
                            onUninstallClick = {}
                        )
                    }
                }
            }
        }
    }
}

@Composable
fun AppRowItem(
    app: AppInfoData,
    onHideChange: (Boolean, (Boolean) -> Unit) -> Unit,
    onSuspendChange: (Boolean, (Boolean) -> Unit) -> Unit,
    onWebViewChange: (Boolean, (Boolean) -> Unit) -> Unit,
    onInternetChange: (Boolean) -> Unit,
    onUninstallClick: () -> Unit
) {
    var hideState by remember { mutableStateOf(app.isHidden) }
    var suspendState by remember { mutableStateOf(app.isSuspended) }
    var webviewState by remember { mutableStateOf(app.isWebViewBlocked) }
    var perAppNetState by remember { mutableStateOf(app.isInternetBlocked) }
    var expanded by remember { mutableStateOf(false) }
    var imageBlockingMode by remember { mutableStateOf(app.imageBlockingMode) }
    val scope = rememberCoroutineScope()
    val context = LocalContext.current

    LaunchedEffect(app) {
        hideState = app.isHidden
        suspendState = app.isSuspended
        webviewState = app.isWebViewBlocked
        perAppNetState = app.isInternetBlocked
        imageBlockingMode = app.imageBlockingMode
    }

    Card(
        colors = CardDefaults.cardColors(containerColor = Color(0xFF1E3E62)),
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(12.dp)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            // Icono de la App (Ya es un Bitmap precargado en background)
            if (app.icon != null) {
                Image(
                    bitmap = app.icon.asImageBitmap(),
                    contentDescription = app.label,
                    modifier = Modifier
                        .size(48.dp)
                        .clip(RoundedCornerShape(8.dp))
                )
            } else {
                Box(
                    modifier = Modifier
                        .size(48.dp)
                        .clip(RoundedCornerShape(8.dp))
                        .background(Color.Gray.copy(alpha = 0.3f)),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = Icons.Default.Info,
                        contentDescription = app.label,
                        tint = Color.White
                    )
                }
            }

            Spacer(modifier = Modifier.width(12.dp))

            // Información
            Column(
                modifier = Modifier.weight(1f)
            ) {
                Text(
                    text = app.label,
                    color = Color.White,
                    fontWeight = FontWeight.Bold,
                    fontSize = 15.sp
                )
                Text(
                    text = app.packageName,
                    color = Color.LightGray,
                    fontSize = 12.sp
                )
            }

            // Botón de desinstalar y Configuración Avanzada - Solo si no es crítica
            if (!app.isCritical) {
                IconButton(onClick = {
                    // Se quitó un Toast de depuración ("Engranaje presionado: $expanded")
                    // que quedó de pruebas: le mostraba al admin un mensaje interno cada
                    // vez que tocaba el engranaje de cada app en la lista.
                    expanded = !expanded
                }) {
                    Icon(
                        imageVector = Icons.Default.Settings,
                        contentDescription = "Configuración Avanzada",
                        tint = if (imageBlockingMode != "none") Color(0xFFF1C40F) else Color.White
                    )
                }

                IconButton(onClick = onUninstallClick) {
                    Icon(
                        imageVector = Icons.Default.Delete,
                        contentDescription = "Desinstalar",
                        tint = Color.Red.copy(alpha = 0.8f)
                    )
                }
            }
        }
        
        if (!app.isCritical) {
            HorizontalDivider(color = Color(0xFF0B192C), thickness = 1.dp)

            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 12.dp, vertical = 4.dp),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                // Ocultar
                Row(
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text("Ocultar", color = Color.White, fontSize = 11.sp)
                    Spacer(modifier = Modifier.width(2.dp))
                    Switch(
                        checked = hideState,
                        onCheckedChange = { newValue ->
                            onHideChange(newValue) { success ->
                                if (success) {
                                    hideState = newValue
                                } else {
                                    hideState = !newValue
                                }
                            }
                        },
                        colors = SwitchDefaults.colors(
                            checkedThumbColor = Color(0xFF0B192C),
                            checkedTrackColor = Color(0xFFF1C40F)
                        ),
                        modifier = Modifier.scale(0.7f)
                    )
                }

                // Suspender
                Row(
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text("Suspender", color = Color.White, fontSize = 11.sp)
                    Spacer(modifier = Modifier.width(2.dp))
                    Switch(
                        checked = suspendState,
                        onCheckedChange = { newValue ->
                            onSuspendChange(newValue) { success ->
                                if (success) {
                                    suspendState = newValue
                                } else {
                                    suspendState = !newValue
                                }
                            }
                        },
                        colors = SwitchDefaults.colors(
                            checkedThumbColor = Color(0xFF0B192C),
                            checkedTrackColor = Color(0xFFF1C40F)
                        ),
                        modifier = Modifier.scale(0.7f)
                    )
                }

                // Bloquear WebView
                Row(
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text("WebView", color = Color.White, fontSize = 11.sp)
                    Spacer(modifier = Modifier.width(2.dp))
                    Switch(
                        checked = webviewState,
                        onCheckedChange = { newValue ->
                            onWebViewChange(newValue) { success ->
                                if (success) {
                                    webviewState = newValue
                                } else {
                                    webviewState = !newValue
                                }
                            }
                        },
                        colors = SwitchDefaults.colors(
                            checkedThumbColor = Color(0xFF0B192C),
                            checkedTrackColor = Color(0xFFF1C40F)
                        ),
                        modifier = Modifier.scale(0.7f)
                    )
                }
            }

            if (expanded) {
                HorizontalDivider(color = Color(0xFF0B192C), thickness = 1.dp)
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(12.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Text(
                        text = "Bloqueo de imágenes:",
                        color = Color.LightGray,
                        fontSize = 13.sp,
                        fontWeight = FontWeight.Bold
                    )
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(4.dp)
                    ) {
                        val modes = listOf(
                            "none" to "Desactivado",
                            "layer1" to "Capa 1",
                            "layer2" to "Capa 2",
                            "both" to "Ambas"
                        )
                        modes.forEach { (valStr, label) ->
                            val isSelected = imageBlockingMode == valStr
                            Box(
                                modifier = Modifier
                                    .weight(1f)
                                    .clip(RoundedCornerShape(8.dp))
                                    .background(if (isSelected) Color(0xFFF1C40F) else Color(0xFF0B192C))
                                    .clickable {
                                        imageBlockingMode = valStr
                                        scope.launch(Dispatchers.IO) {
                                            com.ejemplo.locksuite.mdm.ImageBlockManager.setMode(context, app.packageName, valStr)
                                            com.ejemplo.locksuite.util.FirebaseDeviceSync.syncDeviceInfo(context)
                                        }
                                    }
                                    .padding(vertical = 8.dp),
                                contentAlignment = Alignment.Center
                            ) {
                                Text(
                                    text = label,
                                    color = if (isSelected) Color(0xFF0B192C) else Color.White,
                                    fontSize = 11.sp,
                                    fontWeight = FontWeight.Bold
                                )
                            }
                        }
                    }

                    Spacer(modifier = Modifier.height(8.dp))

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text("Bloqueo Total de Internet", color = Color.White, fontSize = 13.sp, fontWeight = FontWeight.Bold)
                            Text("Corta el acceso a internet para esta app por completo.", color = Color.LightGray, fontSize = 11.sp)
                        }
                        Switch(
                            checked = perAppNetState,
                            onCheckedChange = { enabled ->
                                perAppNetState = enabled
                                onInternetChange(enabled)
                                Toast.makeText(context, if (enabled) "Internet bloqueado en ${app.label}." else "Internet permitido en ${app.label}.", Toast.LENGTH_SHORT).show()
                            },
                            colors = SwitchDefaults.colors(
                                checkedThumbColor = Color(0xFF0B192C),
                                checkedTrackColor = Color(0xFFF1C40F)
                            )
                        )
                    }
                }
            }
        }
    }
}

@Composable
fun PresetsTabContent(context: Context) {
    val policyManager = remember { PolicyManager(context) }
    var presetNameInput by remember { mutableStateOf("") }
    var refreshKey by remember { mutableIntStateOf(0) }
    val presetsMap = remember(refreshKey) { policyManager.getLocalPresets() }

    var pendingExportJson by rememberSaveable { mutableStateOf<String?>(null) }
    var pendingExportName by rememberSaveable { mutableStateOf("") }

    val exportLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.CreateDocument("application/octet-stream")
    ) { uri: Uri? ->
        uri?.let {
            try {
                val jsonToWrite = pendingExportJson
                if (!jsonToWrite.isNullOrEmpty()) {
                    context.contentResolver.openOutputStream(it)?.use { outputStream ->
                        outputStream.bufferedWriter().use { writer ->
                            writer.write(jsonToWrite)
                        }
                    }
                    Toast.makeText(context, "✅ Perfil '$pendingExportName' exportado con éxito.", Toast.LENGTH_LONG).show()
                }
            } catch (e: Exception) {
                Toast.makeText(context, "❌ Error al exportar perfil: ${e.message}", Toast.LENGTH_LONG).show()
            }
        }
    }

    val importLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.GetContent()
    ) { uri: Uri? ->
        uri?.let {
            try {
                val inputStream = context.contentResolver.openInputStream(it)
                val jsonString = inputStream?.bufferedReader()?.use { reader -> reader.readText() } ?: ""
                if (jsonString.isNotEmpty()) {
                    val success = policyManager.importPolicyPresetJson(jsonString)
                    if (success) {
                        val importedName = try {
                            val obj = org.json.JSONObject(jsonString)
                            obj.optString("presetName", "Perfil Importado")
                        } catch (e: Exception) {
                            "Perfil Importado"
                        }
                        policyManager.saveLocalPreset(importedName, jsonString)
                        Toast.makeText(context, "✅ Backup (.locksuite) importado, guardado y aplicado con éxito.", Toast.LENGTH_LONG).show()
                        refreshKey++
                    } else {
                        Toast.makeText(context, "❌ Error al aplicar el backup.", Toast.LENGTH_LONG).show()
                    }
                }
            } catch (e: SecurityException) {
                Toast.makeText(context, "🚨 ALERTA: ${e.message}", Toast.LENGTH_LONG).show()
            } catch (e: Exception) {
                Toast.makeText(context, "Error al leer archivo: ${e.message}", Toast.LENGTH_LONG).show()
            }
        }
    }

    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        item {
            Card(
                colors = CardDefaults.cardColors(containerColor = Color(0xFF1E3E62)),
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(12.dp)
            ) {
                Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text("Guardar Configuración Actual (Preset)", color = Color(0xFFF1C40F), fontWeight = FontWeight.Bold, fontSize = 16.sp)
                    Text(
                        "Guarda todas las políticas y restricciones activas con un nombre personalizado.",
                        color = Color.White.copy(alpha = 0.7f),
                        fontSize = 12.sp
                    )
                    OutlinedTextField(
                        value = presetNameInput,
                        onValueChange = { presetNameInput = it },
                        placeholder = { Text("Ej: Bloqueo A Fuerte", color = Color.Gray) },
                        singleLine = true,
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedBorderColor = Color(0xFFF1C40F),
                            unfocusedBorderColor = Color.White.copy(alpha = 0.5f),
                            focusedTextColor = Color.White,
                            unfocusedTextColor = Color.White
                        ),
                        modifier = Modifier.fillMaxWidth()
                    )
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Button(
                            onClick = {
                                if (presetNameInput.isBlank()) {
                                    Toast.makeText(context, "Ingresa un nombre para el perfil", Toast.LENGTH_SHORT).show()
                                    return@Button
                                }
                                val jsonStr = policyManager.exportPolicyPresetJson(presetNameInput.trim())
                                policyManager.saveLocalPreset(presetNameInput.trim(), jsonStr)
                                presetNameInput = ""
                                refreshKey++
                                Toast.makeText(context, "Perfil guardado con éxito.", Toast.LENGTH_SHORT).show()
                            },
                            colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFF1C40F), contentColor = Color(0xFF0B192C)),
                            modifier = Modifier.weight(1f)
                        ) {
                            Text("Guardar Local", fontWeight = FontWeight.Bold)
                        }
                        Button(
                            onClick = {
                                val name = if (presetNameInput.isNotBlank()) presetNameInput.trim() else "Perfil_LockSuite"
                                val jsonStr = policyManager.exportPolicyPresetJson(name)
                                pendingExportJson = jsonStr
                                pendingExportName = name
                                exportLauncher.launch("${name.replace("\\s+".toRegex(), "_")}.locksuite")
                            },
                            colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF2980B9), contentColor = Color.White),
                            modifier = Modifier.weight(1f)
                        ) {
                            Text("Exportar a Archivo", fontWeight = FontWeight.Bold)
                        }
                    }
                }
            }
        }

        item {
            Card(
                colors = CardDefaults.cardColors(containerColor = Color(0xFF1E3E62)),
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(12.dp)
            ) {
                Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text("Importar Copia de Seguridad (.locksuite)", color = Color(0xFFF1C40F), fontWeight = FontWeight.Bold, fontSize = 16.sp)
                    Text(
                        "Selecciona un archivo de respaldo .locksuite. El archivo se verificará criptográficamente mediante HMAC SHA-256 para evitar alteraciones.",
                        color = Color.White.copy(alpha = 0.7f),
                        fontSize = 12.sp
                    )
                    Button(
                        onClick = {
                            try {
                                importLauncher.launch("*/*")
                            } catch (e: Exception) {
                                Toast.makeText(context, "Error: ${e.message}", Toast.LENGTH_SHORT).show()
                            }
                        },
                        colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFF1C40F), contentColor = Color(0xFF0B192C)),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text("Cargar Archivo .locksuite", fontWeight = FontWeight.Bold)
                    }
                }
            }
        }

        item {
            Text("Perfiles Guardados Localmente", color = Color(0xFFF1C40F), fontWeight = FontWeight.Bold, fontSize = 16.sp)
        }

        if (presetsMap.isEmpty()) {
            item {
                Text("No hay perfiles guardados aún.", color = Color.Gray, fontSize = 13.sp)
            }
        } else {
            items(presetsMap.keys.toList()) { name ->
                val jsonStr = presetsMap[name] ?: ""
                Card(
                    colors = CardDefaults.cardColors(containerColor = Color(0xFF1E3E62)),
                    modifier = Modifier.fillMaxWidth(),
                    shape = RoundedCornerShape(8.dp)
                ) {
                    Row(
                        modifier = Modifier.padding(12.dp).fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(name, color = Color.White, fontWeight = FontWeight.Bold, fontSize = 14.sp)
                        }
                        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            Button(
                                onClick = {
                                    try {
                                        val success = policyManager.importPolicyPresetJson(jsonStr)
                                        if (success) {
                                            Toast.makeText(context, "Perfil '$name' aplicado con éxito.", Toast.LENGTH_SHORT).show()
                                        } else {
                                            Toast.makeText(context, "❌ Error al aplicar el perfil '$name'.", Toast.LENGTH_LONG).show()
                                        }
                                    } catch (e: SecurityException) {
                                        Toast.makeText(context, "🚨 ALERTA: ${e.message}", Toast.LENGTH_LONG).show()
                                    }
                                },
                                colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF27AE60)),
                                contentPadding = PaddingValues(horizontal = 8.dp, vertical = 4.dp)
                            ) {
                                Text("Aplicar", fontSize = 10.sp)
                            }
                            Button(
                                onClick = {
                                    pendingExportJson = jsonStr
                                    pendingExportName = name
                                    exportLauncher.launch("${name.replace("\\s+".toRegex(), "_")}.locksuite")
                                },
                                colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF2980B9)),
                                contentPadding = PaddingValues(horizontal = 8.dp, vertical = 4.dp)
                            ) {
                                Text("Exportar", fontSize = 10.sp)
                            }
                            Button(
                                onClick = {
                                    policyManager.deleteLocalPreset(name)
                                    refreshKey++
                                    Toast.makeText(context, "Perfil eliminado.", Toast.LENGTH_SHORT).show()
                                },
                                colors = ButtonDefaults.buttonColors(containerColor = Color.Red),
                                contentPadding = PaddingValues(horizontal = 8.dp, vertical = 4.dp)
                            ) {
                                Text("Eliminar", fontSize = 10.sp)
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun StatusLabelRow(label: String, value: String) {
    Column(modifier = Modifier.padding(vertical = 4.dp)) {
        Text(label, color = Color.LightGray, fontSize = 12.sp)
        Text(value, color = Color.White, fontSize = 14.sp, fontWeight = FontWeight.Bold)
        Spacer(modifier = Modifier.height(4.dp))
    }
}

@Composable
fun ServicesTabContent(
    context: Context,
    onTriggerPermissionsReauth: () -> Unit,
    onTriggerUninstallReauth: () -> Unit
) {
    val dpm = context.getSystemService(Context.DEVICE_POLICY_SERVICE) as DevicePolicyManager
    val isDeviceOwner = dpm.isDeviceOwnerApp(context.packageName)
    val scope = rememberCoroutineScope()
    val policyManager = remember { com.ejemplo.locksuite.mdm.PolicyManager(context) }
    
    val aliasComponent = ComponentName(context, "com.ejemplo.locksuite.LauncherAlias")
    val pm = context.packageManager
    val isStealthActive = pm.getComponentEnabledSetting(aliasComponent) == PackageManager.COMPONENT_ENABLED_STATE_DISABLED

    var stealthModeState by remember { mutableStateOf(isStealthActive) }

    // Antes el estado del Watchdog se mostraba como "ACTIVO" en un string fijo,
    // sin verificar nada real: si el servicio moría (p.ej. un OEM agresivo con la
    // batería lo mata) el panel seguía mostrando "ACTIVO" igual, dando una falsa
    // sensación de seguridad justo sobre el mecanismo que reaplica las
    // restricciones. Ahora se consulta el estado real del servicio (sin remember,
    // igual que las comprobaciones de Accesibilidad y VPN de más abajo: se
    // reevalúa en cada recomposición para reflejar el estado actual).
    val isWatchdogRunning = try {
        val am = context.getSystemService(Context.ACTIVITY_SERVICE) as android.app.ActivityManager
        @Suppress("DEPRECATION")
        am.getRunningServices(Int.MAX_VALUE).any {
            it.service.className == com.ejemplo.locksuite.service.WatchdogForegroundService::class.java.name
        }
    } catch (e: Exception) {
        false
    }

    val navyMedium = Color(0xFF1E3E62)
    val accentOrange = Color(0xFFF1C40F)

    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        // Tarjeta de Estado
        item {
            Card(
                colors = CardDefaults.cardColors(containerColor = navyMedium),
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(12.dp)
            ) {
                Column(
                    modifier = Modifier.padding(16.dp)
                ) {
                    Text(
                        "Estado de LockSuite MDM",
                        color = accentOrange,
                        fontSize = 16.sp,
                        fontWeight = FontWeight.Bold,
                        modifier = Modifier.padding(bottom = 12.dp)
                    )
                    
                    StatusLabelRow(label = "Licencia de Propietario (Device Owner)", value = if (isDeviceOwner) "ACTIVO (Seguridad de Sistema)" else "INACTIVO")
                    StatusLabelRow(
                        label = "Servicio Watchdog (Persistencia)",
                        value = if (isWatchdogRunning) "ACTIVO (Servicio de Primer Plano)" else "⚠️ INACTIVO — reabra la app para reiniciarlo"
                    )
                    StatusLabelRow(label = "Canal FCM de Control Remoto", value = "LISTO (Firebase Cloud Messaging)")
                    StatusLabelRow(label = "Modo Stealth (Launcher Oculto)", value = if (stealthModeState) "ACTIVADO" else "DESACTIVADO")
                }
            }
        }

        // Configuración de Seguridad
        item {
            Card(
                colors = CardDefaults.cardColors(containerColor = navyMedium),
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(12.dp)
            ) {
                Column(
                    modifier = Modifier.padding(16.dp)
                ) {
                    var showChangePinDialog by remember { mutableStateOf(false) }

                    if (showChangePinDialog) {
                        var newPin by remember { mutableStateOf("") }
                        var confirmPin by remember { mutableStateOf("") }
                        var errorMsg by remember { mutableStateOf("") }

                        AlertDialog(
                            onDismissRequest = { showChangePinDialog = false },
                            containerColor = Color(0xFF0B192C),
                            title = {
                                Text(
                                    "Cambiar PIN de Administrador",
                                    color = accentOrange,
                                    fontWeight = FontWeight.Bold,
                                    fontSize = 18.sp
                                )
                            },
                            text = {
                                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                                    Text(
                                        "El nuevo PIN debe tener entre 4 y 16 dígitos numéricos. Esto actualizará el control web de forma automática.",
                                        color = Color.White,
                                        fontSize = 13.sp
                                    )
                                    OutlinedTextField(
                                        value = newPin,
                                        onValueChange = { if (it.all { c -> c.isDigit() }) newPin = it },
                                        label = { Text("Nuevo PIN", color = Color.White.copy(alpha = 0.8f)) },
                                        visualTransformation = androidx.compose.ui.text.input.PasswordVisualTransformation(),
                                        keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(
                                            keyboardType = androidx.compose.ui.text.input.KeyboardType.NumberPassword
                                        ),
                                        singleLine = true,
                                        colors = OutlinedTextFieldDefaults.colors(
                                            focusedBorderColor = accentOrange,
                                            unfocusedBorderColor = Color.White.copy(alpha = 0.5f),
                                            focusedTextColor = Color.White,
                                            unfocusedTextColor = Color.White
                                        ),
                                        modifier = Modifier.fillMaxWidth()
                                    )
                                    OutlinedTextField(
                                        value = confirmPin,
                                        onValueChange = { if (it.all { c -> c.isDigit() }) confirmPin = it },
                                        label = { Text("Confirmar PIN", color = Color.White.copy(alpha = 0.8f)) },
                                        visualTransformation = androidx.compose.ui.text.input.PasswordVisualTransformation(),
                                        keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(
                                            keyboardType = androidx.compose.ui.text.input.KeyboardType.NumberPassword
                                        ),
                                        singleLine = true,
                                        colors = OutlinedTextFieldDefaults.colors(
                                            focusedBorderColor = accentOrange,
                                            unfocusedBorderColor = Color.White.copy(alpha = 0.5f),
                                            focusedTextColor = Color.White,
                                            unfocusedTextColor = Color.White
                                        ),
                                        modifier = Modifier.fillMaxWidth()
                                    )
                                    if (errorMsg.isNotEmpty()) {
                                        Text(errorMsg, color = Color.Red, fontSize = 12.sp, fontWeight = FontWeight.Bold)
                                    }
                                }
                            },
                            confirmButton = {
                                Button(
                                    onClick = {
                                        if (newPin.length < 4 || newPin.length > 16) {
                                            errorMsg = "El PIN debe tener entre 4 y 16 dígitos."
                                        } else if (com.ejemplo.locksuite.security.PinManager.isTrivialPin(newPin)) {
                                            // Antes este diálogo no validaba PINs triviales: un
                                            // admin podía crear un PIN fuerte en el setup inicial
                                            // y luego cambiarlo acá a algo como "1234" sin ningún
                                            // aviso, debilitando la protección real del equipo.
                                            errorMsg = "PIN muy débil (no use secuencias o dígitos idénticos)."
                                        } else if (newPin != confirmPin) {
                                            errorMsg = "Los PINs no coinciden."
                                        } else {
                                            try {
                                                com.ejemplo.locksuite.security.PinManager.saveAdminPin(context, newPin)
                                                Toast.makeText(context, "PIN de Administrador cambiado con éxito", Toast.LENGTH_SHORT).show()
                                                showChangePinDialog = false
                                            } catch (e: Exception) {
                                                errorMsg = "Error al guardar el PIN: ${e.message}"
                                            }
                                        }
                                    },
                                    colors = ButtonDefaults.buttonColors(containerColor = accentOrange, contentColor = Color(0xFF0B192C))
                                ) {
                                    Text("Guardar", fontWeight = FontWeight.Bold)
                                }
                            },
                            dismissButton = {
                                TextButton(onClick = { showChangePinDialog = false }) {
                                    Text("Cancelar", color = Color.LightGray)
                                }
                            }
                        )
                    }

                    Text(
                        "Opciones Avanzadas",
                        color = accentOrange,
                        fontSize = 16.sp,
                        fontWeight = FontWeight.Bold,
                        modifier = Modifier.padding(bottom = 12.dp)
                    )

                    // Botón para Cambiar PIN
                    Button(
                        onClick = { showChangePinDialog = true },
                        colors = ButtonDefaults.buttonColors(containerColor = accentOrange, contentColor = Color(0xFF0B192C)),
                        shape = RoundedCornerShape(8.dp),
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(bottom = 16.dp)
                    ) {
                        Text("Cambiar PIN de Administrador", fontWeight = FontWeight.Bold)
                    }

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text("Activar Modo Stealth", color = Color.White, fontSize = 14.sp)
                            Text(
                                "Oculta el ícono del menú. Para volver a abrir: marque *#*#1234#*#* en el teléfono, o vaya a Ajustes → Aplicaciones → LockSuite MDM → (ícono de engranaje ⚙️).",
                                color = Color.LightGray,
                                fontSize = 11.sp,
                                modifier = Modifier.padding(top = 2.dp)
                            )
                        }
                        Switch(
                            checked = stealthModeState,
                            onCheckedChange = { enabled ->
                                val state = if (enabled) {
                                    PackageManager.COMPONENT_ENABLED_STATE_DISABLED
                                } else {
                                    PackageManager.COMPONENT_ENABLED_STATE_ENABLED
                                }
                                try {
                                    pm.setComponentEnabledSetting(
                                        aliasComponent,
                                        state,
                                        PackageManager.DONT_KILL_APP
                                    )
                                    stealthModeState = enabled
                                    Toast.makeText(context, if (enabled) "Modo Stealth Activado. El ícono desaparecerá." else "Modo Stealth Desactivado.", Toast.LENGTH_LONG).show()
                                } catch (e: Exception) {
                                    e.printStackTrace()
                                }
                            },
                            colors = SwitchDefaults.colors(
                                checkedThumbColor = Color(0xFF0B192C),
                                checkedTrackColor = accentOrange
                            )
                        )
                    }

                    Spacer(modifier = Modifier.height(16.dp))

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text("Auto Evasión en Ajustes", color = Color.White, fontSize = 14.sp)
                            Text(
                                "Evita que desactiven el administrador o fuercen la detención desde los Ajustes del sistema.",
                                color = Color.LightGray,
                                fontSize = 11.sp,
                                modifier = Modifier.padding(top = 2.dp)
                            )
                        }
                        var evasionEnabledState by remember { 
                            mutableStateOf(PrefsHelper.getMdmPrefs(context).getBoolean("settings_evasion_enabled", false)) 
                        }
                        Switch(
                            checked = evasionEnabledState,
                            onCheckedChange = { enabled ->
                                PrefsHelper.getMdmPrefs(context).edit().putBoolean("settings_evasion_enabled", enabled).apply()
                                evasionEnabledState = enabled
                                Toast.makeText(context, if (enabled) "Auto Evasión Activada." else "Auto Evasión Desactivada.", Toast.LENGTH_SHORT).show()
                            },
                            colors = SwitchDefaults.colors(
                                checkedThumbColor = Color(0xFF0B192C),
                                checkedTrackColor = accentOrange
                            )
                        )
                    }

                    Spacer(modifier = Modifier.height(16.dp))

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text("Activar Modo IA (Filtro de Siluetas)", color = Color.White, fontSize = 14.sp)
                            Text(
                                "Detecta y difumina siluetas humanas en pantalla en tiempo real (Capa 2).",
                                color = Color.LightGray,
                                fontSize = 11.sp,
                                modifier = Modifier.padding(top = 2.dp)
                            )
                        }
                        var globalAiState by remember {
                            mutableStateOf(com.ejemplo.locksuite.mdm.ImageBlockManager.isGlobalAiEnabled(context))
                        }
                        Switch(
                            checked = globalAiState,
                            onCheckedChange = { enabled ->
                                com.ejemplo.locksuite.mdm.ImageBlockManager.setGlobalAiEnabled(context, enabled)
                                if (!enabled) {
                                    com.ejemplo.locksuite.service.AIContentGate.releaseAll()
                                }
                                globalAiState = enabled
                                scope.launch(Dispatchers.IO) {
                                    com.ejemplo.locksuite.util.FirebaseDeviceSync.syncDeviceInfo(context)
                                }
                                Toast.makeText(context, if (enabled) "Modo IA Activado." else "Modo IA Desactivado.", Toast.LENGTH_SHORT).show()
                            },
                            colors = SwitchDefaults.colors(
                                checkedThumbColor = Color(0xFF0B192C),
                                checkedTrackColor = accentOrange
                            )
                        )
                    }

                    Spacer(modifier = Modifier.height(16.dp))

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text("Bloqueo de Imágenes en Maps", color = Color.White, fontSize = 14.sp)
                            Text(
                                "Activa un bloqueo ultra estricto de fotos y personas específico para Google Maps.",
                                color = Color.LightGray,
                                fontSize = 11.sp,
                                modifier = Modifier.padding(top = 2.dp)
                            )
                        }
                        var mapsAiState by remember {
                            mutableStateOf(com.ejemplo.locksuite.mdm.ImageBlockManager.isMapsImageBlockingEnabled(context))
                        }
                        Switch(
                            checked = mapsAiState,
                            onCheckedChange = { enabled ->
                                com.ejemplo.locksuite.mdm.ImageBlockManager.setMapsImageBlockingEnabled(context, enabled)
                                mapsAiState = enabled
                                scope.launch(Dispatchers.IO) {
                                    com.ejemplo.locksuite.util.FirebaseDeviceSync.syncDeviceInfo(context)
                                }
                                Toast.makeText(context, if (enabled) "Bloqueo Maps Activado." else "Bloqueo Maps Desactivado.", Toast.LENGTH_SHORT).show()
                            },
                            colors = SwitchDefaults.colors(
                                checkedThumbColor = Color(0xFF0B192C),
                                checkedTrackColor = accentOrange
                            )
                        )
                    }

                    Spacer(modifier = Modifier.height(16.dp))

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text("Bloquear Estados de WhatsApp", color = Color.White, fontSize = 14.sp)
                            Text(
                                "Evita ver o publicar Estados de contactos en WhatsApp.",
                                color = Color.LightGray,
                                fontSize = 11.sp,
                                modifier = Modifier.padding(top = 2.dp)
                            )
                        }
                        var blockStatusState by remember {
                            mutableStateOf(policyManager.isWhatsAppBlockStatusEnabled())
                        }
                        Switch(
                            checked = blockStatusState,
                            onCheckedChange = { enabled ->
                                policyManager.setWhatsAppBlockStatus(enabled)
                                blockStatusState = enabled
                                scope.launch(Dispatchers.IO) {
                                    com.ejemplo.locksuite.util.FirebaseDeviceSync.syncDeviceInfo(context)
                                }
                                Toast.makeText(context, if (enabled) "Estados Bloqueados." else "Estados Permitidos.", Toast.LENGTH_SHORT).show()
                            },
                            colors = SwitchDefaults.colors(
                                checkedThumbColor = Color(0xFF0B192C),
                                checkedTrackColor = accentOrange
                            )
                        )
                    }

                    Spacer(modifier = Modifier.height(16.dp))

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text("Bloquear Canales de WhatsApp", color = Color.White, fontSize = 14.sp)
                            Text(
                                "Evita buscar, seguir o ver Canales/Newsletters en WhatsApp.",
                                color = Color.LightGray,
                                fontSize = 11.sp,
                                modifier = Modifier.padding(top = 2.dp)
                            )
                        }
                        var blockChannelsState by remember {
                            mutableStateOf(policyManager.isWhatsAppBlockChannelsEnabled())
                        }
                        Switch(
                            checked = blockChannelsState,
                            onCheckedChange = { enabled ->
                                policyManager.setWhatsAppBlockChannels(enabled)
                                blockChannelsState = enabled
                                scope.launch(Dispatchers.IO) {
                                    com.ejemplo.locksuite.util.FirebaseDeviceSync.syncDeviceInfo(context)
                                }
                                Toast.makeText(context, if (enabled) "Canales Bloqueados." else "Canales Permitidos.", Toast.LENGTH_SHORT).show()
                            },
                            colors = SwitchDefaults.colors(
                                checkedThumbColor = Color(0xFF0B192C),
                                checkedTrackColor = accentOrange
                            )
                        )
                    }

                    Spacer(modifier = Modifier.height(16.dp))

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text("Bloquear Ofertas MP (Accesibilidad)", color = Color.White, fontSize = 14.sp)
                            Text(
                                "Bloquea visualmente la pestaña de Ofertas y Promociones en la pantalla de Mercado Pago.",
                                color = Color.LightGray,
                                fontSize = 11.sp,
                                modifier = Modifier.padding(top = 2.dp)
                            )
                        }
                        var blockMpAccState by remember {
                            mutableStateOf(policyManager.isMercadoPagoBlockOffersAccessibilityEnabled())
                        }
                        Switch(
                            checked = blockMpAccState,
                            onCheckedChange = { enabled ->
                                policyManager.setMercadoPagoBlockOffersAccessibility(enabled)
                                blockMpAccState = enabled
                                scope.launch(Dispatchers.IO) {
                                    com.ejemplo.locksuite.util.FirebaseDeviceSync.syncDeviceInfo(context)
                                }
                                Toast.makeText(context, if (enabled) "Ofertas MP (Accesibilidad) Bloqueadas." else "Ofertas MP Permitidas.", Toast.LENGTH_SHORT).show()
                            },
                            colors = SwitchDefaults.colors(
                                checkedThumbColor = Color(0xFF0B192C),
                                checkedTrackColor = accentOrange
                            )
                        )
                    }

                    Spacer(modifier = Modifier.height(12.dp))

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text("Bloquear Ofertas MP (por VPN)", color = Color.White, fontSize = 14.sp)
                            Text(
                                "Bloquea las peticiones de red y APIs de promociones y créditos en Mercado Pago.",
                                color = Color.LightGray,
                                fontSize = 11.sp,
                                modifier = Modifier.padding(top = 2.dp)
                            )
                        }
                        var blockMpVpnState by remember {
                            mutableStateOf(policyManager.isMercadoPagoBlockOffersVpnEnabled())
                        }
                        Switch(
                            checked = blockMpVpnState,
                            onCheckedChange = { enabled ->
                                policyManager.setMercadoPagoBlockOffersVpn(enabled)
                                blockMpVpnState = enabled
                                scope.launch(Dispatchers.IO) {
                                    com.ejemplo.locksuite.util.FirebaseDeviceSync.syncDeviceInfo(context)
                                }
                                Toast.makeText(context, if (enabled) "Ofertas MP (VPN) Bloqueadas." else "Ofertas MP Permitidas.", Toast.LENGTH_SHORT).show()
                            },
                            colors = SwitchDefaults.colors(
                                checkedThumbColor = Color(0xFF0B192C),
                                checkedTrackColor = accentOrange
                            )
                        )
                    }

                    Spacer(modifier = Modifier.height(12.dp))

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text("Bloqueo de Mercado Libre en Mercado Pago", color = Color.White, fontSize = 14.sp)
                            Text(
                                "Bloquea por DNS los dominios de Mercado Libre en Mercado Pago (click1, listado, mobile, snoopy, www).",
                                color = Color.LightGray,
                                fontSize = 11.sp,
                                modifier = Modifier.padding(top = 2.dp)
                            )
                        }
                        var blockMlState by remember {
                            mutableStateOf(policyManager.isMercadoLibreInMpBlocked())
                        }
                        Switch(
                            checked = blockMlState,
                            onCheckedChange = { enabled ->
                                policyManager.setMercadoLibreInMpBlocked(enabled)
                                blockMlState = enabled
                                scope.launch(Dispatchers.IO) {
                                    com.ejemplo.locksuite.util.FirebaseDeviceSync.syncDeviceInfo(context)
                                }
                                Toast.makeText(context, if (enabled) "Mercado Libre en MP Bloqueado." else "Mercado Libre en MP Permitido.", Toast.LENGTH_SHORT).show()
                            },
                            colors = SwitchDefaults.colors(
                                checkedThumbColor = Color(0xFF0B192C),
                                checkedTrackColor = accentOrange
                            )
                        )
                    }

                    Spacer(modifier = Modifier.height(16.dp))

                    // Configuración de Accesibilidad (Enlace directo al sistema)
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        // Comprobar dinámicamente si el servicio de accesibilidad está activo (Evaluación en recomposición)
                        val expectedService = "com.ejemplo.locksuite/com.ejemplo.locksuite.service.LockSuiteAccessibilityService"
                        val settingValue = Settings.Secure.getString(context.contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES) ?: ""
                        val accessibilityEnabled = settingValue.contains(expectedService)

                        Column(modifier = Modifier.weight(1f)) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text("Servicio de Accesibilidad", color = Color.White, fontSize = 14.sp)
                                Spacer(modifier = Modifier.width(8.dp))
                                Box(
                                    modifier = Modifier
                                        .clip(RoundedCornerShape(4.dp))
                                        .background(if (accessibilityEnabled) Color(0xFF27AE60) else Color(0xFFE74C3C))
                                        .padding(horizontal = 6.dp, vertical = 2.dp)
                                ) {
                                    Text(
                                        text = if (accessibilityEnabled) "ACTIVO" else "INACTIVO",
                                        color = Color.White,
                                        fontSize = 10.sp,
                                        fontWeight = FontWeight.Bold
                                    )
                                }
                            }
                            Text(
                                "Requerido para bloquear WebViews y evitar evasiones. Toque el botón para abrir los Ajustes del sistema.",
                                color = Color.LightGray,
                                fontSize = 11.sp,
                                modifier = Modifier.padding(top = 2.dp)
                            )
                        }
                        
                        val navyDark = Color(0xFF0B192C)
                        Button(
                            onClick = {
                                try {
                                    val intent = Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS).apply {
                                        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                                    }
                                    context.startActivity(intent)
                                } catch (e: Exception) {
                                    Toast.makeText(context, "No se pudo abrir los Ajustes de Accesibilidad. Vaya manualmente.", Toast.LENGTH_LONG).show()
                                }
                            },
                            colors = ButtonDefaults.buttonColors(containerColor = accentOrange, contentColor = navyDark),
                            shape = RoundedCornerShape(8.dp),
                            contentPadding = PaddingValues(horizontal = 12.dp, vertical = 4.dp),
                            modifier = Modifier.height(36.dp)
                        ) {
                            Text("Configurar", fontSize = 12.sp, fontWeight = FontWeight.Bold)
                        }
                    }

                    Spacer(modifier = Modifier.height(16.dp))

                    // Configuración de VPN DNS (Capa 3 de red)
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        // Comprobar si la interfaz virtual tun de VPN está arriba (Evaluación en recomposición)
                        var isVpnOn = false
                        try {
                            val nis = java.net.NetworkInterface.getNetworkInterfaces()
                            if (nis != null) {
                                for (ni in nis) {
                                    if (ni.isUp && (ni.name.contains("tun") || ni.name.contains("ppp") || ni.name.contains("p2p"))) {
                                        isVpnOn = true
                                        break
                                    }
                                }
                            }
                        } catch (e: Exception) {
                            e.printStackTrace()
                        }
                        val vpnActive = isVpnOn

                        Column(modifier = Modifier.weight(1f)) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text("Filtrado de Red (VPN DNS)", color = Color.White, fontSize = 14.sp)
                                Spacer(modifier = Modifier.width(8.dp))
                                Box(
                                    modifier = Modifier
                                        .clip(RoundedCornerShape(4.dp))
                                        .background(if (vpnActive) Color(0xFF27AE60) else Color(0xFFE74C3C))
                                        .padding(horizontal = 6.dp, vertical = 2.dp)
                                ) {
                                    Text(
                                        text = if (vpnActive) "ACTIVO" else "INACTIVO",
                                        color = Color.White,
                                        fontSize = 10.sp,
                                        fontWeight = FontWeight.Bold
                                    )
                                }
                            }
                            Text(
                                "Filtro DNS para bloquear dominios no kosher de Waze y DiDi. Pulse para configurar o activar la VPN.",
                                color = Color.LightGray,
                                fontSize = 11.sp,
                                modifier = Modifier.padding(top = 2.dp)
                            )
                        }

                        val navyDark = Color(0xFF0B192C)
                        Button(
                            onClick = {
                                val prepareIntent = android.net.VpnService.prepare(context)
                                if (prepareIntent != null) {
                                    try {
                                        (context as? Activity)?.startActivityForResult(prepareIntent, 1024)
                                    } catch (e: Exception) {
                                        e.printStackTrace()
                                        Toast.makeText(context, "Error al preparar VPN: ${e.message}", Toast.LENGTH_SHORT).show()
                                    }
                                } else {
                                    // Ya tiene permisos concedidos, iniciamos el servicio directamente
                                    try {
                                        val startIntent = Intent(context, com.ejemplo.locksuite.service.KosherVpnService::class.java)
                                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                                            context.startForegroundService(startIntent)
                                        } else {
                                            context.startService(startIntent)
                                        }
                                        Toast.makeText(context, "Filtro VPN DNS activado con éxito.", Toast.LENGTH_SHORT).show()
                                    } catch (e: Exception) {
                                        e.printStackTrace()
                                    }
                                }
                            },
                            colors = ButtonDefaults.buttonColors(containerColor = accentOrange, contentColor = navyDark),
                            shape = RoundedCornerShape(8.dp),
                            contentPadding = PaddingValues(horizontal = 12.dp, vertical = 4.dp),
                            modifier = Modifier.height(36.dp)
                        ) {
                            Text("Configurar", fontSize = 12.sp, fontWeight = FontWeight.Bold)
                        }
                    }
                }
            }
        }

        // Configuración de Factory Reset Protection (FRP)
        item {
            Card(
                colors = CardDefaults.cardColors(containerColor = navyMedium),
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(12.dp)
            ) {
                Column(
                    modifier = Modifier.padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    Text(
                        "Protección de Restablecimiento (FRP)",
                        color = accentOrange,
                        fontSize = 16.sp,
                        fontWeight = FontWeight.Bold
                    )

                    Text(
                        "Si el dispositivo es restablecido de fábrica por el menú de recuperación (Recovery), exigirá iniciar sesión con una cuenta de Google del administrador para poder activarse.",
                        color = Color.LightGray,
                        fontSize = 12.sp
                    )

                    val policyManager = remember { PolicyManager(context) }
                    var frpEnabled by remember { mutableStateOf(policyManager.isFrpEnabled()) }
                    var useDefaultFrp by remember { mutableStateOf(policyManager.useDefaultFrp()) }
                    var frpAccountsText by remember { 
                        mutableStateOf(policyManager.getFrpAccounts().joinToString(", ")) 
                    }

                    // Fila 1: Activar FRP
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text("Activar Bloqueo de FRP", color = Color.White, fontSize = 14.sp)
                        Switch(
                            checked = frpEnabled,
                            onCheckedChange = { enabled ->
                                val accountsList = if (useDefaultFrp) emptyList() else frpAccountsText.split(",")
                                    .map { it.trim() }
                                    .filter { it.length == 21 && it.all { c -> c.isDigit() } }
                                
                                if (enabled && !useDefaultFrp && accountsList.isEmpty()) {
                                    Toast.makeText(context, "Ingrese al menos un ID de Google de 21 dígitos válido", Toast.LENGTH_LONG).show()
                                } else {
                                    val success = policyManager.setFrpPolicy(accountsList, useDefaultFrp, enabled)
                                    if (success) {
                                        frpEnabled = enabled
                                        Toast.makeText(context, if (enabled) "FRP Activado" else "FRP Desactivado", Toast.LENGTH_SHORT).show()
                                    } else {
                                        Toast.makeText(context, "Error al configurar FRP.", Toast.LENGTH_LONG).show()
                                    }
                                }
                            },
                            colors = SwitchDefaults.colors(
                                checkedThumbColor = Color(0xFF0B192C),
                                checkedTrackColor = accentOrange
                            )
                        )
                    }

                    // Fila 2: Usar ID por defecto u otro
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text("Usar ID del Administrador por Defecto", color = Color.White, fontSize = 14.sp)
                            Text(
                                "Mantiene el ID predeterminado protegido y oculto sin mostrarlo en pantalla.",
                                color = Color.LightGray,
                                fontSize = 11.sp,
                                modifier = Modifier.padding(top = 2.dp)
                            )
                        }
                        Switch(
                            checked = useDefaultFrp,
                            onCheckedChange = { useDef ->
                                useDefaultFrp = useDef
                            },
                            colors = SwitchDefaults.colors(
                                checkedThumbColor = Color(0xFF0B192C),
                                checkedTrackColor = accentOrange
                            )
                        )
                    }

                    if (useDefaultFrp) {
                        Card(
                            colors = CardDefaults.cardColors(containerColor = Color(0xFF0B192C)),
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Text(
                                text = "Cuenta activa: [ID Predeterminado Protegido y Oculto] 🔒",
                                color = accentOrange,
                                fontSize = 13.sp,
                                fontWeight = FontWeight.Bold,
                                modifier = Modifier.padding(12.dp)
                            )
                        }
                    } else {
                        OutlinedTextField(
                            value = frpAccountsText,
                            onValueChange = { frpAccountsText = it },
                            label = { Text("IDs de Google Personalizados (21 dígitos, separados por comas)", color = Color.White.copy(alpha = 0.8f)) },
                            placeholder = { Text("Ej: ID_1, ID_2", color = Color.Gray) },
                            singleLine = true,
                            colors = OutlinedTextFieldDefaults.colors(
                                focusedBorderColor = accentOrange,
                                unfocusedBorderColor = Color.White.copy(alpha = 0.5f),
                                focusedLabelColor = accentOrange,
                                unfocusedLabelColor = Color.White,
                                focusedTextColor = Color.White,
                                unfocusedTextColor = Color.White
                            ),
                            modifier = Modifier.fillMaxWidth()
                        )
                    }

                    Button(
                        onClick = {
                            val accountsList = if (useDefaultFrp) emptyList() else frpAccountsText.split(",")
                                .map { it.trim() }
                                .filter { it.length == 21 && it.all { c -> c.isDigit() } }
                            
                            if (!useDefaultFrp && accountsList.isEmpty()) {
                                Toast.makeText(context, "Ingrese al menos un ID de Google de 21 dígitos válido", Toast.LENGTH_LONG).show()
                            } else {
                                val success = policyManager.setFrpPolicy(accountsList, useDefaultFrp, frpEnabled)
                                if (success) {
                                    Toast.makeText(context, "Configuración de FRP guardada correctamente", Toast.LENGTH_SHORT).show()
                                } else {
                                    Toast.makeText(context, "Error al guardar la configuración de FRP.", Toast.LENGTH_LONG).show()
                                }
                            }
                        },
                        colors = ButtonDefaults.buttonColors(containerColor = accentOrange, contentColor = Color(0xFF0B192C)),
                        shape = RoundedCornerShape(8.dp),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text("Guardar Configuración de FRP", fontWeight = FontWeight.Bold)
                    }

                    Text(
                        "Nota: Google exige el ID numérico de la cuenta (21 dígitos) y no el email. Para obtenerlo, inicie sesión y vaya a get.google.com/albumarchive; el ID estará en la URL.",
                        color = Color.Gray,
                        fontSize = 11.sp
                    )
                }
            }
        }

        // Desinstalación y Desvinculación
        item {
            Card(
                colors = CardDefaults.cardColors(containerColor = navyMedium),
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(12.dp)
            ) {
                Column(
                    modifier = Modifier.padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    Text(
                        "Desinstalación / Control de Permisos",
                        color = accentOrange,
                        fontSize = 16.sp,
                        fontWeight = FontWeight.Bold,
                        modifier = Modifier.padding(bottom = 4.dp)
                    )

                    Text(
                        "Advertencia: Estas acciones son irreversibles. Al quitar los privilegios, el dispositivo dejará de estar protegido por LockSuite.",
                        color = Color.LightGray,
                        fontSize = 11.sp
                    )

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        // Botón A: Solo quitar permisos MDM
                        Button(
                            onClick = {
                                onTriggerPermissionsReauth()
                            },
                            colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFE67E22)),
                            modifier = Modifier.weight(1f),
                            shape = RoundedCornerShape(8.dp)
                        ) {
                            Text("Quitar Permisos", fontSize = 11.sp, textAlign = TextAlign.Center)
                        }

                        // Botón B: Desinstalar completamente
                        Button(
                            onClick = {
                                onTriggerUninstallReauth()
                            },
                            colors = ButtonDefaults.buttonColors(containerColor = Color.Red),
                            modifier = Modifier.weight(1f),
                            shape = RoundedCornerShape(8.dp)
                        ) {
                            Text("Desinstalar App", fontSize = 11.sp, textAlign = TextAlign.Center)
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun DnsActivityTabContent(context: Context) {
    val engine = remember { LockSuiteApplication.domainRuleEngine }
    val buffer = remember { LockSuiteApplication.dnsActivityBuffer }
    val ruleManager = remember { LockSuiteApplication.domainRuleManager }

    var windowMinutes by remember { mutableIntStateOf(5) }
    var refreshKey by remember { mutableIntStateOf(0) }
    var searchQuery by remember { mutableStateOf("") }
    var newDomainText by remember { mutableStateOf("") }

    LaunchedEffect(windowMinutes) {
        while (true) {
            kotlinx.coroutines.delay(2000)
            refreshKey++
        }
    }

    LaunchedEffect(Unit) {
        buffer.events.collect { refreshKey++ }
    }

    // Reglas guardadas (persistentes): NO dependen de la ventana de 1h. Aca
    // aparecen TODAS aunque el dominio no se haya vuelto a consultar, y se
    // pueden quitar o forzar en cualquier momento. Arregla "no aparecen los
    // bloqueados / no los puedo desbloquear despues de una hora".
    val customRules = remember(refreshKey) {
        ruleManager.getAllRules().entries.sortedBy { it.key }
    }

    val activity = remember(refreshKey, windowMinutes, searchQuery) {
        val snapshot = buffer.snapshot(windowMinutes * 60_000L)
        val filtered = if (searchQuery.isBlank()) snapshot
                       else snapshot.filter { it.domain.contains(searchQuery, ignoreCase = true) }
        filtered.groupBy { it.domain }
            .map { (domain, hits) ->
                val sorted = hits.sortedBy { it.timestampMillis }
                DomainActivitySummary(
                    domain = domain,
                    firstSeenMillis = sorted.first().timestampMillis,
                    lastSeenMillis = sorted.last().timestampMillis,
                    hitCount = sorted.size,
                    lastAction = sorted.last().action,
                    explicitRule = engine.explicitRule(domain),
                    effectiveRule = engine.effectiveRule(domain)
                )
            }
            .sortedByDescending { it.lastSeenMillis }
    }

    LazyColumn(modifier = Modifier.fillMaxSize()) {
        item {
            Column(modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) {
                Text(
                    "Reglas DNS personalizadas",
                    color = Color(0xFFFF6D00),
                    fontWeight = FontWeight.Bold,
                    fontSize = 16.sp
                )
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    "Forzar bloqueo/permiso le gana a cualquier otra configuracion " +
                    "(WebView, AdBlocker, etc.). Bloquear/Permitir normal solo aplica " +
                    "si ninguna otra politica ya decide sobre ese dominio.",
                    color = Color.White.copy(alpha = 0.6f),
                    fontSize = 11.sp
                )
                Spacer(modifier = Modifier.height(8.dp))
                OutlinedTextField(
                    value = newDomainText,
                    onValueChange = { newDomainText = it },
                    modifier = Modifier.fillMaxWidth(),
                    placeholder = { Text("Ej: ejemplo.com") },
                    singleLine = true,
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedBorderColor = Color(0xFFFF6D00),
                        cursorColor = Color(0xFFFF6D00)
                    )
                )
                Spacer(modifier = Modifier.height(6.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    Button(
                        onClick = {
                            val d = newDomainText.trim()
                            if (d.isNotEmpty()) {
                                ruleManager.setRule(d, RuleType.BLOCK)
                                newDomainText = ""
                                refreshKey++
                            }
                        },
                        colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFB71C1C)),
                        contentPadding = PaddingValues(horizontal = 6.dp, vertical = 4.dp),
                        modifier = Modifier.weight(1f)
                    ) { Text("Bloquear", fontSize = 10.sp) }
                    Button(
                        onClick = {
                            val d = newDomainText.trim()
                            if (d.isNotEmpty()) {
                                ruleManager.setRule(d, RuleType.FORCE_BLOCK)
                                newDomainText = ""
                                refreshKey++
                            }
                        },
                        colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFFF1744)),
                        contentPadding = PaddingValues(horizontal = 6.dp, vertical = 4.dp),
                        modifier = Modifier.weight(1f)
                    ) { Text("Forzar bloq.", fontSize = 10.sp) }
                    Button(
                        onClick = {
                            val d = newDomainText.trim()
                            if (d.isNotEmpty()) {
                                ruleManager.setRule(d, RuleType.ALLOW)
                                newDomainText = ""
                                refreshKey++
                            }
                        },
                        colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF2E7D32)),
                        contentPadding = PaddingValues(horizontal = 6.dp, vertical = 4.dp),
                        modifier = Modifier.weight(1f)
                    ) { Text("Permitir", fontSize = 10.sp) }
                    Button(
                        onClick = {
                            val d = newDomainText.trim()
                            if (d.isNotEmpty()) {
                                ruleManager.setRule(d, RuleType.FORCE_ALLOW)
                                newDomainText = ""
                                refreshKey++
                            }
                        },
                        colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF00E676), contentColor = Color.Black),
                        contentPadding = PaddingValues(horizontal = 6.dp, vertical = 4.dp),
                        modifier = Modifier.weight(1f)
                    ) { Text("Forzar perm.", fontSize = 10.sp) }
                }
                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    if (customRules.isEmpty()) "No hay reglas guardadas todavia."
                    else "${customRules.size} regla(s) guardada(s):",
                    color = Color.Gray,
                    fontSize = 12.sp
                )
            }
        }

        items(customRules, key = { "rule_" + it.key }) { entry ->
            DnsCustomRuleRow(
                domain = entry.key,
                rule = entry.value,
                onForce = {
                    val forced = if (entry.value == RuleType.BLOCK) RuleType.FORCE_BLOCK else RuleType.FORCE_ALLOW
                    ruleManager.setRule(entry.key, forced)
                    refreshKey++
                },
                onRemove = {
                    ruleManager.clearRule(entry.key)
                    refreshKey++
                }
            )
            HorizontalDivider(color = Color.White.copy(alpha = 0.1f), thickness = 0.5.dp)
        }

        item {
            Spacer(modifier = Modifier.height(12.dp))
            Text(
                "Actividad reciente",
                color = Color(0xFFFF6D00),
                fontWeight = FontWeight.Bold,
                fontSize = 16.sp,
                modifier = Modifier.padding(horizontal = 16.dp)
            )
            Row(
                modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                listOf(1, 5, 15, 30, 60).forEach { minutes ->
                    FilterChip(
                        selected = minutes == windowMinutes,
                        onClick = { windowMinutes = minutes; refreshKey++ },
                        label = { Text(if (minutes == 60) "1h" else "${minutes}m") },
                        colors = FilterChipDefaults.filterChipColors(
                            selectedContainerColor = Color(0xFFFF6D00),
                            selectedLabelColor = Color.White
                        )
                    )
                }
            }
            OutlinedTextField(
                value = searchQuery,
                onValueChange = { searchQuery = it },
                modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp),
                placeholder = { Text("Buscar dominio...") },
                leadingIcon = { Icon(Icons.Default.Search, "Buscar") },
                singleLine = true,
                colors = OutlinedTextFieldDefaults.colors(
                    focusedBorderColor = Color(0xFFFF6D00),
                    cursorColor = Color(0xFFFF6D00)
                )
            )
            Spacer(modifier = Modifier.height(8.dp))
            Text(
                "${activity.size} dominios en los ultimos ${if (windowMinutes == 60) "60 min" else "$windowMinutes min"}",
                modifier = Modifier.padding(horizontal = 16.dp),
                style = MaterialTheme.typography.bodySmall,
                color = Color.Gray
            )
            Spacer(modifier = Modifier.height(4.dp))
        }

        if (activity.isEmpty()) {
            item {
                Box(Modifier.fillMaxWidth().padding(32.dp), contentAlignment = Alignment.Center) {
                    Text(
                        "Sin actividad DNS en esta ventana de tiempo.\n" +
                        "Abri una app y volve aca para ver sus dominios.",
                        textAlign = TextAlign.Center,
                        color = Color.Gray
                    )
                }
            }
        } else {
            items(activity, key = { "act_" + it.domain }) { summary ->
                DnsActivityRow(
                    summary = summary,
                    onBlock = {
                        ruleManager.setRule(summary.domain, RuleType.FORCE_BLOCK)
                        refreshKey++
                    },
                    onAllow = {
                        ruleManager.setRule(summary.domain, RuleType.FORCE_ALLOW)
                        refreshKey++
                    },
                    onClearRule = {
                        ruleManager.clearRule(summary.domain)
                        refreshKey++
                    }
                )
                HorizontalDivider(
                    color = Color.White.copy(alpha = 0.1f),
                    thickness = 0.5.dp
                )
            }
        }
    }
}

@Composable
private fun DnsCustomRuleRow(
    domain: String,
    rule: RuleType,
    onForce: () -> Unit,
    onRemove: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        val (estado, color) = when (rule) {
            RuleType.FORCE_BLOCK -> "🔒 Forzado: bloqueado" to Color(0xFFFF1744)
            RuleType.BLOCK -> "🔒 Bloqueado (normal)" to Color(0xFFFF5252)
            RuleType.FORCE_ALLOW -> "✅ Forzado: permitido" to Color(0xFF00E676)
            RuleType.ALLOW -> "✅ Permitido (normal)" to Color(0xFF69F0AE)
        }
        Box(
            modifier = Modifier
                .size(8.dp)
                .clip(RoundedCornerShape(4.dp))
                .background(color)
        )
        Spacer(modifier = Modifier.width(12.dp))
        Column(modifier = Modifier.weight(1f)) {
            Text(domain, fontWeight = FontWeight.Medium, color = Color.White, fontSize = 13.sp, maxLines = 1)
            Text(estado, color = color, style = MaterialTheme.typography.labelSmall)
        }
        if (rule == RuleType.BLOCK || rule == RuleType.ALLOW) {
            TextButton(onClick = onForce) {
                Text("Forzar", color = Color(0xFFFFD180), fontSize = 11.sp)
            }
        }
        TextButton(onClick = onRemove) {
            Text("Quitar", color = Color(0xFFFF5252), fontSize = 11.sp)
        }
    }
}

@Composable
private fun DnsActivityRow(
    summary: DomainActivitySummary,
    onBlock: () -> Unit,
    onAllow: () -> Unit,
    onClearRule: () -> Unit
) {
    val fmt = remember { java.text.SimpleDateFormat("HH:mm:ss", java.util.Locale.getDefault()) }

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        val indicatorColor = when (summary.effectiveRule) {
            RuleType.BLOCK, RuleType.FORCE_BLOCK -> Color(0xFFFF1744)
            RuleType.ALLOW, RuleType.FORCE_ALLOW -> Color(0xFF00E676)
            else -> Color(0xFFFF6D00)
        }
        Box(
            modifier = Modifier
                .size(8.dp)
                .clip(RoundedCornerShape(4.dp))
                .background(indicatorColor)
        )

        Spacer(modifier = Modifier.width(12.dp))

        Column(modifier = Modifier.weight(1f)) {
            Text(
                summary.domain,
                fontWeight = FontWeight.Medium,
                color = Color.White,
                fontSize = 13.sp,
                maxLines = 1
            )

            val rango = if (summary.firstSeenMillis == summary.lastSeenMillis) {
                fmt.format(java.util.Date(summary.lastSeenMillis))
            } else {
                "${fmt.format(java.util.Date(summary.firstSeenMillis))} → ${fmt.format(java.util.Date(summary.lastSeenMillis))}"
            }
            Text(
                "$rango  ·  ${summary.hitCount}x",
                style = MaterialTheme.typography.bodySmall,
                color = Color.Gray
            )

            val (estado, color) = when {
                summary.effectiveRule == RuleType.FORCE_BLOCK ->
                    "🔒 Forzado: bloqueado" to Color(0xFFFF5252)
                summary.effectiveRule == RuleType.FORCE_ALLOW ->
                    "✅ Forzado: permitido" to Color(0xFF69F0AE)
                summary.effectiveRule == RuleType.BLOCK && summary.explicitRule == null ->
                    "🔒 Bloqueado (heredado)" to Color(0xFFFF5252)
                summary.effectiveRule == RuleType.BLOCK ->
                    "🔒 Bloqueado (normal)" to Color(0xFFFF5252)
                summary.effectiveRule == RuleType.ALLOW ->
                    "✅ Permitido (normal)" to Color(0xFF69F0AE)
                else ->
                    "" to Color.Transparent
            }
            if (estado.isNotEmpty()) {
                Text(estado, color = color, style = MaterialTheme.typography.labelSmall)
            }
        }

        when {
            (summary.effectiveRule == RuleType.BLOCK || summary.effectiveRule == RuleType.FORCE_BLOCK) && summary.explicitRule == null ->
                TextButton(onClick = onAllow) {
                    Text("Permitir", color = Color(0xFF69F0AE), fontSize = 11.sp)
                }
            summary.explicitRule != null ->
                TextButton(onClick = onClearRule) {
                    Text("Quitar", color = Color(0xFFFF5252), fontSize = 11.sp)
                }
            else ->
                TextButton(onClick = onBlock) {
                    Text("Bloquear", color = Color(0xFFFF5252), fontSize = 11.sp)
                }
        }
    }
}
