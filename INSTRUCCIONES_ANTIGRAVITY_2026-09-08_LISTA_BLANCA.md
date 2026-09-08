# Instrucciones para Antigravity — 8/9/2026 (tarde)
## Modo lista blanca (B.53) + media B.52 cerrada

Sesión de Claude por el puente al dispositivo. **`device_bash` no montó la carpeta (novena vez consecutiva)**, así que esta sesión **no pudo compilar, ni desplegar, ni commitear**. Todo el código está escrito en el disco; falta lo que sigue.

Antes de arrancar, releé **B.53** en `LOCKSUITE_CONTEXTO_PARA_IA.md`: acá está el qué hacer, allá está el por qué de cada decisión.

---

## 0. Antes que nada: verificá el estado real

```powershell
cd "E:\Documentos\Lock Suite segunda version"
git log -1
git status
cat app\build.gradle.kts | Select-String versionCode
```

Lo esperado: HEAD en `719bfbc` (0.6.46 / código 109) y **trece archivos sin commitear**.

**Tres nuevos:**

```
app/src/main/java/com/ejemplo/locksuite/mdm/WhitelistCatalog.kt
app/src/main/java/com/ejemplo/locksuite/mdm/WhitelistManager.kt
tools/check_whitelist_sync.py
```

**Diez modificados:**

```
app/src/main/java/com/ejemplo/locksuite/service/KosherVpnService.kt
app/src/main/java/com/ejemplo/locksuite/service/LockSuiteFirebaseService.kt
app/src/main/java/com/ejemplo/locksuite/mdm/PolicyManager.kt
app/src/main/java/com/ejemplo/locksuite/util/FirebaseDeviceSync.kt
app/src/main/java/com/ejemplo/locksuite/worker/WatchdogWorker.kt
app/src/main/java/com/ejemplo/locksuite/LockSuiteApplication.kt
app/src/main/java/com/ejemplo/locksuite/ui/dashboard/DashboardActivity.kt
admin-backend/public/app.js
admin-backend/public/index.html
admin-backend/functions/index.js
```

Más `LOCKSUITE_CONTEXTO_PARA_IA.md` y este documento.

Si `git status` muestra **muchos más** archivos modificados sin que los hayas tocado vos, revisá los finales de línea antes de nada: `KosherVpnService.kt` está en **CRLF** en el disco y todo el resto en **LF**. Esta sesión respetó eso archivo por archivo (se verificó comparando tamaños en bytes contra el commit), pero conviene confirmarlo.

---

## 1. Compilar

```powershell
.\gradlew compileDebugKotlin
```

Esta sesión type-checkeó con `kotlinc` 2.0.21 contra stubs (0 errores / 0 warnings, 14 controles negativos detectados) y corrió 49 aserciones de comportamiento contra el catálogo y el Trie reales — pero **no corrió Gradle**. Lo que un type-check con stubs no puede ver, y que es donde miraría yo si algo falla:

- **`ui/dashboard/DashboardActivity.kt`** — es el único archivo con Compose, y Compose no se puede stubbear a mano de forma razonable. Se agregó una pestaña nueva (`WhitelistTabContent` + `WhitelistAppRow`, al final del archivo) y la entrada en el `ScrollableTabRow`. Se verificó a mano que cada componente que usa (`Switch`, `Card`, `TextButton`, `OutlinedTextField`, `LazyColumn`, `items`, `mutableIntStateOf`, `SwitchDefaults`, `CardDefaults`, `ButtonDefaults`, `OutlinedTextFieldDefaults`) **ya se usa en ese mismo archivo**, y que `LocaleManager`, `Toast`, `LockSuiteApplication` y `com.ejemplo.locksuite.dns.*` están importados. Si algo no compila, lo más probable es acá.
- **`org.json.JSONArray(Collection)`** en `FirebaseDeviceSync.pullWhitelistConfig()`. Ese constructor existe en el `org.json` de Android; si tu SDK se queja, cambialo por un `JSONArray()` y un `put()` en bucle.

Si compila, seguí. Si no, el error casi seguro está en uno de esos dos lugares.

---

## 2. Correr el chequeo de simetría (nuevo, tarda un segundo)

```powershell
python tools\check_whitelist_sync.py
```

Tiene que decir `OK — los dos catálogos dicen lo mismo.` y salir con código 0.

Qué compara y por qué importa: el catálogo de la lista blanca vive en Kotlin (la fuente de verdad, la que decide qué dominio resuelve) **y** en `admin-backend/public/app.js` (la copia que el panel necesita para dibujar las tarjetas). Los dominios no se duplican, pero el nombre y el paquete sí — y **un paquete mal escrito en el panel escribe una decisión que ningún equipo va a aplicar nunca, mostrando la app en verde igual**. Es el mismo modo de falla silencioso de `no_apps_control` en B.28. **Conviene correrlo antes de cada `deploy_all.ps1`.**

---

## 3. Desplegar el panel

Como siempre: `hosting,database` primero y `functions` aparte.

```powershell
cd admin-backend
firebase deploy --only hosting,database
firebase deploy --only functions
```

O directamente `.\deploy_all.ps1 -VersionName "0.6.47"` desde la raíz — **pero antes sacá `scratch\diag_red_2026-09-06*.txt` del árbol o agregá `scratch/` al `.gitignore`**: `deploy_all.ps1` hace `git add .` y esos son volcados de red del celular real del dueño (B.32).

Al abrir el panel: **Ctrl+F5**. El cache-buster de `app.js` ya está en `v=31`.

### 3-b. ⚠️ Lo primero que hay que mirar si el catálogo no llega a los celulares

**Esta sesión NO tocó `database.rules.json`.** El modo lista blanca usa un nodo nuevo:

```
globalSettings/whitelist/decisions/<paquete_con_guiones_bajos>   = "allow" | "block"
globalSettings/whitelist/custom/<paquete_con_guiones_bajos>      = {label, packageName, allow:[], block:[]}
```

El panel lo **escribe** (usuario admin autenticado) y el celular lo **lee** (sesión anónima) al recibir `SYNC_WHITELIST`. Confirmá que las reglas actuales permiten las dos cosas. Si no, el comando va a fallar con permisos y **el ack lo va a decir textual** (`No se pudo leer globalSettings/whitelist`) — no falla en silencio. Miralo en el panel, en la respuesta del comando.

Referencia: `globalSettings/allowedPackages` ya funciona con este patrón, así que probablemente alcance con la misma regla para `globalSettings/whitelist`.

---

## 4. Orden de prueba en equipo real

**La prueba 1 va primero y no es opcional.** La lista de infraestructura cambia el comportamiento del filtro **aunque el modo lista blanca esté apagado** (esa es la mitad de B.52 que se cierra acá), así que es la única prueba que puede detectar una regresión sobre la flota que ya está en producción.

### 1. Con el modo APAGADO: que no se rompió nada ← **LA MÁS IMPORTANTE**

Instalar y **sin tocar nada del modo lista blanca**:

- Hay internet y los dominios resuelven normal.
- Un dominio bloqueado sigue fallando al instante (no por timeout).
- **El panel sigue controlando el equipo**: mandá cualquier comando y confirmá que llega el ack.
- Play Store abre y una app se actualiza.
- El Wi-Fi con portal cautivo sigue funcionando.

Si algo de esto falla, el problema está en `WhitelistCatalog.INFRASTRUCTURE` y se revierte quitando el bloque `FORCE_ALLOW` de `handleDnsQuery`; el resto no depende de eso.

### 2. Simulación: que registre sin bloquear

En el panel, pestaña Políticas del equipo → **Activar el filtro de lista blanca**, dejando **"Bloquear de verdad" APAGADO**. Usar el celular normal un día entero.

- Para el usuario **nada tiene que cambiar**. Si algo deja de andar en simulación, hay un bug: la simulación no bloquea.
- En el panel el resumen tiene que decir `SIMULANDO (no bloquea)` y `whitelistAuditCount` ir subiendo.
- La lista "Dominios que quedaron afuera" se llena. Se publica cada 15 minutos (ciclo del `WatchdogWorker`), o al instante con el botón 🔄 Sincronizar catálogo.

### 3. Completar el catálogo

Pestaña **🛡️ Lista blanca** → permitir las apps que quiere el dueño → **Enviar a todos los celulares**. Después, desde la auditoría del equipo, ir sumando con el desplegable "Agregar a…" los dominios que aparezcan, hasta que la lista deje de crecer con uso normal.

Lo que se agrega desde el panel **se suma** a los dominios de fábrica de esa app, no los reemplaza (`WhitelistManager.allowedDomainsOf()`). Para **sacar** un dominio de fábrica, ponelo en la lista de bloqueados de esa app: los bloqueos se escriben después de los permisos y ganan.

Aun así, cuando un dominio resulte ser parte estable del catálogo base, conviene **agregarlo directo en `WhitelistCatalog.kt` y recompilar** en vez de dejarlo solo en el panel — así lo tiene también un equipo recién instalado, antes de la primera sincronización.

### 4. Recién ahí, estricto — en UN equipo de prueba

Encender **"Bloquear de verdad"**. Probar una por una las apps permitidas.

- **Mercado Pago con una transferencia real.** Es la entrada más delicada del catálogo: se apoya en `mercadolibre.com` y `mlstatic.com`, y se le bloquean 21 hosts de marketplace y ofertas.
- **Google Messages**: que los mensajes salgan (RCS pasa por `jibe.google.com`) y que **el selector de GIF no cargue nada**.
- **Traductor**: que traduzca. `translate.google.com` está bloqueado a propósito (es un proxy de navegación); la app usa `translate.googleapis.com`.
- Confirmar que el buscador de Google, YouTube y cualquier dominio nuevo **no resuelven**.

### 5. Prohibir una app: las tres cosas juntas

Prohibir una app instalada desde el panel → Enviar → confirmar **las tres**:

- desaparece del launcher (oculta),
- **desaparece de la tienda del celular** (LockSuite → Tienda),
- sus dominios dejan de resolver.

### 6. Reiniciar el equipo

La app prohibida tiene que **seguir oculta**. Es la reconciliación que se agregó a `reapplyAllRestrictions()`, y está puesta exactamente por la lección de **B.11 punto 2** (las apps ocultas no se reconstruían desde las preferencias y volvían a aparecer para siempre).

### 7. Suspensión de LockSuite

Con el modo encendido, suspender LockSuite: **todas** las apps tienen que volver a aparecer, incluidas las prohibidas por lista blanca. Reanudar: se vuelven a ocultar solas.

### 8. Que "forzar" siga ganando

- Forzar **permitir** un dominio que la lista blanca bloquea → tiene que resolver.
- Forzar **prohibir** un dominio de infraestructura (probá con uno inofensivo, no con `mtalk.google.com`) → tiene que caer.

Es el pedido textual del dueño y además la salida de emergencia del sistema entero.

---

## 5. Cosas que NO hay que "simplificar"

1. **El orden de inserción en `WhitelistManager.buildRules()`.** No es cosmético: es lo que hace que `tenor.googleapis.com` le gane a `googleapis.com` y que ninguna app agregada desde el panel pueda destapar un bloqueo fijo. Está documentado arriba de la función.
2. **La posición del bloque en `handleDnsQuery`** — después de `FORCE_*`, antes de todo lo demás. Correrlo hacia abajo rompe la promesa de que "forzar" gana; correrlo hacia arriba rompe la salida de emergencia.
3. **`WhitelistManager.decide()` es estática y el Trie vive en un `AtomicReference`.** No convertirlo en algo que construya un `WhitelistManager` o lea `SharedPreferences` por consulta: ese es exactamente el costo que B.13 sacó del camino caliente.
4. **`buildRules()` es una función pura a propósito.** Es lo único que permite probar el comportamiento sin un equipo. Si le metés un `Context` adentro, se pierde.
5. **La auditoría NO registra los bloqueos deliberados** (dominios de apps prohibidas, Tenor, YouTube). Solo los que quedaron afuera. Si se mezclaran, el panel ofrecería "agregar a la lista" justo sobre lo que se decidió cerrar.
6. **La lista blanca no está en el mapa de políticas de GRUPOS.** Encender el filtro estricto sobre un grupo entero con un clic es la clase de acción que B.12 decidió no ofrecer, y acá el costo de equivocarse es toda la flota sin internet a la vez.
7. **`allowedDomainsOf()` / `blockedDomainsOf()` SUMAN catálogo + panel, no eligen uno.** La primera versión hacía `custom[pkg] ?: catálogo` y convertía el camino más usado del modo (sumar un dominio desde la auditoría) en la forma más fácil de romper una app. Para sacar un dominio de fábrica alcanza con bloquearlo.
8. **`syncWhitelistState()` compara una firma antes de reescribir el nodo de auditoría.** Sin eso son ~15 KB cada 15 minutos de datos móviles del usuario final por un dato que no cambió (mismo criterio de B.30).

---

## 6. Mensaje de commit

```
feat(lista blanca): filtro estricto por app, con permitir/prohibir de un solo toque

Modo lista blanca: solo resuelven los dominios de las apps permitidas. Permitir
una app abre sus dominios, la descarga desde la tienda administrada y la
des-oculta; prohibirla hace las tres al reves. Una app sin marcar no se toca,
pero se queda sin ningun dominio abierto, o sea sin internet.

La lista blanca es global por dominio y no por app porque es lo unico que
funciona: las consultas DNS salen por netd y el filtro no puede atribuirlas a
una app (B.10, medido en B.52). Una lista blanca "por app" habria sido codigo
muerto.

Arranca en modo simulacion, que no bloquea nada y publica al panel los dominios
que quedaron afuera. Ninguna lista de dominios escrita de antemano puede estar
completa, asi que el catalogo se termina de armar mirando el equipo real en vez
de adivinar.

Precedencia: forzar permitir/prohibir de la seccion DNS le gana a todo; un
bloqueo de la lista blanca corta incluso con el modo apagado; despues la
infraestructura y los dominios permitidos; lo que no matcheo se bloquea.

Cierra la mitad de B.52: la lista de infraestructura (FCM, Play Store y sus CDN,
portal cautivo, hora, OCSP, el propio panel) se aplica con el modo apagado
tambien, asi mtalk.google.com deja de caer bajo el sufijo google.com de la lista
negra global de WebView. Se verifico que no pisa a AdBlocker ni a
GoogleAccountWebPolicy: cero dominios en comun.

Catalogo de 26 apps con sus dominios kosher analizados uno por uno. google.com no
se permite nunca entero (seria la busqueda); translate.google.com queda bloqueado
aunque el Traductor este permitido, porque traduce paginas enteras y funciona
como proxy de navegacion.

Panel: pestana nueva de catalogo global, tarjeta por dispositivo con los tres
interruptores y la auditoria con "Agregar a...". La configuracion no viaja por
FCM (tope de 4 KB, ver B.28): va en globalSettings/whitelist y por FCM va solo el
aviso SYNC_WHITELIST.

Nuevo tools/check_whitelist_sync.py: compara el catalogo Kotlin contra su copia
en el panel y falla si difieren. Correrlo antes de deploy_all.ps1.

Verificado con kotlinc 2.0.21 contra stubs (0 errores / 0 warnings, 14 controles
negativos detectados, con el codigo extraido de los archivos reales) y con 49
aserciones de comportamiento contra el catalogo y el Trie reales. node --check
verde en los dos .js. Sin Gradle y sin probar en equipo.

Co-Authored-By: Claude Opus 5 <noreply@anthropic.com>
Claude-Session: https://claude.ai/code/session_0176AzYdJnc7T6PTGDUSakpc
```

---

## 7. Lo que queda pendiente después de esto

- **`database.rules.json` para `globalSettings/whitelist`** (punto 3-b). Es lo primero a mirar si el catálogo no llega.
- **El catálogo se aplica igual a todos los equipos.** Si algún día hace falta una app permitida en un celular y no en otro, el lugar natural es un `devices/<id>/whitelistOverrides` que se lea después del global.
- **No hay expiración del modo simulación.** Un equipo que quede simulando por olvido es un equipo sin filtro estricto. El panel lo muestra pero no avisa. Mismo riesgo que la suspensión de B.11.
- **Confirmar el paquete del "clima"**: `life.channel.accurate.local.weather.forecast` resultó ser "מרכז המידע" (Centro de información) de Lomdaat, no una app de clima. Si el dueño quería otra, hay que cambiar la entrada del catálogo.
- **Personal Pay** está como `ar.com.personalpay` (el vigente). Existe también `com.telecom.personalpay`, discontinuado; si algún equipo tiene el viejo, hay que agregarlo.
- **Si `SYNC_WHITELIST` funciona bien, es el camino para mover los presets** (`APPLY_PRESET_PROFILE`) fuera del `data` de FCM, que es lo que B.28 dejó pedido.
