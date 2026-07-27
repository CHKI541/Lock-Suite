package com.ejemplo.locksuite.ui.auth

import android.content.Intent
import android.app.admin.DevicePolicyManager
import android.content.ComponentName
import android.app.PendingIntent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.content.Context
import kotlinx.coroutines.launch
import android.os.Handler
import com.google.firebase.database.FirebaseDatabase
import com.google.firebase.database.DataSnapshot
import com.google.firebase.database.DatabaseError
import com.google.firebase.database.ValueEventListener
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.TextButton
import androidx.compose.material3.CircularProgressIndicator
import android.os.Looper
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.OnBackPressedCallback
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Language
import com.ejemplo.locksuite.util.LocaleManager
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.*
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.ejemplo.locksuite.R
import com.ejemplo.locksuite.security.LockoutState
import com.ejemplo.locksuite.security.LockoutStatus
import com.ejemplo.locksuite.security.PinManager
import com.ejemplo.locksuite.security.SessionManager
import com.ejemplo.locksuite.ui.dashboard.DashboardActivity
import kotlinx.coroutines.delay

class LoginActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        com.ejemplo.locksuite.util.LocaleManager.init(this)
        
        // Proteger actividad contra capturas de pantalla/grabaciones (H12)
        window.addFlags(android.view.WindowManager.LayoutParams.FLAG_SECURE)

        // Redirigir a la configuración si no existe PIN
        if (!PinManager.isPinConfigured(this)) {
            startActivity(Intent(this, SetupPinActivity::class.java))
            finish()
            return
        }

        // Si la sesión ya estaba activa, pasar directamente al Dashboard
        if (SessionManager.isActive()) {
            startActivity(Intent(this, DashboardActivity::class.java))
            finish()
            return
        }

        // Bloquear el botón atrás — el usuario no puede salir de la pantalla de login
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                // No hacer nada — no se puede salir del login
            }
        })

        val openStore = intent?.getBooleanExtra("OPEN_STORE", false) ?: false

        setContent {
            LoginScreen(
                initialOpenStore = openStore,
                onLoginSuccess = {
                    SessionManager.openSession()
                    startActivity(Intent(this, DashboardActivity::class.java))
                    finish()
                }
            )
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
    }
}

data class StoreApp(
    val label: String = "",
    val packageName: String = "",
    val apkUrl: String = ""
)

@Composable
fun LoginScreen(
    initialOpenStore: Boolean = false,
    onLoginSuccess: () -> Unit
) {
    val context = LocalContext.current
    var showPinInput by remember { mutableStateOf(false) }
    var inputPin by remember { mutableStateOf("") }
    var lockoutTimeRemaining by remember { mutableLongStateOf(0L) }
    var warningText by remember { mutableStateOf("") }
    var showLanguageMenu by remember { mutableStateOf(false) }
    var langUpdateKey by remember { mutableIntStateOf(0) } // Forzar recomposición al cambiar de idioma
    
    var showStoreDialog by remember { mutableStateOf(initialOpenStore) }
    var storeAppsList by remember { mutableStateOf<List<StoreApp>>(emptyList()) }
    var allowedPackagesSet by remember { mutableStateOf<Set<String>>(emptySet()) }
    var isStoreLoading by remember { mutableStateOf(false) }
    
    var updateProgress by remember { mutableStateOf<Int?>(null) }
    var storeDownloadingPackage by remember { mutableStateOf<String?>(null) }
    var storeProgress by remember { mutableStateOf(0) }

    fun updateLockoutState() {
        when (val state = PinManager.getLockoutState(context)) {
            is LockoutState.Locked -> {
                lockoutTimeRemaining = state.remainingMs
                warningText = LocaleManager.t("entry_blocked")
            }
            LockoutState.Open -> {
                lockoutTimeRemaining = 0
                if (warningText == LocaleManager.t("entry_blocked")) {
                    warningText = ""
                }
            }
        }
    }

    // Efecto secundario optimizado con corrutinas de Kotlin (cancela el loop automáticamente al salir)
    LaunchedEffect(Unit) {
        while (true) {
            updateLockoutState()
            if (lockoutTimeRemaining > 0) {
                delay(1000L)
            } else {
                delay(3000L)
            }
        }
    }

    // Colores Dark-mode Premium
    val navyDark = Color(0xFF0B192C)
    val navyMedium = Color(0xFF1E3E62)
    val accentOrange = Color(0xFFF1C40F)
    val alertRed = Color(0xFFC0392B)

    val scrollState = rememberScrollState()
    val focusRequester = remember { FocusRequester() }

    LaunchedEffect(showPinInput) {
        if (showPinInput) {
            delay(100L)
            try {
                focusRequester.requestFocus()
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }
    }

    // Dummy block para forzar la lectura del trigger de idioma y recomponer dinámicamente
    if (langUpdateKey >= 0) {
        if (!showPinInput) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .background(navyDark)
                    .padding(24.dp)
                    .verticalScroll(rememberScrollState()),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.SpaceBetween
            ) {
                // Barra superior de iconos
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 8.dp),
                    horizontalArrangement = Arrangement.End,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    // Selector de idioma (Mundo)
                    Box {
                        IconButton(
                            onClick = { showLanguageMenu = true },
                            modifier = Modifier
                                .size(48.dp)
                                .background(navyMedium.copy(alpha = 0.5f), shape = CircleShape)
                        ) {
                            Icon(
                                imageVector = Icons.Default.Language,
                                contentDescription = "Idioma / Language / שפה",
                                tint = Color.White
                            )
                        }

                        DropdownMenu(
                            expanded = showLanguageMenu,
                            onDismissRequest = { showLanguageMenu = false },
                            modifier = Modifier.background(navyMedium)
                        ) {
                            DropdownMenuItem(
                                text = { Text("Español", color = Color.White) },
                                onClick = {
                                    LocaleManager.setLang(context, "es")
                                    langUpdateKey++
                                    showLanguageMenu = false
                                }
                            )
                            DropdownMenuItem(
                                text = { Text("English", color = Color.White) },
                                onClick = {
                                    LocaleManager.setLang(context, "en")
                                    langUpdateKey++
                                    showLanguageMenu = false
                                }
                            )
                            DropdownMenuItem(
                                text = { Text("עברית", color = Color.White) },
                                onClick = {
                                    LocaleManager.setLang(context, "he")
                                    langUpdateKey++
                                    showLanguageMenu = false
                                }
                            )
                        }
                    }

                    Spacer(modifier = Modifier.width(8.dp))

                    // Icono de configuración
                    IconButton(
                        onClick = { showPinInput = true },
                        modifier = Modifier
                            .size(48.dp)
                            .background(navyMedium.copy(alpha = 0.5f), shape = CircleShape)
                    ) {
                        Icon(
                            imageVector = Icons.Default.Settings,
                            contentDescription = LocaleManager.t("settings"),
                            tint = Color.White
                        )
                    }
                }

                Spacer(modifier = Modifier.height(24.dp))

                // Logo central
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Center
                ) {
                    Image(
                        painter = painterResource(id = R.drawable.ic_logo),
                        contentDescription = "LockSuite Logo",
                        modifier = Modifier
                            .size(140.dp)
                            .clip(RoundedCornerShape(24.dp))
                            .background(navyMedium)
                            .padding(20.dp)
                    )
                    Spacer(modifier = Modifier.height(24.dp))
                    Text(
                        text = LocaleManager.t("app_name"),
                        color = Color.White,
                        fontSize = 28.sp,
                        fontWeight = FontWeight.Bold,
                        textAlign = TextAlign.Center
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        text = LocaleManager.t("active_protection"),
                        color = Color(0xFF2ECC71),
                        fontSize = 16.sp,
                        fontWeight = FontWeight.SemiBold,
                        textAlign = TextAlign.Center
                    )
                }

                Spacer(modifier = Modifier.height(48.dp))

                // Botones y versión al fondo
                Column(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    val coroutineScope = rememberCoroutineScope()
                    val pInfo = try {
                        context.packageManager.getPackageInfo(context.packageName, 0)
                    } catch (e: Exception) {
                        null
                    }
                    val currentVersionName = pInfo?.versionName ?: "Unknown"
                    val currentVersionCode = if (pInfo != null) {
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                            pInfo.longVersionCode.toInt()
                        } else {
                            @Suppress("DEPRECATION")
                            pInfo.versionCode
                        }
                    } else {
                        0
                    }
                    
                    // Botón para Tienda Kosher
                    Button(
                        onClick = {
                            showStoreDialog = true
                        },
                        colors = ButtonDefaults.buttonColors(containerColor = accentOrange, contentColor = navyDark),
                        modifier = Modifier.fillMaxWidth(0.85f),
                        shape = RoundedCornerShape(12.dp)
                    ) {
                        Text(
                            text = if (LocaleManager.getLang() == "he") "חנות אפליקציות" else if (LocaleManager.getLang() == "en") "App Store" else "Tienda",
                            fontWeight = FontWeight.Bold
                        )
                    }

                    // Botón para Actualizar
                    Button(
                        onClick = {
                            coroutineScope.launch {
                                val db = FirebaseDatabase.getInstance()
                                val deviceId = com.ejemplo.locksuite.util.FirebaseDeviceSync.deviceId(context)
                                val error = com.ejemplo.locksuite.util.SelfUpdater.checkAndPerformUpdate(context, true) { progress ->
                                    coroutineScope.launch(kotlinx.coroutines.Dispatchers.Main) {
                                        updateProgress = progress
                                    }
                                    db.getReference("devices/$deviceId/updateProgress").setValue(progress)
                                }
                                updateProgress = null
                                db.getReference("devices/$deviceId/updateProgress").removeValue()
                                if (error != null) {
                                    Toast.makeText(context, error, Toast.LENGTH_LONG).show()
                                }
                            }
                        },
                        enabled = updateProgress == null,
                        colors = ButtonDefaults.buttonColors(
                            containerColor = accentOrange,
                            contentColor = navyDark,
                            disabledContainerColor = Color.White.copy(alpha = 0.1f),
                            disabledContentColor = Color.White.copy(alpha = 0.4f)
                        ),
                        modifier = Modifier.fillMaxWidth(0.85f),
                        shape = RoundedCornerShape(12.dp)
                    ) {
                        Text(
                            text = if (updateProgress != null) {
                                if (LocaleManager.getLang() == "he") "מוריד: ${updateProgress}%" else if (LocaleManager.getLang() == "en") "Downloading: ${updateProgress}%" else "Descargando: ${updateProgress}%"
                            } else {
                                if (LocaleManager.getLang() == "he") "עדכן אפליקציה" else if (LocaleManager.getLang() == "en") "Update App" else "Actualizar Aplicación"
                            },
                            fontWeight = FontWeight.Bold
                        )
                    }

                    Spacer(modifier = Modifier.height(8.dp))

                    // Información de versión
                    Text(
                        text = "LockSuite v$currentVersionName ($currentVersionCode)",
                        color = Color.White.copy(alpha = 0.5f),
                        fontSize = 12.sp,
                        textAlign = TextAlign.Center
                    )
                }
            }
        } else {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .background(navyDark)
                    .verticalScroll(scrollState)
                    .focusRequester(focusRequester)
                    .focusable()
                    .onKeyEvent { keyEvent ->
                        if (keyEvent.type == KeyEventType.KeyDown) {
                            val isLocked = lockoutTimeRemaining > 0
                            if (isLocked) return@onKeyEvent false

                            when (keyEvent.key) {
                                Key.Backspace -> {
                                    if (inputPin.isNotEmpty()) {
                                        inputPin = inputPin.dropLast(1)
                                    }
                                    true
                                }
                                Key.Enter -> {
                                    if (inputPin.isNotEmpty()) {
                                        if (PinManager.verifyPin(context, inputPin)) {
                                            PinManager.resetAttempts(context)
                                            onLoginSuccess()
                                        } else {
                                            inputPin = ""
                                            when (val status = PinManager.recordFailedAttempt(context)) {
                                                LockoutStatus.LockedOut -> {
                                                    updateLockoutState()
                                                    Toast.makeText(context, LocaleManager.t("device_locked_5m"), Toast.LENGTH_LONG).show()
                                                }
                                                is LockoutStatus.Warning -> {
                                                    warningText = LocaleManager.t("incorrect_pin") + "${status.remainingAttempts}"
                                                }
                                            }
                                        }
                                    }
                                    true
                                }
                                Key.Escape -> {
                                    inputPin = ""
                                    true
                                }
                                else -> {
                                    val codePoint = keyEvent.utf16CodePoint
                                    if (codePoint > 0) {
                                        val char = codePoint.toChar()
                                        if (char.isDigit()) {
                                            if (inputPin.length < 16) {
                                                inputPin += char
                                            }
                                            true
                                        } else if (char == 'c' || char == 'C') {
                                            inputPin = ""
                                            true
                                        } else {
                                            false
                                        }
                                    } else {
                                        false
                                    }
                                }
                            }
                        } else {
                            false
                        }
                    }
                    .padding(24.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(32.dp)
            ) {
                // Barra superior con botón de regreso
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.Start
                ) {
                    IconButton(
                        onClick = { showPinInput = false },
                        modifier = Modifier
                            .size(48.dp)
                            .background(navyMedium.copy(alpha = 0.5f), shape = CircleShape)
                    ) {
                        Icon(
                            imageVector = Icons.Default.ArrowBack,
                            contentDescription = LocaleManager.t("back"),
                            tint = Color.White
                        )
                    }
                }

                // Cabecera con Logo
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    modifier = Modifier.padding(top = 24.dp)
                ) {
                    Image(
                        painter = painterResource(id = R.drawable.ic_logo),
                        contentDescription = "LockSuite Logo",
                        modifier = Modifier
                            .size(100.dp)
                            .clip(RoundedCornerShape(16.dp))
                            .background(navyMedium)
                            .padding(12.dp)
                    )
                    
                    Spacer(modifier = Modifier.height(16.dp))
                    
                    Text(
                        text = LocaleManager.t("app_name"),
                        color = Color.White,
                        fontSize = 24.sp,
                        fontWeight = FontWeight.Bold,
                        textAlign = TextAlign.Center
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    
                    if (lockoutTimeRemaining > 0) {
                        val sec = (lockoutTimeRemaining / 1000) % 60
                        val min = (lockoutTimeRemaining / 1000 / 60)
                        Text(
                            text = LocaleManager.t("try_again_in") + "%02d:%02d".format(min, sec),
                            color = alertRed,
                            fontSize = 16.sp,
                            fontWeight = FontWeight.Bold,
                            textAlign = TextAlign.Center
                        )
                    } else {
                        Text(
                            text = LocaleManager.t("enter_pin"),
                            color = Color.LightGray,
                            fontSize = 16.sp,
                            textAlign = TextAlign.Center
                        )
                    }
                    
                    if (warningText.isNotEmpty()) {
                        Spacer(modifier = Modifier.height(4.dp))
                        Text(
                            text = warningText,
                            color = if (lockoutTimeRemaining > 0) alertRed else accentOrange,
                            fontSize = 14.sp,
                            textAlign = TextAlign.Center
                        )
                    }
                }

                // Círculos del PIN
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.Center,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    val length = inputPin.length
                    val displayLength = if (length > 4) length else 4
                    for (i in 0 until displayLength) {
                        val filled = i < length
                        Box(
                            modifier = Modifier
                                .size(16.dp)
                                .padding(horizontal = 4.dp)
                                .clip(CircleShape)
                                .background(if (filled) accentOrange else navyMedium)
                        )
                    }
                }

                // Teclado Numérico
                Column(
                    modifier = Modifier.padding(bottom = 24.dp),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    val keys = listOf(
                        listOf("1", "2", "3"),
                        listOf("4", "5", "6"),
                        listOf("7", "8", "9"),
                        listOf("C", "0", "OK")
                    )

                    keys.forEach { row ->
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceEvenly
                        ) {
                            row.forEach { key ->
                                val isLocked = lockoutTimeRemaining > 0
                                Button(
                                    onClick = {
                                        if (isLocked) return@Button
                                        when (key) {
                                            "C" -> inputPin = ""
                                            "OK" -> {
                                                if (inputPin.isNotEmpty()) {
                                                    if (PinManager.verifyPin(context, inputPin)) {
                                                        PinManager.resetAttempts(context)
                                                        onLoginSuccess()
                                                    } else {
                                                        inputPin = ""
                                                        when (val status = PinManager.recordFailedAttempt(context)) {
                                                            LockoutStatus.LockedOut -> {
                                                                updateLockoutState()
                                                                Toast.makeText(context, LocaleManager.t("device_locked_5m"), Toast.LENGTH_LONG).show()
                                                            }
                                                            is LockoutStatus.Warning -> {
                                                                warningText = LocaleManager.t("incorrect_pin") + "${status.remainingAttempts}"
                                                            }
                                                        }
                                                    }
                                                }
                                            }
                                            else -> {
                                                if (inputPin.length < 16) {
                                                    inputPin += key
                                                }
                                            }
                                        }
                                    },
                                    enabled = !isLocked,
                                    colors = ButtonDefaults.buttonColors(
                                        containerColor = if (key == "OK") accentOrange else navyMedium,
                                        contentColor = if (key == "OK") navyDark else Color.White,
                                        disabledContainerColor = navyMedium.copy(alpha = 0.3f),
                                        disabledContentColor = Color.Gray
                                    ),
                                    shape = RoundedCornerShape(12.dp),
                                    modifier = Modifier
                                        .size(72.dp)
                                        .padding(4.dp)
                                ) {
                                    Text(
                                        text = key,
                                        fontSize = 18.sp,
                                        fontWeight = FontWeight.Bold,
                                        textAlign = TextAlign.Center
                                    )
                                }
                            }
                        }
                        Spacer(modifier = Modifier.height(8.dp))
                    }
                }
            }
        }
    }

    LaunchedEffect(showStoreDialog) {
        if (showStoreDialog) {
            isStoreLoading = true
            val deviceId = com.ejemplo.locksuite.util.FirebaseDeviceSync.deviceId(context)
            val db = FirebaseDatabase.getInstance()
            
            var globalList = emptyList<String>()
            var deviceList = emptyList<String>()
            
            fun updateSet() {
                allowedPackagesSet = (globalList + deviceList + context.packageName + "com.ejemplo.locksuite").toSet()
            }
            
            db.getReference("globalSettings/allowedPackages").addListenerForSingleValueEvent(object : ValueEventListener {
                override fun onDataChange(snapshot: DataSnapshot) {
                    globalList = snapshot.children.mapNotNull { it.getValue(String::class.java) }
                    updateSet()
                }
                override fun onCancelled(error: DatabaseError) {
                    android.util.Log.w("LoginActivity", "globalSettings query cancelled: ${error.message}")
                }
            })
            
            db.getReference("devices/$deviceId/allowedPackages").addListenerForSingleValueEvent(object : ValueEventListener {
                override fun onDataChange(devSnap: DataSnapshot) {
                    deviceList = devSnap.children.mapNotNull { it.getValue(String::class.java) }
                    updateSet()
                }
                override fun onCancelled(error: DatabaseError) {
                    android.util.Log.w("LoginActivity", "device allowedPackages query cancelled: ${error.message}")
                }
            })
            
            db.getReference("storeApps").addListenerForSingleValueEvent(object : ValueEventListener {
                override fun onDataChange(storeSnap: DataSnapshot) {
                    val apps = storeSnap.children.mapNotNull {
                        val label = it.child("label").getValue(String::class.java) ?: ""
                        val pkg = it.child("packageName").getValue(String::class.java) ?: ""
                        val apk = it.child("apkUrl").getValue(String::class.java) ?: ""
                        if (label.isNotEmpty() && pkg.isNotEmpty() && apk.isNotEmpty()) {
                            StoreApp(label, pkg, apk)
                        } else null
                    }
                    storeAppsList = apps
                    isStoreLoading = false
                }
                override fun onCancelled(error: DatabaseError) {
                    android.util.Log.e("LoginActivity", "storeApps query failed: ${error.message}")
                    isStoreLoading = false
                }
            })
        }
    }

    if (showStoreDialog) {
        AlertDialog(
            onDismissRequest = { showStoreDialog = false },
            title = {
                Text(
                    text = if (LocaleManager.getLang() == "he") "חנות אפליקציות" else if (LocaleManager.getLang() == "en") "App Store" else "Tienda",
                    color = Color.White,
                    fontWeight = FontWeight.Bold,
                    fontSize = 20.sp
                )
            },
            text = {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(350.dp),
                    contentAlignment = Alignment.Center
                ) {
                    if (isStoreLoading) {
                        CircularProgressIndicator(color = accentOrange)
                    } else if (storeAppsList.isEmpty()) {
                        Text(
                            text = if (LocaleManager.getLang() == "he") "אין אפליקציות בחנות כרגע" else if (LocaleManager.getLang() == "en") "No apps available in the store" else "No hay aplicaciones disponibles en la tienda.",
                            color = Color.White.copy(alpha = 0.7f),
                            textAlign = TextAlign.Center
                        )
                    } else {
                        androidx.compose.foundation.lazy.LazyColumn(
                            modifier = Modifier.fillMaxSize(),
                            verticalArrangement = Arrangement.spacedBy(12.dp)
                        ) {
                            items(storeAppsList.size) { index ->
                                val app = storeAppsList[index]
                                val isAllowed = allowedPackagesSet.contains(app.packageName)
                                val coroutineScope = rememberCoroutineScope()
                                
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .background(navyMedium.copy(alpha = 0.5f), shape = RoundedCornerShape(12.dp))
                                        .padding(12.dp),
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Column(modifier = Modifier.weight(1f)) {
                                        Text(app.label, color = Color.White, fontWeight = FontWeight.Bold, fontSize = 15.sp)
                                        Spacer(modifier = Modifier.height(2.dp))
                                        Text(app.packageName, color = Color.White.copy(alpha = 0.6f), fontSize = 11.sp)
                                    }
                                    
                                    val isDownloadingThis = storeDownloadingPackage == app.packageName
                                    Button(
                                        onClick = {
                                            if (isAllowed && storeDownloadingPackage == null) {
                                                coroutineScope.launch {
                                                    storeDownloadingPackage = app.packageName
                                                    val db = FirebaseDatabase.getInstance()
                                                    val deviceId = com.ejemplo.locksuite.util.FirebaseDeviceSync.deviceId(context)
                                                    val err = com.ejemplo.locksuite.util.SelfUpdater.downloadAndInstallApk(
                                                        context,
                                                        app.apkUrl,
                                                        app.packageName,
                                                        app.label
                                                    ) { progress ->
                                                        coroutineScope.launch(kotlinx.coroutines.Dispatchers.Main) {
                                                            storeProgress = progress
                                                        }
                                                        db.getReference("devices/$deviceId/updateProgress").setValue(progress)
                                                    }
                                                    storeDownloadingPackage = null
                                                    db.getReference("devices/$deviceId/updateProgress").removeValue()
                                                    if (err != null) {
                                                        Toast.makeText(context, err, Toast.LENGTH_LONG).show()
                                                    } else {
                                                        Toast.makeText(context, "${app.label} instalado correctamente.", Toast.LENGTH_LONG).show()
                                                    }
                                                }
                                            }
                                        },
                                        enabled = isAllowed && (storeDownloadingPackage == null || isDownloadingThis),
                                        colors = ButtonDefaults.buttonColors(
                                            containerColor = accentOrange,
                                            contentColor = navyDark,
                                            disabledContainerColor = Color.White.copy(alpha = 0.1f),
                                            disabledContentColor = Color.White.copy(alpha = 0.4f)
                                        ),
                                        shape = RoundedCornerShape(8.dp),
                                        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp)
                                    ) {
                                        Text(
                                            text = if (isDownloadingThis) {
                                                if (LocaleManager.getLang() == "he") "מוריד: ${storeProgress}%" else if (LocaleManager.getLang() == "en") "Downloading: ${storeProgress}%" else "Descargando: ${storeProgress}%"
                                            } else if (isAllowed) {
                                                if (LocaleManager.getLang() == "he") "הורד" else if (LocaleManager.getLang() == "en") "Download" else "Instalar"
                                            } else {
                                                if (LocaleManager.getLang() == "he") "חסום" else if (LocaleManager.getLang() == "en") "Blocked" else "Bloqueada"
                                            },
                                            fontSize = 12.sp,
                                            fontWeight = FontWeight.Bold
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { showStoreDialog = false }) {
                    Text(
                        text = if (LocaleManager.getLang() == "he") "סגור" else if (LocaleManager.getLang() == "en") "Close" else "Cerrar",
                        color = accentOrange,
                        fontWeight = FontWeight.Bold
                    )
                }
            },
            containerColor = navyDark,
            shape = RoundedCornerShape(18.dp)
        )
    }
}
