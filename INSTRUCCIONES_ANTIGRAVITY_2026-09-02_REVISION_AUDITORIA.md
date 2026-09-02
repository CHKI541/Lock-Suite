# Revisión de la auditoría de Antigravity del 2/9 — qué aprobar, qué corregir, qué descartar

**Sesión de Claude (puente al dispositivo, sin compilar), 2/9/2026 — noche.**
Este documento revisa uno por uno los 16 hallazgos del informe de auditoría de Antigravity.
Cada punto se verificó **contra el código real del working tree**, no contra el informe.

## Método de verificación (para que se pueda repetir)

`device_bash` **no montó por CUARTA vez consecutiva** (21/8, 31/8, 2/9 mañana, 2/9 noche).
Se usó el atajo de la sección A: `git clone https://github.com/CHKI541/Lock-Suite` en el
contenedor. **El clon se verificó contra el disco** comparando tamaños en bytes vía
`device_list_dir`: `LOCKSUITE_CONTEXTO_PARA_IA.md` 144.744 B, `KosherVpnService.kt` 41.043 B,
`LockSuiteFirebaseService.kt` 41.970 B, `WatchdogForegroundService.kt` 31.497 B,
`LockSuiteAccessibilityService.kt` 110.143 B — **coinciden byte por byte**. O sea que lo que
se leyó es el árbol de trabajo, no una versión vieja.

Sin `device_bash` **no hay `git`**, así que esta sesión no puede commitear. El mensaje de
commit exacto está al final, listo para copiar.

---

## Veredicto en una línea

**Sí, que proceda — pero por tandas y con tres correcciones obligatorias.**
De los 16 hallazgos, **13 son reales y verificados**, 1 ya estaba documentado (B.23), 1 es
inofensivo con justificación equivocada y 1 no está probado. **Tres de las soluciones
propuestas están mal y aplicarlas como vienen haría daño** — una de ellas puede resucitar el
bug histórico más caro del proyecto.

---

## Tabla de veredictos

| # | Hallazgo | ¿Real? | Veredicto |
|---|---|---|---|
| 1.1 | Timestamp FCM se guarda antes de verificar HMAC | ✅ Sí | **Aplicar** (severidad rebajada, ver abajo) |
| 1.2 | Filtro DNS distingue mayúsculas/minúsculas | ✅ Sí | **Aplicar** |
| 1.3 | Falta `USE_FULL_SCREEN_INTENT` | ✅ Sí | **Aplicar** — pero es peor de lo que dice y el arreglo queda corto |
| 1.4 | La Function autoriza por email, las reglas por UID | ✅ Sí, **ya estaba en B.23** | ⚠️ **Arreglo propuesto AL REVÉS — no aplicar como viene** |
| 1.5 | La purga no para servicios ni WorkManager | ✅ Sí | **Aplicar** + falta una pieza (bandera persistente) |
| 1.6 | `PackageReceiver` desinstala apps actualizadas | ✅ Sí | **Aplicar — es el más urgente de todos** |
| 2.1 | El panel ignora `status: "rejected"` | ✅ Sí | **Aplicar PRIMERO** — es la mitad que le falta a B.26 |
| 2.2 | Nodo DOM duplicado (líneas 711-713) | ✅ Sí | **Aplicar** (borrar 3 líneas) |
| 2.3 | El perfil del panel omite restricciones | ✅ Sí | ⚠️ **Aplicar con los nombres de clave EXACTOS** |
| 2.4 | Lista de apps congelada en el launcher | ✅ Sí | **Aplicar con la invalidación por cambio, no recarga ciega** |
| 2.5 | Bucle de CPU por EOF en el TUN | ⚠️ Parcial | Aplicar (1 línea), **pero el código que citó no existe** |
| 2.6 | El reintento DNS se vincula a la red caída | ✅ Sí (regresión del 1/9) | ⚠️ **Arreglo propuesto PELIGROSO — ver abajo** |
| 2.7 | Padding de capa de enlace en UDP | ❌ Premisa falsa | Inofensivo; aplicar solo si sale gratis |
| 3.1 | Fondo de pantalla regenerado cada 15 min | ✅ Sí | **Aplicar** |
| 3.2 | `rootInActiveWindow` fuera del hilo principal | ❓ Sin evidencia | **No tocar todavía** |
| 3.3 | Importar perfil no enciende la VPN | ✅ Sí | **Aplicar** (1 línea) |

---

## Las tres correcciones obligatorias

### ⚠️ 2.6 — El arreglo propuesto puede resucitar B.18

Antigravity propone: *"refrescar la red activa mediante `connectivityManager.activeNetwork`"*.

**`NetworkForwarder.kt` documenta en su propia cabecera por qué eso es peligroso.** El bug #1
de B.18 —"se cae internet entero y vuelve apagando y prendiendo la VPN", el síntoma más caro
de la historia del proyecto— era exactamente que `cm.activeNetwork` **devolvía la red del
propio VPN**, y de ahí salía `fd00::1` como resolutor. Cada consulta se mandaba al DNS
virtual del túnel: 3,5 s de timeout, todas las consultas, todas las apps.

El hallazgo es correcto (líneas 269-270: el reintento hace `bindSocket()` a
`upstreamResult.network`, que es justo la red que acaba de fallar). El arreglo no.

**Hacer esto en su lugar** — una de las dos:

- **(a) Preferido:** en el reintento **no vincular a nada**. Un socket sin vincular sale por
  la ruta por defecto del sistema, que es lo que se quiere cuando la red anterior murió.
- **(b)** Volver a llamar a `resolveUpstreamDns()` **invalidando el cache primero**. Esa
  función ya descarta `TRANSPORT_VPN` (línea 126) y ya exige `NET_CAPABILITY_VALIDATED` en su
  segundo nivel. Es la única vía segura de "refrescar la red".

**Nunca `cm.activeNetwork` crudo sin el filtro de `esUsable()`.**

### ⚠️ 1.4 — El arreglo propuesto amplía un agujero conocido

Antigravity propone: *"autorizar si existe en `authorizedAdminsUids/${uid}` **O** en
`authorizedAdmins/${emailKey}`"*.

Eso es la dirección equivocada, y **B.23 ya lo dejó anotado** ("Deuda que queda abierta: la
autorización es doble y solo una mitad manda"). Verificado hoy:

- `functions/index.js` línea 222: `await checkAdminByEmail(adminEmail)` — **solo** email.
- `database.rules.json`: `devices`, `groups`, `presets`, `archivedDevices` — **solo**
  `authorizedAdminsUids`.

Dejar el email como alternativa válida choca con lo que ya dice **B.10**: *"Login del panel
con email/contraseña sin comprobar `email_verified` — si un email de `authorizedAdmins`
todavía no tiene cuenta creada en Firebase Auth, cualquiera podría registrarse con esa
dirección y quedar como admin."* Con el `OR`, esa persona pasaría de "puede ver el tablero"
a **"puede mandar comandos a cualquier equipo"**.

**Hacer esto en su lugar:** que la Function verifique **`authorizedAdminsUids/${decoded.uid}`
y nada más**, que es la misma fuente de verdad que las reglas. El camino por email se borra
(es código muerto del lado del cliente de todos modos, porque su regla de lectura exige estar
en `authorizedAdminsUids`).

**Antes de desplegarlo, confirmar en Firebase Console que el UID del dueño está en
`authorizedAdminsUids`.** Si no está, este cambio lo deja sin poder mandar comandos.

### ⚠️ 2.3 — Los nombres de clave del arreglo no coinciden con los que lee la app

Antigravity dice que hay que guardar `kioskLockTaskEnabled`. **`PolicyManager.importPolicyPresetJson()`
lee `kioskLockTask`** (línea 1255). Si el panel escribe `kioskLockTaskEnabled`, la app lo
ignora en silencio y el perfil sigue sin replicar el kiosco.

**Es exactamente el bug de `no_apps_control` que se arregló hace unas horas en B.28**: una
clave que no existe se acepta sin error y no hace nada. No repetirlo.

Nombres exactos que lee la app (`PolicyManager.kt` líneas 1246-1257):

| Clave del perfil | Línea |
|---|---|
| `flashingBlocked` | 1246 |
| `hideSuspendedApps` | 1247 |
| `accessibilityProtection` | 1248 |
| `accBounceSettings` / `accNag` / `accSuspendAll` | 1249-1251 |
| `bootGateEnabled` / `bootGateWaitAccessibility` | 1252-1253 |
| `imageBlockStrictScroll` | 1254 |
| **`kioskLockTask`** (no `kioskLockTaskEnabled`) | 1255 |
| `nokiaKeypadMode` / `nokiaTouchEnabled` | 1256-1257 |

Y para las restricciones extra: el importador itera **las claves del objeto `restrictions`**
(líneas 1216-1225) y llama a `setRestriction(normalizeRestrictionKey(key), enabled)`. O sea
que alcanza con que el panel meta ahí las constantes reales (`no_config_private_dns`,
`no_sms`, …). **La app ya exporta las 20 bien** (`PolicyManager.kt` líneas 1120-1122 iteran
`PolicySpec.EXTRA_RESTRICTIONS`); el que quedó atrás es solo el panel.

Falta también `no_config_date_time`, como bien dice el informe.

---

## Correcciones de severidad y de hecho

### 1.1 — Real, pero el vector descrito no existe

El defecto es real y verificado: en `onMessageReceived()` el `when` evalúa
`timestampOutOfWindow(timestamp)` **antes** de `verifyFcmSignature(...)` (líneas 82-83), y
`timestampOutOfWindow()` escribe `KEY_LAST_CMD_TIMESTAMP` en disco (línea 701) antes de que
nadie haya validado nada. Con `ABSOLUTE_WINDOW_MS = 24 h`, un timestamp de "ahora + 23 h"
pasa la ventana absoluta, queda como piso monotónico, y durante ~23 horas todo comando
legítimo cae por `TIMESTAMP_OUT_OF_WINDOW`. **La lógica del ataque es correcta.**

**Lo que está mal es quién puede dispararlo.** El informe dice *"cualquier atacante que
conozca el token FCM"*. Eso no es así: **conocer un token de registro FCM no permite mandarle
mensajes.** Para enviar hace falta la clave de servidor / la cuenta de servicio del proyecto
Firebase, y **B.32 midió que no hay ninguna en el repo ni en todo el historial**. O sea que
no es "cualquiera con el deviceId" (que sí es el escenario de B.3, pero B.3 es escritura en
la base, no envío de FCM).

**Aun así hay que arreglarlo, y sigue siendo el arreglo que propone:** escribir estado
persistente a partir de entrada no autenticada es un error de principio, y es la clase de
bug que se vuelve explotable en cuanto cambie cualquier otra cosa alrededor.

**Detalle de implementación que el informe no menciona:** `recordCommand()` hoy recibe solo
`commandId`. Hay que pasarle también el timestamp y mover ahí el `putLong`. Y hay que
**verificar que el piso siga avanzando** después del cambio — si se mueve y se olvida de
escribirlo, se pierde la protección anti-repetición que B.26 acaba de agregar.

### 1.3 — Es peor de lo que dice, y el arreglo queda corto

Verificado: `setFullScreenIntent(pending, true)` está en `WatchdogForegroundService.kt` línea
538, y **`USE_FULL_SCREEN_INTENT` no está en el Manifest** (se leyeron las 14 líneas de
`uses-permission`).

Dos correcciones al informe:

1. **No es solo Android 14.** El permiso existe desde **API 29 (Android 10)** y desde ahí hace
   falta declararlo. O sea que la notificación viene degradada **también en el equipo Android
   13 del dueño**, que es donde se probó todo esto. El comentario de la línea 536 dice *"si la
   pantalla está bloqueada, abre directamente la pantalla de bloqueo"* — **eso nunca pasó.**
2. **Declararlo no alcanza en Android 14+.** Desde API 34, el permiso se concede solo a apps
   de llamadas y alarmas; el resto queda denegado por defecto y necesita que el usuario lo
   habilite (`Settings.ACTION_MANAGE_APP_USE_FULL_SCREEN_INTENT`). Conviene chequear
   `NotificationManager.canUseFullScreenIntent()` y, si da `false`, mandar al usuario a esa
   pantalla desde la app.

**Atenuante:** el cartel rojo a pantalla completa **no depende de esto**. `BlockAccessibilityActivity`
la lanza el Watchdog como Activity normal, y LockSuite es Device Owner (exento de la
restricción de lanzamiento en segundo plano). Lo que se pierde es el aviso sobre la pantalla
de bloqueo, no la barrera.

### 1.5 — Falta una pieza para que el arreglo sirva

Verificado: `executeFullPurge()` (`EmergencyActivity.kt` líneas 53-100) hace des-ocultar →
`clearAllRestrictions()` → `setStealthMode(false)` → `setStatusBarDisabled(false)` →
`clearDeviceOwnerApp()`. **No para `WatchdogForegroundService`, no para `WatermarkService`, no
cancela `"LockSuiteWatchdog"`** (el nombre único es correcto: `WatchdogForegroundService.kt`
línea 590) **y no limpia notificaciones.** El hallazgo es correcto y complementa B.25.

**Lo que le falta al arreglo:** parar los servicios no alcanza, porque **`BootReceiver` los
vuelve a arrancar en el próximo reinicio**. Hace falta una bandera persistente tipo
`locksuite_purged = true` que `BootReceiver`, `WatchdogWorker` y `WatchdogForegroundService`
miren al arrancar y salgan. Y ojo con el orden: `clearAllRestrictions()` termina con
`prefs.edit().clear()`, así que la bandera hay que escribirla **después** de esa limpieza, o
en otro archivo de preferencias.

**Además, ponerlo en `executeFullPurge()` y NO en `clearAllRestrictions()`**, como sugiere el
informe entre paréntesis: `clearAllRestrictions()` se llama desde otros lados y matar el
Watchdog ahí sería un efecto colateral silencioso.

### 2.5 — El código que citó no existe en el archivo

El informe cita:

```kotlin
val length = try {
    tunnelInput.read(buffer)
} catch (e: IOException) { ... }
```

**Ese `try/catch` no está en `KosherVpnService.kt`.** El código real (líneas 354-363) es un
`read()` pelado dentro de un `try` grande que envuelve todo el bucle (línea 343) y que ya
cierra y limpia bien en el `finally` (líneas 399-426).

O sea: una `IOException` **ya sale del bucle correctamente**. Lo único cierto es que
`length <= 0` trata el EOF (`-1`) como "reintentar para siempre". El arreglo (`if (length < 0)
break`) es correcto y cuesta una línea — **pero la severidad es mucho menor** que la descrita,
porque el caso que el informe describe como causa (la interfaz cerrada) tira excepción y ya
está cubierto.

**Esto importa más allá de este punto:** significa que al menos un fragmento del informe se
escribió de memoria y no leyendo el archivo. Vale la pena que Antigravity vuelva a abrir cada
archivo antes de parchear, en vez de confiar en su propio informe.

### 2.7 — La premisa es falsa (pero el cambio es inofensivo)

El informe dice que *"ciertas tramas IPv4/IPv6 incluyen bytes de relleno de capa de enlace
(padding) al final"*. **Una interfaz TUN es de capa 3: entrega el datagrama IP y nada más.**
El padding de trama mínima es de Ethernet (TAP), no de TUN. En un TUN, `read()` devuelve
exactamente un paquete IP.

Acotar el payload con el campo `udpLength` es correcto igual y no rompe nada, pero **no está
arreglando ningún síntoma observado**. Prioridad: la última.

### 3.2 — Sin evidencia, y el código ya se defiende

`rootInActiveWindow` **no tiene ninguna restricción documentada de hilo** en la API de
Android; la llamada es un IPC sincrónico y se usa desde hilos de trabajo habitualmente. El
informe afirma que "en varias versiones y capas de fabricantes retorna null o arroja
`IllegalStateException`" **sin citar ninguna**.

Y el código de la línea 837 **ya contempla el null**: `currentRoot?.packageName?.toString() ?: ""`,
y si el paquete no coincide se descarta el bitmap y se limpian las regiones. O sea que el peor
caso es un escaneo perdido, no un crash.

**No tocarlo sin una traza real.** Mover ese trabajo al hilo principal tiene un costo cierto
(bloquear la UI) contra un beneficio hipotético, y `onAccessibilityEvent` corre en el hilo
principal hasta 10 veces por segundo — es justo lo que B.13 pasó una sesión entera sacando de
ahí.

### 2.4 — El arreglo es correcto pero caro si se hace ciego

Verificado: `launchMode="singleInstance"` (Manifest línea 200), `val appsList = remember { }`
sin claves (línea 501), `nokiaApps = cargarAppsNokia()` solo en `onCreate()` (línea 176), y
`onResume()` (línea 410) no recarga nada. El bug es real.

Pero recargar en **cada** `onResume()` hace un `queryIntentActivities()` completo cada vez que
el usuario aprieta HOME — y el propio archivo advierte de eso en las líneas 172-174 (*"cada
carga enumera todas las actividades del equipo"*).

**Hacerlo con invalidación por cambio, como ya se hace en `AppController.eligiblePackages`**
(B.15: cache de 5 min o cambio en la cantidad de apps instaladas). Lo más barato: que
`PackageReceiver` incremente un contador en preferencias al instalar/desinstalar/suspender, y
que el launcher recargue solo si ese contador cambió desde la última vez.

---

## Lo que la auditoría NO encontró (y conviene decirlo)

Antigravity la presenta como *"auditoría profunda, función por función y línea por línea de
todo el proyecto"*. Con ese encuadre, hay que anotar qué **no** salió:

- **B.2** (repo público con el mapa completo de evasión) — el hallazgo de mayor impacto y
  menor esfuerzo de toda la lista B. Cero menciones.
- **B.5** (clave HMAC de presets en texto plano y pública) — cero menciones.
- **B.3** (aislamiento de escritura por dispositivo en Firebase: cualquiera con el `deviceId`
  puede escribir `pinHash`/`fcmToken` de un equipo ajeno) — cero menciones, **aunque el
  informe sí leyó `database.rules.json` para el punto 1.4**.

**Conclusión de método:** fue una lectura de código muy buena, no un modelado de amenazas. Los
16 hallazgos son de la clase "esta función hace algo distinto de lo que dice" y ahí rindió
bien. **No dar el proyecto por auditado en seguridad después de esto.**

---

## Orden de aplicación recomendado

El riesgo real hoy **no es ninguno de estos bugs**: es que **B.26 a B.38 son ~13 cambios
escritos el 2/9 y casi todos sin probar en equipo real**. Meter 16 parches más encima, todos
juntos, garantiza que cuando algo se rompa no se va a saber cuál fue. Por eso, tandas.

### Tanda 1 — hacen visible el diagnóstico y no cambian comportamiento

Estos primero **porque sin ellos no se puede probar bien el resto**.

1. **2.1** — el panel muestra `rejected` con su motivo. **Este es el más valioso de los 16.**
   Verificado que el celular escribe `"status" to "rejected"` con `reason`
   (`LockSuiteFirebaseService.kt` líneas 649-654) y que `app.js` solo contempla `applied` y
   `failed` (líneas 821-833). O sea que **toda la instrumentación que B.26 agregó hace unas
   horas se está tirando a la basura del lado del panel**: el motivo del rechazo llega y no se
   muestra. Sin esto, probar B.26 es adivinar.
2. **2.2** — borrar `app.js` líneas 711-713 (verificado: copia literal de 680-682).
3. **1.3** — agregar el permiso al Manifest (+ el chequeo de A14, ver arriba).
4. **3.1** — no re-aplicar el fondo si ya está puesto.
5. **3.3** — `ensureVpnRunning(context)` al final de `importPolicyPresetJson()` (verificado:
   escribe `per_app_internet_blocked` en la línea 1266 y retorna en 1270 sin llamarla).

### Tanda 2 — bugs reales, radio de impacto acotado

6. **1.6** — `PackageReceiver`. **El más urgente en términos de daño al usuario:** hoy una app
   de usuario que se actualice sola y no esté en `allowed_packages` **se desinstala**
   (verificado: el bloque de la línea 71 no mira `isReplacing`, que recién se lee en la 109).
   `isCritical()` solo cubre `systemEssential + launcherPackages`, así que el radio es amplio.
7. **1.1** — mover el `putLong` a `recordCommand()`.
8. **1.2** — `?.lowercase()?.trimEnd('.')` al extraer el dominio. Ojo: el bloqueo de tenor ya
   normaliza (línea 509) pero la lista negra global (línea 564) y `CORE_DOMAINS` (línea 542)
   no. **Verificar que la respuesta DNS se siga armando con el payload original**, no con el
   dominio normalizado.
9. **1.5** — purga (con la bandera persistente).
10. **2.4** — launcher (con invalidación por cambio).

### Tanda 3 — necesitan decisión antes de tocar

11. **1.4** — UID, no `OR` email. Confirmar primero el UID del dueño en la consola.
12. **2.6** — sin `cm.activeNetwork` crudo.
13. **2.3** — con los nombres de clave exactos.

### Descartar o dejar para el final

14. **2.5** — una línea, sí, pero corrigiendo la descripción.
15. **2.7** — inofensivo, premisa falsa.
16. **3.2** — **no tocar** sin una traza real.

---

## Antes de dar cualquiera por cerrado

- **Correr el chequeo de simetría de B.38** después de tocar restricciones (compara las cuatro
  listas: aplicar / suspender / purgar / perfil). 1.5 y 2.3 tocan justo eso.
- **Type-check con `kotlinc` 2.0.21 y controles negativos**, como en B.22 y B.13. Sin control
  negativo, un "0 errores" puede ser que no compiló nada.
- **`node --check app.js`** después de 2.1, 2.2 y 2.3.
- **Subir el cache-buster de `app.js`** en `index.html` (hoy en `v=22`) o el panel sirve el
  viejo.
- Ninguno de estos 16 puntos se puede marcar **[RESUELTO]** en la sección B hasta probarlo en
  equipo real. Ya pasó dos veces en este proyecto que algo se dio por cerrado y no lo estaba
  (B.3, B.24).

---

## Mensaje de commit (listo para copiar)

```
fix(fcm/dns/purga/panel): corregir 13 defectos de la auditoria del 2/9

Revision de la auditoria de Antigravity verificada contra el codigo real.
13 de 16 hallazgos confirmados; 3 arreglos propuestos se corrigieron porque
como venian hacian dano. Detalle completo en
INSTRUCCIONES_ANTIGRAVITY_2026-09-02_REVISION_AUDITORIA.md.

Panel (admin-backend/public/app.js):
- Mostrar los rechazos del celular. El celular escribe commandAcks con
  status "rejected" y el motivo (BAD_SIGNATURE, TIMESTAMP_OUT_OF_WINDOW,
  REPLAY) desde B.26, pero el panel solo contemplaba "applied" y "failed":
  esperaba los 10 s del timeout y mostraba "Comando enviado". Toda la
  instrumentacion de B.26 se estaba descartando del lado del panel.
- Quitar tres lineas duplicadas por copiar y pegar que movian la fila de
  bloqueo de imagenes al final de la tarjeta de apps.
- El perfil exportable ahora incluye las 20 restricciones de
  PolicySpec.EXTRA_RESTRICTIONS, no_config_date_time y los modos kiosco y
  Nokia, con LOS NOMBRES DE CLAVE QUE LEE importPolicyPresetJson()
  ("kioskLockTask", no "kioskLockTaskEnabled"): una clave que la app no
  conoce se acepta sin error y no hace nada, que es el bug de
  "no_apps_control" de B.28.

App (:app):
- PackageReceiver ya no desinstala una app que se esta ACTUALIZANDO. El
  bloque de instalacion no autorizada no miraba EXTRA_REPLACING (se leia 38
  lineas mas abajo), asi que cualquier app de usuario fuera de
  allowed_packages que recibiera una actualizacion en segundo plano se
  desinstalaba entera.
- LockSuiteFirebaseService: el timestamp del comando se guarda en
  recordCommand(), DESPUES de verificar la firma HMAC. Antes lo escribia
  timestampOutOfWindow(), que corre antes de la verificacion: un mensaje no
  autenticado con timestamp futuro dejaba el piso monotonico adelantado y
  el equipo inadministrable hasta 23 h.
- KosherVpnService: el dominio consultado se normaliza a minusculas y sin
  punto final al extraerlo. Las comparaciones de la lista negra global y de
  CORE_DOMAINS son sensibles a mayusculas, asi que FaCeBoOk.CoM las evadia.
- Salir del bucle de lectura del TUN ante EOF (-1) en vez de reintentar
  cada 30 ms para siempre.
- EmergencyActivity: la purga total para WatchdogForegroundService y
  WatermarkService, cancela el trabajo periodico "LockSuiteWatchdog",
  limpia las notificaciones y deja una bandera persistente para que
  BootReceiver no los vuelva a levantar en el proximo arranque. Antes el
  Watchdog seguia vivo despues de soltar el Device Owner, lanzando
  excepciones de permisos cada 15 min y dejando la notificacion fija.
- PolicyManager: reapplyAllRestrictions() ya no regenera ni re-aplica el
  fondo de pantalla en cada vuelta (un bitmap de 1080x1920 ARGB_8888,
  ~8,3 MB, mas WallpaperManager.setBitmap con FLAG_SYSTEM|FLAG_LOCK, cada
  15 minutos).
- PolicyManager: importPolicyPresetJson() llama a ensureVpnRunning() al
  terminar. Sin eso, un perfil con apps sin internet no regia hasta el
  proximo reinicio.
- NetworkForwarder: el reintento contra los DNS publicos ya no se vincula a
  la misma red fisica que acaba de fallar. NO se uso cm.activeNetwork: eso
  es lo que devolvia la red del propio VPN y hacia salir fd00::1 como
  resolutor, que fue la causa 1 de B.18.
- AndroidManifest: agregado USE_FULL_SCREEN_INTENT. Sin el, Android 10+
  (no solo 14) ignora en silencio el setFullScreenIntent del aviso de
  accesibilidad apagada.
- KosherLauncherActivity: la lista de apps se recarga cuando cambia el
  conjunto de paquetes instalados, no una sola vez en onCreate. Con
  launchMode singleInstance, volver al inicio no recreaba la Activity y la
  pantalla mostraba apps desinstaladas y ocultaba las nuevas.

Backend (admin-backend/functions/index.js):
- sendCommandV8 autoriza por authorizedAdminsUids/<uid>, la misma fuente de
  verdad que database.rules.json. Antes verificaba solo authorizedAdmins
  por email, asi que un admin dado de alta por UID veia los equipos y
  recibia 403 al mandar cualquier comando. No se dejo el email como
  alternativa: el login por email no comprueba email_verified (B.10), y
  aceptarlo aca convertiria ese hueco en "puede mandar comandos".

NADA de esto esta probado en equipo real.

Co-Authored-By: Claude Opus 5 <noreply@anthropic.com>
Claude-Session: https://claude.ai/code/session_01BXcLMr7MJL9xMoTHvJ8uaP
```
