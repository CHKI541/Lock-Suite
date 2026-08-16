package com.ejemplo.locksuite.service

import android.app.admin.DevicePolicyManager
import android.content.Context
import com.ejemplo.locksuite.mdm.PolicyManager
import com.ejemplo.locksuite.util.FirebaseDeviceSync
import com.google.firebase.messaging.FirebaseMessagingService
import com.google.firebase.messaging.RemoteMessage

/**
 * CAMBIO IMPORTANTE: se eliminaron los comandos remotos ENABLE_STEALTH /
 * DISABLE_STEALTH. Ese canal permitía ocultar o mostrar el ícono de la app
 * sin que quien tiene el teléfono en la mano hiciera nada — es exactamente
 * el tipo de capacidad de "comando y control" encubierto que no tiene lugar
 * en una herramienta de restricción consentida. El resto de los comandos
 * (bloqueo remoto, políticas, ahora también actualización de la lista de
 * apps permitidas) son gestión legítima de una flota de dispositivos.
 */
class LockSuiteFirebaseService : FirebaseMessagingService() {

    override fun onMessageReceived(message: RemoteMessage) {
        val command = message.data["command"] ?: return
        val policyManager = PolicyManager(this)

        when (command) {
            "LOCK_DEVICE" -> lockDeviceNow()
            "BLOCK_INSTALL_APPS" -> policyManager.setInstallAppsBlocked(true)
            "UNBLOCK_INSTALL_APPS" -> policyManager.setInstallAppsBlocked(false)
            "BLOCK_WIFI" -> policyManager.setWifiConfigBlocked(true)
            "UNBLOCK_WIFI" -> policyManager.setWifiConfigBlocked(false)
            "BLOCK_BLUETOOTH" -> policyManager.setBluetoothBlocked(true)
            "UNBLOCK_BLUETOOTH" -> policyManager.setBluetoothBlocked(false)
            "BLOCK_VPN" -> policyManager.setVpnConfigBlocked(true)
            "UNBLOCK_VPN" -> policyManager.setVpnConfigBlocked(false)
            "UPDATE_ALLOWLIST" -> updateAllowlist(message, policyManager)
        }

        // Confirma el estado resultante al backend, para que el panel
        // de administración lo refleje sin esperar a que se abra el Dashboard.
        FirebaseDeviceSync.syncDeviceInfo(this)
    }

    private fun lockDeviceNow() {
        try {
            val dpm = getSystemService(Context.DEVICE_POLICY_SERVICE) as DevicePolicyManager
            dpm.lockNow()
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    private fun updateAllowlist(message: RemoteMessage, policyManager: PolicyManager) {
        // Payload esperado: data["packages"] = "com.app.uno,com.app.dos,com.app.tres"
        val packages = message.data["packages"]
            ?.split(",")
            ?.map { it.trim() }
            ?.filter { it.isNotEmpty() }
            ?.toSet()
            ?: return
        policyManager.setAllowedPackages(packages)
    }

    override fun onNewToken(token: String) {
        FirebaseDeviceSync.syncToken(this, token)
        FirebaseDeviceSync.syncDeviceInfo(this)
    }
}
