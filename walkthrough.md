# 🛠️ Walkthrough Técnico - LockSuite / Kosherlock MDM (v0.4.3)

---

## 📌 ¿Qué es este archivo?
El **`walkthrough.md`** es el documento técnico oficial generado por Antigravity (AI). Resume todo el historial de cambios, correcciones de errores, arquitectura y estado de verificación del proyecto.

---

## 🚀 Historial Reciente de Mejoras y Correcciones (Hasta v0.4.3)

### 1. Corrección del Servicio de Accesibilidad en Android 13+ (Qin / Qemay / AOSP)
- **Problema:** En Android 13 y marcas de celulares como Qemay/Qin, el sistema mostraba la accesibilidad como `"Enabled"` pero mantenía `Bound services: {}` (vacío, sin conectar el proceso).
- **Causa:** En Android 13 AOSP, `AccessibilityManagerService` rechaza enlazar el servicio si el archivo XML `accessibility_service_config.xml` no incluye explícitamente la etiqueta `android:description` o si los eventos no usan `typeAllMask`.
- **Solución Aplicada (`v0.4.1`):** Se añadió `android:description="@string/app_name"`, `typeAllMask` y `flagReportViewIds` en [accessibility_service_config.xml](file:///c:/Users/israe/OneDrive/Documentos/Lock%20Suite%20segunda%20version/app/src/main/res/xml/accessibility_service_config.xml). Con esto, el servicio se vincula instantáneamente (`Bound services`).

---

### 2. Dos Switches Independientes para Ofertas de Mercado Pago (`v0.4.2`)
Se separó el bloqueo de ofertas de Mercado Pago en 2 controles independientes en el Panel Web y en la app Android:
1. **`Bloquear Ofertas MP (Accesibilidad)`** (`BLOCK_MP_OFFERS_ACCESSIBILITY`): Bloquea únicamente el escaneo visual de nodos y vistas en la pantalla del celular.
2. **`Bloquear Ofertas MP (por VPN)`** (`BLOCK_MP_OFFERS_VPN`): Bloquea únicamente las solicitudes DNS de la red a los servidores de promociones y créditos de Mercado Pago.

---

### 3. Reparación Total de Conectividad a Internet en la VPN (`v0.4.3`)
Se solucionó la pérdida de internet provocada al encender la VPN en la versión `v0.4.0`:
1. **Eliminación del Lockdown Estricto (`lockdownEnabled = false`)**: En `PolicyManager.kt`, se cambió la configuración de `setAlwaysOnVpnPackage` a `lockdownEnabled = false`. Esto mantiene la VPN como permanente en el sistema pero permite que el tráfico nativo TCP (sitios web, WhatsApp, Mercado Pago) navegue sin caer en el túnel de captura DNS.
2. **Cumplimiento RFC 2460 (Checksum UDP en IPv6)**: En `NetworkForwarder.kt`, se implementó el cálculo e inyección del Checksum UDP obligatorio para IPv6, evitando que el Kernel de Android 13 descarte las respuestas DNS.
3. **Servidor DNS Upstream Dinámico**: En `NetworkForwarder.kt`, la función `getUpstreamDnsAddress()` detecta el servidor DNS real de la red activa (Wi-Fi o Móvil) en lugar de depender únicamente de `8.8.8.8`.
4. **Desautorización de la propia app (`addDisallowedApplication`)**: Se excluyó a `com.ejemplo.locksuite` / `com.ejemplo.kosherlock` de su propia interfaz TUN en `KosherVpnService.kt`.

---

## 📂 Archivos Clave del Sistema

- **Android App Core**:
  - `app/src/main/java/.../service/KosherVpnService.kt`: Servicio VPN DNS en Capa 3.
  - `app/src/main/java/.../service/LockSuiteAccessibilityService.kt`: Servicio de Accesibilidad e intercepción visual.
  - `app/src/main/java/.../mdm/PolicyManager.kt`: Gestor de políticas Device Owner de Android.
  - `app/src/main/java/.../util/NetworkForwarder.kt`: Motor de reenvío de paquetes UDP y checksums.

- **Panel Web & Cloud Functions**:
  - `admin-backend/public/index.html` & `app.js`: Interfaz de administración en tiempo real.
  - `admin-backend/public/version.json`: Registro de versiones para Auto-Update (OTA).
  - `admin-backend/functions/index.js`: Envíos FCM de comandos a los dispositivos.
