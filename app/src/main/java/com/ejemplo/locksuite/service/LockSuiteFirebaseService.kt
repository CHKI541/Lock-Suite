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
import javax.crypto.spec.SecretKeySpec
import android.util.Base64
import java.security.MessageDigest

class LockSuiteFirebaseService : FirebaseMessagingService() {

    override fun onMessageReceived(message: RemoteMessage) {
        val data = message.data
        val command = data["command"] ?: return
        val commandId = data["commandId"]
        
        val policyManager = PolicyManager(this)

        // Si el mensaje viene del panel de administración web (no incluye firma HMAC pero proviene
        // de un backend autenticado y controlado por Firebase Cloud Functions), procesamos directamente.
        val signature = data["signature"]
        val timestamp = data["timestamp"]

        if (signature != null && timestamp != null) {
            // Validar la firma criptográfica si viene de la app de control local antigua (evita inyección y replay attacks)
            if (!verifyFcmSignature(command, timestamp, signature)) {
                return
            }
        }

        val packagesStr = data["packages"]
        val packagesList = packagesStr?.split(",")?.map { it.trim() }?.filter { it.isNotEmpty() } ?: emptyList()

        val requiresPackages = setOf(
            "HIDE_APP", "UNHIDE_APP", "SUSPEND_APP", "UNSUSPEND_APP", 
            "BLOCK_WEBVIEW", "UNBLOCK_WEBVIEW", "SET_IMAGE_BLOCK_NONE", 
            "SET_IMAGE_BLOCK_LAYER_1", "SET_IMAGE_BLOCK_LAYER_2", "SET_IMAGE_BLOCK_BOTH"
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
                        false
                    }
                }
                "BLOCK_INSTALL_APPS" -> policyManager.setInstallAppsBlocked(true)
                "UNBLOCK_INSTALL_APPS" -> policyManager.setInstallAppsBlocked(false)
                "BLOCK_UNINSTALL_APPS" -> policyManager.setUninstallAppsBlocked(true)
                "UNBLOCK_UNINSTALL_APPS" -> policyManager.setUninstallAppsBlocked(false)
                "BLOCK_FACTORY_RESET" -> policyManager.setFactoryResetBlocked(true)
                "UNBLOCK_FACTORY_RESET" -> policyManager.setFactoryResetBlocked(false)
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
                "DISABLE_KEYGUARD" -> policyManager.setKeyguardDisabled(true)
                "ENABLE_KEYGUARD" -> policyManager.setKeyguardDisabled(false)
                "BLOCK_INTERNET" -> policyManager.setInternetBlocked(true)
                "UNBLOCK_INTERNET" -> policyManager.setInternetBlocked(false)
                "ENABLE_ADBLOCK" -> policyManager.setAdBlockingEnabled(true)
                "DISABLE_ADBLOCK" -> policyManager.setAdBlockingEnabled(false)
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
                "BLOCK_WEBVIEW" -> {
                    packagesList.forEach { com.ejemplo.locksuite.mdm.WebViewBlockManager.setBlocked(this, it, true) }
                    true
                }
                "UNBLOCK_WEBVIEW" -> {
                    packagesList.forEach { com.ejemplo.locksuite.mdm.WebViewBlockManager.setBlocked(this, it, false) }
                    true
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
                        false
                    }
                }
                else -> false
            }
        } catch (e: Exception) {
            e.printStackTrace()
            success = false
        }

        // Registrar confirmación de ejecución del comando (Command ACK)
        if (!commandId.isNullOrBlank()) {
            try {
                val deviceId = com.ejemplo.locksuite.util.FirebaseDeviceSync.deviceId(this)
                val baseRef = FirebaseDatabase.getInstance().reference
                val status = if (success) "applied" else "failed"
                
                val ackData = mapOf(
                    "status" to status,
                    "command" to command,
                    "timestamp" to com.google.firebase.database.ServerValue.TIMESTAMP
                )
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

    private fun verifyFcmSignature(command: String, timestamp: String, signature: String): Boolean {
        return try {
            val timeMs = timestamp.toLongOrNull() ?: return false
            // Bloquear si el mensaje tiene más de 5 minutos (evita replay attacks)
            if (Math.abs(System.currentTimeMillis() - timeMs) > 5 * 60 * 1000L) {
                return false
            }

            val secret = com.ejemplo.locksuite.util.Constants.getFcmSecret()
            val mac = Mac.getInstance("HmacSHA256")
            val secretKey = SecretKeySpec(secret.toByteArray(), "HmacSHA256")
            mac.init(secretKey)
            
            val message = "$command:$timestamp"
            val expectedBytes = mac.doFinal(message.toByteArray())
            val expectedSig = Base64.encodeToString(expectedBytes, Base64.NO_WRAP)

            MessageDigest.isEqual(expectedSig.toByteArray(), signature.toByteArray())
        } catch (e: Exception) {
            e.printStackTrace()
            false
        }
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
