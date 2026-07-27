package com.ejemplo.locksuite.ui.emergency

import android.app.admin.DevicePolicyManager
import android.content.ComponentName
import android.content.Context
import android.content.pm.PackageManager
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.ejemplo.locksuite.mdm.PolicyManager
import com.ejemplo.locksuite.security.PinManager
import com.ejemplo.locksuite.security.SessionManager
import com.ejemplo.locksuite.util.LocaleManager
import kotlinx.coroutines.delay
import com.ejemplo.locksuite.security.LockoutState
import com.ejemplo.locksuite.security.LockoutStatus

class EmergencyActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        
        // Proteger actividad contra capturas de pantalla/grabaciones (H12)
        window.addFlags(android.view.WindowManager.LayoutParams.FLAG_SECURE)

        setContent {
            EmergencyScreen(
                onPurgeSuccess = {
                    executeFullPurge()
                }
            )
        }
    }

    private fun executeFullPurge() {
        val policyManager = PolicyManager(this)
        val dpm = getSystemService(Context.DEVICE_POLICY_SERVICE) as DevicePolicyManager
        val adminComponent = ComponentName(this, com.ejemplo.locksuite.receiver.DeviceAdminReceiver::class.java)

        Toast.makeText(this, "Iniciando purga total de políticas...", Toast.LENGTH_LONG).show()

        // 1. Unhide any hidden apps before losing Device Owner privileges
        try {
            val appController = com.ejemplo.locksuite.mdm.AppController(this)
            val apps = appController.getUserApps(loadIcon = false)
            for (app in apps) {
                if (app.isHidden) {
                    appController.hideApp(app.packageName, false)
                }
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }

        // 2. Limpiar todas las restricciones de usuario de PolicyManager
        policyManager.clearAllRestrictions()

        // 3. Reactivar launcher alias
        setStealthMode(false)

        // 4. Reactivar la barra de estado
        try {
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.P) {
                dpm.setStatusBarDisabled(adminComponent, false)
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }

        // 5. Revocar privilegios de Device Owner (irreversible)
        try {
            dpm.clearDeviceOwnerApp(packageName)
        } catch (e: Exception) {
            e.printStackTrace()
            Toast.makeText(this, "Error al quitar Device Owner: ${e.message}", Toast.LENGTH_LONG).show()
        }

        // 6. Cerrar sesión y salir
        SessionManager.closeSession()
        Toast.makeText(this, "Purga de emergencia completa. Dispositivo liberado.", Toast.LENGTH_LONG).show()
        finishAffinity()
    }

    private fun setStealthMode(enabled: Boolean) {
        val aliasComponent = ComponentName(this, "com.ejemplo.locksuite.LauncherAlias")
        val state = if (enabled) {
            PackageManager.COMPONENT_ENABLED_STATE_DISABLED
        } else {
            PackageManager.COMPONENT_ENABLED_STATE_ENABLED
        }
        try {
            packageManager.setComponentEnabledSetting(
                aliasComponent,
                state,
                PackageManager.DONT_KILL_APP
            )
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun EmergencyScreen(onPurgeSuccess: () -> Unit) {
    val context = LocalContext.current
    var inputPassword by remember { mutableStateOf("") }
    var errorMessage by remember { mutableStateOf("") }
    var passwordVisible by remember { mutableStateOf(false) }
    var lockoutTimeRemaining by remember { mutableLongStateOf(0L) }

    fun updateLockoutState() {
        when (val state = PinManager.getLockoutState(context)) {
            is LockoutState.Locked -> {
                lockoutTimeRemaining = state.remainingMs
                errorMessage = LocaleManager.t("entry_blocked") + " ${state.remainingMs / 1000}s"
            }
            LockoutState.Open -> {
                lockoutTimeRemaining = 0
                if (errorMessage.startsWith(LocaleManager.t("entry_blocked"))) {
                    errorMessage = ""
                }
            }
        }
    }

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

    val alertRed = Color(0xFFC0392B)
    val alertDarkRed = Color(0xFF7B241C)
    val accentYellow = Color(0xFFF1C40F)

    val scrollState = rememberScrollState()

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(alertDarkRed)
            .verticalScroll(scrollState)
            .padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(32.dp)
    ) {
        // Encabezado
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            modifier = Modifier.padding(top = 24.dp)
        ) {
            Text(
                text = LocaleManager.t("emerg_title"),
                color = accentYellow,
                fontSize = 24.sp,
                fontWeight = FontWeight.Bold,
                textAlign = TextAlign.Center
            )
            Spacer(modifier = Modifier.height(16.dp))
            Text(
                text = if (LocaleManager.getLang() == "he") "אזהרה" else if (LocaleManager.getLang() == "en") "WARNING" else "ADVERTENCIA",
                color = Color.White,
                fontSize = 18.sp,
                fontWeight = FontWeight.Bold,
                textAlign = TextAlign.Center
            )
            Spacer(modifier = Modifier.height(8.dp))
            Text(
                text = LocaleManager.t("emerg_warning"),
                color = Color.LightGray,
                fontSize = 14.sp,
                textAlign = TextAlign.Center
            )
        }

        // Campo de entrada de contraseña QWERTY seguro
        Column(
            modifier = Modifier.fillMaxWidth(),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            OutlinedTextField(
                value = inputPassword,
                onValueChange = { 
                    inputPassword = it 
                    errorMessage = ""
                },
                enabled = lockoutTimeRemaining <= 0,
                label = { Text(LocaleManager.t("emerg_pin"), color = Color.White.copy(alpha = 0.8f)) },
                visualTransformation = if (passwordVisible) VisualTransformation.None else PasswordVisualTransformation(),
                singleLine = true,
                colors = OutlinedTextFieldDefaults.colors(
                    focusedBorderColor = accentYellow,
                    unfocusedBorderColor = Color.White.copy(alpha = 0.5f),
                    focusedLabelColor = accentYellow,
                    unfocusedLabelColor = Color.White,
                    focusedTextColor = Color.White,
                    unfocusedTextColor = Color.White,
                    disabledTextColor = Color.Gray,
                    disabledBorderColor = Color.White.copy(alpha = 0.2f),
                    disabledLabelColor = Color.Gray
                ),
                trailingIcon = {
                    val label = if (passwordVisible) {
                        if (LocaleManager.getLang() == "he") "הסתר" else if (LocaleManager.getLang() == "en") "HIDE" else "OCULTAR"
                    } else {
                        if (LocaleManager.getLang() == "he") "הצג" else if (LocaleManager.getLang() == "en") "SHOW" else "MOSTRAR"
                    }
                    TextButton(onClick = { passwordVisible = !passwordVisible }, enabled = lockoutTimeRemaining <= 0) {
                        Text(
                            text = label,
                            color = if (lockoutTimeRemaining <= 0) accentYellow else Color.Gray,
                            fontWeight = FontWeight.Bold,
                            fontSize = 12.sp
                        )
                    }
                },
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp)
            )

            if (errorMessage.isNotEmpty()) {
                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    text = errorMessage,
                    color = accentYellow,
                    fontSize = 14.sp,
                    fontWeight = FontWeight.Bold,
                    textAlign = TextAlign.Center
                )
            }
        }

        // Botón de ejecución
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(bottom = 24.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Button(
                onClick = {
                    if (inputPassword.isNotEmpty() && lockoutTimeRemaining <= 0) {
                        if (PinManager.verifyMasterPassword(inputPassword)) {
                            PinManager.resetAttempts(context)
                            onPurgeSuccess()
                        } else {
                            inputPassword = ""
                            when (val status = PinManager.recordFailedAttempt(context)) {
                                is LockoutStatus.LockedOut -> {
                                    updateLockoutState()
                                }
                                is LockoutStatus.Warning -> {
                                    errorMessage = LocaleManager.t("incorrect_pin") + "${status.remainingAttempts}"
                                }
                            }
                        }
                    }
                },
                enabled = lockoutTimeRemaining <= 0,
                colors = ButtonDefaults.buttonColors(
                    containerColor = accentYellow,
                    contentColor = alertDarkRed,
                    disabledContainerColor = Color.Gray,
                    disabledContentColor = Color.DarkGray
                ),
                shape = RoundedCornerShape(12.dp),
                modifier = Modifier
                    .fillMaxWidth()
                    .height(56.dp)
                    .padding(horizontal = 16.dp)
            ) {
                Text(
                    text = LocaleManager.t("emerg_wipe"),
                    fontSize = 16.sp,
                    fontWeight = FontWeight.Bold
                )
            }
        }
    }
}
