# Instrucciones para Antigravity — sesiones del 17 y 18/8/2026

> **AGREGADO EL 18/8.** El dueño probó lo del 17/8 en el equipo: **los cuatro
> interruptores de Protecciones de Accesibilidad funcionaban mal**. Están corregidos y
> hay un tema nuevo y más importante (evasión por cambio de idioma). Todo eso está en
> la **sección 5 al final de este documento** — leela antes de probar la sección 3.D.


**Leé primero `LOCKSUITE_CONTEXTO_PARA_IA.md` completo.** Este documento no lo reemplaza: solo dice qué dejó escrito la sesión de Claude del 17/8, qué falta hacer y en qué orden.

## Estado de partida, sin adornos

La sesión de Claude del 17/8 trabajó **sin terminal** (la VM de bash/git/gradle estaba caída, igual que el 16/8). Eso significa, literalmente:

- **Nada de lo que sigue se compiló.** Ni `compileDebugKotlin`, ni nada.
- **Nada se probó en equipo.**
- **Nada se commiteó.** No pudo correr `git` ni para mirar el estado.
- Puede haber trabajo de otra sesión en paralelo sin commitear. **Empezá por `git status` y `git log -1`.**

Tomá todo lo de abajo como "código escrito con cuidado y revisado a mano, pero sin una sola verificación automática".

---

## 1. Qué se tocó y por qué

Cinco pedidos del dueño. En tres de ellos el problema resultó ser distinto de lo que parecía.

### 1.1 Bloqueo de imágenes — reescrito de N ventanas a una capa de canvas

**Archivos:** `service/BlockOverlayManager.kt` (reescrito entero), `service/LockSuiteAccessibilityService.kt`.
**Contexto:** B.17.

El dueño reportó que los recuadros "se tienen que ir moviendo con la pantalla y eso es muy incómodo". La causa raíz: **cada recuadro era su propia ventana del sistema**. Moverlo un píxel era un IPC al WindowManagerService más una recomposición; diez imágenes por cada frame de scroll son cientos de IPC por segundo. Los recuadros iban por un camino estructuralmente más lento que el contenido, y cada uno llegaba por separado.

Ahora hay **una sola ventana transparente a pantalla completa** que pinta los recuadros en su canvas. Mover todos = cambiar números en memoria + `invalidate()`. Además, el evento de scroll trae el delta en píxeles (`getScrollDeltaX/Y`, API 28+): los recuadros se corren ese delta al instante sin tocar el árbol de nodos, y el recorrido real corrige después. Por eso `IMAGE_SCROLL_DEBOUNCE_MS` subió de 50 a 90 ms — se recorre el árbol la mitad de veces y se ve mejor.

**Compatibilidad:** la API pública del `BlockOverlayManager` es la misma (`blockRegion` / `clearStaleRegions` / `hasRegions` / `clearAll` / `showBlockingMessageOverlay` / …), así que nadie más tuvo que cambiar. Se agregaron `regionCount()`, `translateRegions(dx, dy)`, `setStrictCover(rect)` y `hasStrictCover()`.

**Lo que hay que mirar en pantalla y no se puede saber sin el equipo:** que los recuadros no queden **corridos verticalmente**. Ahora es una sola ventana, así que si hay corrimiento va a ser igual para todos y saltará a la vista; el culpable sería el par `FLAG_LAYOUT_IN_SCREEN | FLAG_LAYOUT_NO_LIMITS` de `ensureOverlayAttached()`.

### 1.2 VPN — dos causas del "se cae internet entero"

**Archivos:** `service/KosherVpnService.kt`, `util/NetworkForwarder.kt`.
**Contexto:** B.18.

No era la arquitectura (B.4). Eran bugs:

1. **El DNS de salida podía ser `fd00::1`, el DNS virtual del propio túnel.** Estaba excluida la dirección IPv4 (`10.0.0.1`) pero no la IPv6. Cuando pasaba, cada consulta se mandaba a la nada: 3,5 s de timeout, todas las consultas, todas las apps — que es exactamente "se cayó internet". Y no se arreglaba solo; apagar y prender la VPN sí, porque volvía a consultar en un momento en que `activeNetwork` era la red física. **Encaja con el síntoma reportado hasta en el detalle de cómo se lo destrababa.**
2. **`CallerRunsPolicy` frenaba al único hilo que lee del túnel.** Ante una ráfaga, ese hilo se ponía a esperar red hasta 3,5 s y dejaba de leer; la cola seguía llena y se realimentaba.

Más: tres `Log.i` **por paquete** en el hilo lector (ahora detrás de `VERBOSE`), un `Thread.sleep(15)` por consulta (eliminado), `resolveOwnerUid()` que ahora ni se llama si no hay reglas por app, `restartVpn()` que se disparaba con cualquier `onAvailable` (ahora compara `networkHandle`, debounce 3 s → 8 s), y MTU 1500 → 4000 **con reintento automático a 1500** si el sistema lo rechaza.

⚠️ **Prestá atención especial al reintento de MTU.** Si `establish()` devuelve null con 4000 y el reintento con 1500 no funcionara, el equipo se queda **sin filtro**. Verificá en el log que aparece `Servicio VPN iniciado exitosamente` después de bootear.

### 1.3 Arranque protegido — archivo nuevo `util/BootGate.kt`

**Archivos:** `util/BootGate.kt` (nuevo), `receiver/BootReceiver.kt`, `service/KosherVpnService.kt`, `service/WatchdogForegroundService.kt`, `mdm/PolicyManager.kt` (`setNetworkGateBlocked`).
**Contexto:** B.16.

`BootReceiver` cierra la red con el proxy global a puerto muerto **antes que nada** (antes de `reapplyAllRestrictions()`, que tarda cientos de ms). La reabre `KosherVpnService` cuando el bucle de lectura **ya está leyendo**, no cuando el servicio arranca. Red de seguridad: el Watchdog libera igual a los 120 s y lo anota en `bootGateLastResult`, que el panel muestra.

`setNetworkGateBlocked()` **no escribe** la preferencia `internet_blocked` a propósito: esa preferencia representa la intención del administrador y `reapplyAllRestrictions()` la usa para reconstruir el estado. Si el arranque protegido la escribiera, un equipo cuyo bloqueo no llegara a limpiarse quedaría marcado como "sin internet a propósito" para siempre. **No lo "simplifiques" a usar `setInternetBlocked()`.**

En Arranque Directo (`LOCKED_BOOT_COMPLETED`, antes del primer desbloqueo) `engage()` sale enseguida porque las preferencias todavía no se pueden leer. Es correcto: hasta ese momento las apps de usuario no arrancan igual.

### 1.4 Protecciones de Accesibilidad — cuatro interruptores separados

**Archivos:** `service/LockSuiteAccessibilityService.kt` (rebote de Ajustes), `service/WatchdogForegroundService.kt` (aviso + suspensión), `mdm/AppController.kt` (`setEmergencySuspendAll`), `mdm/PolicyManager.kt`, `service/LockSuiteFirebaseService.kt`, `util/FirebaseDeviceSync.kt`, `ui/dashboard/DashboardActivity.kt`, `admin-backend/*`.
**Contexto:** B.15 (tabla con el costo de cada uno).

Claves: `acc_protect_bounce_settings`, `acc_protect_nag`, `acc_protect_suspend_all`, `boot_gate_wait_accessibility`. Todas **apagadas por defecto**, todas colgadas del maestro `accessibility_protection_enabled` (encendido por defecto).

El rebote del menú de Ajustes detecta por nombre de clase de ventana y, si eso no alcanza (Android puro mete casi todo dentro de `com.android.settings.SubSettings`), por título contra una lista en 10 idiomas. **No rebota con sesión de administrador abierta** — si no, el propio administrador no podría apagarlo.

### 1.5 WhatsApp — dos bugs de "tapa de más" y "tapa de menos"

**Archivo:** `service/LockSuiteAccessibilityService.kt`.

- **Tapaba de más (importante):** `"mediaview"` estaba en la lista de clases de "Estados", pero `com.whatsapp.MediaView` es **el visor de fotos de las conversaciones normales**. Con "bloquear Estados" activado no se podía abrir una foto que te manda un contacto en un chat común. Sacado.
- **Tapaba de más (segundo):** `scanForUpdatesTab()` recibía `blockStatus`/`blockChannels` y **no los usaba**. Como Estados y Canales viven juntos en la pestaña "Novedades", con un solo interruptor encendido se rebotaba de la pestaña entera. Ahora la pestaña completa solo se rebota si están bloqueadas las dos cosas; con una sola, el bloqueo actúa al abrir el estado o el canal concreto.
- **Tapaba de menos y costaba caro:** eran seis búsquedas completas del árbol en el caso negativo (el 99 % de las veces) y solo en español e inglés. Ahora es un recorrido con topes contra etiquetas en diez idiomas.

---

## 2. Qué hacer, en orden

### Paso 0 — Estado del repo

```powershell
git status
git log -1
git diff --stat
```

Puede aparecer ruido de fin de línea (CRLF) sin cambios de contenido en `app/build.gradle.kts`, `admin-backend/public/version.json` y `ui/emergency/BlockAccessibilityActivity.kt` — ver B.10.

### Paso 1 — Compilar

```powershell
.\gradlew.bat compileDebugKotlin
```

Dónde es más probable que salte algo, en orden de superficie nueva:

1. `service/BlockOverlayManager.kt` — reescrito entero. Ojo con `synchronized` + `return` y con la clase interna `RegionOverlayView`.
2. `util/BootGate.kt` — archivo nuevo.
3. `service/KosherVpnService.kt` — `buildTunnel()` extraído, `needsUidLookup()` nuevo, campos `by lazy` nuevos.
4. `service/LockSuiteAccessibilityService.kt` — `handleScrollEvent`, `handleAccessibilitySettingsBounce`, `hasAccessibilityTitle`, `scanWhatsAppTabNode`, `isSelectedNodeOrAncestor` (reemplaza a `isNodeOrParentSelected`, que se borró).
5. `service/WatchdogForegroundService.kt` — `accessibilityRequirementState()` devuelve `Boolean?`; el `?: run { … return }` es a propósito.
6. `mdm/PolicyManager.kt` y `mdm/AppController.kt` — métodos nuevos al final de sus secciones.

### Paso 2 — B.8 primero, siempre

Instalar en el Android 13 real y confirmar con `dumpsys accessibility` que `installedServiceCount ≠ 0` y que `Bound services` no está vacío. **Sin esto no se puede verificar nada de lo demás.**

### Paso 3 — Probar, en este orden

**A. Imágenes (B.17)** — es lo que más le molestaba al dueño.

1. Activar bloqueo de imágenes (Capa 1) en WhatsApp y en la galería.
2. Scroll largo y rápido en un chat con muchas fotos. Mirar: ¿los recuadros van pegados al contenido? ¿parpadean? ¿quedan corridos verticalmente?
3. Scroll con dos imágenes en pantalla a la vez: ¿se mueven **juntas** o desincronizadas? (antes iban desincronizadas por diseño; ahora tienen que ir juntas).
4. Encender "Imágenes: tapado estricto al desplazar" y repetir 2. Tiene que taparse el contenedor entero mientras el dedo se mueve y volver a los recuadros exactos al frenar (~220 ms).
5. Apagar el bloqueo y confirmar que la capa **desaparece** (no debe quedar una ventana transparente colgada; se puede ver con `dumpsys window | findstr locksuite`).
6. Mirar el consumo de batería del proceso después de 10 minutos de scroll, comparado con la versión anterior.

**B. VPN (B.18)** — es el bug más difícil de reproducir, así que hay que usarlo un rato.

1. Media hora de uso normal con reglas DNS activas. ¿Aparece el corte?
2. Forzar handoff Wi-Fi ↔ datos móviles cinco o seis veces seguidas. No debería haber cortes encadenados (antes cada `onAvailable` tiraba abajo el túnel).
3. Abrir de golpe cuatro o cinco apps que consulten mucho (ráfaga). No debería trabarse.
4. Confirmar que un dominio bloqueado **sigue fallando al instante** y no por timeout.
5. En el log, confirmar que ya no aparece `TUN_READ:` por cada paquete.

**C. Arranque protegido (B.16)**

1. Con reglas DNS activas, reiniciar el equipo. Durante los primeros segundos **no debe haber internet**; apenas levanta el filtro debe volver solo, sin tocar nada.
2. El panel debe mostrar "Último arranque: la red se reabrió cuando el filtro estuvo listo. ✓".
3. Deshabilitar la VPN a propósito y reiniciar: a los 2 minutos el internet tiene que volver igual, y el panel mostrar el aviso rojo de ventana vencida.
4. Apagar el interruptor "Arranque protegido" y reiniciar: no debe pasar nada raro.
5. Confirmar que un equipo que ya tenía "Bloquear Internet Completo" puesto a mano **sigue sin internet** después de reiniciar (el arranque protegido no debe pisarlo ni al poner ni al sacar).

**D. Protecciones de Accesibilidad (B.15)** — una por una, no todas juntas.

1. Rebote de Ajustes: entrar a Ajustes → Accesibilidad. Debe volver atrás. Probar también entrando desde el buscador de Ajustes. Confirmar que **no** rebota si acabás de ingresar el PIN.
2. Aviso insistente: apagar la accesibilidad y confirmar que la notificación aparece cada ~18 s y desaparece al reactivarla.
3. Suspender todas: apagar la accesibilidad y confirmar que ninguna app abre; reactivarla y confirmar que **vuelven todas**, y que las que el administrador tenía suspendidas a propósito **siguen suspendidas**.
4. Arranque protegido por accesibilidad: apagar la accesibilidad, reiniciar, confirmar que no hay internet hasta reactivarla (con el techo de 120 s).

**E. WhatsApp**

1. Con **solo** "bloquear Estados" activado: la pestaña Novedades tiene que **poder abrirse** (antes rebotaba), pero abrir un Estado tiene que rebotar.
2. Con **solo** "bloquear Canales": ídem al revés.
3. Con **los dos**: la pestaña entera rebota a Chats.
4. **Crítico:** con "bloquear Estados" activado, abrir una foto que te mandaron en un chat normal. **Tiene que abrirse.** Antes no.

**F. El resto, sin cambios:** B.13 (Mercado Pago que NO rebote en inicio ni en pagos), B.9/B.14 (actualización por Play Store), B.11 (suspensión).

### Paso 4 — Commit

La sesión de Claude no pudo commitear. Hacelo vos, y **separado del deploy** (`deploy_all.ps1` hace `git add .` y se lleva todo por delante):

```powershell
git add app/src/main/java/com/ejemplo/locksuite/service/BlockOverlayManager.kt `
        app/src/main/java/com/ejemplo/locksuite/service/LockSuiteAccessibilityService.kt `
        app/src/main/java/com/ejemplo/locksuite/service/KosherVpnService.kt `
        app/src/main/java/com/ejemplo/locksuite/service/WatchdogForegroundService.kt `
        app/src/main/java/com/ejemplo/locksuite/service/LockSuiteFirebaseService.kt `
        app/src/main/java/com/ejemplo/locksuite/util/NetworkForwarder.kt `
        app/src/main/java/com/ejemplo/locksuite/util/BootGate.kt `
        app/src/main/java/com/ejemplo/locksuite/util/FirebaseDeviceSync.kt `
        app/src/main/java/com/ejemplo/locksuite/mdm/PolicyManager.kt `
        app/src/main/java/com/ejemplo/locksuite/mdm/AppController.kt `
        app/src/main/java/com/ejemplo/locksuite/receiver/BootReceiver.kt `
        app/src/main/java/com/ejemplo/locksuite/ui/dashboard/DashboardActivity.kt `
        admin-backend/functions/index.js admin-backend/public/index.html admin-backend/public/app.js `
        LOCKSUITE_CONTEXTO_PARA_IA.md INSTRUCCIONES_ANTIGRAVITY_2026-08-17.md

git commit -m "Capa 3 y VPN: tapado de imagenes en una sola capa de canvas, dos bugs de caida de internet, arranque protegido y protecciones de accesibilidad

Imagenes (B.17): cada recuadro era una ventana del sistema, asi que moverlo
era un IPC al WindowManager por recuadro y por frame. Ahora es una sola capa
transparente que los pinta en canvas, mas seguimiento predictivo con el delta
del evento de scroll. Es la causa raiz de 'los recuadros se mueven con la
pantalla y es incomodo'.

VPN (B.18): el DNS de salida podia terminar siendo fd00::1, el DNS virtual del
propio tunel (estaba excluida la direccion IPv4 pero no la IPv6) -> 3,5 s de
timeout en todas las consultas de todas las apps, que es el sintoma 'se cae
internet y vuelve apagando y prendiendo la VPN'. Y CallerRunsPolicy frenaba al
unico hilo que lee del tunel ante una rafaga. Ademas: tres logs por paquete,
un sleep(15) por consulta, restartVpn() con cualquier onAvailable, y MTU 1500
que descartaba respuestas DNS grandes en silencio.

Arranque protegido (B.16, util/BootGate.kt nuevo): al bootear se cierra la red
con el proxy global a puerto muerto y se reabre cuando el tunel esta leyendo
paquetes de verdad, con techo de 120 s. Tapa el hueco de 'al reiniciar tarda en
activarse y mientras queda todo abierto'.

Protecciones de Accesibilidad (B.15): cuatro interruptores separados en app y
panel (rebote del menu de Ajustes, aviso insistente, suspender todas las apps,
arranque protegido por accesibilidad). Android no permite impedir por API que
se desactive un servicio de accesibilidad; el objetivo es que apagarlo no sirva.

WhatsApp: 'mediaview' bloqueaba el visor de fotos de los chats normales, no solo
Estados; y scanForUpdatesTab ignoraba sus dos parametros, asi que un solo
interruptor rebotaba la pestana Novedades entera. Ademas, un recorrido en vez de
seis busquedas del arbol, y etiquetas en diez idiomas.

Escrito por Claude el 17/8 SIN terminal: no compilado ni probado en esa sesion.
Probado en <equipo/version> por Antigravity el <fecha>."
```

Reemplazá `<equipo/version>` y `<fecha>` por lo que realmente hayas probado. Si algo no anduvo, **decilo en el mensaje** en vez de commitear como si estuviera todo bien.

### Paso 5 — Publicar

Subir el versionCode a uno **estrictamente mayor que el último realmente publicado** (verificalo, no lo asumas: el working tree venía en 0.6.21/83 pero puede haber cambiado).

```powershell
.\deploy_all.ps1 -VersionName "0.6.22"
```

Panel: `database` → `functions` → `hosting`, en ese orden y con `functions` en su propio try/catch.

---

## 3. Cosas que NO hay que "simplificar"

Van acá porque cada una parece un rulo innecesario y no lo es:

1. **`setNetworkGateBlocked()` no escribe `internet_blocked`.** Ver 1.3.
2. **`AppController.setEmergencySuspendAll()` no escribe las preferencias `suspend_<paquete>`.** Son estado de emergencia, no intención del administrador. Si las escribiera, al reactivar la accesibilidad las apps quedarían suspendidas "a propósito" para siempre.
3. **El pool DNS usa `DiscardPolicy`, no `CallerRunsPolicy`.** Descartar una consulta es normal (el cliente reintenta solo); frenar al hilo lector congela el equipo entero.
4. **El resolutor de salida se busca en una red sin `TRANSPORT_VPN`, no en `activeNetwork`.** Ese es el arreglo, no un adorno.
5. **La capa de imágenes se quita cuando no hay regiones.** Dejarla siempre puesta "por si acaso" agrega una capa al compositor todo el tiempo y come batería.
6. **`isSelectedNodeOrAncestor()` no recicla el nodo que recibe.** La versión anterior sí lo hacía y dejaba un puntero muerto en manos del recorrido.
7. **El `blockStatus && blockChannels` de `scanForUpdatesTab` es a propósito.** Ver 1.5.
8. Y lo de siempre: **`dpm.setUninstallBlocked(target, true)` durante el flujo de actualización** (B.14) y **el snapshot de flags del camino caliente** (B.13).

## 4. Si algo no anda

- **Los recuadros aparecen corridos verticalmente** → sacar `FLAG_LAYOUT_IN_SCREEN | FLAG_LAYOUT_NO_LIMITS` de `ensureOverlayAttached()` en `BlockOverlayManager.kt`. Nada más depende de esos dos flags.
- **Parpadeo permanente de los recuadros (aparecen y desaparecen solos, sin tocar nada)** → es el riesgo propio de que la capa sea de pantalla completa: `rootInActiveWindow` estaría devolviendo NUESTRA capa en vez de la app de abajo, así que el escaneo no encuentra imágenes, borra las regiones, quita la capa, y vuelve a empezar. Debería estar cubierto por `importantForAccessibility = NO_HIDE_DESCENDANTS` en `RegionOverlayView.init` más el corte por paquete propio de `runLayer1NodeBlocking()`. Confirmalo con `dumpsys accessibility` (la ventana no debería figurar) antes de tocar otra cosa.
- **El scroll se siente pesado** → subir `IMAGE_SCROLL_DEBOUNCE_MS` (90 → 150). La fluidez ya no depende de ese número, así que subirlo casi no se nota.
- **La VPN no levanta después de bootear** → mirar si `establish()` está fallando con MTU 4000 y el reintento a 1500 tampoco funciona. Bajar `TUNNEL_MTU` a 1500 desactiva la mejora pero deja el filtro andando.
- **El equipo queda sin internet y no vuelve** → `BootGate.release(context, "manual")`, o directamente `dpm.setRecommendedGlobalProxy(admin, null)` por ADB. Y revisar por qué no se liberó solo: el techo de 120 s debería haberlo hecho.
- **El rebote de Ajustes saca al usuario de pantallas que no son Accesibilidad** → NO agregar ni ampliar listas de palabras. La detección va por componente resuelto y por título localizado (ver sección 5.1); si falla, el lugar a mirar es `refreshAccessibilitySignals()`, no una lista.

---

# 5. AGREGADO DEL 18/8 — los cuatro interruptores estaban rotos

El dueño probó lo del 17/8 y reportó, textual: *"Los switches de protección de accesibilidad funcionan todos mal"*. Tenía razón en los cuatro. Tres de las cuatro causas eran errores de razonamiento, no de tipeo, y ninguna la habría agarrado un type-check.

## 5.1 Rebote del menú de Ajustes — *"rebota mucho más cosas, casi no se puede abrir la app de ajustes"*

**Causa:** la detección recorría **todo el árbol** de la ventana buscando la palabra "Accesibilidad" contra una lista en diez idiomas. Pero la pantalla **principal** de Ajustes tiene "Accesibilidad" como una fila más de su menú — igual que la búsqueda y varias sub-pantallas. O sea que rebotaba de casi cualquier pantalla de Ajustes.

**Además la lista de palabras era inútil justo en el caso que importa** (ver 5.5): diez idiomas de los 100+ que soporta Android.

**Cómo quedó** (`LockSuiteAccessibilityService.kt`, `refreshAccessibilitySignals()` + `isAccessibilitySettingsScreen()`), tres señales, ninguna con palabras nuestras:

1. **Componente exacto.** Se le pregunta al sistema qué actividad atiende `Settings.ACTION_ACCESSIBILITY_SETTINGS` y se compara `ev.className` contra eso. Exacto, gratis, independiente del idioma y del fabricante.
2. **Título localizado pedido a la propia app de Ajustes.** `getResourcesForApplication("com.android.settings")` + `getIdentifier("accessibility_settings", "string", …)` devuelve el título en el idioma que el equipo tenga puesto **ahora**. Y se compara **solo contra el título de la ventana** (`ev.text`), nunca contra el árbol — ese fue el bug.
3. **Nuestra propia etiqueta junto a un `Switch`.** Identifica la pantalla donde realmente se apaga el servicio, en cualquier idioma, porque nuestro nombre no se traduce.

También: solo corre en `TYPE_WINDOW_STATE_CHANGED` (antes también en `CONTENT_CHANGED`), y se re-resuelve todo en `onConfigurationChanged`, que es lo que dispara un cambio de idioma.

## 5.2 Aviso insistente — *"avisa una sola vez y listo"*

**Causa:** el ciclo de 18 s sí corría. Lo que no volvía a pasar era el **aviso**, por tres razones acumuladas:

1. Se re-publicaba **la misma notificación, mismo id, mismo texto**. Android lo trata como una actualización de la que ya está en la bandeja: la refresca en silencio, sin cartel flotante y sin sonido.
2. Estaba como `setOngoing(true)`, que se comporta como notificación de estado y no de alerta.
3. No tenía `setFullScreenIntent`.

**Cómo quedó:** `cancel()` antes de cada publicación, el texto **cambia en cada aviso** (lleva el número y hace cuánto que está apagada), sin `ongoing`, con full-screen intent, `PRIORITY_MAX`, vibración y `setBypassDnd`.

⚠️ **Ojo con el canal:** en Android 8+ un canal de notificación **no se puede reconfigurar por código una vez creado**. Por eso el id pasó a `locksuite_accessibility_nag_v2`. Si probás sobre una instalación que ya tenía la versión anterior y el aviso sigue sin sonar, **borrá datos de la app o reinstalá** antes de dar el arreglo por fallido.

## 5.3 y 5.4 Suspender todas / Arranque protegido por accesibilidad — *"no funcionan"*

**Causa: no estaban rotas, estaban calladas.** `accessibilityRequirementState()` devolvía "no hacer nada" cuando había **sesión de administrador abierta**. La sesión dura **5 minutos** desde que ingresás el PIN… que es exactamente lo que hay que hacer para tocar los interruptores en la app. Se apagaban solas justo mientras alguien las probaba.

**Cómo quedó:**

- La sesión de administrador ahora silencia **únicamente la pantalla roja a pantalla completa** (para que el administrador pueda trabajar). Ni la suspensión de apps ni el aviso dependen de ella. Es recuperable: al reactivar la accesibilidad vuelve todo solo.
- Los interruptores hacen efecto **al instante**: `PolicyManager` llama a `WatchdogForegroundService.requestImmediateCheck()`. Antes había que esperar hasta 20 s al próximo ciclo, lo que por sí solo ya hacía parecer que no funcionaban.
- El **arranque protegido por accesibilidad ahora suspende apps** además del proxy. Solo con el proxy no se notaba nada: el usuario reiniciaba, abría WhatsApp y WhatsApp abría igual. Cuando lo que se espera es que **una persona** vaya a activar la accesibilidad, el bloqueo tiene que verse.
- **Dos techos distintos:** 2 minutos para esperar al filtro de red (levantar un servicio es cuestión de segundos) y **30 minutos** para esperar a la accesibilidad. Antes eran los mismos 2 minutos, así que quien reiniciaba y se demoraba en mirar ya encontraba el bloqueo liberado solo.
- La suspensión de emergencia ahora se anota en preferencias (`acc_emergency_suspend_active`) además de en memoria, para que sobreviva a que el proceso muera, y se publica en el panel como `accEmergencySuspendActive` — **así se puede ver si se aplicó en vez de adivinarlo.**

## 5.5 Evasión por cambio de idioma — el hallazgo del dueño

Textual: *"si el usuario cambia el idioma a uno raro se evade todo, porque ya lee distinto la pantalla"*. **Es correcto y es el agujero más grande de la Capa 3**, porque no requiere ninguna habilidad: Ajustes → Idioma → cualquiera de los 100+ que soporta Android, y todos los filtros que comparan texto quedan mudos. Detalle completo en **B.19**.

Tres respuestas, ya en código:

1. **Interruptor "Bloquear cambio de idioma del sistema"** (`DISALLOW_CONFIG_LOCALE`), primero en la sección de Protecciones de Accesibilidad, en app y panel. Comandos `BLOCK_LOCALE_CHANGE` / `UNBLOCK_LOCALE_CHANGE`. Se reaplica en `reapplyAllRestrictions()`. **Es una línea y cierra la puerta entera.**
2. **Pedirle las palabras a la app que las muestra** en vez de traducirlas nosotros (ver 5.1, señal 2). Cubre los 100+ idiomas sin escribir ninguno. **Se puede extender a Mercado Pago** — queda pendiente y es la continuación natural.
3. **No leer texto donde hay señal estructural.** Regla para el futuro: si hay clase de actividad, view-id o componente resuelto, usar eso antes que una palabra.

**No cubre** el idioma **por app** de Android 13+ (Ajustes → Apps → X → Idioma). Contra eso sirven 2 y 3. Probar si `DISALLOW_CONFIG_LOCALE` además esconde esa entrada.

## 5.6 Cómo probar esto (reemplaza al bloque 3.D)

Antes que nada: **si venís de una instalación con la versión del 17/8, borrá datos o reinstalá** (por el canal de notificación, ver 5.2).

1. **Idioma primero.** Encender "Bloquear cambio de idioma" y confirmar que la entrada de Idioma de Ajustes queda deshabilitada. Después apagarlo, poner el equipo en hebreo (o cualquier idioma raro) y confirmar que **el rebote del menú de Accesibilidad sigue funcionando** — esa es la prueba de que la señal del título localizado sirve. Volver a español.
2. **Rebote de Ajustes.** Recorrer Ajustes normalmente: pantalla principal, red, sonido, apps, batería, buscador. **No debe rebotar de ninguna.** Después entrar a Accesibilidad: ahí sí. Y entrar a Ajustes → Apps → LockSuite: también debe rebotar. Confirmar que con el PIN recién ingresado **no** rebota.
3. **Aviso insistente.** Apagar la accesibilidad y **cronometrar**: tiene que sonar/vibrar cada ~18 s, no una sola vez. Reactivarla y confirmar que la notificación desaparece.
4. **Suspender todas.** Encender el interruptor **sin cerrar la sesión de administrador** (esa era la trampa) y apagar la accesibilidad: las apps tienen que dejar de abrir **en el acto**, no a los 20 s. El panel debe mostrar `accEmergencySuspendActive` en true. Reactivar la accesibilidad y confirmar que vuelven **todas**, y que las que el administrador tenía suspendidas a propósito siguen suspendidas.
5. **Arranque protegido por accesibilidad.** Apagar la accesibilidad, reiniciar, y confirmar que el equipo queda cerrado (sin internet y sin apps) hasta activarla — con techo de 30 minutos. Al activarla se tiene que liberar **al instante**, no en el próximo ciclo.
