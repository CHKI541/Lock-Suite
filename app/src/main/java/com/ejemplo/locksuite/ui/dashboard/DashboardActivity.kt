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

    override fun onStop() {
        super.onStop()
        if (!isChangingConfigurations) {
            SessionManager.closeSession()
            finish()
        }
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
            TabRow(
                selectedTabIndex = selectedTab,
                containerColor = navyDark,
                contentColor = accentOrange,
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
            }

            when (selectedTab) {
                0 -> PoliciesTabContent(context)
                1 -> AppManagerTabContent(context)
                2 -> ServicesTabContent(
                    context = context,
                    onTriggerPermissionsReauth = { showReauthDialogForPermissions = true },
                    onTriggerUninstallReauth = { showReauthDialogForUninstall = true }
                )
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
                                prefs.edit().putString("device_name", deviceNameInput).apply()
                                com.ejemplo.locksuite.util.FirebaseDeviceSync.syncDeviceInfo(context)
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
                    label = "Bloquear Restauración de Fábrica",
                    isChecked = remember(refreshKey) { policyManager.isRestrictionEnabled(android.os.UserManager.DISALLOW_FACTORY_RESET) },
                    onCheckedChange = { policyManager.setFactoryResetBlocked(it).also { refreshKey++ } }
                )
                PolicySwitchRow(
                    label = "Bloquear Instalación de Apps",
                    isChecked = remember(refreshKey) { policyManager.isRestrictionEnabled(android.os.UserManager.DISALLOW_INSTALL_APPS) },
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
    onUninstallClick: () -> Unit
) {
    var hideState by remember { mutableStateOf(app.isHidden) }
    var suspendState by remember { mutableStateOf(app.isSuspended) }
    var webviewState by remember { mutableStateOf(app.isWebViewBlocked) }
    var expanded by remember { mutableStateOf(false) }
    var imageBlockingMode by remember { mutableStateOf(app.imageBlockingMode) }
    val scope = rememberCoroutineScope()
    val context = LocalContext.current

    LaunchedEffect(app) {
        hideState = app.isHidden
        suspendState = app.isSuspended
        webviewState = app.isWebViewBlocked
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
                    expanded = !expanded
                    Toast.makeText(context, "Engranaje presionado: $expanded", Toast.LENGTH_SHORT).show()
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
                }
            }
        }
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
                    StatusLabelRow(label = "Servicio Watchdog (Persistencia)", value = "ACTIVO (Servicio de Primer Plano)")
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
                            Text("Bloquear Ofertas en Mercado Pago", color = Color.White, fontSize = 14.sp)
                            Text(
                                "Bloquea la sección de Ofertas, Descuentos y Promociones manteniendo pagos y transferencias activos.",
                                color = Color.LightGray,
                                fontSize = 11.sp,
                                modifier = Modifier.padding(top = 2.dp)
                            )
                        }
                        var blockMpOffersState by remember {
                            mutableStateOf(policyManager.isMercadoPagoBlockOffersEnabled())
                        }
                        Switch(
                            checked = blockMpOffersState,
                            onCheckedChange = { enabled ->
                                policyManager.setMercadoPagoBlockOffers(enabled)
                                blockMpOffersState = enabled
                                scope.launch(Dispatchers.IO) {
                                    com.ejemplo.locksuite.util.FirebaseDeviceSync.syncDeviceInfo(context)
                                }
                                Toast.makeText(context, if (enabled) "Ofertas de MP Bloqueadas." else "Ofertas de MP Permitidas.", Toast.LENGTH_SHORT).show()
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
fun StatusLabelRow(label: String, value: String) {
    Column(modifier = Modifier.padding(vertical = 4.dp)) {
        Text(label, color = Color.LightGray, fontSize = 12.sp)
        Text(value, color = Color.White, fontSize = 14.sp, fontWeight = FontWeight.Bold)
        Spacer(modifier = Modifier.height(4.dp))
    }
}
