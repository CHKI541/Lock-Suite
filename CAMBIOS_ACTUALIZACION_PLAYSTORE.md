# Registro Detallado de Cambios: Automatización y Bloqueo de Actualización de Apps

Este documento detalla todos los archivos modificados, las funciones agregadas y la lógica implementada para la actualización remota de aplicaciones vía Google Play Store con pantalla de bloqueo y control de acceso.

---

## 1. Archivos Modificados

1. [`app/src/main/java/com/ejemplo/locksuite/service/BlockOverlayManager.kt`](file:///c:/Users/israe/OneDrive/Documentos/Lock%20Suite%20segunda%20version/app/src/main/java/com/ejemplo/locksuite/service/BlockOverlayManager.kt)
2. [`app/src/main/java/com/ejemplo/locksuite/mdm/PolicyManager.kt`](file:///c:/Users/israe/OneDrive/Documentos/Lock%20Suite%20segunda%20version/app/src/main/java/com/ejemplo/locksuite/mdm/PolicyManager.kt)
3. [`app/src/main/java/com/ejemplo/locksuite/service/LockSuiteFirebaseService.kt`](file:///c:/Users/israe/OneDrive/Documentos/Lock%20Suite%20segunda%20version/app/src/main/java/com/ejemplo/locksuite/service/LockSuiteFirebaseService.kt)
4. [`app/src/main/java/com/ejemplo/locksuite/receiver/PackageReceiver.kt`](file:///c:/Users/israe/OneDrive/Documentos/Lock%20Suite%20segunda%20version/app/src/main/java/com/ejemplo/locksuite/receiver/PackageReceiver.kt)
5. [`app/src/main/java/com/ejemplo/locksuite/service/LockSuiteAccessibilityService.kt`](file:///c:/Users/israe/OneDrive/Documentos/Lock%20Suite%20segunda%20version/app/src/main/java/com/ejemplo/locksuite/service/LockSuiteAccessibilityService.kt)

---

## 2. Detalle de Cambios por Archivo

### A. `BlockOverlayManager.kt`
- **Métodos agregados:**
  - `showBlockingMessageOverlay(message: String)`:
    - Crea una vista a pantalla completa con fondo negro sólido (`#090A0F`), texto de estado y barra de progreso indeterminada.
    - **Captura el 100% de los toques:** `isClickable = true`, `isFocusable = true`, `setOnTouchListener { _, _ -> true }`.
    - **Flags de ventana:** `TYPE_ACCESSIBILITY_OVERLAY`, `PixelFormat.OPAQUE`, `FLAG_NOT_FOCUSABLE`, `FLAG_LAYOUT_IN_SCREEN`, `FLAG_LAYOUT_NO_LIMITS` (cubre también barra de estado y gestos de navegación).
  - `updateBlockingMessageSubtitle(subtitle: String)`: Permite actualizar el texto secundario (ej: *"Descargando e instalando..."*).
  - `hideBlockingMessageOverlay()`: Remueve y destruye la vista de bloqueo.

---

### B. `PolicyManager.kt`
- **Método agregado:**
  - `isPlayStoreSuspended(): Boolean`: Consulta `suspend_com.android.vending` o `install_apps_blocked_admin` en SharedPreferences.
- **Método modificado (`restoreInstallRestrictions()`):**
  - Restaura automáticamente el estado de ocultamiento (`dpm.setApplicationHidden`) y suspensión (`dpm.setPackagesSuspended`) de `com.android.vending` según las preferencias guardadas (`hide_com.android.vending` y `suspend_com.android.vending`).
  - Limpia la clave `mdm_install_in_progress = false`.

---

### C. `LockSuiteFirebaseService.kt`
- **Manejador del comando `UPDATE_APP`:**
  - Des-oculta (`setApplicationHidden(..., false)`) y des-suspende (`setPackagesSuspended(..., false)`) la Play Store (`com.android.vending`) temporalmente con bloques `try-catch` independientes.
  - Guarda en SharedPreferences:
    - `updating_package = packageName`
    - `mdm_install_in_progress = true`
  - Remueve temporalmente las restricciones de usuario de instalación (`DISALLOW_INSTALL_APPS` y `DISALLOW_INSTALL_UNKNOWN_SOURCES`).
  - Inicia el Intent de Play Store (`market://details?id=$packageName`) con fallback al navegador web si no hubiera Play Store.
  - Programa un temporizador de seguridad watchdog de 10 minutos (`PackageReceiver` con acción `UPDATE_TIMEOUT`).
  - Registra el motivo de fallo (`reason`) en los `commandAcks` de Firebase si ocurre una excepción.

---

### D. `PackageReceiver.kt`
- **Manejador de `UPDATE_TIMEOUT`:**
  - Llama a `policyManager.restoreInstallRestrictions()`.
  - Limpia `updating_package` y `mdm_install_in_progress = false`.
- **Manejador de `ACTION_PACKAGE_REPLACED` / `ACTION_PACKAGE_ADDED`:**
  - Si el paquete instalado coincide con `updating_package`:
    - Llama a `policyManager.restoreInstallRestrictions()`.
    - Limpia `updating_package` y `mdm_install_in_progress = false`.
    - Cancela la alarma watchdog de timeout.
    - Oculta el overlay de accesibilidad y regresa al Home (`GLOBAL_ACTION_HOME`).

---

### E. `LockSuiteAccessibilityService.kt`
- **Control de instancia global y visibilidad:**
  - Agregado en `companion object`: `@Volatile var instance: LockSuiteAccessibilityService? = null`.
  - `lateinit var overlayManager: BlockOverlayManager` pública para acceso sincronizado.
  - Asignación de `instance = this` en `onServiceConnected()` e `instance = null` en `onDestroy()`.
- **Lógica de interceptación en `onAccessibilityEvent()`:**
  - **Durante actualización (`isUpdateInProgress == true`):**
    - Si el evento es de `com.android.vending`: Llama a `handlePlayStoreAutoUpdate(ev, updatingPkg)` y corta el flujo (`return`).
    - Si el evento es de cualquier otra app (excepto LockSuite, GMS, PackageInstaller, SystemUI): Re-lanza inmediatamente el Intent de Play Store para devolver al usuario a la pantalla de actualización.
  - **Fuera de actualización (`isUpdateInProgress == false`):**
    - Si el paquete es `com.android.vending` y la Play Store está configurada como bloqueada/suspendida: Bloquea el intento, cierra la Play Store regresando al Home (`GLOBAL_ACTION_HOME`) y llama a `policyManager.restoreInstallRestrictions()`.
- **Lógica de automatización en `handlePlayStoreAutoUpdate()`:**
  - Inicializa sesión de escaneo (`updateSessionPkg`, `updateSessionStartTime`, `updateSessionClickedAction`).
  - Muestra el overlay de bloqueo opaco.
  - Escanea los nodos de la pantalla recursivamente buscando:
    - Botones de acción: `actualizar`, `update`, `עדכן`, `instalar`, `install`, `התקן`, `habilitar`, `enable`, o IDs `com.android.vending:id/buy_button` / `com.android.vending:id/action_button`.
    - Botones de diálogo de confirmación: `continuar`, `continue`, `aceptar`, `ok`, `proceder`, `descargar`, `download`, `sí`, `yes`.
    - Botón de completado: `abrir`, `open`, `פתח`.
  - **Ejecución:**
    - Si hay diálogo de confirmación: Hace clic automático.
    - Si hay botón de Actualizar/Instalar: Hace clic automático y marca `updateSessionClickedAction = true`.
    - Si el botón pasa a `Abrir` después de haber hecho clic (o tras 8 segundos sin botón de actualizar): Llama a `finishUpdateAndLock()`.
- **Métodos auxiliares:**
  - `finishUpdateAndLock(context, updatingPkg)`: Oculta el overlay, ejecuta `GLOBAL_ACTION_HOME`, restaura restricciones MDM y limpia preferencias.
  - `performClickOnNode(node)`: Sube por el árbol de nodos buscando uno clickeable y recicla los nodos padres intermedios en un bloque `finally` para evitar fugas de memoria.
