# LockSuite Kosher — Changelog de esta actualización

## Resumen del cambio de enfoque

Se sacó todo lo que servía para **ocultar** la app (modo Stealth local y
remoto, código de marcador `1234`). Se mantuvo y se completó todo lo que
sirve para **restringir con consentimiento** (Device Owner, PIN de admin,
persistencia tras reinicio, bloqueo de funciones). Y se agregó lo que
realmente pedías — una lista blanca de apps permitidas, implementada con
la API de Android pensada para eso (Lock Task Mode), no a mano
ocultando/suspendiendo apps una por una.

"Sin posibilidad de evasión" ahora se logra con Lock Task Mode + Device
Owner (no se puede salir a nada fuera de la lista permitida), no
escondiendo la app de quien usa el teléfono.

## Archivos NUEVOS

| Archivo | Qué hace |
|---|---|
| `util/Constants.kt` | Faltaba en el bundle original. Ya no incluye `MASTER_PIN_HASH/SALT`. |
| `util/PrefsHelper.kt` | Faltaba en el bundle original. Implementa `EncryptedSharedPreferences` con Android Keystore de verdad. |
| `ui/onboarding/ConsentActivity.kt` | Pantalla de consentimiento, obligatoria una sola vez antes de configurar el PIN. |
| `ui/kiosk/KioskLauncherActivity.kt` | La lista blanca real: reemplaza el Inicio del dispositivo, muestra solo las apps permitidas, usa `startLockTask()`. |
| `security/SessionManager.kt` | Recreado con nombres de método limpios para que el resto de archivos calcen (ver nota de compatibilidad abajo). |
| `ui/auth/SetupPinActivity.kt` | Recreado para asegurar compatibilidad con el `PinManager` nuevo. |

## Archivos MODIFICADOS

| Archivo | Cambios |
|---|---|
| `security/PinManager.kt` | Se eliminó el "PIN Maestro" hardcodeado (`Constants.MASTER_PIN_HASH/SALT` — credencial fija en el APK, extraíble descompilando). Ahora hay un único PIN por instalación. Se agregó comparación en tiempo constante. |
| `receiver/SecretCodeReceiver.kt` | Se sacó el código `1234` (ya no hace falta: el ícono nunca se oculta). Queda `9999` como recuperación de emergencia. |
| `service/LockSuiteFirebaseService.kt` | Se eliminaron los comandos remotos `ENABLE_STEALTH`/`DISABLE_STEALTH`. Se agregó `UPDATE_ALLOWLIST` para empujar la lista de apps permitidas remotamente. |
| `mdm/PolicyManager.kt` | Se agregaron `setAllowedPackages`, `getAllowedPackages`, `isKioskModeEnabled`, `registerKioskLauncher` (Lock Task API). Se mantienen todas las restricciones que ya tenías (Wi-Fi, Bluetooth, VPN, tethering, factory reset, ADB, cámara, captura de pantalla). |
| `receiver/BootReceiver.kt` | Corrige un bug real: `Constants.KIOSK_MODE_KEY` se leía pero **nunca se escribía** en ningún lado del código original, así que el bloque de "relanzar modo kiosco tras reinicio" nunca se ejecutaba. Ahora queda conectado de punta a punta. |
| `ui/emergency/EmergencyActivity.kt` | Usa `PinManager.verifyPin()` en vez del PIN maestro hardcodeado. También limpia el modo kiosco al purgar. |
| `ui/auth/LoginActivity.kt` | Enruta a `ConsentActivity` en el primer uso. Ya no bloquea el botón atrás de forma absoluta (ahora es solo la puerta al panel admin, no la pantalla de uso diario). Se sacó código muerto (`handler`/`updateRunnable` sin usar). El polling de lockout pasó de `Handler`/`Runnable` manual a una corrutina (`LaunchedEffect` + `delay`), que Compose cancela sola. |
| `mdm/AppController.kt` | `getUserApps()` ya no puede crashear la pestaña completa si una app se desinstala a mitad de la iteración (antes no capturaba `NameNotFoundException`). |
| `ui/dashboard/DashboardActivity.kt` | Se eliminó el switch de "Modo Stealth". Se agregó gestión de lista blanca en la pestaña Aplicaciones. Se corrigió `PolicySwitchRow` (tenía estado duplicado con un `remember` local + `refreshKey` externo que podía desincronizarse). Se movió `getUserApps()` a `Dispatchers.IO` (antes corría en el hilo principal y podía trabar la UI). |
| `AndroidManifest.xml` | Se sacó el `1234` del `SecretCodeReceiver`. Se agregaron `ConsentActivity` y `KioskLauncherActivity` (categoría `HOME`). El `LauncherAlias` se simplificó: como nunca más se deshabilita, `LoginActivity` es directamente el ícono del launcher (ya no hace falta el alias intermedio que existía solo para poder ocultarlo). |

## Archivos SIN CAMBIOS (dejalos como estaban en tu proyecto)

`LockSuiteApplication.kt`, `receiver/DeviceAdminReceiver.kt`,
`service/WatchdogForegroundService.kt`, `worker/WatchdogWorker.kt`,
`res/xml/device_admin_policies.xml`, `res/values/themes.xml`,
`res/values/strings.xml`. Ninguno necesitaba cambios funcionales para
este parche.

## Pendientes antes de compilar / desplegar

1. **Dependencia nueva**: agregá a `build.gradle` (módulo `app`):
   `implementation("androidx.security:security-crypto:1.1.0-alpha06")`
   (o la versión estable vigente).
2. **`WatchdogForegroundService`**: en el manifest le puse
   `foregroundServiceType="specialUse"` como valor razonable por
   defecto, pero no tengo el código de ese archivo a mano en esta
   sesión para confirmar que sea el tipo correcto — revisalo contra lo
   que el servicio hace realmente, especialmente si tu `targetSdk` es
   34+.
3. **Lock Task Mode**: probalo en un dispositivo/emulador real. El
   comportamiento de `setLockTaskFeatures` varía un poco entre
   versiones de Android y fabricantes (algunos OEMs personalizan el
   comportamiento del botón Recientes/Overview en modo kiosco).
4. **App de teléfono en la lista permitida**: si el celular kosher
   necesita poder hacer/recibir llamadas, agregá el paquete del
   marcador del fabricante (varía por dispositivo) a la lista blanca
   desde la pestaña Aplicaciones.
5. **No compilé este proyecto**: este entorno no tiene Android
   SDK/Gradle disponible, así que esto no pasó por un build real.
   Repasé cada archivo con cuidado y verifiqué que los nombres de
   clases/métodos calcen entre sí, pero conviene compilarlo y probarlo
   antes de desplegarlo en dispositivos reales.

---

## Actualización: panel de administración remota

Ver `admin-backend/README.md` para el detalle completo y los pasos de
despliegue. Resumen:

- **`util/FirebaseDeviceSync.kt` (NUEVO)**: centraliza toda escritura hacia
  Firebase Realtime Database, autenticada con Firebase Anonymous Auth. Antes
  `onNewToken` escribía sin autenticar, lo que hubiera obligado a reglas de
  base de datos abiertas (cualquiera podría pisar el token FCM de cualquier
  dispositivo). Ahora las reglas exigen `auth != null`.
- **`service/LockSuiteFirebaseService.kt`**: se agregaron los comandos
  remotos `BLOCK_BLUETOOTH`/`UNBLOCK_BLUETOOTH` y `BLOCK_VPN`/`UNBLOCK_VPN`
  (simétricos a los toggles que ya existían localmente). Cada comando
  ejecutado reporta el estado resultante al panel.
- **`receiver/BootReceiver.kt`** y **`ui/dashboard/DashboardActivity.kt`**:
  ahora también reportan estado al panel (tras reiniciar, y al abrir el
  Dashboard) para que la información no dependa únicamente de que cambie el
  token FCM.
- **`admin-backend/` (NUEVO, carpeta separada del proyecto Android)**:
  - `functions/index.js`: dos Cloud Functions — `sendCommand` (autenticada,
    valida el comando contra una lista blanca, nunca expone el token FCM al
    cliente) y `listDevices` (metadata de dispositivos, sin tokens).
  - `public/`: panel web (HTML/CSS/JS plano, sin build step) con login,
    tarjeta por dispositivo, toggles de estado real, y campo para actualizar
    la lista de apps permitidas.
  - `database.rules.json`: bloquea lectura de clientes por completo, exige
    autenticación para que un dispositivo escriba su propio nodo.
  - Requiere plan Blaze de Firebase (costo esperado $0/mes al volumen de un
    uso familiar/comunitario) y agregar `firebase-auth-ktx` al build.gradle
    de Android.

**Importante sobre alcance**: el panel solo agrega comandos de
restricción/gestión (bloquear, listas de apps, wifi/bluetooth/vpn/instalar).
No agregué ni voy a agregar por mi cuenta capacidades de vigilancia
encubierta (ubicación, mensajes, cámara/micrófono remotos, capturas de
pantalla) — no es lo que pediste y se saldría del caso de uso de celular
kosher consentido del que veníamos hablando.

---

## Actualización: PIN por dispositivo + credenciales de Firebase configuradas

- **`public/firebase-config.js`** ya tiene los datos reales del proyecto
  `looksuite-41866` cargados (el `apiKey` de configuración web no es un
  secreto — Firebase lo documenta así, está pensado para vivir en código de
  cliente).
- **`admin-backend/.firebaserc`** apunta al proyecto para que el despliegue
  no necesite el paso interactivo de elegirlo.
- **`admin-backend/DEPLOY_GUIDE_FOR_AI_AGENT.md` (NUEVO)**: guía de
  despliegue paso a paso pensada para dársela directamente a un agente de
  IA con terminal (Claude Code u otro) — este chat no tiene salida de red
  hacia los servidores de Firebase/Google Cloud (solo hacia registros de
  paquetes), así que no pude correr `firebase deploy` yo mismo desde acá.
- **Nueva función de seguridad — PIN por dispositivo**: administrar un
  celular puntual desde el panel ahora exige el PIN de administrador
  configurado en ESE celular (`security/PinManager.kt` sincroniza su hash a
  Firebase vía `FirebaseDeviceSync.syncPinCredentials`), con la opción
  "No volver a pedirlo en este dispositivo" que lo recuerda de forma
  persistente para esa cuenta de administrador (`functions/index.js`:
  `trustedAdmins/{adminUid}` por dispositivo, con `forgetDeviceTrust` para
  revertirlo). Esto cierra la brecha que había quedado anotada en la entrega
  anterior: antes, cualquier admin autenticado podía controlar cualquier
  dispositivo sin ninguna verificación adicional.


