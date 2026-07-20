package com.ejemplo.locksuite.receiver

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import com.ejemplo.locksuite.mdm.PolicyManager
import com.ejemplo.locksuite.service.WatchdogForegroundService
import com.ejemplo.locksuite.ui.auth.LoginActivity
import com.ejemplo.locksuite.util.Constants
import com.ejemplo.locksuite.util.PrefsHelper

class BootReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        val action = intent.action
        if (action != Intent.ACTION_BOOT_COMPLETED && action != "android.intent.action.LOCKED_BOOT_COMPLETED") return

        // 1. Re-aplicar restricciones MDM de inmediato
        val policyManager = PolicyManager(context)
        policyManager.reapplyAllRestrictions()
        
        // Sincronizar el estado del dispositivo con Firebase
        try {
            com.ejemplo.locksuite.util.FirebaseDeviceSync.syncDeviceInfo(context)
        } catch (e: Exception) {
            e.printStackTrace()
        }

        // 2. Iniciar el servicio Watchdog
        val serviceIntent = Intent(context, WatchdogForegroundService::class.java)
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(serviceIntent)
            } else {
                context.startService(serviceIntent)
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }



        // 4. Reiniciar la VPN si hay bloqueo de WebView activo o bloqueo de anuncios global activo
        try {
            val hasWebViewBlocked = com.ejemplo.locksuite.mdm.WebViewBlockManager.getBlockedPackages(context).isNotEmpty()
            val hasAdBlocking = com.ejemplo.locksuite.util.PrefsHelper.getMdmPrefs(context).getBoolean("global_ad_blocking", false)
            val hasGifsBlocked = com.ejemplo.locksuite.util.PrefsHelper.getMdmPrefs(context).getBoolean("block_gifs", false)
            
            if (hasWebViewBlocked || hasAdBlocking || hasGifsBlocked) {
                val vpnIntent = Intent(context, com.ejemplo.locksuite.service.KosherVpnService::class.java)
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    context.startForegroundService(vpnIntent)
                } else {
                    context.startService(vpnIntent)
                }
                android.util.Log.i("BootReceiver", "Re-arrancando KosherVpnService tras boot (WebView=$hasWebViewBlocked, AdBlock=$hasAdBlocking, Gifs=$hasGifsBlocked).")
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }
}
