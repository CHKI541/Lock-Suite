# 📘 Manual Técnico Exhaustivo y Documentación de Arquitectura LockSuite MDM

---

## 📄 TABLA DE CONTENIDOS

1. [Visión General y Filosofía de Diseño del Sistema](#1-visión-general-y-filosofía-de-diseño-del-sistema)
2. [Arquitectura de Software de la Aplicación Android Nativa](#2-arquitectura-de-software-de-la-aplicación-android-nativa)
   - 2.1. Controlador de Políticas MDM (`PolicyManager.kt`)
   - 2.2. Motor de Filtrado DNS de Red sin Consumo de Batería (`KosherVpnService.kt`)
   - 2.3. Servicio de Inspección Visual DOM (`LockSuiteAccessibilityService.kt`)
   - 2.4. Gestor de Paquetes y Visibilidad de Apps (`AppController.kt`)
   - 2.5. Receptor de Comandos Remotos Firebase (`LockSuiteFirebaseService.kt`)
   - 2.6. Monitores de Sistema e Instalación (`PackageReceiver.kt`, `PackageInstallStatusReceiver.kt`)
   - 2.7. Arranque Resiliente en Reinicios (`BootReceiver.kt`)
   - 2.8. Sistema de Actualización Transparente (`SelfUpdater.kt`)
3. [Detalle Minucioso de las Funciones de Bloqueo y Control](#3-detalle-minucioso-de-las-funciones-de-bloqueo-y-control)
   - 3.1. Restricciones Nativas del Sistema Operativo (DPM - 25 Restricciones Documentadas)
   - 3.2. Módulo Kosher VPN & Filtrado DNS de Cero Consumo de Batería
   - 3.3. Bloqueo Total de Internet por Aplicación (Per-App Internet Blocking)
   - 3.4. Bloqueo Dual de Mercado Pago (Accesibilidad vs VPN DNS)
   - 3.5. Ocultar Aplicaciones al Suspender ("Ocultar al suspender")
   - 3.6. Sistema de Presets (.locksuite) con Firma Criptográfica HMAC SHA-256
   - 3.7. Capa de Protección Visual de Imágenes (Silhouette & AI Content Gate)
   - 3.8. Modo Stealth y Lanzador Oculto
   - 3.9. Tienda de Apps Administrada y Gestión de APKs
4. [Arquitectura y Guía Completa del Panel de Administración Web](#4-arquitectura-y-guía-completa-del-panel-de-administración-web)
   - 4.1. Diseño Estético y Sistema de Interfaz Web
   - 4.2. Flujo de Comunicación Bidireccional en Tiempo Real (FCM + Realtime Database)
   - 4.3. Manual de Operación Pantalla por Pantalla del Panel Web
5. [Guía de Instalación, Aprovisionamiento y Despliegue MDM](#5-guía-de-instalación-aprovisionamiento-y-despliegue-mdm)
   - 5.1. Requisitos de Hardware y Sistema Operativo
   - 5.2. Métodos de Aprovisionamiento Device Owner (ADB y Código QR Zero-Touch)
   - 5.3. Despliegue de Backend en Google Cloud / Firebase Functions
6. [Matriz de Diagnóstico y Resolución de Problemas (Troubleshooting)](#6-matriz-de-diagnóstico-y-resolución-de-problemas-troubleshooting)

---

## 1. VISIÓN GENERAL Y FILOSOFÍA DE DISEÑO DEL SISTEMA

**LockSuite MDM** es un sistema integral de **Gestión de Dispositivos Móviles (Mobile Device Management - MDM)** y filtro de protección personalizado diseñado para dispositivos con sistema operativo Android. Su propósito fundamental es ofrecer un control absoluto, inalterable y administrado remotamente sobre la seguridad, conectividad y disponibilidad de aplicaciones en el teléfono del usuario final.

### 1.1. El Paradigma de Propietario de Dispositivo (Device Owner)

A diferencia de las aplicaciones convencionales de control parental que utilizan permisos comunes de accesibilidad o administradores de dispositivos antiguos (susceptibles de ser desinstalados o anulados por el usuario), LockSuite se aprovisiona a nivel de **Device Owner** (Propietario del Dispositivo), el nivel de privilegio más alto otorgado por la API de Android (`android.app.admin.DevicePolicyManager`).

Bajo este paradigma:
- La aplicación LockSuite forma parte indisociable de la capa de gestión del sistema operativo.
- El usuario final no puede desinstalar LockSuite, deshabilitar sus permisos ni detener sus procesos en segundo plano.
- Las opciones de depuración USB (ADB), formateo de fábrica (Recovery/Hardware Reset) y arranque en Modo Seguro quedan neutralizadas a nivel de firmware.

### 1.2. Filtrado Comunitario Kosher y Protección Multicapa

LockSuite combina tres capas de defensa independientes pero sincronizadas en tiempo real:
1. **Capa Nativa del Sistema Operativo**: Restricciones físicas y de software ejecutadas por la API del Device Policy Manager (DPM).
2. **Capa de Red DNS (Kosher VPN)**: Un motor tunelizado local que intercepta solicitudes de resolución de nombres de dominio (DNS) en tiempo real con latencia nula y sin retransmitir tráfico a servidores externos.
3. **Capa de Inspección Visual (Accessibility Engine)**: Un analizador de interfaz gráfica de usuario que monitorea los elementos renderizados en pantalla (`AccessibilityNodeInfo`) para bloquear secciones específicas dentro de apps cerradas de terceros (como Mercado Pago o WhatsApp).

---

## 2. ARQUITECTURA DE SOFTWARE DE LA APLICACIÓN ANDROID NATIVA

El proyecto nativo Android de LockSuite se organiza bajo una arquitectura limpia y orientada a servicios resilientes, ubicada en el paquete base `com.ejemplo.locksuite`.

```
                        ┌─────────────────────────────────────────┐
                        │      Panel Web de Administración        │
                        │    (Firebase Hosting & Cloud Run)       │
                        └────────────────────┬────────────────────┘
                                             │ (Comandos FCM Cifrados)
                                             ▼
                        ┌─────────────────────────────────────────┐
                        │       LockSuiteFirebaseService          │
                        │  (Receptor de Comandos & Emisor ACK)    │
                        └────────────────────┬────────────────────┘
                                             │
      ┌──────────────────────────────────────┼──────────────────────────────────────┐
      │                                      │                                      │
      ▼                                      ▼                                      ▼
┌───────────┐                          ┌───────────┐                          ┌───────────┐
│ Policy    │                          │ Kosher    │                          │ LockSuite │
│ Manager   │                          │ Vpn       │                          │ Access    │
│           │                          │ Service   │                          │ ibility   │
└─────┬─────┘                          └─────┬─────┘                          └─────┬─────┘
      │ (DPM Restriction APIs)               │ (Local TUN DNS Filter)               │ (DOM Node Inspector)
      ▼                                      ▼                                      ▼
┌───────────┐                          ┌───────────┐                          ┌───────────┐
│ Sistema   │                          │ Capa UDP  │                          │ Interfaz  │
│ Android   │                          │ Red Socket│                          │ Gráfica UI│
└───────────┘                          └───────────┘                          └───────────┘
```

### 2.1. Controlador de Políticas MDM (`PolicyManager.kt`)

Ubicación: `com.ejemplo.locksuite.mdm.PolicyManager`

**PolicyManager** es el singleton central que encapsula todas las llamadas a la API `android.app.admin.DevicePolicyManager` y `android.os.UserManager`. Sus responsabilidades principales incluyen:

1. **Gestión de Restricciones del Usuario**: Aplicar o remover llaves de restricción nativas mediante `dpm.addUserRestriction()` y `dpm.clearUserRestriction()`.
2. **Control de Visibilidad y Suspensión**: Coordinar con `AppController` la ocultación y suspensión de aplicaciones.
3. **Gestión de Persistencia Local**: Guardar las políticas activas en almacenamiento encriptado `SharedPreferences` para garantizar su restaurabilidad inmediata tras un reinicio del sistema.
4. **Firma y Verificación Criptográfica de Presets**: Importar y exportar archivos `.locksuite` validando su firma criptográfica **HMAC SHA-256** mediante el secreto maestro `LockSuiteMDM_Preset_HMAC_SecretKey_2026`.

### 2.2. Motor de Filtrado DNS de Red sin Consumo de Batería (`KosherVpnService.kt`)

Ubicación: `com.ejemplo.locksuite.service.KosherVpnService`

`KosherVpnService` extiende la clase nativa `android.net.VpnService`. A diferencia de soluciones de VPN comerciales que reencaminan el tráfico de Internet hacia servidores remotos (lo que produce consumo masivo de batería, sobrecalentamiento y latencia), la VPN de LockSuite es **100% local**:

- **Construcción de la Interfaz TUN**:
  Establece una interfaz virtual local asignando la dirección IP `10.1.10.1` y configurando las rutas para capturar únicamente el tráfico dirigido a servidores DNS (puerto UDP/TCP 53).
- **Inspección Cero-Carga**:
  El servicio ignora los paquetes de datos pesados (descargas HTTPS, streaming de video, llamadas VoIP) y deja que fluyan directamente por el hardware de red nativo del dispositivo. Solo examina paquetes de consulta de nombres de dominio de unos pocos bytes.
- **Respuesta Instantánea `0.0.0.0`**:
  Al detectar una solicitud de resolución DNS para un dominio bloqueado o para una app configurada con **Bloqueo Total de Internet**, el motor responde de inmediato con una respuesta DNS simulada conteniendo la IP de bucle nulo `0.0.0.0`. El sistema operativo del teléfono interpreta la respuesta en menos de 1 milisegundo y cancela la conexión sin intentar reintentos innecesarios.

### 2.3. Servicio de Inspección Visual DOM (`LockSuiteAccessibilityService.kt`)

Ubicación: `com.ejemplo.locksuite.service.LockSuiteAccessibilityService`

Este servicio de accesibilidad monitorea constantemente los eventos de la interfaz de usuario (`AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED`, `TYPE_WINDOW_CONTENT_CHANGED`).

- **Detección de Pantallas Específicas**:
  Inspecciona el nombre de paquete de la aplicación visible en primer plano (`event.packageName`).
- **Navegación y Análisis del Árbol de Nodos**:
  Recorre recursivamente los elementos visuales (`AccessibilityNodeInfo`) buscando coincidencias de texto, identificadores de vista o descripciones de contenido.
- **Acción Inmediata de Rebote**:
  Si el servicio detecta la apertura de una sección restringida (por ejemplo, la pestaña de Ofertas en Mercado Pago), ejecuta instantáneamente la acción global `performGlobalAction(GLOBAL_ACTION_BACK)`, forzando a la app a rebotar hacia la pantalla previa antes de que el usuario interactúe con el contenido no deseado.

### 2.4. Gestor de Paquetes y Visibilidad de Apps (`AppController.kt`)

Ubicación: `com.ejemplo.locksuite.mdm.AppController`

`AppController` es la clase encargada de traducir los comandos de bloqueo individual de aplicaciones a las APIs nativas de Android:

- **Suspensión de Paquetes (`dpm.setPackagesSuspended`)**:
  Marca la aplicación como suspendida por el administrador. El icono en la pantalla de inicio se vuelve gris y se muestra un diálogo del sistema impidiendo su apertura.
- **Ocultamiento de Paquetes (`dpm.setApplicationHidden`)**:
  Desactiva la visibilidad del paquete en todo el sistema operativo. La aplicación desaparece por completo del cajón de aplicaciones, del menú de ajustes y de la pantalla principal.
- **Combinación "Ocultar al suspender"**:
  Cuando esta política está activa en `PolicyManager`, al enviar la orden de suspender una app, `AppController` invoca adicionalmente `setApplicationHidden(true)` para que el icono desaparezca completamente en lugar de quedar visible en gris.

### 2.5. Receptor de Comandos Remotos Firebase (`LockSuiteFirebaseService.kt`)

Ubicación: `com.ejemplo.locksuite.service.LockSuiteFirebaseService`

Hereda de `FirebaseMessagingService` y actúa como la antena receptora de la consola web:

1. **Recepción de Payloads FCM**: Recibe notificaciones push silenciosas de alta prioridad que contienen un objeto JSON de comando (por ejemplo: `{ "command": "SET_FACTORY_RESET_BLOCKED", "value": true, "commandId": "cmd_98234" }`).
2. **Despacho Interno**: Enruta el comando al componente correspondiente (`PolicyManager`, `KosherVpnService` o `AppController`).
3. **Emisión de Acknowledge (ACK)**: Tras aplicar la política exitosamente, escribe inmediatamente una confirmación en el nodo `commandAcks/{deviceId}/{commandId}` de Firebase Realtime Database para que la interfaz del panel web actualice el estado a "Aplicado" con un indicador verde.

### 2.6. Monitores de Sistema e Instalación (`PackageReceiver.kt`, `PackageInstallStatusReceiver.kt`)

Ubicación: `com.ejemplo.locksuite.receiver.*`

- `PackageReceiver.kt`: Escucha los eventos globales del sistema `ACTION_PACKAGE_ADDED`, `ACTION_PACKAGE_REPLACED` y `ACTION_PACKAGE_REMOVED`. Si un usuario intenta instalar una aplicación sin autorización, el receptor detecta la nueva instalación en tiempo real y la bloquea o suspende de inmediato si la política de instalación restringida está activa.
- `PackageInstallStatusReceiver.kt`: Recibe los callbacks del instalador del sistema durante la descarga y actualización de aplicaciones desde la tienda administrada de LockSuite.

### 2.7. Arranque Resiliente en Reinicios (`BootReceiver.kt`)

Ubicación: `com.ejemplo.locksuite.receiver.BootReceiver`

Escucha las intenciones de inicio del dispositivo (`ACTION_BOOT_COMPLETED`, `ACTION_LOCKED_BOOT_COMPLETED`). Tan pronto como el hardware enciende:
1. Re-inicializa inmediatamente todas las restricciones nativas en `PolicyManager`.
2. Inicia el servicio `KosherVpnService` en primer plano.
3. Asegura que el estado de las aplicaciones bloqueadas permanezca inalterado antes de que el usuario ingrese su PIN de desbloqueo.

### 2.8. Sistema de Actualización Transparente (`SelfUpdater.kt`)

Ubicación: `com.ejemplo.locksuite.util.SelfUpdater`

Permite la actualización remota y silenciosa de la aplicación LockSuite o de apps asociadas:
- Descarga el paquete `.apk` de actualización desde una URL HTTPS segura.
- Inicia el proceso de instalación mediante la API `PackageInstaller` del Device Owner sin requerir confirmación ni intervención táctil por parte del usuario.

---

## 3. DETALLE MINUCIOSO DE LAS FUNCIONES DE BLOQUEO Y CONTROL

### 3.1. Restricciones Nativas del Sistema Operativo (DPM - 25 Restricciones Documentadas)

La siguiente tabla describe en detalle cada una de las 25 restricciones nativas administradas por `PolicyManager.kt` mediante la API `DevicePolicyManager`:

| Restricción | Clave de Restricción Nativa Android | Comportamiento Técnico y Efecto Visual en el Celular |
| :--- | :--- | :--- |
| **Bloqueo de Formateo de Fábrica** | `DISALLOW_FACTORY_RESET` | Deshabilita y oculta la opción "Restablecer datos de fábrica" en los ajustes. Neutraliza el formateo por combinación de botones físicos (Menú Recovery). |
| **Bloqueo de Instalación de Apps** | `DISALLOW_INSTALL_APPS` | Bloquea el servicio instalador de paquetes de Android (`PackageInstaller`). Al intentar abrir un APK, se muestra el mensaje "Acción no permitida por su administrador". |
| **Bloqueo de Fuentes Desconocidas** | `DISALLOW_INSTALL_UNKNOWN_SOURCES` | Impide activar la casilla de instalación de APKs desde administradores de archivos o navegadores web. |
| **Bloqueo de Desinstalación** | `DISALLOW_UNINSTALL_APPS` | Desactiva la opción "Desinstalar" en todas las aplicaciones del dispositivo. El botón aparece en gris inhabilitado. |
| **Bloqueo de Depuración USB / ADB** | `DISALLOW_DEBUGGING_FEATURES` | Inhabilita las Opciones de Desarrollador y el puente ADB. Si se conecta el teléfono a una computadora, los comandos ADB son rechazados. |
| **Bloqueo de Modo Seguro** | `DISALLOW_SAFE_BOOT` | Evita que el usuario inicie el celular presionando los botones de volumen para entrar en "Modo Seguro", donde se cargan solo apps de fábrica. |
| **Bloqueo de Cambio de Usuario** | `DISALLOW_USER_SWITCH` | Deshabilita el menú de cambio de usuario en la barra de notificaciones y ajustes, evitando crear perfiles secundarios o de invitado. |
| **Bloqueo de Agregar Usuarios** | `DISALLOW_ADD_USER` | Prohíbe la creación de nuevos usuarios o perfiles de trabajo en el almacenamiento del sistema. |
| **Bloqueo de Modificación de Cuentas** | `DISALLOW_MODIFY_ACCOUNTS` | Inhabilita la opción de añadir, sincronizar o remover cuentas de Google, correo electrónico o redes sociales desde Ajustes. |
| **Bloqueo de Configuración de Bluetooth** | `DISALLOW_CONFIG_BLUETOOTH` | Oculta los ajustes de emparejamiento Bluetooth, evitando vincular nuevos dispositivos o transferir archivos por este medio. |
| **Bloqueo de Compartir Bluetooth** | `DISALLOW_BLUETOOTH_SHARING` | Deshabilita la pila del protocolo OPP (Object Push Profile) de Bluetooth para envío de documentos o fotos. |
| **Bloqueo Total de Bluetooth** | `DISALLOW_BLUETOOTH` | Apaga el chip físico de Bluetooth y bloquea su encendido desde el panel de control o ajustes rápidos. |
| **Bloqueo de Zona Wi-Fi / Compartir Datos**| `DISALLOW_CONFIG_TETHERING` | Inhabilita la opción "Zona Wi-Fi portátil", tethering USB y compartición de Internet por Bluetooth. |
| **Bloqueo de Configuración Wi-Fi** | `DISALLOW_CONFIG_WIFI` | Impide agregar, modificar o eliminar redes Wi-Fi guardadas en el dispositivo. |
| **Bloqueo de Redes Móviles** | `DISALLOW_CONFIG_CELLULAR_NETWORKS` | Oculta el menú de selección de APN, itinerancia de datos y preferencia de red móvil (4G/5G). |
| **Bloqueo de Montaje de Medios OTG / SD** | `DISALLOW_MOUNT_PHYSICAL_MEDIA` | Bloquea la lectura y escritura en memorias pendrive USB conectadas por adaptador OTG o tarjetas MicroSD externas. |
| **Bloqueo de Transferencia USB** | `DISALLOW_USB_FILE_TRANSFER` | Inhabilita los protocolos MTP (Media Transfer Protocol) y PTP al conectar el teléfono a una PC; el puerto solo permite carga eléctrica. |
| **Bloqueo de Fondo de Pantalla** | `DISALLOW_SET_WALLPAPER` | Inhabilita el cambio de fondo de pantalla desde la galería, ajustes o aplicaciones de terceros. |
| **Bloqueo de Certificados de Confianza** | `DISALLOW_CONFIG_CREDENTIALS` | Impide instalar certificados CA de usuario o alterar el almacén de credenciales del sistema. |
| **Bloqueo de Llamadas Salientes** | `DISALLOW_OUTGOING_CALLS` | Deshabilita la realización de llamadas telefónicas comunes (excepto números de emergencia). |
| **Bloqueo de Envíos SMS** | `DISALLOW_SMS` | Deshabilita el envío de mensajes de texto SMS a través de la red celular. |
| **Bloqueo de Diálogos de Error del Sistema**| `DISALLOW_SYSTEM_ERROR_DIALOGS` | Suprime las ventanas emergentes de error "La aplicación se ha detenido" para evitar que el usuario acceda al menú de información de la app. |
| **Bloqueo de Ajustes de Fecha y Hora** | `DISALLOW_CONFIG_DATE_TIME` | Prohíbe modificar manualmente la fecha y hora del sistema, evitando saltarse restricciones temporales. |
| **Bloqueo de Ajustes de Ubicación** | `DISALLOW_SHARE_LOCATION` | Inhabilita apagar el sensor GPS o modificar la precisión de los servicios de localización. |
| **Bloqueo de Ajustes de Pantalla / Sleep** | `DISALLOW_CONFIG_SCREEN_TIMEOUT` | Impide alterar el tiempo de espera de apagado automático de la pantalla. |

---

### 3.2. Módulo Kosher VPN & Filtrado DNS de Cero Consumo de Batería

#### Diagrama de Flujo del Paquete UDP DNS

```
[ Solicitud DNS de App ] ──► (UDP Port 53) ──► [ Interfaz Virtual TUN ]
                                                        │
                                                        ▼
                                           [ KosherVpnService ]
                                                        │
                                   ┌────────────────────┴────────────────────┐
                                   │ ¿Dominio Bloqueado o App en Per-App Net? │
                                   └────────────────────┬────────────────────┘
                                                        │
                                       ┌────────────────┴────────────────┐
                                       │                                 │
                                   (SI) ▼                                ▼ (NO)
                        ┌──────────────────────┐              ┌──────────────────────┐
                        │ Retornar 0.0.0.0     │              │ Dejar Fluir por      │
                        │ Respuesta Local <1ms │              │ Hardware Nativo Red  │
                        └──────────────────────┘              └──────────────────────┘
```

- **Captura Eficiente de Encabezados**:
  El motor de la VPN utiliza un buffer nativo de lectura de sockets UDP. Solamente decodifica los primeros bytes de la cabecera DNS (RFC 1035) para extraer el campo *QNAME* (el nombre de dominio consultado).
- **Lista de Dominios en Memoria RAM**:
  Los dominios restringidos (sitios de contenido explícito, redes de anuncios, servidores de juegos, redes sociales) se almacenan en un conjunto encriptado `HashSet<String>` en memoria RAM. La comprobación se realiza en complejidad `O(1)`, garantizando que el filtrado no ralentice la navegación en lo absoluto.

---

### 3.3. Bloqueo Total de Internet por Aplicación (Per-App Internet Blocking)

El bloqueo selectivo de Internet permite privar de acceso a la red a una app específica sin afectar la conectividad del resto del celular.

1. **Resolución de UID Linux**:
   Cada aplicación en Android se ejecuta bajo un identificador de usuario de Linux único asignado durante la instalación (`Linux UID`). `PolicyManager` obtiene este identificador mediante `context.packageManager.getPackageUid(packageName, 0)`.
2. **Inspección de Socket en VPN**:
   Cuando `KosherVpnService` intercepta una consulta DNS, consulta el UID del socket emisor a través de la API del sistema `VpnService`.
3. **Aislamiento Instantáneo**:
   Si el UID coincide con una app en la lista de `per_app_internet_blocked`, la VPN devuelve inmediatamente `0.0.0.0` para todas sus solicitudes DNS. La aplicación bloqueada mostrará una pantalla de "Sin conexión a Internet" o quedará intentando conectar indefinidamente, mientras que otras apps como WhatsApp o el navegador funcionarán a máxima velocidad.

---

### 3.4. Bloqueo Dual de Mercado Pago (Accesibilidad vs VPN DNS)

LockSuite ofrece dos aproximaciones complementarias para neutralizar las secciones de ofertas, descuentos y tentaciones comerciales en Mercado Pago:

```
                                  [ Mercado Pago App ]
                                           │
                    ┌──────────────────────┴──────────────────────┐
                    │                                             │
                    ▼                                             ▼
        [ Método A: Accesibilidad ]                   [ Método B: Kosher VPN ]
                    │                                             │
        Inspecciona el árbol DOM                      Intercepta la petición DNS
        en busca de "Ofertas"                         a api.mercadopago.com/promotions
                    │                                             │
                    ▼                                             ▼
        Ejecuta GLOBAL_ACTION_BACK                    Responde IP 0.0.0.0
        Rebote instantáneo de pantalla                Carga limpia sin contenido
```

#### Cuadro Comparativo de Ambos Métodos:

| Criterio | Método A: Servicio de Accesibilidad | Método B: Filtro VPN DNS |
| :--- | :--- | :--- |
| **Mecanismo** | Inspección visual de nodos `AccessibilityNodeInfo`. | Interceptación de solicitudes DNS en capa de red. |
| **Comportamiento Visual** | Al tocar la pestaña "Ofertas", la pantalla rebota hacia atrás inmediatamente. | La pestaña abre, pero se muestra vacía con un aviso de error de carga. |
| **Requisito de Permisos** | Requiere activar el permiso de Accesibilidad en el teléfono. | Requiere activar el perfil de VPN local en el teléfono. |
| **Efectividad ante Cambios** | Inmune a cambios de servidor; detecta el texto visible en español. | Inmune a actualizaciones de la app; bloquea la API de promociones. |

---

### 3.5. Ocultar Aplicaciones al Suspender ("Ocultar al suspender")

Cuando se requiere inhabilitar una aplicación, el administrador puede elegir entre dos comportamientos:

1. **Modo Estándar (Solo Suspender)**:
   - Invoca `dpm.setPackagesSuspended(adminComponent, arrayOf(packageName), true)`.
   - **Resultado**: El icono permanece en la pantalla de inicio, pero con un tono grisáceo. Al tocarlo, salta una advertencia del sistema notificando la suspensión.
2. **Modo Avanzado ("Ocultar al suspender" ACTIVADO)**:
   - Invoca de forma coordinada `setPackagesSuspended(true)` y `setApplicationHidden(true)`.
   - **Resultado**: El icono desaparece 100% de la pantalla de inicio, del menú de aplicaciones y de las búsquedas del sistema. El usuario no percibe la presencia de la app en su dispositivo.

---

### 3.6. Sistema de Presets (.locksuite) con Firma Criptográfica HMAC SHA-256

El sistema de Presets permite empaquetar toda la configuración de seguridad del dispositivo en un archivo portable `.locksuite`.

#### Estructura Interna del Archivo `.locksuite`

```json
{
  "version": 2,
  "presetName": "Bloqueo Estricto Trabajo",
  "timestamp": 1784678400000,
  "policies": {
    "factoryResetBlocked": true,
    "installAppsBlocked": true,
    "debuggingBlocked": true,
    "hideSuspendedApps": true,
    "perAppInternetBlocked": [
      "com.instagram.android",
      "com.zhiliaoapp.musically"
    ],
    "suspendedPackages": [
      "com.android.chrome"
    ],
    "hiddenPackages": [
      "com.facebook.katana"
    ],
    "mercadoPagoAccessibilityBlocked": true,
    "mercadoPagoVpnBlocked": true
  },
  "hmacSignature": "e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855"
}
```

#### Proceso de Firma y Validación Antimanipulación (HMAC SHA-256)

1. **Clave Secreta Maestra**:
   Existe una clave simétrica compartida entre la App Android y el Panel Web: `"LockSuiteMDM_Preset_HMAC_SecretKey_2026"`.
2. **Generación del Hash**:
   Se concatenan los valores ordenados de las políticas y el timestamp. Se calcula la firma HMAC-SHA256 y se adjunta en el campo `hmacSignature`.
3. **Verificación en Importación**:
   Antes de aplicar cualquier política desde un archivo `.locksuite` importado:
   - La aplicación toma el objeto `policies`, reconstruye la cadena original y vuelve a generar la firma localmente usando su propia clave secreta.
   - Si la firma generada no es 100% idéntica a la firma del archivo (lo que ocurriría si un usuario abre el JSON e intenta cambiar un `true` a `false`), el archivo es **rechazado al instante** sin aplicar ningún cambio.

---

### 3.7. Capa de Protección Visual de Imágenes (Silhouette & AI Content Gate)

Módulo dedicado al filtrado y moderación visual en aplicaciones con alto contenido de imágenes:

- **Modo Silueta (Capa 1)**: Aplica un filtro gráfico de renderizado que convierte las imágenes detectadas en figuras vectoriales planas o siluetas de bajo contraste.
- **AI Content Gate (Capa 2)**: Superpone una capa flotante de atenuación sobre la vista gráfica (`WindowManager`), bloqueando la visualización de imágenes potencialmente no aptas en navegadores web o redes sociales.

---

### 3.8. Modo Stealth y Lanzador Oculto

Para garantizar que el usuario final no pueda acceder a la interfaz de administración local de LockSuite:

- **Desactivación del Componente Lanzador**:
  LockSuite ejecuta `packageManager.setComponentEnabledSetting()` sobre su propio `LauncherActivity`, cambiando el estado a `COMPONENT_ENABLED_STATE_DISABLED`.
- **Efecto**:
  El icono de LockSuite desaparece por completo del cajón de aplicaciones de Android.
- **Acceso Administrativo de Emergencia**:
  El administrador puede volver a desplegar la consola local marcando un código secreto (por ejemplo `*#*#56257847#*#*`) en el teclado de llamadas del celular, desencadenando el receptor de difusión `SecretCodeReceiver`.

---

### 3.9. Tienda de Apps Administrada y Gestión de APKs

LockSuite actúa como un repositorio de distribución de software corporativo/comunitario:

- **Catálogo Administrado**: Muestra una lista de aplicaciones autorizadas para instalación.
- **Instalación Directa Silenciosa**: Al presionar "Instalar" desde la app o desde el panel web, LockSuite descarga la APK en un directorio encriptado temporal y solicita la instalación a la API nativa `PackageInstaller` de Android. Al poseer privilegios de Device Owner, la instalación se completa sin mostrar confirmaciones ni solicitar permisos al usuario.

---

## 4. ARQUITECTURA Y GUÍA COMPLETA DEL PANEL DE ADMINISTRACIÓN WEB

La consola de administración centralizada está alojada en **Firebase Hosting** y conectada con microservicios en **Google Cloud Run** y **Firebase Realtime Database**.

```
[ Interfaz Web Admin ] ──► (JavaScript app.js) ──► [ Realtime Database ]
          │                                                  │
          ▼                                                  ▼
[ Cloud Function sendCommandV8 ] ──► [ Firebase Cloud Messaging (FCM) ]
                                                     │
                                                     ▼
                                        [ Dispositivo Android ]
```

### 4.1. Diseño Estético y Sistema de Interfaz Web

El panel web fue desarrollado bajo pautas de diseño modernas de alto nivel:
- **Paleta de Colores Curada**: Fondos oscuros profundos (`#0B192C`, `#1E3E62`), tarjetas con efecto de vidrio esmerilado (*Glassmorphism*), acentos dorados/azules y tipografía Inter de Google Fonts.
- **Indicadores Dinámicos**: Iconos animados de estado de batería, barra de estado de conexión WebSocket/FCM en tiempo real y micro-animaciones al presionar interruptores de control.

---

### 4.2. Flujo de Comunicación Bidireccional en Tiempo Real (FCM + Realtime Database)

1. **Envío de Comando**:
   Al presionar un interruptor en el panel web (por ejemplo, "Bloquear Reset de Fábrica"), `app.js` invoca la Cloud Function `sendCommandV8` pasando la ID del dispositivo objetivo y el payload del comando.
2. **Entrega Ultrarrápida por FCM**:
   Firebase Cloud Messaging entrega el paquete de datos al celular a través del canal Push de alta prioridad en menos de 1 segundo.
3. **Confirmación Acknowledge (ACK)**:
   La app Android procesa el comando y escribe un registro en `commandAcks/{deviceId}/{commandId}` con el estado `"applied"`.
4. **Actualización de la Interfaz Web**:
   El panel web escucha ese nodo en tiempo real. Al recibir el ACK, muestra un mensaje flotante (Toast) en verde notificando *"Política aplicada con éxito en el dispositivo"*.

---

### 4.3. Manual de Operación Pantalla por Pantalla del Panel Web

#### Pestaña 1: "Dispositivos Conectados"
- **Ficha del Dispositivo**: Muestra la marca, modelo, versión de Android, dirección IP pública/privada, porcentaje de batería y estado del canal VPN.
- **Acciones Rápidas**: Botón de "Reiniciar Servicio", "Sincronizar Políticas" y "Localizar Dispositivo".

#### Pestaña 2: "Políticas de Sistema"
Contiene los interruptores maestros para activar o desactivar las restricciones nativas descritas en la Sección 3.1:
- Switch `Restringir Formateo de Fábrica`
- Switch `Restringir Depuración USB (ADB)`
- Switch `Restringir Instalación de APKs`
- Switch `Restringir Bluetooth y Compartir Datos`
- Switch `Bloqueo Dual Mercado Pago (Accesibilidad / VPN)`

#### Pestaña 3: "Gestión de Aplicaciones"
- **Buscador y Filtros**: Permite buscar aplicaciones instaladas por nombre o nombre de paquete (`packageName`).
- **Interruptor Global "Ocultar al suspender"**: Determina si las apps suspendidas mostrarán icono gris o desaparecerán por completo.
- **Menú de Opciones por App (⚙️)**:
  - **Bloquear Totalmente (Ocultar)**: Hace desaparecer la app del dispositivo.
  - **Suspender App**: Deshabilita la ejecución de la app.
  - **Bloqueo Total de Internet**: Priva a la app de acceso a la red dejando el resto del celular conectado.
  - **Filtro de Imágenes (Silueta / AI Gate)**: Configura el nivel de filtrado visual para esa app específica.

#### Pestaña 4: "Perfiles / Presets"
- **Crear Nuevo Preset**: Permite tomar el estado actual de todas las llaves y guardarlo con un nombre (ej. *"Modo Estudio Estricto"*).
- **Aplicar Preset**: Envía una orden masiva al dispositivo para reconfigurar todas sus políticas en un solo clic.
- **Exportar Backup (.locksuite)**: Descarga un archivo JSON firmado criptográficamente con HMAC SHA-256 a la computadora del administrador.
- **Importar Backup (.locksuite)**: Permite subir un archivo de preset desde la computadora. El sistema valida automáticamente la firma digital antes de permitir su aplicación.

---

## 5. GUÍA DE INSTALACIÓN, APROVISIONAMIENTO Y DESPLIEGUE MDM

### 5.1. Requisitos de Hardware y Sistema Operativo

- **Dispositivo Móvil**: Smartphone o Tablet con Android 8.0 (Oreo) o superior (compatible con Android 9, 10, 11, 12, 13, 14 y 15+).
- **Estado de Fábrica**: Para aprovisionar como Device Owner, el dispositivo debe estar recién formateado (en la pantalla inicial de bienvenida "Hola") o no debe tener ninguna cuenta de Google vinculada.

---

### 5.2. Métodos de Aprovisionamiento Device Owner

#### Método 1: Aprovisionamiento por Consola ADB (Recomendado para Pruebas)

1. En el teléfono recién iniciado o sin cuentas Google, active las **Opciones de Desarrollador** y habilite la **Depuración USB**.
2. Conecte el teléfono a la computadora mediante un cable USB.
3. Abra una consola de comandos (Terminal / PowerShell) y verifique la conexión ejecutando:
   ```bash
   adb devices
   ```
4. Instale la aplicación LockSuite en el dispositivo:
   ```bash
   adb install -r app-release.apk
   ```
5. Otorgue los privilegios de **Device Owner** ejecutando el siguiente comando exacto:
   ```bash
   adb shell dpm set-device-owner com.ejemplo.locksuite/.receiver.LockSuiteDeviceAdminReceiver
   ```
6. Si el comando responde `Success: Device owner set to package...`, el dispositivo ha quedado aprovisionado exitosamente.

#### Método 2: Aprovisionamiento por Código QR (Zero-Touch Provisioning para Despliegue Masivo)

1. En la pantalla de bienvenida inicial de un teléfono formateado de fábrica ("Hola" / "Welcome"), toque la pantalla **6 veces seguidas en el mismo lugar**.
2. Se abrirá el escáner de código QR integrado de Android.
3. Escanee el código QR de aprovisionamiento de LockSuite (que contiene el enlace de descarga de la APK, el hash SHA-256 del paquete y las credenciales de configuración inicial).
4. El teléfono se conectará a Wi-Fi, descargará e instalará LockSuite automáticamente y se configurará como Device Owner de manera 100% transparente.

---

### 5.3. Despliegue de Backend en Google Cloud / Firebase Functions

Para desplegar la infraestructura web del panel de administración:

1. Instale las herramientas de línea de comandos de Firebase (`firebase-tools`).
2. Acceda a la carpeta `admin-backend`:
   ```bash
   cd admin-backend
   ```
3. Inicie sesión en Firebase:
   ```bash
   firebase login
   ```
4. Despliegue los servicios de Hosting y Cloud Functions:
   ```bash
   firebase deploy --only hosting,functions
   ```

---

## 6. MATRIZ DE DIAGNÓSTICO Y RESOLUCIÓN DE PROBLEMAS (TROUBLESHOOTING)

| Síntoma / Error | Causa Probable | Solución Técnica |
| :--- | :--- | :--- |
| **El comando `set-device-owner` da error "Not allowed to set the device owner because there are already users on the device"**. | Existe una cuenta de Google, WhatsApp o usuario secundario vinculado en el teléfono. | Vaya a Ajustes > Cuentas y elimine todas las cuentas vinculadas, o restablezca el teléfono a valores de fábrica antes de ejecutar el comando ADB. |
| **Los comandos enviados desde el Panel Web no se aplican en el celular**. | El dispositivo perdió la conexión a Internet o el servicio `LockSuiteFirebaseService` fue detenido. | Verifique que el celular tenga señal Wi-Fi/4G. Presione el botón "Reiniciar Servicio" en el panel web para forzar la reconexión de FCM. |
| **Una app con "Bloqueo Total de Internet" sigue cargando contenido**. | La aplicación tiene datos en caché o está utilizando direcciones IP directas codificadas en lugar de consultas DNS. | Borre la caché de la aplicación en el teléfono. Asegúrese de que el servicio `KosherVpnService` esté activo en el área de notificaciones. |
| **Al importar un archivo `.locksuite` sale el error "Firma de seguridad inválida"**. | El archivo JSON fue modificado manualmente o se guardó con una clave secreta diferente. | No edite el archivo `.locksuite` con editores de texto. Genere un nuevo archivo de respaldo directamente desde la función "Exportar Backup" del panel web. |
| **La sección de Ofertas en Mercado Pago no rebota**. | El servicio de accesibilidad de LockSuite se desactivó en los ajustes de Android. | Acceda a Ajustes > Accesibilidad > Servicios Instalados y asegúrese de que `LockSuiteAccessibilityService` esté activado. |
| **El icono de una aplicación suspendida sigue apareciendo en gris**. | La opción "Ocultar al suspender" está desactivada en la configuración global. | Ingrese a la pestaña "Gestión de Aplicaciones" del panel web y active la casilla "Ocultar icono al suspender apps". |

---

*Documento técnico de arquitectura y manual de usuario oficial para el sistema LockSuite MDM.*  
*Versión de Software: 2.4.0-Release | Año: 2026*
