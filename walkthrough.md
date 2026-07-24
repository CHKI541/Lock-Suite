# 🛠️ Walkthrough Técnico - LockSuite / Kosherlock MDM (v0.4.5)

---

## 📌 ¿Qué es este archivo?
El **`walkthrough.md`** es el documento técnico oficial generado por Antigravity (AI). Resume todo el historial de cambios, correcciones de errores, arquitectura y estado de verificación del proyecto.

---

## 🚀 Historial Reciente de Mejoras y Correcciones (Hasta v0.4.5)

### 1. Perfiles Locales (Presets) y Exportación/Importación Segura (v0.4.5)
- **Mejora:** Se implementó una nueva pestaña de **Presets (Perfiles)** en la aplicación de Android (`DashboardActivity.kt`).
- **Guardado Local:** Permite al administrador guardar la configuración de políticas actual con un nombre descriptivo.
- **Respaldos Criptográficos (`.locksuite`):** Permite exportar e importar las políticas del dispositivo a un archivo local. Para evitar que el usuario manipule las restricciones editando el JSON manualmente, el archivo se firma y valida mediante un código de autenticación **HMAC-SHA256** con una clave interna secreta en `PolicyManager.kt`.
- **UI Integrada:** Se añadió soporte en la interfaz para seleccionar archivos locales, mostrar errores de firma (si fue manipulado) y aplicar el backup instantáneamente.

### 2. Ocultar Íconos de Apps Suspendidas (v0.4.5)
- **Mejora:** Se agregó la opción de **"Ocultar icono al suspender aplicaciones"** (`setHideSuspendedApps`) en la sección de opciones avanzadas.
- **Funcionamiento:** Anteriormente, las aplicaciones restringidas aparecían con el ícono gris de suspendido. Al activar esta opción, `PolicyManager` usa la API de propietario de dispositivo `setApplicationHidden()` para hacer desaparecer por completo la aplicación del launcher de Android, simulando una desinstalación para el usuario pero preservando sus datos para cuando sea desbloqueada.

### 3. Autocompletado y Control Remoto Separado de Mercado Pago (v0.4.4 y v0.4.5)
- **Mejoras:** Se subió la versión oficial de APK autoinstalable (`v0.4.5` / `versionCode 35`) enlazada con el repositorio público en GitHub para actualizaciones directas vía OTA (`SelfUpdater.kt`).
- **Sincronización Firebase:** Despliegue automático de las reglas de base de datos, Hosting (con el archivo `version.json` apuntando a la versión 0.4.5) y Cloud Functions a la consola de Firebase (`locksuite-nueva`).

### 4. Reparación de la VPN y Redirección DNS (v0.4.3)
- **Mejoras de conectividad:** Se eliminó el Lockdown estricto (`lockdownEnabled = false`), se implementó la inyección correcta del Checksum UDP en IPv6 según el RFC 2460, y se desautorizó el tráfico de la propia app de MDM (`addDisallowedApplication`) en el túnel VPN.

---

## 📂 Archivos Clave del Sistema

- **Android App Core**:
  - `app/src/main/java/.../ui/dashboard/DashboardActivity.kt`: Interfaz del panel Android con la nueva sección de Presets y backups HMAC.
  - `app/src/main/java/.../mdm/PolicyManager.kt`: Controlador de las APIs de Device Owner, encriptación HMAC, exportación e importación.
  - `app/src/main/java/.../service/KosherVpnService.kt`: Servicio VPN DNS en Capa 3.
  - `app/src/main/java/.../service/LockSuiteAccessibilityService.kt`: Servicio de Accesibilidad e intercepción visual.

- **Panel Web & Cloud Functions (Desplegados en Firebase)**:
  - `admin-backend/public/index.html` & `app.js`: Interfaz de administración en tiempo real.
  - `admin-backend/public/version.json`: Registro de versión live para Auto-Update OTA (actualizado a v0.4.5 / VC 35).
  - `admin-backend/functions/index.js`: Envíos FCM de comandos a los dispositivos.

