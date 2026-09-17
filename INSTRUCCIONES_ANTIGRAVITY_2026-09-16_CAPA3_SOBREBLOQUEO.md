# INSTRUCCIONES PARA ANTIGRAVITY — 16/9/2026
## La Capa 3 bloqueaba de más en dos lugares, y no había forma de verlo desde el panel

**Empezá por acá si vas a aplicar, compilar, desplegar o probar lo del 16/9.**
Cubre B.67 (Mercado Pago), B.68 (Tefilon), B.69 (registro de rebotes) y B.70 (el
trabajo del 15/9 que estaba sin commitear).

---

## 0. LO PRIMERO, Y NO ES OPCIONAL: HAY DOS COMMITS, Y VAN EN ESTE ORDEN

Cuando esta sesión arrancó, el árbol de trabajo **no estaba limpio**: había cinco
archivos modificados del **15/9 ~20:45 UTC** que no son de esta sesión (el arreglo
del portal cautivo en aviones). Se commitearon **tal cual, sin tocar una línea**,
para que lo de hoy quede separado y revisable aparte.

`device_bash` no montó la carpeta (duodécima vez), así que **no hay `git` sobre el
disco del dueño desde la sesión de IA**. Los archivos SÍ están escritos en disco.
Lo que falta es que vos hagas los dos commits.

> **Los dos commits ya están hechos en el clon del contenedor, pero NO se pudieron
> pushear**: el proxy de git de la sesión responde `403 — CHKI541/Lock-Suite is not in
> this session's authorized repository set`. O sea que el repo público se puede clonar
> sin credenciales pero **no se puede escribir desde la nube**. El push lo hacés vos.
>
> **Los archivos YA están escritos en el disco**, así que la vía normal es `git add` +
> `git commit` con los mensajes de abajo. Si preferís los commits con su autoría y su
> fecha original, están como parches en `Claude outputs/`:
>
> ```
> Claude outputs/2026-09-16_0001_portal_cautivo_aviones.patch
> Claude outputs/2026-09-16_0002_capa3_sobrebloqueo.patch
> ```
>
> ⚠️ Para usarlos con `git am` hay que **descartar primero los cambios del working
> tree** (`git checkout -- .` sobre los archivos tocados), porque si no los parches no
> aplican: el contenido ya está en disco. Con `git add`+`git commit` no hace falta
> descartar nada. **Las dos vías son equivalentes; elegí una, no las dos.**

### Commit 1 — el trabajo recuperado del 15/9 (B.70)

```
git add app/src/main/java/com/ejemplo/locksuite/mdm/CaptivePortalPolicy.kt \
        app/src/main/java/com/ejemplo/locksuite/mdm/EmbeddedBrowserDetector.kt \
        app/src/main/java/com/ejemplo/locksuite/mdm/WhitelistCatalog.kt \
        admin-backend/public/catalog.js
git commit -F- <<'EOF'
fix(portal cautivo): que el guard no cierre la ventana en portales de avion

En portales de avion (KLM/Viasat/Panasonic) la red valida ANTES de que el usuario
termine de aceptar terminos o de cargar el voucher, y el guard de B.50 le cerraba
la ventana en la cara. Ahora la gracia se mide desde que la red valido
(VALIDATED_GRACE_MS 1,5s -> 20s) y ademas se exige que el usuario no haya tocado
la pantalla en 10s; los clicks y el scroll cuentan como senal de vida. Se suma
com.google.android.captiveportallogin al reconocimiento de la ventana, se lo
excluye del detector de navegadores embebidos, y se agregan los dominios de wifi
a bordo a la lista de infraestructura.

Ver B.70.
EOF
```

⚠️ **`LockSuiteAccessibilityService.kt` tiene cambios de las DOS tandas.** Si querés
separarlo de verdad, el commit 1 lleva solo la parte de portal cautivo de ese
archivo (`captiveValidatedAt`, el bloque de `TYPE_VIEW_CLICKED`/`SCROLLED`, y el
`lower.contains("captiveportal")`). Si no vale la pena el `git add -p`, metelo
entero en el commit 2 y dejalo dicho en el mensaje — no cambia nada funcional.

### Commit 2 — lo de hoy (B.67, B.68, B.69)

```
git add -A
git commit -F- <<'EOF'
fix(capa 3): dejar de cerrar apps por un marcador mal comparado y de echar al
usuario del asistente de Mercado Pago

Dos reportes del duenio, dos causas distintas, las dos medidas.

1) Tefilon se cerraba al abrirse (B.68). Su actividad de arranque se llama
   tfilon.tfilon.TfilonStartActivity, y el marcador "artactivity" del bloqueo del
   selector de foto se comparaba con contains() crudo: "artactivity" esta adentro
   de "StartActivity" (St + artActivity). LockSuite creia que el sidur era el
   catalogo de ilustraciones de Google y le mandaba GLOBAL_ACTION_BACK apenas
   aparecia la ventana, con el interruptor encendido de fabrica. Medido bajando el
   APK real de la Tienda y leyendo su manifiesto. Cuarta vez que el proyecto paga
   el mismo error de comparar por substring contra una palabra corta (B.9 p1,
   B.41 p3, B.60). Ahora se compara por SEGMENTO de nombre de clase.

2) Mercado Pago echaba al usuario del asistente (Mago) y de cobros de ANSES
   (B.67). La causa era la "red de seguridad" de B.13: "pantalla WebView + UNA
   palabra debil". Casi toda Mercado Pago es una pantalla WebView, asi que esa no
   era una red de seguridad: era la regla principal, y bastaba una palabra como
   "beneficio" o "descuento" en cualquier nodo. El asistente contesta texto libre
   y ANSES llama "beneficios" a sus prestaciones. Se estaba tratando la MENCION de
   una palabra como si fuera la SECCION. La decision se mudo a
   mdm/MercadoPagoOffersPolicy.kt (funcion pura con banco), el veto que arregla el
   asistente es estructural (hay un campo de texto editable => no es un catalogo),
   y las palabras solo se leen de textos cortos.

3) Registro unificado de rebotes de la Capa 3 (B.69). Hay nueve lugares que pueden
   cerrar una app y para el usuario los nueve se ven igual. mdm/Layer3Audit.kt
   anota que cerro, cuando y por que; se publica al panel y se dibuja en la ficha
   del celular. photoPickerSeenClasses ya existia y habria sido ciego justo para
   Tefilon, porque solo anota paquetes "relevantes". Con esto el reporte de hoy se
   contestaba en dos minutos.

Verificado: 77 aserciones de comportamiento contra los archivos reales, 0 rojas,
14 controles negativos los 14 detectados. Type-check con kotlinc 2.0.21 del bloque
extraido del servicio, 0 errores / 0 warnings, 6 controles negativos mas los 6
detectados. Los seis chequeos de simetria en verde. Ficha del celular renderizada
en Chromium sin errores de consola. SIN Gradle y SIN probar en equipo.

Ver B.67, B.68 y B.69.
EOF
```

---

## 1. QUÉ SE TOCÓ, ARCHIVO POR ARCHIVO

| Archivo | Qué cambió | Finales de línea |
|---|---|---|
| `mdm/MercadoPagoOffersPolicy.kt` | **NUEVO.** La decisión de "¿esto es la sección de ofertas?", como función pura. Toda la explicación está en su cabecera. | LF |
| `mdm/Layer3Audit.kt` | **NUEVO.** Anillo de 20 entradas con lo que cerró la Capa 3. | LF |
| `mdm/PhotoPickerPolicy.kt` | `contieneMarcador()` nueva: el marcador tiene que arrancar en un límite de segmento (`.`, `$`, `_`, `/`). Las dos listas de marcadores la usan. | LF |
| `service/LockSuiteAccessibilityService.kt` | El recorrido de MP ahora arma un **retrato** y delega; se borraron `MP_OFFERS_*`, `MpScanState`, `matchMpText` y `detectMercadoPagoOffers`; `foldAccents`/`containsWholeWord` **delegan** en la política (no se duplican); `Layer3Audit.anotar` cableado en **9** puntos de rebote. | **CRLF** |
| `mdm/PolicyManager.kt` | `getLayer3Audit()` / `clearLayer3Audit()`. | **CRLF** |
| `util/FirebaseDeviceSync.kt` | Publica `layer3Audit` al panel. | **CRLF** |
| `admin-backend/public/celular.html` | Tarjeta «🛑 Qué cerró la Capa 3» en Resumen; cache-buster `celular.js?v=2`. | **CRLF** |
| `admin-backend/public/celular.js` | `pintarLayer3()` y su llamada desde `pintarResumen()`. | **CRLF** |

**Escribir con el final de línea equivocado ensucia el `git status` entero sin
cambiar una línea de contenido** (B.10). Los archivos ya quedaron escritos con el
final que corresponde; esta tabla es por si tenés que reescribir alguno.

---

## 2. QUÉ MIRAR SI NO COMPILA

Lo único que **no** se pudo type-checkear contra el proyecto entero es el resto del
servicio, porque necesita el SDK de Android. Lo que sí se verificó: el bloque nuevo
se extrajo **por número de línea del archivo real** y compiló contra stubs con 0
errores y 0 warnings, y 6 controles negativos confirmaron que el chequeo estaba vivo.
Las tres cosas concretas que podrían fallar en Gradle:

1. **`node.isEditable`** — existe desde API 18 en `AccessibilityNodeInfo`. Si el
   linter se queja por el `minSdk 24`, no debería. Si por algún motivo falla,
   reemplazalo por `node.className?.toString()?.contains("EditText") == true` — es
   peor señal pero no cambia la forma de la decisión.
2. **`triggerBlock(packageName: String, motivo: String = "…")`** ahora tiene un
   parámetro con valor por omisión. Todas las llamadas viejas siguen compilando.
   Si tu versión de Kotlin se queja por el default en un método privado, pasá el
   motivo explícito en las tres llamadas.
3. **`foldAccents` y `containsWholeWord` son ahora expresiones de una línea** que
   delegan en `MercadoPagoOffersPolicy`. Si algún otro lugar del archivo las usaba
   con otra aridad, saldría acá — se verificó que no.

**Si algo no compila, NO revertir el archivo entero**: los tres puntos de arriba son
aislados y no tocan la forma de la decisión.

---

## 3. ORDEN DE PRUEBA EN EQUIPO REAL

Va ordenado por **cuál te avisa más rápido si algo se rompió**. Las dos primeras son
regresiones: si fallan, el arreglo es peor que el problema.

### ⚠️ 1. LA REGRESIÓN DE B.68 — que el selector de foto SIGA rebotando

- Contactos → editar un contacto → cambiar foto → el **catálogo de ilustraciones de
  Google tiene que rebotar**.
- **Repetilo inmediatamente después de ingresar el PIN.** Esa es la prueba de la
  causa 1 de B.47 (la sesión de administrador lo apagaba en silencio 5 minutos).
- **Adjuntar una foto en WhatsApp tiene que seguir funcionando.**
- Sacar una foto con la cámara y recortarla para un contacto: tiene que andar.

### ⚠️ 2. LA REGRESIÓN DE B.67 — que las ofertas de Mercado Pago SIGAN rebotando

- Entrar a la sección de **ofertas / Mercado Puntos de verdad**: tiene que rebotar.
- **Si NO rebota**, no toques el código a ciegas: andá al panel → ficha del celular
  → Resumen → «Qué cerró la Capa 3». La línea de `mp-ofertas` te dice con qué se
  quedó corto, y agregás esa frase a `FRASES_FUERTES` o ese id a `IDS_DE_VISTA` en
  `MercadoPagoOffersPolicy.kt`. **No vuelvas a la regla de una sola palabra débil.**
- **Hacer una transferencia real** (regresión que B.62 ya dejó anotada).

### 3. Lo que el dueño reportó — que ya NO bloquee de más

- **★ Abrir Tefilon.** No se tiene que cerrar.
- **★ Abrir el asistente (Mago) y conversar**, pidiéndole a propósito algo que lo
  haga nombrar descuentos, beneficios o Mercado Puntos. No tiene que rebotar.
- **★ Entrar a cobros de ANSES** y completar el flujo.
- Recorrer inicio, tarjetas, QR, actividad y comprobantes de Mercado Pago.

### 4. El registro nuevo (B.69)

- Provocá un rebote a propósito (Ajustes → Accesibilidad con `acc_protect_bounce_settings`
  encendido) y confirmá que aparece en la tarjeta del panel.
- Repetí el mismo rebote cinco veces: tiene que quedar **una** línea con `×5`, no cinco.
- En un equipo sin rebotes, la tarjeta tiene que decir que no cerró nada, no quedar vacía.

### 5. B.70 — portal cautivo

- Un portal cautivo real (el Wi-Fi de un bar o un shopping alcanza): que se pueda
  completar el login sin que la ventana se cierre a mitad del trámite.
- Y que **siga cerrándose sola** cuando el usuario terminó y dejó de tocarla.
- Mirar `captivePortalForcedCloses` en el panel: es el número que delata que el
  guard cierra de más.

---

## 4. DESPLIEGUE

- **`hosting` sí, `functions` no.** No hay comandos FCM nuevos, así que
  `ALLOWED_COMMANDS` no cambió. `celular.html` y `celular.js` sí cambiaron.
- **Cache-buster: `celular.js?v=2`.** `app.js` sigue en `v=39` porque no se tocó.
  Al abrir la ficha del celular después de desplegar, **Ctrl+F5**.
- **El versionCode no se subió a mano.** `deploy_all.ps1` hace `currentCode + 1` solo.
  Si compilás a mano, subilo por encima de **114**.
- Los seis chequeos de simetría ya dieron verde en esta sesión, pero corrélos igual
  después de aplicar los commits — son segundos.

---

## 5. SIETE COSAS QUE NO HAY QUE "SIMPLIFICAR"

1. **El veto por campo editable de `MercadoPagoOffersPolicy` va ANTES que cualquier
   palabra.** Es lo único que arregla el asistente sin depender de qué conteste. Si
   alguien lo saca "porque las ofertas también tienen buscador", vuelve el bug.
2. **Las palabras de Mercado Pago solo se leen de textos cortos (≤ 48 caracteres).**
   Ese corte es lo que separa un título de sección de la prosa que habla de algo.
3. **Los marcadores de `PhotoPickerPolicy` se comparan por SEGMENTO, no por
   substring.** Volver a `contains` crudo es volver a cerrar Tefilon.
4. **`Layer3Audit` persiste en disco y se agrupa por repetición.** En memoria se
   borraría justo cuando el equipo está inestable, y sin agrupar un rebote en bucle
   tapa todo lo demás.
5. **`Layer3Audit` anota también lo que NO bloqueó.** Ver que una pantalla legítima
   pasó raspando es lo que permite corregir un umbral antes de que lo reporten.
6. **`foldAccents`/`containsWholeWord` delegan, no se duplican.** Dos copias del
   mismo algoritmo fue la causa 1 de B.18.
7. **El id de vista le gana al veto, y el veto le gana a las palabras.** Ese orden
   es la especificación, no un detalle de implementación.

---

## 6. LO QUE NECESITA AL DUEÑO Y NO A VOS

- **Probar en el equipo real.** Nada de esto está probado en un celular: esta sesión
  no puede compilar ni desplegar.
- **Decidir si `block_contact_photo_picker` sigue encendido de fábrica.** Hoy lo
  está, y es lo que hizo que el bug de B.68 le pegara a toda la flota sin que nadie
  lo pidiera. Con el arreglo el riesgo baja mucho, pero es una decisión de producto.
- **Waze sigue sin reemplazar** (B.66): el APK de la Tienda está firmado por un
  tercero (`ANDROID-KOSHER/YOLEVI`), no por Google.

---

## 7. LO QUE QUEDÓ ABIERTO A PROPÓSITO

- **La tarjeta del registro está solo en la ficha nueva (`celular.html`), no en el
  panel viejo (`index.html`).** La ficha es el lugar donde el dueño ya mira un
  celular concreto; duplicarlo en los dos sería la quinta repetición del problema
  que B.65 vino a resolver.
- **No hay comando para limpiar el registro desde el panel.** `clearLayer3Audit()`
  existe en `PolicyManager` pero no está cableado a ningún comando FCM: agregarlo
  implica tocar `ALLOWED_COMMANDS` y desplegar `functions`, y el anillo de 20 se
  recicla solo. Si después se quiere, es un comando de tres líneas.
- **El registro no distingue "rebotó y funcionó" de "rebotó y el usuario volvió a
  entrar".** Alcanzaría con mirar la cuenta de repeticiones, pero no está medido.
