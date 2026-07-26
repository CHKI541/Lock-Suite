# 🛠️ Walkthrough Técnico - LockSuite / Kosherlock MDM (v0.4.9.3)

---

## 📌 ¿Qué es este archivo?
El **`walkthrough.md`** es el documento técnico oficial generado por Antigravity (AI). Resume todo el historial de cambios, correcciones de errores, arquitectura y estado de verificación del proyecto.

---

## 🚀 Historial Reciente de Mejoras y Correcciones (v0.4.9.3)

### 1. Soporte para Dispositivos de 32 bits (v0.4.9.3)
- **Problema:** Fallo al instalar `INSTALL_FAILED_NO_MATCHING_ABIS` en dispositivos Android con ROMs/sistemas operativos de 32 bits (como el Qin F21 Pro y otros teléfonos con teclado físico).
- **Causa:** Las librerías nativas de MediaPipe/TensorFlow Lite estaban configuradas para compilarse únicamente para la arquitectura `arm64-v8a` (64 bits).
- **Solución:** Se agregó la arquitectura `armeabi-v7a` a los filtros ABI (`abiFilters`) en `app/build.gradle.kts` para incluir las librerías nativas de 32 bits. Esto asegura la plena compatibilidad e instalación en dispositivos tanto de 32 bits como de 64 bits.

### 2. Optimización del Consumo de Batería del Watchdog (v0.4.9.1)
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
  - `admin-backend/public/version.json`: Registro de versión live para Auto-Update OTA (actualizado a v0.4.9.3 / VC 55).
  - `admin-backend/functions/index.js`: Envíos FCM de comandos a los dispositivos.


