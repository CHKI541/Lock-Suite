# Instrucciones — Checksum de APK, período de gracia y selector de celular (B.58) — 10/9/2026

Sesión sobre **0.6.48 / código 111** (`199a2de`), que ya tenía B.53–B.57 commiteado y desplegado.

Tres cosas, en orden de importancia:

1. **Se cerró B.6** — el agujero que quedaba abierto desde el principio: la Tienda
   administrada descargaba e instalaba APKs **sin verificar absolutamente nada**, en
   silencio, con privilegio de Device Owner.
2. **Perfil Nivel 4 — período de gracia**, pedido del dueño: bloquear lo claramente no
   kosher, dejar lo dudoso a criterio del usuario por un plazo, con la tienda abierta, y
   que se cierre solo al vencer.
3. **Selector de celular en los perfiles** — el dueño reportó *"si pongo aplicar, ¿a qué
   usuario se lo pondrá?"* y tenía razón: había que pegar un `ANDROID_ID` en un `prompt()`.

---

## 1. B.6 CERRADO — el agujero más grande que quedaba

### El problema, sin adornos

`SelfUpdater.downloadAndInstallApk()` bajaba el APK de la `apkUrl` de una entrada de
`storeApps` y lo instalaba **en silencio, sin confirmación del usuario, como Device
Owner**. La única verificación que existía, `ApkSignatureVerifier` (B.37), compara contra
la firma del paquete **ya instalado** — y la Tienda instala paquetes que **no** están
instalados. Ahí no hay nada contra qué comparar. B.37 lo dejó escrito textual: *"Esto NO
cierra B.6 entero y no hay que darlo por cerrado"*, y hay un comentario en
`ApkInstaller.kt` que dice lo mismo.

O sea: **cualquiera que pudiera cambiar el contenido servido en esa URL conseguía
ejecución silenciosa con privilegios en toda la flota.** No hace falta romper Firebase —
alcanza con que la URL apunte a un hosting de terceros que caduque, cambie de dueño o se
comprometa. Y como varias entradas apuntan a GitHub Releases, el tercero es real.

### Lo que se hizo

Archivo nuevo **`util/ApkChecksum.kt`**. El `sha256` se publica en la entrada de
`storeApps` y se compara **contra el archivo ya descargado en disco, ANTES de levantar las
restricciones de instalación y de abrir la sesión de `PackageInstaller`**. El orden
importa: verificar después habría dejado el equipo con `DISALLOW_INSTALL_APPS` levantado
durante la comprobación.

**La Tienda falla CERRADO: una entrada sin `sha256` no se instala. Y no hay interruptor
para saltearlo.** Hay que defender eso cuando alguien proponga "un switch mientras tanto":
A Bloq tiene este mismo código escrito y **comentado**, con un `return true // TEMPORARILY
BYPASS` adentro (B.31). Alguien lo escribió bien, lo apagó "por un rato", y quedó así:
una verificación que parece existir y no existe. **Es peor que no tenerla.**

**El OTA de LockSuite es la excepción, y es deliberada:** ahí el hash se verifica **si está
publicado** y se sigue si no. Dos motivos: Android ya exige la misma firma para reemplazar
un paquete instalado (protección gratis), y el OTA es **la vía de rescate de un equipo** —
un `version.json` viejo sin hash que impidiera actualizar dejaría equipos sin poder recibir
el arreglo de lo que sea que esté roto. Mismo criterio que el techo de 120 s del arranque
protegido en B.16: *"es preferible unos minutos sin filtrar a un ladrillo"*.

### El panel calcula el hash solo

No se le pide al administrador que lo pegue: **un paso manual en un control de seguridad es
un paso que se va a saltear**, y la protección queda decorativa. Al cargar una app, el
panel descarga el APK con `fetch` y lo hashea con `crypto.subtle`.

Si falla (típicamente CORS, porque GitHub Releases no manda `Access-Control-Allow-Origin`),
**no se guarda la entrada sin hash**: se muestra el porqué y las dos salidas — subir el APK
a Firebase Hosting (recomendado, además deja de depender de un tercero) o calcularlo con
`Get-FileHash` / `certutil` y pegarlo.

### ⚠️ LO PRIMERO QUE HAY QUE HACER DESPUÉS DE DESPLEGAR

**Las entradas de `storeApps` que ya existen NO tienen `sha256`, así que los celulares van
a dejar de instalarlas.** Es el comportamiento correcto, pero hay que arreglarlo el mismo
día o va a parecer que se rompió la tienda:

> Panel → **Ajustes** → sección Tienda → cada tarjeta sin huella sale en rojo con un botón
> **🔒 Calcular huella**. Un clic por app.

Y **cada vez que se reemplace el APK en una URL hay que recalcular**, porque el archivo
cambia y la huella deja de coincidir. El celular ahí hace lo correcto (no instalar), pero
se ve como "dejó de andar". Por eso la tarjeta muestra la fecha del último cálculo.

---

## 2. NIVEL 4 — PERÍODO DE GRACIA

### Lo que pidió el dueño, textual

> *"tiene que haber un perfil de auto personalización, donde se bloqueará todo lo no
> kosher, y lo que no es tan claro quedará a decisión del usuario por un tiempo que yo
> especifique, y lo mismo la tienda de apps quedará abierta, y yo pondré que por ejemplo
> después de 2 días se cierre todo"*

### Lo que hace, y por qué vale más que un temporizador

Lo interesante no es que se cierre a los dos días: es **qué pasa durante esos dos días**.
Con el modo lista blanca en SIMULACIÓN (B.53), el equipo va anotando cada dominio que
quedaría afuera y lo publica al panel agregado por dominio y cantidad.

**O sea que el período de gracia es el mecanismo que llena el catálogo.** Sin esto, pasar
un equipo a estricto es adivinar qué necesita esa persona — y B.53 ya dejó anotado que
ninguna lista escrita de antemano puede estar completa. Con esto, se entrega el equipo, se
usa normal dos días, y al cerrar se cierra **con la lista de lo que esa persona usó**.

| Durante la gracia | Al vencer |
|---|---|
| Tienda **abierta** (`DISALLOW_INSTALL_APPS` en false) | Se aplica el perfil elegido (por omisión Nivel 1) |
| Lanzador normal del usuario | Vuelve el launcher kosher |
| Puede manejar y desinstalar sus apps | Se cierra el control de apps |
| **Todo lo claramente no kosher ya bloqueado** | Sigue bloqueado |
| **Piso anti-manipulación completo** | Sigue |

### Los tres problemas reales y cómo se resolvieron

**1. Atrasar el reloj para que no venza nunca.** No es hipotético: B.26 causa 3 fue un
equipo con el reloj corrido que rechazaba TODOS los comandos para siempre, y B.29 fue el
latido que no avanzaba en sueño profundo por usar `uptimeMillis()`.

Hay **dos relojes y cierra el que primero venza**:

- **Reloj de pared** — cuenta con el equipo apagado (el caso normal). Lo puede mover el usuario.
- **Acumulador de tiempo real** (`elapsedRealtime()`) — avanza con el equipo encendido
  incluso en sueño profundo y **no lo puede tocar nadie**; se reinicia en cada arranque,
  así que se acumula a mano en cada vuelta del Watchdog.

Ninguno alcanza solo. Juntos, atrasar el reloj solo consigue que dispare el otro. Y encima
el perfil enciende **`DISALLOW_CONFIG_DATE_TIME`** — ⚠️ **esa restricción está por un motivo
mecánico y no de contenido: si alguien la saca "porque molesta", rompe la función entera y
no va a ser evidente.**

**2. Que el cierre no dispare y el equipo quede abierto para siempre.** Es literal el
riesgo que B.11 dejó anotado para la suspensión. El cierre lo fuerza el **`WatchdogWorker`**
(cada 15 min), que es **lo único del proyecto que sobrevive a que muera el proceso**. Se
chequea además en el ciclo de 20 s del servicio de primer plano (para que se sienta
puntual) y en `BootReceiver` (para el caso de que venza con el equipo apagado).

**3. Al cerrar NO se pasa la lista blanca a "bloquear de verdad".** Sería tentador —el
catálogo ya está lleno— y sería exactamente lo que B.53 pidió evitar: ese paso *va en un
equipo de prueba*, con una persona mirando. **Que el catálogo esté completo es el regalo
del período de gracia; usarlo sigue siendo una decisión del dueño.**

---

## 3. EL SELECTOR DE CELULAR

Reporte del dueño mirando el panel: *"no entendí qué hacen estos perfiles, si pongo aplicar
¿a qué usuario se lo pondrá?"*.

Tenía razón y era un defecto real: sin un celular seleccionado, el botón abría un
`prompt()` pidiendo que se **pegara el ID del dispositivo**. Eso es exactamente la fricción
que estos perfiles venían a eliminar, y se coló por copiar el comportamiento del botón
viejo de presets sin cuestionarlo.

Ahora hay un desplegable con la flota (nombre + qué perfil tiene puesto), el botón de cada
tarjeta dice **"⚡ Aplicar a *Celular de Eli*"**, y sin celular elegido queda
**deshabilitado y diciendo qué falta** en vez de abrir un prompt. Un botón que se puede
tocar y después te pide datos que no tenés es peor que uno que dice qué falta.

Además, si el celular elegido está en gracia, arriba aparece **cuánto falta y con qué va a
cerrar**, con un botón para cancelar el vencimiento.

---

## 4. Los archivos

| Archivo | Qué cambió |
|---|---|
| **`app/util/ApkChecksum.kt`** *(nuevo)* | Cálculo y verificación del sha256. Falla cerrado en la tienda, abierto ante la ausencia en el OTA. |
| **`app/mdm/GracePeriodManager.kt`** *(nuevo)* | Los dos relojes, el acumulador, el cierre y la visibilidad. |
| `app/mdm/EnrollmentProfiles.kt` | Nivel 4 (`LEVEL_GRACE`) con sus restricciones e interruptores. |
| `app/mdm/PolicyManager.kt` | `applyMasterProfile()` acepta duración y destino; cancela una gracia en curso al aplicar otro perfil. |
| `app/util/SelfUpdater.kt` | `downloadAndInstallApk()` acepta `sha256` y verifica; el OTA lee `sha256` de `version.json`. |
| `app/util/FirebaseDeviceSync.kt` | Seis campos de gracia al panel. |
| `app/service/LockSuiteFirebaseService.kt` | `APPLY_MASTER_PROFILE` con `graceMs`/`graceTarget`, y `CANCEL_GRACE_PERIOD`. |
| `app/service/WatchdogForegroundService.kt`, `app/worker/WatchdogWorker.kt`, `app/receiver/BootReceiver.kt` | Los tres puntos donde se chequea el vencimiento. |
| `app/ui/auth/LoginActivity.kt` | `StoreApp.sha256`; una app sin huella se muestra deshabilitada y con el motivo. |
| `app/ui/dashboard/DashboardActivity.kt` | Nivel 4 con selector de plazo y aviso de gracia activa. |
| `admin-backend/functions/index.js` | `CANCEL_GRACE_PERIOD`, validación de `graceMs` (1 min–90 días) y de `graceTarget`. |
| `admin-backend/public/app.js` | sha256 automático al cargar apps, botón de recálculo, selector de celular, Nivel 4. |
| `admin-backend/public/index.html` | Selector, aviso de gracia. **Cache-buster a `v=34`.** |

---

## 5. Verificación hecha

**No se corrió Gradle y no se probó nada en equipo real.** Lo que sí:

- **`kotlinc` 2.0.21 contra stubs escritos a mano**, con el código de los archivos reales:
  `EnrollmentProfiles.kt`, `GracePeriodManager.kt` y `ApkChecksum.kt` completos.
  **0 errores / 0 warnings**, con **4 controles negativos, los 4 detectados**.
  *(De paso el type-check encontró que a un stub le faltaba `SharedPreferences.Editor.remove()`
  — se confirmó contra la API real de Android antes de agregarlo, en vez de asumirlo.)*
- **Tres pruebas de comportamiento reales, 183 aserciones, todas verdes:**
  - `EnrollmentProfiles` — **124**, con **4 controles negativos nuevos detectados**: que la
    tienda queda abierta en la gracia, que la gracia bloquea la hora (sin eso el
    vencimiento se evade), que sigue filtrando lo no kosher, y que no rompe el piso
    anti-manipulación.
  - `ApkChecksum` — **28**, con **4 controles negativos detectados**. Incluye que la tienda
    **no instala** sin hash, con hash vacío, con hash mal formado ni con archivo cambiado;
    y que el OTA sí instala sin hash pero **no** con un hash que no coincide.
  - `GracePeriodManager` — **31**, con **5 controles negativos detectados**. La central:
    **con el reloj de pared movido diez años adelante, el acumulador cierra igual**, y el
    motivo publicado delata que el reloj no llegó. También: que un reinicio a mitad del
    período **no le regala tiempo** al que reinicia, que cerrar tres veces aplica el perfil
    una sola vez, y que cancelar **no** aplica el perfil de cierre.
- `tools/check_profile_sync.py` y `tools/check_whitelist_sync.py` en verde.
- `node --check` en los dos `.js`. Balance de llaves y paréntesis en los 12 `.kt` tocados.

---

## 6. Cómo probarlo en equipo real (en este orden)

**La 1 y la 2 van primero y no son opcionales.**

1. **La tienda sigue andando.** Desplegar, abrir el panel con **Ctrl+F5**, ir a Ajustes →
   Tienda y **calcular la huella de cada app que ya estaba cargada**. Después, desde el
   celular, instalar una: tiene que funcionar igual que antes.
2. **Una entrada SIN huella no se instala.** Sacarle el `sha256` a una entrada desde la
   consola de Firebase: en el celular tiene que aparecer deshabilitada con
   *"⚠ Sin verificación"*. Es la prueba de que B.6 quedó cerrado de verdad.
3. **Una huella que NO coincide no se instala.** Poner un `sha256` inventado (64 caracteres
   hexadecimales cualquiera): el celular tiene que bajar el APK y **rechazarlo** con
   *"NO es el que el administrador publicó"*.
4. **Nivel 4 con un plazo corto — usar 2 horas para probar, no 2 días.** Aplicarlo a un
   equipo de descarte y confirmar: la tienda queda abierta, el lanzador es el normal, y
   WhatsApp Estados **sigue bloqueado**.
5. **Que cierre solo.** Esperar el plazo (o acortarlo desde la consola tocando
   `grace_deadline_wall`) y confirmar que a los ~20 s aparece el Nivel 1 aplicado y el
   panel lo muestra.
6. **★ La prueba del reloj, que es la que importa.** Con la gracia activa, **atrasar la hora
   del equipo un año** (hay que apagar `DISALLOW_CONFIG_DATE_TIME` a mano para poder
   hacerlo). El equipo tiene que cerrarse igual al cumplirse el tiempo de uso, y el panel
   tiene que decir **"vencido (tiempo de uso; el reloj del equipo no llegó…)"**.
7. **Reiniciar a mitad del período** y confirmar que el tiempo que faltaba no se reinició.
8. **El selector**: elegir un celular en el desplegable y confirmar que el botón dice su
   nombre; sin elegir ninguno, que esté deshabilitado.
9. **Cancelar el vencimiento** desde el panel y confirmar que el equipo **se queda como
   está** (no se cierra) y que el aviso desaparece.

---

## 7. Lo que queda abierto

- **La tienda todavía no tiene el flujo de "pedir una app".** Era la segunda mitad del
  PROMPT A y quedó para la próxima: el nodo `devices/<id>/appRequests/<paquete>`, la
  campanita en el panel y el botón de aprobar con las tres acciones de un toque. El prompt
  sigue vigente tal como está — solo que **su tarea 1 (el checksum) ya está hecha**.
- **El período de gracia no avisa cuando está por vencer.** Hoy el administrador lo ve si
  entra al panel. Una notificación en el celular a las 24 h y a la hora de vencer sería
  barata y evitaría la sorpresa.
- **No hay gracia por grupo.** Misma razón que B.12 y B.53: aplicar algo así a la flota
  entera con un clic es demasiado fácil de hacer sin querer.
- **La firma HMAC de los perfiles sigue siendo la clave pública de B.5.**

---

## 8. Mensaje de commit listo para copiar

```
feat(tienda/gracia): checksum obligatorio de APK (cierra B.6) y perfil con vencimiento

B.6 estaba abierto desde el principio y era el agujero mas grande que quedaba: la
Tienda administrada bajaba el APK de una URL y lo instalaba EN SILENCIO, como Device
Owner, sin verificar nada. ApkSignatureVerifier (B.37) no puede cubrirlo porque
compara contra el paquete YA instalado y la Tienda instala primeras instalaciones —
su propio comentario lo dice. Ahora el sha256 se publica en storeApps y se compara
contra el archivo en disco ANTES de levantar las restricciones de instalacion.

La Tienda falla CERRADO: sin sha256 no instala, y no hay interruptor para saltearlo
(A Bloq tiene este mismo codigo comentado con un "TEMPORARILY BYPASS" adentro: una
verificacion que parece existir y no existe — ver B.31). El OTA es la excepcion
deliberada: ahi Android ya exige la misma firma y el OTA es la via de rescate, asi
que la ausencia de hash no bloquea pero un hash que no coincide si.
El panel calcula la huella solo al cargar la app: un paso manual en un control de
seguridad es un paso que se saltea.

Ademas, el perfil Nivel 4 que pidio el dueno: bloquea lo claramente no kosher, deja
lo dudoso a criterio del usuario con la tienda abierta, y se cierra solo al vencer el
plazo. Lo valioso no es el temporizador: durante la gracia la auditoria de la lista
blanca (B.53) anota que dominios usa esa persona, asi que al cerrar se cierra con el
catalogo lleno en vez de a ciegas. El vencimiento lo sostienen DOS relojes —el de
pared y un acumulador de tiempo real que nadie puede mover— mas
DISALLOW_CONFIG_DATE_TIME, y lo garantiza el WatchdogWorker, que sobrevive a que
muera el proceso.

Y el selector de celular en los perfiles: antes habia que pegar un ANDROID_ID en un
prompt(), que es justo la friccion que los perfiles venian a eliminar.

Verificacion: kotlinc 2.0.21 contra stubs, 0 errores / 0 warnings, con 4 controles
negativos detectados; y TRES pruebas de comportamiento con 183 aserciones verdes y 13
controles negativos detectados — entre ellas que con el reloj movido diez anos
adelante el acumulador cierra igual, que un reinicio no regala tiempo, y que la
tienda no instala sin hash, con hash vacio, con hash mal formado ni con el archivo
cambiado. Sin Gradle y sin probar en equipo real.

Ver B.58 y INSTRUCCIONES_ANTIGRAVITY_2026-09-10_TIENDA_Y_GRACIA.md.

⚠️ AL DESPLEGAR: las entradas de storeApps que ya existen no tienen sha256, asi que
los celulares dejan de instalarlas. Hay que darle a "Calcular huella" en cada una
desde el panel (Ajustes -> Tienda). Es un clic por app.
```
