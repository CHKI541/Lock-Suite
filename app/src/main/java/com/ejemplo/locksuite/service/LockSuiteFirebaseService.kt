package com.ejemplo.locksuite.service

import android.app.admin.DevicePolicyManager
import android.content.ComponentName
import android.content.Context
import android.content.pm.PackageManager
import android.provider.Settings
import com.ejemplo.locksuite.mdm.PolicyManager
import com.google.firebase.database.FirebaseDatabase
import com.google.firebase.messaging.FirebaseMessagingService
import com.google.firebase.messaging.RemoteMessage

import javax.crypto.Mac
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import javax.crypto.spec.SecretKeySpec
import android.util.Base64
import java.security.MessageDigest

class LockSuiteFirebaseService : FirebaseMessagingService() {

    companion object {
        /** Timestamp más alto ya aceptado. Piso monotónico anti-repetición. */
        private const val KEY_LAST_CMD_TIMESTAMP = "last_command_timestamp"

        /** Desorden tolerado en la entrega de FCM antes de considerar el mensaje repetido. */
        private const val REORDER_SLACK_MS = 10 * 60 * 1000L

        /**
         * Ventana absoluta contra el reloj del equipo. Amplia a propósito: acá NO vive la
         * protección anti-repetición (esa la dan commandId + el piso monotónico), así que
         * apretarla solo sirve para dejar inadministrable un equipo con la hora mal.
         */
        private const val ABSOLUTE_WINDOW_MS = 24 * 60 * 60 * 1000L

        /**
         * 1/1/2024 en epoch. Por debajo de esto el reloj del equipo no arrancó nunca
         * (no hubo hora de red ni RTC válido) y no sirve para comparar contra nada.
         */
        private const val CREDIBLE_CLOCK_FLOOR_MS = 1_704_067_200_000L
    }

    override fun onMessageReceived(message: RemoteMessage) {
        val data = message.data
        val command = data["command"] ?: return
        var commandId = data["commandId"]
        
        val policyManager = PolicyManager(this)

        // Latido oportunista. Si llegó un mensaje FCM, el equipo está despierto y con red
        // AHORA MISMO: es el mejor momento posible para refrescar `lastSeen`, y no cuesta
        // nada porque la conexión ya está abierta. Va ANTES de validar la firma a
        // propósito: un equipo cuya credencial de comandos se desincronizó (ver
        // FirebaseDeviceSync.pushCommandSecret) tiene que seguir apareciendo en línea en
        // el panel, si no el diagnóstico apunta al lugar equivocado ("está desconectado")
        // cuando en realidad recibe todo y lo descarta.
        try {
            com.ejemplo.locksuite.util.FirebaseDeviceSync.syncLastSeenOnly(this)
        } catch (e: Exception) {
            e.printStackTrace()
        }

        // Todo comando exige firma HMAC por dispositivo + timestamp + commandId, venga del
        // panel web (vía Cloud Functions) o de cualquier otro origen — no existe una vía "de
        // confianza implícita" sin firmar; las condiciones de abajo lo exigen siempre.
        val signature = data["signature"]
        val timestamp = data["timestamp"]

        // ⚠️ 2/9/2026 — POR QUÉ ESTO YA NO ES UN `if` GIGANTE QUE TERMINA EN `return` MUDO.
        //
        // Antes, cualquiera de estas cuatro causas descartaba el comando SIN dejar rastro
        // en ningún lado: ni ack, ni campo en Firebase, solo un Log.w que hay que estar
        // mirando por USB para ver. El panel, mientras tanto, escribía "status: sent" y
        // daba el comando por bueno. Ese es exactamente el síntoma "mando cosas desde la
        // web y en el celular no pasa nada" — y es indistinguible, desde el panel, de un
        // equipo apagado. Ahora cada rechazo escribe un ack con su motivo.
        val rejection: String? = when {
            commandId.isNullOrBlank() -> "MISSING_COMMAND_ID"
            signature.isNullOrBlank() -> "MISSING_SIGNATURE"
            timestamp.isNullOrBlank() -> "MISSING_TIMESTAMP"
            timestampOutOfWindow(timestamp) -> "TIMESTAMP_OUT_OF_WINDOW"
            !verifyFcmSignature(data, timestamp, signature) -> "BAD_SIGNATURE"
            isReplay(commandId) -> "REPLAY"
            else -> null
        }
        if (rejection != null) {
            android.util.Log.w("LockSuiteFCM", "Comando FCM rechazado ($rejection): $command")
            // Un commandId ausente o en blanco no da dónde escribir el ack; el resto sí.
            if (!commandId.isNullOrBlank()) {
                writeRejectionAck(commandId, command, rejection)
            }
            // BAD_SIGNATURE es la firma de que el secreto de comandos se desincronizó.
            // Marcarlo para que el panel lo muestre y ofrezca re-vincular, en vez de
            // dejar al administrador adivinando por qué el equipo no obedece.
            if (rejection == "BAD_SIGNATURE") {
                try {
                    com.ejemplo.locksuite.util.FirebaseDeviceSync.pushCommandSecret(this)
                } catch (e: Exception) {
                    e.printStackTrace()
                }
            }
            return
        }
        recordCommand(commandId!!, timestamp!!.toLong())

        val packagesStr = data["packages"]
        val packagesList = packagesStr?.split(",")?.map { it.trim() }?.filter { it.isNotEmpty() } ?: emptyList()

        val requiresPackages = setOf(
            "HIDE_APP", "UNHIDE_APP", "SUSPEND_APP", "UNSUSPEND_APP", 
            "BLOCK_WEBVIEW", "UNBLOCK_WEBVIEW", "SET_IMAGE_BLOCK_NONE", 
            "SET_IMAGE_BLOCK_LAYER_1", "SET_IMAGE_BLOCK_LAYER_2", "SET_IMAGE_BLOCK_BOTH",
            "BLOCK_APP_INTERNET", "UNBLOCK_APP_INTERNET"
        )
        if (requiresPackages.contains(command) && packagesList.isEmpty()) {
            android.util.Log.w("LockSuiteFCM", "Comando $command requiere una lista de paquetes, pero se recibió vacía.")
            if (commandId != null) {
                try {
                    val deviceId = com.ejemplo.locksuite.util.FirebaseDeviceSync.deviceId(this)
                    val baseRef = FirebaseDatabase.getInstance().reference
                    val ackData = mapOf(
                        "status" to "failed",
                        "command" to command,
                        "reason" to "PACKAGES_LIST_EMPTY",
                        "timestamp" to com.google.firebase.database.ServerValue.TIMESTAMP
                    )
                    baseRef.child("devices/$deviceId/commandAcks/$commandId").setValue(ackData)
                    baseRef.child("devices/$deviceId/info/commandAcks/$commandId").setValue(ackData).addOnFailureListener {}
                } catch (e: Exception) {
                    e.printStackTrace()
                }
            }
            return
        }

        var commandErrorReason: String? = null
        var success = true

        // ── LockSuite suspendido: no se aplican políticas ──
        // Mientras dura la suspensión el equipo tiene que estar realmente libre.
        // Si se dejaran pasar los comandos de política, un switch tocado en el
        // panel volvería a bloquear algo en el acto y la suspensión dejaría de
        // significar lo que dice. Se rechaza con un mensaje claro en vez de
        // ejecutar a medias. Las únicas excepciones son las que hacen falta
        // justamente para salir de este estado o para no perder el control
        // remoto del equipo.
        val allowedWhileSuspended = setOf(
            "RESUME_LOCKSUITE", "SUSPEND_LOCKSUITE", "UPDATE_LOCKSUITE",
            "VERIFY_PIN", "CHANGE_PIN", "CANCEL_UPDATE_APP"
        )
        if (command !in allowedWhileSuspended && policyManager.isLockSuiteSuspended()) {
            commandErrorReason = "LockSuite está suspendido en este equipo. Desactivá la suspensión antes de cambiar políticas."
            sendCommandAck(commandId, command, false, commandErrorReason)
            return
        }

        try {
            success = when (command) {
                "LOCK_DEVICE" -> {
                    val dpm = getSystemService(Context.DEVICE_POLICY_SERVICE) as DevicePolicyManager
                    try {
                        dpm.lockNow()
                        true
                    } catch (e: Exception) {
                        e.printStackTrace()
                        commandErrorReason = e.message ?: e.toString()
                        false
                    }
                }
                "BLOCK_INSTALL_APPS" -> policyManager.setInstallAppsBlocked(true)
                "UNBLOCK_INSTALL_APPS" -> policyManager.setInstallAppsBlocked(false)
                "BLOCK_UNINSTALL_APPS" -> policyManager.setUninstallAppsBlocked(true)
                "UNBLOCK_UNINSTALL_APPS" -> policyManager.setUninstallAppsBlocked(false)
                "BLOCK_FACTORY_RESET" -> policyManager.setFactoryResetBlocked(true)
                "UNBLOCK_FACTORY_RESET" -> policyManager.setFactoryResetBlocked(false)
                "BLOCK_FLASHING" -> policyManager.setFlashingBlocked(true)
                "UNBLOCK_FLASHING" -> policyManager.setFlashingBlocked(false)
                "BLOCK_ADB" -> policyManager.setDebuggingFeaturesBlocked(true)
                "UNBLOCK_ADB" -> policyManager.setDebuggingFeaturesBlocked(false)
                "BLOCK_USER_SWITCH" -> policyManager.setUserSwitchBlocked(true)
                "UNBLOCK_USER_SWITCH" -> policyManager.setUserSwitchBlocked(false)
                "BLOCK_MODIFY_ACCOUNTS" -> policyManager.setModifyAccountsBlocked(true)
                "UNBLOCK_MODIFY_ACCOUNTS" -> policyManager.setModifyAccountsBlocked(false)
                "BLOCK_SAFE_BOOT" -> policyManager.setSafeBootBlocked(true)
                "UNBLOCK_SAFE_BOOT" -> policyManager.setSafeBootBlocked(false)
                "BLOCK_UNKNOWN_SOURCES" -> policyManager.setUnknownSourcesBlocked(true)
                "UNBLOCK_UNKNOWN_SOURCES" -> policyManager.setUnknownSourcesBlocked(false)
                "BLOCK_VOLUME" -> policyManager.setAdjustVolumeBlocked(true)
                "UNBLOCK_VOLUME" -> policyManager.setAdjustVolumeBlocked(false)
                "BLOCK_APPS_CONTROL" -> policyManager.setAppsControlBlocked(true)
                "UNBLOCK_APPS_CONTROL" -> policyManager.setAppsControlBlocked(false)
                "BLOCK_BLUETOOTH_SHARING" -> policyManager.setBluetoothSharingBlocked(true)
                "UNBLOCK_BLUETOOTH_SHARING" -> policyManager.setBluetoothSharingBlocked(false)
                "BLOCK_EXTERNAL_MEDIA" -> policyManager.setExternalMediaBlocked(true)
                "UNBLOCK_EXTERNAL_MEDIA" -> policyManager.setExternalMediaBlocked(false)
                "BLOCK_TETHERING" -> policyManager.setTetheringBlocked(true)
                "UNBLOCK_TETHERING" -> policyManager.setTetheringBlocked(false)
                
                "BLOCK_WIFI" -> policyManager.setWifiConfigBlocked(true)
                "UNBLOCK_WIFI" -> policyManager.setWifiConfigBlocked(false)
                "BLOCK_BLUETOOTH" -> policyManager.setBluetoothBlocked(true)
                "UNBLOCK_BLUETOOTH" -> policyManager.setBluetoothBlocked(false)
                "BLOCK_VPN" -> policyManager.setVpnConfigBlocked(true)
                "UNBLOCK_VPN" -> policyManager.setVpnConfigBlocked(false)
                "ENABLE_STEALTH" -> {
                    setStealthMode(true)
                    true
                }
                "DISABLE_STEALTH" -> {
                    setStealthMode(false)
                    true
                }
                
                "DISABLE_CAMERA" -> policyManager.setCameraDisabled(true)
                "ENABLE_CAMERA" -> policyManager.setCameraDisabled(false)
                "BLOCK_SCREEN_CAPTURE" -> policyManager.setScreenCaptureBlocked(true)
                "UNBLOCK_SCREEN_CAPTURE" -> policyManager.setScreenCaptureBlocked(false)
                "DISABLE_STATUSBAR" -> policyManager.setStatusBarDisabled(true)
                "ENABLE_STATUSBAR" -> policyManager.setStatusBarDisabled(false)
                "ENABLE_KOSHER_LAUNCHER" -> policyManager.setKosherLauncherEnabled(true)
                "DISABLE_KOSHER_LAUNCHER" -> policyManager.setKosherLauncherEnabled(false)

                // Kiosco real del sistema operativo (Lock Task). Ver PolicyManager.
                // ⚠️ Con esto encendido, el equipo SOLO abre los paquetes de la lista de
                // apps permitidas del launcher. Si el marcador telefónico no está en esa
                // lista, el código de recuperación *#*#9999#*#* no se puede marcar.
                "ENABLE_KIOSK_LOCK_TASK" -> policyManager.setKioskLockTaskEnabled(true)
                "DISABLE_KIOSK_LOCK_TASK" -> policyManager.setKioskLockTaskEnabled(false)

                // Modo teléfono de teclas (estilo Nokia). Ver ui/launcher/NokiaKeypadScreen.kt.
                "ENABLE_NOKIA_MODE" -> policyManager.setNokiaKeypadMode(true)
                "DISABLE_NOKIA_MODE" -> policyManager.setNokiaKeypadMode(false)
                // ⚠️ Apagar el táctil solo afecta a la pantalla del launcher (Android no
                // deja apagar el digitalizador del equipo entero). Y en un celular sin
                // teclas físicas, deja el inicio manejable solo desde el panel o con el
                // gesto de emergencia de 3 s en la esquina superior derecha.
                "ENABLE_NOKIA_TOUCH" -> policyManager.setNokiaTouchEnabled(true)
                "DISABLE_NOKIA_TOUCH" -> policyManager.setNokiaTouchEnabled(false)

                "DISABLE_KEYGUARD" -> policyManager.setKeyguardDisabled(true)
                "ENABLE_KEYGUARD" -> policyManager.setKeyguardDisabled(false)
                "BLOCK_INTERNET" -> policyManager.setInternetBlocked(true)
                "UNBLOCK_INTERNET" -> policyManager.setInternetBlocked(false)
                "ENABLE_ADBLOCK" -> policyManager.setAdBlockingEnabled(true)
                "DISABLE_ADBLOCK" -> policyManager.setAdBlockingEnabled(false)
                "BLOCK_GIFS" -> policyManager.setGifsBlocked(true)
                "UNBLOCK_GIFS" -> policyManager.setGifsBlocked(false)
                "BLOCK_WHATSAPP_STATUS" -> {
                    policyManager.setWhatsAppBlockStatus(true)
                    true
                }
                "UNBLOCK_WHATSAPP_STATUS" -> {
                    policyManager.setWhatsAppBlockStatus(false)
                    true
                }
                "BLOCK_WHATSAPP_CHANNELS" -> {
                    policyManager.setWhatsAppBlockChannels(true)
                    true
                }
                "UNBLOCK_WHATSAPP_CHANNELS" -> {
                    policyManager.setWhatsAppBlockChannels(false)
                    true
                }
                "BLOCK_MERCADOPAGO_OFFERS" -> {
                    policyManager.setMercadoPagoBlockOffers(true)
                    true
                }
                "UNBLOCK_MERCADOPAGO_OFFERS" -> {
                    policyManager.setMercadoPagoBlockOffers(false)
                    true
                }
                "BLOCK_MP_OFFERS_ACCESSIBILITY" -> {
                    policyManager.setMercadoPagoBlockOffersAccessibility(true)
                    true
                }
                "UNBLOCK_MP_OFFERS_ACCESSIBILITY" -> {
                    policyManager.setMercadoPagoBlockOffersAccessibility(false)
                    true
                }
                "BLOCK_MP_OFFERS_VPN" -> {
                    policyManager.setMercadoPagoBlockOffersVpn(true)
                    true
                }
                "UNBLOCK_MP_OFFERS_VPN" -> {
                    policyManager.setMercadoPagoBlockOffersVpn(false)
                    true
                }
                "BLOCK_ML_IN_MP" -> {
                    policyManager.setMercadoLibreInMpBlocked(true)
                    true
                }
                "UNBLOCK_ML_IN_MP" -> {
                    policyManager.setMercadoLibreInMpBlocked(false)
                    true
                }

                "HIDE_APP" -> {
                    val appController = com.ejemplo.locksuite.mdm.AppController(this)
                    packagesList.all { appController.hideApp(it, true) }
                }
                "UNHIDE_APP" -> {
                    val appController = com.ejemplo.locksuite.mdm.AppController(this)
                    packagesList.all { appController.hideApp(it, false) }
                }
                "SUSPEND_APP" -> {
                    val appController = com.ejemplo.locksuite.mdm.AppController(this)
                    packagesList.all { appController.suspendApp(it, true) }
                }
                "UNSUSPEND_APP" -> {
                    val appController = com.ejemplo.locksuite.mdm.AppController(this)
                    packagesList.all { appController.suspendApp(it, false) }
                }
                "UNSUSPEND_ALL_APPS" -> {
                    val appController = com.ejemplo.locksuite.mdm.AppController(this)
                    val userApps = appController.getUserApps(loadIcon = false)
                    for (app in userApps) {
                        if (!app.isCritical) {
                            appController.suspendApp(app.packageName, false)
                        }
                    }
                    val prefs = com.ejemplo.locksuite.util.PrefsHelper.getMdmPrefs(this)
                    prefs.edit().remove("allowed_packages").apply()
                    policyManager.refreshInstallRestriction()
                    true
                }
                "BLOCK_WEBVIEW" -> {
                    packagesList.forEach { com.ejemplo.locksuite.mdm.WebViewBlockManager.setBlocked(this, it, true) }
                    true
                }
                "UNBLOCK_WEBVIEW" -> {
                    packagesList.forEach { com.ejemplo.locksuite.mdm.WebViewBlockManager.setBlocked(this, it, false) }
                    true
                }
                "BLOCK_APP_INTERNET" -> {
                    packagesList.forEach { policyManager.setPerAppInternetBlocked(it, true) }
                    true
                }
                "UNBLOCK_APP_INTERNET" -> {
                    packagesList.forEach { policyManager.setPerAppInternetBlocked(it, false) }
                    true
                }
                "SET_HIDE_SUSPENDED_APPS" -> {
                    val enabled = data["enabled"]?.toBoolean() ?: true
                    policyManager.setHideSuspendedApps(enabled)
                    true
                }
                "APPLY_PRESET_PROFILE" -> {
                    val presetJson = data["presetJson"]
                    if (!presetJson.isNullOrEmpty()) {
                        policyManager.importPolicyPresetJson(presetJson)
                    } else {
                        false
                    }
                }
                "SET_IMAGE_BLOCK_NONE" -> {
                    packagesList.forEach { com.ejemplo.locksuite.mdm.ImageBlockManager.setMode(this, it, "none") }
                    true
                }
                "SET_IMAGE_BLOCK_LAYER_1" -> {
                    packagesList.forEach { com.ejemplo.locksuite.mdm.ImageBlockManager.setMode(this, it, "layer1") }
                    true
                }
                "SET_IMAGE_BLOCK_LAYER_2" -> {
                    packagesList.forEach { com.ejemplo.locksuite.mdm.ImageBlockManager.setMode(this, it, "layer2") }
                    true
                }
                "SET_IMAGE_BLOCK_BOTH" -> {
                    packagesList.forEach { com.ejemplo.locksuite.mdm.ImageBlockManager.setMode(this, it, "both") }
                    true
                }
                "ENABLE_AI_MODE" -> {
                    com.ejemplo.locksuite.mdm.ImageBlockManager.setGlobalAiEnabled(this, true)
                    true
                }
                "DISABLE_AI_MODE" -> {
                    com.ejemplo.locksuite.mdm.ImageBlockManager.setGlobalAiEnabled(this, false)
                    com.ejemplo.locksuite.service.AIContentGate.releaseAll()
                    true
                }
                "ENABLE_MAPS_IMAGE_BLOCKING" -> {
                    com.ejemplo.locksuite.mdm.ImageBlockManager.setMapsImageBlockingEnabled(this, true)
                    true
                }
                "DISABLE_MAPS_IMAGE_BLOCKING" -> {
                    com.ejemplo.locksuite.mdm.ImageBlockManager.setMapsImageBlockingEnabled(this, false)
                    true
                }
                "UPDATE_ALLOWLIST" -> {
                    val prefs = com.ejemplo.locksuite.util.PrefsHelper.getMdmPrefs(this)
                    prefs.edit()
                        .putStringSet("allowed_packages", packagesList.toSet())
                        .apply()
                    policyManager.refreshInstallRestriction()
                    // 2/9/2026 — La lista de Lock Task se arma DESDE esta misma lista, así
                    // que hay que reaplicarla acá. Sin esto, una app recién permitida desde
                    // el panel no se podría abrir en un equipo con el kiosco encendido, y
                    // el síntoma sería "agregué la app y no aparece / no abre" sin ningún
                    // error a la vista.
                    policyManager.applyKioskLockTask(policyManager.isKioskLockTaskEnabled())
                    true
                }
                "UPDATE_APP" -> {
                    // Todo el flujo (destapar Play Store, levantar restricciones,
                    // armar el watchdog, tapar la pantalla, automatizar el clic y
                    // volver a bloquear) vive en UpdateFlowManager. Acá solo se
                    // traduce el resultado a un ACK para el panel.
                    val packageName = packagesList.firstOrNull()
                    if (packageName.isNullOrBlank()) {
                        commandErrorReason = "No package specified"
                        false
                    } else {
                        val error = com.ejemplo.locksuite.util.UpdateFlowManager.start(
                            context = this,
                            packageName = packageName,
                            source = com.ejemplo.locksuite.util.UpdateFlowManager.SOURCE_PANEL,
                            cancelable = true
                        )
                        if (error != null) {
                            commandErrorReason = error
                            false
                        } else {
                            true
                        }
                    }
                }
                "CANCEL_UPDATE_APP" -> {
                    if (com.ejemplo.locksuite.util.UpdateFlowManager.isRunning(this)) {
                        com.ejemplo.locksuite.util.UpdateFlowManager.requestCancel(this)
                        true
                    } else {
                        // Igual se fuerza la limpieza: si el panel manda cancelar es
                        // porque ve algo trabado, y el caso clásico era preferencias
                        // limpias con el overlay negro todavía en pantalla.
                        com.ejemplo.locksuite.util.UpdateFlowManager.forceCleanup(
                            this,
                            com.ejemplo.locksuite.util.UpdateFlowManager.RESULT_CANCELLED
                        )
                        true
                    }
                }
                "SUSPEND_LOCKSUITE" -> {
                    policyManager.setLockSuiteSuspended(true)
                }
                "RESUME_LOCKSUITE" -> {
                    policyManager.setLockSuiteSuspended(false)
                }
                "PROTECT_ACCESSIBILITY" -> {
                    policyManager.setAccessibilityProtection(true)
                }
                "UNPROTECT_ACCESSIBILITY" -> {
                    policyManager.setAccessibilityProtection(false)
                }

                // ── Sub-interruptores de Protecciones de Accesibilidad (17/8/2026) ──
                // Todos exigen PIN del dispositivo (no están en la excepción de
                // sendCommandV8) y quedan fuera de allowedWhileSuspended: mientras
                // LockSuite esté suspendido no se cambian políticas a medias.
                // Bloquear el cambio de idioma del sistema. Es la defensa más barata
                // contra la evasión por cambio de idioma: cualquier filtro que compare
                // texto de pantalla queda mudo si el equipo cambia a un idioma que no
                // está en las listas. Ver PolicyManager.setLocaleChangeBlocked().
                "BLOCK_LOCALE_CHANGE" -> policyManager.setLocaleChangeBlocked(true)
                "UNBLOCK_LOCALE_CHANGE" -> policyManager.setLocaleChangeBlocked(false)

                "ENABLE_ACC_BOUNCE_SETTINGS" -> policyManager.setAccBounceSettings(true)
                "DISABLE_ACC_BOUNCE_SETTINGS" -> policyManager.setAccBounceSettings(false)
                "ENABLE_ACC_NAG" -> policyManager.setAccNag(true)
                "DISABLE_ACC_NAG" -> policyManager.setAccNag(false)
                "ENABLE_ACC_SUSPEND_ALL" -> policyManager.setAccSuspendAll(true)
                "DISABLE_ACC_SUSPEND_ALL" -> policyManager.setAccSuspendAll(false)
                "ENABLE_BOOT_GATE_ACCESSIBILITY" -> policyManager.setBootGateWaitAccessibility(true)
                "DISABLE_BOOT_GATE_ACCESSIBILITY" -> policyManager.setBootGateWaitAccessibility(false)

                // ── Arranque protegido (ver util/BootGate.kt) ──
                "ENABLE_BOOT_GATE" -> policyManager.setBootGateEnabled(true)
                "DISABLE_BOOT_GATE" -> policyManager.setBootGateEnabled(false)

                // ── Bloqueo de imágenes: tapado estricto al desplazar ──
                "ENABLE_IMAGE_STRICT_SCROLL" -> policyManager.setImageBlockStrictScroll(true)
                "DISABLE_IMAGE_STRICT_SCROLL" -> policyManager.setImageBlockStrictScroll(false)
                "UPDATE_LOCKSUITE" -> {
                    val idToAck = commandId
                    commandId = null
                    kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.IO).launch {
                        val deviceId = com.ejemplo.locksuite.util.FirebaseDeviceSync.deviceId(applicationContext)
                        val baseRef = FirebaseDatabase.getInstance().reference
                        val progressRef = baseRef.child("devices/$deviceId/updateProgress")
                        
                        val error = com.ejemplo.locksuite.util.SelfUpdater.checkAndPerformUpdate(applicationContext, false) { progress ->
                            progressRef.setValue(progress)
                        }
                        
                        progressRef.removeValue()
                        
                        if (!idToAck.isNullOrBlank()) {
                            val ackRef = baseRef.child("devices/$deviceId/commandAcks/$idToAck")
                            val ackInfoRef = baseRef.child("devices/$deviceId/info/commandAcks/$idToAck")
                            if (error == null) {
                                val ackData = mapOf(
                                    "status" to "applied",
                                    "command" to "UPDATE_LOCKSUITE",
                                    "timestamp" to com.google.firebase.database.ServerValue.TIMESTAMP
                                )
                                ackRef.setValue(ackData)
                                ackInfoRef.setValue(ackData).addOnFailureListener {}
                            } else {
                                val ackData = mapOf(
                                    "status" to "failed",
                                    "reason" to error,
                                    "command" to "UPDATE_LOCKSUITE",
                                    "timestamp" to com.google.firebase.database.ServerValue.TIMESTAMP
                                )
                                ackRef.setValue(ackData)
                                ackInfoRef.setValue(ackData).addOnFailureListener {}
                            }
                        }
                    }
                    true
                }
                "CHANGE_PIN" -> {
                    val newHash = data["pinHash"]
                    val newSalt = data["pinSalt"]
                    if (!newHash.isNullOrBlank() && !newSalt.isNullOrBlank()) {
                        val isValidBase64 = try {
                            android.util.Base64.decode(newHash, android.util.Base64.NO_WRAP)
                            android.util.Base64.decode(newSalt, android.util.Base64.NO_WRAP)
                            true
                        } catch (e: Exception) {
                            false
                        }
                        if (isValidBase64) {
                            val prefs = com.ejemplo.locksuite.util.PrefsHelper.getEncryptedPrefs(this)
                            prefs.edit()
                                .putString(com.ejemplo.locksuite.util.Constants.KEY_PIN_HASH, newHash)
                                .putString(com.ejemplo.locksuite.util.Constants.KEY_PIN_SALT, newSalt)
                                .apply()
                            
                            // MED-11: Cerrar sesiones locales activas y resetear intentos fallidos
                            com.ejemplo.locksuite.security.SessionManager.closeSession()
                            com.ejemplo.locksuite.security.PinManager.resetAttempts(this)
                            true
                        } else {
                            commandErrorReason = "pinHash or pinSalt invalid format"
                            false
                        }
                    } else {
                        commandErrorReason = "pinHash or pinSalt empty"
                        false
                    }
                }
                else -> {
                    // ── Registro declarativo de restricciones (2/9/2026) ──
                    // Antes de dar el comando por desconocido, se busca en PolicySpec. Eso
                    // hace que agregar una restricción nueva NO requiera tocar este archivo:
                    // alcanza con sumarla a la lista de mdm/PolicySpec.kt. Es el patrón
                    // FeatureRegistry de A Bloq, y existe justamente para que no vuelva a
                    // pasar que una política quede a medio cablear entre los cinco lugares
                    // que había que tocar a mano.
                    val blockSpec = com.ejemplo.locksuite.mdm.PolicySpec.byBlockCommand(command)
                    val unblockSpec = com.ejemplo.locksuite.mdm.PolicySpec.byUnblockCommand(command)
                    when {
                        blockSpec != null -> {
                            if (!blockSpec.supportedHere) {
                                // Se aplica igual (queda guardada y va a regir si el equipo
                                // se actualiza), pero se dice que no rige HOY. Android
                                // acepta y descarta en silencio una restricción que no
                                // conoce: sin este aviso, el panel mostraría el interruptor
                                // encendido sobre un equipo donde no hace nada.
                                commandErrorReason = "Guardada, pero este equipo (Android API " +
                                    "${android.os.Build.VERSION.SDK_INT}) no soporta " +
                                    "${blockSpec.label}: requiere API ${blockSpec.minSdk}."
                            }
                            policyManager.setExtraRestriction(blockSpec, true)
                        }
                        unblockSpec != null -> policyManager.setExtraRestriction(unblockSpec, false)
                        else -> {
                            commandErrorReason = "Unknown command: $command"
                            false
                        }
                    }
                }
            }
        } catch (e: Exception) {
            e.printStackTrace()
            commandErrorReason = e.message ?: e.toString()
            success = false
        }

        // Registrar confirmación de ejecución del comando (Command ACK)
        if (!commandId.isNullOrBlank()) {
            try {
                val deviceId = com.ejemplo.locksuite.util.FirebaseDeviceSync.deviceId(this)
                val baseRef = FirebaseDatabase.getInstance().reference
                val status = if (success) "applied" else "failed"
                
                val ackData = mutableMapOf<String, Any>(
                    "status" to status,
                    "command" to command,
                    "timestamp" to com.google.firebase.database.ServerValue.TIMESTAMP
                )
                if (commandErrorReason != null) {
                    ackData["reason"] = commandErrorReason!!
                }
                baseRef.child("devices/$deviceId/commandAcks/$commandId").setValue(ackData)
                baseRef.child("devices/$deviceId/info/commandAcks/$commandId").setValue(ackData).addOnFailureListener {}
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }

        // Reportar el nuevo estado resultante a la base de datos para que se refleje de inmediato en el panel web
        try {
            com.ejemplo.locksuite.util.FirebaseDeviceSync.syncDeviceInfo(this)
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    /**
     * Escribe el ACK de un comando en Firebase. Extraído para poder responderle
     * al panel también cuando el comando se rechaza antes de entrar al `when`
     * (por ejemplo con LockSuite suspendido): sin esto, el panel se quedaba
     * esperando una confirmación que nunca llegaba.
     */
    private fun sendCommandAck(commandId: String?, command: String, success: Boolean, reason: String?) {
        if (commandId.isNullOrBlank()) return
        try {
            val deviceId = com.ejemplo.locksuite.util.FirebaseDeviceSync.deviceId(this)
            val baseRef = FirebaseDatabase.getInstance().reference
            val ackData = mutableMapOf<String, Any>(
                "status" to if (success) "applied" else "failed",
                "command" to command,
                "timestamp" to com.google.firebase.database.ServerValue.TIMESTAMP
            )
            if (!reason.isNullOrBlank()) ackData["reason"] = reason
            baseRef.child("devices/$deviceId/commandAcks/$commandId").setValue(ackData)
            baseRef.child("devices/$deviceId/info/commandAcks/$commandId").setValue(ackData).addOnFailureListener {}
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    /** Ack de un comando que ni siquiera llegó a ejecutarse: no pasó la autenticación. */
    private fun writeRejectionAck(commandId: String, command: String, reason: String) {
        try {
            val deviceId = com.ejemplo.locksuite.util.FirebaseDeviceSync.deviceId(this)
            val baseRef = FirebaseDatabase.getInstance().reference
            val ackData = mapOf(
                "status" to "rejected",
                "command" to command,
                "reason" to reason,
                "timestamp" to com.google.firebase.database.ServerValue.TIMESTAMP
            )
            baseRef.child("devices/$deviceId/commandAcks/$commandId").setValue(ackData)
            baseRef.child("devices/$deviceId/info/commandAcks/$commandId").setValue(ackData)
                .addOnFailureListener {}
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    /**
     * ¿El timestamp del comando está fuera de la ventana aceptable?
     *
     * ⚠️ 2/9/2026 — POR QUÉ ESTO YA NO ES `abs(now - t) > 5 min`.
     *
     * El timestamp lo pone la Cloud Function, con el reloj de Google. La comparación se
     * hacía contra `System.currentTimeMillis()`, que es el reloj de PARED DEL CELULAR.
     * En un equipo kosher sin SIM, sin hora de red, o después de quedarse sin batería, ese
     * reloj puede estar corrido horas o días — y entonces **TODOS los comandos del panel
     * se rechazan**, en silencio, para siempre. El equipo aparece en línea, el panel dice
     * "enviado", y no pasa nada. Es una de las causas de "no puedo controlarlo desde la
     * web", y es la más difícil de sospechar porque nada apunta al reloj.
     *
     * La protección contra repetición NO depende de este chequeo: la dan
     * `commandId` + `isReplay()` (registro de ids ya procesados) y, desde ahora, un piso
     * MONOTÓNICO — se recuerda el timestamp más alto aceptado y se rechaza cualquiera
     * muy anterior. Eso no necesita que el reloj del equipo esté bien, solo que el del
     * servidor avance, que es lo único que un atacante no puede retroceder.
     *
     * El chequeo absoluto se conserva, pero con ventana amplia (24 h) y solo cuando el
     * reloj del equipo es creíble. Si el reloj está claramente mal (anterior a 2024), se
     * omite: mejor obedecer con el reloj roto que quedar inadministrable.
     */
    private fun timestampOutOfWindow(timestamp: String): Boolean {
        val timeMs = timestamp.toLongOrNull() ?: return true
        if (timeMs <= 0L) return true

        val prefs = com.ejemplo.locksuite.util.PrefsHelper.getEncryptedPrefs(this)
        val lastAccepted = prefs.getLong(KEY_LAST_CMD_TIMESTAMP, 0L)
        // FCM puede reordenar entregas, así que se tolera hasta REORDER_SLACK_MS de
        // desorden; más viejo que eso es un mensaje reproducido.
        if (lastAccepted > 0L && timeMs < lastAccepted - REORDER_SLACK_MS) return true

        val now = System.currentTimeMillis()
        val clockIsCredible = now > CREDIBLE_CLOCK_FLOOR_MS
        if (clockIsCredible && Math.abs(now - timeMs) > ABSOLUTE_WINDOW_MS) return true

        return false
    }

    private fun verifyFcmSignature(data: Map<String, String>, timestamp: String, signature: String): Boolean {
        return try {
            // La ventana temporal ya la validó timestampOutOfWindow() antes de llegar acá.
            val secret = com.ejemplo.locksuite.util.FirebaseDeviceSync.getOrCreateCommandSecret(this)
            val mac = Mac.getInstance("HmacSHA256")
            val secretKey = SecretKeySpec(secret.toByteArray(), "HmacSHA256")
            mac.init(secretKey)
            
            val message = data
                .filterKeys { it != "signature" }
                .toSortedMap()
                .entries
                .joinToString("\n") { (key, value) -> "$key=$value" }
            val expectedBytes = mac.doFinal(message.toByteArray())
            val expectedSig = Base64.encodeToString(expectedBytes, Base64.NO_WRAP)

            MessageDigest.isEqual(expectedSig.toByteArray(), signature.toByteArray())
        } catch (e: Exception) {
            e.printStackTrace()
            false
        }
    }

    private fun isReplay(commandId: String): Boolean {
        val prefs = com.ejemplo.locksuite.util.PrefsHelper.getEncryptedPrefs(this)
        val processed = prefs.getStringSet("processed_command_ids", emptySet()) ?: emptySet()
        return processed.any { it.substringBeforeLast(':', "") == commandId }
    }

    /**
     * Registra el comando ya procesado junto a su timestamp de recepción y purga por
     * antigüedad real, no por conteo. Antes se guardaba un Set<String> de solo
     * commandId y se purgaba "el primero" (processed.first()) al pasar de 100
     * elementos — pero un Set recuperado de SharedPreferences NO garantiza orden de
     * inserción, así que podía borrar un comando recién procesado en vez de uno
     * viejo, reabriendo brevemente su ventana de repetición.
     */
    private fun recordCommand(commandId: String, timestampMs: Long) {
        val prefs = com.ejemplo.locksuite.util.PrefsHelper.getEncryptedPrefs(this)
        val lastAccepted = prefs.getLong(KEY_LAST_CMD_TIMESTAMP, 0L)
        if (timestampMs > lastAccepted) {
            prefs.edit().putLong(KEY_LAST_CMD_TIMESTAMP, timestampMs).apply()
        }
        val now = System.currentTimeMillis()
        // El doble de la ventana de 5 minutos que ya exige verifyFcmSignature: para
        // cuando un comando llega acá ya sabemos que tiene como máximo 5 min de
        // antigüedad, así que 10 min de retención da margen de sobra sin crecer sin límite.
        val maxAgeMs = 10 * 60 * 1000L
        val processed = (prefs.getStringSet("processed_command_ids", emptySet()) ?: emptySet())
            .mapNotNull { entry ->
                val id = entry.substringBeforeLast(':', "")
                val ts = entry.substringAfterLast(':', "").toLongOrNull()
                if (id.isEmpty() || ts == null) null else id to ts
            }
            .filter { (_, ts) -> now - ts < maxAgeMs }
            .map { (id, ts) -> "$id:$ts" }
            .toMutableSet()
        processed.add("$commandId:$now")
        prefs.edit().putStringSet("processed_command_ids", processed).apply()
    }

    override fun onNewToken(token: String) {
        try {
            com.ejemplo.locksuite.util.FirebaseDeviceSync.syncToken(this, token)
            com.ejemplo.locksuite.util.FirebaseDeviceSync.syncDeviceInfo(this)
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    private fun setStealthMode(enabled: Boolean) {
        val aliasComponent = ComponentName(this, "com.ejemplo.locksuite.LauncherAlias")
        val state = if (enabled) {
            PackageManager.COMPONENT_ENABLED_STATE_DISABLED
        } else {
            PackageManager.COMPONENT_ENABLED_STATE_ENABLED
        }
        try {
            packageManager.setComponentEnabledSetting(
                aliasComponent,
                state,
                PackageManager.DONT_KILL_APP
            )
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }
}
