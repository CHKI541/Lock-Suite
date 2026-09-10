# Prompts listos para otras conversaciones — 9/9/2026

Cada bloque de abajo es un **prompt completo y autocontenido**: se copia y se pega tal cual
al arrancar una conversación nueva (Claude, Antigravity o la que sea) y esa sesión tiene
todo lo que necesita sin haber leído esta.

Los cuatro salieron de analizar la comparación con MB Smart que el dueño pidió el 9/9. El
análisis completo —qué de eso conviene, qué no y por qué— está en
`INSTRUCCIONES_ANTIGRAVITY_2026-09-09_PERFILES_MAESTROS.md` §1. Acá va solo lo accionable.

**Orden recomendado:** A → B → C → D. La A es la que más se parece a lo que hace que MB
Smart se sienta fácil; la D es la que menos conviene y va última a propósito.

---

## PROMPT A — Tienda kosher con solicitudes de apps (+ cerrar B.6)

> Copiar desde acá ↓

```
Trabajás en LockSuite / KosherLock MDM. Leé primero LOCKSUITE_CONTEXTO_PARA_IA.md completo
(arrancá clonando github.com/CHKI541/Lock-Suite en el contenedor: device_bash no monta la
carpeta desde hace nueve sesiones seguidas, no pierdas llamadas intentándolo).

OBJETIVO: que el usuario final pueda pedir una app desde el celular y que el administrador
la apruebe desde el panel con un botón, sin ADB y sin abrir Play Store. Es la pieza que
hace que un MDM comercial se sienta fácil: el instalador no tiene que adivinar qué apps va
a querer el usuario en seis meses.

LO QUE YA EXISTE Y NO HAY QUE REHACER (verificalo antes de escribir una línea):
  · util/SelfUpdater.downloadAndInstallApk() instala en silencio como Device Owner, vía
    PackageInstaller. Es la ruta "rápida + silenciosa + inevadible" que B.14 punto 6
    describe como la única que tiene las tres cosas a la vez.
  · El nodo `storeApps` en Firebase ya existe, y el panel ya tiene una pantalla para
    cargarle apps (admin-backend/public/app.js, buscá `renderStoreApps`).
  · `globalSettings/allowedPackages` ya lo escribe el modo lista blanca de B.53.
  · util/ApkSignatureVerifier.kt (B.37) ya compara la firma del APK descargado contra la
    del paquete instalado.

✅ ACTUALIZACIÓN DEL 10/9: LA TAREA 1 DE ESTE PROMPT (EL CHECKSUM) YA ESTÁ HECHA.
B.6 se cerró en B.58: existe `util/ApkChecksum.kt`, `storeApps` lleva `sha256`, el panel
lo calcula solo al cargar una app y tiene botón para recalcular, y el celular no instala
una entrada sin huella. Verificá que esté antes de rehacerlo, y SALTEÁ el bloque de abajo
—queda como historial de por qué era bloqueante—. Empezá directo en "DESPUÉS DE ESO, LA
FUNCIÓN". Lo que falta de este prompt es solo el flujo de PEDIR una app.

⚠️ (HISTÓRICO) BLOQUEANTE QUE IBA PRIMERO: B.6 ESTABA ABIERTO.
ApkSignatureVerifier solo puede comparar contra un paquete YA INSTALADO. La tienda
administrada instala apps por PRIMERA vez, así que ahí no hay contra qué comparar: hoy se
descarga y se instala un APK sin verificar absolutamente nada. B.37 lo dice explícito
("Esto NO cierra B.6 entero y no hay que darlo por cerrado"), y hay un comentario en
ApkInstaller.kt línea ~50 que también lo dice. Instalar en silencio, como Device Owner,
un APK sin checksum, es la peor combinación posible de este proyecto.
Entonces la tarea 1 es:
  1. Agregar `sha256` a cada entrada de `storeApps` (y de paso a version.json).
  2. Calcularlo sobre el archivo YA DESCARGADO EN DISCO, antes de abrir la sesión de
     PackageInstaller, y abortar si no coincide.
  3. En el panel, calcular el sha256 en el navegador al cargar la app (crypto.subtle) para
     que el administrador no tenga que hacerlo a mano — si se lo pedís a mano, se va a
     saltear y la protección queda decorativa.
  4. Una entrada de storeApps SIN sha256 no se instala. Fallar cerrado, no abierto.

DESPUÉS DE ESO, LA FUNCIÓN:

1. Pantalla "Tienda" en el celular (Compose, dentro de DashboardActivity o pantalla
   propia). Muestra las apps de `storeApps` que el grupo del equipo tiene permitidas, con
   botón "Instalar" que llama a downloadAndInstallApk(). Sin Play Store, sin pantalla
   negra, sin accesibilidad, sin idioma: es la ruta sin superficie de evasión.
2. Botón "Pedir una app…" que escribe en `devices/<id>/appRequests/<paquete_con_guiones>`
   un objeto {packageName, label, requestedAt, note}. Las claves de Firebase no admiten
   `.`, así que el paquete viaja con `_` — mirá cómo lo hace FirebaseDeviceSync para la
   auditoría de la lista blanca (B.53) y copiá ese patrón exacto.
3. En el panel: una campanita con la cantidad de solicitudes pendientes de toda la flota,
   y por dispositivo una tarjeta con [Aprobar] / [Rechazar]. Aprobar tiene que hacer LAS
   TRES cosas de un toque, igual que "permitir" en la lista blanca de B.53: des-suspender
   y des-ocultar la app si está instalada, sumarla a allowedPackages, y abrirle los
   dominios si está en el catálogo de WhitelistCatalog.
4. Comandos FCM nuevos en los tres lugares que hacen falta: el `when` de
   LockSuiteFirebaseService, ALLOWED_COMMANDS de admin-backend/functions/index.js, y el
   panel. Exigen PIN (no van en la excepción de UPDATE_*).

REGLAS DEL PROYECTO QUE NO SON NEGOCIABLES:
  · La configuración grande NO viaja por el `data` de FCM: tope duro de ~4 KB y la Cloud
    Function corta en 3.000 bytes. Está medido en B.57: el perfil de políticas ya pesa
    2.009 bytes. Poné el dato en la base y mandá por FCM solo el aviso, como hacen
    SYNC_WHITELIST (B.53) y APPLY_PROFILE (B.57).
  · Una clave que el otro lado no conoce se acepta en silencio y no hace nada. Es el bug de
    `no_apps_control` (B.28) y ya pasó cuatro veces. Si duplicás una lista en dos
    lenguajes, escribí un chequeo de simetría en tools/ como check_whitelist_sync.py o
    check_profile_sync.py, con su propio control negativo.
  · No podés compilar ni probar en equipo. Antes de entregar: kotlinc 2.0.21 contra stubs
    escritos a mano, con el código EXTRAÍDO de los archivos reales y no transcripto, y con
    controles negativos (romper a propósito una referencia y confirmar que la detecta; sin
    eso un "0 errores" puede ser que no compiló nada). node --check en los .js, también con
    control negativo. Y si alguna función se puede dejar pura, dejala pura y corré
    aserciones de comportamiento de verdad: es lo único que agarra los errores que importan.

AL CERRAR: actualizá LOCKSUITE_CONTEXTO_PARA_IA.md (sección B con lo nuevo y lo que quede
abierto, sección C con la bitácora de tu sesión), y dejá un
INSTRUCCIONES_ANTIGRAVITY_<fecha>_TIENDA.md con el orden de prueba en equipo real y el
mensaje de commit listo para copiar. Verificá primero con `git log -1` y `git status` que
nadie más esté trabajando en paralelo, y si escribís por device_commit_files usá siempre
`expectedMtimeMs` — el 9/9 dos sesiones se pisaron y eso fue lo único que lo evitó.
```

> Hasta acá ↑

---

## PROMPT B — Detector genérico de WebViews embebidos (el "IAB Finder" de MB Smart)

> Copiar desde acá ↓

```
Trabajás en LockSuite / KosherLock MDM. Leé primero LOCKSUITE_CONTEXTO_PARA_IA.md completo
(arrancá clonando github.com/CHKI541/Lock-Suite en el contenedor).

EL PROBLEMA, TAL COMO LO DEJÓ ESCRITO B.44:
La Capa 3 es una lista de apps CONOCIDAS (WhatsApp, Mercado Pago, Play Store) y el bloqueo
de WebView es opt-in por app. O sea que la postura por omisión del equipo es *permitir*, y
cada agujero nuevo es "una app en la que no pensamos". B.44 los enumeró: la app de Google,
"Ayuda y comentarios", el selector de fondos, Gboard, Instant Apps, el salvapantallas,
Takeout, Maps, y los específicos de fabricante. Van a seguir apareciendo indefinidamente.

⚠️ LEÉ ESTO ANTES DE PROPONER LA SOLUCIÓN OBVIA: **el dueño YA RECHAZÓ la lista blanca
global de WebView** que proponía B.44 (textual: "lista blanca no quiero"). Está registrado
en B.45. No la vuelvas a proponer.

LO QUE SÍ HAY QUE HACER, Y DE DÓNDE SALE:
MB Smart resuelve esto sin lista blanca, con un detector ESTRUCTURAL. En su MainActivity
tiene mainiabUseIABFinder, Miabwhitelistclasses = "android.webkit.WebView" y
Miabsuspechclases = "android.widget.Image,android.view.View,android.widget.ImageView". La
idea: en vez de esperar eventos de accesibilidad específicos por app, detecta por la
JERARQUÍA DE VISTAS que una app cualquiera levantó un navegador embebido, y le aplica el
filtro general. Una señal estructural, no una lista de nombres.

Eso encaja perfecto con la regla que este proyecto ya tiene escrita en B.19 punto 3:
"si hay una señal estructural —clase de actividad, view-id, componente resuelto— usarla
antes que una palabra".

LO QUE HAY QUE ESCRIBIR:
1. Un archivo nuevo mdm/EmbeddedBrowserDetector.kt con una función PURA que reciba una
   descripción del árbol (nombres de clase, si hay campo de texto editable, cuántos nodos,
   si hay una barra que parece de URL) y devuelva un veredicto: NO_ES_NAVEGADOR /
   WEBVIEW_DE_CONTENIDO / NAVEGADOR_EMBEBIDO. Pura, para poder correr aserciones reales
   fuera del equipo (es lo que se hizo con WhitelistManager.buildRules() en B.53 y con
   EnrollmentProfiles.buildData() en B.57, y es donde se agarran los errores que importan).
2. Cablearlo en service/LockSuiteAccessibilityService.kt, dentro de handleWebViewBlocking.

⚠️ LO QUE HACE DIFÍCIL ESTO, Y HAY QUE RESOLVERLO ANTES DE ESCRIBIR NADA:
  a. `onAccessibilityEvent` corre en el HILO PRINCIPAL y el sistema lo llama hasta 10 veces
     por segundo. Hay un bloque de comentario al principio de ese archivo con las reglas
     del camino caliente: leelo. B.13 pasó una sesión entera sacando de ahí cosas
     razonables en sí mismas que mataban la fluidez del equipo entero. Un detector que
     recorra el árbol en cada evento es exactamente eso. Tiene que ir detrás de un cambio
     de ventana, con antirrebote, con tope de profundidad y presupuesto de nodos, como
     todos los recorridos que ya tiene el archivo (MAX_TREE_DEPTH = 40,
     MAX_NODES_PER_SCAN = 2500).
  b. **El falso positivo es caro.** Si el detector confunde una app legítima con un
     navegador, esa app deja de funcionar y nadie sabe por qué. Este proyecto ya pagó eso
     tres veces: B.43 (bloqueó la administración entera de la cuenta de Google), B.50 (dejó
     a un usuario sin poder conectarse al Wi-Fi de un aeropuerto) y B.15 punto 1 (casi no se
     podía abrir Ajustes). Por eso: interruptor propio, APAGADO POR DEFECTO, con MODO
     SIMULACIÓN primero — que no bloquee y publique al panel qué HABRÍA bloqueado, igual
     que la auditoría de B.53, que es la parte que hizo usable ese punto entero.
  c. Hay exclusiones que si fallan rompen el equipo, y hay que probarlas: la ventana del
     portal cautivo (B.46/B.50 — su contenido ENTERO es un WebView y taparla dejó a alguien
     sin poder conectarse), el flujo de alta de cuenta de Google (`auth.uiflows.minutemaid`,
     B.43 — LockSuite se instala con el equipo SIN cuenta, así que ese flujo es el estado
     de fábrica del procedimiento), y :admin-app (que es a propósito un WebView del panel).
  d. Ojo con el dato de B.45 punto 4: `googlequicksearchbox` ya está en
     KNOWN_BROWSER_PACKAGES, y en handleWebViewBlocking "ser navegador" significa otra cosa
     de lo que parece. Leé esa función antes de asumir.

VERIFICACIÓN OBLIGATORIA (no podés compilar ni probar en equipo): kotlinc 2.0.21 contra
stubs, con el código EXTRAÍDO de los archivos reales y no transcripto, con controles
negativos; y aserciones de comportamiento sobre la función pura, incluyendo casos que
tienen que pasar de largo (portal cautivo, MinuteMaid, el panel de :admin-app).

AL CERRAR: actualizá LOCKSUITE_CONTEXTO_PARA_IA.md (B y C) y dejá un
INSTRUCCIONES_ANTIGRAVITY_<fecha>_IAB.md con el orden de prueba —la primera prueba es que
NO se rompió nada con el interruptor apagado— y el mensaje de commit listo.
```

> Hasta acá ↑

---

## PROMPT C — Alta en 30 segundos: QR de aprovisionamiento + perfil automático

> Copiar desde acá ↓

```
Trabajás en LockSuite / KosherLock MDM. Leé primero LOCKSUITE_CONTEXTO_PARA_IA.md completo
(arrancá clonando github.com/CHKI541/Lock-Suite en el contenedor). Leé también B.57, que es
la mitad de esta función y ya está escrita.

OBJETIVO: que dar de alta un equipo sea escanear un QR y elegir un nivel, sin ADB.

DE DÓNDE SALE: la comparación con MB Smart del 9/9. Su instalador no configura nada en el
celular: enrola el equipo con un QR de aprovisionamiento y baja un perfil pre-armado. La
mitad del perfil YA ESTÁ HECHA en LockSuite (B.57: tres perfiles maestros dentro del APK,
aplicables de un toque, más globalSettings/profiles para los grandes). Falta la otra mitad:
el enrolamiento.

LO QUE HAY HOY: `adb shell dpm set-device-owner com.ejemplo.locksuite/...`, o sea una PC con
ADB, drivers y depuración USB habilitada, por cada equipo.

LO QUE HAY QUE HACER:
1. **QR de aprovisionamiento (NFC/QR provisioning de Android).** Desde Android 7 (API 24,
   que es el minSdk de este proyecto) un equipo recién reseteado permite tocar seis veces la
   pantalla de bienvenida y escanear un QR que instala una app como Device Owner. El QR es
   un JSON con android.app.extra.PROVISIONING_DEVICE_ADMIN_COMPONENT_NAME,
   ..._PACKAGE_DOWNLOAD_LOCATION (el APK ya está en Firebase Hosting),
   ..._SIGNATURE_CHECKSUM y ..._ADMIN_EXTRAS_BUNDLE.
   ⚠️ VERIFICÁ CONTRA LA DOCUMENTACIÓN DE ANDROID ANTES DE ESCRIBIR EL JSON: el nombre y el
   formato del checksum de firma cambiaron entre versiones y un QR mal armado falla con un
   mensaje inútil en el equipo. No lo escribas de memoria.
2. **El QR lo genera el panel**, en la pestaña de perfiles, con un desplegable para elegir
   el nivel (Nivel 1 / 2 / 3 de B.57). El nivel viaja dentro de ADMIN_EXTRAS_BUNDLE.
3. **Al terminar el aprovisionamiento**, DeviceAdminReceiver.onProfileProvisioningComplete()
   lee ese extra y llama a `PolicyManager.applyMasterProfile(nivel)` — que ya existe. Con eso
   el equipo queda configurado entero sin que nadie toque un interruptor.
4. **Aviso honesto en el panel**, porque si no va a generar un reclamo: el QR requiere el
   equipo RECIÉN RESETEADO y SIN CUENTA DE GOOGLE, igual que el método ADB de hoy. No es
   magia, es el mismo requisito con menos fricción.
5. Actualizar el instalador WebADB de admin-backend/public/installer/ para ofrecer los dos
   caminos, y README.md.

⚠️ CUIDADO CON EL ORDEN, Y ES EL PUNTO MÁS FÁCIL DE EMBARRAR:
El Nivel 1 de B.57 bloquea `DISALLOW_MODIFY_ACCOUNTS`. Si el perfil se aplica AL TERMINAR
el aprovisionamiento —o sea antes de que el instalador agregue la cuenta de Google— el
equipo NO SE PUEDE DAR DE ALTA. Es el mismo error de razonamiento que ya costó B.41 punto 3
y B.43: LockSuite se instala con el equipo SIN cuenta, así que "todavía no hay cuenta" es
el estado de fábrica del procedimiento, no un caso raro.
La salida limpia: el aprovisionamiento aplica el perfil SALVO las restricciones marcadas
como "post-alta", y el panel muestra un botón "Terminar alta" que aplica el resto una vez
que la cuenta está puesta. Marcá esas restricciones en EnrollmentProfiles.kt con un campo
nuevo (por ejemplo `postEnrollment: Set<String>`) y actualizá tools/check_profile_sync.py
para que las contemple.

VERIFICACIÓN: kotlinc 2.0.21 contra stubs con código extraído de los archivos reales y
controles negativos; node --check en los .js; `python tools/check_profile_sync.py` y
`python tools/check_whitelist_sync.py` en verde antes de entregar.

AL CERRAR: LOCKSUITE_CONTEXTO_PARA_IA.md (B y C) y un
INSTRUCCIONES_ANTIGRAVITY_<fecha>_QR.md con el orden de prueba —hace falta un equipo de
descarte reseteado de fábrica, no se puede probar en uno en uso— y el commit listo.
```

> Hasta acá ↑

---

## PROMPT D — DNS por categorías en la nube (⚠️ leer las tres condiciones antes)

**Este es el único de los cuatro que NO recomiendo hacer ya**, y el prompt lo dice adentro.
El porqué completo está en `INSTRUCCIONES_ANTIGRAVITY_2026-09-09_PERFILES_MAESTROS.md` §1.3.
Va igual, por si el dueño lo quiere después de leer las objeciones.

> Copiar desde acá ↓

```
Trabajás en LockSuite / KosherLock MDM. Leé primero LOCKSUITE_CONTEXTO_PARA_IA.md completo
(arrancá clonando github.com/CHKI541/Lock-Suite en el contenedor). Leé además B.18, B.21,
B.24 y B.49 enteras antes de tocar una línea: son las seis causas distintas del síntoma
"se cae el internet", todas en el camino que esta tarea toca.

LA IDEA (de MB Smart): en vez de mantener listas de dominios a mano, mandar las consultas a
un resolutor que ya tiene millones de dominios categorizados (CleanBrowsing, AdGuard
Family, Cloudflare for Families) y bloquear por categoría. Así una app nueva "queda filtrada
sola" sin escribir código.

⚠️ ANTES DE EMPEZAR, TRES COSAS MEDIDAS QUE HACEN QUE ESTO NO SE PUEDA HACER "COMO SUENA".
Están verificadas contra el código el 9/9; confirmalas vos también antes de seguir:

1. **HAY UN CAMINO QUE DEJA EL EQUIPO SIN FILTRAR, EN SILENCIO, Y ESTO LO ACTIVA.**
   util/NetworkForwarder.kt tiene FALLBACK_DNS_PRIMARY = 8.8.8.8 y FALLBACK_DNS_SECONDARY =
   1.1.1.1 (líneas ~184-185), y forwardDnsQuery() reintenta contra ellos ante CUALQUIER
   IOException. Eso hoy está bien: es la red de seguridad de B.21/B.24 y evita que un
   microcorte del ISP deje al equipo sin internet.
   Pero si el resolutor de arriba pasa a ser el que filtra, **cada timeout manda la consulta
   a un resolutor SIN filtrar y la deja resolver**. El usuario no ve nada raro; el filtro
   simplemente no está. Es la peor forma de falla posible para este producto.
   → Antes de cualquier otra cosa: el reintento tiene que ir a un SEGUNDO resolutor
     categorizador (por ejemplo el secundario de CleanBrowsing), nunca a uno abierto. Y si
     ninguno responde, la decisión de si se falla abierto o cerrado es del dueño, no tuya:
     preguntásela con las dos consecuencias escritas.

2. **`upstream_dns_ip` existe y es configuración MUERTA.** Se lee en un solo lugar
   (NetworkForwarder línea ~174), no hay setter, ni comando FCM, ni interruptor en el panel,
   ni en la app: buscalo con grep y confirmalo. Y encima se usa solo como ÚLTIMO RECURSO
   cuando no se pudo resolver el DNS de la red. Para que un resolutor categorizador sea el
   que manda hay que INVERTIR esa prioridad.
   → Y ahí choca de frente con B.21: la corrección del CGNAT depende justamente de
     `network.bindSocket()` a la red física y de preferir su propio DNS. Ese arreglo costó
     dos sesiones de diagnóstico forense con ADB. No lo deshagas sin entenderlo entero.

3. **Se pisa con el modo lista blanca (B.53), que es más fuerte y ya está hecho.** Una lista
   blanca estricta bloquea todo lo que no esté permitido; una lista negra por categorías
   permite todo lo que no esté categorizado como malo. Con el modo de B.53 encendido, el
   categorizador no agrega nada. Y las categorías comerciales (adultos, malware, apuestas)
   no son las categorías que le importan a un equipo kosher.
   → Entonces esto NO es un reemplazo de nada: como mucho es una RED DE RESPALDO para los
     equipos que todavía no pasaron a estricto, o para la etapa de simulación.

SI DESPUÉS DE ESO EL DUEÑO IGUAL LO QUIERE, ASÍ SE HACE BIEN:
  · Interruptor `dns_category_filter` + campo para elegir el proveedor, APAGADO POR DEFECTO.
    Comandos FCM en los tres lugares (el `when` de LockSuiteFirebaseService, ALLOWED_COMMANDS
    de functions/index.js, el panel), con PIN.
  · Los IP de los proveedores (CleanBrowsing Family, AdGuard Family, Cloudflare for
    Families) **verificalos con una búsqueda web al momento de escribir**, no de memoria:
    cambian. Y probá que respondan por UDP/53 plano — NextDNS con perfil propio NO sirve por
    UDP plano en un celular con IP cambiante, necesita DoH/DoT, que el túnel de LockSuite no
    habla (solo captura UDP/53: eso es Capa 2 entera, ver B.4).
  · Precedencia: las reglas FORCE_BLOCK/FORCE_ALLOW del administrador le ganan a todo,
    igual que en B.53. La lista de infraestructura de WhitelistCatalog.INFRASTRUCTURE
    también tiene que sobrevivir, o el equipo se queda sordo al panel.
  · Visibilidad: reportá al panel cuántas consultas resolvió el categorizador y cuántas
    cayeron al reintento. Sin ese número no hay forma de saber si el filtro está puesto.

VERIFICACIÓN: kotlinc 2.0.21 contra stubs con código extraído de los archivos reales y
controles negativos. Y para el camino de red, pedile al dueño un volcado de ADB antes de
concluir nada: la sección A del contexto lo dice y B.49 lo demuestra — "pedir la evidencia
cruda temprano es la jugada de mayor rendimiento del proyecto".

AL CERRAR: LOCKSUITE_CONTEXTO_PARA_IA.md (B y C) y el documento de instrucciones con el
orden de prueba y el commit listo. La primera prueba es siempre "con el interruptor apagado,
que no se rompió nada".
```

> Hasta acá ↑
