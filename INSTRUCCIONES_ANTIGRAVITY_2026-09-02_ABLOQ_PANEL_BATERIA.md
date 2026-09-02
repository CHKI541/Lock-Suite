# 2/9/2026 — Comparación con A Bloq, arreglos de panel/PIN/nombre/presets, batería y auditoría de secretos

Sesión de Claude por el puente al dispositivo. **`device_bash` no montó otra vez**, así que no
hubo terminal ni `git` sobre el disco: los archivos se escribieron con
`device_stage_files`/`device_commit_files` y **el commit lo tenés que hacer vos** (mensaje listo
al final).

Todo el Kotlin nuevo se type-checkeó con `kotlinc` 2.0.21 contra stubs escritos a mano:
**0 errores / 0 warnings**, con **cuatro controles negativos** (método inexistente, tipo
equivocado, rama de `when` con tipo malo, referencia inexistente) que el chequeo detectó — o sea
que el "0 errores" no es que no compiló nada. `node --check` limpio sobre `app.js` e `index.js`.
**No se corrió Gradle. No se probó nada en equipo real.**

---

## 0. Orden de prueba recomendado

Ordenado por "cuál descarta más rápido el problema más molesto".

1. **§3 — Canal de comandos.** Es el más importante y el que explica "no lo puedo controlar
   desde la web" y "hay que cambiar el PIN varias veces". Probá: mandar cualquier comando desde
   el panel y confirmar que aparece un `commandAcks/<id>` con `status: applied`. Si aparece
   `rejected` con un motivo, **ese motivo es el diagnóstico** (antes no había ninguno).
2. **§4 — Nombre de dispositivo.** Poné un nombre desde la web, esperá 2 minutos, recargá.
   Tiene que seguir ahí (antes se borraba solo en segundos).
3. **§5 — Presets.** Guardá un perfil desde el panel de un equipo SIN apps con internet
   bloqueado y aplicalo. Antes fallaba siempre con "archivo alterado".
4. **§6 — En línea.** Dejá el celular con la pantalla apagada 20 minutos y mirá el panel.
5. **§7 — Batería.** Comparar consumo del proceso con `dumpsys batterystats` antes/después.
6. **§8 — Seguridad de GitHub.** No requiere compilar; hacelo cuando quieras.

---

## 1. Qué es A Bloq / SecureGuard MDM y qué se le puede copiar

Repo: `github.com/imreykodesh/secureguardmdm-private`.
**Ojo #1: el repo se clona sin credenciales — es público, a pesar del nombre.**
Es un espejo de `github.com/sesese1234/SecureGuardMDM`; sitio y foro en
`cfopuser.github.io/A-bloq-site/` y `mitmachim.top/post/974945`. Versión 0.5.0, ~11.800 líneas
de Kotlin, 4.053 descargas. Comunidad haredí israelí, mismo problema que LockSuite.

**Arquitectura: parecida en el objetivo, muy distinta en la forma.**

| | LockSuite | A Bloq |
|---|---|---|
| Administración | Panel web + FCM (remoto) | **Solo local**, en el propio equipo. No hay panel ni nube. |
| Políticas | Métodos sueltos en `PolicyManager.kt` (91 KB) | **Un objeto por política** (`ProtectionFeature`) + `FeatureRegistry` |
| UI | Compose a mano | Compose + Hilt + Room, generada desde el registro |
| VPN | Túnel dividido, **solo DNS** | Túnel **completo** con `addRoute("0.0.0.0", 0)` — es un corta-internet, no un filtro |
| Filtro de contenido | Propio (DNS + accesibilidad + overlays) | Delega en **NetFree** (filtro externo israelí) y lo vigila |
| Contraseña | SHA-256 + salt | **BCrypt costo 12** |
| Kiosco | Launcher propio | `setLockTaskPackages` + `LOCK_TASK_FEATURE_NONE` (kiosco real del SO) |

### 1.1 Lo que CONVIENE copiar (ordenado por valor/esfuerzo)

**(a) El patrón `ProtectionFeature` + `FeatureRegistry`. Es lo más valioso de todo el repo.**
Cada restricción es un objeto con `id`, título, descripción, ícono, `requiredSdkVersion`,
`applyPolicy()` e `isPolicyActive()`. La UI, el registro de categorías y (en LockSuite) la lista
de comandos FCM y el panel web **se derivarían de una sola lista** en vez de mantenerse en
paralelo a mano en cinco lugares (`PolicyManager`, `LockSuiteFirebaseService`, `ALLOWED_COMMANDS`,
`app.js`, `index.html`). Hoy esos cinco están alineados por casualidad y por revisión manual —
esta sesión lo verificó y da 105/106, pero es una alineación que se rompe sola en cuanto se
agregue una política. **No hay que reescribir `PolicyManager` para aprovecharlo:** alcanza con
un archivo nuevo `mdm/PolicySpec.kt` que declare la lista (clave, comando, etiqueta, SDK mínimo)
y que `LockSuiteFirebaseService` y un `policies.json` generado para el panel la consuman.

**(b) `isPolicyActive()` — leer el estado REAL del sistema, no la preferencia.**
`PolicyManager.isRestrictionEnabled()` lee `SharedPreferences`, o sea lo que LockSuite *quiso*
aplicar. A Bloq consulta `dpm.getUserRestrictions(admin)`, o sea lo que el sistema *tiene
puesto*. Cuando divergen —una llamada al DPM que falló, un OEM que la ignora, Knox— LockSuite
muestra el interruptor encendido y el equipo está desprotegido, en silencio. **Es el mismo tipo
de bug que B.15 punto 3** ("se recordaba 'ya lo apliqué' en una variable"), pero para las
restricciones DPM. Sugerencia concreta: agregar `isRestrictionActuallyEnforced()` y reportar al
panel las que difieren, como un aviso ámbar por equipo.

**(c) Restricciones que A Bloq tiene y LockSuite no.** LockSuite maneja 20 constantes
`DISALLOW_*`; A Bloq 39. Las que faltan y valen la pena, con lo que cierran:

| Constante | Qué cierra en un equipo kosher |
|---|---|
| `DISALLOW_CONFIG_PRIVATE_DNS` (API 29) | **La más importante.** Hoy LockSuite re-impone "DNS privado apagado" cada 60 s desde el Watchdog (ver `WatchdogForegroundService`); con esta restricción el usuario directamente no puede tocarlo, y se puede bajar esa reimposición. Cierra la ventana de hasta 60 s en que el filtro DNS no ve nada. |
| `DISALLOW_SMS` / `DISALLOW_OUTGOING_CALLS` | Control de mensajería y llamadas, muy pedido en este tipo de equipos. |
| `DISALLOW_CONFIG_LOCATION`, `DISALLOW_SHARE_LOCATION` | Ubicación. |
| `DISALLOW_AUTOFILL` (26), `DISALLOW_CONTENT_CAPTURE` (29) | Evitan que el contenido de pantalla salga por otro camino. |
| `DISALLOW_PRINTING` (28), `DISALLOW_OUTGOING_BEAM`, `DISALLOW_USB_FILE_TRANSFER`, `DISALLOW_DATA_ROAMING`, `DISALLOW_AIRPLANE_MODE`, `DISALLOW_AMBIENT_DISPLAY` (28), `DISALLOW_SYSTEM_ERROR_DIALOGS`, `DISALLOW_SET_WALLPAPER`, `DISALLOW_SET_USER_ICON`, `DISALLOW_CONFIG_CREDENTIALS`, `DISALLOW_CONFIG_CELL_BROADCASTS`, `DISALLOW_REMOVE_MANAGED_PROFILE` | Endurecimiento general. Cada una es ~10 líneas siguiendo el patrón de (a). |

**(d) Corta-internet REAL, con el kernel de por medio.** Esto es la respuesta definitiva a
**B.4**, y hay que decirla con precisión porque es fácil sacar la conclusión equivocada:

> **A Bloq usa `setAlwaysOnVpnPackage(admin, paquete, lockdown = true)` y le funciona.
> LockSuite lo probó dos veces y le rompió el internet. Los dos son correctos.**
> A Bloq puede porque su `BlockerVpnService` hace `addRoute("0.0.0.0", 0)` +
> `addAllowedApplication(propioPaquete)`: se queda con TODO el tráfico y no lo reenvía a
> ninguna parte. **Su VPN es un corta-internet, no un filtro.** Con lockdown, Android le exige
> a la VPN hacerse cargo de todo el tráfico — y la de A Bloq justamente quiere descartarlo
> todo. La de LockSuite es un túnel dividido que solo sabe qué hacer con UDP/53, así que el
> resto se queda sin salida. **No copiar `lockdown = true` sobre `KosherVpnService`.**

Lo que **sí** conviene copiar es ese servicio como una **segunda** VPN, solo para bloquear:
hoy `PolicyManager.setInternetBlocked()` y `BootGate.engage()` usan
`setRecommendedGlobalProxy("127.0.0.1", 9999)`, que es un proxy *recomendado* —
lo respetan WebView/Chrome/OkHttp pero **no** una app con sockets crudos o QUIC (está dicho
así en el comentario de cabecera de `BootGate.kt`, sin vender de más). Un `VpnService` con ruta
por defecto y sin apps permitidas lo hace cumplir el kernel: **fallo cerrado de verdad**, y
además arregla el problema de B.20 de raíz, porque no deja ningún ajuste global del sistema que
se pueda quedar clavado. Es la mejora de arquitectura más grande que sale de esta comparación.

**(e) BCrypt para el PIN — cierra B.3-b.** A Bloq usa
`BCrypt.withDefaults().hashToString(12, password)` (`at.favre.lib:bcrypt`). LockSuite usa
SHA-256, que es rápido a propósito y por lo tanto malo para un PIN de 4-16 dígitos si el hash
se filtra. **Cuidado con la trampa:** el hash de LockSuite se calcula en TRES lugares que tienen
que coincidir (`PinManager.kt`, `hashPin` en `functions/index.js`, `hashPinLocal` en `app.js`),
así que migrar implica los tres a la vez más los PINs ya existentes. Sigue siendo B.3-b y sigue
sin ser urgente, pero ahora hay una implementación de referencia.

**(f) Kiosco con `LOCK_TASK_FEATURE_NONE`.** `dpm.setLockTaskPackages()` +
`setLockTaskFeatures(LOCK_TASK_FEATURE_NONE)` + `startLockTask()` bloquea inicio, recientes,
notificaciones, pantalla dividida y keyguard **a nivel de sistema operativo**. El launcher
kosher de LockSuite es una Activity normal: se puede salir con gestos según el equipo. Es un
endurecimiento opcional grande para `KosherLauncherActivity`.

**(g) `SecureUpdateHelper.verifyLocalApkSignature()` — el código de B.6, ya escrito.**
Compara la firma del APK descargado (`getPackageArchiveInfo(..., GET_SIGNING_CERTIFICATES)`)
contra la del paquete instalado. **Está entero pero COMENTADO**, con un
`return true // TEMPORARILY ALWAYS RETURN TRUE TO BYPASS SIGNATURE VERIFICATION`. O sea:
copiar la forma, **no** el estado. Cubre la mitad de B.6 (la autoactualización); la otra mitad
—la Tienda administrada, que es primera instalación y no tiene firma previa contra la cual
comparar— sigue necesitando el `sha256` en `version.json`/`storeApps`.

### 1.2 Lo que NO conviene copiar

- **`lockdown = true` sobre la VPN actual.** Ver (d).
- **`SecureUpdateHelper` tal como está**: la verificación de firma está desactivada.
- **`isOfficialBuild()`**: compara un string de recursos consigo mismo
  (`buildStatus.equals(buildStatus)`) — devuelve `true` siempre. Es código muerto que aparenta
  ser un control de integridad.
- **`ServiceWatchdogJob`**: pide `setPeriodic(1 minuto)`, que Android sube a 15 min igual, y
  llama a `startForegroundService` en cada disparo sin mirar si el servicio ya corre. Es
  exactamente el patrón que esta sesión **sacó** de LockSuite por batería (§7).
- **`BlockIncomingCallsFeature`**: instala un APK de terceros (`org.fossify.phone.debug`) desde
  los assets para reemplazar el marcador. Ingenioso, pero es superficie de ataque y dependencia
  de un binario ajeno dentro del APK.
- **La delegación en NetFree**: LockSuite tiene su propio filtro; sumar una dependencia externa
  cambiaría el producto.

---

## 2. Estado del repo al empezar (medido, no supuesto)

**El documento de contexto estaba una sesión atrasado.** El HEAD real es `3d1174e` del
**1/9 19:52 UTC**: *"fix(red/suspension/purga): reintento DNS ante ENETUNREACH, eleccion de red
validada, y simetria de suspender/purgar"*, que toca `PolicyManager.kt`, `BootReceiver.kt`,
`KosherVpnService.kt` y `NetworkForwarder.kt`. O sea que **Antigravity ya aplicó los parches de
B.24, B.11 y B.25** que el documento daba por pendientes. Verificado además contra el disco: los
12 archivos clave coinciden **byte por byte** en tamaño con `device_list_dir`, así que el árbol
de trabajo estaba limpio y sincronizado con GitHub.

**Hallazgo de método que ahorra una sesión entera:** el repo `github.com/CHKI541/Lock-Suite` es
público, así que **se puede clonar directo en el contenedor** en vez de pelear con el tope de 7
carpetas de `device_stage_files` o con el truco de `.git/objects/` del 1/9. Verificar que el
clon corresponde al disco comparando tamaños contra `device_list_dir` (así se hizo). Es
muchísimo más rápido. **El día que el repo pase a privado (B.2), este atajo desaparece** — y ahí
sí hay que pedir la carpeta `app\src\main\java\com\ejemplo\locksuite` de entrada.

---

## 3. EL CANAL DE COMANDOS: por qué "no lo puedo controlar de la web" y por qué "hay que cambiar el PIN varias veces"

Son **el mismo problema**, y esta es la parte más importante del documento.

### 3.1 La cadena

Cada comando del panel viaja por FCM firmado con HMAC-SHA256. El secreto (`commandSecret`) lo
genera el celular y lo publica en `deviceSecrets/<id>/commandSecret`. La Cloud Function firma
con ese valor; el celular verifica con el suyo. Si no coinciden, `onMessageReceived` hacía:

```kotlin
android.util.Log.w("LockSuiteFCM", "Comando FCM rechazado: autenticación o replay inválido.")
return
```

**Sin ack, sin campo en Firebase, sin nada.** Y la Function, mientras tanto, ya había escrito
`commandAcks/<id> = {status: "sent"}`. Desde el panel es **indistinguible de un equipo apagado**.

### 3.2 Causa A — el secreto se desincroniza y NO se puede resincronizar

La regla de `database.rules.json` dice, textual:

```json
"commandSecret": { ".write": "auth != null && (!data.exists() || newData.val() === data.val() || auth.token.admin === true || root.child('authorizedAdminsUids').child(auth.uid).exists())" }
```

O sea: **el dispositivo puede escribirlo la primera vez, o reescribir el mismo valor, pero nunca
cambiarlo.** Es correcto como defensa (que nadie que adivine un `deviceId` se apropie del
equipo). El problema es el ciclo de vida:

- el secreto vive en `EncryptedSharedPreferences` → **no** sobrevive a reinstalar la app ni a una
  invalidación del Keystore;
- el `deviceId` es el `ANDROID_ID` → **sí** sobrevive.

**Reinstalás LockSuite en el mismo equipo y el canal queda roto para siempre.** Y vos reinstalás
APKs todo el tiempo mientras desarrollás: es casi seguro que ya te pasó.

### 3.3 Causa B — el PIN quedaba atrapado en la misma escritura atómica

`FirebaseDeviceSync.syncPinCredentials()` mandaba `{pinHash, pinSalt, commandSecret}` en **un
solo `updateChildren()`**. Un `updateChildren` de Realtime Database es **atómico**: si las reglas
rechazan UNA ruta, **se rechaza la escritura entera**.

Entonces, con el secreto desincronizado (§3.2), cambiar el PIN en el celular **no subía el PIN
tampoco**. El panel seguía validando contra el hash viejo → "PIN incorrecto". Cambiabas otra vez,
y otra vez, hasta que en algún momento el estado se alineaba solo. **Ese es, exactamente, "a
veces hay que cambiar el pin de administrador varias veces hasta que lo puedo controlar de la
web".**

### 3.4 Causa C — el reloj del celular

`verifyFcmSignature()` rechazaba todo comando con
`Math.abs(System.currentTimeMillis() - timeMs) > 5 min`. El timestamp lo pone la Cloud Function
(reloj de Google, exacto); la comparación era contra el **reloj de pared del celular**. En un
equipo sin SIM, sin hora de red, o después de quedarse sin batería, ese reloj se corre horas o
días → **todos** los comandos rechazados, en silencio, para siempre. Nada apunta al reloj: es la
causa más difícil de sospechar de las tres.

### 3.5 Qué se cambió

**`FirebaseDeviceSync.kt`**
- `syncPinCredentials()` ahora hace **dos escrituras separadas**: `{pinHash, pinSalt}` por un
  lado y el secreto por otro. **El PIN llega siempre.** (Arregla §3.3.)
- `pushCommandSecret()` nuevo: escribe el secreto solo y, si las reglas lo rechazan, marca
  `devices/<id>/commandSecretMismatch = true` — una ruta que el equipo **sí** puede escribir.
  El fallo deja de ser invisible.
- `syncInstalledApps` pasa a ir dentro de `withAuth` y reafirmando `ownerUid`. Antes eran dos
  `setValue()` sueltos que las reglas rechazaban después de que rotara el uid anónimo: la lista
  de apps del panel se quedaba congelada en la de la instalación anterior, sin ningún error.

**`LockSuiteFirebaseService.kt`**
- El rechazo mudo pasa a ser un `when` con motivo, y **cada rechazo escribe un ack**
  `{status: "rejected", reason: ...}`. Motivos: `MISSING_COMMAND_ID`, `MISSING_SIGNATURE`,
  `MISSING_TIMESTAMP`, `TIMESTAMP_OUT_OF_WINDOW`, `BAD_SIGNATURE`, `REPLAY`.
  **Esto es lo que convierte un fallo silencioso en un diagnóstico de diez segundos.**
- `BAD_SIGNATURE` dispara `pushCommandSecret()`, que marca la bandera para el panel.
- `timestampOutOfWindow()` nuevo reemplaza el chequeo de reloj (§3.4): ventana absoluta de
  **24 h** y solo si el reloj es creíble (posterior a 2024), **más un piso monotónico** — se
  recuerda el timestamp más alto aceptado y se rechaza cualquiera anterior en más de 10 min (la
  tolerancia es porque FCM puede reordenar entregas). La protección anti-repetición **no
  empeora**: la siguen dando `commandId` + `isReplay()` + el piso monotónico, y ninguna de las
  tres depende del reloj del equipo.
- Latido oportunista: `syncLastSeenOnly()` al recibir cualquier mensaje FCM, **antes** de
  validar la firma (un equipo con el secreto roto tiene que seguir apareciendo en línea, si no
  el diagnóstico apunta al lugar equivocado).

**`admin-backend/public/app.js`**
- `relinkCommandSecret()` nuevo. Al abrir un equipo con `commandSecretMismatch`, el panel
  explica el problema y ofrece re-vincular: borra `deviceSecrets/<id>/commandSecret` (un admin
  autorizado **sí** puede, por la misma regla de arriba) para que el equipo lo vuelva a escribir
  con `!data.exists()`. **No hace falta tocar las reglas ni desplegar Functions.**
- La tarjeta del equipo muestra un estado **"Re-vincular"** en ámbar.

**Qué mirar en la prueba:** mandá un comando cualquiera y mirá
`devices/<id>/commandAcks/<commandId>` en la consola de Firebase. `applied` = anda. `rejected`
+ motivo = ahí está la causa. Si nunca aparece nada, el problema es anterior (token FCM o red),
no la firma.

---

## 4. "No se registra si pongo el nombre de dispositivo de la web"

**Causa exacta.** El panel escribe `devices/<id>/deviceName`. Pero `syncDeviceInfo()` incluía

```kotlin
"deviceName" to prefs.getString("device_name", "")
```

en su `writeFields()`. El Watchdog dispara esa sincronización todo el tiempo (y de entrada al
arrancar el servicio), así que **el celular pisaba el nombre del panel con la cadena vacía**, en
segundos. El único lugar que traía el nombre desde Firebase era un `LaunchedEffect` dentro de
`DashboardScreen`, o sea que solo pasaba si alguien abría el panel local **en el celular**.

**Arreglo — regla explícita: el panel manda.**
- `deviceName` sale del `writeFields()` periódico.
- `reconcileDeviceName()` nuevo: lee `devices/<id>/deviceName`; si hay un nombre remoto no
  vacío, el celular lo **adopta**; si no hay ninguno y el celular sí tiene uno, lo sube.
  **Nunca se escribe una cadena vacía sobre un nombre existente.**
- `pushDeviceName()` nuevo para el botón "Guardar" del Dashboard local, que es el único lugar
  donde el celular tiene que ganarle al panel.

---

## 5. Presets: por qué no andaban

### 5.1 El bug que los rompía casi siempre

**Realtime Database no guarda arrays vacíos**: equivalen a `null` y la clave desaparece del nodo.
El panel armaba el perfil con `perAppInternetBlocked: []`, **firmaba sobre ese objeto** y recién
después lo guardaba en `presets/`. Al leerlo para aplicarlo, la clave ya no estaba: el objeto que
llegaba al celular **no era el que se había firmado**, el HMAC no podía coincidir nunca, y
`importPolicyPresetJson()` tiraba `SecurityException("Firma del archivo de respaldo inválida.
Archivo alterado no autorizado.")`.

Como la enorme mayoría de los equipos **no** tiene ninguna app con internet bloqueado por app,
eso pasaba **casi siempre**: prácticamente todo perfil creado desde el panel era imposible de
aplicar.

- **Panel:** ahora se borra la clave **antes** de firmar, para firmar exactamente lo que se
  guarda.
- **App:** tercer fallback de verificación que reconstruye el array vacío que RTDB se comió, para
  que **los perfiles ya guardados** sigan funcionando sin volver a crearlos.

### 5.2 Una restricción que nunca se aplicó

El panel escribía la clave `no_apps_control`. **Esa constante no existe en Android**: la real es
`UserManager.DISALLOW_APPS_CONTROL == "no_control_apps"`. `addUserRestriction()` con una clave
desconocida no da error — la acepta y no hace nada. O sea que "Bloquear control de apps" viajaba
en todos los perfiles del panel y **no se aplicaba nunca, en silencio**.
Corregido en el panel, y `normalizeRestrictionKey()` en `PolicyManager` traduce la clave vieja
para que los perfiles ya guardados también funcionen.

### 5.3 Cobertura: el perfil dejaba afuera media configuración

Se agregaron al perfil (exportación e importación): `DISALLOW_BLUETOOTH`,
`DISALLOW_CONFIG_LOCALE`, `DISALLOW_CONFIG_MOBILE_NETWORKS`, `DISALLOW_CONFIG_DATE_TIME`,
`DISALLOW_NETWORK_RESET` (esta además **no se reportaba**: el panel la daba por apagada y al
aplicar el perfil la **quitaba** del equipo de destino — se agregó `networkResetBlocked` a
`syncDeviceInfo`), más los interruptores `flashingBlocked`, `hideSuspendedApps`,
`accessibilityProtection`, `accBounceSettings`, `accNag`, `accSuspendAll`, `bootGateEnabled`,
`bootGateWaitAccessibility`, `imageBlockStrictScroll`.

**Detalle importante de diseño:** en la importación, el valor por omisión de las claves nuevas es
**el valor actual del equipo**, no `false`. Un perfil viejo (guardado antes de que existieran) no
debe **apagar** protecciones que el equipo ya tiene puestas — en particular el arranque protegido
y la protección de accesibilidad, que vienen encendidas por defecto. **Un perfil solo cambia lo
que nombra.**

### 5.4 Lo que sigue faltando en presets (NO se tocó)

- El perfil **no** guarda apps suspendidas/ocultas, reglas DNS, bloqueos de WebView, modos de
  bloqueo de imagen por app ni la lista blanca del launcher. Un "perfil" sigue siendo solo
  políticas globales.
- `APPLY_PRESET_PROFILE` viaja por el `data` de FCM, que tiene un tope duro de ~4 KB; la Function
  corta en 3.000 bytes. Con las claves nuevas el margen se achicó. Si aparece un error 413,
  la salida limpia es escribir el perfil en `devices/<id>/pendingPreset` y mandar por FCM solo el
  aviso de que lo lea. **Vale la pena hacerlo antes de sumar más claves.**
- La clave HMAC sigue siendo la de B.5, pública. Ver §8.

---

## 6. "A veces los celulares no quedan en línea"

**No es una sola causa; son dos, y una es del panel.**

1. **El latido se congela cuando el equipo duerme.** `syncLastSeenOnly()` iba cada 90 s dentro de
   un `handler.postDelayed(..., 20000)`. `Handler.postDelayed` cuenta con
   `SystemClock.uptimeMillis()`, que **no avanza en sueño profundo**. Un servicio de primer plano
   **no** impide que el equipo entre en sueño profundo (no hay wakelock). Con la pantalla apagada
   un rato, el latido se espacia solo.
2. **El panel llamaba "Desconectado" a los 5 minutos**, y eso es engañoso por partida doble: el
   equipo puede estar perfecto y dormido, y además **un comando FCM de alta prioridad lo
   despierta igual**, así que un celular "desconectado" se administra sin problema.

**Cambios:**
- `WatchdogWorker` (WorkManager, 15 min, es lo único que sobrevive a que muera el proceso y que
  corre en la ventana de mantenimiento de Doze) ahora hace `syncLastSeenOnly()` **primero y
  barato**. Antes sincronizaba al final y con `syncDeviceInfo()`, la pesada — o sea que si
  `reapplyAllRestrictions()` tardaba o el proceso moría antes, el latido no salía nunca.
- Latido al recibir cualquier FCM (§3.5): cada comando del panel refresca el estado.
- Panel: **"En línea"** hasta 20 min, **"En reposo"** (ámbar, con explicación al pasar el mouse:
  se puede administrar igual) mientras haya token, **"Desconectado"** recién pasado un día.

**Lo honesto que hay que decir:** esto hace que el panel diga la verdad y que el latido sea más
confiable. **No** convierte el latido en tiempo real; para eso haría falta una alarma
`setAndAllowWhileIdle` (o presencia de RTDB con socket abierto), y las dos cuestan batería —
justo lo contrario de lo que pediste. Si después de probar querés precisión en vez de batería,
la alarma es el camino y son ~30 líneas más un receiver en el Manifest.

---

## 7. Batería y CPU

Todo lo de esta sección es **quitar trabajo repetido**, sin cambiar ninguna protección.

**(a) Dos arranques de servicio por ciclo, 4.320 veces por día cada uno.**
`checkRunnable` corre cada 20 s y llamaba **siempre** a:
- `BootReceiver.ensureVpnRunning()` → `startForegroundService(KosherVpnService)`
- `startForegroundService(WatermarkService)` (si el launcher kosher está activo)

Aunque el servicio ya esté corriendo, cada llamada es una transacción Binder contra
ActivityManagerService que despierta al proceso y lo obliga a reafirmar el primer plano; en el
caso de la VPN, además, entra a `startVpn()` y toma `lifecycleLock` para salir por su propio
`if (running) return`. **8.640 arranques de servicio por día tirados a la basura**, en el mismo
proceso que tiene el hilo lector del túnel DNS.

Ahora hay un espejo en memoria (`KosherVpnService.isTunnelRunning`, `WatermarkService.isRunning`)
que se consulta gratis, y **un reintento forzado cada 5 minutos** que ignora el espejo.

**Por qué esto NO debilita la auto-reparación** (importante, no lo "simplifiques" después):
- Si el **proceso muere**, los espejos vuelven a `false` solos porque se reinicializan con la
  clase. El caso común se detecta **al instante**, igual que antes.
- Si el servicio está **vivo pero inútil** (el túnel no se estableció, la ventana de la marca de
  agua se perdió), un booleano no puede verlo — para eso está el forzado de 5 min. Solo ese caso
  raro pasa de 20 s a como mucho 5 min, y sigue habiendo `onRevoke()`, el callback de red y el
  Worker de 15 min por debajo.

**(b) `PolicyManager` construido cada 20 s.** Su constructor resuelve el `DevicePolicyManager` y
arma un `ComponentName`. Ahora se construye solo cuando de verdad hace falta arrancar la marca de
agua. `Settings.canDrawOverlays()` (otra llamada al sistema por ciclo) también.

**(c) Sugerido, NO aplicado** — porque cambia comportamiento y quiero que lo decidas vos:
- La reimposición de "DNS privado apagado" cada 60 s se puede reemplazar por
  `DISALLOW_CONFIG_PRIVATE_DNS` (§1.1c): el usuario no puede tocarlo y la reimposición sobra.
  **Es la mejora de batería más grande que queda**, y encima cierra mejor el agujero.
- `WatchdogWorker` llama a `syncDeviceInfo()` (pide el token de FCM, enumera políticas, escribe
  ~40 campos) cada 15 minutos. Con `syncLastSeenOnly()` ya al principio, la pesada podría bajar a
  una de cada cuatro vueltas (una hora).
- `enforcerRunnable` corre cada 20 s **además** del ciclo grande. Con el `ContentObserver` y los
  avisos de `onServiceConnected`/`onDestroy` ya cubriendo la detección instantánea, ese ciclo es
  la red de seguridad: 60 s alcanzaría. **No lo toqué** porque el pedido explícito del 18/8 fue
  "detectar todo el tiempo" (B.15) y no quiero revertir una decisión tuya sin preguntar.

---

## 8. Seguridad y datos privados en GitHub

### 8.1 Lo bueno (medido, no supuesto)

`.gitignore` está bien armado y **funciona**: no hay keystores, ni `google-services.json`, ni
service accounts, ni `local.properties`, **ni en HEAD ni en todo el historial** (se revisó con
`git log --all --diff-filter=A`). Eso descarta la clase de filtración más grave.

La `apiKey` de `firebase-config.js` **no es un secreto** — es un identificador público del
proyecto, Firebase lo documenta así; lo que protege es `database.rules.json`.

### 8.2 Lo que hay que sacar

1. **`scratch/diagnostico_vpn_vivo/` — 2,1 MB de logcat en vivo de tu celular real.** Es lo más
   privado que hay en el repo: expone el fingerprint exacto del equipo, todas las apps
   instaladas, su comportamiento de red y 14 menciones de IMEI. No hay emails ni credenciales
   (se buscaron). **No es una fuga crítica, pero no tiene por qué estar público.**
   → Sacarlo del repo y agregar `scratch/` al `.gitignore`.
2. **`zizR3CfM`** (35 KB): un ZIP suelto en la raíz con una copia vieja de
   `LOCKSUITE_CONTEXTO_PARA_IA.md`. Basura, borrar.
3. **`Claude web 1.zip`**, `_to_delete/_snapshot_56/GIT_DIFF_FULL.txt`: lo mismo, ruido histórico
   publicado sin necesidad.

### 8.3 Lo que sigue siendo el problema de fondo (B.2 + B.5)

**El repo entero es público, y eso es lo que más rinde arreglar.** Cualquiera puede leer, sin
decompilar nada:

- **la clave HMAC de los presets** (`LockSuiteMDM_Preset_HMAC_SecretKey_2026`, en `app.js` y en
  `PolicyManager.kt`) → puede firmar un `.locksuite` válido y aplicarle al equipo el perfil que
  quiera;
- **el código de purga total** (`*#*#9999#*#*`) y todo el flujo de emergencia;
- **el mapa completo de evasión**: qué palabras busca la Capa 3, qué view-ids de Mercado Pago,
  qué dominios están en las listas, cómo funciona el arranque protegido y dónde están sus topes
  de tiempo;
- **`database.rules.json`**, o sea el modelo de permisos exacto contra el que probar.

Nada de esto es una contraseña, así que no hay que rotar credenciales. Pero para un producto cuyo
valor es que **el usuario final no pueda evadirlo**, publicar el mapa de evasión es el problema.

**Recomendación, en orden:**
1. **Pasar `CHKI541/Lock-Suite` a privado.** Settings → General → Danger Zone → Change visibility.
   El APK y `version.json` ya se sirven desde Firebase Hosting, que es público sin publicar el
   código: **el auto-updater OTA y el instalador web siguen funcionando igual.** Es la
   recomendación de mayor impacto y menor esfuerzo de todo el proyecto (es B.2, sigue abierta).
   ⚠️ Después de hacerlo, el atajo de "clonar el repo desde el contenedor" (§2) deja de andar:
   avisale a la próxima sesión de IA que pida la carpeta `app\src\main\java\com\ejemplo\locksuite`
   desde el principio.
2. **Sacar `scratch/` del repo** (§8.2) — hacelo aunque el repo pase a privado.
3. **Reemplazar la clave HMAC de presets por firma asimétrica** (ECDSA P-256): clave privada solo
   en una Cloud Function, pública embebida en la app solo para verificar. Es B.5.

### 8.4 El resto de tu GitHub

**No lo pude revisar.** La API de GitHub está restringida en este entorno a los repos
configurados para la sesión, y `github.com/CHKI541?tab=repositories` está bloqueado por
`robots.txt` para el buscador de páginas. **Decime los nombres de los repos** (o abrí la lista y
pegámela) y los reviso uno por uno con el mismo procedimiento de §8.1: nombres de archivo
sensibles en HEAD y en todo el historial, más búsqueda de claves por patrón.

Mientras tanto, el chequeo que podés hacer vos en dos minutos por cada repo público:
```bash
git log --all --pretty=format: --name-only --diff-filter=A | sort -u | \
  grep -Ei '(google-services\.json|\.jks$|\.keystore$|local\.properties|service.?account|\.p12$|\.pem$|\.env|firebase-adminsdk|id_rsa)'
```
Si eso devuelve algo, **el archivo sigue en el historial aunque lo hayas borrado**, y sacarlo
requiere reescribir la historia (`git filter-repo`) **y rotar la credencial** — borrarla del HEAD
no alcanza.

---

## 9. Verificación hecha y lo que NO se verificó

**Hecho:**
- `kotlinc` 2.0.21 contra stubs escritos a mano de `SharedPreferences`, Firebase RTDB/Auth y
  `org.json`: **0 errores / 0 warnings** sobre todo el código nuevo.
- **Cuatro controles negativos**, todos detectados (2, 9, 1 y 1 errores respectivamente): método
  inexistente, tipo equivocado, rama de `when` con tipo malo, referencia inexistente. Sin esto un
  "0 errores" no significa nada.
- Parseo con `kotlinc` de los 9 archivos `.kt` modificados completos: **cero errores de sintaxis**
  (el resto son referencias no resueltas por falta del SDK de Android, que es lo esperado).
- `node --check` sobre `app.js` e `index.js`.
- Paridad de comandos medida con un script: `ALLOWED_COMMANDS` 106, la app maneja 105, el panel
  referencia 105. **La única diferencia es `VERIFY_PIN`, y es correcta** (la resuelve la Function,
  nunca llega al celular). **No hay ningún comando que la app entienda y el panel no pueda
  mandar, ni al revés.**

**NO hecho:** Gradle, APK, despliegue, y **ninguna prueba en equipo real**.

---

## 10. Mensaje de commit

```
fix(comandos/nombre/presets/bateria): destrabar el canal FCM, dejar de pisar el
nombre del panel, arreglar la firma de los perfiles y sacar 8.640 arranques de
servicio por dia

CANAL DE COMANDOS (era "no lo puedo controlar de la web" + "hay que cambiar el
PIN varias veces"):
- syncPinCredentials() mandaba {pinHash, pinSalt, commandSecret} en un solo
  updateChildren, que es atomico: como la regla de commandSecret prohibe al
  equipo cambiar un valor ya existente (y el secreto se pierde al reinstalar la
  app, mientras el ANDROID_ID sobrevive), el rechazo de esa ruta se llevaba
  puesto el PIN. Ahora son dos escrituras separadas.
- onMessageReceived descartaba los comandos invalidos sin dejar rastro. Ahora
  cada rechazo escribe commandAcks/<id> con motivo (BAD_SIGNATURE, REPLAY,
  TIMESTAMP_OUT_OF_WINDOW, ...).
- La ventana de 5 min se comparaba contra el reloj de pared del celular; con la
  hora mal se rechazaban TODOS los comandos para siempre. Ahora: ventana de 24 h
  solo si el reloj es creible, mas un piso monotonico de timestamps que no
  depende del reloj. El anti-repeticion no se debilita (commandId + isReplay +
  piso monotonico).
- pushCommandSecret() reporta el desajuste en devices/<id>/commandSecretMismatch
  y el panel ofrece "Re-vincular" (borra el secreto del servidor; el equipo lo
  reescribe con !data.exists()). No requiere tocar las reglas.

NOMBRE DE DISPOSITIVO: syncDeviceInfo() mandaba deviceName desde las preferencias
locales en cada sincronizacion, o sea que pisaba con "" el nombre puesto desde el
panel a los pocos segundos. Ahora manda el panel: reconcileDeviceName() adopta el
remoto y solo sube el local si Firebase no tiene ninguno. pushDeviceName() para el
boton del Dashboard del celular.

PRESETS: Realtime Database no guarda arrays vacios, asi que perAppInternetBlocked
desaparecia despues de firmar y la firma HMAC no coincidia NUNCA -- practicamente
ningun perfil creado desde el panel se podia aplicar. El panel ahora borra la
clave antes de firmar y la app tolera el array perdido para los perfiles ya
guardados. Ademas la clave "no_apps_control" no existe en Android (es
"no_control_apps"): esa restriccion no se aplico nunca. Mas cobertura de perfil
(bluetooth, locale, mobile networks, date time, network reset y los interruptores
de accesibilidad/boot gate), importando con "valor actual" por omision para que un
perfil viejo no apague protecciones que no nombra.

EN LINEA: el latido iba en un Handler, que no cuenta en sueno profundo. El
WatchdogWorker (unico mecanismo que sobrevive a que muera el proceso) ahora late
primero y barato; ademas se late al recibir cualquier FCM. El panel pasa a 20 min
de ventana y suma el estado "En reposo" en vez de mentir "Desconectado".

BATERIA: el ciclo de 20 s hacia startForegroundService() de la VPN y de la marca
de agua SIEMPRE, aunque ya estuvieran corriendo: 8.640 transacciones Binder por
dia tiradas. Ahora se consulta un espejo en memoria y se fuerza el reintento cada
5 min. Si el proceso muere, el espejo vuelve a false solo, asi que el caso comun
se sigue detectando al instante.

Sin compilar en Gradle y sin probar en equipo real. Type-check con kotlinc 2.0.21
contra stubs: 0 errores / 0 warnings, con 4 controles negativos detectados.
```

---

# SEGUNDA PARTE (misma fecha) — se aplicaron TODAS las recomendaciones y se copió más de A Bloq

El dueño pidió, textual: *"Todos los cambios que recomiendas hacelos ahora"*, *"no hay mas funciones
de abloq por copiar? como el modo kiosco que en abloq esta mejor"*, y *"quizas mejor ver tambien los
codigos y compararlos, para ver que los mios no tengan bugs"*. Después sumó el pedido del
**modo teléfono de teclas** estilo KeyLauncher con opción de apagar el táctil e íconos de Nokia.

## 11. Qué se agregó (resumen)

| Qué | Dónde | Detalle |
|---|---|---|
| Registro declarativo de políticas + **20 restricciones nuevas** | `mdm/PolicySpec.kt` (nuevo) | B.33 |
| Verificación de **estado real** vs preferencia | `PolicyManager.divergentRestrictions()` | B.34 |
| **Kiosco real del SO** (Lock Task) | `PolicyManager` + `KosherLauncherActivity` | B.35 |
| **Modo teléfono de teclas** + apagar táctil + íconos propios | `NokiaKeypadScreen.kt`, `NokiaIconSet.kt` (nuevos) | B.36 |
| **Verificación de firma de APK** | `ApkSignatureVerifier.kt` (nuevo) + `ApkInstaller` | B.37 |
| Las 3 optimizaciones de batería que faltaban | `WatchdogForegroundService`, `WatchdogWorker` | B.30 |
| Limpieza del repo | `.gitignore` | B.32 |

**El panel pasó de 65 a 107 interruptores y de 106 a 152 comandos.**

## 12. ⚠️ Tres cosas que hay que hacer sí o sí al desplegar

1. **`firebase deploy --only hosting,functions`.** Esta vez `functions` NO es opcional:
   `ALLOWED_COMMANDS` pasó de 106 a 152 entradas. Si desplegás solo `hosting`, cada interruptor
   nuevo del panel va a responder **"Comando no reconocido"**. (Seguí usando `deploy_all.ps1`, que
   ya separa `hosting,database` de `functions` en un try/catch aparte.)
2. **Agregar los cuatro archivos Kotlin nuevos al repo.** `PolicySpec.kt`,
   `ApkSignatureVerifier.kt`, `NokiaIconSet.kt`, `NokiaKeypadScreen.kt`. Un `git add -A` los toma;
   un `git add` de archivos sueltos no.
3. **Sacar del repo lo que ya estaba rastreado.** El `.gitignore` nuevo no desrastrea nada que ya
   esté en git:
   ```powershell
   git rm -r --cached scratch/
   git rm --cached zizR3CfM "Claude web 1.zip"
   ```

## 13. El chequeo de simetría — conservalo, encontró un bug real

Este script compara las cuatro listas de restricciones de `PolicyManager` (aplicar / suspender /
purgar / perfil). Encontró **B.38** en segundos: `DISALLOW_CONFIG_DATE_TIME` se levantaba y se
limpiaba pero no se volvía a aplicar tras un reinicio — el mismo defecto que S-3 y P-3, que en su
momento costaron una sesión de diagnóstico cada uno. **Corrélo cada vez que toques restricciones.**

```python
import re
s = open("app/src/main/java/com/ejemplo/locksuite/mdm/PolicyManager.kt", encoding="utf-8").read()
def bloque(inicio):
    i = s.index(inicio); j = s.index("listOf(", i); depth = 0; k = j + 6
    while True:
        if s[k] == "(": depth += 1
        elif s[k] == ")":
            depth -= 1
            if depth == 0: break
        k += 1
    txt = s[j:k]
    keys = set(re.findall(r'UserManager\.(DISALLOW_[A-Z_]+)', txt))
    keys |= set(m.replace("no_", "DISALLOW_").upper() for m in re.findall(r'"(no_[a-z_]+)"', txt))
    return keys
r = bloque("fun reapplyAllRestrictions()"); l = bloque("private fun liftAllForSuspension()")
c = bloque("fun clearAllRestrictions()");   e = bloque("fun exportPolicyPresetJson(")
print("se aplican pero NO se levantan al suspender:", sorted(r - l) or "OK")
print("se aplican pero NO se limpian al purgar   :", sorted(r - c) or "OK")
print("se aplican pero NO estan en el perfil      :", sorted(r - e) or "OK")
print("estan en el perfil pero NO se reaplican    :", sorted(e - r) or "OK")
```

Al cierre de esta sesión las cuatro comparaciones dan **OK**.

## 14. Orden de prueba de lo nuevo

1. **DNS privado** (lo más valioso): encender "Bloquear DNS privado" y confirmar en Ajustes → Red →
   DNS privado que queda deshabilitado. Con eso, el Watchdog deja de reimponerlo cada minuto.
2. **Aviso de divergencia:** encender tres o cuatro restricciones y mirar si aparece el aviso ámbar.
   Si aparece con restricciones que el equipo sí soporta, es un hallazgo real: ese fabricante las
   está ignorando.
3. **Kiosco (Lock Task):** ⚠️ **primero agregá el marcador telefónico a la lista de apps
   permitidas.** Después encenderlo y confirmar que el botón de inicio vuelve al launcher, que
   recientes no abre nada, y que una app fuera de la lista no se puede abrir. **Probalo en un equipo
   de descarte antes que en el tuyo.**
4. **Modo teléfono de teclas:** encenderlo y navegar con la cruceta; probar los dígitos 1-9;
   confirmar que Llamadas/Contactos/Mensajes salen con ícono y nombre por función.
5. **Apagar el táctil:** confirmar que el launcher deja de responder al dedo y que **mantener
   3 segundos en la esquina superior derecha abre la pantalla de administrador**. Probá esa salida
   ANTES de necesitarla.
6. **Firma de APK:** actualizar una app de la Tienda administrada y confirmar que sigue instalando
   (no debería cambiar nada); el camino de rechazo solo se ve con un APK firmado por otro.

## 15. Lo que NO se hizo, y por qué (esto responde al "hacelos todos")

- **BCrypt para el PIN (B.3-b).** El hash se calcula en **tres** lugares que tienen que coincidir
  exactamente (`PinManager.kt`, `hashPin` en `functions/index.js`, `hashPinLocal` en `app.js`) y hay
  PINs en producción. Hacerlo mal, sin poder compilar ni desplegar, **te deja sin poder entrar a tus
  propios equipos**. Necesita: soportar los dos algoritmos a la vez, migrar al primer ingreso
  correcto, y desplegar app + panel + Function coordinados. Es una sesión con terminal.
- **Firma asimétrica de presets (B.5).** Hay que generar el par de claves y poner la privada en una
  Cloud Function. Sin `firebase deploy` no se cierra el círculo, y dejar la app verificando contra
  una clave pública cuyo par no está desplegado **rompe todos los presets existentes**.
- **Segunda VPN como corta-internet real (B.31 punto d).** Es la mejora de arquitectura más grande
  que sale de la comparación y sigue siendo la recomendación. No entró en esta sesión porque es un
  `VpnService` nuevo + Manifest + interacción con `BootGate` y con la VPN actual: demasiado para
  entregar sin una sola compilación.

## 16. Verificación de esta segunda parte

- **Prueba de comportamiento, no solo de tipos:** se compiló y **ejecutó** la lógica del teclado y
  del set de íconos con 9 aserciones (`kotlinc` → `java -jar`). Las 9 pasan: el dígito respeta la
  página actual, la cruceta no envuelve de página, y Contactos/Llamadas salen por función y no por
  nombre de app.
- **Type-check con stubs** del registro de políticas, el despacho de comandos, el kiosco y el
  cálculo del intervalo de DNS: **0 errores / 0 warnings**, con **4 controles negativos** detectados
  (campo inexistente, tipo mal en una rama de `when`, constante de Lock Task inventada, método
  inexistente del DPM).
- **Parseo con `kotlinc` de los 17 archivos `.kt`** tocados o nuevos: **0 errores de sintaxis**.
- **Paridad medida por script:** 152 comandos permitidos, 151 manejados por la app, 151 ofrecidos
  por el panel; 107 interruptores en el HTML, **todos** con comando en `app.js`. La única diferencia
  es `VERIFY_PIN`, y es correcta.
- `node --check` limpio sobre `app.js` e `index.js`.
- **NO se corrió Gradle y NO se probó nada en equipo real.**

*(Detalle menor anotado: `mercadoPagoBlockOffers` sigue en el mapa de `app.js` sin fila en el HTML.
Es legado — lo reemplazaron `…Accessibility` y `…Vpn`. Inofensivo, candidato a borrar.)*
