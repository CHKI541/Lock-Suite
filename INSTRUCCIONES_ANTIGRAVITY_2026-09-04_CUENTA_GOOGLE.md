# Ajustes de la cuenta de Google: el agujero, el interruptor, y los que faltan

**Sesión de Claude, 4/9/2026.** Escrito y type-checkeado; **sin compilar con Gradle y sin probar en equipo real.**

Leer con `LOCKSUITE_CONTEXTO_PARA_IA.md` § **B.43** al lado. Este documento tiene el detalle:
el porqué de cada decisión, la auditoría de agujeros del mismo tipo, el orden de prueba en
equipo real y el mensaje de commit listo para copiar.

---

## 1. Qué encontró el dueño

Textual: *"estuve viendo los ajustes de mi cuenta de Google en mi celular, y al entrar a
ajustes de privacidad logré entrar a mi historial de YouTube y ver los videos, eso afecta a
que no sea tan kosher."*

El camino, entero, **sin ningún navegador de por medio**:

```
Ajustes → Google → Gestionar tu cuenta de Google
       → Datos y privacidad → Configuración del historial
       → Historial de YouTube → "Administrar toda la actividad"
       → lista de videos vistos, con título y MINIATURA
```

Y por el mismo camino, a un toque de distancia:

| Vecino | Qué muestra |
|---|---|
| **Actividad web y de aplicaciones** | TODAS las búsquedas que hizo el usuario, y las deja repetir con un toque |
| **Cronología** (historial de ubicaciones) | dónde estuvo, con fotos del lugar |
| **Descargar tus datos** (Takeout) | exporta y muestra el contenido de la cuenta |
| **Configuración de anuncios** | intereses inferidos, con imágenes |
| **Tus fotos** | Google Fotos |

---

## 2. Por qué NINGUNA de las tres capas lo veía

Esto es lo importante del hallazgo: **no es un descuido puntual, es un hueco de forma.**

- **Capa 1 (DevicePolicyManager).** Android **no tiene** ninguna restricción `DISALLOW_*`
  para "ver los ajustes de la cuenta de Google". Y un Device Owner **no puede deshabilitar
  una Activity de otra app**: `setComponentEnabledSetting()` solo rige sobre el propio
  paquete. `DISALLOW_MODIFY_ACCOUNTS` impide AGREGAR o QUITAR cuentas — no impide VERLAS.
  **Por Capa 1 esto no se cierra, y hay que decirlo así.**
- **Capa 2 (DNS).** El filtro bloquea un dominio solo si hay una regla para él, y nadie
  había puesto reglas para `myaccount.google.com` / `myactivity.google.com`. La lista negra
  de WebView (`WebViewPolicy.GLOBAL_BLACKLIST`, que sí tiene `youtube.com`) **solo se aplica
  a apps con el bloqueo de WebView encendido a mano**, y `com.google.android.gms` no lo
  tiene — ni conviene que lo tenga, porque rompería el inicio de sesión.
- **Capa 3 (Accesibilidad).** Es una lista de apps **conocidas**: WhatsApp, Mercado Pago,
  Play Store. Google Play services nunca estuvo en esa lista.

> **La lección generalizable, que vale más que el parche:** el contenido web no llega solo
> por un navegador. Llega por **cualquier app del sistema que tenga un WebView adentro y una
> página de Google del otro lado.** Play services es la más grande, no la única.

---

## 3. Lo que se hizo: un interruptor, dos capas

**Interruptor `block_google_account_web` — "Bloquear ajustes de la cuenta de Google
(historial y actividad)". ENCENDIDO POR DEFECTO.** Comandos FCM
`BLOCK_GOOGLE_ACCOUNT_WEB` / `UNBLOCK_GOOGLE_ACCOUNT_WEB`. Está en la app (Políticas →
Protecciones de Accesibilidad, primera fila) y en el panel, por dispositivo y por grupo.

### Por qué encendido por defecto, si todos los otros vienen apagados

Los interruptores de la sección vienen apagados porque cada uno cuesta comodidad y el
administrador elige cuánto pagar. Este es distinto por dos motivos:

1. Lo que abre **no es una función del equipo que alguien pueda querer**: es el historial de
   videos y de búsquedas del propio usuario, servido dentro de Ajustes. En un equipo kosher
   no tiene ningún uso legítimo.
2. Un equipo que se actualiza y no se vuelve a configurar quedaría con el agujero abierto.
   **Un interruptor apagado por defecto solo protege a quien se acuerde de encenderlo.**

El costo es bajo y acotado: el administrador pierde poder abrir "Gestionar tu cuenta de
Google" **desde el propio equipo**. Desde una PC sigue igual.

### Capa 3 — rebote por nombre de clase de la ventana

`mdm/GoogleAccountWebPolicy.kt` (archivo nuevo) + `handleGoogleAccountWebBounce()` en
`LockSuiteAccessibilityService.kt`.

**No se compara ni un solo texto de pantalla.** Es la regla de B.19 punto 3: *si hay una
señal estructural, usarla antes que una palabra*. Cambiar el idioma del equipo no evade nada
de esto.

Se eligió el **segmento de paquete del módulo** y no el nombre de la Activity concreta:
`com.google.android.gms.accountsettings.mg.ui.main.MainActivity` es el nombre de hoy, pero
`accountsettings` es lo que identifica al módulo y sobrevive a que Google reorganice las
clases de adentro. Marcadores: `accountsettings`, `googlesettings`, `adssettings`,
`usagereporting`, `myaccount`, `myactivity`.

**Exclusiones, que son la parte delicada:** el flujo de AGREGAR una cuenta de Google es un
WebView de Play services (`...auth.uiflows.minutemaid.MinuteMaidActivity` renderiza
accounts.google.com). Si el rebote disparara ahí, **el equipo no se podría dar de alta** — y
LockSuite se instala justamente con el celular sin cuenta de Google, así que ese flujo es
parte del procedimiento normal de instalación. Por eso `minutemaid`, `signin`, `auth.uiflows`,
`authzen`, `setupwizard` y `consent` nunca rebotan. Es la misma clase de error que el bug 3
de B.41 ("Iniciar sesión" leído como "Abrir").

El rebote es `BACK` verificado a los 700 ms, y **solo escala a HOME si seguimos dentro de
Play services**. Si el "atrás" devolvió a Ajustes, funcionó: sacar al usuario de Ajustes
entero fue exactamente el sobre-bloqueo de B.15 punto 1. Hay backoff de 4 s y no rebota con
sesión de administrador abierta.

### Capa 2 — bloqueo de los dominios (es la que de verdad cierra)

En `KosherVpnService`, junto a anuncios y GIFs, como **lista negra GLOBAL** — no como regla
por app, porque las consultas DNS de Android salen por `netd` y no se pueden atribuir a la
app que las pidió (B.10).

```
myaccount.google.com   myactivity.google.com   history.google.com
takeout.google.com     timeline.google.com     adssettings.google.com
photos.google.com      ytimg.com
```

`ytimg.com` es solo imágenes de YouTube, así que bloquearlo no puede romper otra cosa y de
paso mata las miniaturas dentro de cualquier otro WebView.

**Lo que NO está en la lista, y no hay que agregar sin pensarlo dos veces:**
`accounts.google.com` (sin eso no se da de alta el equipo ni entra `:admin-app`),
`play.google.com` y `android.clients.google.com` (Play Store y actualizaciones),
`mtalk.google.com` y `fcm.googleapis.com` (**FCM es el canal de comandos del panel:
bloquearlo deja el equipo inadministrable**), `googleapis.com` / `gstatic.com` /
`googleusercontent.com` (infraestructura compartida).

El bloque va **después** del `when(customRule)`, así que una regla **FORCE_ALLOW** del
administrador sigue ganando: es la vía de escape si alguna vez hace falta abrir la cuenta
desde el propio equipo.

### Diagnóstico, para no adivinar en el próximo equipo

Play services reparte sus pantallas en módulos que se actualizan solos (Chimera), así que el
nombre de clase de hoy puede no ser el de mañana. El servicio publica al panel
`googleAccountWebSeenClasses`: **las clases de Play services que vio y no supo clasificar.**
Si en un equipo el rebote no dispara, ahí está el dato exacto para agregar. Es la misma idea
que `debugLabels` de B.41 — sin eso, cada equipo nuevo es una sesión entera de adivinanza.

### Límites, dichos sin adornos

1. El filtro DNS solo ve UDP/53. Si alguna vez Play services resolviera por DoH por su
   cuenta, este bloqueo no lo vería. Contra el DNS privado que configura el **usuario** ya
   está `DISALLOW_CONFIG_PRIVATE_DNS` (B.33); contra el que pudiera usar Google internamente
   no hay defensa posible desde una app.
2. El rebote de Capa 3 necesita que la Accesibilidad esté activa (B.15).
3. Esto **no** impide ver el historial desde una computadora. Cierra el equipo, no la cuenta.
   La contramedida real de la cuenta es apagar el guardado de historial desde una PC.

---

## 4. Los otros agujeros del mismo estilo (auditoría, NADA parcheado todavía)

El dueño pidió explícitamente buscar más agujeros de este tipo. Están ordenados por lo que
cuesta explotarlos: **arriba, lo que cualquiera encuentra sin saber nada.**

### 4.1 — Mismo mecanismo exacto (app del sistema con WebView + página de Google)

| # | Agujero | Estado |
|---|---|---|
| **G-1** | **Ajustes → Google → "Ayuda y comentarios"**, y el mismo botón dentro de casi cualquier app de Google. Abre `support.google.com` en un WebView **con caja de búsqueda**. Es una vía de escape a la web abierta, tan buena como un navegador. | **Abierto. Es el que sigue en gravedad al de esta sesión.** Se cierra igual: agregar `support.google.com` a la lista de dominios, y el marcador de clase de la pantalla de ayuda. |
| **G-2** | **Google Takeout** desde "Descargar tus datos". El dominio ya quedó bloqueado en este parche, pero la **descarga** sale por otro host (`*.usercontent.google.com`), que NO se bloqueó por ser demasiado amplio. | Parcialmente cerrado. |
| **G-3** | **Play Store** es un catálogo de contenido: capturas, videos de vista previa, textos editoriales, y en algunas regiones pestañas de Libros y Películas. | Cubierto **de hecho**: la tienda está suspendida salvo durante el flujo de actualización, que va con la pantalla tapada. Conviene confirmarlo en la prueba. |
| **G-4** | **Selector de fondo de pantalla de Google** (`com.google.android.apps.wallpaper`): baja un catálogo de fotos en línea. Es un visor de imágenes con todas las letras. | **Se cierra con una línea ya escrita:** `DISALLOW_SET_WALLPAPER` ya existe en `PolicySpec` (B.33) pero viene **apagado**. Candidato claro a encenderlo por defecto. |
| **G-5** | **Salvapantallas / Daydream** puede mostrar un álbum de Google Fotos con la pantalla bloqueada. | Abierto. `DISALLOW_AMBIENT_DISPLAY` (ya en `PolicySpec`) ayuda pero no es lo mismo. Revisar en el equipo. |
| **G-6** | **Instant Apps** (`com.google.android.instantapps.supervisor`): ejecuta una app **sin instalarla**, desde un enlace. Es instalación de apps sin pasar por instalación de apps. | Abierto. Se apaga desde Ajustes → Google, y esa pantalla ahora rebota — o sea que hay que apagarlo **antes** de encender el interruptor nuevo, o desde el panel. |

### 4.2 — La app de Google y el teclado (los dos más grandes fuera de Ajustes)

| # | Agujero | Estado |
|---|---|---|
| **G-7** | **App de Google / Asistente** (`com.google.android.googlequicksearchbox`). Si está instalada es, de lejos, **el agujero más grande del equipo**: el feed Discover es contenido infinito con imágenes, el Asistente responde con imágenes y resultados web, y Lens es una cámara que busca en la web. No es "una app con un WebView": es un navegador con otra forma. | **Verificar YA si está instalada y habilitada.** Si está, suspenderla u ocultarla es más importante que todo lo demás de esta lista. |
| **G-8** | **Gboard** (`com.google.android.inputmethod.latin`). Búsqueda de GIFs y stickers (Tenor — ya lo cubre `block_gifs`), Emoji Kitchen, y **el botón G, que trae resultados web dentro del teclado**. Gboard está en `partialBlockOnly`: no se puede suspender sin dejar al equipo sin teclado. | Parcialmente cubierto. Lo que falta es DNS. |
| **G-9** | **Cualquier app con "pestaña personalizada"** (Custom Tab). Sin navegador instalado cae a un WebView, que es lo que cubre `WebViewBlockManager` — **pero ese bloqueo es opt-in por app y viene apagado.** | Ver 4.4: es el punto estructural. |

### 4.3 — Superficies menores, anotadas para que no se redescubran

- **Búsqueda dentro de Ajustes**: entra por enlace directo a pantallas que el launcher esconde.
- **Ajustes → Apps → Apps predeterminadas → Navegador**: puede reactivar un navegador oculto.
- **Archivos / Descargas**: abrir un `.html` descargado lo abre `com.android.htmlviewer`
  (ya está en `KNOWN_BROWSER_PACKAGES`, o sea que la Capa 3 lo trata como navegador — bien),
  y un video descargado lo abre el reproductor del sistema.
- **Notificaciones con imagen grande** y widgets tipo "Un vistazo".
- **Restaurar desde copia de seguridad** (Ajustes → Google → Configurar y restaurar).
- **Específicos de fabricante**: Samsung Free / Samsung Daily (feed de noticias), Bixby,
  Galaxy Store, Samsung Internet, Samsung Members; en Xiaomi, GetApps y los anuncios de MIUI.
  **En el CAT S22 Flip (Android 11 Go) hay bastante menos de esto**, pero la flota no es un
  solo equipo.
- **Google Maps**, si está instalada: Street View, fotos de comercios, feed "Explorar".

### 4.4 — La recomendación estructural, que vale más que los quince parches

Todo lo de arriba comparte una sola causa: **la Capa 3 es una lista de apps CONOCIDAS y el
bloqueo de WebView es opt-in por app.** O sea que la postura por defecto del equipo es
*permitir*, y cada agujero nuevo es "una app en la que no pensamos". Van a seguir apareciendo.

La vuelta correcta es invertir la postura: **bloquear el WebView en TODA app salvo una lista
blanca explícita.** No es una idea nueva en este proyecto — es exactamente lo que ya hace
`:admin-app` consigo misma (B.22), y funcionó. `WebViewBlockManager` no necesita reescribirse:
alcanza con que `isBlocked()` devuelva `true` por omisión para las apps que no estén en una
lista blanca corta (LockSuite, el launcher, Play Store durante la actualización, y las que el
administrador agregue a mano).

**Es un cambio de postura, no un parche, así que va con el dueño de acuerdo y con una prueba
en un equipo de descarte antes que en uno real.** Pero mientras la postura sea "permitir por
omisión", esta lista de agujeros no se termina nunca.

---

## 5. Archivos tocados

| Archivo | Qué cambió |
|---|---|
| `app/.../mdm/GoogleAccountWebPolicy.kt` | **NUEVO.** Marcadores de clase, exclusiones y dominios. Sin ninguna dependencia de Android: es lógica pura y se puede probar corriéndola. |
| `app/.../mdm/PolicyManager.kt` | `is/setGoogleAccountWebBlocked()` (por defecto `true`), `getGoogleAccountWebSeenClasses()`, y la clave en el perfil exportable (export **e** import, con el mismo nombre). |
| `app/.../service/LockSuiteAccessibilityService.kt` | `googleAccountWeb` en el snapshot de flags, el gancho en `onAccessibilityEvent` (solo `WINDOW_STATE_CHANGED`), `handleGoogleAccountWebBounce()` y `recordUnknownGoogleClass()`. |
| `app/.../service/KosherVpnService.kt` | El bloque de bloqueo global de dominios, después del `when(customRule)`. |
| `app/.../service/LockSuiteFirebaseService.kt` | Los dos comandos FCM. |
| `app/.../util/FirebaseDeviceSync.kt` | `googleAccountWebBlocked` y `googleAccountWebSeenClasses` al panel. |
| `app/.../ui/dashboard/DashboardActivity.kt` | El interruptor en la app. |
| `admin-backend/public/index.html` | El interruptor por dispositivo y por grupo + el texto explicativo. Cache-buster a `v=28`. |
| `admin-backend/public/app.js` | El mapeo a comandos (dispositivo y grupo) y la clave del perfil. |
| `admin-backend/functions/index.js` | `ALLOWED_COMMANDS`. |

---

## 6. Verificación hecha (y lo que NO se hizo)

- **`kotlinc` 2.0.21** contra stubs de la API de Android escritos para esto: **0 errores /
  0 warnings**, en dos bancos. El código de las funciones nuevas se **extrajo del archivo
  real** (mismo método que B.41), no se transcribió.
- **10 controles negativos, los 10 detectados**: método inexistente, tipo equivocado, campo
  inexistente en `Flags`, aridad equivocada de `performGlobalAction`, constante inexistente,
  argumento de tipo equivocado, método DNS inexistente, aridad de `sendBlockedDnsResponse`,
  getter inexistente en prefs, constante de enum inexistente.
- **Prueba de comportamiento ejecutada** sobre `GoogleAccountWebPolicy` (no es solo
  type-check: se corre): 46 casos, todos verdes, **con control negativo que confirma que el
  banco detecta una expectativa mal puesta.** Incluye los casos que romperían el equipo si
  fallaran — `accounts.google.com`, `play.google.com`, `mtalk.google.com`,
  `fcm.googleapis.com` y `MinuteMaidActivity` tienen que pasar de largo, y pasan.
- `node --check` sobre `app.js` y `functions/index.js`, **con control negativo**.
- **NO se corrió Gradle. NO se generó APK. NO se probó nada en equipo real.**

---

## 7. Orden de prueba en equipo real

La primera es la que confirma el arreglo; la 2 y la 3 son las que confirman que **no rompió
nada**, y son las que más importan porque las dos romperían el equipo de forma cara.

1. **Ajustes → Google → Gestionar tu cuenta de Google.** Tiene que rebotar solo, con el
   cartel, sin llegar a mostrar nada. Repetir entrando por Ajustes → Contraseñas y cuentas.
2. **⚠️ Que el equipo siga pudiendo agregar una cuenta de Google.** Con el interruptor
   encendido, quitar y volver a poner la cuenta en un equipo de prueba. **Si el alta se
   rebota, el interruptor es inservible y hay que ampliar las exclusiones de clase.** Es la
   prueba más importante de todas.
3. **Que Play Store, el panel y los comandos sigan andando.** Abrir Play Store, mandar un
   comando desde el panel y confirmar que llega el ack. Si el celular deja de responder al
   panel, se bloqueó un dominio de más — mirar `adb logcat -s KosherVPN`.
4. Con `myactivity.google.com` bloqueado, confirmar por `adb logcat -s KosherVPN` que aparece
   `🚫 BLOQUEADO CUENTA/ACTIVIDAD GOOGLE`.
5. **Mirar `googleAccountWebSeenClasses` en el panel.** Si el paso 1 no rebotó, ahí está el
   nombre de clase real de ESE equipo: agregarlo a `CLASS_MARKERS` y listo, sin adivinar.
6. Apagar el interruptor desde el panel y confirmar que la pantalla vuelve a abrir (o sea,
   que el interruptor manda de verdad y el comando FCM llega).
7. Repetir el paso 1 **con el equipo en otro idioma** (hebreo, por ejemplo). Tiene que
   rebotar igual: la detección no lee texto.

---

## 8. Mensaje de commit (listo para copiar)

```
feat(cuenta-google): cerrar el historial de YouTube y Mi Actividad desde Ajustes

Hallazgo del dueno: Ajustes -> Google -> Gestionar tu cuenta -> Datos y
privacidad -> Historial de YouTube mostraba los videos vistos, con miniatura,
DENTRO de Ajustes y sin ningun navegador. Por el mismo camino quedaban a un
toque la actividad web (todas las busquedas), la cronologia de ubicaciones,
Takeout y la configuracion de anuncios.

Ninguna de las tres capas lo veia, y no por descuido: Android no tiene ninguna
restriccion DISALLOW_* para esto y un Device Owner no puede deshabilitar una
Activity de otra app; el filtro DNS no tenia reglas para esos dominios; y la
capa 3 es una lista de apps conocidas donde Play services nunca estuvo.

Interruptor nuevo block_google_account_web, ENCENDIDO POR DEFECTO (lo que abre
no tiene uso legitimo en un equipo kosher, y un interruptor apagado por defecto
solo protege a quien se acuerde de encenderlo). Cierra por dos capas:

- Capa 3: rebote por nombre de clase de la ventana (accountsettings,
  googlesettings, adssettings, usagereporting). No compara ni un solo texto de
  pantalla, asi que cambiar el idioma no lo evade. Excluye a proposito el flujo
  de alta de cuenta (MinuteMaid/auth.uiflows): si rebotara ahi, el equipo no se
  podria dar de alta.
- Capa 2: bloqueo global de myaccount / myactivity / history / takeout /
  timeline / adssettings / photos.google.com y ytimg.com. Va despues de las
  reglas forzadas, asi que un FORCE_ALLOW del administrador sigue ganando.

Ademas publica al panel las clases de Play services que vio y no supo
clasificar (googleAccountWebSeenClasses), para calibrar el rebote en un equipo
donde no dispare en vez de adivinar.

Archivo nuevo mdm/GoogleAccountWebPolicy.kt (logica pura, sin dependencias de
Android). Comandos FCM BLOCK/UNBLOCK_GOOGLE_ACCOUNT_WEB, interruptor en la app
y en el panel (por dispositivo y por grupo), clave en el perfil exportable con
el mismo nombre de los dos lados. Cache-buster de app.js a v=28.

Verificado: kotlinc 2.0.21 con stubs, 0 errores / 0 warnings, con 10 controles
negativos todos detectados; prueba de comportamiento ejecutada (46 casos) con
control negativo; node --check con control negativo. Sin Gradle y sin probar en
equipo.
```

Después del commit: `firebase deploy --only hosting,functions` y abrir el panel con
**Ctrl+F5**.
