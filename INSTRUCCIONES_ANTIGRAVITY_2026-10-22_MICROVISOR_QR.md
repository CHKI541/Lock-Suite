# Instrucciones para Antigravity — 22/10/2026 — Microvisor de verificaciones de Google (B.92): el SMS y el escáner de QR

**Leé primero `LOCKSUITE_CONTEXTO_PARA_IA.md`, punto B.92 (la "revisión del 22/10" de arriba de todo).** Este archivo es la parte operativa: qué se tocó, cómo compilarlo, el commit, el despliegue y las pruebas en el celular.

## 0. El resumen en tres líneas

1. El QR de Google ("Verificá tu información para continuar") termina con el celular **mandando un SMS a Google**: la página `/devicephoneverification/consent` lo dice textual y tiene un botón "Send SMS". El microvisor de 0.6.64 bloqueaba todo `sms:`, así que **la verificación no podía terminar nunca**. Ahora el pedido de SMS se valida y se abre Mensajes con el borrador.
2. En Android 12+ la **cámara del sistema no le pasa el link a LockSuite** (va al navegador, que está suspendido). Ahora LockSuite tiene **su propio escáner de QR** (CameraX + ZXing), un widget "Verificar Google" y el botón de Ajustes Kosher.
3. Nada de esto se compiló con Gradle ni se probó en un celular: eso es lo tuyo. Las pruebas puras (33) y un type-check de la parte Android sí se corrieron en el contenedor.

## 1. Antes de tocar nada

```powershell
cd "E:\Documentos\Lock Suite segunda version"
git log -1 --oneline     # tiene que ser dfa76f4 (0.6.64 / código 127)
git status
```

`git status` tiene que mostrar **exactamente** esto (y si aparece algo más, es de otra sesión: commitealo aparte, antes o después, pero no mezclado):

```
 M LOCKSUITE_CONTEXTO_PARA_IA.md
 M app/build.gradle.kts
 M app/src/main/AndroidManifest.xml
 M app/src/main/java/com/ejemplo/locksuite/mdm/GoogleVerificationPolicy.kt
 M app/src/main/java/com/ejemplo/locksuite/ui/launcher/KosherSettingsActivity.kt
 M app/src/main/java/com/ejemplo/locksuite/ui/verification/GoogleVerificationActivity.kt
 M app/src/test/java/com/ejemplo/locksuite/mdm/GoogleVerificationPolicyTest.kt
 M gradle/libs.versions.toml
?? INSTRUCCIONES_ANTIGRAVITY_2026-10-22_MICROVISOR_QR.md
?? app/src/main/java/com/ejemplo/locksuite/ui/verification/GoogleQrScanner.kt
?? app/src/main/java/com/ejemplo/locksuite/util/QrLuminanceDecoder.kt
?? app/src/main/java/com/ejemplo/locksuite/widget/GoogleVerifyWidgetProvider.kt
?? app/src/main/res/drawable/ic_qr_verify.xml
?? app/src/main/res/layout/widget_google_verify.xml
?? app/src/main/res/xml/google_verify_widget_info.xml
?? app/src/test/java/com/ejemplo/locksuite/util/QrLuminanceDecoderTest.kt
```

En `Claude outputs/` (que está en `.gitignore`, así que no aparece en `git status`) quedan de respaldo `b92_revision_2026-10-22.patch` —el mismo cambio en formato parche, por si algún archivo del disco no coincidiera: `git apply --check` y `git apply`— y `b92_commit_msg_2026-10-22.txt`, el mensaje del commit. No hace falta aplicar el parche: los archivos ya están escritos.

## 2. Qué se tocó, archivo por archivo

| Archivo | Qué |
|---|---|
| `mdm/GoogleVerificationPolicy.kt` | **Pura, con banco.** Nuevo: `parseSmsHandoff()` (valida `sms:`/`smsto:`/`intent:` de SMS: un solo destinatario de 3-15 dígitos, texto ≤ 320 sin caracteres de control, decodificación `%XX` que **no** convierte `+` en espacio), `isSmsHandoffContext()` (el SMS solo se acepta con la página en `/devicephoneverification`), subrecursos de `accounts.google.com` por raíz de app + `/_/` (antes el RPC del desafío v3 daba 403; `/checkcookie` sale), `chromeLikeUserAgent()`, `describeForLog()` (nunca la query) y `describeSmsShapeForLog()` (la forma, no el contenido). `isNavigationAllowed()` **no cambió**. |
| `ui/verification/GoogleVerificationActivity.kt` | Tres modos (inicio / escáner / página). El SMS → `ACTION_SENDTO smsto:` con el paquete de Mensajes predeterminado (avisa si `DISALLOW_SMS`). `onRenderProcessGone` (antes faltaba y un renderizador muerto mataba el proceso de la VPN). `onShowFileChooser` devuelve `true`. Menú de selección por título del sistema + sin `Intent`, ocultando en vez de borrar, con `Callback2`. `onNewIntent` arreglado. Estado del WebView guardado si el sistema mata el proceso. Cookies y almacenamiento web borrados al cerrar. `onPermissionRequest` deniega. Diagnóstico a `logcat -s LockSuiteVerif` y a `Layer3Audit` (origen `verificacion-google`, con tope por sesión). |
| `ui/verification/GoogleQrScanner.kt` | **Nuevo.** Escáner: CameraX (`Preview` + `ImageAnalysis` 1280×720, `KEEP_ONLY_LATEST`) y ZXing. Solo acepta lo que pase `isNavigationAllowed`; otro QR → aviso y sigue. Respeta la cámara deshabilitada por el administrador (`getCameraDisabled` / `no_camera`). Permiso: Device Owner se lo concede solo; si Android no lo deja, diálogo normal. |
| `util/QrLuminanceDecoder.kt` | **Nuevo, puro.** ZXing sobre el plano Y con `rowStride`. |
| `widget/GoogleVerifyWidgetProvider.kt` + `res/layout/widget_google_verify.xml` + `res/xml/google_verify_widget_info.xml` + `res/drawable/ic_qr_verify.xml` | **Nuevo.** Widget 1×1 "Verificar / Google (QR)" que abre directo el escáner (`requestCode` 104). Mismo fondo que el de Actualizar. |
| `ui/launcher/KosherSettingsActivity.kt` | Solo el texto del botón: "Verificación de Google (escanear QR)". El lanzador kosher no aloja widgets: en esos equipos este es el camino. |
| `AndroidManifest.xml` | `CAMERA` + `uses-feature` de cámara con `required="false"`; el receptor del widget; comentario sobre Android 12+ en la Activity. |
| `app/build.gradle.kts` + `gradle/libs.versions.toml` | CameraX 1.4.2 (`camera-camera2`, `camera-lifecycle`, `camera-view`) y `com.google.zxing:core:3.5.3`. |
| `test/.../GoogleVerificationPolicyTest.kt` | 10 pruebas nuevas (las 16 viejas siguen igual). |
| `test/.../util/QrLuminanceDecoderTest.kt` | **Nuevo.** 7 pruebas con QR generados por ZXing como cuadros de cámara. |

## 3. Compilar y probar en la PC

```powershell
.\gradlew :app:testDebugUnitTest --tests "com.ejemplo.locksuite.mdm.GoogleVerificationPolicyTest" --tests "com.ejemplo.locksuite.util.QrLuminanceDecoderTest"
.\gradlew :app:testDebugUnitTest
.\gradlew :app:assembleDebug
python tools/check_command_sync.py
python tools/check_panel_commands.py
python tools/check_whitelist_sync.py
python tools/check_profile_sync.py
```

En el contenedor dieron **33/33** las dos clases nuevas/tocadas. La parte de Compose y CameraX se type-checkeó contra el `android.jar` real y **stubs** (la red no llegaba a Maven), así que lo primero que puede fallar es una firma de librería. Qué cambiar **sin tocar comportamiento**:

| Si falla… | Cambiá esto |
|---|---|
| No resuelve CameraX 1.4.2 | Subí `camerax` en `libs.versions.toml` a la última estable 1.5.x/1.6.x. Las APIs que se usan (`ProcessCameraProvider`, `ResolutionSelector`, `PreviewView`) son las mismas. |
| `Icons.Default.QrCodeScanner` no existe | `Icons.Default.QrCode` o `Icons.Default.CameraAlt` (los dos de `material-icons-extended`, que ya está). Son dos usos en `GoogleVerificationActivity.kt`. |
| `androidx.lifecycle.compose.LocalLifecycleOwner` no existe | `androidx.compose.ui.platform.LocalLifecycleOwner` (la vieja, deprecada). Una línea en `GoogleQrScanner.kt`. |
| `ResolutionStrategy.FALLBACK_RULE_CLOSEST_HIGHER_THEN_LOWER` no existe | `ImageAnalysis.Builder().setTargetResolution(Size(1280, 720))` en vez de `setResolutionSelector(...)` (deprecado, anda igual). |
| R8 (release) avisa `Missing class` de ZXing | `-dontwarn com.google.zxing.**` en `app/proguard-rules.pro`. No hacen falta `-keep`: ZXing no usa reflexión. |
| Lint marca `InlinedApi` por `android.R.id.textAssist` | Es una constante (se compila adentro). Ignorar. |

## 4. El commit — ANTES de `deploy_all.ps1`

`deploy_all.ps1` hace `git add .` y lo commitea todo junto como "Actualización automática…". Para que este cambio quede separado y revisable, commitealo antes:

```powershell
git add LOCKSUITE_CONTEXTO_PARA_IA.md INSTRUCCIONES_ANTIGRAVITY_2026-10-22_MICROVISOR_QR.md app/build.gradle.kts gradle/libs.versions.toml app/src/main/AndroidManifest.xml app/src/main/java/com/ejemplo/locksuite/mdm/GoogleVerificationPolicy.kt app/src/main/java/com/ejemplo/locksuite/ui/launcher/KosherSettingsActivity.kt app/src/main/java/com/ejemplo/locksuite/ui/verification/GoogleVerificationActivity.kt app/src/main/java/com/ejemplo/locksuite/ui/verification/GoogleQrScanner.kt app/src/main/java/com/ejemplo/locksuite/util/QrLuminanceDecoder.kt app/src/main/java/com/ejemplo/locksuite/widget/GoogleVerifyWidgetProvider.kt app/src/main/res/drawable/ic_qr_verify.xml app/src/main/res/layout/widget_google_verify.xml app/src/main/res/xml/google_verify_widget_info.xml app/src/test/java/com/ejemplo/locksuite/mdm/GoogleVerificationPolicyTest.kt app/src/test/java/com/ejemplo/locksuite/util/QrLuminanceDecoderTest.kt
git commit -F "Claude outputs/b92_commit_msg_2026-10-22.txt"
```

Si el archivo del mensaje no está, el texto es este (copialo a un `.txt` y usá `-F`):

```
fix(verificacion): el microvisor de Google (B.92) no podia terminar el QR; SMS validado y escaner QR propio

Auditoria del microvisor de 0.6.64. El confinamiento estaba bien, pero el flujo del QR
no podia terminar nunca:

- La pagina de Google (/devicephoneverification/consent) pide MANDAR un SMS desde el
  celular ("Send SMS") y el microvisor bloqueaba todo sms:. Ahora el pedido se valida
  (un solo destinatario, texto corto, sin convertir '+' en espacio) y solo se acepta
  desde /devicephoneverification; se abre Mensajes con el borrador y el usuario toca
  Enviar. No se manda nada solo ni hace falta SEND_SMS.
- En Android 12+ la camara del sistema manda el link al navegador (suspendido), nunca a
  LockSuite. Escaner propio con CameraX + ZXing (sin servicios de Google ni red), widget
  "Verificar Google" y boton en Ajustes Kosher. Permiso CAMERA con required=false; si el
  administrador deshabilito la camara, el escaner la respeta.

Ademas: onRenderProcessGone (el WebView comparte proceso con la VPN y la Accesibilidad:
sin esto, un renderizador muerto tumbaba todo), subrecursos por raiz de app (el RPC del
desafio v3 daba 403; /checkcookie fuera), onShowFileChooser devuelve true, menu de
seleccion por titulo del sistema (Chromium no usa android.R.id), onNewIntent sin
WebView, UA de Chrome con la version real del WebView, cookies borradas al cerrar,
scroll y foco para equipos chicos y de teclas, y diagnostico sin tokens (logcat
LockSuiteVerif + Layer3Audit).

Pruebas: 33 puras (17 nuevas) con 13 controles negativos; el QR real de la captura del
dueño se lee en 47 ms. Type-check de la parte Android contra android.jar real + stubs
(8/8 controles negativos). Sin Gradle ni equipo real: ver
INSTRUCCIONES_ANTIGRAVITY_2026-10-22_MICROVISOR_QR.md.

Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>
Claude-Session: https://claude.ai/code/session_01VRzG3D1FXzar9MgoY8rohG
```

## 5. Desplegar

```powershell
.\deploy_all.ps1 -VersionName "0.6.65"     # → versionCode 128
```

Antes, `git status` tiene que estar limpio (salvo lo que sea a propósito de otra tarea): el script commitea todo lo que encuentre.

## 6. Pruebas en el celular, en este orden

**0. Regresión (va primero).** Internet y VPN como siempre, el panel ve el equipo en línea y le llegan los comandos, la Accesibilidad activa. Nada de esto se tocó, pero se agregó una dependencia (CameraX) y un permiso.

**1. El escáner abre.** Ajustes Kosher → "Verificación de Google (escanear QR)" → **Escanear código QR**. La primera vez puede aparecer el diálogo de permiso de cámara (si Android no deja que el Device Owner lo conceda solo) — una sola vez. Confirmá:
```powershell
adb shell dumpsys package com.ejemplo.locksuite | findstr "android.permission.CAMERA"
```

**2. Un QR que no es de Google NO abre nada.** Mostrá en la pantalla de la PC un QR de cualquier página (o de YouTube): tiene que salir el aviso naranja "Ese código QR no es de una verificación de Google" y seguir escaneando.

**3. ★ LA PRUEBA QUE IMPORTA: el QR real.** En la PC, en una ventana de incógnito, crear una cuenta de Google o iniciar sesión hasta que aparezca **"Verificá tu información para continuar"** con el QR (Google decide cuándo lo muestra; crear una cuenta nueva suele dispararlo). En el celular: escanear → se abre la página de Google dentro del microvisor → **"Send SMS" / "Enviar SMS"** → se abre Mensajes con número y texto ya puestos → **Enviar** sin cambiar nada → la PC tiene que avanzar sola en uno o dos minutos. Mientras tanto:
```powershell
adb logcat -s LockSuiteVerif
```
Tiene que aparecer `QR aceptado: https://accounts.google.com/devicephoneverification/begin` y `SMS de verificación entregado a Mensajes (N caracteres)`.
- Si en cambio sale **`Pedido de SMS rechazado (formato no válido): sms:+9999…`**, mandá esa línea entera: es la FORMA del pedido de Google (dígitos → 9, letras → x), sin el código. Con eso se ajusta `parseSmsHandoff` en una línea.
- Si sale **`Bloqueado (navegación): https://…`** o **`Subrecurso bloqueado: https://…`** con un host o ruta de Google que la página necesita, mandá la línea: se agrega esa ruta a la lista que corresponda de `GoogleVerificationPolicy` (**nunca** aflojar el filtro entero "para probar").

**4. Que no se pueda salir.** Dentro del microvisor: tocar "Más información", "Ayuda", "Privacidad", "Condiciones" → tiene que salir "🚫 Navegación externa bloqueada" y quedarse donde estaba. Mantener apretado un texto → solo Copiar/Pegar (sin Buscar, Compartir ni Traducir). Mantener apretado un enlace → no abre nada. Atrás desde la primera página → vuelve a la pantalla de inicio del microvisor.

**5. Cámara bloqueada.** Desde el panel, bloquear la cámara de ese equipo → el escáner tiene que decir "La cámara está bloqueada por el administrador…" (y no abrirla). Después volver a habilitarla.

**6. SMS bloqueados** (si ese equipo usa la restricción de SMS de B.33): con los SMS bloqueados, el "Send SMS" tiene que mostrar el aviso de que los SMS están bloqueados.

**7. El widget** (en un equipo con el lanzador común, no el kosher): mantener apretado el inicio → Widgets → LockSuite → "Verificar Google" → tocarlo abre directo el escáner.

**8. (Opcional, Android 12+) Que la cámara del sistema también abra el microvisor.** No es necesario (para eso está el escáner), pero en un equipo puntual:
```powershell
adb shell pm set-app-links-user-selection --user 0 --package com.ejemplo.locksuite true accounts.google.com
adb shell pm get-app-links --user 0 com.ejemplo.locksuite
```
Después, escanear el QR con la cámara del sistema tiene que abrir el microvisor en vez del "app pausada" del navegador.

**9. El panel.** Ficha del celular → Resumen → «🛑 Qué cerró la Capa 3»: líneas `verificacion-google · com.ejemplo.locksuite · microvisor · …` (por ejemplo "SMS de verificación abierto en Mensajes" o "navegación bloqueada: https://support.google.com/…"). **Ninguna puede tener `?` ni tokens.**

**10. (Observar, no forzar) Poca memoria.** Si alguna vez, con el microvisor abierto, aparece "La página de Google se cerró por falta de memoria", confirmar que la llave de la VPN siguió en la barra y que `adb shell pidof com.ejemplo.locksuite` no cambió: es la prueba de que `onRenderProcessGone` salvó el proceso.

## 7. Plan B si Google rechazara el WebView

Si la página de Google, adentro del microvisor, dice algo como "Este navegador o esta app pueden no ser seguros" o "navegador no compatible": no es el filtro, es Google detectando un WebView. El User-Agent ya no lo delata; quedan el encabezado `X-Requested-With` y la marca "Android WebView" de los Client Hints, que solo se sacan con `androidx.webkit`:

1. `implementation("androidx.webkit:webkit:1.12.1")` (o la última estable).
2. En `GoogleVerificationWebView`, después del bloque `settings.apply { … }`:
```kotlin
if (WebViewFeature.isFeatureSupported(WebViewFeature.REQUESTED_WITH_HEADER_ALLOW_LIST)) {
    WebSettingsCompat.setRequestedWithHeaderOriginAllowList(settings, emptySet())
}
```
3. Si con eso no alcanza, `WebSettingsCompat.setUserAgentMetadata(...)` con las marcas "Google Chrome" y "Chromium" y la versión mayor del WebView (`WebViewFeature.USER_AGENT_METADATA`).

**Medir primero** (qué dice la página, qué muestra `logcat`) y recién ahí agregar esto. Y nunca aflojar las listas de `GoogleVerificationPolicy` para "ver si era eso".

## 8. Cosas que NO hay que "simplificar"

1. **El `sms:` no es una navegación** (`isNavigationAllowed` sigue en `false`). Es un pedido aparte, aceptado solo con la página en `/devicephoneverification`, convertido en un `ACTION_SENDTO smsto:` propio con el paquete de Mensajes predeterminado. Del pedido de la página se usan SOLO número y texto (de un `intent:` se descarta paquete, componente y extras).
2. **La decodificación del texto deja el `+` como `+`.** `URLDecoder` lo convierte en espacio y Google no verificaría nada, sin error visible.
3. **El escáner no toca una cámara deshabilitada por el administrador.**
4. **`onRenderProcessGone` devuelve `true`.** El WebView está en el proceso de la VPN y la Accesibilidad.
5. **`describeForLog` nunca anota la query** (ahí viajan `plt`, `TL`, `dsh` y el código del SMS).
6. **No volver a poner `/checkcookie` ni `/ServiceLogin` como subrecursos.** Son redirectores, y `shouldInterceptRequest` no ve las redirecciones.
7. **No confiar en la Capa 2 para el microvisor**: LockSuite se excluye de su propia VPN, y la Capa 3 ignora el propio paquete. Los filtros del WebView son la única barrera.

## 9. Aparte, no mezclar con este commit: B.93

`python tools/gen_policies_js.py --check` falla desde el 19/10 (commit `914c822`): `admin-backend/public/policies.js` todavía lista `nokiaKeypadMode` en `handledSeparately`. Regenerarlo (`python tools/gen_policies_js.py`), mirar el diff, abrir la ficha de un celular y confirmar que el modo Nokia se sigue prendiendo y apagando, y commitearlo en un commit propio antes del despliegue.
