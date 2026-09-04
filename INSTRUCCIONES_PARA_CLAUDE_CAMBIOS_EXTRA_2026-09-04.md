# Instrucciones y Resumen de Cambios Adicionales para Claude (4/9/2026)

**Para:** Claude  
**De:** Antigravity / Sesión de trabajo 4/9/2026  
**Contexto base:** Continuación directa de `INSTRUCCIONES_ANTIGRAVITY_2026-09-04_CUENTA_GOOGLE.md` y `LOCKSUITE_CONTEXTO_PARA_IA.md` (§ B.43 y B.44).

---

## 1. Estado del trabajo previo de Claude (B.43)

Todo lo que dejaste escrito para **B.43** (cierre del agujero de "Ajustes de la cuenta de Google" / Historial de YouTube):
- `mdm/GoogleAccountWebPolicy.kt`
- Bloqueo DNS en `KosherVpnService.kt` (`myaccount`, `myactivity`, `history`, `takeout`, `timeline`, `adssettings`, `photos.google.com`, `ytimg.com`)
- Rebote de Accesibilidad con exclusión explícita de `MinuteMaidActivity` para no romper el alta de cuentas
- Interruptor `block_google_account_web`, comandos FCM y sincronización al panel

**Se conservó al 100% sin modificaciones que alteren su lógica.**

---

## 2. Decisión del dueño sobre la "Lista Blanca Global"

En § 4.4 de tu auditoría propusiste invertir la postura general del MDM a una lista blanca restrictiva de WebViews (*default-deny*).
El dueño lo evaluó y **lo rechazó expresamente**: *"Lista blanca no quiero."*

Por ende, **se mantiene el paradigma arquitectónico de LockSuite**: bloqueos quirúrgicos, listas negras específicas y protecciones puntuales por capas, sin forzar un confinamiento global de WebViews que rompería el uso normal de aplicaciones autorizadas.

---

## 3. Detalle de los 4 Cambios Adicionales Implementados

A continuación se detallan los 4 puntos que el dueño pidió corregir o blindar a partir de tu auditoría, explicando para cada uno: **cuál era el problema**, **por qué ocurría** y **cómo se solucionó en código**.

---

### A. App de Google / Asistente / Lens por defecto en "Bloqueadas" (Punto 1 / G-7)

* **El Problema:**  
  La app de Google (`com.google.android.googlequicksearchbox`) no es un simple WebView: integra el feed **Discover** (noticias infinitas con imágenes), el **Asistente de voz** (tarjetas interactivas con resúmenes web) y **Google Lens** (búsqueda visual inversa por cámara). Si está instalada y habilitada, es el agujero más grande del teléfono.
* **Por qué ocurría:**  
  No estaba en la lista de navegadores conocidos (`KNOWN_BROWSER_PACKAGES`) ni venía suspendida por omisión al enrolar o reiniciar el dispositivo; dependía de que el administrador la suspendiera manualmente app por app.
* **Cómo se solucionó:**
  1. **En `AppController.kt`:** Se creó `DEFAULT_BLOCKED_PACKAGES = setOf("com.google.android.googlequicksearchbox", "com.google.android.apps.wallpaper")`.
  2. En `AppController.isAppSuspended()`, `AppController.reconcileEmergencySuspend()` y `PolicyManager.reapplyAllRestrictions()`, si la app no tiene una preferencia explícita (`suspend_<paquete>`), **su estado por defecto es suspendida (`true`)**. Aparece inmediatamente en la sección "Bloqueadas" del panel y del celular.
  3. **En `PolicyManager.kt` y `LockSuiteAccessibilityService.kt`:** Se agregó `"com.google.android.googlequicksearchbox"` a `KNOWN_BROWSER_PACKAGES`. Si el usuario intentara abrirla, Capa 3 la detecta como navegador y la expulsa en el acto.

---

### B. Licencias de código abierto y Términos legales en Ajustes (Punto D)

* **El Problema:**  
  En `Ajustes → Información del teléfono → Información legal → Licencias de código abierto` (o Términos de Google), Android lista bibliotecas con hipervínculos azules (`http://...`).
* **Por qué ocurría:**  
  Aunque no haya navegador normal en el equipo, ciertas subpantallas de licencias de AOSP y Google Play Services (`LicenseHtmlActivity`, `OpenSourceLicensesActivity`) pueden intentar invocar visores internos o Custom Tabs.
* **Cómo se solucionó:**
  1. **En `LockSuiteAccessibilityService.kt`:** Se agregó la función auxiliar estructural `isLegalOrLicenseScreen(cls: String)` que verifica si el nombre de clase contiene `"license"`, `"legalsettings"`, `"opensource"` o `"copyright"`.
  2. Ante eventos `TYPE_WINDOW_STATE_CHANGED` en paquetes de Ajustes o `com.google.android.gms`, si la clase coincide, el servicio ejecuta inmediatamente `performGlobalAction(GLOBAL_ACTION_BACK)` y corta el procesamiento.
  3. **Cero costo de CPU y no depende del idioma:** Se evalúa únicamente al cambiar de ventana y por nombre de clase de la Activity (regla B.19 punto 3).

---

### C. Selector de Fondos de Pantalla de Google y `DISALLOW_SET_WALLPAPER` (Punto E / G-4)

* **El Problema:**  
  El selector de fondos de Google (`com.google.android.apps.wallpaper`) descarga galerías completas de fotografías en línea (paisajes, arte, personas).
* **Por qué ocurría:**  
  La restricción `UserManager.DISALLOW_SET_WALLPAPER` ya estaba registrada en `PolicySpec.EXTRA_RESTRICTIONS` (desde el 2/9), pero venía **apagada por omisión**.
* **Cómo se solucionó:**
  1. **En `PolicyManager.isRestrictionEnabled()`:** Se agregó:
     ```kotlin
     if (restriction == UserManager.DISALLOW_SET_WALLPAPER && !prefs.contains(restriction)) {
         return true // Bloquear cambio de fondo por defecto para evitar catálogo de fotos online
     }
     ```
     Con esto, el sistema operativo impone `DISALLOW_SET_WALLPAPER` automáticamente desde el primer arranque a nivel kernel/DPM.
  2. **En `AppController.kt`:** Se sumó `"com.google.android.apps.wallpaper"` a `DEFAULT_BLOCKED_PACKAGES`, quedando suspendida por defecto en la sección Bloqueadas.

---

### D. Protección contra Fugas en Portal Cautivo (`com.android.captiveportallogin`) (Punto B / 1.B)

* **El Problema planteado por el dueño:**  
  El dueño no quería bloquear el portal cautivo (`com.android.captiveportallogin`) porque en hoteles, aeropuertos o cafeterías los usuarios quedarían completamente incomunicados sin poder autenticar el Wi-Fi. Sin embargo, existía el temor de que la página del portal tuviera botones o enlaces a redes sociales o videos.
* **Por qué ocurría:**  
  `CaptivePortalLoginActivity` es un WebView del sistema. Aunque no tiene barra de direcciones para escribir URLs, puede renderizar enlaces incrustados por la página web del router.
* **Cómo se solucionó:**
  1. **En `KosherVpnService.kt`:** Se intercepta el tráfico DNS cuando `logPackage == "com.android.captiveportallogin"`.
  2. Se creó el filtro `isCaptivePortalEscapeDomain(queriedDomain)`:
     * **Permite:** Las IPs locales del router (ej. `192.168.x.x`), los dominios de verificación de Android (`connectivitycheck.gstatic.com`, `clients3.google.com`) y el dominio de autenticación del portal.
     * **Bloquea:** Cualquier intento de fuga hacia entretenimiento, redes sociales o video (`youtube.com`, `googlevideo.com`, `ytimg.com`, `facebook.com`, `instagram.com`, `twitter.com`, `x.com`, `tiktok.com`, `netflix.com`, `spotify.com`, etc.).
  3. **Cierre automático de Android:** En cuanto el usuario ingresa las credenciales del Wi-Fi y la red obtiene salida a internet, el sistema operativo cierra automáticamente la Activity (`finish()`), impidiendo que el usuario permanezca navegando.

---

### E. Bloqueo del selector de fotos e ilustraciones de Contactos / Google Photos (Punto 5)

* **El Problema:**  
  Al editar un contacto en Contactos de Google (`com.google.android.contacts`) (o al editar la foto de perfil en apps de Google como Gmail o Cuenta de Google), al presionar sobre la imagen o avatar se abre el selector de fotos de Google (`Google Profile Photo Picker`). Este selector incluye pestañas para:
  1. **Google Fotos:** Permite buscar y navegar por toda la galería multimedia en la nube.
  2. **Ilustraciones de Google:** Un inmenso catálogo categorizado de ilustraciones, avatares, personajes y dibujos que no tiene justificación en un dispositivo kosher.
* **Por qué ocurría:**  
  No existía un control específico para este componente. No es posible deshabilitar la app de Contactos de Google completa (el usuario necesita gestionar sus llamadas y contactos), y el componente selector corre como una biblioteca interna (`com.google.android.libraries.user.profile.photopicker`) o subactividad del paquete de contactos.
* **Cómo se solucionó:**
  1. **En `PolicyManager.kt`:** Se implementó el interruptor `isContactPhotoPickerBlocked()` y `setContactPhotoPickerBlocked(enabled)`, **ENCENDIDO POR DEFECTO (`true`)** al igual que `block_google_account_web`. Se integró en `exportProfile` e `importProfile`.
  2. **En `LockSuiteAccessibilityService.kt` (Capa 3):**
     * En `onAccessibilityEvent` ante `TYPE_WINDOW_STATE_CHANGED`, se evalúa `isContactPhotoPickerScreen(packageName, cls)`.
     * Detecta clases y paquetes de:
       * `com.android.avatarpicker` / `com.google.android.avatarpicker`.
       * Biblioteca `user.profile.photopicker` (`PhotoPickerIntentActivity`).
       * Actividades de ilustraciones (`illustration`, `artpicker`, `artactivity`).
       * Subpantallas de selección o recorte de avatar dentro de paquetes de contactos (`contacts`, `people`) que no sean la edición del contacto en sí (`ContactEditorActivity`).
     * Al detectarse, se ejecuta inmediatamente `GLOBAL_ACTION_BACK` con un Toast explicativo (`"🚫 Selección de foto de contacto bloqueada por LockSuite"`). La ventana emergente se cierra en milisegundos y el usuario permanece en la pantalla de edición del contacto sin interrupciones.
  3. **Comandos FCM:** `"BLOCK_CONTACT_PHOTO_PICKER"` y `"UNBLOCK_CONTACT_PHOTO_PICKER"` agregados en `LockSuiteFirebaseService.kt` y en `admin-backend/functions/index.js` (`ALLOWED_COMMANDS`).
  4. **Panel Web y Sincronización:**
     * `FirebaseDeviceSync.kt` reporta `contactPhotoPickerBlocked`.
     * `index.html` y `app.js` exponen el switch tanto a nivel individual de dispositivo como en la configuración por lote de grupos.

---

## 4. Archivos modificados en esta entrega

| Archivo | Cambios realizados |
|---|---|
| `app/.../mdm/AppController.kt` | `DEFAULT_BLOCKED_PACKAGES` (Google App y Wallpapers). Adaptación de `isAppSuspended()` y `reconcileEmergencySuspend()`. |
| `app/.../mdm/PolicyManager.kt` | `UserManager.DISALLOW_SET_WALLPAPER` activo por omisión en `isRestrictionEnabled()`. Respeto de `DEFAULT_BLOCKED_PACKAGES` en `reapplyAllRestrictions()`. `"com.google.android.googlequicksearchbox"` en `KNOWN_BROWSER_PACKAGES`. Implementación de `is/setContactPhotoPickerBlocked()` (por defecto `true`), integrado a export/import de perfil. |
| `app/.../service/LockSuiteAccessibilityService.kt` | `"com.google.android.googlequicksearchbox"` en `KNOWN_BROWSER_PACKAGES`. Detección y rebote automático para pantallas de licencias/términos legales (`isLegalOrLicenseScreen`). Detección y rebote del selector de fotos/ilustraciones de contactos (`isContactPhotoPickerScreen` / `handleContactPhotoPickerBounce`). |
| `app/.../service/KosherVpnService.kt` | Filtro `isCaptivePortalEscapeDomain` para `com.android.captiveportallogin` en Capa 2. |
| `app/.../service/LockSuiteFirebaseService.kt` | Manejo de comandos FCM `BLOCK_CONTACT_PHOTO_PICKER` y `UNBLOCK_CONTACT_PHOTO_PICKER`. |
| `app/.../util/FirebaseDeviceSync.kt` | Sincronización de `contactPhotoPickerBlocked` hacia Firebase Realtime Database. |
| `app/.../ui/dashboard/DashboardActivity.kt` | Fila con interruptor interactivo para `block_contact_photo_picker` en la pestaña de Políticas. |
| `admin-backend/functions/index.js` | Inclusión de `BLOCK_CONTACT_PHOTO_PICKER` y `UNBLOCK_CONTACT_PHOTO_PICKER` en `ALLOWED_COMMANDS`. |
| `admin-backend/public/index.html` | Switch `contactPhotoPickerBlocked` en sidebar de dispositivo y en políticas de grupo. |
| `admin-backend/public/app.js` | Mapeo de comandos, lectura de switches y soporte por grupo para `contactPhotoPickerBlocked`. |
| `INSTRUCCIONES_PARA_CLAUDE_CAMBIOS_EXTRA_2026-09-04.md` | Este documento explicativo detallado. |
| `LOCKSUITE_CONTEXTO_PARA_IA.md` | Actualización de bitácora y estado del repositorio. |

