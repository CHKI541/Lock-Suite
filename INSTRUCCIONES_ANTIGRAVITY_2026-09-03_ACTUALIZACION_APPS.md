# Actualización de apps por Play Store — ocho defectos corregidos (3/9/2026)

Sesión de Claude por el puente al dispositivo. **Código escrito en el disco y
type-checkeado; NO compilado con Gradle, NO probado en equipo.** Sin `device_bash`
no hubo `git`: el commit lo tenés que correr vos, y está listo al final.

Resumen técnico en la sección **B.41** de `LOCKSUITE_CONTEXTO_PARA_IA.md`.

---

## 0. Lo primero, porque cambia el orden de todo

El dueño reportó que **Banco Ciudad no se actualizaba en un CAT S22 Flip con
Android 11**, y que en otros equipos el sistema sí funcionaba. A mitad de la
sesión verificó el equipo y avisó: **estaba sin espacio.**

Es la primera causa **medida** que tiene este subsistema en vez de inferida. Vale
anotar la lección de método, que es la misma que dejó B.18 (*"medir la capa antes
de elegir la causa"*): mirar el espacio libre habría ahorrado toda la parte de
adivinanza. Por eso el arreglo principal de esta tanda no es una heurística mejor:
es una **medición** antes de empezar.

**Consecuencia práctica para las pruebas: la prueba 1 hay que hacerla ANTES de
liberar espacio.** Si liberás espacio primero, perdés la única oportunidad de
verificar en condiciones reales el arreglo más importante.

### El equipo, porque explica casi todo

**CAT S22 Flip:** 480×640 px en 2,8" (~286 ppi), Snapdragon 215, **2 GB de RAM**,
16 GB de almacenamiento, **Android 11 (Go edition)**. Es el equipo más chico y más
lento de la flota. Todo lo que esté calibrado contra un teléfono normal falla acá.
Conviene tenerlo como equipo de referencia para lo que venga.

---

## 1. Qué se tocó

| Archivo | Qué cambió |
|---|---|
| `util/PlayButtonFinder.kt` | **Reescrito.** Franja en dp en vez de fracción; `debugLabels` de toda la pantalla; diagnóstico de pantallas de error; `renderedNodes`; `scrollable`; `"iniciar"` fuera de `OPEN_WORDS`. |
| `util/PlayUpdateSessionWatcher.kt` | Tres marcas de tiempo nuevas (`lastProgressAt`, `sessionFailedAt`, `sawSessionAt`) para poder distinguir "baja bien" de "se trabó" de "el sistema la abortó". |
| `util/UpdateFlowManager.kt` | Verificaciones previas (espacio con `StatFs`, Play Store habilitada); resultados nuevos; motivo legible; `unlockPlayStore()` en cada relanzamiento; fallback a navegador **eliminado**. |
| `util/FirebaseDeviceSync.kt` | Dos campos nuevos en `updateFlow`: `lastResultReason` y `freeSpaceMb`. |
| `service/LockSuiteAccessibilityService.kt` | `scanAndAct()` reordenado y con relojes nuevos; `playStoreBounds()`; `diagnosisResult()`; `finishUpdateWithBestReason()`; `safeScanPlayStore()`; desplazamiento de la ficha. |

---

## 2. Los ocho defectos, en orden de daño

1. **Falso éxito: "la app ya está actualizada".** Dos salidas medían el tiempo
   desde que aparecía la **ventana** de Play Store — que existe apenas arranca la
   Activity, con la ficha en blanco. En un Snapdragon 215 la ficha tarda 4-8 s: a
   los 3,5 s el flujo concluía "no hay botón" sobre una pantalla vacía y cerraba
   informando que ya estaba al día. **Esto explica el "en otros equipos funciona".**
   Ahora hacen falta contenido dibujado (`Result.rendered`, ≥ 6 nodos con texto) **y**
   tiempo (`UPDATE_CARD_RENDER_MS = 9 s`, techo `UPDATE_CARD_GIVEUP_MS = 30 s`). Y
   una ficha dibujada sin botones ya no se informa como "al día" sino como error.
2. **Cuelgue de 10 minutos.** `sawSession` no vuelve nunca a `false` y el paso 2 de
   `scanAndAct()` salía con `return` apenas valía `true` — el freno por estancamiento
   está más abajo **en la misma función** y no se alcanzaba nunca. Ahora: sesión
   abortada sin reemplazo (6 s de gracia) o `DOWNLOAD_STALL_MS = 150 s` sin avanzar.
3. **"Iniciar sesión" se leía como "Abrir".** `"iniciar"` estaba en `OPEN_WORDS`.
   **LockSuite se instala con el equipo sin cuenta de Google**, así que ese es el
   estado de fábrica del procedimiento, no un caso raro.
4. **La franja de botones descartaba el botón bueno.** `BAND_BOTTOM = 0.62` asume
   ~800 dp de alto; el encabezado de Play Store mide ~250 dp **en cualquier equipo**,
   o sea 30 % en un teléfono normal y **70-80 %** en este. Ahora se mide en dp
   (`PREFERRED_BOTTOM_DP = 260`, `BAND_BOTTOM_DP = 340`). Queda **más estricto** en un
   teléfono normal (42 % contra 62 %), o sea que también achica el bug 4 de B.9.
5. **`debugLabels` llegaba vacío justo donde hacía falta**, porque usaba el mismo
   filtro de franja que fallaba. Ahora se juntan de toda la pantalla, con la
   posición marcada (`@NNNpx`) cuando caen fuera.
6. **No había desplazamiento.** Android no instancia en el árbol lo que no está
   dibujado: en 2,8" la fila `[Desinstalar] [Actualizar]` puede quedar bajo el borde.
   Ahora hasta 3 `ACTION_SCROLL_FORWARD`, **solo antes del primer clic**.
7. **Ninguna pantalla de error se reconocía.** Nueva tabla `Diagnosis` multiidioma
   (sin espacio / sin cuenta / incompatible / no encontrada / red / esperando Wi-Fi
   / error de tienda), comparada **solo contra textos ≤ 90 caracteres** para que la
   descripción de una app no pueda dispararla, y evaluada **antes de cualquier clic**.
8. **`openStore()` caía a un NAVEGADOR.** Inútil (desde la web no se instala) y
   peligroso (equipo kosher, restricciones levantadas por 10 min). Reemplazado por
   un segundo `market://` sin fijar paquete.

### Cosas que parecen simplificables y NO hay que tocar

- **`updateSessionCandidatesTried == 0` en el desplazamiento y en la salida
  "sin botones".** Después de un clic la fila de botones se convierte en fila de
  progreso y también se ve "sin candidatos": sin esa guarda, el flujo abortaría una
  descarga que arrancó bien.
- **El diagnóstico va antes del clic.** En la pantalla de "sin espacio" el único
  botón grande abre el administrador de almacenamiento del sistema: apretarlo saca
  al usuario de la tienda con la pantalla todavía tapada. Por eso también entró en
  `FORBIDDEN_WORDS`.
- **El fallback a navegador de `openStore()`.** No volver a ponerlo.
- **`setUninstallBlocked(target, true)` durante todo el flujo.** Es lo que hace
  seguro probar candidatos a ciegas. Sin eso, el enfoque entero no va.
- **La estimación de espacio conservadora** (`3 × APK + 250 MB`, piso 400 MB). Es
  preferible pedir 300 MB de más que dejar al dueño diez minutos mirando negro.

---

## 3. Verificación ya hecha

`kotlinc` 2.0.21 contra stubs de la API de Android escritos para esto:
**0 errores / 0 warnings** sobre `PlayButtonFinder.kt`, `PlayUpdateSessionWatcher.kt`,
`UpdateFlowManager.kt` y el bloque nuevo de `LockSuiteAccessibilityService.kt`.

**Seis controles negativos, los seis detectados** (método inexistente, tipo
equivocado, campo inexistente en `WalkState`, `scan()` con la aridad vieja, campo
mal escrito de `Result`, constante inexistente de `UpdateFlowManager`). Sin control
negativo, un "0 errores" puede ser simplemente que no compiló nada.

El bloque de `LockSuiteAccessibilityService.kt` se verificó **extrayendo el código
real del archivo** a un banco de pruebas con los helpers preexistentes stubbeados,
no transcribiéndolo — que es la trampa en la que cayó el informe del 2/9 (citaba un
`try/catch` que no existe).

**Falta:** `./gradlew compileDebugKotlin` (o `assembleRelease`) y la prueba en equipo.

---

## 4. Orden de prueba en equipo real

> **La 1 va ANTES de liberar espacio.** Es la única prueba de la causa medida y no
> se puede repetir después.

1. **Equipo todavía sin espacio, actualización desde el panel.** Esperado: rebota
   **al instante**, **sin tapar la pantalla**, con el texto de cuántos MB faltan; el
   panel muestra `lastResult = NO_SPACE` y `lastResultReason` con los números, y
   `freeSpaceMb` con lo que queda.
2. **Liberar espacio y repetir con Banco Ciudad.** Esperado: instala sola, con el
   porcentaje avanzando en la pantalla negra, y al terminar Play Store queda
   re-suspendida y las restricciones restauradas.
3. **Una app que ya esté al día.** Esperado: sale sola con "ya está actualizada", y
   **no antes** de que la ficha se dibuje (o sea, ya no a los 3,5 s de abrir).
4. **Una app no publicada / incompatible.** Esperado: "no encontró la ficha" o "no
   es compatible" — **nunca** "ya está actualizada".
5. **Mirar `debugLabels` del S22 Flip en el panel.** Tienen que llegar **no vacías**.
   Las que caigan fuera de la franja vienen marcadas `@NNNpx`: con ese número se
   termina de calibrar `BAND_BOTTOM_DP` para ese equipo si hiciera falta.
6. **Repetir 2 y 3 en un teléfono normal.** Es la contraprueba de que la franja más
   estricta (42 % contra 62 %) no dejó de encontrar el botón donde antes lo encontraba.
7. **Cancelar a mitad de descarga** y confirmar que vuelve a la pantalla común con
   todo re-bloqueado.
8. **Con la Accesibilidad apagada**, confirmar que el flujo se **niega** a arrancar
   en vez de dejar Play Store abierta.

Diagnóstico por ADB si algo no cierra:

```
adb logcat -s LockSuite_Update:* LockSuite_Session:* LockSuiteAccessibility:*
adb shell df /data
```

---

## 5. Lo que NO se tocó y conviene hacer antes

**El hallazgo 1 de B.40**: `app.js` (líneas 821-833) contempla `applied` y `failed`
pero **no `rejected`**, que es lo que el celular escribe. Es el que hace visible
desde el panel todo lo de esta tanda. Los motivos nuevos viajan por
`updateFlow.lastResultReason`, que el panel **sí** lee, así que la prueba 1 se puede
hacer igual — pero conviene cerrar B.40-1 primero.

**Panel, pendiente menor:** mostrar `updateFlow.lastResultReason` y `freeSpaceMb` en
la tarjeta del dispositivo. Hoy los campos se publican y nadie los dibuja.

---

## 6. Commit

```
git add app/src/main/java/com/ejemplo/locksuite/util/PlayButtonFinder.kt \
        app/src/main/java/com/ejemplo/locksuite/util/PlayUpdateSessionWatcher.kt \
        app/src/main/java/com/ejemplo/locksuite/util/UpdateFlowManager.kt \
        app/src/main/java/com/ejemplo/locksuite/util/FirebaseDeviceSync.kt \
        app/src/main/java/com/ejemplo/locksuite/service/LockSuiteAccessibilityService.kt \
        LOCKSUITE_CONTEXTO_PARA_IA.md \
        INSTRUCCIONES_ANTIGRAVITY_2026-09-03_ACTUALIZACION_APPS.md
```

```
fix(actualizacion): dejar de informar exito falso y detectar por que Play Store no puede actualizar

El sintoma era "Banco Ciudad no se actualiza en un CAT S22 Flip (Android 11)",
con el sistema andando bien en otros equipos. La causa real la midio el dueno a
mitad de la sesion: el celular estaba sin espacio. Son ocho defectos, y varios se
tapaban entre si.

1. Falso exito. Las dos salidas de "ya esta actualizada" contaban 1,5 s y 3,5 s
   desde que aparecia la VENTANA de Play Store, que existe apenas arranca la
   Activity con la ficha todavia en blanco. En un Snapdragon 215 con 2 GB la ficha
   tarda de 4 a 8 s: el flujo concluia "no hay boton" sobre una pantalla vacia y
   cerraba informando que la app ya estaba al dia. Por eso en un telefono rapido
   andaba. Ahora hacen falta contenido dibujado Y tiempo, y una ficha dibujada sin
   botones se informa como error, no como "al dia".

2. Cuelgue de 10 minutos sin espacio. sawSession no vuelve nunca a false y el
   ciclo salia con return antes del freno por estancamiento, asi que la pantalla
   quedaba en "Descargando..." hasta el watchdog. El estado descargando ahora
   tiene reloj propio: sesion abortada sin reemplazo, o 150 s sin avanzar.

3. "Iniciar sesion" se leia como el boton "Abrir" ("iniciar" estaba en OPEN_WORDS),
   asi que un equipo sin cuenta de Google cerraba con "ya esta al dia" a los 1,5 s.
   LockSuite se instala justamente sin cuenta de Google.

4. La franja de botones estaba en fraccion de pantalla (62 %) y asumia ~800 dp de
   alto. El encabezado de Play Store mide ~250 dp en cualquier equipo: 30 % en un
   telefono normal y 70-80 % en una pantalla de 2,8". Ahora se mide en dp desde
   arriba, lo que ademas queda mas estricto en un telefono normal (42 %).

5. debugLabels usaba el mismo filtro de franja, asi que llegaba vacio al panel
   justo en el equipo donde hacia falta. Ahora se juntan de toda la pantalla.

6. No habia ningun desplazamiento: en una pantalla chica la fila de botones queda
   bajo el borde visible y Android no la instancia en el arbol de accesibilidad.
   Ahora hasta 3 scrolls, solo antes del primer clic.

7. No se reconocia ninguna pantalla de error de Play Store. Nueva tabla Diagnosis
   multiidioma (sin espacio, sin cuenta, incompatible, no encontrada, red,
   esperando Wi-Fi, error de tienda), evaluada antes de cualquier clic porque en
   la pantalla de "sin espacio" el unico boton grande abre Ajustes.

8. openStore() caia a un NAVEGADOR si market:// fallaba: inutil para actualizar y
   peligroso en un equipo kosher con las restricciones levantadas 10 minutos.

Ademas: verificacion previa de espacio (StatFs) y de Play Store habilitada ANTES
de tapar la pantalla, motivo legible publicado al panel (lastResultReason,
freeSpaceMb), avisos accionables 5 s en pantalla en vez de 1,8 s, Play Store
des-suspendida en cada relanzamiento, y medicion contra la ventana real de Play
Store en vez de displayMetrics del servicio.

Verificado con kotlinc 2.0.21 contra stubs: 0 errores / 0 warnings, con seis
controles negativos, los seis detectados. SIN COMPILAR CON GRADLE Y SIN PROBAR EN
EQUIPO. Detalle y orden de prueba en INSTRUCCIONES_ANTIGRAVITY_2026-09-03_ACTUALIZACION_APPS.md
y en B.41 de LOCKSUITE_CONTEXTO_PARA_IA.md.
```

> ⚠️ **No uses `deploy_all.ps1` para esto todavía**: hace `git add .` y commitearía
> junto todo lo que haya sin commitear (incluido lo del 2/9 noche que sigue
> pendiente). Commiteá esto aparte primero.
