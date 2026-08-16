package com.ejemplo.locksuite.ui.auth

import android.content.Intent
import android.os.Bundle
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
import com.ejemplo.locksuite.security.PinManager
import com.ejemplo.locksuite.ui.dashboard.DashboardActivity

/**
 * Configura el PIN de administrador único (por instalación). Se llega acá
 * después de ConsentActivity, en el primer uso del dispositivo.
 */
class SetupPinActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            SetupPinScreen(onPinSaved = {
                startActivity(Intent(this, DashboardActivity::class.java))
                finish()
            })
        }
    }
}

@Composable
fun SetupPinScreen(onPinSaved: () -> Unit) {
    val context = LocalContext.current
    var pin by remember { mutableStateOf("") }
    var confirmPin by remember { mutableStateOf("") }
    var errorMessage by remember { mutableStateOf("") }

    val navyDark = Color(0xFF0B192C)
    val accentOrange = Color(0xFFF1C40F)

    Column(
        modifier = Modifier.fillMaxSize().background(navyDark).padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Text("Configurar PIN de administrador", color = Color.White, fontSize = 20.sp, fontWeight = FontWeight.Bold, textAlign = TextAlign.Center)
        Spacer(modifier = Modifier.height(8.dp))
        Text(
            "Este PIN va a ser necesario para cambiar la configuración del modo kosher. Guardalo en un lugar seguro.",
            color = Color.LightGray, fontSize = 13.sp, textAlign = TextAlign.Center
        )
        Spacer(modifier = Modifier.height(24.dp))

        OutlinedTextField(
            value = pin,
            onValueChange = { if (it.length <= 16) pin = it },
            label = { Text("Nuevo PIN (mínimo 4 dígitos)") },
            visualTransformation = PasswordVisualTransformation(),
            singleLine = true,
            modifier = Modifier.fillMaxWidth()
        )
        Spacer(modifier = Modifier.height(12.dp))
        OutlinedTextField(
            value = confirmPin,
            onValueChange = { if (it.length <= 16) confirmPin = it },
            label = { Text("Confirmar PIN") },
            visualTransformation = PasswordVisualTransformation(),
            singleLine = true,
            modifier = Modifier.fillMaxWidth()
        )

        if (errorMessage.isNotEmpty()) {
            Spacer(modifier = Modifier.height(8.dp))
            Text(errorMessage, color = Color(0xFFFF8A80), fontSize = 13.sp)
        }

        Spacer(modifier = Modifier.height(20.dp))

        Button(
            onClick = {
                when {
                    pin.length < 4 -> errorMessage = "El PIN debe tener al menos 4 dígitos"
                    pin != confirmPin -> errorMessage = "Los PIN no coinciden"
                    else -> {
                        PinManager.saveAdminPin(context, pin)
                        onPinSaved()
                    }
                }
            },
            colors = ButtonDefaults.buttonColors(containerColor = accentOrange, contentColor = navyDark),
            shape = RoundedCornerShape(12.dp),
            modifier = Modifier.fillMaxWidth()
        ) {
            Text("Guardar PIN", fontWeight = FontWeight.Bold)
        }
    }
}
