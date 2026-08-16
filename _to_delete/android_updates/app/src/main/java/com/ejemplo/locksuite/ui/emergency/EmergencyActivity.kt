package com.ejemplo.locksuite.ui.emergency

import android.app.admin.DevicePolicyManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.ejemplo.locksuite.mdm.PolicyManager
import com.ejemplo.locksuite.receiver.DeviceAdminReceiver
import com.ejemplo.locksuite.security.PinManager

/**
 * CAMBIO: antes verificaba contra un "PIN Maestro" separado, hardcodeado en
 * Constants.MASTER_PIN_HASH/SALT (una misma constante para todas las
 * instalaciones, extraíble del APK). Ahora usa el mismo PinManager.verifyPin
 * que el panel de administración — un único PIN, generado por instalación.
 * Si en tu operación el rol "puede purgar" debe ser distinto del rol "puede
 * cambiar políticas del día a día", lo correcto es modelar dos PIN
 * explícitos (por ejemplo KEY_PIN_HASH y KEY_RECOVERY_PIN_HASH, cada uno
 * generado en su propio flujo de configuración) — no un valor fijo en el
 * código igual para todas las instalaciones.
 */
class EmergencyActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            EmergencyScreen(onPurgeComplete = { finish() })
        }
    }
}

@Composable
fun EmergencyScreen(onPurgeComplete: () -> Unit) {
    val context = LocalContext.current
    var inputPin by remember { mutableStateOf("") }
    var errorMessage by remember { mutableStateOf("") }
    var isProcessing by remember { mutableStateOf(false) }

    val alertRed = Color(0xFF7A1712)
    val darkRed = Color(0xFF3D0C09)

    Column(
        modifier = Modifier.fillMaxSize().background(darkRed).padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Text(
            text = "PURGA DE EMERGENCIA",
            color = Color.White,
            fontSize = 22.sp,
            fontWeight = FontWeight.Bold,
            textAlign = TextAlign.Center
        )
        Spacer(modifier = Modifier.height(8.dp))
        Text(
            text = "Esto va a quitar todas las restricciones, salir del modo kiosco y revocar el rol de administrador del dispositivo. Requiere el PIN de administrador.",
            color = Color.LightGray,
            fontSize = 13.sp,
            textAlign = TextAlign.Center
        )
        Spacer(modifier = Modifier.height(24.dp))

        OutlinedTextField(
            value = inputPin,
            onValueChange = { if (it.length <= 16) inputPin = it },
            label = { Text("PIN de administrador") },
            visualTransformation = PasswordVisualTransformation(),
            singleLine = true,
            enabled = !isProcessing,
            modifier = Modifier.fillMaxWidth()
        )

        if (errorMessage.isNotEmpty()) {
            Spacer(modifier = Modifier.height(8.dp))
            Text(errorMessage, color = Color(0xFFFFCDD2), fontSize = 13.sp)
        }

        Spacer(modifier = Modifier.height(20.dp))

        Button(
            onClick = {
                if (isProcessing) return@Button
                if (PinManager.verifyPin(context, inputPin)) {
                    isProcessing = true
                    performEmergencyPurge(context)
                    Toast.makeText(context, "Restricciones eliminadas", Toast.LENGTH_LONG).show()
                    onPurgeComplete()
                } else {
                    errorMessage = "PIN incorrecto"
                    inputPin = ""
                }
            },
            enabled = inputPin.isNotEmpty() && !isProcessing,
            colors = ButtonDefaults.buttonColors(containerColor = alertRed, contentColor = Color.White),
            shape = RoundedCornerShape(12.dp),
            modifier = Modifier.fillMaxWidth()
        ) {
            Text(if (isProcessing) "Procesando..." else "Confirmar Purga", fontWeight = FontWeight.Bold)
        }
    }
}

private fun performEmergencyPurge(context: Context) {
    val policyManager = PolicyManager(context)
    val dpm = context.getSystemService(Context.DEVICE_POLICY_SERVICE) as DevicePolicyManager
    val adminComponent = ComponentName(context, DeviceAdminReceiver::class.java)

    // Unhide any hidden apps before losing Device Owner privileges
    try {
        val appController = com.ejemplo.locksuite.mdm.AppController(context)
        val apps = appController.getUserApps()
        // Wait, under android_updates, AppController's getUserApps returns a list of InstalledAppInfo, but let's check:
        // Does InstalledAppInfo have an isHidden field? Let's check AppController.kt in android_updates:
        // data class InstalledAppInfo(packageName, label, icon, isSystemApp)
        // Ah! In android_updates' AppController.kt, InstalledAppInfo does NOT have isHidden or isSuspended fields!
        // But AppController has `setAppHidden(packageName, false)`. So we can just call it for all returned apps or query hidden apps ourselves!
        // Actually, we can just run setAppHidden(app.packageName, false) for all apps! That is safe because setAppHidden(..., false) on an already visible app is a no-op!
        for (app in apps) {
            appController.setAppHidden(app.packageName, false)
        }
    } catch (e: Exception) {
        e.printStackTrace()
    }

    // 1. Salir del modo kiosco / lista blanca
    try {
        policyManager.setAllowedPackages(emptySet())
    } catch (e: Exception) {
        e.printStackTrace()
    }

    // 2. Limpiar todas las restricciones de usuario aplicadas
    listOf(
        android.os.UserManager.DISALLOW_INSTALL_APPS,
        android.os.UserManager.DISALLOW_UNINSTALL_APPS,
        android.os.UserManager.DISALLOW_CONFIG_WIFI,
        android.os.UserManager.DISALLOW_BLUETOOTH,
        android.os.UserManager.DISALLOW_CONFIG_VPN,
        android.os.UserManager.DISALLOW_CONFIG_TETHERING,
        android.os.UserManager.DISALLOW_FACTORY_RESET,
        android.os.UserManager.DISALLOW_SAFE_BOOT,
        android.os.UserManager.DISALLOW_DEBUGGING_FEATURES
    ).forEach {
        try {
            dpm.clearUserRestriction(adminComponent, it)
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    try {
        dpm.setCameraDisabled(adminComponent, false)
        dpm.setScreenCaptureDisabled(adminComponent, false)
    } catch (e: Exception) {
        e.printStackTrace()
    }

    // 3. Revocar el rol de Device Owner
    try {
        dpm.clearDeviceOwnerApp(context.packageName)
    } catch (e: Exception) {
        e.printStackTrace()
    }
}
