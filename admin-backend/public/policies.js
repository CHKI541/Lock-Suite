// ARCHIVO GENERADO — NO EDITAR A MANO.
//
// Lo genera `tools/gen_policies_js.py` leyendo los DOS archivos que ya eran la
// fuente de verdad del panel viejo: las etiquetas y los nombres de campo salen de
// `index.html` (la ficha lateral) y los pares de comandos de `app.js`.
//
// `handledSeparately` son los interruptores que la ficha nueva trata aparte porque
// necesitan confirmación propia: suspender LockSuite, el kiosco (puede dejar el
// equipo sin poder marcar *#*#9999#*#*) y apagar el táctil.
//
// Para regenerarlo:  python tools/gen_policies_js.py
// Para verificarlo:  python tools/gen_policies_js.py --check
window.LOCKSUITE_POLICIES = {
  "policies": [
    {
      "field": "factoryResetBlocked",
      "label": "Bloquear Restauración de Fábrica",
      "group": "Políticas de Sistema (Device Owner)",
      "on": "BLOCK_FACTORY_RESET",
      "off": "UNBLOCK_FACTORY_RESET"
    },
    {
      "field": "flashingBlocked",
      "label": "Bloquear Flasheo (Odin — Samsung/Knox)",
      "group": "Políticas de Sistema (Device Owner)",
      "on": "BLOCK_FLASHING",
      "off": "UNBLOCK_FLASHING"
    },
    {
      "field": "installAppsBlocked",
      "label": "Bloquear Instalación de Apps",
      "group": "Políticas de Sistema (Device Owner)",
      "on": "BLOCK_INSTALL_APPS",
      "off": "UNBLOCK_INSTALL_APPS"
    },
    {
      "field": "uninstallAppsBlocked",
      "label": "Bloquear Desinstalación de Apps",
      "group": "Políticas de Sistema (Device Owner)",
      "on": "BLOCK_UNINSTALL_APPS",
      "off": "UNBLOCK_UNINSTALL_APPS"
    },
    {
      "field": "adbBlocked",
      "label": "Bloquear ADB y Opciones de Desarrollador",
      "group": "Políticas de Sistema (Device Owner)",
      "on": "BLOCK_ADB",
      "off": "UNBLOCK_ADB"
    },
    {
      "field": "userSwitchBlocked",
      "label": "Bloquear Cambio de Usuario",
      "group": "Políticas de Sistema (Device Owner)",
      "on": "BLOCK_USER_SWITCH",
      "off": "UNBLOCK_USER_SWITCH"
    },
    {
      "field": "modifyAccountsBlocked",
      "label": "Bloquear Modificación de Cuentas",
      "group": "Políticas de Sistema (Device Owner)",
      "on": "BLOCK_MODIFY_ACCOUNTS",
      "off": "UNBLOCK_MODIFY_ACCOUNTS"
    },
    {
      "field": "safeBootBlocked",
      "label": "Bloquear Reinicio Seguro (Safe Boot)",
      "group": "Políticas de Sistema (Device Owner)",
      "on": "BLOCK_SAFE_BOOT",
      "off": "UNBLOCK_SAFE_BOOT"
    },
    {
      "field": "unknownSourcesBlocked",
      "label": "Bloquear Orígenes Desconocidos (APK)",
      "group": "Políticas de Sistema (Device Owner)",
      "on": "BLOCK_UNKNOWN_SOURCES",
      "off": "UNBLOCK_UNKNOWN_SOURCES"
    },
    {
      "field": "wifiBlocked",
      "label": "Bloquear Ajustes de Red / WiFi / Datos",
      "group": "Políticas de Sistema (Device Owner)",
      "on": "BLOCK_WIFI",
      "off": "UNBLOCK_WIFI"
    },
    {
      "field": "vpnBlocked",
      "label": "Bloquear Ajustes de VPN",
      "group": "Políticas de Sistema (Device Owner)",
      "on": "BLOCK_VPN",
      "off": "UNBLOCK_VPN"
    },
    {
      "field": "appsControlBlocked",
      "label": "Bloquear Control de Apps",
      "group": "Políticas de Sistema (Device Owner)",
      "on": "BLOCK_APPS_CONTROL",
      "off": "UNBLOCK_APPS_CONTROL"
    },
    {
      "field": "adjustVolumeBlocked",
      "label": "Bloquear Ajuste de Volumen",
      "group": "Políticas de Sistema (Device Owner)",
      "on": "BLOCK_VOLUME",
      "off": "UNBLOCK_VOLUME"
    },
    {
      "field": "accessibilityProtected",
      "label": "Proteger Servicio de Accesibilidad",
      "group": "Políticas de Sistema (Device Owner)",
      "on": "PROTECT_ACCESSIBILITY",
      "off": "UNPROTECT_ACCESSIBILITY"
    },
    {
      "field": "privateDnsBlocked",
      "label": "Bloquear DNS privado (DoT)",
      "group": "Restricciones Adicionales",
      "on": "BLOCK_PRIVATE_DNS",
      "off": "UNBLOCK_PRIVATE_DNS"
    },
    {
      "field": "smsBlocked",
      "label": "Bloquear SMS",
      "group": "Restricciones Adicionales",
      "on": "BLOCK_SMS",
      "off": "UNBLOCK_SMS"
    },
    {
      "field": "outgoingCallsBlocked",
      "label": "Bloquear llamadas salientes",
      "group": "Restricciones Adicionales",
      "on": "BLOCK_OUTGOING_CALLS",
      "off": "UNBLOCK_OUTGOING_CALLS"
    },
    {
      "field": "configLocationBlocked",
      "label": "Bloquear ajustes de ubicación",
      "group": "Restricciones Adicionales",
      "on": "BLOCK_CONFIG_LOCATION",
      "off": "UNBLOCK_CONFIG_LOCATION"
    },
    {
      "field": "shareLocationBlocked",
      "label": "Bloquear compartir ubicación",
      "group": "Restricciones Adicionales",
      "on": "BLOCK_SHARE_LOCATION",
      "off": "UNBLOCK_SHARE_LOCATION"
    },
    {
      "field": "configCredentialsBlocked",
      "label": "Bloquear almacén de credenciales",
      "group": "Restricciones Adicionales",
      "on": "BLOCK_CONFIG_CREDENTIALS",
      "off": "UNBLOCK_CONFIG_CREDENTIALS"
    },
    {
      "field": "usbFileTransferBlocked",
      "label": "Bloquear transferencia por USB",
      "group": "Restricciones Adicionales",
      "on": "BLOCK_USB_FILE_TRANSFER",
      "off": "UNBLOCK_USB_FILE_TRANSFER"
    },
    {
      "field": "outgoingBeamBlocked",
      "label": "Bloquear envío por NFC",
      "group": "Restricciones Adicionales",
      "on": "BLOCK_OUTGOING_BEAM",
      "off": "UNBLOCK_OUTGOING_BEAM"
    },
    {
      "field": "printingBlocked",
      "label": "Bloquear impresión",
      "group": "Restricciones Adicionales",
      "on": "BLOCK_PRINTING",
      "off": "UNBLOCK_PRINTING"
    },
    {
      "field": "autofillBlocked",
      "label": "Bloquear autocompletado",
      "group": "Restricciones Adicionales",
      "on": "BLOCK_AUTOFILL",
      "off": "UNBLOCK_AUTOFILL"
    },
    {
      "field": "contentCaptureBlocked",
      "label": "Bloquear captura de contenido",
      "group": "Restricciones Adicionales",
      "on": "BLOCK_CONTENT_CAPTURE",
      "off": "UNBLOCK_CONTENT_CAPTURE"
    },
    {
      "field": "airplaneModeBlocked",
      "label": "Bloquear modo avión",
      "group": "Restricciones Adicionales",
      "on": "BLOCK_AIRPLANE_MODE",
      "off": "UNBLOCK_AIRPLANE_MODE"
    },
    {
      "field": "dataRoamingBlocked",
      "label": "Bloquear roaming de datos",
      "group": "Restricciones Adicionales",
      "on": "BLOCK_DATA_ROAMING",
      "off": "UNBLOCK_DATA_ROAMING"
    },
    {
      "field": "ambientDisplayBlocked",
      "label": "Bloquear pantalla ambiente",
      "group": "Restricciones Adicionales",
      "on": "BLOCK_AMBIENT_DISPLAY",
      "off": "UNBLOCK_AMBIENT_DISPLAY"
    },
    {
      "field": "systemErrorDialogsBlocked",
      "label": "Ocultar diálogos de error del sistema",
      "group": "Restricciones Adicionales",
      "on": "BLOCK_SYSTEM_ERROR_DIALOGS",
      "off": "UNBLOCK_SYSTEM_ERROR_DIALOGS"
    },
    {
      "field": "setWallpaperBlocked",
      "label": "Bloquear cambio de fondo de pantalla",
      "group": "Restricciones Adicionales",
      "on": "BLOCK_SET_WALLPAPER",
      "off": "UNBLOCK_SET_WALLPAPER"
    },
    {
      "field": "setUserIconBlocked",
      "label": "Bloquear foto de perfil",
      "group": "Restricciones Adicionales",
      "on": "BLOCK_SET_USER_ICON",
      "off": "UNBLOCK_SET_USER_ICON"
    },
    {
      "field": "unmuteMicrophoneBlocked",
      "label": "Silenciar micrófono",
      "group": "Restricciones Adicionales",
      "on": "BLOCK_UNMUTE_MICROPHONE",
      "off": "UNBLOCK_UNMUTE_MICROPHONE"
    },
    {
      "field": "configCellBroadcastsBlocked",
      "label": "Bloquear alertas de difusión celular",
      "group": "Restricciones Adicionales",
      "on": "BLOCK_CONFIG_CELL_BROADCASTS",
      "off": "UNBLOCK_CONFIG_CELL_BROADCASTS"
    },
    {
      "field": "removeManagedProfileBlocked",
      "label": "Bloquear quitar perfil de trabajo",
      "group": "Restricciones Adicionales",
      "on": "BLOCK_REMOVE_MANAGED_PROFILE",
      "off": "UNBLOCK_REMOVE_MANAGED_PROFILE"
    },
    {
      "field": "nokiaKeypadMode",
      "label": "Activar modo teléfono de teclas",
      "group": "Modo Teléfono de Teclas",
      "on": "ENABLE_NOKIA_MODE",
      "off": "DISABLE_NOKIA_MODE"
    },
    {
      "field": "googleAccountWebBlocked",
      "label": "Bloquear ajustes de la cuenta de Google (historial y actividad)",
      "group": "Protecciones de Accesibilidad",
      "on": "BLOCK_GOOGLE_ACCOUNT_WEB",
      "off": "UNBLOCK_GOOGLE_ACCOUNT_WEB"
    },
    {
      "field": "googleAccountBlockStrict",
      "label": "↳ Modo estricto: no abrir NINGUNA pantalla de la cuenta",
      "group": "Protecciones de Accesibilidad",
      "on": "SET_GOOGLE_ACCOUNT_MODE_STRICT",
      "off": "SET_GOOGLE_ACCOUNT_MODE_NORMAL"
    },
    {
      "field": "captivePortalGuard",
      "label": "Vigilar la ventana de inicio de sesión de Wi-Fi (portal cautivo)",
      "group": "Protecciones de Accesibilidad",
      "on": "ENABLE_CAPTIVE_PORTAL_GUARD",
      "off": "DISABLE_CAPTIVE_PORTAL_GUARD"
    },
    {
      "field": "captivePortalCoverImages",
      "label": "Tapar las imágenes de esa ventana (apagalo si un portal no deja iniciar sesión)",
      "group": "Protecciones de Accesibilidad",
      "on": "ENABLE_CAPTIVE_PORTAL_IMAGES",
      "off": "DISABLE_CAPTIVE_PORTAL_IMAGES"
    },
    {
      "field": "contactPhotoPickerBlocked",
      "label": "Bloquear selector de fotos e ilustraciones de contactos",
      "group": "Protecciones de Accesibilidad",
      "on": "BLOCK_CONTACT_PHOTO_PICKER",
      "off": "UNBLOCK_CONTACT_PHOTO_PICKER"
    },
    {
      "field": "localeChangeBlocked",
      "label": "Bloquear cambio de idioma del sistema",
      "group": "Protecciones de Accesibilidad",
      "on": "BLOCK_LOCALE_CHANGE",
      "off": "UNBLOCK_LOCALE_CHANGE"
    },
    {
      "field": "accBounceSettings",
      "label": "Rebotar el menú de Accesibilidad de Ajustes",
      "group": "Protecciones de Accesibilidad",
      "on": "ENABLE_ACC_BOUNCE_SETTINGS",
      "off": "DISABLE_ACC_BOUNCE_SETTINGS"
    },
    {
      "field": "accNag",
      "label": "Aviso insistente cada ~18 s si está apagada",
      "group": "Protecciones de Accesibilidad",
      "on": "ENABLE_ACC_NAG",
      "off": "DISABLE_ACC_NAG"
    },
    {
      "field": "accSuspendAll",
      "label": "Suspender TODAS las apps mientras esté apagada",
      "group": "Protecciones de Accesibilidad",
      "on": "ENABLE_ACC_SUSPEND_ALL",
      "off": "DISABLE_ACC_SUSPEND_ALL"
    },
    {
      "field": "bootGateWaitAccessibility",
      "label": "Arranque protegido: esperar también a la Accesibilidad",
      "group": "Protecciones de Accesibilidad",
      "on": "ENABLE_BOOT_GATE_ACCESSIBILITY",
      "off": "DISABLE_BOOT_GATE_ACCESSIBILITY"
    },
    {
      "field": "whitelistEnabled",
      "label": "Activar el filtro de lista blanca",
      "group": "🛡️ Modo lista blanca",
      "on": "ENABLE_WHITELIST_MODE",
      "off": "DISABLE_WHITELIST_MODE"
    },
    {
      "field": "whitelistEnforce",
      "label": "↳ Bloquear de verdad (apagado = solo simular)",
      "group": "🛡️ Modo lista blanca",
      "on": "SET_WHITELIST_ENFORCE",
      "off": "SET_WHITELIST_SIMULATION"
    },
    {
      "field": "whitelistSharedCdn",
      "label": "↳ Permitir CDN compartidos (CloudFront, Akamai, Fastly)",
      "group": "🛡️ Modo lista blanca",
      "on": "ENABLE_WHITELIST_SHARED_CDN",
      "off": "DISABLE_WHITELIST_SHARED_CDN"
    },
    {
      "field": "bootGateEnabled",
      "label": "Arranque protegido (cerrar la red hasta que el filtro esté listo)",
      "group": "Arranque protegido y filtro visual",
      "on": "ENABLE_BOOT_GATE",
      "off": "DISABLE_BOOT_GATE"
    },
    {
      "field": "imageStrictScroll",
      "label": "Imágenes: tapado estricto al desplazar",
      "group": "Arranque protegido y filtro visual",
      "on": "ENABLE_IMAGE_STRICT_SCROLL",
      "off": "DISABLE_IMAGE_STRICT_SCROLL"
    },
    {
      "field": "cameraDisabled",
      "label": "Deshabilitar Cámara Física",
      "group": "Control de Hardware y Pantalla",
      "on": "DISABLE_CAMERA",
      "off": "ENABLE_CAMERA"
    },
    {
      "field": "screenCaptureBlocked",
      "label": "Bloquear Captura de Pantalla",
      "group": "Control de Hardware y Pantalla",
      "on": "BLOCK_SCREEN_CAPTURE",
      "off": "UNBLOCK_SCREEN_CAPTURE"
    },
    {
      "field": "statusBarDisabled",
      "label": "Deshabilitar Barra de Estado",
      "group": "Control de Hardware y Pantalla",
      "on": "DISABLE_STATUSBAR",
      "off": "ENABLE_STATUSBAR"
    },
    {
      "field": "keyguardDisabled",
      "label": "Deshabilitar Bloqueo de Pantalla (Keyguard)",
      "group": "Control de Hardware y Pantalla",
      "on": "DISABLE_KEYGUARD",
      "off": "ENABLE_KEYGUARD"
    },
    {
      "field": "kosherLauncherEnabled",
      "label": "Activar Modo Launcher MP3 Kosher",
      "group": "Control de Hardware y Pantalla",
      "on": "ENABLE_KOSHER_LAUNCHER",
      "off": "DISABLE_KOSHER_LAUNCHER"
    },
    {
      "field": "internetBlocked",
      "label": "Bloquear Todo el Internet",
      "group": "Conectividad y Filtros",
      "on": "BLOCK_INTERNET",
      "off": "UNBLOCK_INTERNET"
    },
    {
      "field": "bluetoothBlocked",
      "label": "Bloquear Bluetooth",
      "group": "Conectividad y Filtros",
      "on": "BLOCK_BLUETOOTH",
      "off": "UNBLOCK_BLUETOOTH"
    },
    {
      "field": "bluetoothSharingBlocked",
      "label": "Bloquear Compartir por Bluetooth",
      "group": "Conectividad y Filtros",
      "on": "BLOCK_BLUETOOTH_SHARING",
      "off": "UNBLOCK_BLUETOOTH_SHARING"
    },
    {
      "field": "externalMediaBlocked",
      "label": "Bloquear Montaje de Medios Externos",
      "group": "Conectividad y Filtros",
      "on": "BLOCK_EXTERNAL_MEDIA",
      "off": "UNBLOCK_EXTERNAL_MEDIA"
    },
    {
      "field": "tetheringBlocked",
      "label": "Bloquear Zona WiFi / Tethering",
      "group": "Conectividad y Filtros",
      "on": "BLOCK_TETHERING",
      "off": "UNBLOCK_TETHERING"
    },
    {
      "field": "adBlockingEnabled",
      "label": "Filtro de Anuncios DNS (AdBlock)",
      "group": "Conectividad y Filtros",
      "on": "ENABLE_ADBLOCK",
      "off": "DISABLE_ADBLOCK"
    },
    {
      "field": "gifsBlocked",
      "label": "Bloquear GIFs y Stickers (Tenor)",
      "group": "Conectividad y Filtros",
      "on": "BLOCK_GIFS",
      "off": "UNBLOCK_GIFS"
    },
    {
      "field": "stealthModeEnabled",
      "label": "Modo Oculto (Stealth Mode)",
      "group": "Conectividad y Filtros",
      "on": "ENABLE_STEALTH",
      "off": "DISABLE_STEALTH"
    },
    {
      "field": "aiModeEnabled",
      "label": "Activar Modo IA (Filtro de Siluetas)",
      "group": "Inteligencia Artificial",
      "on": "ENABLE_AI_MODE",
      "off": "DISABLE_AI_MODE"
    },
    {
      "field": "mapsImageBlockingEnabled",
      "label": "Bloqueo de Imágenes en Maps",
      "group": "Inteligencia Artificial",
      "on": "ENABLE_MAPS_IMAGE_BLOCKING",
      "off": "DISABLE_MAPS_IMAGE_BLOCKING"
    },
    {
      "field": "whatsappBlockStatus",
      "label": "Bloquear Estados (WhatsApp)",
      "group": "Filtros de Servicios",
      "on": "BLOCK_WHATSAPP_STATUS",
      "off": "UNBLOCK_WHATSAPP_STATUS"
    },
    {
      "field": "whatsappBlockChannels",
      "label": "Bloquear Canales (WhatsApp)",
      "group": "Filtros de Servicios",
      "on": "BLOCK_WHATSAPP_CHANNELS",
      "off": "UNBLOCK_WHATSAPP_CHANNELS"
    },
    {
      "field": "mercadoPagoBlockOffersAccessibility",
      "label": "Bloquear Ofertas MP (Accesibilidad)",
      "group": "Filtros de Servicios",
      "on": "BLOCK_MP_OFFERS_ACCESSIBILITY",
      "off": "UNBLOCK_MP_OFFERS_ACCESSIBILITY"
    },
    {
      "field": "mercadoPagoBlockOffersVpn",
      "label": "Bloquear Ofertas MP (por VPN)",
      "group": "Filtros de Servicios",
      "on": "BLOCK_MP_OFFERS_VPN",
      "off": "UNBLOCK_MP_OFFERS_VPN"
    },
    {
      "field": "blockMlInMp",
      "label": "Bloqueo de Mercado Libre en MP",
      "group": "Filtros de Servicios",
      "on": "BLOCK_ML_IN_MP",
      "off": "UNBLOCK_ML_IN_MP"
    },
    {
      "field": "blockPopularNonKosher",
      "label": "Bloquear apps populares no kosher",
      "group": "Filtros de Servicios",
      "on": "BLOCK_POPULAR_NON_KOSHER",
      "off": "UNBLOCK_POPULAR_NON_KOSHER"
    }
  ],
  "handledSeparately": [
    "kioskLockTaskEnabled",
    "locksuiteSuspended",
    "nokiaTouchEnabled"
  ],
  "switchesWithoutCommand": [],
  "commandsWithoutSwitch": [
    "mercadoPagoBlockOffers"
  ]
};
