# Instrucciones para Antigravity — 10/9/2026 (tarde)
## Solicitudes de apps (B.59), detector de navegadores embebidos (B.60) y alta por QR (B.61)

> **Esto va ENCIMA del parche de B.58 (`Claude outputs/B58_tienda_y_gracia.patch`), que
> todavía no está aplicado en el disco.** No pisa nada de esa sesión: son commits
> distintos sobre archivos que en su mayoría no se tocan entre sí, y donde sí
> (`app.js`, `index.html`, `functions/index.js`, `LockSuiteFirebaseService.kt`,
> `PolicyManager.kt`, `FirebaseDeviceSync.kt`, `LoginActivity.kt`) el trabajo de B.58
> está adentro y respetado.

---

## 0. CÓMO APLICARLO. ESTE ORDEN Y NO OTRO.

```powershell
cd "E:\Documentos\Lock Suite segunda version"

git log -1            # tiene que decir 0.6.48 / codigo 111 (199a2de)
git status            # tiene que estar limpio

# 1) primero el de la otra sesión (B.6 + Nivel 4 con vencimiento)
git am "Claude outputs\B58_tienda_y_gracia.patch"

# 2) después este (B.59 + B.60 + B.61)
git am "Claude outputs\B59_B61_solicitudes_iab_qr.patch"

# 3) los tres chequeos de simetría, los tres tienen que salir en verde
python tools\check_profile_sync.py
python tools\check_whitelist_sync.py
python tools\check_command_sync.py     # ← nuevo en esta sesión

# 4) compilar
.\gradlew compileReleaseKotlin
```

Si `git am` del segundo parche falla, es que el primero no se aplicó: los tres commits
de acá tienen a `f6b6b8a` (el de B.58) como padre. `git am --abort` y volvé al paso 1.

---

## 1. LO PRIMERO AL DESPLEGAR, ANTES QUE CUALQUIER PRUEBA DE ACÁ

**Sigue vigente lo que dejó dicho el documento de B.58:** las entradas de `storeApps`
que ya existen no tienen `sha256`, así que **los celulares dejan de instalarlas**.
Panel → Ajustes → Tienda → **"Calcular huella"** en cada tarjeta roja. Un clic por app.

Si no hacés eso primero, la prueba 1 de B.59 (abajo) va a fallar por un motivo que no
tiene nada que ver con B.59, y se pierde media hora buscando en el lugar equivocado.

---

## 2. B.59 — PEDIR UNA APP DESDE EL CELULAR

**Qué cambió.** El botón "Bloqueada" de la Tienda deja de existir como estado final:
ahora dice **"Pedir"**. El usuario pide, y el administrador ve el pedido en una pestaña
nueva **🔔 Pedidos** con el contador de pendientes de toda la flota.

**Archivos:** `mdm/AppRequestManager.kt` (nuevo, Kotlin puro),
`util/FirebaseDeviceSync.kt`, `ui/auth/LoginActivity.kt`,
`service/LockSuiteFirebaseService.kt`, `admin-backend/functions/index.js`,
`admin-backend/public/app.js`, `admin-backend/public/index.html`,
`tools/check_command_sync.py` (nuevo).

**Orden de prueba en equipo real:**

1. **La regresión que va PRIMERO.** Abrí la Tienda en el celular y confirmá que una app
   permitida **sigue instalándose** igual que antes. Se tocó el botón de esa pantalla:
   si esto se rompió, no importa nada de lo demás.
2. Tocá **"Pedir"** en una app bloqueada → tiene que decir *"Pedido enviado al
   administrador"* y el botón pasar a **"Pedida"**.
3. Tocá "Pedir" otra vez en la misma → *"Ya la pediste"*. **No** se puede pedir dos veces.
4. **"Pedir otra app…"** → escribí `com.spotify.music` → entra. Escribí `hola` → tiene
   que decir *"Ese no es un nombre de paquete válido"* y **no** escribir nada en Firebase.
5. En el panel, pestaña **🔔 Pedidos**: el pedido tiene que estar, con el nombre del
   equipo y la hora. **Aprobar** → pide el PIN del equipo → y tiene que hacer las cuatro
   cosas: sumar la app a la lista blanca, abrirle los dominios (`SYNC_WHITELIST`),
   des-suspenderla y des-ocultarla, y avisarle al celular.
6. **★ En el celular tiene que llegar una notificación** *"✅ … : aprobada"*. Esta es la
   parte que evita que el usuario tenga que estar abriendo la Tienda a probar suerte.
7. Volvé a abrir la Tienda: la app ahora tiene que aparecer **instalable**.
   ⚠️ Si aparece permitida pero **no está en la tienda**, es lo esperado: aprobar **no
   sube el APK**. Cargala en Ajustes → Tienda con su URL y calculale la huella.
8. **Rechazar** otro pedido → notificación *"❌ … : rechazada"*, y en el celular el botón
   vuelve a decir "Pedir" pero **no deja pedir hasta mañana** (espera de 24 h).
9. Con el celular **apagado**, aprobá un pedido: el panel tiene que decir *"aprobada y
   guardada, pero el celular no contestó a: …"*. **No puede decir que salió todo bien.**

---

## 3. B.60 — DETECTOR DE NAVEGADORES EMBEBIDOS

Cierra B.44 sin la lista blanca de WebView que rechazaste el 4/9.

**Archivos:** `mdm/EmbeddedBrowserDetector.kt` (nuevo, Kotlin puro),
`service/LockSuiteAccessibilityService.kt`, `mdm/PolicyManager.kt`,
`util/FirebaseDeviceSync.kt`, `worker/WatchdogWorker.kt`,
`service/LockSuiteFirebaseService.kt`, `admin-backend/functions/index.js`,
`admin-backend/public/app.js`, `admin-backend/public/index.html`.

**⚠️ VIENE APAGADO Y EN SIMULACIÓN, Y ASÍ TIENE QUE QUEDAR HASTA QUE HAYA DATOS.**
Son dos interruptores separados a propósito: encenderlo **no** hace que bloquee.

**Orden de prueba:**

1. **★ LA PRUEBA 1 NO ES OPCIONAL: con el detector APAGADO, que no se haya roto nada.**
   Usá el equipo media hora normal — WhatsApp, Waze, Mercado Pago, Ajustes, el Wi-Fi.
   El detector está en el camino caliente de la Capa 3 y ese camino ya costó una sesión
   entera en B.13. Si el equipo se siente más lento, **pará acá y avisá**.
2. Encendé el detector desde el panel (Lista blanca → *Detector de navegadores
   embebidos* → elegí el celular → **Encender detector**). Sigue en simulación.
3. Usá el equipo un par de días normalmente. La lista de abajo se va llenando sola
   (la publica el `WatchdogWorker`, así que tarda hasta 15 minutos en aparecer).
4. **★ Mirá esa lista antes de tocar nada más.** Es lo que se va a empezar a bloquear.
   Si hay ahí una app que el usuario necesita, **no pases a bloquear**: avisá.
5. **Las tres exclusiones, probadas a mano en el equipo**, con el detector encendido:
   - **Portal cautivo**: conectate a un Wi-Fi con login (un bar, un aeropuerto). La
     página de "Iniciar sesión en la red" **tiene que abrir y dejarte entrar**. Esto ya
     rompió el equipo una vez (B.50).
   - **Alta de cuenta de Google**: Ajustes → Agregar cuenta → Google. **Tiene que
     dejarte llegar hasta el final.** Esto ya rompió el equipo una vez (B.43).
   - **`:admin-app`**: abrí la app de administración. **Tiene que funcionar entera.**
6. Recién ahí, **"Pasar a bloquear de verdad"** (pide confirmación). Volvé a hacer la
   prueba 5 completa.
7. Si algo se rompe: **"Volver a simulación"** desde el panel deja de bloquear al
   instante. Es la salida de emergencia y no pide confirmación.

---

## 4. B.61 — ALTA POR QR

**Archivos:** `receiver/DeviceAdminReceiver.kt`, `mdm/EnrollmentProfiles.kt`,
`mdm/PolicyManager.kt`, `util/ApkSignatureVerifier.kt`, `util/FirebaseDeviceSync.kt`,
`service/LockSuiteFirebaseService.kt`, `admin-backend/functions/index.js`,
`admin-backend/public/app.js`, `admin-backend/public/index.html`,
`admin-backend/public/qr.js` (nuevo), `tools/check_profile_sync.py`.

**⚠️ Esto necesita un equipo de descarte reseteado de fábrica. No se puede probar en
uno en uso.**

**Orden de prueba:**

1. **La regresión que va primero:** aplicá un perfil maestro desde el panel a un equipo
   ya dado de alta, como se venía haciendo. Tiene que seguir aplicando **todo**,
   incluidas las cuentas y el idioma. El recorte de `POST_ALTA` es **solo** para el
   camino del QR — si se coló en el camino normal, es un equipo menos protegido.
2. En el panel, Lista blanca → *Alta de un equipo nuevo por QR* → elegí Nivel 1 →
   **Generar datos del QR**. Tiene que salir el JSON con la huella de firma completa.
   Si dice que no hay huella todavía, es que ningún equipo publicó todavía
   `signatureChecksum`: esperá un ciclo del Watchdog en cualquier equipo actualizado.
3. **El código tiene que dibujarse ahí mismo** (versión 17, 85×85 módulos). Escanealo
   en el equipo de descarte reseteado: seis toques en la pantalla de bienvenida.
   ⚠️ **Esta es la única prueba que no se pudo hacer desde el contenedor**: que el
   código sea correcto está verificado contra un decodificador, pero que *Android lo
   acepte como aprovisionamiento* solo lo dice un equipo real.
4. **★ Cuando termine, TIENE QUE PODERSE AGREGAR LA CUENTA DE GOOGLE Y CAMBIAR EL
   IDIOMA.** Es el punto entero de esta función. Si no se puede, el recorte de
   `POST_ALTA` no funcionó y el equipo quedó inservible para el alta.
5. El panel tiene que mostrar ese equipo en **"Terminar un alta"**. Agregá la cuenta,
   dejá el idioma definitivo, y tocá **"Terminar alta"**.
6. Confirmá que **ahora sí** no se pueden agregar ni sacar cuentas y no se puede cambiar
   el idioma. Y que el equipo desaparece de esa lista.

---

## 5. EL CODIFICADOR DE QR: POR QUÉ ES PROPIO Y CÓMO SE VERIFICÓ

**`admin-backend/public/qr.js` es un codificador escrito para este proyecto.** No se
usa una biblioteca de un CDN, y no es capricho: `:admin-app` filtra los subrecursos por
lista blanca de host (`RESOURCE_ALLOWED_HOSTS`), así que un `<script src="https://cdn…">`
**no cargaría en el celular kosher** y el síntoma sería "el QR anda en la compu y en el
celular no", sin ningún error visible. Este archivo se sirve del mismo origen que el
panel y pasa el filtro sin tocar ninguna lista.

Hace **modo byte, nivel L, versiones 1 a 40**, y nada más.

**★ La primera vuelta NO pasó la verificación, y tenía dos bugs distintos.** Los dos
estructurales, ninguno en el álgebra:

1. **Las dos copias de la información de formato estaban traspuestas.** Como ocupan una
   fila 8 y una columna 8 que se cruzan, el error es perfectamente simétrico y "parece
   bien" al mirarlo.
2. **El temporizador se dibujaba antes que los patrones de alineación**, lo que hacía
   saltear los que caen sobre la línea de tiempo —como `(6, 22)` en la versión 7—.

El segundo explica el síntoma más confuso: **las versiones 1 a 6 salían perfectas y de
la 7 en adelante no coincidía un solo módulo.** Falta un patrón entero, el mapa de
reservados queda corrido, y el zigzag de datos se desplaza desde ahí.

**Si alguna vez esto vuelve a fallar, mirá el orden en que se dibujan los patrones fijos
y el mapa de reservados antes que el álgebra.**

**Cómo se verificó, por si hay que repetirlo:**

```bash
pip install qrcode opencv-python-headless --break-system-packages
# comparar la matriz módulo por módulo, FORZANDO modo byte en la referencia
#   qrcode.util.QRData(texto.encode(), mode=MODE_8BIT_BYTE)
#   ERROR_CORRECT_L  (ojo: en python-qrcode vale 1, no 0)
#   border=0, y mask_pattern fijado al que devuelve el JS
```

Resultado final: **84 casos idénticos módulo por módulo**, cubriendo las 40 versiones
(cada una llena y en su borde inferior) más hebreo, acentos y el payload real de
aprovisionamiento; **10 controles negativos, los 10 detectados** (entre ellos los dos
bugs de arriba reintroducidos a propósito); y **el círculo cerrado**: se renderizó el
payload real a imagen, se decodificó con OpenCV, y devolvió **los 608 bytes exactos del
JSON original**.

**Lo que sigue sin poder verificarse desde acá:** que un equipo Android real acepte ese
QR. La prueba 3 de la sección 4 es la única que lo dice.

---

## 6. MENSAJE DE COMMIT

No hace falta escribir ninguno: **los tres commits vienen adentro del parche** con su
mensaje completo. `git am` los aplica tal cual.

Si preferís aplastarlos en uno solo:

```
feat: pedidos de apps, detector de navegadores embebidos y alta por QR (B.59, B.60, B.61)

Cierra la segunda mitad del PROMPT A (pedir una app desde el celular y aprobarla de un
toque), cierra B.44 con un detector estructural que no necesita la lista blanca de
WebView que el dueno rechazo, y deja el alta por QR andando salvo el dibujo del codigo.

Ver B.59, B.60 y B.61 en LOCKSUITE_CONTEXTO_PARA_IA.md, y el orden de prueba en
INSTRUCCIONES_ANTIGRAVITY_2026-09-10_SOLICITUDES_IAB_QR.md.

Co-Authored-By: Claude Opus 5 <noreply@anthropic.com>
```

---

## 7. SIETE COSAS QUE PARECEN RULOS Y NO HAY QUE "SIMPLIFICAR"

1. **Los controles de navegación del detector están agrupados por FUNCIÓN, no en una
   lista plana.** El banco de pruebas encontró que con una lista plana,
   `swipe_refresh_layout` (el widget que tiene media app de Android) más un botón que
   diga "recargar" contaban dos controles y bloqueaban la app — pero son el **mismo**
   control en dos idiomas.
2. **`search_box` no está entre los ids de barra de direcciones.** Media app de compras
   tiene un WebView y un buscador propio, y ese buscador no deja ir a cualquier lado.
3. **El detector solo mira campos EDITABLES.** Una URL de solo lectura es el subtítulo
   "fuente: diario.com" que muestra medio catálogo.
4. **Los dos interruptores del detector son dos y no uno.** Encenderlo no puede alcanzar
   para que bloquee.
5. **En `POST_ALTA` las restricciones se OMITEN, no se ponen en `false`.** El importador
   trata "clave ausente" como "no se toca"; ponerlas en false las levantaría
   explícitamente en un equipo que ya las tuviera puestas.
6. **`enviarSolicitudApp()` decide adentro y devuelve el veredicto, no un texto.** Si la
   pantalla decidiera y después llamara a escribir, tarde o temprano aparece un camino
   que escribe sin decidir. Y con un String no se puede distinguir "entró" de "no entró"
   sin comparar cadenas.
7. **El panel pinta con `textContent` todo lo que viene del celular** (nombre de la app,
   nota del usuario, nombre del equipo, motivo del detector). El panel no puede confiar
   en una validación que corre del otro lado de la red.

---

## 8. VERIFICACIÓN QUE SE HIZO ACÁ (sin Gradle y sin equipo real, como siempre)

- **185 aserciones de comportamiento** en dos bancos que compilan **los archivos reales**
  (`AppRequestManager` y `EmbeddedBrowserDetector` no importan nada de Android a
  propósito): 108 + 77, **0 fallas**.
- **23 controles negativos** sobre esos bancos: **21 detectados**. Los otros dos
  resultaron ser guardas redundantes de `pareceUrl()` — sacar el chequeo del punto o el
  del espacio no cambia el resultado porque el chequeo del TLD ya los cubre. Se dejan
  por claridad y **se cuentan como redundantes, no como detectados**.
- **Dos controles negativos encontraron dos casos flojos del banco** y se reescribieron:
  el de `minutemaid` traía las dos palabras de la exclusión en la misma cadena, y el de
  "la URL de solo lectura no alcanza" no ponía la URL en ningún lado.
- **Type-check con `kotlinc` 2.0.21** contra stubs escritos a mano, con el código
  **extraído** de los archivos reales: el bloque nuevo de `FirebaseDeviceSync` (0
  errores / 0 warnings, 6 controles negativos, los 6 detectados) y el cableado nuevo de
  la Capa 3 (0 / 0, 7 controles negativos, los 7 detectados).
- **`node --check`** en los dos `.js`, con control negativo. Balance de llaves y
  paréntesis en los `.kt`. HTML sin desbalances nuevos respecto de `git HEAD`.
- **Los tres chequeos de simetría en verde**, incluido el nuevo:
  `check_command_sync.py` (183 ejecuta el celular / 184 permite la Function / 18 manda
  el panel, con `VERIFY_PIN` declarada como única excepción), `check_profile_sync.py`
  (con las dos simetrías nuevas de B.61, las dos con control negativo) y
  `check_whitelist_sync.py`.
- **El codificador de QR: 84 casos idénticos módulo por módulo contra `qrcode` de
  Python sobre las 40 versiones, 10 controles negativos los 10 detectados, y el payload
  real decodificado con OpenCV devolviendo los 608 bytes exactos.** Ver sección 5 — la
  primera vuelta no pasó y ahí está lo que se aprendió.
