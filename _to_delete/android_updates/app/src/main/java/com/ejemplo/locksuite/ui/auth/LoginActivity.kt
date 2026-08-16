package com.ejemplo.locksuite.ui.auth

import android.content.Intent
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.OnBackPressedCallback
import androidx.activity.compose.setContent
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
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
import com.ejemplo.locksuite.ui.onboarding.ConsentActivity
import com.ejemplo.locksuite.util.Constants
import com.ejemplo.locksuite.util.PrefsHelper
import kotlinx.coroutines.delay

/**
 * Cambios respecto a la versión anterior:
 * - Ya no bloquea el botón atrás de forma absoluta. Esta pantalla es
 *   solamente la puerta al panel de administración; el uso diario del
 *   celular kosher pasa por KioskLauncherActivity, no por acá. Cancelar el
 *   intento de entrar al panel es una acción normal, no una "fuga".
 * - Primer uso (sin PIN configurado) enruta a ConsentActivity antes de
 *   SetupPinActivity.
 * - Se eliminaron los campos `handler`/`updateRunnable` a nivel de Activity:
 *   quedaban declarados pero nunca se usaban (el polling real vivía en el
 *   Composable). El polling ahora es una corrutina con LaunchedEffect, que
 *   Compose cancela sola al salir de composición.
 */
class LoginActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        if (!PinManager.isPinConfigured(this)) {
            val consentAccepted = PrefsHelper.getMdmPrefs(this)
                .getBoolean(Constants.KEY_CONSENT_ACCEPTED, false)
            val target = if (consentAccepted) SetupPinActivity::class.java else ConsentActivity::class.java
            startActivity(Intent(this, target))
            finish()
            return
        }

        if (SessionManager.isActive()) {
            startActivity(Intent(this, DashboardActivity::class.java))
            finish()
            return
        }

        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                finish()
            }
        })

        setContent {
            LoginScreen(onLoginSuccess = {
                SessionManager.startSession()
                startActivity(Intent(this, DashboardActivity::class.java))
                finish()
            })
        }
    }
}

@Composable
fun LoginScreen(onLoginSuccess: () -> Unit) {
    val context = LocalContext.current
    var inputPin by remember { mutableStateOf("") }
    var lockoutTimeRemaining by remember { mutableStateOf(0L) }
    var warningText by remember { mutableStateOf("") }

    fun refreshLockoutState() {
        when (val state = PinManager.getLockoutState(context)) {
            is LockoutState.Locked -> {
                lockoutTimeRemaining = state.remainingMs
                warningText = "Entrada bloqueada por seguridad"
            }
            LockoutState.Open -> {
                lockoutTimeRemaining = 0L
                if (warningText.startsWith("Entrada")) warningText = ""
            }
        }
    }

    LaunchedEffect(Unit) {
        while (true) {
            refreshLockoutState()
            delay(if (lockoutTimeRemaining > 0) 1000L else 3000L)
        }
    }

    val navyDark = Color(0xFF0B192C)
    val navyMedium = Color(0xFF1E3E62)
    val accentOrange = Color(0xFFF1C40F)
    val alertRed = Color(0xFFC0392B)

    Column(
        modifier = Modifier.fillMaxSize().background(navyDark).padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.SpaceBetween
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.padding(top = 32.dp)) {
            Image(
                painter = painterResource(id = R.drawable.ic_logo),
                contentDescription = "LockSuite Logo",
                modifier = Modifier.size(100.dp).clip(RoundedCornerShape(16.dp)).background(navyMedium).padding(12.dp)
            )
            Spacer(modifier = Modifier.height(16.dp))
            Text("LockSuite Kosher", color = Color.White, fontSize = 24.sp, fontWeight = FontWeight.Bold, textAlign = TextAlign.Center)
            Spacer(modifier = Modifier.height(8.dp))

            if (lockoutTimeRemaining > 0) {
                val sec = (lockoutTimeRemaining / 1000) % 60
                val min = (lockoutTimeRemaining / 1000 / 60)
                Text(
                    text = "Intente nuevamente en %02d:%02d".format(min, sec),
                    color = alertRed, fontSize = 16.sp, fontWeight = FontWeight.Bold, textAlign = TextAlign.Center
                )
            } else {
                Text("Ingrese PIN de Administrador", color = Color.LightGray, fontSize = 16.sp, textAlign = TextAlign.Center)
            }

            if (warningText.isNotEmpty()) {
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = warningText,
                    color = if (lockoutTimeRemaining > 0) alertRed else accentOrange,
                    fontSize = 14.sp, textAlign = TextAlign.Center
                )
            }
        }

        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.Center, verticalAlignment = Alignment.CenterVertically) {
            val length = inputPin.length
            val displaySlots = if (length > 8) length else 8
            for (i in 0 until displaySlots) {
                val filled = i < length
                Box(
                    modifier = Modifier.size(16.dp).padding(horizontal = 4.dp)
                        .clip(CircleShape)
                        .background(if (filled) accentOrange else navyMedium)
                )
            }
        }

        Column(modifier = Modifier.padding(bottom = 24.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            val keys = listOf(
                listOf("1", "2", "3"),
                listOf("4", "5", "6"),
                listOf("7", "8", "9"),
                listOf("C", "0", "OK")
            )
            keys.forEach { row ->
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
                    row.forEach { key ->
                        val isLocked = lockoutTimeRemaining > 0
                        Button(
                            onClick = {
                                if (isLocked) return@Button
                                when (key) {
                                    "C" -> inputPin = ""
                                    "OK" -> {
                                        if (inputPin.isEmpty()) return@Button
                                        if (PinManager.verifyPin(context, inputPin)) {
                                            PinManager.resetAttempts(context)
                                            onLoginSuccess()
                                        } else {
                                            inputPin = ""
                                            when (val status = PinManager.recordFailedAttempt(context)) {
                                                LockoutStatus.LockedOut -> {
                                                    refreshLockoutState()
                                                    Toast.makeText(context, "Dispositivo bloqueado por 5 minutos", Toast.LENGTH_LONG).show()
                                                }
                                                is LockoutStatus.Warning -> {
                                                    warningText = "PIN incorrecto. Intentos restantes: ${status.remainingAttempts}"
                                                }
                                            }
                                        }
                                    }
                                    else -> if (inputPin.length < 16) inputPin += key
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
                            modifier = Modifier.size(72.dp).padding(4.dp)
                        ) {
                            Text(key, fontSize = 18.sp, fontWeight = FontWeight.Bold, textAlign = TextAlign.Center)
                        }
                    }
                }
                Spacer(modifier = Modifier.height(8.dp))
            }
        }
    }
}
