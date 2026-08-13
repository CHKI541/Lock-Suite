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

    override fun onMessageReceived(message: RemoteMessage) {
        val data = message.data
        val command = data["command"] ?: return
        var commandId = data["commandId"]
        
        val policyManager = PolicyManager(this)

        // Todo comando exige firma HMAC por dispositivo + timestamp + commandId, venga del
        // panel web (vía Cloud Functions) o de cualquier otro origen — no existe una vía "de
        // confianza implícita" sin firmar; las condiciones de abajo lo exigen siempre.
        val signature = data["signature"]
        val timestamp = data["timestamp"]
        if (commandId.isNullOrBlank() || signature.isNullOrBlank() || timestamp.isNullOrBlank() ||
            !verifyFcmSignature(data, timestamp, signature) ||
            isReplay(commandId)
        ) {
            android.util.Log.w("LockSuiteFCM", "Comando FCM rechazado: autenticación o replay inválido.")
            return
        }
        recordCommand(commandId)

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
                    true
                }
                "UPDATE_APP" -> {
                    val packageName = packagesList.firstOrNull()
                    if (packageName != null) {
                        try {
                            val localDpm = getSystemService(Context.DEVICE_POLICY_SERVICE) as android.app.admin.DevicePolicyManager
                            val localAdminComponent = android.content.ComponentName(this, com.ejemplo.locksuite.receiver.DeviceAdminReceiver::class.java)
                            
                            // 1. Des-suspender Play Store (silenciosamente si falla por no estar instalado)
                            try {
                                localDpm.setPackagesSuspended(localAdminComponent, arrayOf("com.android.vending"), false)
                            } catch (e: Exception) {
                                android.util.Log.w("LockSuiteFCM", "No se pudo des-suspender Play Store: ${e.message}")
                            }
                            
                            // 2. Levantar restricciones de instalación temporalmente y marcar en progreso
                            val prefs = com.ejemplo.locksuite.util.PrefsHelper.getMdmPrefs(this)
                            prefs.edit()
                                .putString("updating_package", packageName)
                                .putBoolean("mdm_install_in_progress", true)
                                .apply()
                            
                            try {
                                localDpm.clearUserRestriction(localAdminComponent, android.os.UserManager.DISALLOW_INSTALL_APPS)
                                localDpm.clearUserRestriction(localAdminComponent, android.os.UserManager.DISALLOW_INSTALL_UNKNOWN_SOURCES)
                            } catch (e: Exception) {
                                android.util.Log.w("LockSuiteFCM", "No se pudieron remover restricciones de instalación: ${e.message}")
                            }
                            
                            // 3. Abrir Play Store con la app correspondiente, fallback al navegador si falla
                            try {
                                val playStoreIntent = android.content.Intent(
                                    android.content.Intent.ACTION_VIEW,
                                    android.net.Uri.parse("market://details?id=$packageName")
                                ).apply {
                                    addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK or android.content.Intent.FLAG_ACTIVITY_CLEAR_TASK)
                                }
                                startActivity(playStoreIntent)
                            } catch (e: android.content.ActivityNotFoundException) {
                                try {
                                    val webIntent = android.content.Intent(
                                        android.content.Intent.ACTION_VIEW,
                                        android.net.Uri.parse("https://play.google.com/store/apps/details?id=$packageName")
                                    ).apply {
                                        addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK or android.content.Intent.FLAG_ACTIVITY_CLEAR_TASK)
                                    }
                                    startActivity(webIntent)
                                } catch (ex: Exception) {
                                    throw Exception("No se encontró Play Store ni navegador para actualizar: ${ex.message}")
                                }
                            } catch (e: Exception) {
                                throw Exception("Error al abrir Play Store: ${e.message}")
                            }
                            
                            // 4. Programar temporizador de seguridad (watchdog) de 10 minutos
                            try {
                                val watchdogIntent = android.content.Intent(this, com.ejemplo.locksuite.receiver.PackageReceiver::class.java).apply {
                                    action = "UPDATE_TIMEOUT"
                                }
                                val pendingIntent = android.app.PendingIntent.getBroadcast(
                                    this,
                                    9911,
                                    watchdogIntent,
                                    android.app.PendingIntent.FLAG_UPDATE_CURRENT or android.app.PendingIntent.FLAG_IMMUTABLE
                                )
                                val alarmManager = getSystemService(Context.ALARM_SERVICE) as android.app.AlarmManager
                                val triggerTime = android.os.SystemClock.elapsedRealtime() + 10 * 60 * 1000L
                                alarmManager.set(android.app.AlarmManager.ELAPSED_REALTIME_WAKEUP, triggerTime, pendingIntent)
                            } catch (e: Exception) {
                                android.util.Log.w("LockSuiteFCM", "No se pudo programar el temporizador watchdog: ${e.message}")
                            }
                            true
                        } catch (e: Exception) {
                            e.printStackTrace()
                            commandErrorReason = e.message ?: e.toString()
                            false
                        }
                    } else {
                        commandErrorReason = "No package specified"
                        false
                    }
                }
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
                        commandErrorReason = "pinHash or pinSalt empty"
                        false
                    }
                }
                else -> {
                    commandErrorReason = "Unknown command: $command"
                    false
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

    private fun verifyFcmSignature(data: Map<String, String>, timestamp: String, signature: String): Boolean {
        return try {
            val timeMs = timestamp.toLongOrNull() ?: return false
            // Bloquear si el mensaje tiene más de 5 minutos (evita replay attacks)
            if (Math.abs(System.currentTimeMillis() - timeMs) > 5 * 60 * 1000L) {
                return false
            }

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
    private fun recordCommand(commandId: String) {
        val prefs = com.ejemplo.locksuite.util.PrefsHelper.getEncryptedPrefs(this)
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
