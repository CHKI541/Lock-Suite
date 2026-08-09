# 🛠️ Walkthrough Técnico - LockSuite / Kosherlock MDM (v0.6.1)

---

## 📌 ¿Qué es este archivo?
El **`walkthrough.md`** es el documento técnico oficial generado por Antigravity (AI). Resume todo el historial de cambios, correcciones de errores, arquitectura y estado de verificación del proyecto.

---

## 🚀 Historial Reciente de Mejoras y Correcciones (v0.6.1)

### 1. Corrección de Bloqueos DNS de Dominio Específico (v0.6.1)
- **Problemas:**
  - Las reglas personalizadas añadidas no se aplicaban inmediatamente ni al reiniciar el dispositivo.
  - La caché DNS interna de Android seguía resolviendo las IPs reales para dominios que se acababan de bloquear.
- **Solución:**
  - **Recarga de Reglas:** Se agregó `domainRuleManager.loadRules()` al inicio de `KosherVpnService.startVpn()` para que el Trie se actualice siempre ante cualquier arranque o restauración de la VPN.
  - **Forzar reinicio y Limpieza de Caché:** Se implementó la acción `RESTART_VPN` en `KosherVpnService.onStartCommand()`. Al añadir o eliminar una regla en `DomainRuleManager.kt`, se dispara esta acción para re-establecer el túnel VPN al instante. Al recrearse la interfaz TUN, Android invalida su caché de DNS interna, forzando a que las nuevas peticiones consulten de nuevo y caigan en la regla de bloqueo inmediatamente.
  - **Apagado Dinámico:** Si se eliminan todas las reglas y no hay otra política activa, la VPN ahora se apaga correctamente usando `STOP_VPN`.

### 2. Solución a la Exportación de Presets de 0 Bytes (v0.6.1)
- **Problema:** Los archivos `.locksuite` exportados desde el menú de Presets se generaban con 0 bytes de tamaño.
- **Causa:** El JSON a exportar se almacenaba en un estado de Compose con `remember`. Al llamar al selector de archivos nativo, Android suspendía y recreaba la Activity, reinicializando el estado a `null`. Cuando el callback del selector escribía el archivo, leía `null` y lo guardaba vacío.
- **Solución:** Se reemplazó `remember` por `rememberSaveable` para `pendingExportJson` y `pendingExportName` en `DashboardActivity.kt`. Esto fuerza a Android a persistir el contenido del JSON en el `savedInstanceState` al recrear la Activity, permitiendo que se escriba con éxito al retornar del selector.

### 3. Historial de Versiones Anteriores (v0.5.5)

### 1. Resolución de Caché en Auto-Actualizaciones (v0.5.5)
- **Problema:** Los celulares mostraban que la app ya estaba al día en la versión anterior porque Firebase Hosting y las capas de red locales cacheaban la respuesta estática del archivo `version.json`.
- **Solución:**
  - Se definieron cabeceras de control de caché (`Cache-Control: no-cache, no-store, must-revalidate`) específicas para `/version.json` en `firebase.json`.
  - Se modificó `SelfUpdater.kt` para inyectar una marca de tiempo dinámica (`?t=timestamp`) en la solicitud de red, evitando el almacenamiento en caché local del cliente.

### 2. Endurecimiento de Seguridad vía Samsung Knox SDK (v0.5.5)
- **Mejora:** Integración del módulo `KnoxHardening.kt` para dispositivos Samsung. 
- **Bloqueos adicionales:**
  - **Flasheo de Firmware:** Bloqueo de Odin y Download Mode (`allowFirmwareRecovery(false)`). Evita que se reinstale la ROM de fábrica para evadir las políticas.
  - **Menú Recovery:** Bloqueo real del restablecimiento de fábrica desde el menú de recuperación del hardware (`allowFactoryReset(false)`), impidiendo el "hard reset" físico.
- **Funcionamiento:** Las llamadas se activan si el dispositivo es Samsung y se ha configurado una licencia KPE Standard gratuita en `strings.xml`. En otros dispositivos o sin la clave, el código se ejecuta de forma segura sin interrumpir las políticas estándar de Device Policy Manager.

### 2. Proxy para Cuando SUBO - Colectivos CABA (v0.5.4)
- **Mejora:** Agregada la Cloud Function `colectivosApi` que actúa como puente CORS seguro para consultar líneas, paradas y arribos en tiempo real desde la API móvil de "Cuando SUBO" de la tarjeta SUBE.

### 3. Soporte para Dispositivos de 32 bits (v0.4.9.3)
- **Problema:** Fallo al instalar `INSTALL_FAILED_NO_MATCHING_ABIS` en dispositivos Android con ROMs/sistemas operativos de 32 bits (como el Qin F21 Pro y otros teléfonos con teclado físico).
- **Causa:** Las librerías nativas de MediaPipe/TensorFlow Lite estaban configuradas para compilarse únicamente para la arquitectura `arm64-v8a` (64 bits).
- **Solución:** Se agregó la arquitectura `armeabi-v7a` a los filtros ABI (`abiFilters`) en `app/build.gradle.kts` para incluir las librerías nativas de 32 bits. Esto asegura la plena compatibilidad e instalación en dispositivos tanto de 32 bits como de 64 bits.


### 2. Mejoras Visuales en el Panel Web: Expansión de Sidebar, Iconos y Tooltips
- **Problema:** Los nombres de paquete largos se truncaban (`com.android.t...`), lo que dificultaba identificar qué aplicaciones eran exactamente. Tampoco se visualizaban los iconos de las aplicaciones.
- **Solución:**
  - **Expansión (Botón `↔`):** Se agregó un botón de expansión en el encabezado de los paneles laterales de administración de dispositivos y grupos. Al presionarlo, el panel se ensancha dinámicamente de `460px` a `750px` en pantalla, revelando la información completa.
  - **Iconos de Apps:** Se implementó la visualización de los iconos de las aplicaciones. Para las aplicaciones más comunes (como WhatsApp, Chrome, Gmail, YouTube, Google Maps, etc.), el sistema carga sus logotipos oficiales en SVG; para las demás aplicaciones, utiliza un icono placeholder con diseño premium.
  - **Tooltips:** Se agregaron tooltips interactivos de navegador. Al pasar el cursor sobre cualquier nombre de aplicación o paquete, se muestra la ruta completa sin cortes.

### 3. Optimización del Consumo de Batería del Watchdog (v0.4.9.1)
- **Problema:** El servicio en primer plano `WatchdogForegroundService.kt` realizaba comprobaciones de estado de accesibilidad y VPN de forma continua cada 3 segundos, lo que causaba despertares constantes de CPU y un gasto excesivo de batería en el celular.
- **Solución:** Se incremento el intervalo de sondeo del bucle de comprobaciones del Watchdog a 20 segundos (`20000L`). Esto reduce sustancialmente el número de ciclos de CPU activos y disminuye drásticamente el consumo de batería del celular, manteniendo una latencia de respuesta de seguridad óptima.
- **Actualización OTA:** Se incrementó el `versionCode` a `53` y `versionName` a `"0.4.9.1"` tanto en la configuración de la app de Android como en el archivo `version.json` del servidor OTA para asegurar que los dispositivos detecten la nueva versión y se actualicen automáticamente.

### 3. Corrección del Bloqueo de Cuentas Google en FRP (v0.4.6)
- **Problema:** Al activar el bloqueo de Factory Reset Protection (FRP), se bloqueaba automáticamente la posibilidad de agregar o modificar cualquier cuenta de Google en el celular.
- **Causa:** En la función `setLegacyFrpHardening` en `PolicyManager.kt`, se aplicaba automáticamente la restricción `UserManager.DISALLOW_MODIFY_ACCOUNTS` del sistema Android.
- **Solución:** Se eliminó esta restricción del flujo automático de FRP. Ahora el usuario puede agregar y administrar cuentas en el celular con normalidad mientras que el FRP sigue activo y restringido únicamente a las cuentas propietarias especificadas por LockSuite. Si se desea bloquear las cuentas de manera explícita, se puede seguir activando la restricción a demanda desde el panel de control general ("Bloquear modificación de cuentas").

### 2. Perfiles Locales (Presets) y Exportación/Importación Segura (v0.4.5)
- **Mejora:** Pestaña de **Presets (Perfiles)** en la aplicación de Android (`DashboardActivity.kt`) para guardar la configuración de políticas actual localmente.
- **Respaldos Criptográficos (`.locksuite`):** Permite exportar e importar las políticas firmando y validando el archivo con **HMAC-SHA256** para evitar alteraciones manuales del JSON.

### 3. Ocultar Íconos de Apps Suspendidas (v0.4.5)
- **Mejora:** Opción de **"Ocultar icono al suspender aplicaciones"** que usa `setApplicationHidden()` para hacer desaparecer por completo la aplicación del launcher de Android en lugar de mostrarla con el ícono gris de suspendido.

### 4. Reparación de la VPN y Redirección DNS (v0.4.3)
- **Mejoras de conectividad:** Se eliminó el Lockdown estricto (`lockdownEnabled = false`), se implementó la inyección del Checksum UDP en IPv6 (RFC 2460), y se desautorizó el tráfico de la propia app de MDM en el túnel VPN.

---

## 📂 Archivos Clave del Sistema

- **Android App Core**:
  - `app/src/main/java/.../ui/dashboard/DashboardActivity.kt`: Interfaz del panel Android con la nueva sección de Presets y backups HMAC.
  - `app/src/main/java/.../mdm/PolicyManager.kt`: Controlador de las APIs de Device Owner, encriptación HMAC, exportación e importación (FRP modificado en v0.4.6).
  - `app/src/main/java/.../service/KosherVpnService.kt`: Servicio VPN DNS en Capa 3.
  - `app/src/main/java/.../service/LockSuiteAccessibilityService.kt`: Servicio de Accesibilidad e intercepción visual.

- **Panel Web & Cloud Functions (Desplegados en Firebase)**:
  - `admin-backend/public/index.html` & `app.js`: Interfaz de administración en tiempo real.
  - `admin-backend/public/version.json`: Registro de versión live para Auto-Update OTA (actualizado a v0.5.5 / VC 61).
  - `admin-backend/functions/index.js`: Envíos FCM de comandos a los dispositivos.


