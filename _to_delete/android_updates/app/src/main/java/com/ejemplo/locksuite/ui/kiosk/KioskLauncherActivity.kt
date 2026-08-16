package com.ejemplo.locksuite.ui.kiosk

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.drawable.Drawable
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.OnBackPressedCallback
import androidx.activity.compose.setContent
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material3.*
import androidx.compose.runtime.*
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
import com.ejemplo.locksuite.mdm.PolicyManager
import com.ejemplo.locksuite.ui.auth.LoginActivity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

data class KioskAppEntry(
    val packageName: String,
    val label: String,
    val icon: Drawable
)

/**
 * Reemplaza al "Inicio" del dispositivo (ver PolicyManager.registerKioskLauncher).
 * Esta es la pantalla de uso diario del celular kosher: una grilla con
 * únicamente las apps que el administrador autorizó. Todo lo demás queda
 * fuera de alcance porque Lock Task Mode impide salir a cualquier paquete
 * que no esté en la lista — no porque algo esté escondido.
 */
class KioskLauncherActivity : ComponentActivity() {

    private lateinit var policyManager: PolicyManager

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        policyManager = PolicyManager(this)

        // Esta pantalla es la raíz del dispositivo: no hay "atrás" al que volver.
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() { /* no-op intencional */ }
        })

        setContent {
            KioskLauncherScreen(
                onOpenAdmin = { startActivity(Intent(this, LoginActivity::class.java)) }
            )
        }
    }

    override fun onResume() {
        super.onResume()
        // Solo se pinea si ya hay una lista configurada — si todavía no se
        // configuró nada, dejamos al administrador moverse libre para hacerlo.
        if (policyManager.isKioskModeEnabled()) {
            try {
                startLockTask()
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }
    }
}

@Composable
fun KioskLauncherScreen(onOpenAdmin: () -> Unit) {
    val context = LocalContext.current
    val policyManager = remember { PolicyManager(context) }
    var apps by remember { mutableStateOf<List<KioskAppEntry>>(emptyList()) }
    var isLoading by remember { mutableStateOf(true) }

    LaunchedEffect(Unit) {
        apps = withContext(Dispatchers.IO) {
            loadAllowedApps(context, policyManager.getAllowedPackages())
        }
        isLoading = false
    }

    val navyDark = Color(0xFF0B192C)
    val navyMedium = Color(0xFF1E3E62)
    val accentOrange = Color(0xFFF1C40F)

    Column(modifier = Modifier.fillMaxSize().background(navyDark)) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(20.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = "Modo Kosher Activo",
                color = accentOrange,
                fontSize = 18.sp,
                fontWeight = FontWeight.Bold
            )
            IconButton(onClick = onOpenAdmin) {
                Icon(Icons.Default.Lock, contentDescription = "Panel de administración", tint = Color.LightGray)
            }
        }

        when {
            isLoading -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                CircularProgressIndicator(color = accentOrange)
            }
            apps.isEmpty() -> Box(Modifier.fillMaxSize().padding(32.dp), contentAlignment = Alignment.Center) {
                Text(
                    "Todavía no hay apps configuradas.\nUsá el candado para entrar al panel de administración.",
                    color = Color.LightGray,
                    fontSize = 14.sp,
                    textAlign = TextAlign.Center
                )
            }
            else -> LazyVerticalGrid(
                columns = GridCells.Adaptive(minSize = 88.dp),
                contentPadding = PaddingValues(16.dp),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp),
                modifier = Modifier.fillMaxSize()
            ) {
                items(apps, key = { it.packageName }) { app ->
                    KioskAppIcon(app = app, backgroundColor = navyMedium)
                }
            }
        }
    }
}

@Composable
fun KioskAppIcon(app: KioskAppEntry, backgroundColor: Color) {
    val context = LocalContext.current
    val bitmap = remember(app.packageName) { app.icon.toBitmap() }

    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = Modifier.clickable {
            val launchIntent = context.packageManager.getLaunchIntentForPackage(app.packageName)
            try {
                if (launchIntent != null) {
                    context.startActivity(launchIntent)
                } else {
                    Toast.makeText(context, "No se pudo abrir ${app.label}", Toast.LENGTH_SHORT).show()
                }
            } catch (e: ActivityNotFoundException) {
                Toast.makeText(context, "No se pudo abrir ${app.label}", Toast.LENGTH_SHORT).show()
            }
        }
    ) {
        Image(
            bitmap = bitmap.asImageBitmap(),
            contentDescription = app.label,
            modifier = Modifier
                .size(64.dp)
                .clip(RoundedCornerShape(16.dp))
                .background(backgroundColor)
                .padding(8.dp)
        )
        Spacer(modifier = Modifier.height(4.dp))
        Text(
            text = app.label,
            color = Color.White,
            fontSize = 11.sp,
            textAlign = TextAlign.Center,
            maxLines = 2
        )
    }
}

private fun loadAllowedApps(context: Context, allowedPackages: Set<String>): List<KioskAppEntry> {
    val pm = context.packageManager
    return allowedPackages.mapNotNull { pkg ->
        try {
            val appInfo = pm.getApplicationInfo(pkg, 0)
            KioskAppEntry(
                packageName = pkg,
                label = pm.getApplicationLabel(appInfo).toString(),
                icon = pm.getApplicationIcon(appInfo)
            )
        } catch (e: PackageManager.NameNotFoundException) {
            null // La app permitida ya no está instalada — se ignora, no crashea.
        }
    }.sortedBy { it.label }
}
