# Actualización de apps y protección de Accesibilidad — diagnóstico y rediseño

**Fecha:** 16 de agosto de 2026
**Autor:** sesión de Claude por el puente al dispositivo (sin SDK, sin compilar, sin equipo real)
**Estado del entorno:** la VM del puente se cayó a mitad de sesión, así que este documento reemplaza al código que iba a escribir directo. Todo lo de acá está pensado para aplicarse con terminal real (Antigravity o vos).

Continúa el trabajo commiteado en `bbdada1`. Leer primero `LOCKSUITE_CONTEXTO_PARA_IA.md`, secciones B.9 y B.11.

---

## 0. Resumen en tres líneas

1. **La actualización se traba porque el bucle es puramente reactivo a eventos de accesibilidad, y Play Store deja de emitir eventos apenas la ficha termina de cargar.** No hay ningún temporizador que vuelva a mirar. Ese es el bug principal, y explica los dos síntomas ("Esperando a Google Play..." y "Buscando el botón Actualizar...").
2. **Depender del texto de los botones fue un error de diseño mío.** Se arregla apoyándose en `PackageInstaller.SessionCallback`, que da progreso y finalización en números, sin idioma, y dejando la accesibilidad únicamente para el toque — con verificación posterior en vez de adivinanza.
3. **Bloquear por MDM que se desactive la Accesibilidad no existe en Android.** No hay API. Lo que sí se puede hacer, y es bastante, está en la sección 3 — incluye una mejora grande: detección instantánea por `ContentObserver` en vez de los 20 s del Watchdog.

---

## 1. Por qué se traba

### 1.1 Causa raíz: el bucle no tiene reloj propio

`handlePlayStoreAutoUpdate()` solo corre cuando llega un `AccessibilityEvent` con
`packageName == "com.android.vending"`. Play Store manda una ráfaga de eventos
mientras la ficha carga y después **se queda callado**: la pantalla es estática,
no hay más `TYPE_WINDOW_CONTENT_CHANGED`.

Consecuencia: si en esa ráfaga el árbol de nodos todavía no estaba disponible
(`findPlayStoreRoot()` devuelve `null` → etapa `WAITING_STORE`) o el botón todavía
no estaba dibujado (etapa `LOOKING_BUTTON`), **nunca se vuelve a mirar**. El texto
de la pantalla negra queda congelado en la última etapa que alcanzó a escribir, que
es exactamente lo que reportaste.

**Arreglo (el más importante de todo el documento):** darle al flujo un tick propio.

```kotlin
// En LockSuiteAccessibilityService
private val updateTickRunnable = object : Runnable {
    override fun run() {
        val pkg = UpdateFlowManager.currentPackage(applicationContext)
        if (pkg.isNullOrBlank() || !UpdateFlowManager.isRunning(applicationContext)) return
        try {
            scanAndAct(pkg)          // el cuerpo de handlePlayStoreAutoUpdate, sin el parámetro event
        } catch (e: Exception) {
            Log.w(TAG, "tick de actualización: ${e.message}")
        }
        mainHandler.postDelayed(this, UPDATE_TICK_MS)   // 700 ms
    }
}

fun startUpdateTicker() {
    mainHandler.removeCallbacks(updateTickRunnable)
    mainHandler.post(updateTickRunnable)
}

fun stopUpdateTicker() {
    mainHandler.removeCallbacks(updateTickRunnable)
}
```

`UpdateFlowManager.start()` llama a `startUpdateTicker()` y `finish()` a
`stopUpdateTicker()`. `handlePlayStoreAutoUpdate()` pasa a ser solo
`scanAndAct(updatingPkg)`; los eventos siguen sirviendo como "despertador" extra,
pero ya no son la única fuente.

`UPDATE_TICK_MS = 700L`. Solo corre mientras hay una actualización en curso, así
que no toca el presupuesto de batería del camino caliente.

### 1.2 Causa secundaria: el emparejamiento por igualdad exacta es demasiado estricto

Al arreglar el bug del `contains` sobre palabras cortísimas me pasé para el otro
lado. Play Store expone el botón de formas que no son igualdad exacta:

- `contentDescription` = `"Actualizar Waze"` (etiqueta + nombre de la app),
- texto `"Actualizar"` en un `TextView` hijo de un contenedor clickeable,
- en hebreo `"עדכון"` (sustantivo) en vez de `"עדכן"` (imperativo), que es el que puse,
- en inglés `"Update"` pero con el equipo en cualquier otro idioma la lista se queda corta.

El arreglo no es agrandar la lista de palabras (eso no termina nunca): es dejar de
depender del texto, sección 2.

### 1.3 Causa terciaria: el filtro del 60 % de la pantalla puede quedar corto

`resources.displayMetrics.heightPixels` desde un `Service` no siempre es la altura
real utilizable, y en fichas con encabezado grande la fila de botones puede caer
más abajo del 60 %. Con el enfoque de la sección 2 el filtro deja de ser un corte
duro y pasa a ser solo un criterio de ORDEN (más arriba = se prueba primero), que
no puede descartar al botón bueno.

---

## 2. Rediseño: dejar de depender de la accesibilidad para todo salvo el toque

### 2.1 La idea

De las cuatro cosas que hacía la accesibilidad, tres tienen una fuente de datos
mucho mejor, que es numérica y no depende del idioma:

| Qué hay que saber | Antes | Ahora |
|---|---|---|
| ¿Arrancó la descarga? | buscar la palabra "Descargando" | `PackageInstaller.SessionCallback` |
| ¿Cuánto va? | leer el texto de la pantalla | `SessionInfo.getProgress()` (0.0 a 1.0) |
| ¿Terminó? | ver que aparezca "Abrir" | `onFinished(id, success)` + `ACTION_PACKAGE_REPLACED` + `versionCode` |
| Apretar "Actualizar" | match de texto | **único uso restante de accesibilidad**, ahora con verificación |

`PackageInstaller.registerSessionCallback()` reporta las sesiones de instalación de
**todos** los instaladores del equipo, incluida Play Store. `SessionInfo.getAppPackageName()`
dice qué app se está instalando. La app ya declara `QUERY_ALL_PACKAGES`, así que
la visibilidad de paquetes de Android 11+ no molesta.

### 2.2 Lo que hace que el toque sea seguro: bloquear la desinstalación durante el flujo

El riesgo de "probar botones" es apretar "Desinstalar". Se elimina de raíz:

```kotlin
// en start(), antes de abrir Play Store
val wasUninstallBlocked = dpm.isUninstallBlocked(admin, targetPkg)
prefs.edit().putBoolean("update_flow_prev_uninstall_blocked", wasUninstallBlocked).apply()
dpm.setUninstallBlocked(admin, targetPkg, true)

// en finish(), al restaurar
dpm.setUninstallBlocked(admin, targetPkg,
    prefs.getBoolean("update_flow_prev_uninstall_blocked", false))
```

Con eso, Android rechaza la desinstalación venga de donde venga. Recién ahí es
razonable probar candidatos.

### 2.3 Candidatos ordenados, y verificar en vez de adivinar

En cada tick, si todavía no arrancó ninguna sesión de instalación para el paquete
objetivo, se aprieta **un** candidato y se espera. Si a los `CANDIDATE_WAIT_MS`
(2500 ms) no apareció sesión ni cambió el `versionCode`, se prueba el siguiente.

Orden de candidatos (de más confiable a menos):

1. **Por ID de vista** — `com.android.vending:id/buy_button`, `action_button`,
   `right_button`, `positive_button`. Independiente del idioma. Las versiones
   viejas de Play Store (equipos Qin, Android 11/12) los exponen.
2. **Por texto**, con la tabla multiidioma de abajo, emparejando por **palabra
   completa** (ya existe `containsWholeWord()` en el archivo) y no por igualdad
   exacta ni por `contains` crudo. `"Actualizar Waze"` matchea; `"Installing"` no
   matchea `"install"` porque la frontera siguiente es una letra.
3. **Por posición** — el último elemento clickeable de la fila superior de botones.
   En todas las variantes de Play Store el botón primario es el de más a la derecha
   de esa fila. Se ordena por `boundsInScreen.top` ascendente y, a igual fila, por
   `left` descendente.

Excluir siempre lo que matchee la lista de prohibidos (desinstalar / cancelar /
detener), pero ya sin depender de que la lista esté completa: si falla, el
`setUninstallBlocked` de 2.2 cubre el peor caso.

**Tabla multiidioma** (fallback nomás; 1 y 3 hacen el trabajo pesado):

```kotlin
private val UPDATE_WORDS = setOf(
    "actualizar", "actualizacion", "update",              // es / en
    "atualizar", "atualizacao",                            // pt
    "mettre a jour", "mise a jour",                        // fr
    "aggiorna", "aggiornare",                              // it
    "aktualisieren", "update",                             // de
    "обновить", "обновление",                              // ru
    "עדכן", "עדכון", "עדכנו",                              // he
    "تحديث",                                               // ar
    "instalar", "install", "installer", "installieren",
    "habilitar", "enable", "reanudar", "resume"
)
private val OPEN_WORDS = setOf(
    "abrir", "open", "ouvrir", "aprire", "offnen",
    "открыть", "פתח", "פתיחה", "فتح"
)
```

Pasar todo por `foldAccents()` (ya existe y además pasa a minúsculas) antes de
comparar, por eso la tabla va sin tildes.

### 2.4 Progreso y finalización reales

```kotlin
private val sessionCallback = object : PackageInstaller.SessionCallback() {
    override fun onCreated(sessionId: Int) { checkSession(sessionId) }
    override fun onBadgingChanged(sessionId: Int) { checkSession(sessionId) }
    override fun onActiveChanged(sessionId: Int, active: Boolean) { checkSession(sessionId) }

    override fun onProgressChanged(sessionId: Int, progress: Float) {
        if (!isOurSession(sessionId)) return
        sawSession = true
        val pct = (progress * 100).toInt().coerceIn(0, 100)
        UpdateFlowManager.setStage(ctx, UpdateFlowManager.STAGE_DOWNLOADING, "Descargando... $pct%")
    }

    override fun onFinished(sessionId: Int, success: Boolean) {
        if (!isOurSession(sessionId)) return
        if (success) {
            UpdateFlowManager.finish(ctx, UpdateFlowManager.RESULT_UPDATED, null)
        } else {
            // Puede ser un fallo real o que Play Store partió la descarga en dos
            // sesiones. No cerrar acá: dejar que el tick decida por versionCode.
            sawSession = false
        }
    }
}

private fun isOurSession(sessionId: Int): Boolean {
    val target = UpdateFlowManager.currentPackage(ctx) ?: return false
    val info = ctx.packageManager.packageInstaller.getSessionInfo(sessionId) ?: return false
    return info.appPackageName == target
}
```

Registrar en `start()` con `registerSessionCallback(cb, mainHandler)` y
**desregistrar sin falta** en `finish()`, incluido el camino de error.

Ojo con `onFinished(success = false)`: Play Store a veces crea y descarta sesiones.
No cerrar el flujo ahí; que el tick confirme por `versionCode`.

### 2.5 Detección de "ya estaba al día", más rápida

Hoy son 8 segundos. Con las señales nuevas se puede bajar a 3:

> Si pasaron 3 s desde que el árbol de Play Store está disponible, **y** no hay
> ninguna sesión de instalación para el paquete, **y** ninguno de los candidatos
> por ID o por texto de actualización existe, **y** sí existe un candidato de tipo
> "abrir" → la app ya estaba al día. Cerrar.

### 2.6 Endurecimiento: no levantar más restricciones de las necesarias

Bug de seguridad que quedó del diseño anterior y conviene arreglar en la misma
pasada: `start()` limpia **las dos** restricciones de instalación.

```kotlin
dpm.clearUserRestriction(admin, UserManager.DISALLOW_INSTALL_APPS)
dpm.clearUserRestriction(admin, UserManager.DISALLOW_INSTALL_UNKNOWN_SOURCES)   // ← sacar
```

Play Store **no** necesita `DISALLOW_INSTALL_UNKNOWN_SOURCES` levantada: esa
restricción es para APKs de fuera de la tienda. Levantarla abre una ventana de
hasta 10 minutos en la que, si el usuario lograra salir del overlay, podría
instalar un APK cualquiera. Dejar solo `DISALLOW_INSTALL_APPS`.

### 2.7 La capa que ni siquiera necesita Play Store

Para las apps que el administrador ya hostea en `storeApps` (la Tienda del panel,
con `apkUrl`), **no hay que abrir Play Store para nada**: como Device Owner,
`SelfUpdater.downloadAndInstallApk()` instala en silencio con `PackageInstaller`.
Sin pantalla negra, sin accesibilidad, sin idioma, sin superficie de evasión, y en
el tiempo que tarda la descarga.

Por eso la sección "Actualizar apps" debería elegir sola:

```
¿el paquete está en storeApps con apkUrl?
    SÍ  → SelfUpdater.downloadAndInstallApk()   (silencioso, preferido)
    NO  → flujo por Play Store (secciones 2.1 a 2.6)
```

Antes de habilitar esto en serio hace falta cerrar **B.6** (verificar el `sha256`
del APK descargado antes de abrir la sesión de `PackageInstaller`): sin checksum,
la ruta silenciosa es también la ruta más corta para meter código en el equipo.

Vale la pena decirlo claro: **si querés que la actualización sea rápida, silenciosa
y sin ninguna posibilidad de evadir el filtro, hostear el APK es la única forma que
da las tres cosas a la vez.** El camino por Play Store siempre va a ser
"manejar la interfaz de otro" y va a tener casos raros.

### 2.8 Lo que NO existe, para no perder tiempo buscándolo

- No hay intent público para "actualizá la app X ahora" en Play Store. El toque en
  la ficha es obligatorio.
- La auto-actualización propia de Play Store corre en su horario (de noche, con
  Wi-Fi y cargando). No sirve para un "actualizar ahora" a pedido.
- Managed Google Play / Android Enterprise **sí** actualiza en silencio y es el
  camino oficial, pero implica inscribir la flota en un EMM con cuentas de Google
  administradas. Es un cambio de plataforma, no un parche.

---

## 3. Bloquear que se desactive la Accesibilidad

### 3.1 Lo primero, la verdad incómoda

**Android no tiene ninguna API de MDM que impida desactivar un servicio de
accesibilidad.** No existe `DISALLOW_CONFIG_ACCESSIBILITY`; `setSecureSetting()` de
Device Owner no acepta `ENABLED_ACCESSIBILITY_SERVICES`; y sin
`WRITE_SECURE_SETTINGS` (que un Device Owner no tiene) no se puede volver a
encender por código. Es una decisión deliberada de Google: si un MDM pudiera
clavar un servicio de accesibilidad, cualquier spyware con Device Owner también.

Cualquiera que diga que lo resolvió, o usa Knox (Samsung), o está haciendo lo que
sigue.

### 3.2 Lo que sí se puede, y alcanza para bastante

**(a) Que no puedan habilitar OTRO servicio de accesibilidad.** Esto sí es una API
real y hoy no se está usando:

```kotlin
dpm.setPermittedAccessibilityServices(admin, listOf(context.packageName))
```

Impide que el usuario active cualquier servicio de accesibilidad que no sea el
nuestro (los del sistema siempre están permitidos). Cierra la puerta a instalar un
servicio rival para pelearle a LockSuite. Pasar `null` lo desactiva.

**(b) Detección instantánea en vez de 20 segundos.** Esta es la mejora grande. Hoy
`WatchdogForegroundService` sondea cada 20 s: hay una ventana de hasta 20 segundos
con el filtro caído. Se reemplaza por un `ContentObserver`, que avisa en
milisegundos:

```kotlin
// en WatchdogForegroundService.onCreate()
private val accessibilityObserver = object : ContentObserver(handler) {
    override fun onChange(selfChange: Boolean) {
        checkAccessibilityStatus()   // reacción inmediata
    }
}

contentResolver.registerContentObserver(
    Settings.Secure.getUriFor(Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES),
    false,
    accessibilityObserver
)
// y unregisterContentObserver en onDestroy()
```

El sondeo de 20 s se deja igual como red de seguridad, pero deja de ser la primera
línea. Con esto, el momento entre "el usuario aprieta el switch" y "aparece la
pantalla de bloqueo" pasa de hasta 20 segundos a prácticamente cero, que es lo que
convierte la evasión en algo inútil en la práctica.

**(c) Reacción más dura, y solo si la protección está activada.** Cuando se detecta
que el servicio se apagó:

1. suspender navegadores (ya lo hace),
2. suspender también todas las apps no esenciales, no solo los navegadores,
3. lanzar `BlockAccessibilityActivity` a pantalla completa (ya lo hace) sin el
   antirrebote de 15 s, que hoy deja usar el equipo entre relanzamientos,
4. `dpm.lockNow()` — el equipo se bloquea y para volver a usarlo hay que pasar por
   la pantalla que explica cómo re-habilitar.

**(d) Estorbar el camino a Ajustes.** La anti-evasión que ya existe en
`handleSettingsAntiEvasion()` rebota al usuario cuando entra a la sección de
Accesibilidad. Es circular (si ya apagó el servicio, no hay quien rebote), pero
sirve para el intento: el usuario tiene que llegar a la pantalla ANTES de poder
apagar nada. Conviene reforzar la detección de esa sección por ID de recurso además
de por texto, para que no dependa del idioma.

En Samsung con Knox licenciado se puede ocultar la entrada de Accesibilidad
directamente (`RestrictionPolicy`), y ahí sí queda cerrado de verdad. Es específico
de Samsung, y `KnoxHardening.kt` ya es el lugar donde va.

### 3.3 El switch que pediste

**Preferencia:** `accessibility_protection_enabled`, por defecto `true`.

Se pone en `true` sola la primera vez que el servicio se conecta
(`onServiceConnected()`), si la clave todavía no existe — que es lo que pediste con
"por defecto al configurar accesibilidad se tiene que activar":

```kotlin
val prefs = PrefsHelper.getMdmPrefs(this)
if (!prefs.contains("accessibility_protection_enabled")) {
    prefs.edit().putBoolean("accessibility_protection_enabled", true).apply()
    PolicyManager(this).applyAccessibilityProtection(true)
}
```

**`PolicyManager`:**

```kotlin
fun isAccessibilityProtectionEnabled(): Boolean =
    PrefsHelper.getMdmPrefs(context).getBoolean("accessibility_protection_enabled", true)

fun setAccessibilityProtection(enabled: Boolean): Boolean {
    if (deferIfSuspended("accessibility_protection_enabled", enabled)) return true
    PrefsHelper.getMdmPrefs(context).edit()
        .putBoolean("accessibility_protection_enabled", enabled).apply()
    return applyAccessibilityProtection(enabled)
}

private fun applyAccessibilityProtection(enabled: Boolean): Boolean = try {
    dpm.setPermittedAccessibilityServices(
        adminComponent,
        if (enabled) listOf(context.packageName) else null
    )
    true
} catch (e: Exception) { e.printStackTrace(); false }
```

Agregarlo a `reapplyAllRestrictions()` (para que sobreviva reinicios) y a
`liftAllForSuspension()` con `setPermittedAccessibilityServices(admin, null)`.

**Comandos nuevos** en `ALLOWED_COMMANDS` de `functions/index.js`:
`PROTECT_ACCESSIBILITY` / `UNPROTECT_ACCESSIBILITY`. Exigen PIN del dispositivo (no
van en la excepción de `UPDATE_*`), y agregarlos a `allowedWhileSuspended` **no**:
con LockSuite suspendido no corresponde tocarlos.

**Panel:** interruptor `data-policy="accessibilityProtected"` en la tarjeta
"Políticas de Sistema", con el mapa
`accessibilityProtected: ["PROTECT_ACCESSIBILITY", "UNPROTECT_ACCESSIBILITY"]`, y
reportar `"accessibilityProtected" to policyManager.isAccessibilityProtectionEnabled()`
desde `FirebaseDeviceSync.syncDeviceInfo()`. Con eso el interruptor se sincroniza
solo, como todos los demás.

**App:** una fila más en la pestaña Servicios del dashboard, con el texto honesto:
"Impide habilitar otros servicios de accesibilidad y reacciona al instante si se
desactiva el de LockSuite. Android no permite impedir la desactivación en sí."

---

## 4. Orden sugerido de implementación

1. **El tick de 700 ms** (1.1). Es una función y dos llamadas, y probablemente
   destrabe el 80 % de lo que estás viendo. Probar esto solo, antes de lo demás.
2. **`setUninstallBlocked` durante el flujo** (2.2) y **sacar el clear de
   `DISALLOW_INSTALL_UNKNOWN_SOURCES`** (2.6). Dos cambios chicos, los dos de
   seguridad.
3. **`PackageInstaller.SessionCallback`** (2.4). Es lo que da progreso real y
   finalización sin depender del idioma.
4. **Candidatos ordenados con verificación** (2.3) + tabla multiidioma.
5. **Protección de Accesibilidad** (3.3), empezando por el `ContentObserver` (3.2b),
   que es la parte que más cambia en la práctica.
6. **Ruta silenciosa para apps hosteadas** (2.7), junto con B.6 (checksum).

## 5. Checklist de prueba en equipo real

- [ ] App con actualización pendiente, equipo en español: se actualiza sola, el
      porcentaje avanza, Play Store se cierra y queda re-bloqueada.
- [ ] Lo mismo con el equipo en **inglés** y en **hebreo**.
- [ ] Lo mismo desde el botón "Actualizar apps" del celular, antes del PIN.
- [ ] App ya actualizada: sale sola en ~3 s diciendo que ya estaba al día.
- [ ] Cancelar a mitad de la descarga: vuelve a la pantalla común, todo re-bloqueado.
- [ ] Durante la actualización, intentar tocar la pantalla, salir a otra app y usar
      el buscador de Play Store: nada de eso tiene que funcionar.
- [ ] Durante la actualización, intentar instalar un APK por fuera: tiene que
      seguir bloqueado (esto es el cambio 2.6).
- [ ] Con Accesibilidad apagada, "Actualizar apps" se niega a arrancar con mensaje.
- [ ] Protección de Accesibilidad activada: intentar habilitar otro servicio de
      accesibilidad → Android lo impide.
- [ ] Protección activada: apagar el servicio de LockSuite → la pantalla de bloqueo
      aparece al instante, no a los 20 segundos.
- [ ] Protección desactivada desde el panel: se puede apagar y encender el servicio
      sin que salte nada.
