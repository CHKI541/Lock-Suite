package com.ejemplo.locksuite.ui.onboarding

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.ejemplo.locksuite.ui.auth.SetupPinActivity
import com.ejemplo.locksuite.util.Constants
import com.ejemplo.locksuite.util.PrefsHelper

/**
 * Se muestra una única vez, antes de SetupPinActivity, la primera vez que se
 * configura el dispositivo. Es lo que distingue una herramienta de
 * restricción consentida de una encubierta: quien usa el teléfono ve,
 * explícitamente, qué se va a restringir y por qué, antes de que se aplique.
 */
class ConsentActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        if (PrefsHelper.getMdmPrefs(this).getBoolean(Constants.KEY_CONSENT_ACCEPTED, false)) {
            goToSetup()
            return
        }

        setContent {
            ConsentScreen(onAccept = {
                PrefsHelper.getMdmPrefs(this).edit()
                    .putBoolean(Constants.KEY_CONSENT_ACCEPTED, true)
                    .apply()
                goToSetup()
            })
        }
    }

    private fun goToSetup() {
        startActivity(Intent(this, SetupPinActivity::class.java))
        finish()
    }
}

@Composable
fun ConsentScreen(onAccept: () -> Unit) {
    val scrollState = rememberScrollState()
    var scrolledToEnd by remember { mutableStateOf(false) }

    LaunchedEffect(scrollState.value, scrollState.maxValue) {
        if (scrollState.maxValue == 0 || scrollState.value >= scrollState.maxValue - 16) {
            scrolledToEnd = true
        }
    }

    val navyDark = Color(0xFF0B192C)
    val navyMedium = Color(0xFF1E3E62)
    val accentOrange = Color(0xFFF1C40F)

    Column(modifier = Modifier.fillMaxSize().background(navyDark).padding(24.dp)) {
        Text(
            text = "Antes de continuar",
            color = accentOrange,
            fontSize = 24.sp,
            fontWeight = FontWeight.Bold
        )
        Spacer(modifier = Modifier.height(16.dp))

        Column(modifier = Modifier.weight(1f).verticalScroll(scrollState)) {
            Text(
                text = "Este dispositivo se va a configurar en modo restringido (\"kosher\"):",
                color = Color.White,
                fontSize = 15.sp
            )
            Spacer(modifier = Modifier.height(12.dp))

            listOf(
                "Solo se van a poder abrir las apps que el administrador defina explícitamente.",
                "El resto de las funciones (instalar apps, cámara, capturas de pantalla, Bluetooth, VPN, etc.) puede quedar bloqueado según lo configure el administrador.",
                "Un PIN de administrador es necesario para cambiar esta configuración.",
                "El ícono de esta app va a estar siempre visible en el menú de aplicaciones — esta app no se oculta ni corre de forma encubierta.",
                "Esta configuración se aplica porque el dueño del dispositivo dio su consentimiento para que así sea."
            ).forEach { line ->
                Row(modifier = Modifier.padding(vertical = 6.dp)) {
                    Text("•  ", color = accentOrange, fontSize = 15.sp)
                    Text(line, color = Color.LightGray, fontSize = 14.sp)
                }
            }

            Spacer(modifier = Modifier.height(16.dp))
            Text(
                text = "Si no estás de acuerdo con esta configuración, no continúes y consultá con quien te haya entregado este dispositivo.",
                color = Color.White,
                fontSize = 13.sp,
                fontWeight = FontWeight.Bold
            )
            Spacer(modifier = Modifier.height(24.dp))
        }

        Button(
            onClick = onAccept,
            enabled = scrolledToEnd,
            colors = ButtonDefaults.buttonColors(
                containerColor = accentOrange,
                contentColor = navyDark,
                disabledContainerColor = navyMedium
            ),
            shape = RoundedCornerShape(12.dp),
            modifier = Modifier.fillMaxWidth().padding(top = 12.dp)
        ) {
            Text(
                text = if (scrolledToEnd) "Entiendo y acepto" else "Desplazate para continuar",
                fontWeight = FontWeight.Bold,
                textAlign = TextAlign.Center
            )
        }
    }
}
