# Revisión a fondo del flujo de actualización de apps + dos mejoras (9/9/2026)

Sesión de Claude por el puente al dispositivo. **`device_bash` no montó (décima vez
consecutiva)**, así que se trabajó clonando el repo público en el contenedor y
escribiendo por `device_commit_files`. **Sin `git` no hubo commit: el mensaje está
listo al final, corrilo vos.**

Repo al arrancar: **0.6.47 / código 110** (`413b419`), working tree limpio y
byte-idéntico al clon en los siete archivos tocados (validado contra `device_list_dir`).
Ojo: la sección C del contexto que leí decía "0.6.46 + B.53 sin commitear" — ya estaba
desactualizada, vos habías commiteado y desplegado 0.6.47. Confié en el repo, no en esa
sección.

Resumen técnico en **B.54, B.55 y B.56** de `LOCKSUITE_CONTEXTO_PARA_IA.md`.

---

## 0. Qué se pidió y qué salió

El dueño pidió: revisar a fondo el mecanismo de actualizar apps, que funcione
**siempre y perfecto en cualquier celular y cualquier idioma**, y que muestre
**SIEMPRE** en qué está (actualizando con %, ya está al día, o el error exacto),
**igual desde la pantalla negra del celular y desde el panel web**. Revisar B.41 y
B.42 línea por línea como código ajeno, buscando **casos donde el flujo se queda sin
decir nada**.

**Resultado de la revisión: B.41 y B.42 están muy bien.** Cada camino de `scanAndAct`
o avanza, o cierra con un motivo específico, o fija una etapa y sigue tibieando; la
jerarquía de timeouts es coherente (tick 700 ms < grace 3 s < card-render 9 s <
card-giveup 30 s < stall 75 s < download-stall 150 s < watchdog 600 s); el panel
refleja las mismas etapas por `followUpdateFlow`; los rechazos previos llegan como ACK
`failed` con motivo. **No hay que rehacer nada de eso.**

Pero aparecieron **tres silencios reales** que el diseño no cubría, más una brecha de
idioma. Los tres primeros están **arreglados y type-checkeados**; el idioma y un
detalle de presets quedan como pendientes acotados.

---

## 1. Lo que se ARREGLÓ (código en disco, type-checkeado, SIN compilar ni probar)

### F1 — El watchdog de 10 minutos NO se armaba en Android 12+ (silencio grave). B.54

`UpdateFlowManager.armWatchdog()` usa `setExactAndAllowWhileIdle`, que **desde Android
12 (API 31) exige permiso de alarma exacta**. El Manifest **no declaraba ninguno**, así
que en Android 12/13/14 la llamada tiraba `SecurityException`, caía en el `catch`, y
**la alarma nunca se programaba** — en silencio. Esa alarma es, textual en el código, "la
única garantía de que el equipo no quede desbloqueado" si todo lo demás falla. En el
propio equipo Android 13 del dueño (`QEMAY-QM01`, B.8) el respaldo final estaba apagado.

**Arreglo (dos partes):**
- **Manifest:** se declararon `SCHEDULE_EXACT_ALARM` (API 31-32) y `USE_EXACT_ALARM`
  (API 33+, se auto-concede; LockSuite es sideload como Device Owner, la política de
  Play sobre este permiso no aplica).
- **`armWatchdog`:** ahora verifica `canScheduleExactAlarms()` en caliente (por si un
  OEM lo revoca) y, si no puede exacta, **cae a `setAndAllowWhileIdle`**, que no necesita
  permiso y dispara igual en Doze. Para un tope de seguridad de 10 min, unos minutos de
  imprecisión son tolerables.

### F1b — Si el servicio de accesibilidad se reinicia a mitad de flujo, nadie lo retoma. B.54

`onServiceConnected()` no miraba si había una actualización en curso. Android mata y
recrea los servicios de accesibilidad seguido (más en equipos con poca RAM como el CAT
S22 Flip). Al recrearse, el ticker quedó cortado con el `instance` viejo y **nadie lo
volvía a arrancar**: el flujo seguía marcado "en curso" con Play Store destapada y la
instalación habilitada, **pero sin pantalla negra y sin nadie que lo cerrara**. Peor que
una pantalla trabada: es un estado ABIERTO silencioso. En Android 12+ ni siquiera lo
rescataba el watchdog (F1).

**Arreglo:** al final de `onServiceConnected()`, si `UpdateFlowManager.isRunning()`, se
**redibuja el overlay y se re-arma el ticker** (`startUpdateTicker()`). El flujo retoma y
llega a un cierre con motivo (o a un freno por estancamiento), en vez de quedar colgado.

### F3 — El panel publicaba `lastResultReason`/`freeSpaceMb` pero no los dibujaba. B.54

Era el "pendiente menor" que B.42 §5 ya había anotado. La tarjeta del dispositivo solo
mostraba el flujo con `running === true`; apenas terminaba, **ocultaba todo**. El motivo
del último resultado ("faltan 180 MB") y el espacio libre solo se veían en la línea
efímera del comando al mandarlo. Reabrir el panel no mostraba en qué quedó.

**Arreglo:** tarjeta nueva **"🔄 Última actualización"** (`#update-last-result-card` en
`index.html`) que, con el flujo detenido y habiendo un `lastResult`, muestra el texto de
resultado con su motivo y el espacio libre. Cache-buster de `app.js` a **`v=32`**.

### Mejora 1 — Auto-concesión de permisos peligrosos desde Device Owner. B.55

Pedida por el dueño ("Silent Self-Granting"). Se agregó
`PolicyManager.autoGrantDeclaredPermissions()`, llamada desde `reapplyAllRestrictions()`
(guarda de una vez por proceso). Itera los permisos declarados y hace
`setPermissionGrantState(..., PERMISSION_GRANT_STATE_GRANTED)`.

**Alcance real, importante:** `setPermissionGrantState` **solo** tiene efecto sobre
permisos de **runtime (peligrosos)**. Hoy el único que LockSuite declara es
**`POST_NOTIFICATIONS`** (Android 13+): si el usuario lo niega, se quedan mudos el aviso
de accesibilidad caída (B.15) y las notificaciones del watchdog — un silencio real. Esto
lo cierra y deja el switch "administrado por tu organización".

⚠️ **La descripción original de la mejora estaba parcialmente equivocada:**
`MANAGE_EXTERNAL_STORAGE`, `SYSTEM_ALERT_WINDOW`, `SCHEDULE_EXACT_ALARM` y
`REQUEST_INSTALL_PACKAGES` **NO son permisos de runtime** — son app-ops o normales, y
`setPermissionGrantState` es un no-op sobre ellos. Por eso F1 se arregla declarando el
permiso en el Manifest, **no** por esta vía. La iteración es inofensiva igual: los
ignora solos.

### Archivos tocados

| Archivo | Cambio |
|---|---|
| `app/src/main/AndroidManifest.xml` | + `SCHEDULE_EXACT_ALARM`, `USE_EXACT_ALARM` (F1). **En CRLF, como estaba.** |
| `util/UpdateFlowManager.kt` | `armWatchdog()`: guarda `canScheduleExactAlarms()` + fallback `setAndAllowWhileIdle` (F1). |
| `service/LockSuiteAccessibilityService.kt` | `onServiceConnected()`: re-arma ticker + redibuja overlay si hay flujo en curso (F1b). |
| `mdm/PolicyManager.kt` | `autoGrantDeclaredPermissions()` nueva + llamada en `reapplyAllRestrictions()` + flag en companion (Mejora 1). |
| `admin-backend/public/app.js` | Tarjeta "última actualización" (F3). |
| `admin-backend/public/index.html` | `#update-last-result-card` + cache-buster `v=32` (F3). |

---

## 2. Verificación ya hecha

- **`kotlinc` 2.0.21** contra stubs de la API de Android escritos para esto, con los
  **tres bloques cambiados extraídos** (no transcriptos) a un banco de pruebas: **0
  errores**. **Ocho controles negativos** (método/constante/campo inexistente y aridad
  equivocada de `setPermissionGrantState`, `isDeviceOwnerApp`, `isRunning`,
  `setExactAndAllowWhileIdle`, `canScheduleExactAlarms`, `setAndAllowWhileIdle`) — los
  ocho detectados, o sea que el chequeo estaba vivo.
- **`node --check`** sobre `app.js`: OK, con control negativo detectado.
- **Manifest**: XML well-formed en CRLF; llaves y paréntesis balanceados en los tres `.kt`.
- **NO se corrió Gradle y NO se probó en equipo.**

---

## 3. Orden de prueba en equipo real

Las pruebas de B.41/B.42 siguen vigentes (ver esos puntos). Estas son las NUEVAS,
específicas de esta tanda:

1. **F1 — Android 12+ (idealmente el Android 13 del dueño).** Arrancá una actualización,
   y a mitad **matá el proceso de LockSuite por ADB** (`adb shell am kill
   com.ejemplo.locksuite`) o forzá el cierre. Esperado: a más tardar a los ~10 min la
   pantalla negra se saca sola y el equipo queda re-bloqueado (Play Store re-suspendida,
   instalación re-bloqueada). Antes, en Android 12+, no pasaba nunca.
   - Verificá en `adb logcat -s LockSuite_Update:*` que NO aparezca "No se pudo programar
     el watchdog"; si aparece "watchdog programado inexacto", el fallback está actuando
     (aceptable).
2. **F1b — reinicio del servicio a mitad de flujo.** Con una actualización en curso,
   apagá y prendé el servicio de Accesibilidad de LockSuite desde Ajustes (o dejá que el
   sistema lo recicle). Esperado: la pantalla negra **vuelve** y el flujo sigue hasta un
   cierre con motivo; NO queda Play Store abierta y destapada.
3. **F3 — panel.** Mandá una actualización que falle (equipo sin espacio) y, **después
   de que termine**, cerrá y reabrí la ficha del dispositivo. Esperado: la tarjeta
   **"🔄 Última actualización"** muestra "✗ No hay espacio en el celular — …" y el
   espacio libre. Antes, reabrir no mostraba nada.
4. **Mejora 1 — Android 13+.** Instalá limpio, y en Ajustes → LockSuite → Notificaciones
   confirmá que el permiso quedó concedido y **administrado por la organización** (switch
   deshabilitado). Verificá que el aviso de accesibilidad caída suena/vibra sin haber
   tocado nada a mano.
5. **Regresión: Android 11 (CAT S22 Flip).** Confirmá que una actualización normal sigue
   andando igual (el watchdog exacto sigue disponible en API < 31, sin permiso).

Diagnóstico por ADB:
```
adb logcat -s LockSuite_Update:* LockSuite_Session:* LockSuiteAccessibility:* PolicyManager:*
adb shell dumpsys alarm | grep -i locksuite
```

---

## 4. Mejora 2 — Watchdog centinela: qué copiar y qué NO. B.56. NO IMPLEMENTADA.

La idea del "Dual-Process Sentinel" (4 niveles: foreground service + segundo proceso
`:bprocces` + AlarmManager setExact + NotificationListenerService) se analizó. **Solo un
pedazo conviene, y NO lo escribí porque toca la arquitectura del watchdog, que el proyecto
mide por batería (B.30) y yo no puedo medir.**

**Qué SÍ conviene (bajo riesgo, cero batería en estado normal):** una **alarma de
respaldo con `AlarmManager` que llene la brecha entre los 20 s del
`WatchdogForegroundService` y los 15 min del `WatchdogWorker`**, y que **se arme solo
cuando el foreground service se cae**. Es exactamente lo que B.4-opción-3 y la propia nota
de la mejora anticipaban.

Implementación sugerida (para que la escribas y midas vos):
- En `WatchdogForegroundService.onDestroy()` (y en `onTaskRemoved`), **armá** una alarma
  `AlarmManager` a ~60-90 s con un `PendingIntent` a un receiver que **re-arranque el
  foreground service** (`ContextCompat.startForegroundService`). En `onStartCommand`/
  `onCreate`, **cancelala** (si el servicio está vivo, no hace falta).
- Usá **`setAndAllowWhileIdle`**, **NO** `setExact*` — mismo motivo que F1: en Android
  12+ el exacto exige permiso y tiraría `SecurityException`. La imprecisión de Doze
  (~9 min) es aceptable para un respaldo.
- Reutilizá el patrón de `UpdateFlowManager.armWatchdog` (ya corregido) como molde.

**Qué NO copiar, y por qué:**
- **Segundo proceso `:bprocces`.** Suma huella de memoria permanente. En el equipo de
  referencia (CAT S22 Flip, **2 GB de RAM**) eso es justo lo que no sobra, y contradice la
  eficiencia medida en B.30. No vale la pena.
- **`NotificationListenerService`.** Exige que el usuario conceda "acceso a
  notificaciones" (otra superficie de consentimiento), lee TODAS las notificaciones del
  equipo (privacidad + overhead), y su única ventaja es prioridad de ciclo de vida — que
  la alarma de respaldo ya cubre a un costo mucho menor.

Antes de escribirla, medí: con la alarma armada solo ante caída del FGS, el consumo en
estado normal tiene que ser **cero** (nunca se dispara mientras el FGS vive).

---

## 5. Pendientes acotados que NO toqué

- **F2 — La pantalla negra está en español fijo (idioma).** `stageLabel()` en
  `UpdateFlowManager`, el subtítulo por omisión y el botón "Cancelar" de
  `BlockOverlayManager`, y los strings de error de `start()` están hardcodeados en
  español. **La DETECCIÓN es independiente del idioma (bien), pero el TEXTO que ve el
  usuario final —que en un equipo kosher puede estar en hebreo— no.** El panel es del
  admin (español está bien); la pantalla negra la ve el usuario del celular. Si el dueño
  quiere que esté en el idioma del equipo, hay que mover esos strings a
  `res/values*/strings.xml` (es/en/he) y leerlos con `service.getString(...)`. Es
  decisión de producto + trabajo de recursos; no lo hice a ciegas. Preguntarle al dueño.
- **F5 — Importar un preset que falla no dice por qué.** `importPolicyPresetJson()`
  atrapa TODA excepción y devuelve un `false` pelado; el handler `APPLY_PRESET_PROFILE`
  no setea `commandErrorReason`, así que el panel muestra "✗ El comando falló" sin
  motivo — aunque el fallo sea una **firma HMAC alterada** (que hoy se pierde en el catch
  genérico). Además una aplicación parcial (un setter que tira a mitad) no hace rollback.
  Arreglo sugerido: que `importPolicyPresetJson` devuelva/propague el motivo (p.ej.
  distinguir `SecurityException` de firma), y que el handler lo ponga en
  `commandErrorReason`. Menor y pre-existente; no urgente.
- **B.39-12 quedó resuelto de antes:** `importPolicyPresetJson` ya llama a
  `ensureVpnRunning()` al final (línea ~1519). Confirmado en esta revisión; solo falta la
  prueba en equipo. Se puede tachar cuando se pruebe.

---

## 6. Commit

Esta tanda toca el panel, así que además del APK hay que `firebase deploy --only hosting`.

⚠️ **No uses `deploy_all.ps1` sin antes sacar del árbol los `scratch/diag_red_*.txt`**
(son volcados de red del celular real, ver B.32) — `deploy_all.ps1` hace `git add .`.

```
git add app/src/main/AndroidManifest.xml \
        app/src/main/java/com/ejemplo/locksuite/util/UpdateFlowManager.kt \
        app/src/main/java/com/ejemplo/locksuite/service/LockSuiteAccessibilityService.kt \
        app/src/main/java/com/ejemplo/locksuite/mdm/PolicyManager.kt \
        admin-backend/public/app.js \
        admin-backend/public/index.html \
        LOCKSUITE_CONTEXTO_PARA_IA.md \
        INSTRUCCIONES_ANTIGRAVITY_2026-09-09_ACTUALIZACION_REVISION.md
```

```
fix(actualizacion): cerrar tres silencios del flujo y asegurar permisos criticos

Revision a fondo del mecanismo de actualizar apps (B.41/B.42), pedida por el
dueno: que muestre SIEMPRE en que esta, igual desde la pantalla negra y desde el
panel. El nucleo esta bien; aparecieron tres casos donde el flujo se quedaba sin
decir nada, mas una mejora de permisos.

F1 (B.54) El watchdog de 10 min NO se armaba en Android 12+. armWatchdog usaba
setExactAndAllowWhileIdle, que desde API 31 exige permiso de alarma exacta; el
Manifest no declaraba ninguno, asi que la llamada tiraba SecurityException, caia
en el catch y la alarma —el respaldo final que saca la pantalla negra si todo lo
demas falla— nunca se programaba, en silencio, incluido el equipo Android 13 del
dueno. Ahora el Manifest declara SCHEDULE_EXACT_ALARM + USE_EXACT_ALARM, y
armWatchdog verifica canScheduleExactAlarms() y cae a setAndAllowWhileIdle (sin
permiso, dispara en Doze) si no puede exacta.

F1b (B.54) Si el servicio de accesibilidad se reiniciaba a mitad de flujo, nadie
re-armaba el ticker: el flujo quedaba marcado en curso con Play Store destapada y
la instalacion habilitada, pero sin pantalla y sin cierre. En Android 12+ ni el
watchdog lo rescataba. Ahora onServiceConnected redibuja el overlay y re-arma el
ticker si hay flujo en curso.

F3 (B.54) El panel publicaba lastResultReason y freeSpaceMb pero no los dibujaba:
el motivo del ultimo resultado solo se veia en la linea efimera del comando.
Tarjeta nueva "Ultima actualizacion" que los muestra aunque no haya ninguna en
curso. Cache-buster de app.js a v=32.

Mejora 1 (B.55) Auto-concesion de permisos peligrosos desde Device Owner
(autoGrantDeclaredPermissions en reapplyAllRestrictions). Hoy asegura
POST_NOTIFICATIONS (Android 13+) para que el usuario no pueda dejar mudo el aviso
de accesibilidad caida. setPermissionGrantState solo aplica a permisos de runtime;
MANAGE_EXTERNAL_STORAGE / SCHEDULE_EXACT_ALARM y demas app-ops se ignoran (por eso
F1 va por Manifest).

Verificado con kotlinc 2.0.21 contra stubs, 0 errores, con ocho controles
negativos, los ocho detectados. node --check sobre app.js con control negativo.
Manifest XML well-formed. SIN COMPILAR CON GRADLE Y SIN PROBAR EN EQUIPO. Detalle
y orden de prueba en INSTRUCCIONES_ANTIGRAVITY_2026-09-09_ACTUALIZACION_REVISION.md
y en B.54/B.55/B.56 de LOCKSUITE_CONTEXTO_PARA_IA.md.

Co-Authored-By: Claude Opus 4.8 <noreply@anthropic.com>
Claude-Session: https://claude.ai/code/session_01HV4hiQ8W4UuG8VBSvHcvFi
```

> Mejora 2 (watchdog de respaldo por AlarmManager, B.56) queda SIN implementar a
> proposito — hay que escribirla y medirla con terminal real. Detalle en la seccion 4.
