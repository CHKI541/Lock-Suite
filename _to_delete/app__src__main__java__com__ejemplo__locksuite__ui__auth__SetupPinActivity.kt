package com.ejemplo.locksuite.ui.auth

import android.content.Intent
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.OnBackPressedCallback
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.*
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.ejemplo.locksuite.security.PinManager
import com.ejemplo.locksuite.security.SessionManager
import com.ejemplo.locksuite.ui.dashboard.DashboardActivity

class SetupPinActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        
        // Proteger actividad contra capturas de pantalla/grabaciones (H12)
        window.addFlags(android.view.WindowManager.LayoutParams.FLAG_SECURE)

        // Si ya está configurado, redirigir a LoginActivity
        if (PinManager.isPinConfigured(this)) {
            startActivity(Intent(this, LoginActivity::class.java))
            finish()
            return
        }

        // Bloquear el botón atrás
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                // No hacer nada — no se puede salir del setup
            }
        })

        setContent {
            SetupPinScreen(
                onPinSet = { pin ->
                    PinManager.saveAdminPin(this, pin)
                    
                    Toast.makeText(this, "PIN de Administrador configurado", Toast.LENGTH_SHORT).show()
                    SessionManager.openSession()
                    startActivity(Intent(this, DashboardActivity::class.java))
                    finish()
                }
            )
        }
    }
}

@Composable
fun SetupPinScreen(onPinSet: (String) -> Unit) {
    var pin1 by remember { mutableStateOf("") }
    var pin2 by remember { mutableStateOf("") }
    var isConfirming by remember { mutableStateOf(false) }
    var errorMessage by remember { mutableStateOf("") }
    
    val activePin = if (isConfirming) pin2 else pin1
    val headerText = if (isConfirming) "Confirma el PIN" else "Crea tu PIN de Administrador"
    val subtext = if (isConfirming) "Ingresa el mismo PIN para confirmar" else "El PIN debe tener entre 4 y 16 dígitos"

    // Colores Dark-mode Premium
    val navyDark = Color(0xFF0B192C)
    val navyMedium = Color(0xFF1E3E62)
    val accentOrange = Color(0xFFF1C40F)
    val alertRed = Color(0xFFC0392B)

    val scrollState = rememberScrollState()
    val focusRequester = remember { FocusRequester() }

    LaunchedEffect(Unit) {
        focusRequester.requestFocus()
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(navyDark)
            .verticalScroll(scrollState)
            .focusRequester(focusRequester)
            .focusable()
            .onKeyEvent { keyEvent ->
                if (keyEvent.type == KeyEventType.KeyDown) {
                    errorMessage = ""
                    when (keyEvent.key) {
                        Key.Backspace -> {
                            if (isConfirming) {
                                if (pin2.isNotEmpty()) pin2 = pin2.dropLast(1)
                            } else {
                                if (pin1.isNotEmpty()) pin1 = pin1.dropLast(1)
                            }
                            true
                        }
                        Key.Enter -> {
                            if (!isConfirming) {
                                if (pin1.length in 4..16) {
                                    isConfirming = true
                                } else {
                                    errorMessage = "El PIN debe tener entre 4 y 16 dígitos"
                                }
                            } else {
                                if (pin1 == pin2) {
                                    onPinSet(pin1)
                                } else {
                                    errorMessage = "Los PINs no coinciden. Intente de nuevo."
                                    isConfirming = false
                                    pin1 = ""
                                    pin2 = ""
                                }
                            }
                            true
                        }
                        Key.Escape -> {
                            if (isConfirming) pin2 = "" else pin1 = ""
                            true
                        }
                        else -> {
                            val codePoint = keyEvent.utf16CodePoint
                            if (codePoint > 0) {
                                val char = codePoint.toChar()
                                if (char.isDigit()) {
                                    if (isConfirming) {
                                        if (pin2.length < 16) pin2 += char
                                    } else {
                                        if (pin1.length < 16) pin1 += char
                                    }
                                    true
                                } else if (char == 'c' || char == 'C') {
                                    if (isConfirming) pin2 = "" else pin1 = ""
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
        // Cabecera
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            modifier = Modifier.padding(top = 24.dp)
        ) {
            Text(
                text = "LockSuite MDM",
                color = accentOrange,
                fontSize = 28.sp,
                fontWeight = FontWeight.Bold,
                textAlign = TextAlign.Center
            )
            Spacer(modifier = Modifier.height(16.dp))
            Text(
                text = headerText,
                color = Color.White,
                fontSize = 20.sp,
                fontWeight = FontWeight.SemiBold,
                textAlign = TextAlign.Center
            )
            Spacer(modifier = Modifier.height(8.dp))
            Text(
                text = subtext,
                color = Color.LightGray,
                fontSize = 14.sp,
                textAlign = TextAlign.Center
            )
            // Mensaje de error visible
            if (errorMessage.isNotEmpty()) {
                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    text = errorMessage,
                    color = alertRed,
                    fontSize = 14.sp,
                    fontWeight = FontWeight.Bold,
                    textAlign = TextAlign.Center
                )
            }
        }

        // Visualización del PIN en círculos discretos
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.Center,
            verticalAlignment = Alignment.CenterVertically
        ) {
            val length = activePin.length
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
                        Button(
                            onClick = {
                                errorMessage = ""
                                when (key) {
                                    "C" -> {
                                        if (isConfirming) pin2 = "" else pin1 = ""
                                    }
                                    "OK" -> {
                                        if (!isConfirming) {
                                            if (pin1.length in 4..16) {
                                                if (isTrivialPin(pin1)) {
                                                    errorMessage = "PIN muy débil (no use secuencias o dígitos idénticos)"
                                                } else {
                                                    isConfirming = true
                                                }
                                            } else {
                                                errorMessage = "El PIN debe tener entre 4 y 16 dígitos"
                                            }
                                        } else {
                                            if (pin1 == pin2) {
                                                onPinSet(pin1)
                                            } else {
                                                errorMessage = "Los PINs no coinciden. Intente de nuevo."
                                                isConfirming = false
                                                pin1 = ""
                                                pin2 = ""
                                            }
                                        }
                                    }
                                    else -> {
                                        val current = if (isConfirming) pin2 else pin1
                                        if (current.length < 16) {
                                            if (isConfirming) pin2 += key else pin1 += key
                                        }
                                    }
                                }
                            },
                            colors = ButtonDefaults.buttonColors(
                                containerColor = if (key == "OK") accentOrange else navyMedium,
                                contentColor = if (key == "OK") navyDark else Color.White
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

private fun isTrivialPin(pin: String): Boolean {
    if (pin.all { it == pin[0] }) return true
    
    var ascending = true
    for (i in 0 until pin.length - 1) {
        if (pin[i + 1].code - pin[i].code != 1) {
            ascending = false
            break
        }
    }
    if (ascending) return true

    var descending = true
    for (i in 0 until pin.length - 1) {
        if (pin[i].code - pin[i + 1].code != 1) {
            descending = false
            break
        }
    }
    if (descending) return true

    return false
}
