package com.ejemplo.locksuite.ui.launcher

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.media.AudioManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import android.content.ComponentName
import android.content.pm.PackageManager
import com.ejemplo.locksuite.ui.auth.LoginActivity


class KosherSettingsActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        
        // Deshabilitar captura de pantalla en configuración por seguridad (opcional, como en Dashboard)
        window.addFlags(android.view.WindowManager.LayoutParams.FLAG_SECURE)

        setContent {
            KosherSettingsScreen(
                onBack = { finish() }
            )
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun KosherSettingsScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val audioManager = remember { context.getSystemService(Context.AUDIO_SERVICE) as AudioManager }
    
    // Volume state
    val maxMusicVol = remember { audioManager.getStreamMaxVolume(AudioManager.STREAM_MUSIC).toFloat() }
    var musicVol by remember { 
        mutableFloatStateOf(audioManager.getStreamVolume(AudioManager.STREAM_MUSIC).toFloat()) 
    }
    
    val maxAlarmVol = remember { audioManager.getStreamMaxVolume(AudioManager.STREAM_ALARM).toFloat() }
    var alarmVol by remember { 
        mutableFloatStateOf(audioManager.getStreamVolume(AudioManager.STREAM_ALARM).toFloat()) 
    }

    // Brightness state
    var brightness by remember {
        mutableFloatStateOf(
            try {
                Settings.System.getInt(context.contentResolver, Settings.System.SCREEN_BRIGHTNESS).toFloat()
            } catch (e: Exception) {
                128f
            }
        )
    }

    var canWriteSystemSettings by remember {
        mutableStateOf(if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) Settings.System.canWrite(context) else true)
    }

    val isStealth = remember {
        try {
            val aliasComponent = ComponentName(context, "com.ejemplo.locksuite.LauncherAlias")
            context.packageManager.getComponentEnabledSetting(aliasComponent) == PackageManager.COMPONENT_ENABLED_STATE_DISABLED
        } catch (e: Exception) {
            false
        }
    }


    val scrollState = rememberScrollState()

    // Dark Premium Theme
    MaterialTheme(
        colorScheme = darkColorScheme(
            primary = Color(0xFFFF9100), // Orange
            background = Color(0xFF121212),
            surface = Color(0xFF1E1E1E),
            onBackground = Color.White,
            onSurface = Color.White
        )
    ) {
        Scaffold(
            topBar = {
                TopAppBar(
                    title = { Text("Ajustes Kosher", fontSize = 18.sp, fontWeight = FontWeight.Bold, color = Color.White) },
                    navigationIcon = {
                        IconButton(onClick = onBack) {
                            Icon(Icons.Filled.ArrowBack, contentDescription = "Volver", tint = Color.White)
                        }
                    },
                    colors = TopAppBarDefaults.topAppBarColors(containerColor = Color(0xFF1A1A1A))
                )
            },
            containerColor = Color(0xFF121212)
        ) { padding ->
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding)
                    .padding(16.dp)
                    .verticalScroll(scrollState),
                verticalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                // VOLUMEN SECCIÓN
                SettingsGroup(title = "Sonido y Volumen") {
                    Column(modifier = Modifier.padding(8.dp)) {
                        Text("Volumen Multimedia", fontSize = 14.sp, color = Color.LightGray)
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(Icons.Filled.VolumeUp, contentDescription = null, tint = Color.LightGray)
                            Spacer(modifier = Modifier.width(8.dp))
                            Slider(
                                value = musicVol,
                                onValueChange = {
                                    musicVol = it
                                    audioManager.setStreamVolume(AudioManager.STREAM_MUSIC, it.toInt(), 0)
                                },
                                valueRange = 0f..maxMusicVol,
                                modifier = Modifier.weight(1f)
                            )
                        }
                        
                        Spacer(modifier = Modifier.height(8.dp))
                        
                        Text("Volumen Alarma", fontSize = 14.sp, color = Color.LightGray)
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(Icons.Filled.Alarm, contentDescription = null, tint = Color.LightGray)
                            Spacer(modifier = Modifier.width(8.dp))
                            Slider(
                                value = alarmVol,
                                onValueChange = {
                                    alarmVol = it
                                    audioManager.setStreamVolume(AudioManager.STREAM_ALARM, it.toInt(), 0)
                                },
                                valueRange = 0f..maxAlarmVol,
                                modifier = Modifier.weight(1f)
                            )
                        }
                    }
                }

                // BRILLO SECCIÓN
                SettingsGroup(title = "Brillo de Pantalla") {
                    Column(modifier = Modifier.padding(8.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(Icons.Filled.BrightnessMedium, contentDescription = null, tint = Color.LightGray)
                            Spacer(modifier = Modifier.width(8.dp))
                            Slider(
                                value = brightness,
                                onValueChange = {
                                    brightness = it
                                    if (canWriteSystemSettings) {
                                        try {
                                            Settings.System.putInt(
                                                context.contentResolver, 
                                                Settings.System.SCREEN_BRIGHTNESS, 
                                                it.toInt()
                                            )
                                        } catch (e: Exception) {
                                            e.printStackTrace()
                                        }
                                    } else {
                                        // Fallback: cambiar brillo local de la ventana si es posible
                                        (context as? Activity)?.window?.attributes?.let { layoutParams ->
                                            layoutParams.screenBrightness = it / 255f
                                            context.window.attributes = layoutParams
                                        }
                                    }
                                },
                                valueRange = 10f..255f,
                                modifier = Modifier.weight(1f)
                            )
                        }
                        
                        if (!canWriteSystemSettings) {
                            Button(
                                onClick = {
                                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                                        try {
                                            val intent = Intent(Settings.ACTION_MANAGE_WRITE_SETTINGS).apply {
                                                data = Uri.parse("package:${context.packageName}")
                                                flags = Intent.FLAG_ACTIVITY_NEW_TASK
                                            }
                                            context.startActivity(intent)
                                        } catch (e: Exception) {
                                            e.printStackTrace()
                                        }
                                    }
                                },
                                colors = ButtonDefaults.buttonColors(containerColor = Color.DarkGray),
                                shape = RoundedCornerShape(8.dp),
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Text("Permitir ajustar brillo permanentemente", fontSize = 12.sp)
                            }
                        }
                    }
                }

                // CONECTIVIDAD SECCIÓN
                SettingsGroup(title = "Conectividad") {
                    Column(modifier = Modifier.padding(4.dp)) {
                        SettingsButton(
                            label = "Configurar Bluetooth",
                            icon = Icons.Filled.Bluetooth,
                            onClick = {
                                try {
                                    val intent = Intent(Settings.ACTION_BLUETOOTH_SETTINGS).apply {
                                        flags = Intent.FLAG_ACTIVITY_NEW_TASK
                                    }
                                    context.startActivity(intent)
                                } catch (e: Exception) {
                                    Toast.makeText(context, "No se pudo abrir Bluetooth", Toast.LENGTH_SHORT).show()
                                }
                            }
                        )
                    }
                }

                // CONFIGURACIÓN DE DISPOSITIVO SECCIÓN
                SettingsGroup(title = "Dispositivo") {
                    Column(modifier = Modifier.padding(4.dp)) {
                        SettingsButton(
                            label = "Configurar fecha y hora",
                            icon = Icons.Filled.AccessTime,
                            onClick = {
                                try {
                                    val intent = Intent(Settings.ACTION_DATE_SETTINGS).apply {
                                        flags = Intent.FLAG_ACTIVITY_NEW_TASK
                                    }
                                    context.startActivity(intent)
                                } catch (e: Exception) {
                                    Toast.makeText(context, "No se pudo abrir Fecha y Hora", Toast.LENGTH_SHORT).show()
                                }
                            }
                        )

                        Spacer(modifier = Modifier.height(8.dp))

                        SettingsButton(
                            label = "Encendido/Apagado Temporizado",
                            icon = Icons.Filled.PowerSettingsNew,
                            onClick = {
                                val intentsToTry = listOf(
                                    Intent("android.intent.action.SCHEDULE_POWER_ON_OFF"),
                                    Intent().setComponent(ComponentName("com.android.settings", "com.android.settings.SchedulePowerOnOffSetting")),
                                    Intent().setComponent(ComponentName("com.mediatek.schpwronoff", "com.mediatek.schpwronoff.SchPwrOnOffActivity")),
                                    Intent().setComponent(ComponentName("com.android.settings", "com.android.settings.Settings\$SchedulePowerOnOffSettingActivity")),
                                    Intent().setComponent(ComponentName("com.android.settings", "com.android.settings.SchedulePowerOnOff")),
                                    Intent().setComponent(ComponentName("com.android.settings", "com.android.settings.Settings\$SchedulePowerOnOffActivity"))
                                )

                                var success = false
                                for (intent in intentsToTry) {
                                    try {
                                        intent.flags = Intent.FLAG_ACTIVITY_NEW_TASK
                                        context.startActivity(intent)
                                        success = true
                                        break
                                    } catch (e: Exception) {
                                        // Seguir intentando
                                    }
                                }

                                if (!success) {
                                    Toast.makeText(
                                        context, 
                                        "Tu dispositivo no soporta encendido/apagado temporizado por hardware", 
                                        Toast.LENGTH_LONG
                                    ).show()
                                }
                            }
                        )

                        Spacer(modifier = Modifier.height(8.dp))

                        SettingsButton(
                            label = "Configurar idioma y teclado",
                            icon = Icons.Filled.Language,
                            onClick = {
                                try {
                                    // Abre la pantalla de administración de teclados (Gboard, idiomas de entrada, etc.)
                                    val intent = Intent(Settings.ACTION_INPUT_METHOD_SETTINGS).apply {
                                        flags = Intent.FLAG_ACTIVITY_NEW_TASK
                                    }
                                    context.startActivity(intent)
                                } catch (e: Exception) {
                                    try {
                                        // Fallback a la configuración de idioma general del sistema
                                        val intentLocale = Intent(Settings.ACTION_LOCALE_SETTINGS).apply {
                                            flags = Intent.FLAG_ACTIVITY_NEW_TASK
                                        }
                                        context.startActivity(intentLocale)
                                    } catch (ex: Exception) {
                                        Toast.makeText(context, "No se pudo abrir Ajustes de Idioma", Toast.LENGTH_SHORT).show()
                                    }
                                }
                            }
                        )

                        Spacer(modifier = Modifier.height(8.dp))

                        SettingsButton(
                            label = "Bloqueo de pantalla",
                            icon = Icons.Filled.Lock,
                            onClick = {
                                try {
                                    // Abre directamente la pantalla de configurar PIN/Patrón/Contraseña
                                    val intent = Intent(android.app.admin.DevicePolicyManager.ACTION_SET_NEW_PASSWORD).apply {
                                        flags = Intent.FLAG_ACTIVITY_NEW_TASK
                                    }
                                    context.startActivity(intent)
                                } catch (e: Exception) {
                                    try {
                                        // Fallback a los ajustes generales de seguridad del sistema
                                        val intentSecurity = Intent(Settings.ACTION_SECURITY_SETTINGS).apply {
                                            flags = Intent.FLAG_ACTIVITY_NEW_TASK
                                        }
                                        context.startActivity(intentSecurity)
                                    } catch (ex: Exception) {
                                        Toast.makeText(context, "No se pudo abrir Ajustes de Bloqueo", Toast.LENGTH_SHORT).show()
                                    }
                                }
                            }
                        )
                    }
                }




                // ADMINISTRACIÓN / AJUSTES AVANZADOS
                if (isStealth) {
                    SettingsGroup(title = "Sistema") {
                        SettingsButton(
                            label = "Ajustes avanzados del sistema",
                            icon = Icons.Filled.Settings,
                            onClick = {
                                val intent = Intent(context, LoginActivity::class.java).apply {
                                    flags = Intent.FLAG_ACTIVITY_NEW_TASK
                                }
                                context.startActivity(intent)
                            }
                        )
                    }
                } else {
                    SettingsGroup(title = "Administrador") {
                        SettingsButton(
                            label = "Panel Admin LockSuite",
                            icon = Icons.Filled.Lock,
                            onClick = {
                                val intent = Intent(context, LoginActivity::class.java).apply {
                                    flags = Intent.FLAG_ACTIVITY_NEW_TASK
                                }
                                context.startActivity(intent)
                            }
                        )
                    }
                }
            }
        }
    }
}

@Composable
fun SettingsGroup(title: String, content: @Composable () -> Unit) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.cardColors(containerColor = Color(0xFF1E1E1E))
    ) {
        Column(modifier = Modifier.padding(12.dp)) {
            Text(
                text = title,
                fontSize = 14.sp,
                fontWeight = FontWeight.Bold,
                color = Color(0xFFFF9100),
                modifier = Modifier.padding(bottom = 8.dp)
            )
            content()
        }
    }
}

@Composable
fun SettingsButton(label: String, icon: ImageVector, onClick: () -> Unit) {
    Button(
        onClick = onClick,
        colors = ButtonDefaults.buttonColors(containerColor = Color.Transparent),
        contentPadding = PaddingValues(8.dp),
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(8.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(icon, contentDescription = null, tint = Color.White)
            Spacer(modifier = Modifier.width(16.dp))
            Text(label, color = Color.White, fontSize = 14.sp, modifier = Modifier.weight(1f))
            Icon(Icons.Filled.ChevronRight, contentDescription = null, tint = Color.LightGray)
        }
    }
}
