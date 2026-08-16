> **ARCHIVADO (16/8/2026) — ver `LOCKSUITE_CONTEXTO_PARA_IA.md`.** Este documento quedó consolidado ahí; se conserva acá como referencia histórica.

# Cambios de Claude en `DashboardActivity.kt` — Reaplicación de la UI de DNS

**Para:** Antigravity — **Fecha:** 2026-08-09 19:13 UTC — **Autor:** Claude (sesión Cowork)

**Objetivo:** que puedas verificar que la reaplicación de la UI de DNS **no pisó** tu trabajo del panel de perfiles (exportar/importar) en este mismo archivo.

## Contexto

La UI de la sección DNS (lista persistente de reglas, alta manual de dominios y selector forzar/normal) se había **perdido** de `DashboardActivity.kt`: el archivo había vuelto a la versión original de esa pestaña (muy probablemente un `git checkout`/reset que descartó cambios sin commitear). El backend (motor de reglas, VPN, prioridad forzar/normal) sí estaba. Claude reaplicó **solo la parte de UI** que faltaba.

## Qué tocó Claude (exactamente)

Reemplazo quirúrgico de **2 funciones** + **1 composable nuevo** + **1 import**. NO se reescribió el archivo entero: se ubicaron los límites exactos de cada función por conteo de llaves y se reemplazaron solo esas.

| Elemento | Acción |
|---|---|
| `import androidx.compose.foundation.lazy.item` | **Agregado** (antes solo estaba `.items`) |
| `DnsActivityTabContent(...)` | **Reescrita**: ahora es un único `LazyColumn` con 2 secciones — "Reglas DNS personalizadas" (persistente) + "Actividad reciente" (ventana temporal) |
| `DnsActivityRow(...)` | **Reescrita**: maneja los 4 valores de `RuleType`; el toque rápido ahora crea reglas `FORCE_*` |
| `DnsCustomRuleRow(...)` | **Nueva**: fila de cada regla guardada, con botón "Forzar" (para reglas normales) y "Quitar" |

## Qué NO tocó Claude (tu trabajo, intacto)

Tu feature de exportar/importar perfiles en `PresetsTabContent` quedó **100% intacta**. Verificado que siguen presentes:

- `exportLauncher` / `rememberLauncherForActivityResult` con `CreateDocument`
- Botón "Exportar a Archivo" y "Exportar" por preset
- `importLauncher` con guardado (`saveLocalPreset`) del perfil importado
- `canonicalizeJson(...)` en `PolicyManager.kt` (ese archivo no lo toqué en esta pasada)

## Verificación automática (post-cambio)

- Llaves balanceadas: **683 `{` / 683 `}`** — OK
- Paréntesis balanceados — OK
- Marcadores de tu feature presentes (`exportLauncher`, "Exportar a Archivo") — OK
- `DnsActivityTabContent`, `DnsActivityRow`, `DnsCustomRuleRow`: 1 c/u — OK
- **Importante:** este código todavía NO pasó por el compilador. El primer `assembleRelease` de v0.6.0 es la primera verificación real de sintaxis.

## Otros archivos que Claude cambió antes en esta sesión (no en esta pasada)

Para que tengas el panorama completo, por si tocaste alguno:

- `admin-backend/functions/index.js` — `UPDATE_APP` ya no exige PIN.
- `dns/DomainRuleTrie.kt` — `RuleType` de 2 a 4 valores.
- `dns/DomainRuleManager.kt` — 4 tipos de regla + arranca la VPN al fijar una regla.
- `receiver/BootReceiver.kt` — la VPN se mantiene viva si hay reglas DNS.
- `mdm/PolicyManager.kt` — 2 chequeos para no apagar la VPN con reglas DNS activas. (Convive con tu `canonicalizeJson`, en otra zona del archivo.)
- `service/KosherVpnService.kt` — lógica de prioridad forzar/normal.

---

## Diff completo de `DashboardActivity.kt` (git diff --ignore-cr-at-eol)

```diff
diff --git a/app/src/main/java/com/ejemplo/locksuite/ui/dashboard/DashboardActivity.kt b/app/src/main/java/com/ejemplo/locksuite/ui/dashboard/DashboardActivity.kt
index dbf50d7..d54d14f 100644
--- a/app/src/main/java/com/ejemplo/locksuite/ui/dashboard/DashboardActivity.kt
+++ b/app/src/main/java/com/ejemplo/locksuite/ui/dashboard/DashboardActivity.kt
@@ -23,6 +23,7 @@ import androidx.compose.foundation.horizontalScroll
 import androidx.compose.foundation.rememberScrollState
 import androidx.compose.foundation.layout.*
 import androidx.compose.foundation.lazy.LazyColumn
+import androidx.compose.foundation.lazy.item
 import androidx.compose.foundation.lazy.items
 import androidx.compose.foundation.shape.RoundedCornerShape
 import androidx.compose.material.icons.Icons
@@ -1419,6 +1420,29 @@ fun PresetsTabContent(context: Context) {
     var refreshKey by remember { mutableIntStateOf(0) }
     val presetsMap = remember(refreshKey) { policyManager.getLocalPresets() }
 
+    var pendingExportJson by remember { mutableStateOf<String?>(null) }
+    var pendingExportName by remember { mutableStateOf("") }
+
+    val exportLauncher = rememberLauncherForActivityResult(
+        contract = ActivityResultContracts.CreateDocument("application/octet-stream")
+    ) { uri: Uri? ->
+        uri?.let {
+            try {
+                val jsonToWrite = pendingExportJson
+                if (!jsonToWrite.isNullOrEmpty()) {
+                    context.contentResolver.openOutputStream(it)?.use { outputStream ->
+                        outputStream.bufferedWriter().use { writer ->
+                            writer.write(jsonToWrite)
+                        }
+                    }
+                    Toast.makeText(context, "✅ Perfil '$pendingExportName' exportado con éxito.", Toast.LENGTH_LONG).show()
+                }
+            } catch (e: Exception) {
+                Toast.makeText(context, "❌ Error al exportar perfil: ${e.message}", Toast.LENGTH_LONG).show()
+            }
+        }
+    }
+
     val importLauncher = rememberLauncherForActivityResult(
         contract = ActivityResultContracts.GetContent()
     ) { uri: Uri? ->
@@ -1429,7 +1453,14 @@ fun PresetsTabContent(context: Context) {
                 if (jsonString.isNotEmpty()) {
                     val success = policyManager.importPolicyPresetJson(jsonString)
                     if (success) {
-                        Toast.makeText(context, "✅ Backup (.locksuite) importado y aplicado con éxito.", Toast.LENGTH_LONG).show()
+                        val importedName = try {
+                            val obj = org.json.JSONObject(jsonString)
+                            obj.optString("presetName", "Perfil Importado")
+                        } catch (e: Exception) {
+                            "Perfil Importado"
+                        }
+                        policyManager.saveLocalPreset(importedName, jsonString)
+                        Toast.makeText(context, "✅ Backup (.locksuite) importado, guardado y aplicado con éxito.", Toast.LENGTH_LONG).show()
                         refreshKey++
                     } else {
                         Toast.makeText(context, "❌ Error al aplicar el backup.", Toast.LENGTH_LONG).show()
@@ -1458,28 +1489,27 @@ fun PresetsTabContent(context: Context) {
                 Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                     Text("Guardar Configuración Actual (Preset)", color = Color(0xFFF1C40F), fontWeight = FontWeight.Bold, fontSize = 16.sp)
                     Text(
-                        "Guarda todas las políticas y restricciones activas con un nombre personalizado para volverlas a aplicar rápidamente.",
+                        "Guarda todas las políticas y restricciones activas con un nombre personalizado.",
                         color = Color.White.copy(alpha = 0.7f),
                         fontSize = 12.sp
                     )
+                    OutlinedTextField(
+                        value = presetNameInput,
+                        onValueChange = { presetNameInput = it },
+                        placeholder = { Text("Ej: Bloqueo A Fuerte", color = Color.Gray) },
+                        singleLine = true,
+                        colors = OutlinedTextFieldDefaults.colors(
+                            focusedBorderColor = Color(0xFFF1C40F),
+                            unfocusedBorderColor = Color.White.copy(alpha = 0.5f),
+                            focusedTextColor = Color.White,
+                            unfocusedTextColor = Color.White
+                        ),
+                        modifier = Modifier.fillMaxWidth()
+                    )
                     Row(
                         modifier = Modifier.fillMaxWidth(),
-                        verticalAlignment = Alignment.CenterVertically,
                         horizontalArrangement = Arrangement.spacedBy(8.dp)
                     ) {
-                        OutlinedTextField(
-                            value = presetNameInput,
-                            onValueChange = { presetNameInput = it },
-                            placeholder = { Text("Ej: Bloqueo A Fuerte", color = Color.Gray) },
-                            singleLine = true,
-                            colors = OutlinedTextFieldDefaults.colors(
-                                focusedBorderColor = Color(0xFFF1C40F),
-                                unfocusedBorderColor = Color.White.copy(alpha = 0.5f),
-                                focusedTextColor = Color.White,
-                                unfocusedTextColor = Color.White
-                            ),
-                            modifier = Modifier.weight(1f)
-                        )
                         Button(
                             onClick = {
                                 if (presetNameInput.isBlank()) {
@@ -1492,9 +1522,23 @@ fun PresetsTabContent(context: Context) {
                                 refreshKey++
                                 Toast.makeText(context, "Perfil guardado con éxito.", Toast.LENGTH_SHORT).show()
                             },
-                            colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFF1C40F), contentColor = Color(0xFF0B192C))
+                            colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFF1C40F), contentColor = Color(0xFF0B192C)),
+                            modifier = Modifier.weight(1f)
                         ) {
-                            Text("Guardar", fontWeight = FontWeight.Bold)
+                            Text("Guardar Local", fontWeight = FontWeight.Bold)
+                        }
+                        Button(
+                            onClick = {
+                                val name = if (presetNameInput.isNotBlank()) presetNameInput.trim() else "Perfil_LockSuite"
+                                val jsonStr = policyManager.exportPolicyPresetJson(name)
+                                pendingExportJson = jsonStr
+                                pendingExportName = name
+                                exportLauncher.launch("${name.replace("\\s+".toRegex(), "_")}.locksuite")
+                            },
+                            colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF2980B9), contentColor = Color.White),
+                            modifier = Modifier.weight(1f)
+                        ) {
+                            Text("Exportar a Archivo", fontWeight = FontWeight.Bold)
                         }
                     }
                 }
@@ -1555,7 +1599,7 @@ fun PresetsTabContent(context: Context) {
                         Column(modifier = Modifier.weight(1f)) {
                             Text(name, color = Color.White, fontWeight = FontWeight.Bold, fontSize = 14.sp)
                         }
-                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
+                        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                             Button(
                                 onClick = {
                                     try {
@@ -1563,18 +1607,27 @@ fun PresetsTabContent(context: Context) {
                                         if (success) {
                                             Toast.makeText(context, "Perfil '$name' aplicado con éxito.", Toast.LENGTH_SHORT).show()
                                         } else {
-                                            // Antes, si success era false (p.ej. JSON guardado corrupto)
-                                            // no se mostraba ningún mensaje: el admin tocaba "Aplicar"
-                                            // y no pasaba nada, sin ninguna pista de por qué.
                                             Toast.makeText(context, "❌ Error al aplicar el perfil '$name'.", Toast.LENGTH_LONG).show()
                                         }
                                     } catch (e: SecurityException) {
                                         Toast.makeText(context, "🚨 ALERTA: ${e.message}", Toast.LENGTH_LONG).show()
                                     }
                                 },
-                                colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF27AE60))
+                                colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF27AE60)),
+                                contentPadding = PaddingValues(horizontal = 8.dp, vertical = 4.dp)
+                            ) {
+                                Text("Aplicar", fontSize = 10.sp)
+                            }
+                            Button(
+                                onClick = {
+                                    pendingExportJson = jsonStr
+                                    pendingExportName = name
+                                    exportLauncher.launch("${name.replace("\\s+".toRegex(), "_")}.locksuite")
+                                },
+                                colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF2980B9)),
+                                contentPadding = PaddingValues(horizontal = 8.dp, vertical = 4.dp)
                             ) {
-                                Text("Aplicar", fontSize = 11.sp)
+                                Text("Exportar", fontSize = 10.sp)
                             }
                             Button(
                                 onClick = {
@@ -1582,9 +1635,10 @@ fun PresetsTabContent(context: Context) {
                                     refreshKey++
                                     Toast.makeText(context, "Perfil eliminado.", Toast.LENGTH_SHORT).show()
                                 },
-                                colors = ButtonDefaults.buttonColors(containerColor = Color.Red)
+                                colors = ButtonDefaults.buttonColors(containerColor = Color.Red),
+                                contentPadding = PaddingValues(horizontal = 8.dp, vertical = 4.dp)
                             ) {
-                                Text("Eliminar", fontSize = 11.sp)
+                                Text("Eliminar", fontSize = 10.sp)
                             }
                         }
                     }
@@ -2471,8 +2525,8 @@ fun DnsActivityTabContent(context: Context) {
     var windowMinutes by remember { mutableIntStateOf(5) }
     var refreshKey by remember { mutableIntStateOf(0) }
     var searchQuery by remember { mutableStateOf("") }
+    var newDomainText by remember { mutableStateOf("") }
 
-    // Auto-refresh cada 2 segundos mientras la pantalla está visible
     LaunchedEffect(windowMinutes) {
         while (true) {
             kotlinx.coroutines.delay(2000)
@@ -2480,11 +2534,18 @@ fun DnsActivityTabContent(context: Context) {
         }
     }
 
-    // También refrescar cuando llega un nuevo evento DNS
     LaunchedEffect(Unit) {
         buffer.events.collect { refreshKey++ }
     }
 
+    // Reglas guardadas (persistentes): NO dependen de la ventana de 1h. Aca
+    // aparecen TODAS aunque el dominio no se haya vuelto a consultar, y se
+    // pueden quitar o forzar en cualquier momento. Arregla "no aparecen los
+    // bloqueados / no los puedo desbloquear despues de una hora".
+    val customRules = remember(refreshKey) {
+        ruleManager.getAllRules().entries.sortedBy { it.key }
+    }
+
     val activity = remember(refreshKey, windowMinutes, searchQuery) {
         val snapshot = buffer.snapshot(windowMinutes * 60_000L)
         val filtered = if (searchQuery.isBlank()) snapshot
@@ -2505,85 +2566,238 @@ fun DnsActivityTabContent(context: Context) {
             .sortedByDescending { it.lastSeenMillis }
     }
 
-    Column(modifier = Modifier.fillMaxSize()) {
-        // Selector de ventana temporal
-        Row(
-            modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
-            horizontalArrangement = Arrangement.spacedBy(8.dp)
-        ) {
-            listOf(1, 5, 15, 30, 60).forEach { minutes ->
-                FilterChip(
-                    selected = minutes == windowMinutes,
-                    onClick = { windowMinutes = minutes; refreshKey++ },
-                    label = { Text(if (minutes == 60) "1h" else "${minutes}m") },
-                    colors = FilterChipDefaults.filterChipColors(
-                        selectedContainerColor = Color(0xFFFF6D00),
-                        selectedLabelColor = Color.White
+    LazyColumn(modifier = Modifier.fillMaxSize()) {
+        item {
+            Column(modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) {
+                Text(
+                    "Reglas DNS personalizadas",
+                    color = Color(0xFFFF6D00),
+                    fontWeight = FontWeight.Bold,
+                    fontSize = 16.sp
+                )
+                Spacer(modifier = Modifier.height(4.dp))
+                Text(
+                    "Forzar bloqueo/permiso le gana a cualquier otra configuracion " +
+                    "(WebView, AdBlocker, etc.). Bloquear/Permitir normal solo aplica " +
+                    "si ninguna otra politica ya decide sobre ese dominio.",
+                    color = Color.White.copy(alpha = 0.6f),
+                    fontSize = 11.sp
+                )
+                Spacer(modifier = Modifier.height(8.dp))
+                OutlinedTextField(
+                    value = newDomainText,
+                    onValueChange = { newDomainText = it },
+                    modifier = Modifier.fillMaxWidth(),
+                    placeholder = { Text("Ej: ejemplo.com") },
+                    singleLine = true,
+                    colors = OutlinedTextFieldDefaults.colors(
+                        focusedBorderColor = Color(0xFFFF6D00),
+                        cursorColor = Color(0xFFFF6D00)
                     )
                 )
+                Spacer(modifier = Modifier.height(6.dp))
+                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
+                    Button(
+                        onClick = {
+                            val d = newDomainText.trim()
+                            if (d.isNotEmpty()) {
+                                ruleManager.setRule(d, RuleType.BLOCK)
+                                newDomainText = ""
+                                refreshKey++
+                            }
+                        },
+                        colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFB71C1C)),
+                        contentPadding = PaddingValues(horizontal = 6.dp, vertical = 4.dp),
+                        modifier = Modifier.weight(1f)
+                    ) { Text("Bloquear", fontSize = 10.sp) }
+                    Button(
+                        onClick = {
+                            val d = newDomainText.trim()
+                            if (d.isNotEmpty()) {
+                                ruleManager.setRule(d, RuleType.FORCE_BLOCK)
+                                newDomainText = ""
+                                refreshKey++
+                            }
+                        },
+                        colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFFF1744)),
+                        contentPadding = PaddingValues(horizontal = 6.dp, vertical = 4.dp),
+                        modifier = Modifier.weight(1f)
+                    ) { Text("Forzar bloq.", fontSize = 10.sp) }
+                    Button(
+                        onClick = {
+                            val d = newDomainText.trim()
+                            if (d.isNotEmpty()) {
+                                ruleManager.setRule(d, RuleType.ALLOW)
+                                newDomainText = ""
+                                refreshKey++
+                            }
+                        },
+                        colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF2E7D32)),
+                        contentPadding = PaddingValues(horizontal = 6.dp, vertical = 4.dp),
+                        modifier = Modifier.weight(1f)
+                    ) { Text("Permitir", fontSize = 10.sp) }
+                    Button(
+                        onClick = {
+                            val d = newDomainText.trim()
+                            if (d.isNotEmpty()) {
+                                ruleManager.setRule(d, RuleType.FORCE_ALLOW)
+                                newDomainText = ""
+                                refreshKey++
+                            }
+                        },
+                        colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF00E676), contentColor = Color.Black),
+                        contentPadding = PaddingValues(horizontal = 6.dp, vertical = 4.dp),
+                        modifier = Modifier.weight(1f)
+                    ) { Text("Forzar perm.", fontSize = 10.sp) }
+                }
+                Spacer(modifier = Modifier.height(8.dp))
+                Text(
+                    if (customRules.isEmpty()) "No hay reglas guardadas todavia."
+                    else "${customRules.size} regla(s) guardada(s):",
+                    color = Color.Gray,
+                    fontSize = 12.sp
+                )
             }
         }
 
-        // Barra de búsqueda
-        OutlinedTextField(
-            value = searchQuery,
-            onValueChange = { searchQuery = it },
-            modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp),
-            placeholder = { Text("Buscar dominio...") },
-            leadingIcon = { Icon(Icons.Default.Search, "Buscar") },
-            singleLine = true,
-            colors = OutlinedTextFieldDefaults.colors(
-                focusedBorderColor = Color(0xFFFF6D00),
-                cursorColor = Color(0xFFFF6D00)
+        items(customRules, key = { "rule_" + it.key }) { entry ->
+            DnsCustomRuleRow(
+                domain = entry.key,
+                rule = entry.value,
+                onForce = {
+                    val forced = if (entry.value == RuleType.BLOCK) RuleType.FORCE_BLOCK else RuleType.FORCE_ALLOW
+                    ruleManager.setRule(entry.key, forced)
+                    refreshKey++
+                },
+                onRemove = {
+                    ruleManager.clearRule(entry.key)
+                    refreshKey++
+                }
             )
-        )
-
-        Spacer(modifier = Modifier.height(8.dp))
-
-        // Contador
-        Text(
-            "${activity.size} dominios en los últimos ${if (windowMinutes == 60) "60 min" else "$windowMinutes min"}",
-            modifier = Modifier.padding(horizontal = 16.dp),
-            style = MaterialTheme.typography.bodySmall,
-            color = Color.Gray
-        )
+            HorizontalDivider(color = Color.White.copy(alpha = 0.1f), thickness = 0.5.dp)
+        }
 
-        Spacer(modifier = Modifier.height(4.dp))
+        item {
+            Spacer(modifier = Modifier.height(12.dp))
+            Text(
+                "Actividad reciente",
+                color = Color(0xFFFF6D00),
+                fontWeight = FontWeight.Bold,
+                fontSize = 16.sp,
+                modifier = Modifier.padding(horizontal = 16.dp)
+            )
+            Row(
+                modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
+                horizontalArrangement = Arrangement.spacedBy(8.dp)
+            ) {
+                listOf(1, 5, 15, 30, 60).forEach { minutes ->
+                    FilterChip(
+                        selected = minutes == windowMinutes,
+                        onClick = { windowMinutes = minutes; refreshKey++ },
+                        label = { Text(if (minutes == 60) "1h" else "${minutes}m") },
+                        colors = FilterChipDefaults.filterChipColors(
+                            selectedContainerColor = Color(0xFFFF6D00),
+                            selectedLabelColor = Color.White
+                        )
+                    )
+                }
+            }
+            OutlinedTextField(
+                value = searchQuery,
+                onValueChange = { searchQuery = it },
+                modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp),
+                placeholder = { Text("Buscar dominio...") },
+                leadingIcon = { Icon(Icons.Default.Search, "Buscar") },
+                singleLine = true,
+                colors = OutlinedTextFieldDefaults.colors(
+                    focusedBorderColor = Color(0xFFFF6D00),
+                    cursorColor = Color(0xFFFF6D00)
+                )
+            )
+            Spacer(modifier = Modifier.height(8.dp))
+            Text(
+                "${activity.size} dominios en los ultimos ${if (windowMinutes == 60) "60 min" else "$windowMinutes min"}",
+                modifier = Modifier.padding(horizontal = 16.dp),
+                style = MaterialTheme.typography.bodySmall,
+                color = Color.Gray
+            )
+            Spacer(modifier = Modifier.height(4.dp))
+        }
 
-        // Lista de dominios
         if (activity.isEmpty()) {
-            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
-                Text(
-                    "Sin actividad DNS en esta ventana de tiempo.\n" +
-                    "Abrí una app y volvé acá para ver sus dominios.",
-                    textAlign = TextAlign.Center,
-                    color = Color.Gray
-                )
-            }
-        } else {
-            LazyColumn {
-                items(activity, key = { it.domain }) { summary ->
-                    DnsActivityRow(
-                        summary = summary,
-                        onBlock = {
-                            ruleManager.setRule(summary.domain, RuleType.BLOCK)
-                            refreshKey++
-                        },
-                        onAllow = {
-                            ruleManager.setRule(summary.domain, RuleType.ALLOW)
-                            refreshKey++
-                        },
-                        onClearRule = {
-                            ruleManager.clearRule(summary.domain)
-                            refreshKey++
-                        }
-                    )
-                    HorizontalDivider(
-                        color = Color.White.copy(alpha = 0.1f),
-                        thickness = 0.5.dp
+            item {
+                Box(Modifier.fillMaxWidth().padding(32.dp), contentAlignment = Alignment.Center) {
+                    Text(
+                        "Sin actividad DNS en esta ventana de tiempo.\n" +
+                        "Abri una app y volve aca para ver sus dominios.",
+                        textAlign = TextAlign.Center,
+                        color = Color.Gray
                     )
                 }
             }
+        } else {
+            items(activity, key = { "act_" + it.domain }) { summary ->
+                DnsActivityRow(
+                    summary = summary,
+                    onBlock = {
+                        ruleManager.setRule(summary.domain, RuleType.FORCE_BLOCK)
+                        refreshKey++
+                    },
+                    onAllow = {
+                        ruleManager.setRule(summary.domain, RuleType.FORCE_ALLOW)
+                        refreshKey++
+                    },
+                    onClearRule = {
+                        ruleManager.clearRule(summary.domain)
+                        refreshKey++
+                    }
+                )
+                HorizontalDivider(
+                    color = Color.White.copy(alpha = 0.1f),
+                    thickness = 0.5.dp
+                )
+            }
+        }
+    }
+}
+
+@Composable
+private fun DnsCustomRuleRow(
+    domain: String,
+    rule: RuleType,
+    onForce: () -> Unit,
+    onRemove: () -> Unit
+) {
+    Row(
+        modifier = Modifier
+            .fillMaxWidth()
+            .padding(horizontal = 16.dp, vertical = 10.dp),
+        verticalAlignment = Alignment.CenterVertically
+    ) {
+        val (estado, color) = when (rule) {
+            RuleType.FORCE_BLOCK -> "🔒 Forzado: bloqueado" to Color(0xFFFF1744)
+            RuleType.BLOCK -> "🔒 Bloqueado (normal)" to Color(0xFFFF5252)
+            RuleType.FORCE_ALLOW -> "✅ Forzado: permitido" to Color(0xFF00E676)
+            RuleType.ALLOW -> "✅ Permitido (normal)" to Color(0xFF69F0AE)
+        }
+        Box(
+            modifier = Modifier
+                .size(8.dp)
+                .clip(RoundedCornerShape(4.dp))
+                .background(color)
+        )
+        Spacer(modifier = Modifier.width(12.dp))
+        Column(modifier = Modifier.weight(1f)) {
+            Text(domain, fontWeight = FontWeight.Medium, color = Color.White, fontSize = 13.sp, maxLines = 1)
+            Text(estado, color = color, style = MaterialTheme.typography.labelSmall)
+        }
+        if (rule == RuleType.BLOCK || rule == RuleType.ALLOW) {
+            TextButton(onClick = onForce) {
+                Text("Forzar", color = Color(0xFFFFD180), fontSize = 11.sp)
+            }
+        }
+        TextButton(onClick = onRemove) {
+            Text("Quitar", color = Color(0xFFFF5252), fontSize = 11.sp)
         }
     }
 }
@@ -2603,11 +2817,10 @@ private fun DnsActivityRow(
             .padding(horizontal = 16.dp, vertical = 10.dp),
         verticalAlignment = Alignment.CenterVertically
     ) {
-        // Indicador visual de estado
         val indicatorColor = when (summary.effectiveRule) {
-            RuleType.BLOCK -> Color(0xFFFF1744) // Rojo
-            RuleType.ALLOW -> Color(0xFF00E676) // Verde
-            else -> Color(0xFFFF6D00) // Naranja (sin regla)
+            RuleType.BLOCK, RuleType.FORCE_BLOCK -> Color(0xFFFF1744)
+            RuleType.ALLOW, RuleType.FORCE_ALLOW -> Color(0xFF00E676)
+            else -> Color(0xFFFF6D00)
         }
         Box(
             modifier = Modifier
@@ -2639,12 +2852,16 @@ private fun DnsActivityRow(
             )
 
             val (estado, color) = when {
+                summary.effectiveRule == RuleType.FORCE_BLOCK ->
+                    "🔒 Forzado: bloqueado" to Color(0xFFFF5252)
+                summary.effectiveRule == RuleType.FORCE_ALLOW ->
+                    "✅ Forzado: permitido" to Color(0xFF69F0AE)
                 summary.effectiveRule == RuleType.BLOCK && summary.explicitRule == null ->
                     "🔒 Bloqueado (heredado)" to Color(0xFFFF5252)
                 summary.effectiveRule == RuleType.BLOCK ->
-                    "🔒 Bloqueado" to Color(0xFFFF5252)
+                    "🔒 Bloqueado (normal)" to Color(0xFFFF5252)
                 summary.effectiveRule == RuleType.ALLOW ->
-                    "✅ Permitido" to Color(0xFF69F0AE)
+                    "✅ Permitido (normal)" to Color(0xFF69F0AE)
                 else ->
                     "" to Color.Transparent
             }
@@ -2653,24 +2870,15 @@ private fun DnsActivityRow(
             }
         }
 
-        // Botón de acción contextual
         when {
-            // Bloqueado por herencia → ofrecer excepción para este subdominio
-            summary.effectiveRule == RuleType.BLOCK && summary.explicitRule == null ->
+            (summary.effectiveRule == RuleType.BLOCK || summary.effectiveRule == RuleType.FORCE_BLOCK) && summary.explicitRule == null ->
                 TextButton(onClick = onAllow) {
                     Text("Permitir", color = Color(0xFF69F0AE), fontSize = 11.sp)
                 }
-            // Bloqueado explícitamente → quitar bloqueo
-            summary.explicitRule == RuleType.BLOCK ->
-                TextButton(onClick = onClearRule) {
-                    Text("Desbloquear", color = Color(0xFF69F0AE), fontSize = 11.sp)
-                }
-            // Permitido explícitamente → quitar excepción
-            summary.explicitRule == RuleType.ALLOW ->
+            summary.explicitRule != null ->
                 TextButton(onClick = onClearRule) {
                     Text("Quitar", color = Color(0xFFFF5252), fontSize = 11.sp)
                 }
-            // Sin regla → ofrecer bloquear
             else ->
                 TextButton(onClick = onBlock) {
                     Text("Bloquear", color = Color(0xFFFF5252), fontSize = 11.sp)
```

## Todos los archivos con cambios sin commitear (git diff --stat --ignore-cr-at-eol)

```
 admin-backend/functions/index.js                   |   7 +-
 admin-backend/public/app.js                        | 207 +++++++++-
 admin-backend/public/index.html                    |  25 +-
 .../com/ejemplo/locksuite/dns/DomainRuleManager.kt |  65 ++--
 .../com/ejemplo/locksuite/dns/DomainRuleTrie.kt    |  10 +-
 .../com/ejemplo/locksuite/mdm/PolicyManager.kt     |  71 +++-
 .../com/ejemplo/locksuite/receiver/BootReceiver.kt |  12 +-
 .../ejemplo/locksuite/service/KosherVpnService.kt  |  53 ++-
 .../locksuite/ui/dashboard/DashboardActivity.kt    | 432 +++++++++++++++------
 9 files changed, 696 insertions(+), 186 deletions(-)
```
