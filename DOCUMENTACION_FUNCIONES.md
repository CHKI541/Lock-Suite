# 📘 Manual Técnico Completo — LockSuite MDM

> **Versión cubierta:** 0.5.0  
> **Última actualización:** Julio 2026  
> **Autor:** Documentación técnica oficial del sistema LockSuite / KosherLock

---

## 📄 TABLA DE CONTENIDOS

1. [Visión General y Filosofía de Diseño](#1-visión-general-y-filosofía-de-diseño)
2. [Arquitectura de Software](#2-arquitectura-de-software)
   - 2.1. PolicyManager.kt — Motor Central de Políticas MDM
   - 2.2. AppController.kt — Gestor de Aplicaciones
   - 2.3. KosherVpnService.kt — Motor de Filtrado DNS
   - 2.4. LockSuiteAccessibilityService.kt — Inspección Visual
   - 2.5. LockSuiteFirebaseService.kt — Receptor de Comandos Remotos
   - 2.6. FirebaseDeviceSync.kt — Sincronización de Estado al Panel Web
   - 2.7. BootReceiver.kt — Arranque Resiliente
   - 2.8. SelfUpdater.kt — Actualizaciones OTA Silenciosas
   - 2.9. WebViewBlockManager.kt — Bloqueo de WebViews por App
   - 2.10. ImageBlockManager.kt — Filtrado Visual de Imágenes
   - 2.11. DomainRuleManager.kt — Reglas DNS Personalizadas
3. [Restricciones Nativas del Sistema Operativo (DPM)](#3-restricciones-nativas-del-sistema-operativo-dpm)
4. [Bloqueos de Hardware](#4-bloqueos-de-hardware)
5. [Módulo Kosher VPN y Filtrado DNS](#5-módulo-kosher-vpn-y-filtrado-dns)
   - 5.1. Arquitectura de la VPN Local
   - 5.2. Bloqueo Total de Internet por Aplicación (Per-App Internet Blocking)
   - 5.3. Bloqueo Global de Anuncios (Ad Blocking)
   - 5.4. Bloqueo de GIFs (Tenor / Giphy)
   - 5.5. Bloqueo de DNS Privado
   - 5.6. Always-On VPN con Lockdown
   - 5.7. Bloqueo de WebView por Aplicación (WebView Policy)
   - 5.8. Reglas DNS Personalizadas (Custom DNS Rules)
6. [Control de Aplicaciones](#6-control-de-aplicaciones)
   - 6.1. Suspensión de Aplicaciones
   - 6.2. Ocultamiento de Aplicaciones (Hide)
   - 6.3. Ocultar al Suspender (Hide on Suspend)
   - 6.4. Bloqueo Masivo de Navegadores Web
   - 6.5. Suspensión de Android System WebView
   - 6.6. Desinstalación Silenciosa
   - 6.7. Tienda de Apps Administrada
7. [Protecciones de Contenido Específicas por App](#7-protecciones-de-contenido-específicas-por-app)
   - 7.1. Bloqueo de Estado de WhatsApp
   - 7.2. Bloqueo de Canales de WhatsApp
   - 7.3. Bloqueo Dual de Ofertas Mercado Pago
   - 7.4. Filtrado Visual de Imágenes por App (Silueta / AI Gate)
   - 7.5. Filtrado de Imágenes en Google Maps
8. [Sistema FRP — Factory Reset Protection](#8-sistema-frp--factory-reset-protection)
   - 8.1. ¿Qué es el FRP y para qué sirve en LockSuite?
   - 8.2. Método Oficial (Android 11+)
   - 8.3. Método Legacy (Android 8–10)
   - 8.4. FRP Hardening — Endurecimiento Adicional
   - 8.5. Cuentas Autorizadas FRP por Defecto
   - 8.6. Relación entre FRP y la restricción DISALLOW_MODIFY_ACCOUNTS
9. [Sistema de Presets y Respaldo HMAC](#9-sistema-de-presets-y-respaldo-hmac)
   - 9.1. ¿Qué es un Preset?
   - 9.2. Estructura Interna del Archivo .locksuite
   - 9.3. Firma Criptográfica HMAC-SHA256
   - 9.4. Importar y Exportar Presets
   - 9.5. Presets Guardados Localmente
10. [Modo Stealth y Lanzador Oculto](#10-modo-stealth-y-lanzador-oculto)
11. [Persistencia y Restauración de Políticas al Reiniciar](#11-persistencia-y-restauración-de-políticas-al-reiniciar)
12. [Panel Web de Administración](#12-panel-web-de-administración)
    - 12.1. Arquitectura del Panel Web
    - 12.2. Flujo de Comunicación FCM Bidireccional
    - 12.3. Sincronización de Estado del Dispositivo (FirebaseDeviceSync)
    - 12.4. Pantallas y Pestañas del Panel Web
13. [Sistema de Actualización OTA](#13-sistema-de-actualización-ota)
14. [Instalación y Aprovisionamiento MDM](#14-instalación-y-aprovisionamiento-mdm)
    - 14.1. Requisitos Previos
    - 14.2. Aprovisionamiento por ADB
    - 14.3. Aprovisionamiento por QR (Zero-Touch)
15. [Matriz de Diagnóstico y Troubleshooting](#15-matriz-de-diagnóstico-y-troubleshooting)

---

## 1. VISIÓN GENERAL Y FILOSOFÍA DE DISEÑO

**LockSuite MDM** (también conocido como **KosherLock**) es un sistema integral de **Gestión de Dispositivos Móviles (MDM — Mobile Device Management)** diseñado para Android, con un enfoque en la protección de contenido y la administración centralizada de dispositivos bajo principios de privacidad y filtrado de contenido.

### 1.1. ¿Qué problema resuelve LockSuite?

Las soluciones de control parental convencionales (como Google Family Link o aplicaciones de terceros) son fácilmente eludibles por un usuario técnico: pueden desinstalarse, sus permisos pueden revocarse, y en el peor caso, un simple formateo de fábrica elimina toda restricción. **LockSuite fue diseñado para que esto sea imposible.**

LockSuite se instala en el nivel de mayor privilegio que Android concede a una aplicación: el nivel **Device Owner** (Propietario del Dispositivo). Bajo este nivel:

- El usuario final **no puede desinstalar LockSuite** bajo ninguna circunstancia.
- **No puede revocar sus permisos** desde los Ajustes.
- **No puede eludir las restricciones** reiniciando en Modo Seguro.
- **No puede formatear el teléfono** sin que LockSuite lo permita.
- Las restricciones **sobreviven a reinicios** y se reaplican automáticamente.

### 1.2. Las Tres Capas de Protección

LockSuite opera mediante tres capas de defensa independientes que trabajan en simultáneo:

```
CAPA 1 — SISTEMA OPERATIVO (DevicePolicyManager)
  └── Restricciones nativas de Android: bloqueo de fábrica, ADB,
      instalación de apps, Bluetooth, Wi-Fi, cámara, etc.

CAPA 2 — RED (KosherVpnService)
  └── VPN local 100% en el dispositivo. Intercepta solicitudes DNS
      y bloquea dominios, IPs de apps específicas, anuncios, GIFs,
      ofertas de Mercado Pago, etc. Cero latencia, sin batería extra.

CAPA 3 — VISUAL (LockSuiteAccessibilityService)
  └── Monitorea la interfaz gráfica en tiempo real. Si el usuario
      navega a una sección prohibida (ej. "Ofertas" en Mercado Pago),
      ejecuta un rebote instantáneo antes de que el usuario la vea.
```

Gracias a esta triple capa, eludir LockSuite requeriría sortear simultáneamente tres sistemas completamente diferentes e independientes, lo que resulta prácticamente imposible para un usuario no técnico.

### 1.3. Público Objetivo

LockSuite fue diseñado para administradores comunitarios (mashgichim, rabinos, coordinadores de instituciones) que necesitan distribuir teléfonos con restricciones de contenido definitivas a usuarios que no deben poder modificar esas restricciones. También puede usarse en contextos empresariales como MDM corporativo.

---

## 2. ARQUITECTURA DE SOFTWARE

El proyecto Android se organiza bajo el paquete base `com.ejemplo.locksuite`. A continuación se describen en detalle todos los componentes de software del sistema.

```
                ┌─────────────────────────────────────┐
                │    Panel Web de Administración       │
                │  (Firebase Hosting + Cloud Run)      │
                └─────────────┬───────────────────────┘
                              │ Comandos FCM cifrados
                              ▼
                ┌─────────────────────────────────────┐
                │     LockSuiteFirebaseService         │
                │  (Receptor FCM + Emisor de ACK)      │
                └──────┬──────────┬───────────┬────────┘
                       │          │           │
              ┌────────┘   ┌──────┘    ┌──────┘
              ▼            ▼           ▼
        PolicyManager  AppController  KosherVpnService
        (Restricciones  (Apps:        (Filtrado DNS
         DPM/UserMgr)   suspend,       por red)
                        hide, etc.)
              │
              ▼
        FirebaseDeviceSync
        (Sincroniza estado
         al panel web)
```

### 2.1. `PolicyManager.kt` — Motor Central de Políticas MDM

**Ruta:** `com.ejemplo.locksuite.mdm.PolicyManager`

`PolicyManager` es el **singleton central** que encapsula y centraliza absolutamente todas las llamadas a la API `DevicePolicyManager` (DPM) y `UserManager` de Android. Es el componente más importante del sistema.

Sus responsabilidades son:

1. **Aplicar y remover restricciones nativas del SO** mediante `dpm.addUserRestriction()` y `dpm.clearUserRestriction()`.
2. **Deshabilitar hardware del dispositivo** (cámara, barra de estado, capturas de pantalla, llave de bloqueo).
3. **Gestionar el bloqueo total de Internet** mediante un proxy local.
4. **Gestionar bloqueos de contenido** (VPN, bloqueo de WebViews, Mercado Pago, WhatsApp, GIFs, anuncios).
5. **Controlar suspensión y ocultamiento de navegadores** y del motor WebView.
6. **Implementar el sistema FRP** (Factory Reset Protection) con doble fallback.
7. **Exportar e importar Presets** firmados con HMAC-SHA256.
8. **Persistir el estado** de todas las políticas en `SharedPreferences` encriptado para garantizar su restauración tras un reinicio.
9. **Replicar el estado** completo del dispositivo llamando a `FirebaseDeviceSync.syncDeviceInfo()` tras cada cambio.

#### Método `setRestriction(restriction, enable)` — El corazón del sistema

```kotlin
private fun setRestriction(restriction: String, enable: Boolean): Boolean {
    return try {
        if (enable) {
            dpm.addUserRestriction(adminComponent, restriction)
        } else {
            dpm.clearUserRestriction(adminComponent, restriction)
        }
        saveState(restriction, enable) // Persiste en SharedPreferences
        true
    } catch (e: Exception) { false }
}
```

Este método privado centraliza toda la lógica de activación/desactivación de restricciones nativas de Android. Guarda además el estado en disco para sobrevivir a reinicios del dispositivo.

#### Método `reapplyAllRestrictions()` — Restauración al arrancar

Este método es invocado por `BootReceiver` cada vez que el teléfono reinicia. Recorre la lista completa de restricciones guardadas en `SharedPreferences` y las vuelve a aplicar una por una, garantizando que el dispositivo esté protegido desde el primer segundo tras el encendido, antes incluso de que el usuario ingrese su PIN.

#### Método `clearAllRestrictions()` — Purga completa

Invocado únicamente cuando un administrador quiere liberar completamente el dispositivo. Elimina todas las restricciones, detiene la VPN, habilita la Play Store, reactiva los navegadores y limpia todas las preferencias guardadas.

---

### 2.2. `AppController.kt` — Gestor de Aplicaciones

**Ruta:** `com.ejemplo.locksuite.mdm.AppController`

`AppController` traduce los comandos de control de aplicaciones individuales a las APIs nativas de Android. Mantiene una lista interna de paquetes **críticos del sistema** que nunca pueden ser suspendidos ni ocultados (hacerlo rompería el dispositivo):

**Paquetes protegidos (no suspendibles/ocultables):**
- `com.android.systemui` (interfaz del sistema)
- `com.android.settings` (ajustes)
- `com.android.phone` (teléfono)
- `com.google.android.gms` (Google Play Services)
- `com.ejemplo.locksuite` (la propia app)
- El launcher activo del dispositivo

También existe una categoría intermedia (`partialBlockOnly`): apps como **Gboard** que no pueden suspenderse/ocultarse, pero sí pueden tener restricciones de contenido como bloqueo de imágenes o WebView.

#### Método principal `getUserApps()` — Inventario de Apps

Devuelve un listado completo de todas las apps instaladas en el dispositivo, enriquecido con su estado actual en LockSuite:

```kotlin
AppInfoData(
    packageName,   // Nombre de paquete
    label,         // Nombre visible
    icon,          // Ícono en bitmap
    isHidden,      // ¿Está oculta por DPM?
    isSuspended,   // ¿Está suspendida?
    appType,       // "Usuario", "Preinstalada" o "Sistema"
    isWebViewBlocked,    // ¿WebView bloqueado?
    isInternetBlocked,   // ¿Sin acceso a Internet?
    isCritical,          // ¿Es sistema crítico?
    imageBlockingMode    // "none", "layer1", "layer2", "both"
)
```

---

### 2.3. `KosherVpnService.kt` — Motor de Filtrado DNS

**Ruta:** `com.ejemplo.locksuite.service.KosherVpnService`

Extiende `android.net.VpnService`. Crea un túnel VPN **100% local** en el dispositivo. El tráfico de datos nunca sale hacia servidores externos — todo el filtrado ocurre dentro del propio teléfono.

**Principio de funcionamiento:**
1. Establece una interfaz virtual `TUN` con IP `10.1.10.1`.
2. Captura únicamente los paquetes UDP/TCP dirigidos al puerto 53 (DNS).
3. Lee los primeros bytes del encabezado DNS para extraer el campo `QNAME` (el dominio consultado).
4. Si el dominio está en la lista negra, responde inmediatamente con `0.0.0.0`, cancelando la conexión.
5. Si el dominio no está bloqueado, deja pasar la consulta hacia el servidor DNS real del dispositivo.

**¿Por qué no consume batería?**  
Porque la VPN ignora completamente el tráfico pesado (videos, imágenes, HTTPS). Solo inspecciona paquetes DNS de unos pocos bytes. El motor de resolución de dominios usa un `HashSet<String>` en RAM con complejidad de búsqueda `O(1)`.

---

### 2.4. `LockSuiteAccessibilityService.kt` — Inspección Visual

**Ruta:** `com.ejemplo.locksuite.service.LockSuiteAccessibilityService`

Servicio de accesibilidad que monitorea constantemente los eventos de cambio de pantalla del sistema (`AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED`, `TYPE_WINDOW_CONTENT_CHANGED`).

**Cómo funciona:**
1. Detecta qué aplicación está visible en primer plano (`event.packageName`).
2. Recorre recursivamente el árbol de nodos de interfaz (`AccessibilityNodeInfo`).
3. Busca coincidencias de texto, IDs de vista o descripciones de contenido.
4. Si detecta una sección prohibida, ejecuta `performGlobalAction(GLOBAL_ACTION_BACK)` para rebotar la pantalla instantáneamente.

**Casos de uso activos:**
- Detectar y bloquear la pestaña "Ofertas" en Mercado Pago.
- Detectar y bloquear la sección "Estados" en WhatsApp.
- Detectar y bloquear la sección "Canales" en WhatsApp.

---

### 2.5. `LockSuiteFirebaseService.kt` — Receptor de Comandos Remotos

**Ruta:** `com.ejemplo.locksuite.service.LockSuiteFirebaseService`

Extiende `FirebaseMessagingService`. Es la "antena" del sistema: recibe los comandos enviados desde el panel web de administración.

**Ciclo completo de un comando remoto:**

```
1. Admin presiona un switch en el Panel Web
       ↓
2. app.js llama a Cloud Function sendCommandV8
       ↓
3. FCM entrega el payload al dispositivo (< 1 segundo)
       ↓
4. LockSuiteFirebaseService.onMessageReceived()
       ↓
5. Dispatch interno: PolicyManager / AppController / KosherVpnService
       ↓
6. Política aplicada con éxito
       ↓
7. ACK escrito en commandAcks/{deviceId}/{commandId}
       ↓
8. Panel Web escucha el ACK → muestra "✅ Aplicado"
```

---

### 2.6. `FirebaseDeviceSync.kt` — Sincronización de Estado

**Ruta:** `com.ejemplo.locksuite.util.FirebaseDeviceSync`

Este componente es responsable de mantener el panel web sincronizado con el estado real del dispositivo. Se invoca automáticamente después de cualquier cambio de política.

**Datos sincronizados al panel web:**
- Información del dispositivo: modelo, fabricante, versión Android.
- Versión actual de LockSuite instalada (`versionCode`, `versionName`).
- Estado de todas las restricciones DPM (25+ campos booleanos).
- Estado de hardware: cámara, captura de pantalla, barra de estado.
- Estado de VPN, bloqueo de internet, ad blocking, GIFs.
- Estado de bloqueos específicos: WhatsApp, Mercado Pago, modo Stealth.
- Lista completa de apps instaladas con su estado individual.
- Token FCM para recibir futuros comandos remotos.
- Timestamp `lastSeen` para detectar si el dispositivo está en línea.

**Autenticación:** Utiliza **Firebase Authentication anónima**. El primer acceso crea una sesión anónima que se reutiliza en las llamadas subsiguientes para no generar overhead.

---

### 2.7. `BootReceiver.kt` — Arranque Resiliente

**Ruta:** `com.ejemplo.locksuite.receiver.BootReceiver`

Escucha los intents `ACTION_BOOT_COMPLETED` y `ACTION_LOCKED_BOOT_COMPLETED`. En cuanto el hardware del teléfono enciende:

1. Invoca `PolicyManager.reapplyAllRestrictions()` para restaurar todas las políticas.
2. Inicia `KosherVpnService` en primer plano si hay políticas de VPN activas.
3. Garantiza que el estado de apps suspendidas se mantenga inalterado.

Esto significa que aunque el usuario fuerce un reinicio del teléfono, **LockSuite estará activo antes de que el usuario pueda interactuar con el dispositivo**.

---

### 2.8. `SelfUpdater.kt` — Actualizaciones OTA Silenciosas

**Ruta:** `com.ejemplo.locksuite.util.SelfUpdater`

Permite que la aplicación LockSuite se actualice a sí misma sin intervención del usuario, sin mostrar ningún diálogo de confirmación ni requerir permisos adicionales.

**Proceso de actualización:**

1. Consulta el archivo `version.json` en GitHub Raw o en Firebase Hosting.
2. Compara el `versionCode` del servidor con el `versionCode` instalado.
3. Si hay una versión más nueva, descarga la APK a un archivo temporal en el caché del sistema.
4. Utiliza la API `PackageInstaller` del Device Owner para instalar el APK silenciosamente.
5. La instalación se completa sin diálogos ni solicitudes al usuario.

**URLs de consulta:**
- Principal: `https://raw.githubusercontent.com/CHKI541/Lock-Suite/main/admin-backend/public/version.json`
- Fallback: `https://locksuite-nueva.web.app/version.json`

---

### 2.9. `WebViewBlockManager.kt` — Bloqueo de WebViews

**Ruta:** `com.ejemplo.locksuite.mdm.WebViewBlockManager`

Objeto singleton que gestiona la lista de aplicaciones cuyos **WebViews internos** están bloqueados. Cuando una app tiene el bloqueo de WebView activo, el motor DNS de `KosherVpnService` aplica la política definida en `WebViewPolicy.kt` para interceptar los dominios que esa app intenta cargar dentro de sus vistas web integradas.

**Operaciones:**
- `setBlocked(context, packageName, blocked)` — Activa o desactiva el bloqueo para una app.
- `isBlocked(context, packageName)` — Consulta si una app tiene el bloqueo activo.
- `getBlockedPackages(context)` — Devuelve el conjunto completo de apps bloqueadas.
- Utiliza un caché en memoria (`@Volatile`) para minimizar accesos a disco.

---

### 2.10. `ImageBlockManager.kt` — Filtrado Visual de Imágenes

**Ruta:** `com.ejemplo.locksuite.mdm.ImageBlockManager`

Gestiona el sistema de filtrado visual de imágenes. Soporta hasta tres modos por aplicación:

| Modo | Descripción |
|------|-------------|
| `none` | Sin filtrado de imágenes |
| `layer1` | Capa 1 (Silueta): Las imágenes se degradan visualmente a contornos planos |
| `layer2` | Capa 2 (AI Gate): Se superpone una capa de atenuación sobre las imágenes |
| `both` | Ambas capas simultáneamente para máxima protección |

Adicionalmente gestiona:
- **Modo AI Global** (`isGlobalAiEnabled`): Activa la Capa 2 en todo el sistema.
- **Bloqueo de imágenes en Google Maps** (`isMapsImageBlockingEnabled`): Evita la visualización de fotos de Street View y reseñas.

---

### 2.11. `DomainRuleManager.kt` — Reglas DNS Personalizadas

**Ruta:** `com.ejemplo.locksuite.dns.DomainRuleManager`

Permite al administrador definir reglas DNS personalizadas más allá de las listas preconfiguradas. Soporta dos tipos de reglas:

- `RuleType.BLOCK` — El dominio siempre recibe respuesta `0.0.0.0`.
- `RuleType.ALLOW` — El dominio siempre está permitido (actúa como lista blanca que anula bloqueos globales).

Las reglas se almacenan en `SharedPreferences` y se cargan en un **Trie** (`DomainRuleTrie.kt`) para búsqueda eficiente por sufijo de dominio (permite reglas del tipo `*.ejemplo.com`).

**Recarga atómica:** Cada vez que se modifica una regla, el Trie completo se reconstruye de forma atómica desde cero para garantizar consistencia.

---

## 3. RESTRICCIONES NATIVAS DEL SISTEMA OPERATIVO (DPM)

Estas restricciones son aplicadas directamente por la API `DevicePolicyManager` de Android. Una vez activadas, el sistema operativo las hace cumplir a nivel de kernel — ninguna app puede eludirlas.

| # | Función en PolicyManager | Restricción Nativa Android | Efecto en el Dispositivo |
|---|---|---|---|
| 1 | `setFactoryResetBlocked(true)` | `DISALLOW_FACTORY_RESET` | Desaparece la opción "Restablecer datos de fábrica" en Ajustes. El menú Recovery también queda bloqueado. |
| 2 | `setInstallAppsBlocked(true)` | `DISALLOW_INSTALL_APPS` (nativo) o control programático | Bloquea el instalador de paquetes. Cualquier intento de instalar un APK muestra "No permitido por el administrador". Si hay apps en la lista de permitidas, se activa en modo programático para filtrar por código sin bloquear a nivel de OS. |
| 3 | `setUninstallAppsBlocked(true)` | `DISALLOW_UNINSTALL_APPS` | El botón "Desinstalar" aparece en gris e inactivo en todas las apps del sistema. |
| 4 | `setDebuggingFeaturesBlocked(true)` | `DISALLOW_DEBUGGING_FEATURES` | Deshabilita las Opciones de Desarrollador y ADB. Los comandos ADB son rechazados aunque el cable esté conectado. |
| 5 | `setSafeBootBlocked(true)` | `DISALLOW_SAFE_BOOT` | Evita que el usuario inicie el teléfono en Modo Seguro (que solo carga apps del sistema, eludiendo todas las restricciones). |
| 6 | `setUserSwitchBlocked(true)` | `DISALLOW_USER_SWITCH` | Deshabilita el cambio de usuario y la creación de perfiles de invitado o de trabajo. |
| 7 | `setModifyAccountsBlocked(true)` | `DISALLOW_MODIFY_ACCOUNTS` | Prohíbe agregar, eliminar o sincronizar cuentas de Google, email u otros servicios desde Ajustes. **Nota:** Esta restricción no se aplica automáticamente al activar FRP (desde v0.4.6). |
| 8 | `setUnknownSourcesBlocked(true)` | `DISALLOW_INSTALL_UNKNOWN_SOURCES` | Impide activar "Instalar apps de fuentes desconocidas" en Ajustes, bloqueando APKs de navegadores o gestores de archivos. |
| 9 | `setWifiConfigBlocked(true)` | `DISALLOW_CONFIG_WIFI` + `DISALLOW_NETWORK_RESET` + `no_config_mobile_networks` | Triple bloqueo: no se puede agregar/editar redes Wi-Fi, no se puede resetear la configuración de red, y no se pueden modificar los ajustes de red móvil (APN, datos en roaming). |
| 10 | `setBluetoothBlocked(true)` | `DISALLOW_BLUETOOTH` | Apaga el chip Bluetooth y bloquea su encendido desde ajustes rápidos o Ajustes. |
| 11 | `setBluetoothSharingBlocked(true)` | `DISALLOW_BLUETOOTH_SHARING` | Deshabilita el protocolo OPP de Bluetooth. No se pueden enviar archivos, fotos o documentos por Bluetooth. |
| 12 | `setExternalMediaBlocked(true)` | `DISALLOW_MOUNT_PHYSICAL_MEDIA` | Bloquea el montaje de memorias USB (OTG) y tarjetas MicroSD externas. |
| 13 | `setTetheringBlocked(true)` | `DISALLOW_CONFIG_TETHERING` | Deshabilita la Zona Wi-Fi portátil, el tethering USB y el tethering Bluetooth. |
| 14 | `setAdjustVolumeBlocked(true)` | `DISALLOW_ADJUST_VOLUME` | Los botones físicos de volumen no tienen efecto. El nivel de volumen queda fijo. |
| 15 | `setAppsControlBlocked(true)` | `DISALLOW_APPS_CONTROL` | El usuario no puede ir a Ajustes > Apps y modificar permisos, borrar datos ni forzar el cierre de apps. |
| 16 | `setVpnConfigBlocked(true)` | `DISALLOW_CONFIG_VPN` | Prohíbe al usuario instalar, modificar o deshabilitar perfiles de VPN externos. Combina esto con Always-On para que solo la VPN de LockSuite pueda correr. |

---

## 4. BLOQUEOS DE HARDWARE

Estos bloqueos se aplican mediante APIs específicas del DPM, no mediante `UserManager` como las restricciones anteriores.

### 4.1. Bloqueo de Cámara

```kotlin
fun setCameraDisabled(disabled: Boolean)
```

Invoca `dpm.setCameraDisabled(adminComponent, disabled)`. Desactiva físicamente el acceso a la cámara trasera y frontal para todas las aplicaciones. Las apps de cámara abren pero muestran una pantalla negra o un error de permiso.

### 4.2. Bloqueo de Capturas de Pantalla

```kotlin
fun setScreenCaptureBlocked(block: Boolean)
```

Invoca `dpm.setScreenCaptureDisabled(adminComponent, block)`. Impide que el usuario tome capturas de pantalla con botones físicos o con apps de grabación. También agrega automáticamente el flag `FLAG_SECURE` a la ventana del Dashboard, protegiendo la propia interfaz de administración de LockSuite de ser fotografiada.

### 4.3. Bloqueo de Barra de Estado

```kotlin
fun setStatusBarDisabled(disabled: Boolean)
```

Invoca `dpm.setStatusBarDisabled(adminComponent, disabled)`. Cuando está activo, el usuario no puede deslizar hacia abajo para abrir el panel de notificaciones ni el panel de ajustes rápidos (Wi-Fi, Bluetooth, Modo Avión, etc.).

### 4.4. Bloqueo de Pantalla de Bloqueo (Keyguard)

```kotlin
fun setKeyguardDisabled(disabled: Boolean)
```

Invoca `dpm.setKeyguardDisabled(adminComponent, disabled)`. Cuando está activo, el teléfono no requiere PIN ni patrón para desbloquearse — la pantalla se enciende directamente al home. Útil en contextos de dispositivos compartidos de exhibición.

### 4.5. Bloqueo Total de Internet (Proxy Global)

```kotlin
fun setInternetBlocked(block: Boolean)
```

Técnica: en lugar de cortar las conexiones directamente (lo que requeriría permisos de root), LockSuite configura un **proxy HTTP recomendado** apuntando a una dirección inexistente (`127.0.0.1:9999`) mediante `dpm.setRecommendedGlobalProxy()`. Esto hace que todas las conexiones de red fallen silenciosamente. La VPN de LockSuite permanece funcional porque no pasa por el proxy del sistema.

---

*[Parte 1 de 3 — Secciones 1 a 4 completadas]*
