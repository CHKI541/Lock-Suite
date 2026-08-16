# Informe de optimización — Capa 3 (Servicio de Accesibilidad)

**Fecha:** 16 de agosto de 2026
**Autor:** sesión de Claude vía puente al dispositivo (sin SDK, sin compilar en Gradle, sin equipo real)
**Archivos tocados:** 2
**Objetivo pedido:** que el servicio sea rápido, consuma poco, tape exactamente lo que tiene que tapar en el momento — ni de más ni de menos — y se sienta liviano.

---

## 0. Estado de verificación (leer esto primero)

| Verificación | Estado |
|---|---|
| Parseo de Kotlin (kotlinc 2.0.21) | ✅ limpio, con control negativo que confirma que el chequeo detecta errores reales |
| Type-check contra stubs de la API de Android | ✅ 0 errores, 0 warnings en los dos archivos |
| `./gradlew compileDebugKotlin` | ❌ **NO CORRIDO** — no hay SDK ni Gradle en este entorno |
| Instalado y probado en equipo real | ❌ **NO PROBADO** |

El type-check se hizo escribiendo stubs mínimos de `AccessibilityService`, `AccessibilityNodeInfo`, `WindowManager`, `PowerManager`, `SharedPreferences`, etc., con la nulabilidad del SDK real, y compilando los dos archivos contra ellos. Eso da bastante confianza en firmas, tipos y nulabilidad, pero **no reemplaza compilar de verdad**. Nada de este informe está cerrado hasta que corra en un Android 13 real.

Backups del código anterior, por si hay que volver atrás:

```
_to_delete/LockSuiteAccessibilityService.kt.pre-optimizacion-2026-08-16
_to_delete/BlockOverlayManager.kt.pre-optimizacion-2026-08-16
```

---

## 1. Por qué el servicio se sentía pesado

`onAccessibilityEvent` corre **en el hilo principal** y el sistema lo llama hasta **10 veces por segundo** (`notificationTimeout = 100`) mientras la pantalla está encendida. Todo lo que estaba en el camino directo del evento se pagaba multiplicado por diez, cada segundo. Y había bastante.

El hallazgo más caro, con diferencia:

> `trackPackage()` se ejecutaba en cada evento y llamaba a `isBrowserPackage()`, que para cualquier paquete fuera de la lista fija hacía un **`packageManager.queryIntentActivities(MATCH_ALL)`** — una llamada IPC al `PackageManager` que enumera **todas** las actividades del equipo capaces de abrir `https`. Es del orden de milisegundos. Diez veces por segundo, en el hilo principal, todo el tiempo que el celular estaba en uso.

Eso solo ya explica buena parte de la sensación de lentitud general del equipo, no solo del servicio.

El segundo hallazgo, el que explica el parpadeo:

> La clave de cada overlay de Capa 1 era `"layer1:<clase>:<x>,<y>"` — **incluía la posición**. Al hacer scroll, cada imagen cambiaba de posición → cambiaba de clave → el sistema consideraba el overlay viejo "obsoleto", lo **destruía** (`removeView`) y **creaba uno nuevo** (`addView`) en la posición nueva. `addView`/`removeView` son las operaciones más caras del `WindowManager`. Ese ciclo, repetido en cada escaneo mientras se desplaza la pantalla, es exactamente lo que se veía como parpadeo y se sentía pesado.

---

## 2. Cambios en `LockSuiteAccessibilityService.kt`

### 2.1 — Cache de navegadores (el cambio de mayor impacto)

**Antes:** un `queryIntentActivities(MATCH_ALL)` por paquete desconocido, en cada evento.
**Ahora:** una sola consulta que trae el conjunto completo de navegadores, cacheada 10 minutos (`BROWSER_CACHE_TTL_MS`). La comprobación por evento pasa a ser un lookup O(1) en un `HashSet`.

De ~10 llamadas IPC por segundo a **1 cada 10 minutos**.

El TTL existe para que un navegador recién instalado se detecte solo. Si querés que sea inmediato, hay que enganchar un `PackageReceiver` que invalide `browserPkgCacheAt = 0L` — no lo hice para no meter mano en `PackageReceiver`, que se tocó hace dos días por B.9.

### 2.2 — Una sola instancia de `PolicyManager`

**Antes:** `PolicyManager(applicationContext)` se construía de nuevo en cada evento y en cada runnable de escaneo — hasta cuatro por evento. Su constructor resuelve el `DevicePolicyManager` y arma un `ComponentName`.
**Ahora:** una instancia `by lazy`.

Esto **no congela la configuración**: `PolicyManager` lee las `SharedPreferences` en cada getter, así que sigue viendo los cambios al instante. Lo que se dejó de tirar a la basura son decenas de objetos por segundo.

Lo mismo con `getSystemService(POWER_SERVICE)`, que también se llamaba por evento y ahora es un campo `by lazy`.

### 2.3 — Snapshot de configuración con invalidación por listener

**Antes:** entre 3 y 8 lecturas de `SharedPreferences` por evento.
**Ahora:** una clase `Flags` con todos los booleanos, leída una sola vez y guardada. Se invalida sola por `OnSharedPreferenceChangeListener` (así que un cambio desde el panel o el dashboard se ve al instante) y, como red de seguridad, se vuelve a leer si tiene más de 3 segundos.

⚠️ El listener se guarda en un **campo** a propósito: `SharedPreferences` mantiene los listeners con referencias débiles, y si no lo retenemos el recolector se lo lleva y dejamos de enterarnos de los cambios. No convertirlo en una lambda local.

### 2.4 — Orden del camino caliente

Se adelantó el filtro por tipo de evento (una comparación de enteros) por delante de todo lo que toca prefs o PackageManager. Y las funciones de WhatsApp / Mercado Pago ahora se llaman **solo si su flag está encendido**, en vez de entrar siempre y decidir adentro.

### 2.5 — Topes en todos los recorridos del árbol

`scanNode`, `checkNodeTreeForOffers` y `searchNodeByText` recorrían el árbol completo **sin límite de profundidad**. Ahora todos llevan `MAX_TREE_DEPTH = 40` y un presupuesto compartido de `MAX_NODES_PER_SCAN = 2500` nodos por escaneo. En una pantalla patológica el servicio degrada en vez de trabar el hilo principal.

Además, `scanNode` **ya no desciende dentro de un nodo que acaba de tapar**: si un `WebView` quedó cubierto entero por un rectángulo negro, recorrer sus hijos no aporta nada. En pantallas con contenido web eso recorta buena parte del árbol.

### 2.6 — Anti-evasión de Ajustes: un recorrido en vez de dos

**Antes:** un recorrido completo buscando "locksuite" y, si daba positivo, **otro recorrido completo** buscando las 17 acciones peligrosas.
**Ahora:** un solo recorrido que junta las dos señales y corta apenas tiene ambas.

Mismo comportamiento, la mitad del trabajo.

### 2.7 — WebView: dejar de re-armar la escalera de reintentos

Los tres reintentos diferidos (250 / 800 / 1800 ms) existen para atrapar WebViews que cargan tarde. **Antes** se cancelaban y se volvían a armar en **cada** evento: mientras la pantalla tuviera actividad, se rearmaban tres recorridos completos del árbol una y otra vez.
**Ahora** se arman cuando cambia la ventana (`TYPE_WINDOW_STATE_CHANGED`), o como mucho una vez cada 2 segundos por paquete. La detección tardía se conserva.

### 2.8 — Guardas de rebote separadas por función

**Antes** había **una sola** `blockInProgress` compartida entre WebView, WhatsApp y Mercado Pago. Un bloqueo de WhatsApp en curso hacía que se **perdiera en silencio** un bloqueo de Mercado Pago que ocurriera en esos 700 ms.
**Ahora** hay tres: `webViewBlockInProgress`, `waBlockInProgress`, `mpBlockInProgress`.

Esto es una corrección de comportamiento, no solo de rendimiento: antes había una ventana real de 700 ms en la que un bloqueo se caía.

### 2.9 — Logs fuera del camino caliente

`Log.d(TAG, "EVENT pkg=$packageName type=${...} stack=${appPackageStack.toList()}")` armaba un string y **copiaba una lista entera** en cada evento, incluso en release.

Ahora está detrás de `if (VERBOSE)`, con `private const val VERBOSE = false`. Al ser una constante de compilación, R8 elimina la rama **completa** en la build de release — no queda ni el `if` ni el string. Los logs de eventos que sí importan (bloqueos efectivos, errores) siguen activos.

> Para diagnosticar en equipo real: poner `VERBOSE = true`, compilar, y volver a `false` antes de publicar.

### 2.10 — Correcciones menores de fugas y carreras

- **Wake lock de actualización:** se creaba uno **nuevo** cada vez que llegaba un evento con la pantalla apagada y nunca se liberaba a mano — quedaban wake locks apilados venciendo solos a los 15 s. Ahora hay uno solo, reutilizado, y se libera en `finishUpdateAndLock()` y en `onDestroy()`.
- **Carrera en `onServiceConnected`:** se publicaba `instance = this` **antes** de inicializar `overlayManager`. `PackageReceiver` hace `instance?.overlayManager`, así que había una ventana real en la que ese acceso reventaba con `UninitializedPropertyAccessException`. Ahora `overlayManager` se inicializa primero.
- **`onDestroy`:** ahora también desregistra el listener de prefs y cancela los runnables de WhatsApp y Mercado Pago, que antes quedaban colgados en el `Handler`.
- **`isSystemOrInputPackage`** hacía un `lowercase()` (que asigna un String nuevo) más ocho `contains()` en cada evento. Ahora está memorizado por paquete.
- **`scanForUpdatesTab`** hacía las seis búsquedas `findAccessibilityNodeInfosByText` siempre; ahora corta apenas una da positivo.

---

## 3. Mercado Pago: el bloqueo tapaba de más

Esto no era rendimiento, era un bug de comportamiento, y es el que más se nota.

### El problema

La detección era una sola lista de 14 palabras sueltas, y bastaba con que **cualquiera** apareciera en **cualquier** nodo de la pantalla:

```kotlin
"oferta", "ofertas", "promocion", "promociones", "descuento", "descuentos",
"cupon", "cupones", "beneficio", "beneficios", "mercado puntos", "recompensa",
"novedades y ofertas", "supermercado"
```

Palabras como **"beneficio"**, **"descuento"** o **"supermercado"** están en la **pantalla de inicio** de Mercado Pago y en varios flujos de pago. Resultado: el servicio expulsaba al usuario de pantallas perfectamente legítimas.

Peor todavía, esto:

```kotlin
if (className.contains("WebkitPageActivity") || className.contains("mlwebkit")) return true
```

hacía que **cualquier** pantalla web de Mercado Pago — incluidos pagos, ayuda y comprobantes — se considerara "ofertas".

Y el rebote era un `GLOBAL_ACTION_BACK` **a ciegas**: no se verificaba si había servido. Como el escaneo vuelve a los 350 ms, si el "atrás" no salía de la sección se **encadenaban rebotes** hasta sacar al usuario de Mercado Pago por completo.

También había una inconsistencia: el punto de entrada consultaba `isMercadoPagoBlockOffersAccessibilityEnabled()` pero el runnable diferido consultaba `isMercadoPagoBlockOffersEnabled()`, que es *accesibilidad **O** VPN*. Con solo el bloqueo por VPN encendido, el runnable igual rebotaba al usuario.

### La solución

Tres niveles de señal, en vez de una lista plana:

| Nivel | Qué es | Cuánto hace falta |
|---|---|---|
| **View ID** | identificadores de vista propios de la app (`offers`, `loyalty`, `mercadopuntos`…) | uno solo |
| **Fuerte** | títulos de sección completos (`"novedades y ofertas"`, `"mercado puntos"`, `"tus beneficios"`…) | uno solo |
| **Débil** | palabras sueltas (`"descuento"`, `"beneficios"`, `"puntos"`…) | **dos distintas** |
| **Débil + WebView** | pantalla web de MP, donde casi no hay texto accesible | **una** (red de seguridad) |

Además:

- **Coincidencia por palabra completa**, no por substring. Antes `"puntos"` matcheaba dentro de cualquier palabra que la contuviera.
- **Normalización de tildes** (`foldAccents`). Esto arregla un bug silencioso: `"promoción"` **nunca** coincidía con la palabra `"promocion"` de la lista, porque la comparación era substring literal. Media lista estaba muerta. Se evita `java.text.Normalizer` a propósito: es bastante más caro y esto corre sobre cada texto de cada nodo.
- **Rebote verificado con backoff:** un "atrás" → se verifica → un segundo intento → recién entonces HOME → y una pausa de 4 segundos para que no quede girando.
- **Se unificó el flag** en `isMercadoPagoBlockOffersAccessibilityEnabled()` en los dos caminos.

⚠️ **Esto cambia qué se bloquea, no solo qué tan rápido.** Es el cambio que más hay que mirar en el equipo real (checklist abajo). Si en la prueba se escapa alguna pantalla de ofertas, el ajuste es agregar su título a `MP_OFFERS_STRONG` o su id a `MP_OFFERS_VIEW_ID_HINTS` — **no** volver a la lista plana.

> Todas las cadenas de esas listas van en minúscula y **sin tildes**: el texto de pantalla se normaliza antes de comparar. Una cadena con tilde nunca coincidiría.

Vale recordar que la Capa 2 (VPN/DNS) bloquea los dominios de ofertas en paralelo cuando `mp_offers_vpn` está encendido, así que la accesibilidad no es la única red.

---

## 4. Cambios en `BlockOverlayManager.kt`

### 4.1 — Identidad estable: mover en vez de recrear

La clave de cada región ahora es la **ruta del nodo en el árbol** (`layer1:0.3.1.2.`) en lugar de su posición en pantalla. Mientras se hace scroll, `RecyclerView` reutiliza las vistas, así que la ruta se mantiene y el overlay simplemente **se mueve** (`updateViewLayout`) en vez de destruirse y volver a crearse.

### 4.2 — No repetir el IPC si nada se movió

Se guarda el último rectángulo aplicado a cada overlay y se omite `updateViewLayout` si no cambió. Antes se llamaba en cada escaneo aunque la región estuviera quieta: con 20 imágenes en pantalla, eso eran 20 llamadas al `WindowManager` tres veces por segundo, todas para no cambiar nada.

### 4.3 — Período de gracia al quitar (esto es lo del parpadeo)

Si un escaneo puntual no encuentra un nodo que **sí sigue en pantalla** — pasa seguido durante scroll o animaciones — antes el overlay se destruía y se volvía a crear al escaneo siguiente. Eso se ve como parpadeo y **deja la imagen destapada uno o dos frames**.

Ahora una región que desaparece queda "en observación" 350 ms y solo se destruye si sigue ausente al vencerse ese plazo. Si reaparece, se rescata sin haber parpadeado.

### 4.4 — Aparecer en el mismo frame

Los eventos de accesibilidad llegan **en el hilo principal**, pero el manager encolaba todo con `Handler.post`, perdiendo un frame completo (~16 ms) de más en cada tapado. Ahora, si ya estamos en el hilo principal, se ejecuta en el acto. Desde `bgExecutor` (Capa 2 / IA) se sigue encolando, como corresponde.

### 4.5 — No hacer nada cuando no hay nada que hacer

`handleImageBlocking` llamaba a `clearStaleRegions` **siempre**, encolando un `Runnable` en el hilo principal diez veces por segundo aunque el bloqueo de imágenes estuviera apagado en todas las apps. Ahora hay `hasRegions(prefix)` y solo se llama si hay algo que limpiar.

### 4.6 — Coordenadas absolutas (revisar visualmente)

A los overlays de región se les agregó `FLAG_LAYOUT_IN_SCREEN | FLAG_LAYOUT_NO_LIMITS`, que es el par correcto para que las coordenadas coincidan con las que devuelve `getBoundsInScreen()`.

⚠️ **Este es el cambio con más riesgo visual del informe.** Sin esos flags, el `y` de la ventana puede medirse desde debajo de la barra de estado, lo que da un corrimiento sistemático del alto de la barra. Con ellos debería quedar exacto. **Hay que mirarlo en pantalla**: si los recuadros negros aparecen corridos hacia arriba o hacia abajo respecto de las imágenes, sacar esos dos flags y avisar — el resto de los cambios es independiente de este.

---

## 5. Qué NO toqué, a propósito

- **`handlePlayStoreAutoUpdate` y su `scanNodes` interno.** Es el código de B.9, arreglado el 14/8 y **todavía sin probar en equipo real**. Tiene un `recycle()` que se sacó a propósito porque rompía el clic en Android 11/12 con `IllegalStateException`. Meterme ahí para ahorrar unos objetos no vale el riesgo de reintroducir ese bug. Lo único que cambié son los `Log.d` de diagnóstico (ahora detrás de `VERBOSE`) y el uso de la constante `PKG_PLAY_STORE`.
  - Queda pendiente, documentado y **deliberadamente sin hacer**: el bucle `for (window in windows)` no recicla los roots de las ventanas que no son Play Store. Es una fuga menor y acotada (solo durante una actualización, que dura poco).
- **`notificationTimeout = 100`.** Subirlo a 150–200 ms bajaría la carga de forma notable, pero también retrasaría el "tapar en el momento". Lo dejé como estaba porque preferí bajar el costo **por evento** antes que recibir menos eventos. Queda como perilla si después de probar todavía se siente pesado.
- **`FLAG_INCLUDE_NOT_IMPORTANT_VIEWS`.** Aumenta bastante el tamaño del árbol que el sistema construye, pero hace falta para encontrar WebViews e imágenes que no están marcadas como importantes. No lo toqué.
- **El Manifest.** El fix de B.8 (`android.accessibilityservice.AccessibilityService`) ya está bien puesto, lo verifiqué en la línea 97.

---

## 6. Checklist de prueba en equipo real

Lo primero, en el Android 13 (QEMAY-QM01), porque sin esto nada de lo demás corre:

```bash
adb shell cmd package query-services -a android.accessibilityservice.AccessibilityService
adb shell dumpsys accessibility | grep -E "installedServiceCount|Bound services"
```

Tiene que aparecer `LockSuiteAccessibilityService` y `installedServiceCount` distinto de 0 (esto es B.8, que sigue sin confirmarse).

Después, por orden de importancia:

1. **Mercado Pago — que NO tape de más (lo más importante).** Abrir MP y quedarse en el **inicio**. No tiene que rebotar. Entrar a un **flujo de pago / escanear QR** y navegarlo. No tiene que rebotar. Antes de este cambio, las dos cosas rebotaban.
2. **Mercado Pago — que SÍ tape lo que debe.** Entrar a la sección de ofertas / promociones / Mercado Puntos. Tiene que salir con un solo "atrás", sin cerrar la app entera y sin quedar rebotando.
3. **Bloqueo de imágenes Capa 1 — alineación.** Con Capa 1 activa en alguna app, mirar que los recuadros negros queden **exactamente** encima de las imágenes, sin corrimiento vertical (ver §4.6).
4. **Bloqueo de imágenes Capa 1 — scroll.** Desplazar una lista larga con imágenes. Los recuadros tienen que **acompañar** el scroll sin parpadear y sin dejar la imagen destapada. Este es el cambio que debería notarse más "liviano".
5. **WhatsApp.** Estados y Canales siguen bloqueándose; el rebote a la pestaña Chats sigue funcionando.
6. **WebView.** Confirmar que el bloqueo de navegador interno sigue andando en una app con WebView bloqueado (el punto de B.1 que "antes de ese cambio no bloqueaba").
7. **Evasión de Ajustes.** Ir a Ajustes → Apps → LockSuite. Al aparecer "Desinstalar" / "Forzar detención" tiene que rebotar y abrir el login.
8. **Batería / fluidez.** Usar el equipo un rato normal y comparar la sensación general con la versión anterior. El cambio de §2.1 debería notarse en la fluidez del sistema entero, no solo de la app.
9. **Actualización por Play Store (B.9).** Como toqué el archivo, conviene revalidar el flujo de `UPDATE_APP` de punta a punta aunque la lógica quedó igual.

---

## 7. Resumen de costo por evento

| Operación (por evento de accesibilidad) | Antes | Ahora |
|---|---|---|
| `queryIntentActivities` al PackageManager | hasta 1 | 0 (1 cada 10 min) |
| Instancias de `PolicyManager` creadas | hasta 4 | 0 |
| `getSystemService` | 1–2 | 0 |
| Lecturas de `SharedPreferences` | 3–8 | 0 (snapshot) |
| Strings de log armados en release | 1+ | 0 |
| `lowercase()` de nombre de paquete | 1 | 0 (memorizado) |
| Recorridos completos del árbol (Ajustes) | 2 | 1 |
| Recorridos del árbol sin tope de profundidad | todos | ninguno |
| `Runnable` encolados sin necesidad | 2 | 0 |
| `addView`/`removeView` por scroll y por imagen | 1 cada uno | 0 (solo `updateViewLayout`, y solo si se movió) |

---

## 8. Nota para la próxima sesión

Hay un bloque de comentario al principio de `LockSuiteAccessibilityService.kt` con las reglas del camino caliente. Si alguien va a agregar una función nueva al servicio, conviene leerlo antes: casi todos los problemas de este informe vinieron de agregar algo razonable en sí mismo, sin tener presente que ese lugar se ejecuta diez veces por segundo.
