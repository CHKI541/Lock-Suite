# LockSuite / KosherLock MDM — Contexto para IA (archivo único)

Leé este archivo primero, completo, antes de tocar código. Es el único documento pensado para que una sesión de IA nueva (Claude, Antigravity, o cualquier otra) entienda el proyecto sin releer todo el código fuente ni los informes sueltos que se fueron acumulando.

Este archivo reemplaza y consolida lo vigente de `DOCUMENTO_TECNICO_PARA_CLAUDE.md`, `AUDITORIA_0.5.0.md`, `INSTRUCCIONES_ANTIGRAVITY_v0.6.0.md` y `CAMBIOS_CLAUDE_UI_DNS_para_Antigravity.md`. Esos cuatro quedan obsoletos a partir de acá (propuesta de archivado al final de este mensaje, en el chat — no en este archivo).

Tiene tres partes que se tratan distinto:

- **A. Estable** — cambia poco. Se corrige si algo deja de ser cierto, no se reescribe entera.
- **B. Pendientes** — lista viva de deuda técnica y decisiones abiertas. Se tacha lo resuelto (con fecha y confirmación real en equipo, no solo "compila"), se agrega lo nuevo.
- **C. Bitácora** — qué pasó en la última sesión. Se REEMPLAZA en cada cierre de sesión, no se acumula. Para historial versión por versión, está `walkthrough.md`.

**Regla para toda sesión nueva:** puede haber más de un agente de IA trabajando sobre este mismo repo en paralelo — Claude vía el puente al dispositivo (sin compilar), Antigravity con terminal real en la PC (sí compila/despliega/commitea) — sin commits de por medio la mayor parte del tiempo. Releé el archivo actual antes de editarlo. No confíes en una copia leída minutos antes, ni en que este documento siga exacto línea por línea.

---

## A. ESTABLE

### Qué es la app

**LockSuite**, también **KosherLock MDM**: administración de dispositivos móviles (MDM) para Android, pensada para restringir celulares de forma que el usuario final no pueda desinstalarla, revocarle permisos ni evadirla con un formateo o modo seguro. Pensada originalmente para comunidades que distribuyen celulares "kosher", pero funciona como MDM corporativo genérico. Soporta Android 7.0 (API 24) a Android 14+ (API 34).

Se instala como **Device Owner** (el privilegio más alto posible para una app en Android), con el celular sin ninguna cuenta de Google configurada todavía:

```
adb shell dpm set-device-owner com.ejemplo.locksuite/com.ejemplo.locksuite.receiver.DeviceAdminReceiver
```

Paquete base: `com.ejemplo.locksuite`. `minSdk = 24`, `targetSdk = 34`, `compileSdk = 36`.

### Las tres capas de protección (independientes entre sí)

```
CAPA 1 — SISTEMA OPERATIVO (mdm/PolicyManager.kt)
  DevicePolicyManager/UserManager: factory reset, ADB, instalación de apps,
  Bluetooth, Wi-Fi, cámara, capturas de pantalla, volumen, FRP, etc.

CAPA 2 — RED (service/KosherVpnService.kt)
  VPN local, túnel dividido (split-tunnel): SOLO captura DNS (puerto 53).
  Nunca agrega ruta 0.0.0.0/0. Todo lo que no sea UDP/53 se descarta a
  propósito (evita el costo de batería/CPU de un túnel completo). Reglas
  resueltas en un Trie (DomainRuleManager/DomainRuleTrie) con 4 tipos:
  BLOCK/ALLOW (normales, no pisan otras políticas) y FORCE_BLOCK/FORCE_ALLOW
  (le ganan a cualquier otra configuración).

CAPA 3 — VISUAL (service/LockSuiteAccessibilityService.kt)
  Servicio de Accesibilidad: monitorea la pantalla y rebota
  (GLOBAL_ACTION_BACK / GLOBAL_ACTION_HOME) si el usuario entra a una
  sección prohibida (Estados/Canales de WhatsApp, ofertas de Mercado Pago),
  y automatiza el flujo de actualización silenciosa de apps vía Play Store.
```

### Mapa de archivos clave (`app/src/main/java/com/ejemplo/locksuite/`)

| Archivo | Responsabilidad |
|---|---|
| `mdm/PolicyManager.kt` | Motor central. Singleton, ~50 KB, el archivo más grande. Todas las llamadas a DevicePolicyManager/UserManager, FRP, presets HMAC, bloqueo de internet por proxy. |
| `mdm/AppController.kt` | Suspender/ocultar/desinstalar apps, inventario de apps instaladas. |
| `mdm/WebViewBlockManager.kt` / `WebViewPolicy.kt` | WebView bloqueado por app; whitelist estricta (`CORE_DOMAINS`) para Waze/DiDi, whitelist dinámica para el resto. |
| `mdm/ImageBlockManager.kt` | Filtrado visual de imágenes (silueta / AI gate). |
| `dns/DomainRuleManager.kt`, `dns/DomainRuleTrie.kt` | Reglas DNS personalizadas, 4 tipos (ver Capa 2). Arranca la VPN sola al fijar una regla. |
| `service/KosherVpnService.kt` | Capa 2. `onRevoke()` + monitor de cambios de red (`registerDefaultNetworkCallback`, debounce 3s) para reconectar el túnel ante handoff Wi-Fi↔datos. |
| `util/NetworkForwarder.kt`, `util/DnsPacketParser.kt`, `util/IpPacketParser.kt` | Parseo/reenvío de paquetes IP/UDP/DNS de bajo nivel. |
| `util/AdBlocker.kt` | Blacklist de anuncios, HashSet O(1). |
| `service/WatchdogForegroundService.kt` | Foreground, sondea cada **20s** (subido de 3s en v0.4.9.1 por batería — no bajar sin revisar ese historial). Reimpone DNS privado desactivado, `BootReceiver.ensureVpnRunning()`, chequea Accesibilidad, sincroniza a Firebase. |
| `worker/WatchdogWorker.kt` | WorkManager, cada **15 min**, sobrevive a que muera el proceso. |
| `receiver/BootReceiver.kt` | Al bootear: reaplica restricciones, arranca Watchdog y VPN. Acá viven `shouldVpnBeRunning()` y `ensureVpnRunning()`. |
| `receiver/PackageReceiver.kt` | Detecta fin real de instalación (`ACTION_PACKAGE_REPLACED`) durante auto-actualización de Play Store; timeout watchdog de 10 min. |
| `service/LockSuiteAccessibilityService.kt` | Capa 3. Detección por texto/IDs de vista (incluye ídish). Automatiza clicks en el flujo de actualización de Play Store (`handlePlayStoreAutoUpdate`). |
| `service/BlockOverlayManager.kt` | Overlay negro opaco a pantalla completa que absorbe el 100% de los toques durante una actualización forzada de app. |
| `service/LockSuiteFirebaseService.kt` | Recibe comandos FCM (BLOCK_VPN, UPDATE_APP, CHANGE_PIN, etc.), despacha, responde ACK. |
| `util/FirebaseDeviceSync.kt` | Sincroniza estado al panel (Firebase Auth anónima — ver Pendientes B.3, esto no es una identidad segura). |
| `security/PinManager.kt`, `security/SessionManager.kt` | PIN de admin (comparación en tiempo constante), sesión activa. |
| `security/KnoxHardening.kt` | Solo Samsung + licencia KPE Standard: bloquea Odin/Download Mode y reset de fábrica por hardware. No interrumpe nada en otros equipos. |
| `util/SelfUpdater.kt`, `util/ApkInstaller.kt` | Auto-actualización OTA silenciosa vía `PackageInstaller`, lee `version.json`. |
| `ui/dashboard/DashboardActivity.kt` | Panel local en el celular (Compose): políticas, Presets (export/import `.locksuite` firmado HMAC), reglas DNS. |
| `ui/auth/LoginActivity.kt`, `ui/auth/SetupPinActivity.kt` | Login y configuración inicial de PIN. |

### Backend (`admin-backend/`)

Firebase (Hosting + Cloud Functions v2 Node.js + Realtime Database). Proyecto: **`looksuite-41866`**, cuenta dueña `imc112818@gmail.com`. `functions/index.js` (`sendCommandV8`, whitelist `ALLOWED_COMMANDS`), `database.rules.json`, `public/` (panel HTML/CSS/JS + instalador WebADB). Repo de código: **`github.com/CHKI541/Lock-Suite`, público** (ver Pendientes B.2 — es la recomendación de mayor impacto y menor esfuerzo de toda la lista).

### Compilar y desplegar

- Android: Android Studio + JDK 17, `google-services.json` en `app/`, keystore en `local.properties`. `./gradlew assembleRelease` → APK en `app/build/outputs/apk/release/`.
- Panel: `cd admin-backend && firebase deploy --only hosting,database,functions` — **desplegar `hosting,database` primero y `functions` en un try/catch aparte** (lo hace `deploy_all.ps1`); si Cloud Functions falla en el mismo llamado, Firebase no confirma la versión de Hosting y los celulares siguen viendo el manifiesto viejo.
- **`deploy_all.ps1`** (raíz del proyecto) automatiza todo: sube versionCode/versionName en `build.gradle.kts` y `version.json`, compila, copia el APK a `admin-backend/public/`, se autentica con cuenta de servicio, despliega, commitea y pushea a GitHub en un solo paso. Ejemplo: `.\deploy_all.ps1 -VersionName "0.6.16"`. **Ojo:** hace `git add .`, así que commitea junto TODO lo que esté sin commitear en ese momento (informes, `_to_delete/`, cambios de otras sesiones) — si querés separarlo, commiteá eso vos antes de correrlo.
- Despliegue sin login interactivo: variable `$env:GOOGLE_APPLICATION_CREDENTIALS` apuntando al JSON de service account en `admin-backend/`. Hay tres pasos manuales en Firebase Console que ningún script reemplaza (plan Blaze, habilitar Email/Password + Anonymous en Authentication, crear el usuario admin del panel) — detalle completo en `admin-backend/DEPLOY_GUIDE_FOR_AI_AGENT.md`, que sigue vigente.
- Última versión confirmada por commit: HEAD `7461896` (14/8), versionCode 77 / versionName 0.6.15. **En curso ahora mismo (16/8):** Antigravity está compilando **0.6.18**, que incluye el fix de B.8 (Accesibilidad en Android 13). Todavía no está confirmado instalado ni probado en equipo real, ni commiteado — no lo des por cerrado hasta que se actualice esta línea. **Verificá de nuevo** (`git log -1`, `cat app/build.gradle.kts`) al arrancar cualquier sesión — no asumas que sigue así.

### Carpetas del repo que NO son código real

| Carpeta | Qué es |
|---|---|
| `capa3_opcion_a/`, `capa3_opcion_b/` | Prototipos huérfanos de una Capa 2/3 alternativa, no se compilan, no tienen efecto en la app instalada. `capa3_opcion_a` es una versión vieja y chica de `KosherVpnService.kt`; `capa3_opcion_b` usa la app de terceros NetGuard. No confundir con código real. |
| `android_updates/` | Copia parcial/vieja de `app/src/main/...` + un `CHANGELOG.md` de un parche puntual. No es el código en producción. |
| `_to_delete/` | Convención del proyecto: archivos reemplazados que se mueven acá en vez de borrarse (algunas herramientas de IA no pueden borrar en este entorno). Está en uso activo. |
| `release-apk/`, `build/`, `.gradle/`, `.kotlin/` | Artefactos de build. No tocar a mano. |

### Documentación que sigue viva (no se fusiona acá, se indexa)

- `README.md` — instalación, arquitectura, bilingüe EN/ES.
- `walkthrough.md` — changelog real versión por versión (generado por Antigravity). Última entrada: v0.6.1.
- `DOCUMENTACION_FUNCIONES.md` — manual técnico funcional. **Incompleto**: el índice promete 15 secciones, solo están escritas la 1 a la 4 (visión general, arquitectura, restricciones DPM, bloqueos de hardware). Para VPN/control de apps/FRP/presets/panel web/OTA/troubleshooting hay que leer el código fuente directamente.
- `admin-backend/DEPLOY_GUIDE_FOR_AI_AGENT.md` — guía paso a paso de despliegue Firebase para un agente con terminal.
- `INSTRUCCIONES_ANTIGRAVITY_DE_CLAUDE.md` (14/8) — instrucciones detalladas todavía sin resolver, con el código exacto y las alternativas de arquitectura para B.3. Leerlo completo antes de tocar cualquiera de los puntos B.2 a B.7 de abajo.
- `INFORME_FORENSE_ACCESIBILIDAD_ANDROID13.md` (15/8) — diagnóstico completo del bug de accesibilidad en Android 13, con evidencia ADB (ver B.8).
- `CAMBIOS_ACTUALIZACION_PLAYSTORE.md` + `EXPLICACION_ACTUALIZACION_GOOGLE_PLAY.md` — arquitectura completa del flujo de actualización silenciosa por Play Store (overlay, accesibilidad, Firebase service, package receiver, policy manager).

### Limitaciones del entorno de IA en la nube (puente `mcp__remote-devices__*`)

- No hay Android SDK, Gradle ni acceso a Firebase — ninguna sesión de IA que trabaje por este puente puede compilar, probar en equipo real ni desplegar. Todo cambio de código lo tenés que compilar y probar vos, o Antigravity con terminal real.
- `device_stage_files` falla (HTTP 400) para archivos bajo `app/src/main/java/...` puntualmente (parece un problema de sincronización/placeholder de OneDrive ahí). Para leer/escribir código Kotlin real, usar `device_bash` con `cat`/`grep`/`nl` para leer, y un script Python vía heredoc para escribir con reemplazo de texto exacto verificando **una sola coincidencia** antes de escribir (cuidado con CRLF vs LF). Los cambios por `device_bash` quedan directo en el disco del usuario, sin paso de "commit" al puente.
- La VM de `device_bash` a veces tarda bastante más de lo que dice "Workspace still starting" (se vio más de 3 minutos) — no conviene reintentar en loop.

---

## B. PENDIENTES (deuda técnica y decisiones abiertas)

*(tachar / marcar **[RESUELTO fecha]** solo cuando esté confirmado arreglado Y probado en equipo real — no alcanza con que compile o con que otro documento lo haya dado por cerrado.)*

**B.1 — Inmediato: compilar y desplegar lo que Claude ya dejó escrito el 14/8.** Seis archivos ya corregidos en el repo (tres ya commiteados: `KosherVpnService.kt`, `NetworkForwarder.kt`, `database.rules.json`; tres sin commitear: `PolicyManager.kt`, `admin-backend/public/app.js`, `admin-backend/functions/index.js`, diff total +55/-3 líneas). No hay que rehacerlos, solo compilar (`./gradlew compileDebugKotlin`), correr el checklist de prueba en equipo real (7 puntos, detalle completo en `INSTRUCCIONES_ANTIGRAVITY_DE_CLAUDE.md` §1.3 — el más importante es confirmar que el bloqueo de WebView y ofertas de Mercado Pago funciona, antes de este cambio no bloqueaba) y desplegar `database` primero (es el fix de seguridad), después `functions` y `hosting`.

**B.2 — Crítico, bajo esfuerzo: el repo de GitHub es público.** `github.com/CHKI541/Lock-Suite` se puede leer sin credenciales (`raw.githubusercontent.com/CHKI541/Lock-Suite/main/...`), incluyendo el código fuente completo, las reglas de Firebase y la clave HMAC de los presets. Quien quiera evadir el bloqueo no necesita decompilar nada. Recomendación: pasar el repo a privado y mover el APK + `version.json` a Firebase Hosting (que ya se usa y sirve archivos públicos sin publicar el código). Si se decide dejarlo público, entonces B.3 y B.5 pasan de "conveniente" a obligatorio.

**B.3 — Aislamiento real de escritura por dispositivo en Firebase (abierto, a pesar de que un documento anterior lo daba por resuelto).** La regla actual de `devices/$device_id` exige `newData.child('ownerUid').val() === auth.uid` — pero `newData` es el dato que el propio cliente está escribiendo, así que cualquiera puede declararse dueño de cualquier nodo con solo incluir ese campo en su escritura. Un uid de sesión anónima no es una identidad estable de dispositivo, así que no alcanza como base de una regla de propiedad. Dos caminos (elegir uno, detalle completo en `INSTRUCCIONES_ANTIGRAVITY_DE_CLAUDE.md` §2.2): (a) Custom Token por dispositivo vía Cloud Function — preferido; (b) mover todas las escrituras detrás de una Cloud Function con Admin SDK y dejar `.write: false` para clientes. Cualquiera de los dos necesita convivencia/migración para los equipos ya en producción sin `ownerUid` o con uno viejo.

**B.4 — VPN: decisión de arquitectura sin resolver.** Capa 2 es un túnel dividido que solo captura DNS — no cubre DoH/DoT, QUIC ni conexiones directas por IP. El `lockdown` de Android está en `false` a propósito: se probó `lockdown=true` (para fallo-cerrado si la VPN cae) y rompió el internet general de inmediato, porque con lockdown Android exige que TODO el tráfico salga por la VPN, y esta VPN de solo-DNS no sabe qué hacer con tráfico que no sea DNS. El comentario en `PolicyManager.setVpnConfigBlocked()` documenta el experimento — leerlo antes de volver a proponer `lockdown=true`. Autoreparación actual (de más rápida a más lenta): `onRevoke()`/monitor de red (instantáneo) → `WatchdogForegroundService` (20s) → `WatchdogWorker` (15 min) → `START_STICKY`. Tres caminos discutidos, ninguno decidido: (1) dejarlo como está — recomendado, la autoreparación acota la ventana de exposición a segundos; (2) túnel completo TCP/UDP — cambio grande, más batería/CPU, más superficie de bugs; (3) "lockdown casero" — que el Watchdog detecte "la VPN debería estar arriba y no lo está" y aplique automáticamente el mismo truco de proxy global a puerto muerto que ya usa `setInternetBlocked()`, hasta que la VPN vuelva (reutiliza código existente, pero es un proxy HTTP, no una regla de firewall real — sin probar). También pendiente: telemetría real de tiempo de VPN caída vía `FirebaseDeviceSync`.

**B.5 — Clave HMAC de presets, en texto plano y pública.** `LockSuiteMDM_Preset_HMAC_SecretKey_2026` está hardcodeada en `admin-backend/public/app.js` (que Firebase Hosting sirve públicamente) y en el repo de GitHub (público, ver B.2). Cualquiera puede firmar un preset `.locksuite` válido. Reemplazar por firma asimétrica (ECDSA P-256 o RSA-2048): clave privada solo en una Cloud Function, clave pública embebida en la app únicamente para verificar.

**B.6 — Sin verificación de integridad en APKs de autoactualización/tienda administrada.** `SelfUpdater.kt` descarga e instala sin comparar checksum. La autoactualización de LockSuite tiene protección gratis por exigir la misma firma de Android; la Tienda administrada (`downloadAndInstallApk`) no, porque es la primera instalación de ese paquete. Agregar `sha256` a `version.json` y a cada entrada de `storeApps`, comparar contra el archivo ya descargado en disco antes de abrir la sesión de `PackageInstaller`.

**B.7 — `UPDATE_APP` y `UPDATE_LOCKSUITE` no piden PIN del dispositivo.** Es una excepción deliberada en `sendCommandV8` (`admin-backend/functions/index.js`) para poder actualizar equipos sin PIN a mano. Mientras seas el único admin no importa; si sumás más gente a `authorizedAdmins`, cualquiera de ellos puede empujar una actualización a cualquier equipo sin probar que conoce su PIN. Decidir si se mantiene (documentarlo explícito + destacarlo en `commandLog`) o se revierte.

**B.8 — Accesibilidad rota en Android 13: fix en código, EN COMPILACIÓN AHORA (v0.6.18, 16/8, por Antigravity) — falta instalar y confirmar.** Causa raíz: el `intent-filter` de `LockSuiteAccessibilityService` en el Manifest tenía la acción mal escrita (`android.view.accessibility.AccessibilityService`, el paquete Java, en vez de `android.accessibilityservice.AccessibilityService`, la acción real de sistema) — Android nunca registraba el servicio (`installedServiceCount=0`, confirmado por ADB). Ya corregido en el Manifest, más los comandos ADB de desbloqueo de "Restricted Settings" propio de Android 13 para apps instaladas por sideload. Diagnóstico completo con evidencia ADB en `INFORME_FORENSE_ACCESIBILIDAD_ANDROID13.md`. Estado al 16/8: Antigravity está compilando la v0.6.18 con este fix. Falta: terminar de compilar, instalar, y confirmar en un equipo Android 13 real que el servicio aparece en `dumpsys accessibility` y que el bloqueo de Mercado Pago/WhatsApp vuelve a andar — recién ahí pasa a [RESUELTO].

**B.9 — Dos bugs de auto-actualización por Play Store, arreglados en código el 14/8, sin compilar/probar.** (a) Si fallaban tanto el intent de Play Store como el de navegador, las restricciones quedaban levantadas indefinidamente sin red de seguridad, porque la alarma watchdog se armaba después del punto de fallo — se adelantó el armado de la alarma y se agregó `rollbackFailedUpdateApp()`. (b) En Android 11/12 (equipos Qin) el clic automático en "Actualizar" fallaba con `IllegalStateException` porque el nodo de accesibilidad ya estaba reciclado (`recycle()`) cuando se intentaba usar — se quitaron los `recycle()` de ese recorrido puntual. Detalle en `INSTRUCCIONES_ANTIGRAVITY_DE_CLAUDE.md` §2.1/2.1-b.

**B.10 — Menores / housekeeping:**

- `DISALLOW_CONFIG_DATE_TIME` no implementado — el bloqueo de intentos de PIN ya usa reloj monotónico así que no es explotable hoy, pero cerraría del todo esa puerta. Implementar como interruptor opcional del panel, no forzado (decisión de producto: el usuario final pierde poder corregir la hora).
- Falta un cartel en el panel avisando las dos limitaciones reales del bloqueo de WebView/internet por app: no existe en Android <10, y en todas las versiones las apps que resuelven DNS por el resolutor del sistema pasan por `netd` y no se pueden atribuir. Los campos `perAppDnsRulesSupported`/`androidSdkInt` ya se reportan desde el celular, falta usarlos en el panel.
- `MainScreenTest.kt`/`MainScreenViewModelTest.kt` son plantilla de Android Studio sin relación con el código real — candidatos a borrar.
- Plugin `kotlin.serialization` en `app/build.gradle.kts` sin uso aparente.
- Archivar `capa3_opcion_a/` y `capa3_opcion_b/` a `_to_delete/` (ver A — son prototipos huérfanos, no código real).
- `walkthrough.md` tiene numeración de secciones inconsistente (se repiten "### 2." y "### 3." varias veces) — no es urgente, pero conviene una pasada de limpieza en algún momento.

---

## C. BITÁCORA — última sesión conocida

*(Esto se reemplaza en cada cierre de sesión, no se acumula. Para el historial completo versión por versión, ver `walkthrough.md`.)*

**16/8 (en curso):** Antigravity está compilando la v0.6.18, que resuelve B.8 (fix del `intent-filter` de accesibilidad, diagnosticado el 15/8). Todavía no instalada ni confirmada en equipo real. Apenas se confirme, actualizar B.8 a [RESUELTO 2026-08-XX] con el resultado de la prueba en el celular Android 13 real.

**14–15/8:** Sesión de Claude revisando los cambios de Antigravity de la v0.4.9.3 a la v0.6.15 (versionCode 77, HEAD `7461896`). Encontró y corrigió 6 archivos (detalle en B.1), y dejó un análisis nuevo de 6 puntos que no pudo resolver por no tener SDK/terminal real (B.2 a B.7). El mismo tramo de días: diagnóstico forense completo del bug de accesibilidad en Android 13 (B.8), y dos fixes de auto-actualización por Play Store (B.9). Nada de esto se compiló, probó en equipo real, ni commiteó todavía — sigue en el working tree.

**Antes de eso (~9–13/8):** episodio del VPN lockdown — se probó `lockdownEnabled=true` para fallo-cerrado, rompió el internet general de inmediato (no solo durante caídas de VPN), se revirtió a `false` con el motivo documentado en el comentario del código (ver B.4). Se mantuvieron como mejoras reales e independientes: `onRevoke()` y el monitor de cambios de red en `KosherVpnService`, y la reafirmación de Always-on VPN en `reapplyAllRestrictions()`.

**Próximo paso sugerido:** confirmar en un equipo Android 13 real que 0.6.18 arregla la Accesibilidad (B.8) apenas Antigravity termine de compilarla e instalarla. En paralelo, B.1 (compilar/probar/desplegar lo que Claude dejó escrito el 14/8) y B.2 (privar el repo) siguen siendo los de mayor impacto y menor esfuerzo.

---

## Estado del repo (git)

Mucho trabajo sin commitear, de varias sesiones y de más de una herramienta de IA en paralelo. Correr `git status` y `git diff --stat` al arrancar cualquier sesión nueva, en vez de confiar en una lista fija acá (cambia todo el tiempo).

Recomendación aparte de lo técnico: commitear en puntos de control claros (por ejemplo al final de cada sesión de IA que dejó algo probado y andando). Sin eso, cualquier sesión nueva puede pisar silenciosamente el trabajo de otra herramienta — como ya pasó una vez con el flag de `lockdown` (ver Bitácora).
