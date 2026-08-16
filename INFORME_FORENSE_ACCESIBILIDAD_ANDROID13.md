# 🔍 INFORME FORENSE: Diagnóstico y Solución de la Falla de Accesibilidad en Android 13 (LockSuite MDM)

> **Destinatario:** Claude (IA), Antigravity (IA) y Equipo de Desarrollo de LockSuite  
> **Fecha:** 15 de Agosto, 2026  
> **Dispositivo Evaluado:** QEMAY-QM01 (Android 13 / API 33)  
> **Estado del Problema:** 100% Diagnosticado y Resuelto en Código y Sistema Operativo

---

## 1. Resumen Ejecutivo del Problema

El usuario reportó que en su dispositivo con **Android 13** las funciones dependientes del Servicio de Accesibilidad (como el bloqueo de ofertas y créditos en Mercado Pago, el bloqueo de Estados/Canales en WhatsApp y la protección contra ingreso no autorizado a los Ajustes de Android) no estaban funcionando.

Al inspeccionar visualmente `Ajustes -> Accesibilidad`, la aplicación LockSuite ni siquiera figuraba en la lista de servicios del sistema operativo, a pesar de estar instalada como `Device Owner`.

---

## 2. Evidencia Forense Extraída del Dispositivo (ADB)

### A. Especificaciones del Hardware y Sistema Operativo
```text
Fabricante: alps
Modelo: QEMAY-QM01 (vnd_k65v1_64_bsp)
Plataforma / SoC: MediaTek MT6765
Versión de Android: 13 (API Level 33)
Compilación de ROM: A905-11B-QM01-EN-V2.0.0_04-06-2026
Estado Device Owner: ACTIVO (admin=com.ejemplo.locksuite/.receiver.DeviceAdminReceiver,DeviceOwner)
```

---

### B. Prueba Forense Crucial en el Package Manager de Android
Ejecutamos consultas directas al `PackageManager` del dispositivo para determinar qué servicios de accesibilidad reconocía el kernel de Android:

```bash
# 1. Consulta con la interfaz estándar de Android (AccessibilityService.SERVICE_INTERFACE):
adb shell "cmd package query-services -a android.accessibilityservice.AccessibilityService"
Resultado: No services found (0 servicios encontrados)

# 2. Consulta con la acción que estaba escrita en el Manifest de LockSuite:
adb shell "cmd package query-services -a android.view.accessibility.AccessibilityService"
Resultado: 1 services found (com.ejemplo.locksuite.service.LockSuiteAccessibilityService)
```

---

### C. Estado Interno del AccessibilityManager de Android (`dumpsys accessibility`)
```text
User state:
     installedServiceCount=0   <--- ¡CERO SERVICIOS INSTALADOS!
     Bound services:{}         <--- ¡NINGÚN SERVICIO VINCULADO / CORRIENDO!
     Binding services:{}
     Crashed services:{}
     Enabled services:{{com.ejemplo.locksuite/com.ejemplo.locksuite.service.LockSuiteAccessibilityService}}
```

---

### D. Estado de Permisos y Restricciones de Android 13 (`appops`)
```text
ACCESS_RESTRICTED_SETTINGS: deny; rejectTime=+13d ago
accessibility_enabled: 0
```

---

## 3. Análisis de Causa Raíz (¿Por qué no funcionaba?)

### 🔴 Causa Raíz Principal: Error de Acción en el `AndroidManifest.xml`
En el framework de Android (`AccessibilityManagerService.java`), cuando el sistema operativo escanea las aplicaciones instaladas para construir la lista de servicios de accesibilidad, busca exclusivamente componentes que respondan a la acción:
$$\text{Action} = \texttt{android.accessibilityservice.AccessibilityService}$$

En el archivo [`app/src/main/AndroidManifest.xml`](file:///c:/Users/israe/OneDrive/Documentos/Lock%20Suite%20segunda%20version/app/src/main/AndroidManifest.xml) (línea 97), la acción declarada era:
```xml
<!-- ❌ CÓDIGO ANTERIOR CON ERROR -->
<service
    android:name="com.ejemplo.locksuite.service.LockSuiteAccessibilityService"
    android:permission="android.permission.BIND_ACCESSIBILITY_SERVICE"
    android:exported="true">
    <intent-filter>
        <action android:name="android.view.accessibility.AccessibilityService" /> <!-- ❌ ERROR -->
    </intent-filter>
    <meta-data
        android:name="android.accessibilityservice"
        android:resource="@xml/accessibility_service_config" />
</service>
```

Al decir `android.view.accessibility.AccessibilityService` (que es el paquete Java de clases de vistas, no la acción de servicio del sistema), el `AccessibilityManagerService` de Android determinaba que **LockSuite no tenía ningún servicio de accesibilidad**. Por lo tanto:
1. `installedServiceCount` permanecía en `0`.
2. El sistema operativo **NUNCA iniciaba el proceso** ni llamaba a `onServiceConnected()`.
3. `Bound services: {}` permanecía vacío en todo momento.
4. Jamás se despachaba ningún evento `onAccessibilityEvent` a la app.

---

### 🟠 Causa Raíz Secundaria: Ajustes Restringidos de Android 13 (`ACCESS_RESTRICTED_SETTINGS`)
En Android 13 (API 33), Google introdujo la política de seguridad *"Restricted Settings"* para cualquier aplicación que no provenga de Google Play Store.
- Si una app es instalada por sideloading / APK directo, el sistema marca `ACCESS_RESTRICTED_SETTINGS = deny`.
- Esto provoca que en los Ajustes del sistema el usuario no pueda activar el interruptor de accesibilidad y que `accessibility_enabled` se fuerce a `0`.

---

## 4. Correcciones Aplicadas

### 1. Corrección en el Código Fuente (`AndroidManifest.xml`)
Se corrigió la acción del `intent-filter` en [`app/src/main/AndroidManifest.xml`](file:///c:/Users/israe/OneDrive/Documentos/Lock%20Suite%20segunda%20version/app/src/main/AndroidManifest.xml):
```xml
<!-- ✅ CÓDIGO CORREGIDO -->
<service
    android:name="com.ejemplo.locksuite.service.LockSuiteAccessibilityService"
    android:permission="android.permission.BIND_ACCESSIBILITY_SERVICE"
    android:exported="true">
    <intent-filter>
        <action android:name="android.accessibilityservice.AccessibilityService" />
    </intent-filter>
    <meta-data
        android:name="android.accessibilityservice"
        android:resource="@xml/accessibility_service_config" />
</service>
```

---

### 2. Desbloqueo a Nivel de Sistema Operativo (ADB / Device Owner)
Para garantizar la operación inmediata en Android 13:
```bash
# 1. Desbloquear los ajustes restringidos de Android 13
adb shell appops set com.ejemplo.locksuite ACCESS_RESTRICTED_SETTINGS allow

# 2. Registrar el componente exacto del servicio
adb shell settings put secure enabled_accessibility_services com.ejemplo.locksuite/com.ejemplo.locksuite.service.LockSuiteAccessibilityService

# 3. Encender el interruptor maestro de accesibilidad de Android
adb shell settings put secure accessibility_enabled 1

# 4. Habilitar estadísticas de uso para la detección de apps
adb shell appops set com.ejemplo.locksuite GET_USAGE_STATS allow
```

---

## 5. Validación y Pruebas Posteriores

Tras compilar la nueva versión con el `intent-filter` corregido:
1. `cmd package query-services -a android.accessibilityservice.AccessibilityService` ahora retorna con éxito el componente `com.ejemplo.locksuite.service.LockSuiteAccessibilityService`.
2. `AccessibilityManagerService` vincula el proceso del servicio (`Bound services: {com.ejemplo.locksuite/...}`).
3. Los eventos de UI de accesibilidad se reciben en `LockSuiteAccessibilityService.kt`, permitiendo que el bloqueo de Mercado Pago y el bloqueo de WhatsApp operen con normalidad.
