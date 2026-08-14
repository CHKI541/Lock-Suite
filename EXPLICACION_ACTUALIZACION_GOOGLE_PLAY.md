# Explicación Completa: Flujo de Actualización Silenciosa vía Google Play Store

Este documento explica en detalle **qué se buscó hacer**, **qué problemas ocurrieron en las pruebas iniciales** y **cómo quedó resuelta la solución final**.

---

## 1. El Objetivo Inicial (¿Qué quisimos hacer?)

En los dispositivos administrados por **LockSuite**:
1. **Google Play Store está bloqueada y oculta:** El usuario no puede abrirla, buscar aplicaciones ni instalar nada por su cuenta.
2. **Actualización Remota bajo demanda:** Cuando el administrador presiona *"Actualizar Aplicación"* en el Panel Web (por ejemplo para Mercado Pago, Waze o DiDi):
   - La app debe actualizarse en el momento.
   - **Sin intervención del usuario:** No debe requerir que el usuario presione "Actualizar" manualmente.
   - **Sin acceso a Play Store:** Mientras se actualiza, el usuario **NO** debe poder ver la Play Store, ni navegar en ella, ni tocar nada, ni salir a otras apps.
   - **Cierre y re-bloqueo automático:** Apenas termine de instalarse la actualización, la Play Store debe cerrarse sola, volver a la pantalla de inicio y quedar nuevamente suspendida y bloqueada.

---

## 2. Los Problemas Encontrados en las Primeras Pruebas

Durante las primeras versiones de prueba ocurrieron 3 problemas:

1. **Play Store quedaba expuesta y requería clic manual:**
   Al abrirse `market://details?id=...`, la Play Store se mostraba en pantalla completa y el usuario tenía que pulsar "Actualizar" a mano, pudiendo además usar el buscador y descargar otras cosas.
2. **Falso positivo de "Desinstalar" (por el que no pasaba nada):**
   La Play Store siempre muestra dos botones en apps instaladas: `[ Desinstalar ]` y `[ Actualizar ]`. En una versión de prueba, el detector de accesibilidad interpretó la palabra *"Desinstalar"* como si la app ya estuviera al día, cerrando la Play Store en menos de 50 milisegundos antes de que pudiera hacer clic en Actualizar.
3. **Fugas de toques en la pantalla de bloqueo:**
   La primera versión del overlay no tenía un interceptor de toques activo (`OnTouchListener`), por lo que los toques del usuario en la pantalla atravesaban el fondo y tocaban la Play Store que estaba por detrás.

---

## 3. ¿Qué se hizo recién? (Solución Final Blindada)

Se implementó un sistema coordinado entre 5 componentes de LockSuite:

```
[Panel Web] ──> Comando FCM (UPDATE_APP)
                     │
                     ▼
       [LockSuiteFirebaseService]
         • Des-oculta y des-suspende Play Store
         • Levanta restricciones de instalación temporalmente
         • Abre la página de la app (market://details?id=...)
         • Inicia alarma de seguridad de 10 minutos (Watchdog)
                     │
                     ▼
      [LockSuiteAccessibilityService + BlockOverlayManager]
         • Dibuja pantalla 100% NEGRA y OPACA en todo el display
         • ABSORBE el 100% de los toques (el usuario no puede tocar nada)
         • Muestra: "Actualizando [App]... Por favor no toque la pantalla"
         • En segundo plano (debajo del overlay):
             - Busca el botón "Actualizar" / "Instalar" y hace CLIC automático
             - Si sale diálogo (ej: descargar por datos móviles), hace CLIC en "Continuar/Aceptar"
             - Cambia el texto a "Descargando e instalando..."
         • Si el usuario intenta salir a otra app, lo redirige de inmediato a la actualización
                     │
                     ▼
          [Descarga e Instalación en curso]
                     │
                     ▼
          [PackageReceiver (Sistema Operativo)]
         • Android emite Intent.ACTION_PACKAGE_REPLACED al terminar de instalar el APK
         • PackageReceiver lo captura:
             1. Cierra el overlay negro
             2. Cierra la Play Store enviando la orden HOME
             3. Re-bloquea y re-suspende la Play Store inmediatamente
             4. Cancela la alarma watchdog
```

---

## 4. Protección Adicional Fuera de Actualizaciones

Para garantizar que el usuario **nunca** pueda usar la Play Store fuera de este proceso:

* Si en cualquier momento normal el usuario o alguna app intenta abrir `com.android.vending`:
  * El servicio de accesibilidad lo detecta al instante (`isUpdateInProgress == false`).
  * **Cierra la Play Store de inmediato regresando a la pantalla de inicio (Home)**.
  * Re-aplica `policyManager.restoreInstallRestrictions()` para asegurar que esté suspendida.

---

## 5. Resumen de Archivos Modificados

| Archivo | Responsabilidad |
|---|---|
| [`BlockOverlayManager.kt`](file:///c:/Users/israe/OneDrive/Documentos/Lock%20Suite%20segunda%20version/app/src/main/java/com/ejemplo/locksuite/service/BlockOverlayManager.kt) | Crea la pantalla negra 100% opaca que absorbe todos los toques táctiles. |
| [`LockSuiteAccessibilityService.kt`](file:///c:/Users/israe/OneDrive/Documentos/Lock%20Suite%20segunda%20version/app/src/main/java/com/ejemplo/locksuite/service/LockSuiteAccessibilityService.kt) | Hace los clics automáticos, bloquea navegación indebida y bloquea la Play Store si se abre fuera de hora. |
| [`LockSuiteFirebaseService.kt`](file:///c:/Users/israe/OneDrive/Documentos/Lock%20Suite%20segunda%20version/app/src/main/java/com/ejemplo/locksuite/service/LockSuiteFirebaseService.kt) | Recibe la orden del panel web, prepara las políticas y abre el enlace de actualización. |
| [`PackageReceiver.kt`](file:///c:/Users/israe/OneDrive/Documentos/Lock%20Suite%20segunda%20version/app/src/main/java/com/ejemplo/locksuite/receiver/PackageReceiver.kt) | Detecta la finalización real de la instalación por parte de Android, cierra la Play Store y restaura los bloqueos. |
| [`PolicyManager.kt`](file:///c:/Users/israe/OneDrive/Documentos/Lock%20Suite%20segunda%20version/app/src/main/java/com/ejemplo/locksuite/mdm/PolicyManager.kt) | Restaura el estado de ocultamiento y suspensión de la Play Store según las configuraciones del MDM. |
