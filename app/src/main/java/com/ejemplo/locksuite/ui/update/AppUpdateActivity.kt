package com.ejemplo.locksuite.ui.update

import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.os.Bundle
import android.provider.Settings
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.ejemplo.locksuite.mdm.AppController
import com.ejemplo.locksuite.mdm.AppInfoData
import com.ejemplo.locksuite.service.LockSuiteAccessibilityService
import com.ejemplo.locksuite.util.LocaleManager
import com.ejemplo.locksuite.util.UpdateFlowManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class AppUpdateActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        setContent {
            AppUpdateScreen(onClose = { finish() })
        }
    }
}

@OptEmptyList
@Composable
fun AppUpdateScreen(onClose: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    val bgDark = Color(0xFF0B192C)
    val cardBg = Color(0xFF1E293B)
    val accentAmber = Color(0xFFF59E0B)
    val textPrimary = Color.White
    val textSecondary = Color(0xFF94A3B8)

    var searchQuery by remember { mutableStateOf("") }
    var appsList by remember { mutableStateOf<List<AppItemWithVersion>>(emptyList()) }
    var isLoading by remember { mutableStateOf(true) }
    var isAccessibilityActive by remember { mutableStateOf(LockSuiteAccessibilityService.instance != null) }

    val lang = remember { LocaleManager.getLang() }

    // Cargar aplicaciones en segundo plano
    fun loadApps() {
        scope.launch {
            isLoading = true
            val apps = withContext(Dispatchers.IO) {
                try {
                    val userApps = AppController(context)
                        .getUserApps(loadIcon = true)
                        .filter { !it.isCritical && it.appType != "Sistema" }

                    val pm = context.packageManager
                    userApps.map { app ->
                        val ver = try {
                            val pInfo = pm.getPackageInfo(app.packageName, 0)
                            pInfo.versionName ?: ""
                        } catch (e: Exception) {
                            ""
                        }
                        AppItemWithVersion(app = app, versionName = ver)
                    }
                } catch (e: Exception) {
                    emptyList()
                }
            }
            appsList = apps
            isLoading = false
        }
    }

    LaunchedEffect(Unit) {
        loadApps()
        // Monitoreo de accesibilidad en vivo
        while (true) {
            isAccessibilityActive = LockSuiteAccessibilityService.instance != null
            delay(1200L)
        }
    }

    val filteredApps = remember(appsList, searchQuery) {
        val q = searchQuery.trim().lowercase()
        if (q.isEmpty()) appsList
        else appsList.filter {
            it.app.label.lowercase().contains(q) || it.app.packageName.lowercase().contains(q)
        }
    }

    Surface(
        modifier = Modifier.fillMaxSize(),
        color = bgDark
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .statusBarsPadding()
                .navigationBarsPadding()
                .padding(horizontal = 16.dp, vertical = 8.dp)
        ) {
            // ── Top Bar ──
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                IconButton(
                    onClick = onClose,
                    modifier = Modifier
                        .size(40.dp)
                        .background(Color.White.copy(alpha = 0.08f), CircleShape)
                ) {
                    Icon(
                        imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                        contentDescription = "Volver",
                        tint = textPrimary
                    )
                }

                Spacer(modifier = Modifier.width(12.dp))

                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = if (lang == "he") "עדכון אפליקציות" else if (lang == "en") "App Updates" else "Actualizar Aplicaciones",
                        color = textPrimary,
                        fontSize = 19.sp,
                        fontWeight = FontWeight.Bold
                    )
                    Text(
                        text = if (lang == "he") "Google Play עדכון אוטומטי ובטוח דרך"
                        else if (lang == "en") "Safe automated updates via Google Play"
                        else "Actualización segura vía Google Play",
                        color = textSecondary,
                        fontSize = 12.sp
                    )
                }

                IconButton(
                    onClick = { loadApps() },
                    modifier = Modifier
                        .size(40.dp)
                        .background(Color.White.copy(alpha = 0.08f), CircleShape)
                ) {
                    Icon(
                        imageVector = Icons.Default.Refresh,
                        contentDescription = "Recargar",
                        tint = textSecondary
                    )
                }
            }

            Spacer(modifier = Modifier.height(10.dp))

            // ── Banner de Advertencia si Accesibilidad está inactiva ──
            AnimatedVisibility(visible = !isAccessibilityActive) {
                Card(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(bottom = 12.dp)
                        .clickable {
                            try {
                                context.startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS).apply {
                                    flags = Intent.FLAG_ACTIVITY_NEW_TASK
                                })
                            } catch (e: Exception) {
                                Toast.makeText(context, "Abra Ajustes -> Accesibilidad", Toast.LENGTH_SHORT).show()
                            }
                        },
                    colors = CardDefaults.cardColors(containerColor = Color(0xFF78350F).copy(alpha = 0.8f)),
                    shape = RoundedCornerShape(12.dp)
                ) {
                    Row(
                        modifier = Modifier.padding(12.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(
                            imageVector = Icons.Default.Warning,
                            contentDescription = null,
                            tint = Color(0xFFFBBF24),
                            modifier = Modifier.size(24.dp)
                        )
                        Spacer(modifier = Modifier.width(10.dp))
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = if (lang == "he") "נדרש שירות נגישות פעיל"
                                else if (lang == "en") "Accessibility service required"
                                else "Accesibilidad desactivada",
                                color = Color.White,
                                fontWeight = FontWeight.Bold,
                                fontSize = 13.sp
                            )
                            Text(
                                text = if (lang == "he") "הקש כאן כדי להפעיל בהגדרות"
                                else if (lang == "en") "Tap to enable in Settings"
                                else "Toca aquí para activarla y habilitar la actualización",
                                color = Color(0xFFFDE68A),
                                fontSize = 11.sp
                            )
                        }
                    }
                }
            }

            // ── Buscador ──
            OutlinedTextField(
                value = searchQuery,
                onValueChange = { searchQuery = it },
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(bottom = 12.dp),
                placeholder = {
                    Text(
                        text = if (lang == "he") "חפש אפליקציה..." else if (lang == "en") "Search app..." else "Buscar aplicación...",
                        color = textSecondary,
                        fontSize = 14.sp
                    )
                },
                leadingIcon = {
                    Icon(
                        imageVector = Icons.Default.Search,
                        contentDescription = "Buscar",
                        tint = textSecondary
                    )
                },
                trailingIcon = {
                    if (searchQuery.isNotEmpty()) {
                        IconButton(onClick = { searchQuery = "" }) {
                            Icon(
                                imageVector = Icons.Default.Clear,
                                contentDescription = "Limpiar",
                                tint = textSecondary
                            )
                        }
                    }
                },
                singleLine = true,
                shape = RoundedCornerShape(14.dp),
                colors = OutlinedTextFieldDefaults.colors(
                    focusedBorderColor = accentAmber,
                    unfocusedBorderColor = Color.White.copy(alpha = 0.15f),
                    focusedContainerColor = cardBg.copy(alpha = 0.5f),
                    unfocusedContainerColor = cardBg.copy(alpha = 0.5f),
                    focusedTextColor = textPrimary,
                    unfocusedTextColor = textPrimary
                )
            )

            // ── Lista de Aplicaciones ──
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f),
                contentAlignment = Alignment.Center
            ) {
                if (isLoading) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        CircularProgressIndicator(color = accentAmber, modifier = Modifier.size(38.dp))
                        Spacer(modifier = Modifier.height(12.dp))
                        Text(
                            text = if (lang == "he") "טוען אפליקציות..." else if (lang == "en") "Loading apps..." else "Cargando aplicaciones...",
                            color = textSecondary,
                            fontSize = 13.sp
                        )
                    }
                } else if (filteredApps.isEmpty()) {
                    Text(
                        text = if (lang == "he") "לא נמצאו אפליקציות" else if (lang == "en") "No apps found" else "No se encontraron aplicaciones",
                        color = textSecondary,
                        fontSize = 14.sp,
                        textAlign = TextAlign.Center
                    )
                } else {
                    LazyColumn(
                        modifier = Modifier.fillMaxSize(),
                        verticalArrangement = Arrangement.spacedBy(10.dp),
                        contentPadding = PaddingValues(bottom = 16.dp)
                    ) {
                        items(filteredApps, key = { it.app.packageName }) { item ->
                            AppUpdateItemCard(
                                item = item,
                                isAccessibilityActive = isAccessibilityActive,
                                lang = lang,
                                onUpdateClick = {
                                    val err = UpdateFlowManager.start(
                                        context = context,
                                        packageName = item.app.packageName,
                                        source = UpdateFlowManager.SOURCE_LOCAL,
                                        cancelable = true
                                    )
                                    if (err != null) {
                                        Toast.makeText(context, err, Toast.LENGTH_LONG).show()
                                    }
                                }
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun AppUpdateItemCard(
    item: AppItemWithVersion,
    isAccessibilityActive: Boolean,
    lang: String,
    onUpdateClick: () -> Unit
) {
    val cardBg = Color(0xFF1E293B)
    val accentAmber = Color(0xFFF59E0B)

    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(14.dp),
        colors = CardDefaults.cardColors(containerColor = cardBg)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            // Icono de la App
            if (item.app.icon != null) {
                Image(
                    bitmap = item.app.icon.asImageBitmap(),
                    contentDescription = item.app.label,
                    modifier = Modifier
                        .size(46.dp)
                        .clip(RoundedCornerShape(10.dp))
                )
            } else {
                Box(
                    modifier = Modifier
                        .size(46.dp)
                        .background(Color.White.copy(alpha = 0.1f), RoundedCornerShape(10.dp)),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = item.app.label.take(1).uppercase(),
                        color = Color.White,
                        fontWeight = FontWeight.Bold,
                        fontSize = 18.sp
                    )
                }
            }

            Spacer(modifier = Modifier.width(12.dp))

            // Información de la App
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = item.app.label,
                    color = Color.White,
                    fontWeight = FontWeight.Bold,
                    fontSize = 15.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                Spacer(modifier = Modifier.height(2.dp))
                Text(
                    text = item.app.packageName,
                    color = Color(0xFF94A3B8),
                    fontSize = 11.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
                if (item.versionName.isNotEmpty()) {
                    Text(
                        text = "v${item.versionName}",
                        color = Color(0xFF64748B),
                        fontSize = 10.sp
                    )
                }
            }

            Spacer(modifier = Modifier.width(8.dp))

            // Botón Actualizar
            Button(
                onClick = onUpdateClick,
                enabled = isAccessibilityActive,
                colors = ButtonDefaults.buttonColors(
                    containerColor = accentAmber,
                    contentColor = Color(0xFF0F172A),
                    disabledContainerColor = Color.White.copy(alpha = 0.08f),
                    disabledContentColor = Color.White.copy(alpha = 0.3f)
                ),
                shape = RoundedCornerShape(10.dp),
                contentPadding = PaddingValues(horizontal = 14.dp, vertical = 8.dp)
            ) {
                Text(
                    text = if (lang == "he") "עדכן" else if (lang == "en") "Update" else "Actualizar",
                    fontWeight = FontWeight.Bold,
                    fontSize = 13.sp
                )
            }
        }
    }
}

data class AppItemWithVersion(
    val app: AppInfoData,
    val versionName: String
)

@Target(AnnotationTarget.FUNCTION)
@Retention(AnnotationRetention.SOURCE)
annotation class OptEmptyList
