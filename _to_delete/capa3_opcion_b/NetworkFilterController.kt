package com.ejemplo.locksuite.mdm

import android.app.admin.DevicePolicyManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import com.ejemplo.locksuite.receiver.DeviceAdminReceiver

object NetworkFilterController {

    private const val NETGUARD_PACKAGE = "eu.faircode.netguard"

    /**
     * Configura a NetGuard como el Always-On VPN en modo Lockdown.
     * Con esto, si NetGuard se cae o detiene, Android bloquea todo el tráfico de red.
     */
    fun enforceNetGuardAsVpn(context: Context): Boolean {
        val dpm = context.getSystemService(Context.DEVICE_POLICY_SERVICE) as DevicePolicyManager
        val admin = ComponentName(context, DeviceAdminReceiver::class.java)
        
        return try {
            dpm.setAlwaysOnVpnPackage(admin, NETGUARD_PACKAGE, true)
            android.util.Log.i("KosherVPN_B", "NetGuard configurado como Always-On VPN (Lockdown).")
            true
        } catch (e: PackageManager.NameNotFoundException) {
            android.util.Log.e("KosherVPN_B", "NetGuard no está instalado en el dispositivo.")
            false
        } catch (e: Exception) {
            e.printStackTrace()
            false
        }
    }

    /**
     * Envía un Broadcast o inicia el servicio de NetGuard para forzar la actualización
     * de su archivo de hosts (lista de dominios bloqueados).
     */
    fun refreshKosherHostsList(context: Context) {
        try {
            val intent = Intent("eu.faircode.netguard.DOWNLOAD_HOSTS_FILE").apply {
                setPackage(NETGUARD_PACKAGE)
            }
            context.startService(intent)
            android.util.Log.i("KosherVPN_B", "Intent de actualización de hosts enviado a NetGuard.")
        } catch (e: Exception) {
            android.util.Log.e("KosherVPN_B", "Fallo al enviar intent de actualización a NetGuard: ${e.message}")
        }
    }
}
