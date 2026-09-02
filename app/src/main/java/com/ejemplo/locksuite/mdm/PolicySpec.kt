package com.ejemplo.locksuite.mdm

import android.os.Build
import android.os.UserManager

/**
 * PolicySpec — registro DECLARATIVO de restricciones (2/9/2026).
 *
 * ─────────────────────────────────────────────────────────────────────────────
 * DE DÓNDE SALE ESTO Y POR QUÉ VALE LA PENA
 * ─────────────────────────────────────────────────────────────────────────────
 *
 * Es el patrón `ProtectionFeature` + `FeatureRegistry` de A Bloq (SecureGuard MDM),
 * traído a LockSuite. Allá cada restricción es un objeto con su id, su etiqueta, su
 * API mínima y sus métodos de aplicar/consultar, y **la interfaz entera se genera
 * desde una sola lista**.
 *
 * En LockSuite, hasta ahora, agregar una restricción significaba tocar CINCO lugares a
 * mano y que no se olvidara ninguno:
 *
 *   1. `PolicyManager`  — el setter y el getter
 *   2. `PolicyManager.reapplyAllRestrictions()` — para que sobreviva a un reinicio
 *   3. `LockSuiteFirebaseService` — las dos ramas del `when(command)`
 *   4. `admin-backend/functions/index.js` — `ALLOWED_COMMANDS`
 *   5. `admin-backend/public/app.js` + `index.html` — el interruptor
 *
 * (más el perfil exportable, que son dos lugares más). La sesión del 2/9 midió que los
 * cinco estaban alineados —106 comandos permitidos, 105 manejados, y la única
 * diferencia es `VERIFY_PIN`, que es correcta— pero es una alineación sostenida a mano:
 * se rompe sola en cuanto alguien agregue una política y se saltee un archivo, y el
 * síntoma sería una restricción que el panel ofrece y el equipo ignora en silencio.
 *
 * Con este registro, para las restricciones que viven acá **solo se toca esta lista**.
 * El servicio de FCM, la reaplicación al arrancar, el reporte al panel y el perfil
 * exportable la recorren. En el panel siguen haciendo falta el HTML y el `ALLOWED_COMMANDS`
 * de la Cloud Function (son otro lenguaje y otro despliegue), pero se generan desde acá:
 * ver `tools/generar_policies.md`.
 *
 * ─────────────────────────────────────────────────────────────────────────────
 * POR QUÉ NO SE MIGRÓ TODO `PolicyManager` A ESTE PATRÓN
 * ─────────────────────────────────────────────────────────────────────────────
 *
 * Porque `PolicyManager.kt` son 91 KB con lógica que NO es "una restricción del DPM":
 * suspensión de LockSuite, arranque protegido, presets HMAC, bloqueo de internet por
 * proxy, Knox, FRP. Reescribir eso entero sin poder compilar sería temerario. Este
 * registro cubre las restricciones que son **exactamente** "una constante `DISALLOW_*`
 * puesta o sacada", que son las que se agregan seguido y donde el error de omisión es
 * caro. Las que ya existían se dejaron donde estaban a propósito: moverlas no arregla
 * nada y sí puede romper algo.
 *
 * ─────────────────────────────────────────────────────────────────────────────
 * SOBRE `minSdk`
 * ─────────────────────────────────────────────────────────────────────────────
 *
 * Las constantes son `String` compiladas en línea, así que nombrarlas nunca falla en
 * tiempo de ejecución aunque el equipo sea viejo. Lo que sí pasa es que Android
 * **ignora en silencio** una restricción que su versión no conoce: se guarda y no hace
 * nada. Por eso cada entrada declara su API mínima y se reporta al panel qué equipos la
 * soportan de verdad — el mismo problema que ya se documentó para las reglas DNS por app
 * (`perAppDnsRulesSupported`), donde el panel mostraba el interruptor activado en
 * equipos donde la regla no regía.
 */
data class ExtraRestriction(
    /** Clave corta y estable. Se usa en el perfil exportable y para armar el campo del panel. */
    val id: String,
    /** La constante real de `UserManager` que se le pasa a `addUserRestriction()`. */
    val restriction: String,
    /** Etiqueta en español, para el panel. */
    val label: String,
    /** Una línea explicando qué cierra. Va como descripción del interruptor. */
    val description: String,
    /** API mínima en que Android entiende esta restricción. */
    val minSdk: Int,
    /** Comando FCM que la activa. */
    val blockCommand: String,
    /** Comando FCM que la desactiva. */
    val unblockCommand: String,
    /** Campo con el que se reporta el estado a Firebase. */
    val reportField: String
) {
    /** ¿Este equipo puede aplicarla de verdad? */
    val supportedHere: Boolean
        get() = Build.VERSION.SDK_INT >= minSdk
}

object PolicySpec {

    /**
     * Restricciones que A Bloq maneja y LockSuite no tenía. A Bloq cubre 39 constantes
     * `DISALLOW_*`; LockSuite cubría 20. Estas son las 20 que faltaban y que tienen
     * sentido en un equipo kosher.
     *
     * **La más importante de toda la lista es `no_config_private_dns`** — ver el
     * comentario de `WatchdogForegroundService` sobre por qué reemplaza a reimponer
     * "DNS privado apagado" cada 60 segundos.
     */
    val EXTRA_RESTRICTIONS: List<ExtraRestriction> = listOf(
        ExtraRestriction(
            id = "privateDns",
            restriction = UserManager.DISALLOW_CONFIG_PRIVATE_DNS,
            label = "Bloquear DNS privado",
            description = "Impide que el usuario active DNS-over-TLS, que deja ciego al filtro de dominios. " +
                "Es la restricción más importante de esta lista: sin ella LockSuite tiene que reimponer " +
                "el ajuste cada 60 segundos, y en esa ventana el filtro no ve nada.",
            minSdk = Build.VERSION_CODES.Q,
            blockCommand = "BLOCK_PRIVATE_DNS",
            unblockCommand = "UNBLOCK_PRIVATE_DNS",
            reportField = "privateDnsBlocked"
        ),
        ExtraRestriction(
            id = "sms",
            restriction = UserManager.DISALLOW_SMS,
            label = "Bloquear SMS",
            description = "El equipo no puede enviar ni recibir mensajes de texto.",
            minSdk = Build.VERSION_CODES.M,
            blockCommand = "BLOCK_SMS",
            unblockCommand = "UNBLOCK_SMS",
            reportField = "smsBlocked"
        ),
        ExtraRestriction(
            id = "outgoingCalls",
            restriction = UserManager.DISALLOW_OUTGOING_CALLS,
            label = "Bloquear llamadas salientes",
            description = "No se pueden hacer llamadas. Las de emergencia siguen funcionando: las maneja el sistema.",
            minSdk = Build.VERSION_CODES.LOLLIPOP,
            blockCommand = "BLOCK_OUTGOING_CALLS",
            unblockCommand = "UNBLOCK_OUTGOING_CALLS",
            reportField = "outgoingCallsBlocked"
        ),
        ExtraRestriction(
            id = "configLocation",
            restriction = UserManager.DISALLOW_CONFIG_LOCATION,
            label = "Bloquear ajustes de ubicación",
            description = "El usuario no puede cambiar la configuración de ubicación del sistema.",
            minSdk = Build.VERSION_CODES.P,
            blockCommand = "BLOCK_CONFIG_LOCATION",
            unblockCommand = "UNBLOCK_CONFIG_LOCATION",
            reportField = "configLocationBlocked"
        ),
        ExtraRestriction(
            id = "shareLocation",
            restriction = UserManager.DISALLOW_SHARE_LOCATION,
            label = "Bloquear compartir ubicación",
            description = "Apaga el compartir ubicación con apps.",
            minSdk = Build.VERSION_CODES.LOLLIPOP,
            blockCommand = "BLOCK_SHARE_LOCATION",
            unblockCommand = "UNBLOCK_SHARE_LOCATION",
            reportField = "shareLocationBlocked"
        ),
        ExtraRestriction(
            id = "autofill",
            restriction = UserManager.DISALLOW_AUTOFILL,
            label = "Bloquear autocompletado",
            description = "Impide que un servicio de autocompletado lea y rellene formularios.",
            minSdk = Build.VERSION_CODES.O,
            blockCommand = "BLOCK_AUTOFILL",
            unblockCommand = "UNBLOCK_AUTOFILL",
            reportField = "autofillBlocked"
        ),
        ExtraRestriction(
            id = "contentCapture",
            restriction = UserManager.DISALLOW_CONTENT_CAPTURE,
            label = "Bloquear captura de contenido",
            description = "Impide que el sistema entregue el contenido de las pantallas a servicios de terceros.",
            minSdk = Build.VERSION_CODES.Q,
            blockCommand = "BLOCK_CONTENT_CAPTURE",
            unblockCommand = "UNBLOCK_CONTENT_CAPTURE",
            reportField = "contentCaptureBlocked"
        ),
        ExtraRestriction(
            id = "printing",
            restriction = UserManager.DISALLOW_PRINTING,
            label = "Bloquear impresión",
            description = "Cierra la impresión como vía de salida de contenido.",
            minSdk = Build.VERSION_CODES.P,
            blockCommand = "BLOCK_PRINTING",
            unblockCommand = "UNBLOCK_PRINTING",
            reportField = "printingBlocked"
        ),
        ExtraRestriction(
            id = "usbFileTransfer",
            restriction = UserManager.DISALLOW_USB_FILE_TRANSFER,
            label = "Bloquear transferencia por USB",
            description = "Impide sacar o meter archivos conectando el equipo a una computadora.",
            minSdk = Build.VERSION_CODES.LOLLIPOP,
            blockCommand = "BLOCK_USB_FILE_TRANSFER",
            unblockCommand = "UNBLOCK_USB_FILE_TRANSFER",
            reportField = "usbFileTransferBlocked"
        ),
        ExtraRestriction(
            id = "dataRoaming",
            restriction = UserManager.DISALLOW_DATA_ROAMING,
            label = "Bloquear roaming de datos",
            description = "Evita datos en roaming (control de gasto, no de contenido).",
            minSdk = Build.VERSION_CODES.LOLLIPOP,
            blockCommand = "BLOCK_DATA_ROAMING",
            unblockCommand = "UNBLOCK_DATA_ROAMING",
            reportField = "dataRoamingBlocked"
        ),
        ExtraRestriction(
            id = "airplaneMode",
            restriction = UserManager.DISALLOW_AIRPLANE_MODE,
            label = "Bloquear modo avión",
            description = "Cierra el modo avión como forma rápida de dejar el equipo sin red y sin control remoto.",
            minSdk = Build.VERSION_CODES.P,
            blockCommand = "BLOCK_AIRPLANE_MODE",
            unblockCommand = "UNBLOCK_AIRPLANE_MODE",
            reportField = "airplaneModeBlocked"
        ),
        ExtraRestriction(
            id = "ambientDisplay",
            restriction = UserManager.DISALLOW_AMBIENT_DISPLAY,
            label = "Bloquear pantalla ambiente",
            description = "Apaga la pantalla siempre activa, que muestra contenido sin desbloquear.",
            minSdk = Build.VERSION_CODES.P,
            blockCommand = "BLOCK_AMBIENT_DISPLAY",
            unblockCommand = "UNBLOCK_AMBIENT_DISPLAY",
            reportField = "ambientDisplayBlocked"
        ),
        ExtraRestriction(
            id = "systemErrorDialogs",
            restriction = UserManager.DISALLOW_SYSTEM_ERROR_DIALOGS,
            label = "Ocultar diálogos de error del sistema",
            description = "Los cuadros de 'la app dejó de funcionar' se ocultan. Útil en kiosco.",
            minSdk = Build.VERSION_CODES.P,
            blockCommand = "BLOCK_SYSTEM_ERROR_DIALOGS",
            unblockCommand = "UNBLOCK_SYSTEM_ERROR_DIALOGS",
            reportField = "systemErrorDialogsBlocked"
        ),
        ExtraRestriction(
            id = "setWallpaper",
            restriction = UserManager.DISALLOW_SET_WALLPAPER,
            label = "Bloquear cambio de fondo de pantalla",
            description = "El usuario no puede poner una imagen propia como fondo.",
            minSdk = Build.VERSION_CODES.N,
            blockCommand = "BLOCK_SET_WALLPAPER",
            unblockCommand = "UNBLOCK_SET_WALLPAPER",
            reportField = "setWallpaperBlocked"
        ),
        ExtraRestriction(
            id = "setUserIcon",
            restriction = UserManager.DISALLOW_SET_USER_ICON,
            label = "Bloquear foto de perfil",
            description = "El usuario no puede poner una imagen propia como foto de usuario.",
            minSdk = Build.VERSION_CODES.M,
            blockCommand = "BLOCK_SET_USER_ICON",
            unblockCommand = "UNBLOCK_SET_USER_ICON",
            reportField = "setUserIconBlocked"
        ),
        ExtraRestriction(
            id = "configCredentials",
            restriction = UserManager.DISALLOW_CONFIG_CREDENTIALS,
            label = "Bloquear almacén de credenciales",
            description = "Impide instalar certificados a mano, que es como se rompe HTTPS para espiar o evadir.",
            minSdk = Build.VERSION_CODES.LOLLIPOP,
            blockCommand = "BLOCK_CONFIG_CREDENTIALS",
            unblockCommand = "UNBLOCK_CONFIG_CREDENTIALS",
            reportField = "configCredentialsBlocked"
        ),
        ExtraRestriction(
            id = "configCellBroadcasts",
            restriction = UserManager.DISALLOW_CONFIG_CELL_BROADCASTS,
            label = "Bloquear alertas de difusión celular",
            description = "El usuario no puede reconfigurar las alertas de emergencia del operador.",
            minSdk = Build.VERSION_CODES.LOLLIPOP,
            blockCommand = "BLOCK_CONFIG_CELL_BROADCASTS",
            unblockCommand = "UNBLOCK_CONFIG_CELL_BROADCASTS",
            reportField = "configCellBroadcastsBlocked"
        ),
        ExtraRestriction(
            id = "outgoingBeam",
            restriction = UserManager.DISALLOW_OUTGOING_BEAM,
            label = "Bloquear envío por NFC",
            description = "Cierra Android Beam como vía de salida de archivos.",
            minSdk = Build.VERSION_CODES.LOLLIPOP,
            blockCommand = "BLOCK_OUTGOING_BEAM",
            unblockCommand = "UNBLOCK_OUTGOING_BEAM",
            reportField = "outgoingBeamBlocked"
        ),
        ExtraRestriction(
            id = "unmuteMicrophone",
            restriction = UserManager.DISALLOW_UNMUTE_MICROPHONE,
            label = "Silenciar micrófono",
            description = "El micrófono queda silenciado y el usuario no lo puede reactivar.",
            minSdk = Build.VERSION_CODES.LOLLIPOP,
            blockCommand = "BLOCK_UNMUTE_MICROPHONE",
            unblockCommand = "UNBLOCK_UNMUTE_MICROPHONE",
            reportField = "unmuteMicrophoneBlocked"
        ),
        ExtraRestriction(
            id = "removeManagedProfile",
            restriction = UserManager.DISALLOW_REMOVE_MANAGED_PROFILE,
            label = "Bloquear quitar perfil de trabajo",
            description = "Impide eliminar un perfil administrado si el equipo tiene uno.",
            minSdk = Build.VERSION_CODES.LOLLIPOP,
            blockCommand = "BLOCK_REMOVE_MANAGED_PROFILE",
            unblockCommand = "UNBLOCK_REMOVE_MANAGED_PROFILE",
            reportField = "removeManagedProfileBlocked"
        )
    )

    /** Devuelve la entrada cuyo comando de activar coincide, o `null`. */
    fun byBlockCommand(command: String): ExtraRestriction? =
        EXTRA_RESTRICTIONS.firstOrNull { it.blockCommand == command }

    /** Devuelve la entrada cuyo comando de desactivar coincide, o `null`. */
    fun byUnblockCommand(command: String): ExtraRestriction? =
        EXTRA_RESTRICTIONS.firstOrNull { it.unblockCommand == command }

    /** Devuelve la entrada por su id de perfil, o `null`. */
    fun byId(id: String): ExtraRestriction? =
        EXTRA_RESTRICTIONS.firstOrNull { it.id == id }
}
