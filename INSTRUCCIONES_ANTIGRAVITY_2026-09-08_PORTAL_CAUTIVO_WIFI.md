# Portal cautivo: por qué el guard del 5/9 dejó a un usuario sin Wi-Fi

**Sesión de Claude, 8/9/2026.** Escrito y **type-checkeado con `kotlinc` 2.0.21 (0 errores / 0 warnings,
8 controles negativos, los 8 detectados)**; **sin compilar con Gradle y sin probar en equipo real.**

Va con `LOCKSUITE_CONTEXTO_PARA_IA.md` § **B.50**. Corrige el § **B.46** del 5/9, que quedó mal en
dos afirmaciones concretas — las dos por el mismo motivo: describían la intención, no lo que el
código hace.

---

## 0. El reporte, textual

Mensaje de voz de un usuario de un equipo con LockSuite, 8/9 a la mañana:

> *"¿Cómo estás Isra? Escuchame algo, no sé si es por problema del [equipo] o qué. Estoy en el
> aeropuerto y cuando me conecto al wifi hay [una página] que tengo que aceptar desde la página,
> se me conecta, pero a los dos minutos se desconecta, entonces tengo que entrar, conectar de
> vuelta, todo. Número uno. Número dos: en el avión había wifi gratis, pero como de vuelta tiene
> que entrar por la página. ¿Hay una forma? ¿O no hay?"*

**La fecha importa.** El guard de portal cautivo se escribió el 5/9, se publicó dentro de
**0.6.44 / código 107** el 6/9 y quedó **encendido por defecto**. Este es el primer usuario que pisó
un portal cautivo de verdad desde entonces, y lo reportó dos días después. No es coincidencia.

---

## 1. La causa: el guard le pinta la página de inicio de sesión de negro

Palanca 1 de B.46, textual: *"Tapa las imágenes de esa ventana… Quedan el texto y los formularios:
escribir usuario/contraseña y aceptar términos sigue andando."*

**Es falso.** El camino real, archivo por archivo:

1. `LockSuiteAccessibilityService.handleImageBlocking()` — cuando la ventana del portal está al
   frente, fuerza `mode = "layer1"`.
2. `mode == "layer1"` llama a `runLayer1NodeBlocking()` → `scanNode()`.
3. `scanNode()` tapa entero el primer nodo cuya clase esté en `visualNodeClassNames`… y esa lista es:

```kotlin
private val visualNodeClassNames = setOf(
    "android.widget.ImageView", "android.widget.VideoView",
    "android.view.SurfaceView", "android.view.TextureView", "android.webkit.WebView"
)                                                            ^^^^^^^^^^^^^^^^^^^^^^^
```

4. **El contenido entero de `CaptivePortalLoginActivity` ES un `WebView`** (`R.id.webview`, un
   `WebView` y una barra de progreso: es toda la actividad). O sea que `scanNode` encuentra ese
   nodo, lo tapa entero, y encima **ni siquiera desciende** — el propio comentario lo dice:

```kotlin
// No hace falta descender: el nodo ya quedó tapado entero, y sus hijos
// (por ejemplo las imágenes dentro de un WebView) están debajo del mismo
// recuadro negro.
return
```

**Resultado: un rectángulo negro opaco donde va el formulario de inicio de sesión.** Nadie se
conecta a un rectángulo negro. Es exactamente lo contrario de la condición que puso el dueño
—*"sin bloquear al usuario a que se conecte a la red"*— y explica la pregunta 2 del reporte
("en el avión… ¿hay una forma?"): no la había.

> **La lección, que ya es la cuarta vez en este proyecto (B.22, B.40 p.13, B.45, y ahora esta):
> un informe describió un archivo que dice lo contrario. Reabrir el archivo antes de concluir.**
> Acá alcanzaba con leer `visualNodeClassNames` y ver `android.webkit.WebView` adentro.

## 2. La segunda causa: el tope de 3 minutos no lo hace un portal real

Palanca 3 de B.46: *"Tope duro de 3 minutos (constante, no configurable — decisión del dueño). Un
login legítimo tarda menos de un minuto."*

La premisa es de laboratorio. Un portal de aeropuerto o de avión pide **elegir un plan, aceptar
términos, cargar nombre / mail / número de vuelo / asiento, esperar un código por SMS, mirar un
anuncio**. Tres minutos de reloj de pared se acaban a mitad del trámite. Ahí `closeCaptivePortal()`
manda `GLOBAL_ACTION_HOME` con un cartel, y el usuario tiene que volver a empezar de cero.

**Eso es, palabra por palabra, "a los dos minutos se desconecta, entonces tengo que entrar,
conectar de vuelta, todo".** Para el usuario, "no puedo terminar de entrar al Wi-Fi" y "se me
desconectó el Wi-Fi" son lo mismo.

## 3. Lo que se cambió (8 archivos, ya escritos en disco)

### `mdm/CaptivePortalPolicy.kt`
- `MAX_OPEN_MS` deja de ser el tope de trámite y pasa a ser un **techo absoluto de 15 min**.
- **`IDLE_CLOSE_MS` (3 min) — el tope ahora mide INACTIVIDAD, no tiempo total.** Mientras la
  página cambie —o sea, mientras el usuario toque y escriba— el reloj se reinicia. Lo que este
  tope tiene que matar es una ventana **abandonada** o usada de visor, y eso lo sigue matando
  igual: tres minutos sin que la página cambie ni una vez no es alguien iniciando sesión.
- **`KEY_COVER_IMAGES = "captive_portal_cover_images"`**, encendido por defecto: interruptor
  aparte solo para el tapado.
- `WEB_IMAGE_CLASS_NAMES` / `WEB_CONTAINER_CLASS`, con el porqué escrito arriba.

### `service/LockSuiteAccessibilityService.kt`
- **`scanNode()` con `captivePortalScan`:** en esa ventana el `WebView` **no** se tapa, se baja
  por sus hijos, y se tapan solo los nodos de imagen de verdad — **`android.widget.Image`**, que
  es como WebView expone un `<img>` de HTML (⚠️ `ImageView` es la de las vistas nativas: bajar
  con la lista vieja no habría tapado **nada**, y el bloqueo quedaría anunciado y sin efecto,
  que es la forma de bug de B.45 y B.47) — **salvo las que además son un control**
  (`isClickable` / `isEditable`). El botón de "Conectar"/"Aceptar", el captcha y las tarjetas de
  plan de los portales de avión son imágenes clicables: taparlas es impedir el login. Una foto de
  contenido no es clicable y se sigue tapando. El flag se restaura en un `finally`, así que no
  puede quedar pegado y afectar el escaneo de otra app.
- `captiveLastActivityAt`, alimentado por los `TYPE_WINDOW_CONTENT_CHANGED` de esa ventana —
  **antes del antirrebote de CONTENT_CHANGED**, para que el antirrebote no se coma justo los
  eventos que prueban que el usuario está ahí.
- Contador `captive_portal_forced_closes` + `captive_portal_last_close_reason`.
- Nota en el tick: **Android ya cierra esa ventana solo** al validar la red
  (`CaptivePortalLoginActivity` registra un `NetworkCallback` y en `onCapabilitiesChanged`, con
  `NET_CAPABILITY_VALIDATED`, llama a `done(Result.DISMISSED)`). La palanca 2 es un respaldo, no
  el mecanismo principal — **no agregarle agresividad.**

### `mdm/PolicyManager.kt`, `service/LockSuiteFirebaseService.kt`, `util/FirebaseDeviceSync.kt`
- `isCaptivePortalCoverImagesEnabled()` / `setCaptivePortalCoverImages()`.
- Comandos **`ENABLE_CAPTIVE_PORTAL_IMAGES` / `DISABLE_CAPTIVE_PORTAL_IMAGES`**.
- Al panel viajan `captivePortalCoverImages`, `captivePortalForcedCloses` y
  `captivePortalLastCloseReason`.

### `admin-backend/functions/index.js`, `public/app.js`, `public/index.html`
- Los dos comandos en `ALLOWED_COMMANDS` y el interruptor nuevo en el panel (equipo y grupo).

---

## 4. ⚠️ Mientras tanto, sin compilar nada: cómo destrabar al usuario HOY

Desde el panel, sobre ese equipo:

1. **Apagar "Vigilar la ventana de inicio de sesión de Wi-Fi (portal cautivo)"**
   (`DISABLE_CAPTIVE_PORTAL_GUARD`). Con eso la ventana del portal vuelve a funcionar como
   siempre, hoy, sin actualizar la app.
2. Cuando vuelva y se despliegue esta versión, volver a encenderlo. Si algún portal sigue dando
   problemas, ahora alcanza con apagar **solo** el tapado de imágenes
   (`DISABLE_CAPTIVE_PORTAL_IMAGES`) y el resto del guard sigue trabajando.

**Y la verificación de que el diagnóstico es este y no otro, que no cuesta nada:** mirar en el
panel `captivePortalOpens` de ese equipo. Si el número es alto, el usuario abrió esa ventana una y
otra vez — que es lo que hace alguien peleando con un login que no se deja completar.

---

## 5. Orden de prueba en equipo real

**La 1 es la que prueba el arreglo. La 3 es la regresión a vigilar.**

0. **Que no se rompió nada.** Con el guard encendido, uso normal media hora: el tapado de imágenes
   de las apps donde está activo sigue funcionando igual, y no aparecen recuadros negros donde no
   había. `captivePortalScan` solo se prende dentro de la ventana del portal, pero esto se mira
   igual porque el cambio toca `scanNode()`, que es camino compartido.
1. **Un portal cautivo de verdad** (el Wi-Fi de un bar, un shopping o un aeropuerto sirve; no hace
   falta viajar): **la página tiene que verse y el login tiene que poder completarse.** Antes de
   este cambio se veía un rectángulo negro. Es la prueba de la causa.
2. **El tope de inactividad:** abrir la ventana del portal, tocar y escribir durante más de 3
   minutos seguidos → **no se tiene que cerrar**. Después dejarla quieta 3 minutos → se tiene que
   cerrar con el cartel "la ventana quedó abierta sin usarse".
3. **⚠️ REGRESIÓN A VIGILAR — que el bloqueo siga bloqueando.** En el mismo portal, si la página
   tiene fotos de contenido (banners, publicidad), **esas se tienen que seguir tapando**. Si se ve
   todo, el escaneo no está encontrando los nodos `android.widget.Image` y el bloqueo quedó
   anunciado y sin efecto — exactamente el bug de B.45/B.47. En ese caso el dato para calibrar es
   `adb logcat -s LockSuiteA11y`, y la clase a agregar a `WEB_IMAGE_CLASS_NAMES` sale de ahí.
4. **Cerrar al validar:** completar el login → la ventana se tiene que cerrar sola (la cierra
   Android, y si no el guard) y el equipo queda navegando con el filtro puesto.
5. **El panel:** `captivePortalCoverImages` en verdadero, `captivePortalForcedCloses` en 0 después
   de una sesión de login normal, `captivePortalOpens` subiendo de a uno.
6. **El interruptor nuevo:** `DISABLE_CAPTIVE_PORTAL_IMAGES` desde el panel → la ventana del
   portal deja de tener nada tapado, **y el resto del guard sigue** (se cierra al validar, tope de
   inactividad). `ENABLE_...` lo vuelve a poner.

---

## 6. Mensaje de commit, listo para copiar

```
fix(portal cautivo): la ventana de inicio de sesion de Wi-Fi quedaba pintada de negro y el login no se podia completar

El guard del 5/9 (B.46) reusa el tapado de imagenes de Capa 1 para la ventana
de "Iniciar sesion en la red". Pero `visualNodeClassNames` incluye
`android.webkit.WebView`, y el contenido entero de esa ventana ES un WebView:
`scanNode()` lo encontraba, lo tapaba entero y ni siquiera descendia. La pagina
del portal quedaba como un rectangulo negro y el usuario no se podia conectar,
que es justo lo contrario de la condicion del pedido original ("sin bloquear al
usuario a que se conecte a la red"). Reportado por un usuario desde un
aeropuerto y desde un avion, dos dias despues de publicar 0.6.44.

Segundo defecto del mismo punto: el tope de 3 minutos contaba tiempo de reloj.
Un portal de aeropuerto o de avion pide elegir plan, aceptar terminos, cargar
datos y esperar un codigo por SMS; a los 3 minutos el guard mandaba HOME a
mitad del tramite y habia que empezar de cero ("se desconecta, tengo que entrar,
conectar de vuelta, todo").

- CaptivePortalPolicy: IDLE_CLOSE_MS (3 min de INACTIVIDAD) reemplaza al tope de
  tiempo total; MAX_OPEN_MS pasa a ser un techo absoluto de 15 min; interruptor
  nuevo captive_portal_cover_images y las clases de nodo del WebView.
- LockSuiteAccessibilityService: scanNode con captivePortalScan — en esa ventana
  el WebView no se tapa, se desciende, y se tapan solo los nodos
  android.widget.Image (asi expone WebView un <img>) que NO sean controles
  clicables o editables: el boton de conectar y el captcha son imagenes. Reloj de
  inactividad alimentado por los CONTENT_CHANGED de la ventana. Contador de
  cierres forzados.
- PolicyManager / LockSuiteFirebaseService / FirebaseDeviceSync: interruptor
  nuevo, comandos ENABLE/DISABLE_CAPTIVE_PORTAL_IMAGES y los campos nuevos al
  panel (captivePortalCoverImages, captivePortalForcedCloses,
  captivePortalLastCloseReason).
- admin-backend: los dos comandos en ALLOWED_COMMANDS y el interruptor en el
  panel, por equipo y por grupo.

Verificado con kotlinc 2.0.21 contra stubs, con el codigo extraido del archivo
real: 0 errores / 0 warnings, 8 controles negativos y los 8 detectados. Sin
Gradle y sin probar en equipo: el orden de prueba esta en
INSTRUCCIONES_ANTIGRAVITY_2026-09-08_PORTAL_CAUTIVO_WIFI.md (la 1 prueba el
arreglo, la 3 es la regresion a vigilar).
```

⚠️ Antes de correr `deploy_all.ps1` —que hace `git add .`— sacar del árbol los
`scratch/diag_red_2026-09-06*.txt` o agregarlos al `.gitignore`: son volcados de red del celular
real del dueño (ver B.32).
