# Instrucciones para Antigravity — 16/8/2026 (tarde)

**Contexto:** continúa el commit `bbdada1`. El dueño reportó que "Actualizar app" se
traba en *"Buscando el botón Actualizar"* o en *"Esperando a Google Play"*, y pidió
además (a) que el flujo ande aunque el celular no esté en español, (b) que no
dependa solo de la accesibilidad, y (c) un bloqueo por MDM para que no se pueda
desactivar la Accesibilidad, con interruptor en el panel.

**Por qué te llega como documento y no como código aplicado:** la VM del puente al
dispositivo se cayó a mitad de sesión y no volvió, así que no pude editar los `.kt`
existentes. Sí pude **escribir archivos nuevos**, así que las dos piezas difíciles
ya están en el repo (sección 1) y lo que queda es cableado mecánico (sección 2).

Leer antes: `LOCKSUITE_CONTEXTO_PARA_IA.md` §B.9 y §B.11, y
`INSTRUCCIONES_ACTUALIZACION_Y_ACCESIBILIDAD_2026-08-16.md` (el razonamiento
completo; este documento es la versión ejecutable).

---

## 0. La causa raíz, en una frase

`handlePlayStoreAutoUpdate()` solo corre cuando llega un evento de accesibilidad de
`com.android.vending`, y **Play Store deja de emitir eventos apenas la ficha
termina de cargar**. Si en esa ráfaga inicial el árbol de nodos todavía no estaba
listo, o el botón todavía no estaba dibujado, no se vuelve a mirar nunca: la
pantalla negra queda congelada en la última etapa que alcanzó a escribir.

**Todo lo demás de este documento es mejora. Esto es el bug.** Si querés probar una
sola cosa primero, que sea el tick de la sección 2.2.

---

## 1. Archivos NUEVOS ya escritos en el repo (no rehacer, solo revisar)

| Archivo | Qué hace |
|---|---|
| `app/src/main/java/com/ejemplo/locksuite/util/PlayUpdateSessionWatcher.kt` | Progreso y finalización reales vía `PackageInstaller.SessionCallback`. Números, no texto: funciona en cualquier idioma. |
| `app/src/main/java/com/ejemplo/locksuite/util/PlayButtonFinder.kt` | Devuelve **candidatos ordenados** a botón (por ID de vista, por palabra completa en 10 idiomas, y por posición), más etiquetas de diagnóstico. |

Los dos son `object`, autocontenidos, sin dependencias del resto del proyecto.
Compilan solos. La cabecera de cada uno explica por qué está hecho así.

---

## 2. Ediciones a archivos existentes

### 2.1 `LockSuiteAccessibilityService.kt` — constantes

En el `companion object`, junto a las que agregué en `bbdada1`:

```kotlin
// Reemplazar estos dos valores:
private const val UPDATE_UP_TO_DATE_GRACE_MS = 3_000L   // era 8_000L
private const val UPDATE_STALL_MS = 75_000L             // era 120_000L

// Agregar:
/** Cada cuánto el flujo vuelve a mirar la pantalla, sin depender de eventos. */
private const val UPDATE_TICK_MS = 700L
/** Cuánto se espera tras apretar un candidato antes de probar el siguiente. */
private const val CANDIDATE_WAIT_MS = 2_500L
/** Cuántos candidatos distintos se prueban antes de rendirse. */
private const val MAX_CANDIDATES = 6
```

### 2.2 `LockSuiteAccessibilityService.kt` — el tick (LO IMPORTANTE)

Agregar como miembros de la clase:

```kotlin
private val updateTickRunnable = object : Runnable {
    override fun run() {
        val ctx = applicationContext
        val pkg = UpdateFlowManager.currentPackage(ctx)
        if (pkg.isNullOrBlank() || !UpdateFlowManager.isRunning(ctx)) {
            return   // sin re-encolar: el flujo terminó
        }
        try {
            scanAndAct(pkg)
        } catch (e: Exception) {
            Log.w(TAG, "tick de actualización: ${e.message}")
        }
        mainHandler.postDelayed(this, UPDATE_TICK_MS)
    }
}

/** La llama UpdateFlowManager.start(). */
fun startUpdateTicker() {
    mainHandler.removeCallbacks(updateTickRunnable)
    mainHandler.post(updateTickRunnable)
}

/** La llama UpdateFlowManager.finish(). Tiene que estar en TODOS los caminos de salida. */
fun stopUpdateTicker() {
    mainHandler.removeCallbacks(updateTickRunnable)
}
```

Agregar `mainHandler.removeCallbacks(updateTickRunnable)` en `onDestroy()`, junto a
los otros `removeCallbacks` que ya están ahí.

### 2.3 `LockSuiteAccessibilityService.kt` — reemplazar la lógica de escaneo

Borrar, del bloque que agregué en `bbdada1`:

- las listas `updateActionLabels`, `updateOpenLabels`, `updateDialogLabels`,
  `updateForbiddenFragments`, `updateProgressFragments`, `updateInstallingFragments`,
  `playStoreButtonIds` (las reemplaza `PlayButtonFinder`),
- la clase `StoreScan`, la función `scanPlayStoreTree()` y la función `isInMainRow()`,
- el cuerpo de `handlePlayStoreAutoUpdate()`.

Dejar como están: `findPlayStoreRoot()`, `ensureScreenOnForUpdate()`,
`releaseUpdateWakeLock()`, `invalidateFlagsCache()`, `scanRect`.

Reemplazar los campos de sesión y el handler por esto:

```kotlin
private var updateSessionPkg: String? = null
private var updateSessionStartTime = 0L
private var updateSessionTreeSeenAt = 0L
private var updateSessionLastClickAt = 0L
private var updateSessionCandidatesTried = 0
private val updateSessionTriedKeys = HashSet<String>(8)
private var lastStoreRelaunchAt = 0L

fun resetUpdateSession() {
    updateSessionPkg = null
    updateSessionStartTime = 0L
    updateSessionTreeSeenAt = 0L
    updateSessionLastClickAt = 0L
    updateSessionCandidatesTried = 0
    updateSessionTriedKeys.clear()
    lastStoreRelaunchAt = 0L
    releaseUpdateWakeLock()
}

/**
 * Firma estable de un nodo entre escaneos. Los AccessibilityNodeInfo se recrean
 * en cada escaneo, así que no sirve compararlos por identidad para saber si un
 * candidato ya se probó.
 */
private fun candidateKey(c: PlayButtonFinder.Candidate): String {
    val id = c.node.viewIdResourceName ?: ""
    val txt = c.node.text?.toString() ?: c.node.contentDescription?.toString() ?: ""
    return "$id|${c.top}|${c.left}|$txt"
}

/**
 * Un ciclo del flujo. Lo llama el tick cada UPDATE_TICK_MS y también cada evento
 * de accesibilidad de Play Store (el evento es un despertador extra, no la única
 * fuente — ese era el bug).
 */
private fun scanAndAct(updatingPkg: String) {
    val ctx = applicationContext
    val now = SystemClock.elapsedRealtime()

    if (updateSessionPkg != updatingPkg) {
        resetUpdateSession()
        updateSessionPkg = updatingPkg
        updateSessionStartTime = now
    }

    UpdateFlowManager.showOverlay(
        ctx, updatingPkg,
        UpdateFlowManager.currentStage(ctx),
        null,
        UpdateFlowManager.isCancelable(ctx)
    )

    // ── 1. ¿Ya se instaló? Señal concluyente, no depende de la pantalla ──
    if (UpdateFlowManager.targetAlreadyUpdated(ctx)) {
        UpdateFlowManager.setStage(ctx, UpdateFlowManager.STAGE_FINISHING)
        UpdateFlowManager.finish(ctx, UpdateFlowManager.RESULT_UPDATED, null)
        return
    }

    // ── 2. ¿Play Store ya está descargando? Entonces NO tocar nada ──
    val pct = PlayUpdateSessionWatcher.currentProgressFor(ctx, updatingPkg)
    if (pct >= 0 || PlayUpdateSessionWatcher.sawSession) {
        UpdateFlowManager.setStage(
            ctx,
            UpdateFlowManager.STAGE_DOWNLOADING,
            if (pct >= 0) "Descargando... $pct%" else null
        )
        return
    }

    // ── 3. Árbol de Play Store ──
    val root = findPlayStoreRoot()
    if (root == null) {
        UpdateFlowManager.setStage(ctx, UpdateFlowManager.STAGE_WAITING_STORE)
        // Si a los 6 s todavía no hay árbol, la ficha probablemente nunca abrió:
        // reintentar el intent (con el mismo freno de 1,5 s de siempre).
        if (now - updateSessionStartTime > 6_000L &&
            now - lastStoreRelaunchAt > STORE_RELAUNCH_MIN_MS
        ) {
            lastStoreRelaunchAt = now
            UpdateFlowManager.openStore(ctx, updatingPkg)
        }
        return
    }
    if (updateSessionTreeSeenAt == 0L) updateSessionTreeSeenAt = now

    val dm = resources.displayMetrics
    val scan = PlayButtonFinder.scan(root, dm.widthPixels, dm.heightPixels)
    UpdateFlowManager.reportDebugLabels(ctx, scan.debugLabels)

    val cooledDown = now - updateSessionLastClickAt > CANDIDATE_WAIT_MS

    // ── 4. Diálogo de confirmación, solo después de haber apretado algo ──
    //     (antes de eso, un texto suelto que coincida se llevaría todos los ciclos)
    if (updateSessionCandidatesTried > 0 && scan.dialogs.isNotEmpty() && cooledDown) {
        val d = scan.dialogs.first()
        UpdateFlowManager.setStage(ctx, UpdateFlowManager.STAGE_CONFIRMING)
        if (performClickOnNode(d.node)) {
            updateSessionLastClickAt = now
            Log.i(TAG, "Clic en diálogo: ${d.reason}")
        }
        return
    }

    // ── 5. Probar el próximo candidato a "Actualizar" ──
    //     Se aprieta UNO y se espera: si a CANDIDATE_WAIT_MS no apareció sesión de
    //     instalación ni cambió el versionCode, se prueba el siguiente. Esa
    //     verificación es lo que hace que funcione en un idioma no previsto.
    if (cooledDown && updateSessionCandidatesTried < MAX_CANDIDATES) {
        val next = scan.actions.firstOrNull { candidateKey(it) !in updateSessionTriedKeys }
        if (next != null) {
            updateSessionTriedKeys.add(candidateKey(next))
            updateSessionCandidatesTried++
            UpdateFlowManager.setStage(ctx, UpdateFlowManager.STAGE_LOOKING_BUTTON)
            if (performClickOnNode(next.node)) {
                updateSessionLastClickAt = now
                Log.i(TAG, "Candidato ${updateSessionCandidatesTried}/${MAX_CANDIDATES} apretado: ${next.reason}")
            }
            return
        }
    }

    // ── 6. Ya estaba al día ──
    //     Sin candidatos de actualización, con un "Abrir" a la vista, y sin que
    //     haya arrancado ninguna sesión.
    if (scan.actions.none { it.score >= 80 } && scan.opens.isNotEmpty() &&
        updateSessionTreeSeenAt > 0L && now - updateSessionTreeSeenAt > UPDATE_UP_TO_DATE_GRACE_MS
    ) {
        UpdateFlowManager.setStage(ctx, UpdateFlowManager.STAGE_FINISHING)
        UpdateFlowManager.finish(
            ctx,
            UpdateFlowManager.RESULT_UP_TO_DATE,
            "${UpdateFlowManager.appLabel(ctx, updatingPkg)} ya estaba actualizada."
        )
        return
    }

    // ── 7. Freno por estancamiento ──
    if (now - updateSessionStartTime > UPDATE_STALL_MS) {
        Log.w(TAG, "Actualización estancada para $updatingPkg. Etiquetas vistas: ${scan.debugLabels}")
        UpdateFlowManager.finish(
            ctx,
            UpdateFlowManager.RESULT_ERROR,
            "No se pudo actualizar ${UpdateFlowManager.appLabel(ctx, updatingPkg)}. Probá de nuevo más tarde."
        )
        return
    }

    UpdateFlowManager.setStage(ctx, UpdateFlowManager.STAGE_LOOKING_BUTTON)
}

private fun handlePlayStoreAutoUpdate(event: AccessibilityEvent, updatingPkg: String) {
    scanAndAct(updatingPkg)
}
```

Falta agregar el import: `import com.ejemplo.locksuite.util.PlayButtonFinder` y
`import com.ejemplo.locksuite.util.PlayUpdateSessionWatcher`.

### 2.4 `UpdateFlowManager.kt` — arranque

En `start()`:

**(a) Bloquear la desinstalación del paquete objetivo mientras dure el flujo.** Es
lo que hace que probar candidatos sea seguro: aunque se apretara "Desinstalar" en
un idioma desconocido, Android lo rechaza.

```kotlin
// justo después de calcular admin/dpm, antes de abrir Play Store
val wasUninstallBlocked = try { dpm.isUninstallBlocked(admin, packageName) } catch (e: Exception) { false }
prefs.edit().putBoolean("update_flow_prev_uninstall_blocked", wasUninstallBlocked).apply()
try { dpm.setUninstallBlocked(admin, packageName, true) } catch (e: Exception) { }
```

**(b) NO levantar `DISALLOW_INSTALL_UNKNOWN_SOURCES`.** Borrar esta línea:

```kotlin
dpm.clearUserRestriction(admin, UserManager.DISALLOW_INSTALL_UNKNOWN_SOURCES)   // ← BORRAR
```

Play Store no la necesita: esa restricción es para APKs de fuera de la tienda.
Levantarla abría una ventana de hasta 10 minutos para sideloadear. Dejar solo el
`clearUserRestriction` de `DISALLOW_INSTALL_APPS`.

**(c) Arrancar el observador de sesiones y el tick**, al final de `start()`, justo
antes del `return null`:

```kotlin
PlayUpdateSessionWatcher.start(
    ctx, packageName,
    onProgress = { pct -> setStage(ctx, STAGE_DOWNLOADING, "Descargando... $pct%") },
    onFinished = { ok ->
        // Solo ok=true es concluyente. Play Store descarta sesiones intermedias,
        // así que un false lo resuelve el tick comparando versionCode.
        if (ok) finish(ctx, RESULT_UPDATED, null)
    }
)
LockSuiteAccessibilityService.instance?.startUpdateTicker()
```

### 2.5 `UpdateFlowManager.kt` — cierre

En `finish()`, dentro del bloque que ya limpia todo (**antes** del `postDelayed`):

```kotlin
PlayUpdateSessionWatcher.stop(ctx)
service?.stopUpdateTicker()
```

Y dentro del `postDelayed`, al restaurar:

```kotlin
if (!pkg.isNullOrBlank()) {
    try {
        val dpm = ctx.getSystemService(Context.DEVICE_POLICY_SERVICE) as DevicePolicyManager
        val admin = ComponentName(ctx, DeviceAdminReceiver::class.java)
        dpm.setUninstallBlocked(admin, pkg,
            prefs.getBoolean("update_flow_prev_uninstall_blocked", false))
    } catch (e: Exception) { e.printStackTrace() }
}
prefs.edit().remove("update_flow_prev_uninstall_blocked").apply()
```

### 2.6 `UpdateFlowManager.kt` — diagnóstico visible

Agregar:

```kotlin
const val KEY_DEBUG_LABELS = "update_flow_debug_labels"

/**
 * Guarda las etiquetas de los botones que el escaneo vio en la ficha. Es lo que
 * permite diagnosticar un equipo en un idioma no previsto sin pedirle un ADB al
 * dueño: quedan visibles en el panel.
 */
fun reportDebugLabels(context: Context, labels: List<String>) {
    if (labels.isEmpty()) return
    val joined = labels.joinToString(" · ").take(300)
    val prefs = PrefsHelper.getMdmPrefs(context)
    if (prefs.getString(KEY_DEBUG_LABELS, null) == joined) return
    prefs.edit().putString(KEY_DEBUG_LABELS, joined).apply()
    syncToPanel(context, force = false)
}
```

Limpiarla en `start()` (`remove(KEY_DEBUG_LABELS)`).

En `FirebaseDeviceSync.syncUpdateFlow()`, agregar al mapa `flow`:

```kotlin
"debugLabels" to (prefs.getString(UpdateFlowManager.KEY_DEBUG_LABELS, "") ?: ""),
```

En `admin-backend/public/app.js`, dentro del bloque que ya muestra `update-flow-card`:

```javascript
const dbgEl = document.getElementById("update-flow-debug");
if (dbgEl) {
    dbgEl.textContent = flow.debugLabels ? ("Botones detectados: " + flow.debugLabels) : "";
}
```

Y en `index.html`, dentro de `#update-flow-card`, después de `#update-flow-status`:

```html
<p id="update-flow-debug" class="hint-text" style="margin:0 0 10px; font-size:11px; opacity:.7;"></p>
```

### 2.7 Ruta silenciosa para apps hosteadas (opcional pero recomendado)

En el diálogo "Actualizar apps" de `LoginActivity` y en el comando `UPDATE_APP`: si
el paquete está en `storeApps` con `apkUrl`, usar
`SelfUpdater.downloadAndInstallApk()` en vez del flujo de Play Store. Como Device
Owner instala en silencio, sin pantalla negra, sin accesibilidad, sin idioma y sin
ninguna superficie de evasión.

**No habilitarlo hasta cerrar B.6** (verificar el `sha256` del APK descargado antes
de abrir la sesión de `PackageInstaller`). Sin checksum, esta ruta es también la más
corta para meter código en el equipo.

---

## 3. Protección de Accesibilidad

### 3.1 Lo que Android permite y lo que no

**No existe ninguna API de MDM para impedir que se desactive un servicio de
accesibilidad.** No hay `DISALLOW_CONFIG_ACCESSIBILITY`; `setSecureSetting()` de
Device Owner no acepta `ENABLED_ACCESSIBILITY_SERVICES`; y volver a encenderlo por
código necesita `WRITE_SECURE_SETTINGS`, que un Device Owner no tiene. Es
deliberado de Google: si un MDM pudiera clavar un servicio de accesibilidad,
cualquier spyware con Device Owner también.

Decírselo al dueño tal cual. Lo que sigue es lo mejor que se puede hacer, y en la
práctica alcanza para que no valga la pena intentarlo.

### 3.2 `PolicyManager.kt`

```kotlin
fun isAccessibilityProtectionEnabled(): Boolean =
    PrefsHelper.getMdmPrefs(context).getBoolean("accessibility_protection_enabled", true)

fun setAccessibilityProtection(enabled: Boolean): Boolean {
    if (deferIfSuspended("accessibility_protection_enabled", enabled)) return true
    PrefsHelper.getMdmPrefs(context).edit()
        .putBoolean("accessibility_protection_enabled", enabled).apply()
    return applyAccessibilityProtection(enabled)
}

/**
 * Impide habilitar CUALQUIER otro servicio de accesibilidad (los del sistema
 * siempre están permitidos). No impide desactivar el nuestro — eso no se puede —
 * pero cierra la puerta a instalar un servicio rival que le pelee a LockSuite.
 */
fun applyAccessibilityProtection(enabled: Boolean): Boolean = try {
    dpm.setPermittedAccessibilityServices(
        adminComponent,
        if (enabled) listOf(context.packageName) else null
    )
    true
} catch (e: Exception) {
    e.printStackTrace()
    false
}
```

- En `reapplyAllRestrictions()`: `if (isAccessibilityProtectionEnabled()) applyAccessibilityProtection(true)`.
- En `liftAllForSuspension()`: `safely { dpm.setPermittedAccessibilityServices(adminComponent, null) }`.

### 3.3 Activada por defecto al configurar la Accesibilidad

En `LockSuiteAccessibilityService.onServiceConnected()`:

```kotlin
val p = PrefsHelper.getMdmPrefs(this)
if (!p.contains("accessibility_protection_enabled")) {
    p.edit().putBoolean("accessibility_protection_enabled", true).apply()
    try { policyManager.applyAccessibilityProtection(true) } catch (e: Exception) { }
}
```

### 3.4 Detección instantánea en vez de 20 segundos — la mejora grande

Hoy `WatchdogForegroundService` sondea cada 20 s: hay una ventana de hasta 20
segundos con el filtro caído y el equipo libre. Un `ContentObserver` lo reduce a
milisegundos, que es lo que convierte la evasión en algo inútil en la práctica.

En `WatchdogForegroundService`:

```kotlin
private val accessibilityObserver = object : android.database.ContentObserver(handler) {
    override fun onChange(selfChange: Boolean) {
        // El usuario tocó el switch de algún servicio de accesibilidad.
        checkAccessibilityStatus()
    }
}

// en onCreate()
try {
    contentResolver.registerContentObserver(
        Settings.Secure.getUriFor(Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES),
        false,
        accessibilityObserver
    )
} catch (e: Exception) { e.printStackTrace() }

// en onDestroy()
try { contentResolver.unregisterContentObserver(accessibilityObserver) } catch (e: Exception) { }
```

El sondeo de 20 s se deja como red de seguridad; deja de ser la primera línea.

### 3.5 Reacción, atada al interruptor

En `checkAccessibilityStatus()`, envolver la reacción en
`if (policyManager.isAccessibilityProtectionEnabled())`, y con la protección activa:

- bajar el antirrebote de relanzamiento de `BlockAccessibilityActivity` de 15 s a
  3 s (hoy deja usar el equipo entre relanzamientos);
- además de suspender navegadores, suspender todas las apps no críticas;
- opcional, a criterio del dueño: `dpm.lockNow()`.

Con la protección desactivada, no hacer nada (el admin la apagó para mantenimiento).

### 3.6 Comandos y panel

`admin-backend/functions/index.js`, en `ALLOWED_COMMANDS`:

```javascript
"PROTECT_ACCESSIBILITY", "UNPROTECT_ACCESSIBILITY",
```

Exigen PIN del dispositivo (no van en la excepción de `UPDATE_*`) y **no** van en
`allowedWhileSuspended` de `LockSuiteFirebaseService`.

`LockSuiteFirebaseService`, en el `when`:

```kotlin
"PROTECT_ACCESSIBILITY" -> policyManager.setAccessibilityProtection(true)
"UNPROTECT_ACCESSIBILITY" -> policyManager.setAccessibilityProtection(false)
```

`FirebaseDeviceSync.syncDeviceInfo()`:

```kotlin
"accessibilityProtected" to policyManager.isAccessibilityProtectionEnabled(),
```

`admin-backend/public/index.html`, en la tarjeta "Políticas de Sistema":

```html
<label class="toggle-row"><span>Proteger Accesibilidad (no permitir otros servicios)</span><input type="checkbox" class="policy-switch" data-policy="accessibilityProtected" /></label>
```

`admin-backend/public/app.js`, en el mapa de `.policy-switch`:

```javascript
accessibilityProtected: ["PROTECT_ACCESSIBILITY", "UNPROTECT_ACCESSIBILITY"],
```

Con eso el interruptor se sincroniza solo, como todos los demás.

En el dashboard de la app, una fila más en la pestaña Servicios con el texto
honesto: *"Impide habilitar otros servicios de accesibilidad y reacciona al
instante si se desactiva el de LockSuite. Android no permite impedir la
desactivación en sí."*

---

## 4. Orden de trabajo

1. **2.1 + 2.2 (el tick).** Probar solo esto primero: es una función y dos llamadas,
   y es lo que explica el trabado.
2. **2.4(a) y 2.4(b)** — `setUninstallBlocked` y no levantar unknown sources. Dos
   cambios chicos, los dos de seguridad.
3. **2.3 + 2.4(c) + 2.5** — el escaneo nuevo y el observador de sesiones.
4. **2.6** — diagnóstico. Barato y ahorra la próxima ronda de adivinanzas.
5. **3.4** (ContentObserver) y después el resto de la sección 3.
6. **2.7** junto con B.6.

## 5. Checklist en equipo real

Actualización:
- [ ] App con actualización pendiente, equipo en **español**: se actualiza sola, el
      porcentaje avanza, Play Store se cierra y queda re-bloqueada.
- [ ] Lo mismo con el equipo en **inglés** y en **hebreo**.
- [ ] Lo mismo desde "Actualizar apps" del celular, antes del PIN.
- [ ] App ya actualizada: sale sola en ~3 s.
- [ ] Cancelar a mitad de la descarga: vuelve a la pantalla común, todo re-bloqueado.
- [ ] Durante la actualización: tocar la pantalla, salir a otra app y usar el
      buscador de Play Store — nada de eso tiene que funcionar.
- [ ] Durante la actualización, intentar instalar un APK por fuera: sigue bloqueado.
- [ ] Con Accesibilidad apagada, "Actualizar apps" se niega a arrancar con mensaje.

Accesibilidad:
- [ ] Protección activada: intentar habilitar otro servicio de accesibilidad →
      Android lo impide.
- [ ] Protección activada: apagar el de LockSuite → la pantalla de bloqueo aparece
      al instante, no a los 20 segundos.
- [ ] Protección desactivada desde el panel: se puede apagar y encender sin que
      salte nada.
- [ ] Reiniciar el equipo: la protección sigue puesta (`reapplyAllRestrictions`).

## 6. Al terminar

Actualizar `LOCKSUITE_CONTEXTO_PARA_IA.md`: en B.9 tachar lo que se confirme en
equipo real, agregar B.13 para la protección de Accesibilidad, y reemplazar la
sección C. Commitear con un mensaje que diga qué se probó y en qué equipo.
