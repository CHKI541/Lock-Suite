# LockSuite / KosherLock MDM — Contexto para IA (archivo único)

Leé este archivo primero, completo, antes de tocar código. Es el único documento pensado para que una sesión de IA nueva (Claude, Antigravity, o cualquier otra) entienda el proyecto sin releer todo el código fuente ni los informes sueltos que se fueron acumulando.

Este archivo reemplaza y consolida lo vigente de `DOCUMENTO_TECNICO_PARA_CLAUDE.md`, `AUDITORIA_0.5.0.md`, `INSTRUCCIONES_ANTIGRAVITY_v0.6.0.md`, `CAMBIOS_CLAUDE_UI_DNS_para_Antigravity.md`, `Informe_Correcciones_LineaPorLinea_2026-07-26` e `Informe_Segunda_Revision_Profunda_2026-07-27` (docx+txt). Esos seis quedan archivados en `docs_historicos/` — tienen valor como historial de qué se probó y por qué, pero no hace falta leerlos para trabajar hoy.

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
| `service/BlockOverlayManager.kt` | Overlay negro opaco a pantalla completa que absorbe el 100% de los toques durante una actualización forzada de app. Desde el 16/8 muestra título + estado en vivo + botón Cancelar. |
| `util/UpdateFlowManager.kt` | **(16/8)** Punto único de control del flujo "actualizar una app por Play Store con la pantalla tapada": arranque, etapas, cancelación y cierre. Todo arranque pasa por `start()` y toda salida por `finish()`. Nada más en el proyecto debe escribir `mdm_install_in_progress`. |
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
- `CAMBIOS_ACTUALIZACION_PLAYSTORE.md` + `EXPLICACION_ACTUALIZACION_GOOGLE_PLAY.md` — arquitectura del flujo de actualización silenciosa por Play Store. **Parcialmente desactualizados desde el 16/8**: describen bien la intención y los problemas históricos, pero el reparto de responsabilidades cambió — ahora el flujo vive centralizado en `util/UpdateFlowManager.kt` y no repartido entre cuatro archivos. Leerlos como historial, no como mapa del código actual.

### Limitaciones del entorno de IA en la nube (puente `mcp__remote-devices__*`)

- No hay Android SDK, Gradle ni acceso a Firebase — ninguna sesión de IA que trabaje por este puente puede compilar, probar en equipo real ni desplegar. Todo cambio de código lo tenés que compilar y probar vos, o Antigravity con terminal real.
- `device_stage_files` falla (HTTP 400) para archivos bajo `app/src/main/java/...` puntualmente (parece un problema de sincronización/placeholder de OneDrive ahí). Para leer/escribir código Kotlin real, usar `device_bash` con `cat`/`grep`/`nl` para leer, y un script Python vía heredoc para escribir con reemplazo de texto exacto verificando **una sola coincidencia** antes de escribir (cuidado con CRLF vs LF). Los cambios por `device_bash` quedan directo en el disco del usuario, sin paso de "commit" al puente.
- La VM de `device_bash` a veces tarda bastante más de lo que dice "Workspace still starting" (se vio más de 3 minutos) — no conviene reintentar en loop.

---

## B. PENDIENTES (deuda técnica y decisiones abiertas)

*(tachar / marcar **[RESUELTO fecha]** solo cuando esté confirmado arreglado Y probado en equipo real — no alcanza con que compile o con que otro documento lo haya dado por cerrado.)*

**B.1 — Inmediato: compilar y desplegar lo que Claude ya dejó escrito el 14/8.** Seis archivos ya corregidos en el repo (tres ya commiteados: `KosherVpnService.kt`, `NetworkForwarder.kt`, `database.rules.json`; tres sin commitear: `PolicyManager.kt`, `admin-backend/public/app.js`, `admin-backend/functions/index.js`, diff total +55/-3 líneas). No hay que rehacerlos, solo compilar (`./gradlew compileDebugKotlin`), correr el checklist de prueba en equipo real (7 puntos, detalle completo en `INSTRUCCIONES_ANTIGRAVITY_DE_CLAUDE.md` §1.3 — el más importante es confirmar que el bloqueo de WebView y ofertas de Mercado Pago funciona, antes de este cambio no bloqueaba) y desplegar `database` primero (es el fix de seguridad), después `functions` y `hosting`.

**B.2 — Crítico, bajo esfuerzo: el repo de GitHub es público.** `github.com/CHKI541/Lock-Suite` se puede leer sin credenciales (`raw.githubusercontent.com/CHKI541/Lock-Suite/main/...`), incluyendo el código fuente completo, las reglas de Firebase y la clave HMAC de los presets. Quien quiera evadir el bloqueo no necesita decompilar nada. Recomendación: pasar el repo a privado y mover el APK + `version.json` a Firebase Hosting (que ya se usa y sirve archivos públicos sin publicar el código). Si se decide dejarlo público, entonces B.3 y B.5 pasan de "conveniente" a obligatorio.

**B.3 — Aislamiento real de escritura por dispositivo en Firebase (abierto, a pesar de que un documento anterior lo daba por resuelto).** La regla actual de `devices/$device_id` exige `newData.child('ownerUid').val() === auth.uid` — pero `newData` es el dato que el propio cliente está escribiendo, así que cualquiera puede declararse dueño de cualquier nodo con solo incluir ese campo en su escritura. Un uid de sesión anónima no es una identidad estable de dispositivo, así que no alcanza como base de una regla de propiedad. Hace falta conocer el `deviceId` (el `ANDROID_ID` del equipo) para explotarlo — no se puede listar, pero en Android 7 es el mismo para todas las apps del celular, y el panel lo muestra en pantalla, así que no es un secreto fuerte. Con eso, alguien podría: escribir basura en `fcmToken` para sacar un equipo del control del panel para siempre, sobrescribir `pinHash`/`pinSalt` para inutilizar el PIN de un equipo ajeno, o escribir `trustedAdmins/{miUid}=true` para saltear el PIN por completo si además tiene una cuenta de admin. Relacionado y agravante: el panel ya escribe `trustedAdmins` directo desde el navegador (`app.js`, cerca de la línea 181) sin pasar por la Cloud Function, y las reglas se lo permiten — cualquier admin autorizado puede marcarse "de confianza" para cualquier equipo sin nunca ingresar su PIN; el PIN protege de externos, no de un operador del panel. Dos caminos para cerrarlo de raíz (elegir uno, detalle completo en `INSTRUCCIONES_ANTIGRAVITY_DE_CLAUDE.md` §2.2): (a) Custom Token por dispositivo vía Cloud Function — preferido; (b) mover todas las escrituras (incluida `trustedAdmins`) detrás de una Cloud Function con Admin SDK y dejar `.write: false` para clientes. Cualquiera de los dos necesita convivencia/migración para los equipos ya en producción sin `ownerUid` o con uno viejo.

**B.3-b — El hash del PIN usa SHA-256 (rápido), no una función lenta pensada para contraseñas.** El esquema actual (salt aleatorio por dispositivo + SHA-256 + comparación en tiempo constante + `EncryptedSharedPreferences`) es correcto contra alguien que solo puede probar PINs en la pantalla del celular. El riesgo es si el hash+salt se filtran algún día (por ejemplo, a través del hueco de B.3): SHA-256 es rápido a propósito, y un PIN de 4-16 dígitos tiene poca entropía, así que probar todas las combinaciones contra un hash filtrado es cuestión de milisegundos. Migrar a PBKDF2 (ya disponible en Android) o Argon2 lo evitaría, pero el hash se calcula en tres lugares que tienen que coincidir exactamente (`PinManager.kt` en la app, `hashPin` en `functions/index.js`, `hashPinLocal` en `app.js`) — cambiar el algoritmo implica los tres a la vez, más migrar los PINs ya existentes en producción. No es urgente mientras B.3 sea el problema más grande; sube de prioridad en cuanto B.3 se cierre.

**B.4 — VPN: decisión de arquitectura sin resolver.** Capa 2 es un túnel dividido que solo captura DNS — no cubre DoH/DoT, QUIC ni conexiones directas por IP. El `lockdown` de Android está en `false` a propósito: se probó `lockdown=true` (para fallo-cerrado si la VPN cae) y rompió el internet general de inmediato, porque con lockdown Android exige que TODO el tráfico salga por la VPN, y esta VPN de solo-DNS no sabe qué hacer con tráfico que no sea DNS. El comentario en `PolicyManager.setVpnConfigBlocked()` documenta el experimento — leerlo antes de volver a proponer `lockdown=true`. Autoreparación actual (de más rápida a más lenta): `onRevoke()`/monitor de red (instantáneo) → `WatchdogForegroundService` (20s) → `WatchdogWorker` (15 min) → `START_STICKY`. Tres caminos discutidos, ninguno decidido: (1) dejarlo como está — recomendado, la autoreparación acota la ventana de exposición a segundos; (2) túnel completo TCP/UDP — cambio grande, más batería/CPU, más superficie de bugs; (3) "lockdown casero" — que el Watchdog detecte "la VPN debería estar arriba y no lo está" y aplique automáticamente el mismo truco de proxy global a puerto muerto que ya usa `setInternetBlocked()`, hasta que la VPN vuelva (reutiliza código existente, pero es un proxy HTTP, no una regla de firewall real — sin probar). También pendiente: telemetría real de tiempo de VPN caída vía `FirebaseDeviceSync`.

**B.5 — Clave HMAC de presets, en texto plano y pública.** `LockSuiteMDM_Preset_HMAC_SecretKey_2026` está hardcodeada en `admin-backend/public/app.js` (que Firebase Hosting sirve públicamente) y en el repo de GitHub (público, ver B.2). Cualquiera puede firmar un preset `.locksuite` válido. Reemplazar por firma asimétrica (ECDSA P-256 o RSA-2048): clave privada solo en una Cloud Function, clave pública embebida en la app únicamente para verificar.

**B.6 — Sin verificación de integridad en APKs de autoactualización/tienda administrada.** `SelfUpdater.kt` descarga e instala sin comparar checksum. La autoactualización de LockSuite tiene protección gratis por exigir la misma firma de Android; la Tienda administrada (`downloadAndInstallApk`) no, porque es la primera instalación de ese paquete. Agregar `sha256` a `version.json` y a cada entrada de `storeApps`, comparar contra el archivo ya descargado en disco antes de abrir la sesión de `PackageInstaller`.

**B.7 — `UPDATE_APP` y `UPDATE_LOCKSUITE` no piden PIN del dispositivo.** Es una excepción deliberada en `sendCommandV8` (`admin-backend/functions/index.js`) para poder actualizar equipos sin PIN a mano. Mientras seas el único admin no importa; si sumás más gente a `authorizedAdmins`, cualquiera de ellos puede empujar una actualización a cualquier equipo sin probar que conoce su PIN. Decidir si se mantiene (documentarlo explícito + destacarlo en `commandLog`) o se revierte.

**B.8 — Accesibilidad rota en Android 13: fix en código, EN COMPILACIÓN AHORA (v0.6.18, 16/8, por Antigravity) — falta instalar y confirmar.** Causa raíz: el `intent-filter` de `LockSuiteAccessibilityService` en el Manifest tenía la acción mal escrita (`android.view.accessibility.AccessibilityService`, el paquete Java, en vez de `android.accessibilityservice.AccessibilityService`, la acción real de sistema) — Android nunca registraba el servicio (`installedServiceCount=0`, confirmado por ADB). Ya corregido en el Manifest, más los comandos ADB de desbloqueo de "Restricted Settings" propio de Android 13 para apps instaladas por sideload. Diagnóstico completo con evidencia ADB en `INFORME_FORENSE_ACCESIBILIDAD_ANDROID13.md`. Estado al 16/8: Antigravity está compilando la v0.6.18 con este fix. Falta: terminar de compilar, instalar, y confirmar en un equipo Android 13 real que el servicio aparece en `dumpsys accessibility` y que el bloqueo de Mercado Pago/WhatsApp vuelve a andar — recién ahí pasa a [RESUELTO].

**B.9 — Actualización de apps por Play Store: reescrita el 16/8. Compila-y-reza: NADA de esto se probó en equipo real todavía.** Los dos bugs del 14/8 siguen arreglados (rollback si no abre ni Play Store ni navegador; `recycle()` sacado del recorrido que guarda nodos). Lo del 16/8 es aparte y más profundo — siete causas encontradas para los dos síntomas reportados por el dueño ("la app no se actualiza" y "la pantalla negra no se va"):

1. *(no se actualizaba)* El emparejamiento de botones usaba `contains` sobre palabras cortísimas (`"ok"`, `"yes"`, `"sí"`, `"open"`, `"install"`) y aceptaba cualquier nodo con texto, clickeable o no, subiendo después al primer padre clickeable. En una pantalla de Play Store eso matchea decenas de nodos: se clickeaba cualquier cosa. Peor: `"installing"` contiene `"install"`, así que mientras descargaba volvía a "hacer clic en Actualizar" sobre la fila de progreso, que es donde Play Store pone el botón de cancelar la descarga. Ahora el emparejamiento es por **igualdad exacta** contra listas cerradas, con acentos normalizados.
2. *(no se actualizaba)* No existía ninguna detección de "Play Store ya está trabajando". Ahora, si se ve progreso, el ciclo no toca nada.
3. *(no se actualizaba)* Si el usuario salía a otra app, se relanzaba Play Store **en cada evento de accesibilidad** (el sistema los manda hasta diez por segundo) y encima con `FLAG_ACTIVITY_CLEAR_TASK`: la tarea de Play Store se destruía y recreaba sin parar, y la descarga nunca llegaba a arrancar. Ahora hay un mínimo de 1,5 s entre relanzamientos y se usa solo `NEW_TASK`.
4. *(no se actualizaba)* El escaneo no distinguía la fila de botones de la ficha de los carruseles de "apps similares" que están más abajo, cada uno con su propio "Instalar" / "Abrir". En una app que ya estaba al día, el escaneo podía agarrar el "Instalar" de OTRA app y apretarlo. Ahora los botones de acción solo se consideran en el 60 % superior de la pantalla.
5. *(pantalla trabada)* El watchdog de 10 minutos (`UPDATE_TIMEOUT` en `PackageReceiver`) limpiaba las preferencias y restauraba las restricciones **pero no sacaba el overlay**. Nadie más lo sacaba. Ese era, textual, el síntoma "queda la pantalla esa y no se va".
6. *(pantalla trabada)* La única forma de terminar bien era "aparece Abrir y no hay Actualizar". Cualquier otra cosa en pantalla —pedido de iniciar sesión, error de red, ficha en blanco— no terminaba nunca. Ahora hay cuatro salidas más: el `versionCode` del paquete cambió (la señal más confiable, no depende de ningún texto ni del broadcast), un freno por estancamiento a los 2 minutos, el botón Cancelar y el comando `CANCEL_UPDATE_APP` del panel.
7. *(pantalla trabada)* `reapplyAllRestrictions()` no miraba `mdm_install_in_progress` al re-suspender Play Store ni al re-suspender apps individuales. El `WatchdogWorker` corre cada 15 min y el flujo dura hasta 10: se pisaban, y el Watchdog volvía a suspender Play Store con la descarga a medias.

**Falta probar en equipo real, en este orden:** (a) actualizar una app con actualización pendiente desde el panel y confirmar que se instala sola, que el texto de la pantalla negra va cambiando y que al terminar Play Store se cierra y queda re-bloqueada; (b) lo mismo desde el botón nuevo "Actualizar apps" del celular, antes del PIN; (c) apretar Cancelar a mitad de la descarga y confirmar que vuelve a la pantalla común con todo re-bloqueado; (d) mandar una actualización de una app que ya está al día y confirmar que sale sola a los ~8 s; (e) confirmar que con el servicio de Accesibilidad apagado el flujo se **niega** a arrancar en vez de dejar Play Store abierta.

**B.11 — Suspensión temporal de LockSuite (nueva el 16/8, sin probar en equipo real).** `PolicyManager.setLockSuiteSuspended(true)` deja el equipo como si LockSuite no estuviera instalado; `false` lo devuelve a como estaba. Interruptor en la app (pestaña Políticas, primera tarjeta) y en el panel (`SUSPEND_LOCKSUITE` / `RESUME_LOCKSUITE`, exigen PIN del dispositivo). **Alcance elegido por el dueño el 16/8: se levanta absolutamente todo, incluidas las protecciones anti-manipulación** (bloqueo de restauración de fábrica, Knox/flasheo, FRP y el bloqueo de desinstalar LockSuite). Es una suspensión literal, con la consecuencia de que mientras dure el usuario puede desinstalar LockSuite o formatear el equipo y no habría vuelta atrás — usarla solo con el equipo a la vista.

Idea central del diseño, por si alguien la toca: la suspensión **no guarda ninguna copia del estado previo**. Levanta el estado real en el SO llamando directo a `dpm.*`, sin tocar las preferencias que registran la configuración deseada; al reanudar, `reapplyAllRestrictions()` reconstruye todo leyendo esas mismas preferencias. Por eso `liftAllForSuspension()` no usa los setters de la propia clase (escribirían la preferencia y destruirían lo que hay que restaurar). Si alguien "simplifica" eso a un snapshot, vuelve el problema clásico de copias incompletas o pisadas.

Mientras la suspensión está activa: `reapplyAllRestrictions()`, `refreshInstallRestriction()` y `restoreInstallRestrictions()` cortan al principio; `shouldVpnBeRunning()` devuelve false; el Watchdog no exige Accesibilidad ni suspende navegadores; `PackageReceiver` no re-suspende ni desinstala nada; el servicio de Accesibilidad no bloquea, no rebota y no dibuja; y los comandos de política del panel se rechazan con un mensaje claro en vez de aplicarse a medias (excepciones: `RESUME_LOCKSUITE`, `SUSPEND_LOCKSUITE`, `UPDATE_LOCKSUITE`, `VERIFY_PIN`, `CHANGE_PIN`, `CANCEL_UPDATE_APP`). Activar una política desde la app durante la suspensión guarda la intención sin aplicarla (`deferIfSuspended`).

**Falta probar en equipo real:** suspender, confirmar que todas las apps se desbloquean y que Ajustes/WiFi/cámara vuelven a funcionar; reiniciar el equipo suspendido y confirmar que sigue suspendido; reanudar y confirmar que vuelve exactamente la configuración anterior (incluidas suspensiones individuales de apps, launcher kosher y VPN). **Riesgo conocido sin resolver:** un equipo que quede suspendido por olvido es un equipo sin ninguna protección — no hay expiración automática. Evaluar agregar un vencimiento configurable.

**B.12 — El panel no ofrece suspender un grupo entero.** Deliberado: el interruptor de suspensión existe solo por dispositivo. Suspender un grupo con un clic es demasiado fácil de hacer sin querer para lo que implica. Si se decide agregarlo, va con confirmación escribiendo el nombre del grupo.

**B.10 — Menores / housekeeping:**

- `DISALLOW_CONFIG_DATE_TIME` no implementado — el bloqueo de intentos de PIN ya usa reloj monotónico así que no es explotable hoy, pero cerraría del todo esa puerta. Implementar como interruptor opcional del panel, no forzado (decisión de producto: el usuario final pierde poder corregir la hora).
- Falta un cartel en el panel avisando las dos limitaciones reales del bloqueo de WebView/internet por app: no existe en Android <10, y en todas las versiones las apps que resuelven DNS por el resolutor del sistema pasan por `netd` y no se pueden atribuir. Los campos `perAppDnsRulesSupported`/`androidSdkInt` ya se reportan desde el celular, falta usarlos en el panel.
- `MainScreenTest.kt`/`MainScreenViewModelTest.kt` son plantilla de Android Studio sin relación con el código real — candidatos a borrar.
- `app/build.gradle.kts`, `admin-backend/public/version.json` y `ui/emergency/BlockAccessibilityActivity.kt` figuran como modificados enteros contra HEAD, pero el diff es **solo cambio de fin de línea** (CRLF↔LF), sin una sola línea de contenido distinta. Alguna herramienta los reescribe al guardarlos. Conviene un `.gitattributes` con `* text=auto eol=lf` para que deje de ensuciar cada `git status`.
- `SelfUpdater` usa la misma bandera `mdm_install_in_progress` que el flujo de Play Store pero sin `updating_package`. Ya no se pueden pisar (`UpdateFlowManager.start()` se niega a arrancar si la bandera está puesta), pero siguen siendo dos flujos distintos compartiendo una variable: conviene separarlas cuando se toque `SelfUpdater`.
- `findPlayStoreRoot()` no recicla los `window.root` que descarta. Fuga menor de nodos (no-op desde API 33).
- Plugin `kotlin.serialization` en `app/build.gradle.kts` sin uso aparente.
- Archivar `capa3_opcion_a/` y `capa3_opcion_b/` a `_to_delete/` (ver A — son prototipos huérfanos, no código real).
- `walkthrough.md` tiene numeración de secciones inconsistente (se repiten "### 2." y "### 3." varias veces) — no es urgente, pero conviene una pasada de limpieza en algún momento.
- La Capa 3 (Accesibilidad) se puede apagar desde Ajustes del sistema — límite de la plataforma Android, no hay `DISALLOW_*` para impedirlo puntualmente; el Watchdog reacciona (relanza bloqueo, suspende navegadores) pero no cierra la ventana entre que se apaga y se detecta.
- `SecretCodeReceiver` exportado — necesario para recibir el código secreto del marcador telefónico del sistema, no se puede restringir sin romper esa función legítima; la acción que dispara queda igual detrás del código de recuperación.
- Etiqueta de estado del canal FCM hardcodeada en "LISTO" en el panel — a diferencia de Accesibilidad/VPN/Watchdog, no hay forma barata de verificarlo en tiempo real, queda como informativa no verificada.
- Declaraciones sin uso en `device_admin_policies.xml` — no se referencian desde ningún lado del código actual, revisar si siguen haciendo falta.
- Login del panel con email/contraseña sin comprobar `email_verified` — si un email de `authorizedAdmins` todavía no tiene cuenta creada en Firebase Auth, cualquiera podría registrarse con esa dirección y quedar como admin. Revisar que todas las direcciones autorizadas ya tengan cuenta, o exigir login solo con Google.
- `admin-backend/grant_public_access.js` desactiva la validación de certificados TLS (`NODE_TLS_REJECT_UNAUTHORIZED=0`) mientras se autentica con la clave de service account — es un script que corrés vos localmente (exposición acotada), pero esa línea no debería estar.
- Motor DNS (`KosherVpnService`/parsers): respuestas grandes se pueden perder (buffer de respuesta de 4096B contra MTU 1500 del túnel), una consulta con dominio no parseable se reenvía sin filtrar (la única regla del filtro que falla "abierto" en vez de "cerrado"), y bajo ráfagas de consultas el pool de hilos (2-4, cola de 128, hasta 3.5s por consulta) puede trabar la lectura del túnel unos segundos. Ninguno es urgente — quedan documentados por si aparece el síntoma (sitios puntuales que no cargan, lentitud al abrir páginas con muchos dominios).

---

## C. BITÁCORA — última sesión conocida

*(Esto se reemplaza en cada cierre de sesión, no se acumula. Para el historial completo versión por versión, ver `walkthrough.md`.)*

**16/8 — sesión de Claude (puente al dispositivo, sin compilar).** Punto de partida: HEAD `73e6956`, versionCode 80 / 0.6.18 (Antigravity ya la había commiteado con el fix de B.8). Tres pedidos del dueño, los tres implementados y ninguno probado en equipo real:

1. **Revisión y reescritura del flujo de actualización por Play Store.** Los dos síntomas reportados ("la app no se actualiza" y "queda la pantalla negra y no se va") resultaron ser siete bugs distintos, listados uno por uno en B.9. Los dos que mejor explican lo que se veía: el relanzamiento de Play Store con `CLEAR_TASK` en cada evento de accesibilidad (hasta diez por segundo), que destruía la tarea de la tienda antes de que la descarga arrancara; y el watchdog de 10 minutos, que restauraba las restricciones pero nunca sacaba el overlay. Todo el flujo se centralizó en un archivo nuevo, `util/UpdateFlowManager.kt`: un único `start()`, un único `finish()` idempotente que siempre saca la pantalla negra, y etapas con nombre que se ven en vivo tanto en el celular como en el panel.
2. **Pantalla de actualización con estado, y cancelable.** El overlay ahora muestra en qué va ("Abriendo Google Play", "Descargando", "Instalando", "Terminando y volviendo a bloquear") y trae un botón Cancelar. El panel muestra lo mismo y tiene su propio botón (comando `CANCEL_UPDATE_APP`, exento de PIN a propósito: si hiciera falta el PIN, un equipo trabado no se podría destrabar).
3. **Sección "Actualizar apps" antes del PIN** (`LoginActivity`), para que el usuario final actualice sus apps con el mismo flujo protegido, sin abrirle un camino para instalar lo que quiera. Se niega a arrancar si el servicio de Accesibilidad no está activo, porque sin él la pantalla no se puede tapar.
4. **Suspensión temporal de LockSuite** (B.11), en la app y en el panel. Alcance decidido por el dueño en esta sesión: se levanta absolutamente todo, anti-manipulación incluida.

Archivos tocados: `util/UpdateFlowManager.kt` (nuevo), `service/BlockOverlayManager.kt`, `service/LockSuiteAccessibilityService.kt`, `service/LockSuiteFirebaseService.kt`, `service/WatchdogForegroundService.kt`, `receiver/PackageReceiver.kt`, `receiver/BootReceiver.kt`, `mdm/PolicyManager.kt`, `mdm/AppController.kt`, `util/FirebaseDeviceSync.kt`, `ui/auth/LoginActivity.kt`, `ui/dashboard/DashboardActivity.kt`, `admin-backend/functions/index.js`, `admin-backend/public/index.html`, `admin-backend/public/app.js`.

**Cómo se verificó (y cómo no).** Se validó sintaxis JS con `node --check`, balance de llaves en todos los Kotlin, y se pasó una revisión de código completa con una segunda IA sobre los diez archivos Kotlin buscando errores de compilación y de lógica. Esa revisión encontró cinco bugs reales que ya están corregidos — el más serio, que `reapplyAllRestrictions()` no miraba `mdm_install_in_progress` al re-suspender Play Store, o sea que el Watchdog de 15 minutos podía cortar una actualización en curso y dejar la pantalla trabada otra vez. **Nada se compiló ni se probó en un equipo**: desde el puente al dispositivo no hay SDK ni Gradle. El checklist de prueba está en B.9 y B.11.

**14–15/8:** sesión de Claude revisando los cambios de Antigravity de la v0.4.9.3 a la v0.6.15. Encontró y corrigió 6 archivos (detalle en B.1), y dejó un análisis de 6 puntos que no pudo resolver por no tener SDK ni terminal real (B.2 a B.7). El mismo tramo: diagnóstico forense del bug de accesibilidad en Android 13 (B.8, ya arreglado y commiteado en la 0.6.18) y dos fixes de auto-actualización por Play Store (ahora absorbidos por la reescritura de B.9).

**Antes de eso (~9–13/8):** episodio del VPN lockdown — se probó `lockdownEnabled=true` para fallo-cerrado, rompió el internet general de inmediato, se revirtió a `false` con el motivo documentado en el comentario del código (ver B.4). Se mantuvieron como mejoras reales e independientes: `onRevoke()` y el monitor de cambios de red en `KosherVpnService`, y la reafirmación de Always-on VPN en `reapplyAllRestrictions()`.

**Próximo paso sugerido:** compilar (`./gradlew compileDebugKotlin`) y correr los dos checklists de equipo real —el de B.9 (actualización de apps) y el de B.11 (suspensión)— antes que cualquier otra cosa: son ~2.400 líneas nuevas que todavía no vio un compilador. Después, desplegar el panel (`database` primero, luego `functions` y `hosting`) porque hay tres comandos nuevos en `ALLOWED_COMMANDS` sin los cuales los botones nuevos del panel devuelven "Comando no reconocido". Y B.1 y B.2 siguen siendo los de mayor impacto y menor esfuerzo de la lista.

## Estado del repo (git)

Mucho trabajo sin commitear, de varias sesiones y de más de una herramienta de IA en paralelo. Correr `git status` y `git diff --stat` al arrancar cualquier sesión nueva, en vez de confiar en una lista fija acá (cambia todo el tiempo).

Recomendación aparte de lo técnico: commitear en puntos de control claros (por ejemplo al final de cada sesión de IA que dejó algo probado y andando). Sin eso, cualquier sesión nueva puede pisar silenciosamente el trabajo de otra herramienta — como ya pasó una vez con el flag de `lockdown` (ver Bitácora).
