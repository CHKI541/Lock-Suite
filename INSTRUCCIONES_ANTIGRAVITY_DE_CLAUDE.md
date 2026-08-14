# Instrucciones para Antigravity — pendientes de la tercera revisión de Claude

**Fecha:** 14 de agosto de 2026
**Contexto:** Claude revisó los cambios de la v0.4.9.3 a la v0.6.12 y auditó las correcciones aplicadas por Antigravity. Este archivo lista **únicamente lo que Claude no pudo hacer** desde su entorno (no tiene SDK de Android, ni puede compilar, desplegar ni probar en un equipo real), más las decisiones que requieren criterio del dueño del proyecto.

Claude ya aplicó y dejó escritos en el repo 6 archivos corregidos. Esos **no** hay que rehacerlos — solo compilar, probar y desplegar. Están listados en la sección 0 para que puedas verificarlos.

**Estado al momento de escribir esto (HEAD = `7461896`, versionCode 77 / 0.6.15):** tres de esos seis archivos (`KosherVpnService.kt`, `NetworkForwarder.kt`, `database.rules.json`) ya estaban commiteados, así que aparecen sin cambios contra HEAD. Los otros tres (`PolicyManager.kt`, `admin-backend/public/app.js`, `admin-backend/functions/index.js`) están modificados en el working tree y falta commitearlos. El diff total de esos tres es +55 / -3 líneas, y las únicas líneas de código eliminadas son las dos de `rememberDevice: true`.

---

## 0. Lo que Claude ya dejó aplicado (verificar, no rehacer)

| Archivo | Qué se cambió |
|---|---|
| `app/src/main/java/com/ejemplo/locksuite/service/KosherVpnService.kt` | Los UID del sistema (< 10000) ya no se tratan como si fueran de una app. Restaura la lista negra global de WebView y la regla de Mercado Pago por VPN. |
| `app/src/main/java/com/ejemplo/locksuite/util/NetworkForwarder.kt` | Se eliminó un comentario obsoleto que describía una protección ya removida, y se agregó la verificación de dirección de origen de la respuesta DNS (equivalente a `socket.connect()` pero sin tocar el enrutamiento). |
| `admin-backend/database.rules.json` | Se eliminaron las reglas hijas `pinHash`, `pinSalt` y `recoveryCode` que concedían `auth != null` y anulaban la comprobación de `ownerUid` del nodo padre. |
| `app/src/main/java/com/ejemplo/locksuite/mdm/PolicyManager.kt` | Se revirtió la guarda `if (SDK >= P)` sobre `DISALLOW_CONFIG_MOBILE_NETWORKS`. |
| `admin-backend/public/app.js` | `rememberDevice` en comandos de grupo pasó de `true` fijo a `false`. |
| `admin-backend/functions/index.js` | Se agregó `isTrivialPin()` y su comprobación en el comando `CHANGE_PIN`. |

**Verificación esperada:** `./gradlew compileDebugKotlin` debe terminar en BUILD SUCCESSFUL, y `node --check` debe pasar en los dos archivos JS (Claude ya validó la sintaxis JS y el JSON, pero no pudo compilar Kotlin).

---

## 1. Tareas de compilación, despliegue y prueba

### 1.1 Subir el versionCode antes de publicar
`app/build.gradle.kts` está en `versionCode = 77` / `versionName = "0.6.15"`. Antes de publicar, subir el número (78 / 0.6.16) y actualizar `admin-backend/public/version.json` en la misma operación para que coincida. Si se compila y publica con un versionCode igual o menor al ya instalado, Android rechaza la actualización y los equipos no la toman.

### 1.2 Desplegar backend
```bash
cd admin-backend
firebase deploy --only database    # reglas corregidas — NO omitir, es el fix de seguridad
firebase deploy --only functions   # isTrivialPin en CHANGE_PIN
firebase deploy --only hosting     # rememberDevice en comandos de grupo
```

### 1.3 Checklist de prueba en equipo real
Priorizado por riesgo de regresión de los cambios de Claude:

1. **Bloqueo de WebView y de ofertas de Mercado Pago** (es el cambio de mayor impacto). Con una app que tenga el bloqueo de WebView activado, confirmar que ahora sí se bloquean los dominios de la lista negra global. Antes de este cambio no se bloqueaban.
2. **Resolución DNS normal**: navegar varios sitios permitidos y confirmar que no hay lentitud ni fallos nuevos. La verificación de dirección de origen en `NetworkForwarder` es el punto donde una regresión se notaría como "no carga nada".
3. **Bloqueo de un dominio**: entrar a un dominio bloqueado y confirmar que falla al instante, no que se queda esperando.
4. **Sincronización con Firebase**: confirmar que un equipo sigue apareciendo en línea y que puede escribir su estado tras el cambio de reglas. Si algún equipo deja de sincronizar, revisar primero la consola de Firebase por `permission-denied`.
5. **Cambio de PIN desde el panel**: probar que "1234" y "0000" ahora se rechazan con mensaje claro, y que un PIN normal sigue funcionando.
6. **Comandos de grupo**: confirmar que siguen funcionando con el PIN cacheado en la sesión. Si un equipo del grupo nunca tuvo el PIN ingresado en esa sesión, va a fallar con `PIN_REQUIRED` — ese es el comportamiento correcto ahora.
7. **Bloqueo de Wi-Fi en un equipo con Android 7 u 8**, si hay uno disponible: confirmar si `DISALLOW_CONFIG_MOBILE_NETWORKS` se aplica o si el sistema la rechaza. Ahora se vuelve a intentar siempre y se reporta el resultado real, así que si falla se va a ver.

---

## 2. Correcciones de código que Claude no hizo (requieren decisión o contexto que no tiene)

### 2.1 [RESUELTO 2026-08-14] `UPDATE_APP` no revertía el estado si fallaba el lanzamiento de Play Store
**Archivo:** `app/src/main/java/com/ejemplo/locksuite/service/LockSuiteFirebaseService.kt`, bloque del comando `UPDATE_APP`.

**Nota de Claude (misma IA, sesión posterior a la de arriba):** al re-verificar esto contra el archivo real el 14 de agosto, la premisa de esta sección ya no era cierta — el código SÍ escribía `mdm_install_in_progress = true` y SÍ llamaba a `clearUserRestriction` sobre las dos restricciones (algo debe haber cambiado entre el commit que se revisó para escribir esto y el HEAD actual). Lo que seguía roto era otra cosa, más angosta pero igual de seria: si el intent de Play Store y el de navegador fallaban los dos (por ejemplo, un equipo con "Bloquear navegadores" activo y sin Play Store instalada), la función tiraba la excepción hacia el catch externo y devolvía `false` — pero las restricciones ya habían quedado levantadas, `updating_package`/`mdm_install_in_progress` ya habían quedado escritos, y la alarma de watchdog de 10 minutos (paso 4, programada después de abrir Play Store) nunca llegaba a programarse porque el fallo pasaba antes de ese paso. Como `refreshInstallRestriction()` se salta a sí misma mientras `mdm_install_in_progress` esté en `true`, ni el reinicio ni el watchdog de 15 minutos (`reapplyAllRestrictions`) corregían esto: el equipo quedaba con las instalaciones desbloqueadas indefinidamente, sin ninguna red de seguridad.

**Corrección aplicada:** se adelantó el armado de la alarma de watchdog para que quede programada ANTES de intentar abrir Play Store (no después), y se agregó una función `rollbackFailedUpdateApp()` que se llama de inmediato si tanto el intent de Play Store como el de navegador fallan — cancela la alarma ya armada (no hace falta esperarla), restaura las restricciones vía `PolicyManager.restoreInstallRestrictions()` y limpia `updating_package`. Queda escrito en el repo, sin commitear todavía (ver working tree). No se pudo compilar ni probar en equipo real desde este entorno — falta el mismo paso de verificación que el resto de esta lista.

Se confirmó además que el bug histórico del falso positivo de "Desinstalar" (el detector cerrando todo a los 50ms) sigue arreglado: el escaneo de botones en `LockSuiteAccessibilityService.handlePlayStoreAutoUpdate()` no tiene ningún caso que matchee el texto "desinstalar"/"uninstall", así que ese botón no puede disparar un cierre prematuro.

### 2.1-b [RESUELTO 2026-08-14] Uso de nodos de accesibilidad ya reciclados en el auto-clic de Play Store
**Archivo:** `app/src/main/java/com/ejemplo/locksuite/service/LockSuiteAccessibilityService.kt`, `handlePlayStoreAutoUpdate()`.

Segundo hallazgo de la re-revisión del 14 de agosto (distinto al 2.1). El escaneo de la pantalla de Play Store recorría el árbol de nodos guardando en variables los botones "Actualizar"/"Abrir"/confirmación, pero reciclaba cada nodo al desarmar la recursión (`child.recycle()`) y el root al final (`root.recycle()`). Como los nodos guardados son esas mismas instancias recicladas, para cuando el paso B llamaba `performClickOnNode(foundActionButtonNode)` el nodo ya estaba reciclado; `performClickOnNode` lee `node.parent` ANTES de su try/catch, así que lanzaba `IllegalStateException` sin atrapar, propagándose fuera de `onAccessibilityEvent` (que no envuelve nada en try/catch). En las versiones de Android donde `recycle()` no es no-op (Android 11/12, es decir los equipos tipo Qin), el clic de "Actualizar" fallaba y la actualización nunca arrancaba; sólo "funcionaba" en Android nuevo donde `recycle()` es inerte.

**Corrección aplicada:** se quitaron los `recycle()` de ese recorrido puntual (quedan intactos los de las demás funciones, que no guardan referencias más allá del escaneo). `AccessibilityNodeInfo.recycle()` está deprecado y en Android moderno lo maneja el recolector de basura, así que no reciclar ahí es seguro y deja las referencias válidas para los pasos A/B/C. Sin compilar ni probar en equipo real desde este entorno.

### 2.2 Aislamiento real de escritura por dispositivo en Firebase — cambio de arquitectura
**Archivos:** `admin-backend/database.rules.json`, `admin-backend/functions/index.js`, `app/src/main/java/com/ejemplo/locksuite/util/FirebaseDeviceSync.kt`.

La regla actual de `devices/$device_id` incluye esta condición:

```
newData.child('ownerUid').val() === auth.uid
```

`newData` es el dato que se está escribiendo, así que alcanza con incluir el propio uid como `ownerUid` dentro de la escritura para que la regla dé verdadero. Cualquiera puede apropiarse del nodo de cualquier dispositivo simplemente declarando que es suyo. Mientras esa condición esté, la comprobación de propiedad no protege de nada.

La condición no se puede borrar sin más: sin ella, un celular cuyo uid anónimo cambie (pasa al reinstalar la app o al borrar sus datos) no podría volver a escribir en su propio nodo y desaparecería del panel. El problema de fondo es que **un uid de sesión anónima no es una identidad estable de dispositivo**, así que no sirve como base de una regla de propiedad.

**Dos caminos posibles, elegir uno:**

- **(a) Custom Token por dispositivo (preferido).** Agregar una Cloud Function de aprovisionamiento que reciba el `deviceId` y devuelva un Custom Token de Firebase Auth con un claim `deviceId`. Cambiar `FirebaseDeviceSync.withAuth()` para usar `signInWithCustomToken` en vez de `signInAnonymously`. Después la regla puede exigir `auth.token.deviceId === $device_id`, que sí es infalsificable. Hay que resolver cómo se autentica la primera llamada de aprovisionamiento (por ejemplo, un token de un solo uso generado al preparar el equipo).
- **(b) Escrituras detrás de Cloud Function.** Mover todas las escrituras del dispositivo (`syncToken`, `syncDeviceInfo`, `syncPinCredentials`, etc.) a una Cloud Function que valide y escriba con el Admin SDK, y dejar las reglas de `devices` y `deviceSecrets` en `.write: false` para clientes. Es más simple de razonar pero agrega latencia y costo por cada sincronización.

**Importante durante la migración:** hay equipos en producción sin `ownerUid` o con uno viejo. Cualquiera de los dos caminos necesita un período de convivencia o una migración explícita, o esos equipos van a dejar de sincronizar.

### 2.3 Verificación de integridad del APK descargado por OTA
**Archivo:** `app/src/main/java/com/ejemplo/locksuite/util/SelfUpdater.kt`.

Hoy se descarga el APK desde la URL que indica `version.json` y se instala en silencio con privilegios de Device Owner, sin comparar ningún checksum. Para la autoactualización de LockSuite, Android exige el mismo certificado de firma, lo que da cierta protección gratis. Para la **Tienda administrada** (`downloadAndInstallApk`, que instala paquetes nuevos) esa protección no aplica: es la primera instalación de ese paquete, así que Android acepta cualquier certificado.

**Implementar:** agregar un campo `sha256` al `version.json` y a cada entrada de `storeApps` en la base de datos. Después de descargar y antes de abrir la sesión de `PackageInstaller`, calcular el SHA-256 del archivo temporal y compararlo. Si no coincide: borrar el archivo temporal, **no** abrir la sesión de instalación, restaurar las restricciones y devolver un error claro. La comparación tiene que ser sobre el archivo ya descargado en disco, no sobre el stream.

### 2.4 Firma asimétrica de los perfiles (`.locksuite`)
**Archivos:** `app/src/main/java/com/ejemplo/locksuite/mdm/PolicyManager.kt`, `admin-backend/public/app.js`.

La clave HMAC que firma los perfiles (`LockSuiteMDM_Preset_HMAC_SecretKey_2026`) está en texto plano en `app.js`, que Firebase Hosting sirve públicamente, y además en el repositorio de GitHub, que es público. O sea que cualquiera puede firmar un perfil que la app va a dar por válido. La firma actual no aporta ninguna garantía real de integridad.

**Implementar:** reemplazar el HMAC simétrico por una firma asimétrica (ECDSA P-256 o RSA-2048). La clave privada vive solo en una Cloud Function que firma los perfiles al guardarlos; la clave pública se embebe en la app y solo se usa para verificar. La app nunca debe poder generar una firma válida.

### 2.5 `UPDATE_LOCKSUITE` no pide el PIN del dispositivo
**Archivo:** `admin-backend/functions/index.js`, línea del tipo `if (command !== "UPDATE_LOCKSUITE") await verifyDevicePin(...)`.

Un administrador autorizado puede empujar una actualización a cualquier equipo sin conocer su PIN. Combinado con 2.3 (sin verificación de checksum) y con que el APK se sirve desde un repositorio público, esa es la ruta más corta que existe hoy para meter código nuevo en un celular.

**Decidir:** si la excepción es deliberada (para poder actualizar equipos cuyo PIN se perdió), dejarla pero documentarla explícitamente en el código y registrarla en `commandLog` de forma destacada. Si no lo es, quitar la excepción y exigir el PIN también para este comando.

### 2.6 Restricción de fecha y hora
**Archivo:** `app/src/main/java/com/ejemplo/locksuite/mdm/PolicyManager.kt`.

`DISALLOW_CONFIG_DATE_TIME` no se aplica en ningún lado del proyecto. Claude ya blindó el bloqueo por intentos fallidos de PIN usando un reloj monotónico, así que el ataque de "adelantar la hora para saltear el bloqueo" ya no funciona. Pero aplicar la restricción cerraría la puerta del todo y además protege otras cosas que dependen del reloj.

**Implementar como un interruptor más del panel** (no forzada), siguiendo el mismo patrón que las demás restricciones: función en `PolicyManager`, comandos `BLOCK_DATE_TIME` / `UNBLOCK_DATE_TIME` en `ALLOWED_COMMANDS`, manejador en `LockSuiteFirebaseService`, campo en `syncDeviceInfo` y switch en el panel. Es una decisión de producto porque el usuario final pierde la posibilidad de corregir la hora aunque esté mal.

### 2.7 Cartel en el panel para las limitaciones del bloqueo por app
**Archivo:** `admin-backend/public/app.js` y el HTML del panel.

Cada celular ya reporta los campos `perAppDnsRulesSupported` y `androidSdkInt` (los agregó Claude en una revisión anterior). Todavía no se usan en el panel.

**Implementar:** mostrar una advertencia visible en la ficha del dispositivo cuando los interruptores de "Bloquear WebView por app" o "Bloquear internet por app" estén activos. El texto tiene que ser honesto sobre dos límites distintos:

- En equipos con Android menor a 10, la API que atribuye cada consulta DNS a una app no existe: esas reglas no se aplican nunca.
- En **todas** las versiones, las consultas DNS de las apps que usan el resolutor del sistema las emite `netd` en nombre de la app, así que tampoco se pueden atribuir. El bloqueo por app solo alcanza a las apps que resuelven DNS por su cuenta.

Lo que sí funciona en todos los equipos, y conviene aclararlo en el mismo cartel: las reglas DNS personalizadas, el bloqueo de anuncios, el de GIFs, la lista negra global de WebView y el bloqueo de ofertas de Mercado Pago.

---

## 3. Decisión que no es de código

**El repositorio `CHKI541/Lock-Suite` es público.** Está verificado: se puede descargar `raw.githubusercontent.com/CHKI541/Lock-Suite/main/admin-backend/public/version.json` sin credenciales. Tiene que serlo, porque la actualización OTA baja el APK de ahí.

Eso significa que el código fuente completo de la app, las reglas de Firebase y la clave HMAC de los perfiles son legibles por cualquiera. Quien quiera evadir el bloqueo no necesita decompilar nada.

**Recomendación:** pasar el repositorio a privado y mover el APK y el `version.json` a Firebase Hosting, que ya está en uso y sirve archivos públicos sin publicar el código. Es el cambio de mayor impacto y menor esfuerzo de toda la lista.

Si se decide mantenerlo público, entonces hay que asumirlo en el diseño: ningún secreto en el código, y toda la seguridad apoyada en credenciales por dispositivo (lo que hace que 2.2 y 2.4 pasen de "conveniente" a "necesario").

---

## 4. Nota sobre método

Todos los problemas de esta lista **compilan perfectamente**. `BUILD SUCCESSFUL` y `node --check` cubren errores de sintaxis, no de comportamiento: las reglas de Firebase que no protegen lo que dicen proteger, el UID del sistema tratado como si fuera de una app, o una restricción que se saltea y devuelve éxito, todos pasan la compilación sin una advertencia.

Al terminar cada tarea, conviene dejar registrado no solo *qué* se cambió sino *cómo se verificó que el cambio hace lo que dice* — idealmente con una prueba en un equipo real, y si no es posible, diciendo explícitamente que no se probó.
