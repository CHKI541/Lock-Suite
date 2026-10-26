# Instrucciones para Antigravity — 26/10/2026

## Canal de comandos del panel (B.95) y pestaña «Aplicaciones» en pantallas chicas (B.96)

Escrito por Claude, trabajando por el puente al dispositivo. **Todo está escrito en el
disco, pero SIN commit, SIN compilar con Gradle, SIN desplegar y SIN probar en un
celular.** Lo que sigue es exactamente lo que falta, en orden. El porqué completo está
en `LOCKSUITE_CONTEXTO_PARA_IA.md`, puntos **B.95** y **B.96**.

---

## 0. En una línea

El panel decía "Comando encolado en el buzón… hasta 15 min" y volvía el interruptor
atrás **en todos los celulares** (regresión de B.94: tomaba como "no llegó" el
`queued: true` que la Function manda en casi todo comando), y el celular sin FCM
("R shaul said") solo miraba su buzón cada 15 min. Ahora el panel espera el ack real y
los equipos sin FCM escuchan su buzón en vivo. Aparte, la pestaña «Aplicaciones» de la
app ya no deja la lista sin lugar en pantallas chicas.

---

## 1. Commit — ANTES de `deploy_all.ps1`

`deploy_all.ps1` hace `git add .`: si se corre primero, esto queda mezclado en el commit
automático de versión. Hacer este commit antes.

```powershell
cd "E:\Documentos\Lock Suite segunda version"
git status
```

Tiene que mostrar modificados estos 14 archivos y, sin seguimiento, `tools/panel_render/`
y este documento:

```
admin-backend/functions/index.js
admin-backend/public/app.js
admin-backend/public/celular.html
admin-backend/public/celular.js
admin-backend/public/comun.js
admin-backend/public/dominios.html
admin-backend/public/dominios.js
admin-backend/public/index.html
app/src/main/java/com/ejemplo/locksuite/service/CommandProcessor.kt
app/src/main/java/com/ejemplo/locksuite/service/WatchdogForegroundService.kt
app/src/main/java/com/ejemplo/locksuite/ui/dashboard/DashboardActivity.kt
app/src/main/java/com/ejemplo/locksuite/util/CommandMailbox.kt
app/src/main/java/com/ejemplo/locksuite/util/FirebaseDeviceSync.kt
LOCKSUITE_CONTEXTO_PARA_IA.md
```

⚠️ Si además aparece `admin-backend/public/styles.css`: es un cambio de **1 byte** que
está sin commitear desde el 10/9 y que NO es de esta sesión. Mirá `git diff
admin-backend/public/styles.css` y decidí aparte; no lo metas en este commit.

```powershell
git add admin-backend/functions/index.js admin-backend/public/app.js admin-backend/public/celular.html admin-backend/public/celular.js admin-backend/public/comun.js admin-backend/public/dominios.html admin-backend/public/dominios.js admin-backend/public/index.html
git add app/src/main/java/com/ejemplo/locksuite/service/CommandProcessor.kt app/src/main/java/com/ejemplo/locksuite/service/WatchdogForegroundService.kt app/src/main/java/com/ejemplo/locksuite/ui/dashboard/DashboardActivity.kt app/src/main/java/com/ejemplo/locksuite/util/CommandMailbox.kt app/src/main/java/com/ejemplo/locksuite/util/FirebaseDeviceSync.kt
git add tools/panel_render LOCKSUITE_CONTEXTO_PARA_IA.md INSTRUCCIONES_ANTIGRAVITY_2026-10-26_CANAL_COMANDOS_Y_APLICACIONES.md
git commit -F "Claude outputs/2026-10-26_mensaje_commit.txt"
```

Para comparar: `Claude outputs/2026-10-26_canal_comandos_y_aplicaciones.patch` es el mismo
cambio hecho como commit sobre `68bfbfc` en el clon de la sesión (en LF). `git show --stat
HEAD` tiene que listar los mismos archivos que `git apply --stat` de ese parche.

---

## 2. Compilar y probar en la PC

```powershell
.\gradlew :app:testDebugUnitTest
.\gradlew :app:assembleRelease
python tools/check_command_sync.py
python tools/check_panel_commands.py
python tools/check_whitelist_sync.py
python tools/check_profile_sync.py
python tools/gen_catalog_js.py --check
python tools/gen_policies_js.py --check
```

En la sesión los seis chequeos de `tools/` dieron verde. El Kotlin, en cambio, se chequeó
**con `kotlinc` 2.0.21 y stubs, no con Gradle** (`CommandMailboxTest` 6/6 también así), y
el proyecto compila con Kotlin 2.3.20 y la BOM de Compose 2026.03.01. Si Gradle se queja,
estos son los lugares, con el cambio que NO altera el comportamiento:

| Dónde | Qué podría fallar | Cómo cambiarlo |
|---|---|---|
| `DashboardActivity.kt`, `AppManagerTabContent` | `LinearProgressIndicator(modifier = …, color = …)` ambigua con otra sobrecarga | Agregar `trackColor = Color.Transparent` |
| `DashboardActivity.kt`, `AppManagerTabContent` | el `run { … }` que envuelve la `LazyColumn` (es `inline`, el compilador de Compose lo acepta) | Reemplazar `run {` por `Box(modifier = Modifier.fillMaxSize()) {` |
| `DashboardActivity.kt`, imports | `LocalConfiguration` / `TextOverflow` | Son `androidx.compose.ui.platform.LocalConfiguration` y `androidx.compose.ui.text.style.TextOverflow` (ya están importados) |
| `FirebaseDeviceSync.kt`, `syncToken` y `reportFields` | `updateChildren()` con un `Map<String, Any?>` (valores `null` para BORRAR `fcmTokenError`) | Sacar las dos entradas `null` del mapa y borrar aparte: `ref.child("fcmTokenError").removeValue(); ref.child("info/fcmTokenError").removeValue()` |

Lint puede sumar un aviso nuevo por `LocalConfiguration.current.screenWidthDp`
(`ConfigurationScreenWidthHeight`): es aviso, no error, y se eligió a propósito (ver §6.5).

**Opcional, si hay `node` y `playwright`:** `bash tools/panel_render/run.sh` corre las
pruebas del panel con Firebase falso (20 + 8, todas en verde en la sesión).

---

## 3. Desplegar

```powershell
.\deploy_all.ps1 -VersionName "0.6.67"
```

Sube a **0.6.67 / 130**, publica hosting + database + functions. Las reglas
(`database.rules.json`) **no cambiaron** en esta sesión.

- El panel nuevo funciona igual con la Function vieja (deduce si salió el FCM por la
  ausencia de `fcmError`), pero desplegar `functions` le da el dato explícito
  (`delivered`). Si el despliegue de functions falla, lo demás sirve igual.
- Después de desplegar, abrir el panel con **Ctrl+F5** (cache-busters: `app.js?v=42`,
  `comun.js?v=3`, `celular.js?v=5`, `dominios.js?v=2`). B.94 no los había subido.

---

## 4. Pruebas en el celular, en este orden

**0. Que no se rompió nada:** internet y VPN como siempre en un equipo con 0.6.67, y el
panel ve todos los equipos en línea.

**1. Un equipo CON FCM** (cualquiera que en el panel NO tenga la etiqueta «Sin FCM»), desde
«Vista rápida», tocar una política inofensiva (por ejemplo «Bloquear Ajuste de Volumen»):
- la línea de estado dice *"Comando enviado. Esperando respuesta del celular…"* y en
  segundos *"✓ Comando aplicado con éxito en el celular"*;
- el interruptor queda en la posición nueva (unos segundos con «⏳ pendiente» o
  «⏳ aplicando», y la marca se va sola);
- **nunca** aparece "Comando encolado en el buzón". Si aparece, el navegador tiene el
  `app.js` viejo: Ctrl+F5.

**2. ★ "R shaul said" (sin FCM), TODAVÍA en 0.6.66:**
- al abrir «Vista rápida» aparece arriba la tarjeta **«📡 Canal de comandos»** con el
  motivo de por qué no tiene FCM. **Anotalo y pasáselo al dueño** (ver §7.3);
- tocar una política inofensiva: *"⏳ En el buzón del celular… lo levanta en su próxima
  vuelta, hasta 15 min"*. El interruptor queda en lo pedido con «⏳ pendiente» (no vuelve
  solo atrás) y la tarjeta muestra *"📬 1 comando(s) esperando en su buzón"*;
- dejando el panel abierto, en ≤ 15 min tiene que aparecer *"✓ X: el celular lo aplicó"*
  y el interruptor pierde la marca. Si pasan 20 min y no, ver §5.

**3. Actualizar "R shaul said" a 0.6.67.** Desde el panel: «Vista rápida» → «🔄 Actualizar
LockSuite (Sistema)» → en la fila «LockSuite (MDM Principal)», «Actualizar MDM» (le llega
por el buzón, ≤ 15 min). O a mano en el celular: PIN → tarjeta «Actualizar LockSuite».
Después:
- la tarjeta dice *"✓ Escucha su buzón en vivo"* y *"Último chequeo del buzón: hace…"*;
- tocar una política: *"✓ Comando aplicado en el celular (llegó por el buzón)"* en pocos
  segundos.
- Con ADB: `adb logcat -s CommandMailbox` muestra *"Buzón: escuchando en vivo (el equipo
  no tiene FCM)."* y, al mandar algo, *"Buzón (en vivo): aplicando 1 comando(s)
  pendientes"*.

**4. Batería del equipo sin FCM:** dejarlo un día con 0.6.67 y comparar el consumo de
LockSuite (Ajustes → Batería) con los días anteriores. La escucha en vivo mantiene una
conexión abierta mientras hay red; se espera poco, pero hay que medirlo (§7.2).

**5. Ficha completa** (`celular.html`): en un equipo con FCM, marcar dos políticas en el
carrito y «Aplicar» → *"✓ Listo, todo aplicado."*. En un equipo sin FCM y sin 0.6.67 →
*"⏳ … quedaron en el buzón del celular sin confirmar todavía"*, **sin** contarlo como falla.

**6. Regresiones del canal:** `UPDATE_APP` desde el panel sigue mostrando las etapas en
vivo; un comando a un equipo con FCM pero dormido termina con *"⏳ El celular no confirmó
en 10 s… Te aviso acá cuando lo confirme"* y, si se despierta antes de 16 min, el ✓.

**7. B.96 — pestaña «Aplicaciones» de la app**, en el CAT S22 Flip o el Qin F21 Pro:
- se ven apps y se puede bajar hasta la última (la tarjeta de "apps populares", el
  buscador y los filtros se van con el scroll);
- los chips «Ocultar» / «Suspender» / «WebView» funcionan (amarillo con ✓ = activo) y
  vuelven a su estado si la acción falla;
- abrir el teclado del buscador no cambia el diseño;
- tocar un interruptor de una app de abajo **no** devuelve la lista al principio.
- En un teléfono normal (el A06 del dueño), la pestaña se ve como antes.

---

## 5. Si "R shaul said" sigue sin aplicar nada (con 0.6.67)

Mirá la tarjeta «📡 Canal de comandos» de ese equipo. Ahora dice qué pasa:

| La tarjeta dice | Qué significa | Qué hacer |
|---|---|---|
| "Último chequeo… — error al leer: …" | el equipo no puede leer su buzón | casi siempre permisos: el equipo cambió de uid; tiene que aparecer el cartel "pide re-vincularse" arriba del panel → Aprobar |
| "NO está escuchando su buzón en vivo (la base cortó la escucha: Permission denied)" | lo mismo | ídem |
| "aplicó N (…)" pero el estado no cambia | el comando se aplicó y falló | el ack lo dice: abrí el comando de nuevo y mirá el ✗ con su motivo |
| nunca muestra un chequeo, con 0.6.67 instalada | no corre ni el Worker ni el servicio | `adb shell dumpsys activity services com.ejemplo.locksuite` y `adb logcat -s CommandMailbox LockSuite_Watchdog` |

Y en Firebase Console → Realtime Database → `devices/058d4e4ac999401a`: `fcmTokenError`,
`pendingCommands`, `mailboxLive`, `mailboxLastCheckAt`, `mailboxLastResult`.

---

## 6. Cosas que NO hay que "simplificar"

1. **`queued` no es un desenlace.** La Function lo manda en casi todo comando. Lo que
   decide si hay que esperar al celular es si salió el FCM (`delivered`, o la ausencia de
   `fcmError` con la Function vieja). Volver a cortar por `queued` es volver a romper
   todos los equipos (B.94 → B.95).
2. **"En cola" no deshace el interruptor.** Solo `failed`/`rejected`. El comando está vivo
   en el buzón y se va a aplicar.
3. **La escucha en vivo es SOLO para equipos sin FCM.** Con FCM sería una conexión abierta
   para nada (batería: B.30, B.80, B.89).
4. **`IN_FLIGHT` y "REPLAY no escribe ack"** (`CommandProcessor`): sin el primero, un
   comando que llega por FCM y por el buzón a la vez se aplica dos veces; sin el segundo,
   el "rechazado: REPLAY" pisa el `applied` verdadero.
5. **El modo compacto de la pestaña Aplicaciones se decide por el tamaño de la PANTALLA**
   (`LocalConfiguration`), no por el espacio de la pestaña: si no, al abrir el teclado
   del buscador el diseño cambia mientras se escribe.
6. **La cabecera de la pestaña Aplicaciones va DENTRO de la `LazyColumn`.** Sacarla "para
   que el buscador quede fijo" es exactamente lo que dejó la lista sin altura.
7. **`marcarInterruptoresPendientes()` solo dibuja**: no escribe nada en la base.

---

## 7. Lo que no se pudo hacer desde la sesión, y lo que queda para decidir

**No se pudo (y por qué):**
- Compilar con Gradle, correr las pruebas con Gradle y el lint: la red del contenedor
  solo llegaba a `git` de GitHub (sin Maven, sin `dl.google.com`).
- Commitear: `device_bash` arrancó pero no montó la carpeta, así que no hay `git` sobre el
  disco del dueño. Por eso el §1.
- Desplegar y probar en un celular: sin credenciales ni ADB.
- **Mirar el estado real de "R shaul said" en la base** (`fcmTokenError`,
  `pendingCommands`): el contenedor no llega a Firebase. La causa 2 está deducida del
  código, no medida en ese equipo; la tarjeta nueva la va a mostrar sola.

**Para decidir con el dueño:**
1. **La regla que agregó B.94** en `database.rules.json`:
   `(!data.child('ownerUid').exists() && newData.child('ownerUid').val() === auth.uid)`.
   Deja que CUALQUIER sesión anónima se adueñe de un nodo viejo sin `ownerUid` (el hueco
   de B.3/B.73, acotado a equipos que no sincronizan desde antes del 22/9). Para esos
   equipos ya existía el camino seguro (`deviceClaims` + "Aprobar" en el panel). Si se
   decide sacarla, correr antes `tools/rules_tests/` (`npm install && npm test`).
2. **¿Escuchar el buzón en vivo también en equipos con FCM?** Más robusto si FCM falla en
   silencio; cuesta batería. Medir primero (§4.4).
3. **¿Por qué "R shaul said" no tiene FCM?** El motivo sale en la tarjeta. Si es
   `SERVICE_NOT_AVAILABLE` / `MISSING_INSTANCEID_SERVICE`, el equipo no tiene (o tiene
   roto) Play Services y el buzón en vivo es su canal definitivo. Si es otra cosa, puede
   tener arreglo.

---

## 8. Mensaje de commit

Está en `Claude outputs/2026-10-26_mensaje_commit.txt` (se usa con `git commit -F`).
