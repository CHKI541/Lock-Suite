package com.ejemplo.locksuite.util

// FirebaseDeviceSync.kt — CORREGIDO
//
// Cambios respecto al original que mandaste (todo lo demás es idéntico,
// línea por línea, para que el diff sea mínimo):
//
//   1) syncDeviceInfo(): la rama "else" del fetch del token (cuando
//      task.isSuccessful es false) no existía. Si Play Services está
//      desactualizado/restringido — típico en un celular kosher bloqueado —
//      el token nunca se genera y esto quedaba en silencio absoluto, para
//      siempre, build tras build. Ahora loguea la excepción real y además
//      escribe un campo "fcmTokenError" en la DB (raíz + info) para que
//      puedas ver la causa sin conectar el celular por USB.
//
//   2) syncToken() y writeFields(): sus updateChildren() no tenían
//      addOnFailureListener. El try/catch de Kotlin NO atrapa fallos
//      asincrónicos de Firebase (ej. permission-denied de las reglas) —
//      solo excepciones lanzadas de forma síncrona. Sin esto, un rechazo
//      de las reglas de seguridad también sería silencioso.

import android.content.Context
import android.os.Build
import android.provider.Settings
import com.ejemplo.locksuite.mdm.PolicyManager
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.database.FirebaseDatabase
import com.google.firebase.database.ServerValue

object FirebaseDeviceSync {

    fun deviceId(context: Context): String =
        Settings.Secure.getString(context.contentResolver, Settings.Secure.ANDROID_ID)

    fun syncToken(context: Context, token: String) {
        withAuth {
            val ref = FirebaseDatabase.getInstance().getReference("devices/${deviceId(context)}")
            ref.updateChildren(mapOf(
                "fcmToken" to token,
                "info/fcmToken" to token,
                "lastSeen" to ServerValue.TIMESTAMP,
                "info/lastSeen" to ServerValue.TIMESTAMP
            )).addOnFailureListener { it.printStackTrace() }
        }
    }

    /**
     * Escribe únicamente el timestamp de lastSeen. Llamar al arrancar la app para
     * aparecer "En línea" en el panel web de forma casi instantánea, sin esperar a
     * que se complete la sincronización completa del estado del dispositivo.
     */
    fun syncLastSeenOnly(context: Context) {
        withAuth {
            val ref = FirebaseDatabase.getInstance().getReference("devices/${deviceId(context)}")
            ref.updateChildren(mapOf(
                "lastSeen" to ServerValue.TIMESTAMP,
                "info/lastSeen" to ServerValue.TIMESTAMP
            )).addOnFailureListener { it.printStackTrace() }
        }
    }

    fun syncPinCredentials(context: Context, pinHash: String, pinSalt: String) {
        withAuth {
            // 1. Escribir credenciales reales en la ruta protegida deviceSecrets
            val secretsRef = FirebaseDatabase.getInstance().getReference("deviceSecrets/${deviceId(context)}")
            secretsRef.updateChildren(
                mapOf(
                    "pinHash" to pinHash,
                    "pinSalt" to pinSalt
                )
            ).addOnFailureListener { it.printStackTrace() }

            // 2. Escribir solo la bandera hasPinConfigured en la ruta pública
            val publicRef = FirebaseDatabase.getInstance().getReference("devices/${deviceId(context)}")
            publicRef.updateChildren(
                mapOf(
                    "hasPinConfigured" to true,
                    "info/hasPinConfigured" to true
                )
            ).addOnFailureListener { it.printStackTrace() }
        }
    }

    fun syncDeviceInfo(context: Context) {
        val policyManager = PolicyManager(context)
        val dpm = context.getSystemService(Context.DEVICE_POLICY_SERVICE) as android.app.admin.DevicePolicyManager
        val prefs = PrefsHelper.getMdmPrefs(context)
        val deviceName = prefs.getString("device_name", "") ?: ""

        val aliasComponent = android.content.ComponentName(context, "com.ejemplo.locksuite.LauncherAlias")
        val isStealth = try {
            val state = context.packageManager.getComponentEnabledSetting(aliasComponent)
            state == android.content.pm.PackageManager.COMPONENT_ENABLED_STATE_DISABLED
        } catch (e: Exception) {
            false
        }

        try {
            com.google.firebase.messaging.FirebaseMessaging.getInstance().token
                .addOnCompleteListener { task ->
                    if (task.isSuccessful) {
                        task.result?.let { token ->
                            syncToken(context, token)
                        }
                    } else {
                        // FIX #1: ver nota al principio del archivo.
                        val reason = task.exception?.message ?: "desconocido"
                        task.exception?.printStackTrace()
                        withAuth {
                            val ref = FirebaseDatabase.getInstance().getReference("devices/${deviceId(context)}")
                            ref.updateChildren(
                                mapOf(
                                    "fcmTokenError" to reason,
                                    "info/fcmTokenError" to reason
                                )
                            ).addOnFailureListener { it.printStackTrace() }
                        }
                    }
                }
        } catch (e: Exception) {
            e.printStackTrace()
        }

        withAuth {
            writeFields(
                context,
                mapOf(
                    "deviceName" to deviceName,
                    "model" to "${Build.MANUFACTURER} ${Build.MODEL}",
                    "isDeviceOwner" to dpm.isDeviceOwnerApp(context.packageName),
                    "kioskEnabled" to false,
                    "allowedAppCount" to 0,

                    "wifiBlocked" to policyManager.isRestrictionEnabled(android.os.UserManager.DISALLOW_CONFIG_WIFI),
                    "bluetoothBlocked" to policyManager.isRestrictionEnabled(android.os.UserManager.DISALLOW_BLUETOOTH),
                    "vpnBlocked" to policyManager.isRestrictionEnabled(android.os.UserManager.DISALLOW_CONFIG_VPN),
                    "installAppsBlocked" to policyManager.isRestrictionEnabled(android.os.UserManager.DISALLOW_INSTALL_APPS),
                    "uninstallAppsBlocked" to policyManager.isRestrictionEnabled(android.os.UserManager.DISALLOW_UNINSTALL_APPS),
                    "factoryResetBlocked" to policyManager.isRestrictionEnabled(android.os.UserManager.DISALLOW_FACTORY_RESET),
                    "adbBlocked" to policyManager.isRestrictionEnabled(android.os.UserManager.DISALLOW_DEBUGGING_FEATURES),
                    "userSwitchBlocked" to policyManager.isRestrictionEnabled(android.os.UserManager.DISALLOW_USER_SWITCH),
                    "modifyAccountsBlocked" to policyManager.isRestrictionEnabled(android.os.UserManager.DISALLOW_MODIFY_ACCOUNTS),
                    "safeBootBlocked" to policyManager.isRestrictionEnabled(android.os.UserManager.DISALLOW_SAFE_BOOT),
                    "unknownSourcesBlocked" to policyManager.isRestrictionEnabled(android.os.UserManager.DISALLOW_INSTALL_UNKNOWN_SOURCES),
                    "adjustVolumeBlocked" to policyManager.isRestrictionEnabled(android.os.UserManager.DISALLOW_ADJUST_VOLUME),
                    "appsControlBlocked" to policyManager.isRestrictionEnabled(android.os.UserManager.DISALLOW_APPS_CONTROL),
                    "bluetoothSharingBlocked" to policyManager.isRestrictionEnabled(android.os.UserManager.DISALLOW_BLUETOOTH_SHARING),
                    "externalMediaBlocked" to policyManager.isRestrictionEnabled(android.os.UserManager.DISALLOW_MOUNT_PHYSICAL_MEDIA),
                    "tetheringBlocked" to policyManager.isRestrictionEnabled(android.os.UserManager.DISALLOW_CONFIG_TETHERING),

                    "cameraDisabled" to policyManager.isCameraDisabled(),
                    "screenCaptureBlocked" to policyManager.isScreenCaptureBlocked(),
                    "statusBarDisabled" to policyManager.isStatusBarDisabled(),
                    "keyguardDisabled" to policyManager.isKeyguardDisabled(),
                    "internetBlocked" to policyManager.isInternetBlocked(),
                    "adBlockingEnabled" to policyManager.isAdBlockingEnabled(),
                    "aiModeEnabled" to com.ejemplo.locksuite.mdm.ImageBlockManager.isGlobalAiEnabled(context),
                    "mapsImageBlockingEnabled" to com.ejemplo.locksuite.mdm.ImageBlockManager.isMapsImageBlockingEnabled(context),
                    "whatsappBlockStatus" to policyManager.isWhatsAppBlockStatusEnabled(),
                    "whatsappBlockChannels" to policyManager.isWhatsAppBlockChannelsEnabled(),
                    "stealthModeEnabled" to isStealth
                )
            )
            syncAppsListInternal(context)
        }
    }

    private fun syncAppsListInternal(context: Context) {
        try {
            val appController = com.ejemplo.locksuite.mdm.AppController(context)
            val apps = appController.getUserApps(loadIcon = false)
            val appsMap = mutableMapOf<String, Any>()
            for (app in apps) {
                val safePackageName = app.packageName.replace(".", "_")
                appsMap[safePackageName] = mapOf(
                    "packageName" to app.packageName,
                    "label" to app.label,
                    "isHidden" to app.isHidden,
                    "isSuspended" to app.isSuspended,
                    "isWebViewBlocked" to app.isWebViewBlocked,
                    "imageBlockingMode" to app.imageBlockingMode,
                    "appType" to app.appType,
                    "isCritical" to app.isCritical
                )
            }
            val baseRef = FirebaseDatabase.getInstance().reference
            val deviceId = deviceId(context)
            baseRef.child("devices/$deviceId/apps").setValue(appsMap)
            baseRef.child("devices/$deviceId/info/apps").setValue(appsMap)
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    private fun writeFields(context: Context, fields: Map<String, Any>) {
        val ref = FirebaseDatabase.getInstance().getReference("devices/${deviceId(context)}")
        val payload = mutableMapOf<String, Any>()
        fields.forEach { (key, value) ->
            payload[key] = value
            payload["info/$key"] = value
        }
        payload["lastSeen"] = ServerValue.TIMESTAMP
        payload["info/lastSeen"] = ServerValue.TIMESTAMP
        try {
            ref.updateChildren(payload)
                .addOnFailureListener { it.printStackTrace() }
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    private fun withAuth(action: () -> Unit) {
        val auth = FirebaseAuth.getInstance()
        if (auth.currentUser != null) {
            // Reutilizar sesión existente: no re-autenticar en cada ciclo
            action()
            return
        }
        // Primera vez: iniciar sesión anónima (requiere Anonymous Auth habilitado en Firebase Console)
        auth.signInAnonymously()
            .addOnSuccessListener { action() }
            .addOnFailureListener { e ->
                // Loguear el error real para facilitar el diagnóstico
                android.util.Log.e("FirebaseDeviceSync", "signInAnonymously falló: ${e.message}", e)
                // Intentar igual — si hay reglas permisivas podría funcionar sin auth
                action()
            }
    }
}
