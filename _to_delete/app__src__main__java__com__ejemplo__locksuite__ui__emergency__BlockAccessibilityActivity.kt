package com.ejemplo.locksuite.ui.emergency

import android.app.Activity
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.provider.Settings
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.OnBackPressedCallback
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
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.ejemplo.locksuite.mdm.PolicyManager
import com.ejemplo.locksuite.service.LockSuiteAccessibilityService
import com.ejemplo.locksuite.util.LocaleManager
import kotlinx.coroutines.delay

class BlockAccessibilityActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Prevenir capturas de pantalla o grabaciones (H12)
        window.addFlags(android.view.WindowManager.LayoutParams.FLAG_SECURE)

        // Bloquear botón atrás
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                // No hacer nada - no se permite salir
            }
        })

        setContent {
            BlockAccessibilityScreen()
        }
    }
}

@Composable
fun BlockAccessibilityScreen() {
    val context = LocalContext.current
    val alertDarkRed = Color(0xFF7B241C)
    val accentYellow = Color(0xFFF1C40F)

    // Verificar si el servicio está habilitado
    fun isAccessibilityServiceEnabled(context: Context): Boolean {
        val enabledServices = Settings.Secure.getString(
            context.contentResolver,
            Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES
        ) ?: ""
        val shortId = "com.ejemplo.locksuite/.service.LockSuiteAccessibilityService"
        val longId = "com.ejemplo.locksuite/com.ejemplo.locksuite.service.LockSuiteAccessibilityService"
        return enabledServices.contains(shortId) || enabledServices.contains(longId)
    }

    LaunchedEffect(Unit) {
        while (true) {
            if (isAccessibilityServiceEnabled(context)) {
                val policyManager = PolicyManager(context)
                val prefs = com.ejemplo.locksuite.util.PrefsHelper.getMdmPrefs(context)
                // Restaurar navegadores solo si fueron suspendidos por el watchdog
                if (prefs.getBoolean("browsers_suspended_by_watchdog", false)) {
                    policyManager.setBrowsersSuspended(false)
                    prefs.edit().remove("browsers_suspended_by_watchdog").apply()
                }
                Toast.makeText(context, "Servicio de accesibilidad activado.", Toast.LENGTH_SHORT).show()
                (context as? Activity)?.finish()
            }
            delay(1000L)
        }
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(alertDarkRed)
            .padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Text(
            text = LocaleManager.t("block_acc_title").uppercase(),
            color = accentYellow,
            fontSize = 22.sp,
            fontWeight = FontWeight.Bold,
            textAlign = TextAlign.Center
        )
        
        Spacer(modifier = Modifier.height(24.dp))
        
        Text(
            text = LocaleManager.t("block_acc_desc"),
            color = Color.White,
            fontSize = 16.sp,
            textAlign = TextAlign.Center,
            modifier = Modifier.padding(horizontal = 8.dp)
        )
        
        Spacer(modifier = Modifier.height(32.dp))
        
        Button(
            onClick = {
                // Pausar temporalmente el Watchdog por 60 segundos para permitir la navegación
                com.ejemplo.locksuite.service.WatchdogForegroundService.temporaryPauseUntil = 
                    System.currentTimeMillis() + 60 * 1000L
                try {
                    val intent = Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS).apply {
                        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    }
                    context.startActivity(intent)
                } catch (e: Exception) {
                    e.printStackTrace()
                    Toast.makeText(context, "Error al abrir Ajustes", Toast.LENGTH_SHORT).show()
                }
            },
            colors = ButtonDefaults.buttonColors(
                containerColor = accentYellow,
                contentColor = alertDarkRed
            ),
            shape = RoundedCornerShape(12.dp),
            modifier = Modifier
                .fillMaxWidth()
                .height(56.dp)
        ) {
            Text(
                text = LocaleManager.t("block_acc_btn").uppercase(),
                fontSize = 16.sp,
                fontWeight = FontWeight.Bold
            )
        }
    }
}
