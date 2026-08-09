# LockSuite / KosherLock MDM — Contexto para retomar en una IA nueva

Este archivo es un traspaso de contexto. Lo escribió Claude tras una sesión larga trabajando directo sobre tu proyecto en `C:\Users\israe\OneDrive\Documentos\Lock Suite segunda version`. Adjuntalo al arrancar la conversación nueva para que esa sesión no parta de cero.

**Importante antes que nada:** el proyecto tiene documentación propia ya escrita, que sigue siendo la fuente de verdad para "qué hace cada función" — este archivo no la duplica, la indexa y le agrega lo que NINGÚN otro archivo tiene todavía: qué pasó en esta sesión puntual. Los tres documentos de referencia del proyecto son:

- `README.md` — instalación, arquitectura general, cómo compilar y aprovisionar.
- `walkthrough.md` — historial de cambios y correcciones versión por versión (hasta v0.4.9.3).
- `DOCUMENTACION_FUNCIONES.md` — manual técnico completo. **Ojo: está incompleto.** Termina en "*Parte 1 de 3 — Secciones 1 a 4 completadas*"; el índice promete 15 secciones pero solo están escritas las primeras 4 (visión general, arquitectura de software, restricciones DPM, bloqueos de hardware). Si una IA nueva necesita el detalle de la Sección 5 en adelante (VPN, control de apps, FRP, presets, panel web, OTA, troubleshooting), tiene que leer el código fuente directamente, no asumir que está documentado.
- `AUDITORIA_0.5.0.md` — auditoría general v0.5.0, más corta.
- `Informe_Correcciones_LineaPorLinea_2026-07-26.docx/.txt` e `Informe_Segunda_Revision_Profunda_2026-07-27.docx/.txt` — dos informes de auditoría/corrección extensos, generados por otra sesión de IA el 26 y 27 de julio. Tienen el detalle línea por línea de qué se corrigió y qué quedó pendiente (ver sección "Pendientes conocidos" más abajo).
- `android_updates/CHANGELOG.md` — changelog de un parche puntual (whitelist de apps con Lock Task Mode, panel remoto, PIN por dispositivo).

## 1. Qué es la app

**LockSuite**, también llamado **KosherLock MDM**, es un sistema de administración de dispositivos móviles (MDM) para Android pensado para restringir celulares de forma que el usuario final no pueda desinstalarlo, revocarle permisos, ni evadirlo con un formateo o modo seguro. Está pensado para administradores comunitarios (mashguijim, rabinos, coordinadores institucionales) que distribuyen celulares "kosher" con restricciones de contenido definitivas, aunque también sirve como MDM corporativo genérico.

Se instala como **Device Owner** de Android (el nivel de privilegio más alto que existe para una app), vía:
```
adb shell dpm set-device-owner com.ejemplo.locksuite/com.ejemplo.locksuite.receiver.DeviceAdminReceiver
```
(debe ejecutarse con el celular sin ninguna cuenta de Google configurada todavía).

### Las tres capas de protección

```
CAPA 1 — SISTEMA OPERATIVO (DevicePolicyManager / PolicyManager.kt)
  Restricciones nativas de Android: factory reset, ADB, instalación de apps,
  Bluetooth, Wi-Fi, cámara, capturas de pantalla, volumen, etc.

CAPA 2 — RED (KosherVpnService.kt)
  VPN 100% local que intercepta consultas DNS (puerto 53) y bloquea dominios,
  anuncios, GIFs, apps específicas, ofertas de Mercado Pago, etc.

CAPA 3 — VISUAL (LockSuiteAccessibilityService.kt)
  Servicio de Accesibilidad que monitorea la pantalla en tiempo real y rebota
  (GLOBAL_ACTION_BACK) si el usuario entra a una sección prohibida (ej.
  "Ofertas" de Mercado Pago, "Estados"/"Canales" de WhatsApp).
```

Las tres capas son independientes entre sí — si una falla o se apaga, las otras dos siguen funcionando solas.

Panel de administración remota: web (Firebase Hosting) + Cloud Functions, comunicándose con el celular por FCM (push) en ambas direcciones. Proyecto Firebase: `looksuite-41866`.

## 2. Dónde está todo en tu PC

Raíz del proyecto: `C:\Users\israe\OneDrive\Documentos\Lock Suite segunda version`

| Carpeta/archivo | Qué es |
|---|---|
| `app/` | Proyecto Android real (el que compila). Todo el código fuente vive bajo `app/src/main/java/com/ejemplo/locksuite/`. |
| `admin-backend/` | Panel web + Cloud Functions de Firebase. `public/` es el HTML/CSS/JS del panel, `functions/index.js` son las Cloud Functions. |
| `capa3_opcion_a/`, `capa3_opcion_b/` | **Prototipos huérfanos, no se compilan.** Dos intentos previos de la Capa 2/3 de red que quedaron sueltos en la raíz en vez de descartarse (confirmado en el informe del 27/07). `capa3_opcion_a` es una versión mucho más chica y vieja de `KosherVpnService.kt` (5,2 KB vs. ~20-24 KB la real). `capa3_opcion_b` es un enfoque totalmente distinto basado en la app de terceros NetGuard. Ninguno de los dos tiene efecto en la app instalada — no confundirlos con una tercera implementación real. |
| `_to_delete/` | Convención del proyecto para archivos que se van a borrar: en vez de eliminarlos directo, se mueven acá (algunas herramientas de IA no pueden borrar archivos en este entorno). Está en uso activo — revisar qué hay antes de asumir que se puede vaciar sin mirar. |
| `android_updates/` | Contiene una copia parcial/vieja del proyecto (`app/src/main/...`) más un `CHANGELOG.md` de un parche puntual. No es el código en producción. |
| `.git/` | Repo git. Ver sección "Estado del repo" — hay mucho trabajo sin commitear. |
| `local.properties` | Configuración de firma (keystore) — no compartir, tiene contraseñas. |
| `release-apk/`, `build/`, `.gradle/`, `.kotlin/` | Artefactos de build, no tocar a mano. |

### Archivos clave del código Android (todos bajo `app/src/main/java/com/ejemplo/locksuite/`)

| Archivo | Responsabilidad |
|---|---|
| `mdm/PolicyManager.kt` | **El motor central.** Singleton que encapsula todas las llamadas a `DevicePolicyManager`/`UserManager`. Cerca de 50 KB — es el archivo más grande e importante del proyecto. Acá vive `setVpnConfigBlocked()`, `reapplyAllRestrictions()`, FRP, presets HMAC, bloqueo de internet por proxy, etc. |
| `mdm/AppController.kt` | Suspender/ocultar/desinstalar apps, inventario de apps instaladas. |
| `mdm/WebViewBlockManager.kt` / `mdm/WebViewPolicy.kt` | Qué apps tienen el WebView bloqueado y qué dominios les están permitidos (whitelist estricta para Waze/DiDi vía `CORE_DOMAINS`, whitelist dinámica genérica para el resto). |
| `mdm/ImageBlockManager.kt` | Filtrado visual de imágenes (silueta / AI gate), hasta 2 capas combinables. |
| `dns/DomainRuleManager.kt` | Reglas DNS personalizadas (BLOCK/ALLOW) cargadas en un Trie. |
| `service/KosherVpnService.kt` | **Capa 2.** Extiende `VpnService`. Es un **túnel dividido (split-tunnel) que SOLO captura DNS** — nunca agrega ruta `0.0.0.0/0`, solo rutas /32 al DNS virtual (`10.0.0.1`/`fd00::1`) y a un puñado de resolutores públicos conocidos (8.8.8.8, 1.1.1.1, 9.9.9.9, etc., para que no se evada apuntando DNS ahí directo). Todo lo que no sea UDP/puerto 53 se descarta en `runFilterLoop()` — **no hay reenvío de tráfico general, a propósito** (evita el costo de batería/CPU de un túnel completo). Ver sección 4 para la historia importante de HOY sobre este archivo. |
| `util/NetworkForwarder.kt`, `util/DnsPacketParser.kt`, `util/IpPacketParser.kt` | Helpers de bajo nivel del parseo/reenvío de paquetes IP/UDP/DNS que usa KosherVpnService. |
| `util/AdBlocker.kt` | Blacklist de anuncios, búsqueda O(1) por HashSet. |
| `service/WatchdogForegroundService.kt` | Servicio foreground que sondea cada **20 segundos** (a propósito, se subió de 3s a 20s en v0.4.9.1 para ahorrar batería — no bajar este intervalo sin volver a leer ese historial): re-impone DNS privado desactivado, llama a `BootReceiver.ensureVpnRunning()`, chequea si Accesibilidad sigue activa, sincroniza estado a Firebase. También agenda `WatchdogWorker` como respaldo. |
| `worker/WatchdogWorker.kt` | Job de WorkManager cada **15 minutos**, sobrevive aunque el proceso entero de la app haya muerto (WorkManager lo maneja el sistema operativo). Reaplica restricciones, relanza el Watchdog foreground si hace falta, asegura que la VPN esté corriendo. |
| `receiver/BootReceiver.kt` | Al bootear: reaplica restricciones, arranca el Watchdog, arranca la VPN si corresponde. Acá viven las funciones compartidas `shouldVpnBeRunning()` (evalúa todas las políticas que requieren VPN activa) y `ensureVpnRunning()` (la arranca si debería estar corriendo y no lo está). |
| `service/LockSuiteAccessibilityService.kt` | **Capa 3.** 863 líneas. Detección de secciones prohibidas por texto/IDs de vista, incluye palabras clave en ídish. |
| `service/LockSuiteFirebaseService.kt` | Recibe comandos remotos por FCM (BLOCK_VPN, BLOCK_BLUETOOTH, UPDATE_ALLOWLIST, etc.), despacha a PolicyManager/AppController/KosherVpnService, responde ACK. **Se modificó bastante en esta sesión por otra herramienta de IA** (ver sección 5) — vale la pena releerlo en la sesión nueva. |
| `util/FirebaseDeviceSync.kt` | Sincroniza el estado del dispositivo al panel web (Firebase Auth anónima). |
| `security/PinManager.kt`, `security/SessionManager.kt` | PIN de admin (comparación en tiempo constante, sin PIN maestro hardcodeado desde hace unas versiones), sesión activa. |
| `util/SelfUpdater.kt`, `util/ApkInstaller.kt` | Auto-actualización OTA silenciosa vía `PackageInstaller` (Device Owner), consulta `version.json` en GitHub Raw / Firebase Hosting como fallback. |
| `ui/dashboard/DashboardActivity.kt` | Panel de administración local en el celular (Compose). |
| `ui/auth/LoginActivity.kt`, `ui/auth/SetupPinActivity.kt` | Login y configuración inicial de PIN. **`LoginActivity.kt` también se modificó bastante en esta sesión** (ver `_to_delete/` — hay una copia vieja movida ahí). |
| `AndroidManifest.xml` | Declaraciones de servicios/receivers. `KosherVpnService` está declarado con `foregroundServiceType="specialUse"` y permiso `BIND_VPN_SERVICE`. |

Paquete base: `com.ejemplo.locksuite`. `minSdk = 24`, `targetSdk = 34`, `compileSdk = 36` (`app/build.gradle.kts`).

## 3. Cómo compilar / desplegar

- Android Studio + JDK 17. `google-services.json` va en `app/`. Keystore configurado en `local.properties`.
- `./gradlew assembleRelease` → APK en `app/build/outputs/apk/release/`.
- Panel web: `cd admin-backend && firebase deploy --only hosting,database,functions` (requiere plan Blaze de Firebase, costo esperado ~$0/mes a volumen comunitario).
- **Este entorno de IA en la nube no tiene Android SDK/Gradle ni acceso a Firebase** — ninguna sesión de IA que trabaje sobre este proyecto vía el puente al dispositivo puede compilar ni desplegar por sí misma. Todo cambio de código hay que compilarlo y probarlo vos en un equipo real antes de confiar en él. Esto ya se documentó así en los informes de auditoría previos, no es una limitación nueva.

## 4. LO IMPORTANTE DE HOY — la historia del VPN lockdown (no está escrita en ningún otro lado)

Esto es lo que más vale la pena que la sesión nueva entienda, porque si no lo sabe puede repetir un error ya confirmado.

**Motivo original de esta sesión:** contaste que en un celular Qin, activar los bloqueos por VPN cortó el internet completamente. Se confirmó que era un bug histórico ya documentado en `walkthrough.md` v0.4.3 ("Reparación de la VPN y Redirección DNS"): en ese momento `lockdownEnabled` estaba en `true`, Android cortaba todo el tráfico cuando la VPN no estaba activa, y se arregló poniéndolo en `false`. Confirmado también contra el código real: `PolicyManager.setVpnConfigBlocked()` llama a `dpm.setAlwaysOnVpnPackage(admin, pkg, false)`.

**Pediste explícitamente lo contrario:** que se reactive `lockdown=true` (para que si la VPN cae, no funcione nada — preferís fallo cerrado a fallo abierto), pero que se hiciera lo posible para que la VPN "nunca caiga".

**Lo que se construyó para eso (hoy):**
- `PolicyManager.setVpnConfigBlocked()`: se cambió a `lockdown=true`, con la variante de `setAlwaysOnVpnPackage` de Android 10+ que acepta una lista blanca de apps exentas del lockdown, incluyendo ahí a LockSuite mismo (para que conserve acceso a Firebase durante una caída).
- `KosherVpnService.kt`: se agregó `onRevoke()` (reintenta reestablecer el túnel al instante si Android revoca el permiso de VPN) y un monitor de cambios de red (`registerDefaultNetworkCallback` + `restartVpn()` con debounce de 3s) que reestablece el túnel proactivamente ante un handoff Wi-Fi↔datos móviles — esto cubre un modo de falla que el Watchdog existente no detecta (el túnel queda "vivo" pero deja de enrutar tráfico sin lanzar ninguna excepción).
- `PolicyManager.reapplyAllRestrictions()`: se agregó reafirmación de la designación Always-on VPN (antes solo reaplicaba la restricción genérica `DISALLOW_CONFIG_VPN`, nunca volvía a llamar a `setAlwaysOnVpnPackage` en sí).

**Se rompió todo el internet general de inmediato** (no solo durante caídas). Root cause confirmado línea por línea contra el código real: `KosherVpnService` es un túnel dividido que **solo** agrega rutas para DNS (ver la tabla de arriba) y descarta cualquier paquete que no sea UDP/puerto 53. Con `lockdown=true`, Android exige que **todo** el tráfico de las apps salga por la interfaz de la VPN — pero esta VPN no sabe qué hacer con tráfico que no sea DNS (navegación, WhatsApp, imágenes), así que ese tráfico general queda sin destino posible. Esto pasa **todo el tiempo mientras lockdown esté activo**, con la VPN sana y funcionando perfectamente — no es un problema que dependa de que la VPN se caiga.

**Aclaración honesta para la sesión nueva:** este error fue mío (de la sesión de hoy) — me enfoqué en el escenario "la VPN se cae y lockdown corta todo" (que sí es real) y no re-verifiqué desde cero si lockdown rompía algo incluso con la VPN sana. Convendría que la sesión nueva no vuelva a proponer `lockdown=true` sin releer el comentario que se dejó en el código (ver abajo) primero.

**Cómo quedó ahora (revertido y limpio):**
- `lockdown=false` de nuevo en `PolicyManager.setVpnConfigBlocked()` — de hecho, otra herramienta/IA con la que también estabas trabajando en paralelo ya lo había revertido cuando fui a arreglarlo yo; solo tuve que limpiar el comentario/estructura que había quedado inconsistente. El comentario actual en el código deja documentada toda esta historia (por qué está en `false`, qué se probó, qué haría falta para activar `true` de forma segura) — **la sesión nueva debería leer ese comentario en el código antes de tocar esta función**.
- Se mantienen `onRevoke()` y el monitor de cambios de red en `KosherVpnService.kt` — siguen siendo una mejora real e independiente de lockdown (la VPN de solo-DNS se recupera más rápido de handoffs de red y revocaciones).
- Se mantiene la reafirmación de Always-on en `reapplyAllRestrictions()`.

**Cadena de autoreparación actual, de más rápida a más lenta:**
1. `onRevoke()` / monitor de red — instantáneo, ante sus disparadores específicos.
2. `WatchdogForegroundService` — sondea cada 20s.
3. `WatchdogWorker` (WorkManager) — cada 15 min, sobrevive a que muera el proceso entero.
4. `START_STICKY` — Android intenta recrear el servicio solo si lo mata.

**Decisión pendiente, sin resolver todavía — esto es lo próximo a retomar:** para que "si cae, no funcione nada" sea cierto de verdad (no solo para DNS sino para todo), haría falta reescribir `KosherVpnService` como un túnel completo que reenvíe todo el tráfico TCP/UDP, no solo DNS — cambio de arquitectura grande, con costo real de batería/CPU, ya marcado como fuera de alcance en el informe de auditoría (§3.1). Se conversaron tres caminos sin decidir ninguno todavía:
- **Dejarlo como está** (recomendado): `lockdown=false`, túnel de solo-DNS, con la autoreparación de arriba acotando la ventana de exposición a segundos en la mayoría de los casos.
- **Túnel completo**: proyecto grande, no trivial, más batería/CPU, más superficie de bugs nuevos para probar en dispositivo real.
- **Camino intermedio propuesto (sin construir todavía):** un "lockdown casero" — que el Watchdog detecte "la VPN debería estar arriba y no lo está" y aplique automáticamente, como medida temporal, el mismo truco de proxy global a un puerto muerto que ya usa `PolicyManager.setInternetBlocked()` (`dpm.setRecommendedGlobalProxy` a `127.0.0.1:9999`), hasta que la VPN vuelva. Reutiliza código ya existente en vez de escribir un relay nuevo, pero es un proxy HTTP a nivel de sistema, no una regla de firewall real — no hay certeza de que cubra el 100% de los tipos de tráfico sin probarlo en un equipo real.
- También se sugirió agregar telemetría real de tiempo de VPN caída (vía `FirebaseDeviceSync`) para tener datos en vez de estimaciones — esto ya lo pedía el informe de auditoría del 27/07 y sigue sin existir.

**Dos bugs de Qin distintos, no confundir:** el de arriba (VPN lockdown) es uno. El otro, documentado en `walkthrough.md` v0.4.9.3, es un Qin F21 Pro específico con `INSTALL_FAILED_NO_MATCHING_ABIS` por ser de 32 bits, arreglado agregando `armeabi-v7a` a los `abiFilters` de `app/build.gradle.kts`. Son problemas separados en el mismo modelo de celular.

## 5. Particularidades del entorno para la próxima sesión de IA

- El proyecto vive en tu PC, no en la nube — se accede vía el puente `mcp__remote-devices__*` (device_list_dir, device_stage_files, device_bash, device_commit_files).
- **`device_stage_files` falla con error HTTP 400 para archivos bajo `app/src/main/java/...` específicamente** (probablemente un problema de sincronización/placeholder de OneDrive en esa subcarpeta puntual). Los archivos en la raíz del proyecto o en `app/src/main/` (sin bajar a `java/`) sí se pueden stagear normalmente. Para leer o escribir los archivos de código Kotlin reales, lo que sí funciona de forma confiable es `device_bash` con `cat`/`grep`/`nl` para leer, y un script de Python vía heredoc (`python3 - <<'PYEOF' ... PYEOF`) para escribir, haciendo un reemplazo de texto exacto con verificación de que hay **exactamente una coincidencia** antes de escribir nada (para no arriesgarse a corromper el archivo por un match ambiguo). Cuidado con los saltos de línea: conviene detectar si el archivo usa CRLF o LF antes de comparar strings.
- Los cambios hechos vía `device_bash` quedan **directo en el disco del usuario**, no hace falta ningún paso de "commit" al puente — a diferencia del flujo normal de stage→editar→`device_commit_files`.
- La "workspace" local (la VM Linux que corre `device_bash`) a veces contesta "Workspace still starting" si estuvo un rato inactiva — hay que esperar y reintentar (a veces tarda bastante más de los "10-30 segundos" que dice el mensaje, se vio tardar más de 3 minutos en esta sesión). No conviene spammear reintentos sin esperar.
- **Muy importante: hay más de un agente/herramienta de IA trabajando sobre este mismo proyecto en paralelo** (vos mismo lo mencionaste, y se confirmó en código: algo revirtió `lockdown=true` a `false` mientras yo trabajaba). Cualquier sesión nueva debería releer el contenido actual de un archivo justo antes de editarlo, no confiar en una copia vieja leída minutos antes — y no asumir que el estado descripto en este documento sigue siendo exacto línea por línea a esta altura.

## 6. Estado del repo (git)

Hay bastante trabajo sin commitear — de la pasada de auditoría del 26-27/07 y de esta sesión. `_to_delete/` está en uso activo para archivos reemplazados. No se hizo ningún commit desde ninguna sesión de IA (ni esta ni, aparentemente, la anterior) — todo son cambios pendientes en el working tree. **La sesión nueva debería correr `git status` y `git diff --stat` como primer paso**, en vez de confiar en una lista específica de archivos modificados acá, porque cambia mientras varias herramientas trabajan en paralelo.

## 7. Pendientes conocidos (según el informe de auditoría del 27/07 — no verificados de nuevo por mí salvo lo de VPN de la sección 4)

- El filtro DNS no cubre DoH/DoT ni QUIC ni conexiones directas por IP — mismo tema de fondo que el túnel dividido de la sección 4 (§3.1).
- Reglas de Firebase Realtime Database permiten escritura a cualquier usuario autenticado, incluida sesión anónima — pendiente migrar a identidades de dispositivo con privilegios mínimos (§3.3).
- La clave HMAC de los presets (`.locksuite`) es un secreto compartido fijo en el código, no una firma asimétrica — mitigado en la práctica porque solo se llega ahí con PIN local o comando FCM ya firmado, pero sigue siendo una debilidad de fondo (§2.4/§3.4).
- `SelfUpdater.kt` instala el APK de autoactualización/Tienda sin verificar un checksum esperado — la autoactualización tiene algo de protección gratis por la firma de Android, la Tienda administrada no (§3.5).
- La Capa 3 (Accesibilidad) se puede apagar desde Ajustes del sistema — limitación de la plataforma, no hay `DISALLOW_*` para impedirlo puntualmente; el Watchdog reacciona pero no puede cerrar la ventana entre que se apaga y se detecta (§3.6).
- Cosas menores sin corregir aún: `MainScreenTest.kt`/`MainScreenViewModelTest.kt` son plantilla de Android Studio sin relación con el código real (recomendado borrarlos), plugin `kotlin.serialization` sin uso aparente en `app/build.gradle.kts`, declaraciones sueltas en `device_admin_policies.xml`, y las carpetas huérfanas `capa3_opcion_a/`/`capa3_opcion_b/` (ver sección 2) que convendría archivar en `_to_delete/` o dejar claramente marcadas como no-código-real.

## 8. Próximo paso sugerido al arrancar la sesión nueva

Retomar la decisión pendiente de la sección 4 (dejar el túnel de solo-DNS como está, ir por un túnel completo, o probar el "lockdown casero" con el proxy global) — esa fue la última pregunta abierta de esta conversación, todavía sin resolver.
