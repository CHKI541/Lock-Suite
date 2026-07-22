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
        if (action != Intent.ACTION_BOOT_COMPLETED &&
            action != "android.intent.action.LOCKED_BOOT_COMPLETED" &&
            action != Intent.ACTION_MY_PACKAGE_REPLACED) return

        android.util.Log.i("BootReceiver", "Recibido broadcast de sistema: $action")

        // 1. Re-aplicar restricciones MDM de inmediato
        try {
            val policyManager = PolicyManager(context)
            policyManager.reapplyAllRestrictions()
        } catch (e: Exception) {
            android.util.Log.e("BootReceiver", "Error re-aplicando restricciones: ${e.message}")
        }
        
        // Sincronizar el estado del dispositivo con Firebase
        try {
            com.ejemplo.locksuite.util.FirebaseDeviceSync.syncDeviceInfo(context)
        } catch (e: Exception) {
            e.printStackTrace()
        }

        // 2. Iniciar el servicio Watchdog
        try {
            val serviceIntent = Intent(context, WatchdogForegroundService::class.java)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(serviceIntent)
            } else {
                context.startService(serviceIntent)
            }
        } catch (e: Exception) {
            android.util.Log.e("BootReceiver", "Error iniciando Watchdog: ${e.message}")
        }

        // 3. Garantizar que la VPN se inicie inmediatamente si está activa cualquier política que la requiera
        ensureVpnRunning(context)
    }

    companion object {
        fun ensureVpnRunning(context: Context) {
            try {
                val prefs = try {
                    PrefsHelper.getMdmPrefs(context)
                } catch (e: Exception) {
                    null
                }

                val isVpnConfigBlocked = prefs?.getBoolean(android.os.UserManager.DISALLOW_CONFIG_VPN, false) ?: false
                val hasAdBlocking = prefs?.getBoolean("global_ad_blocking", false) ?: false
                val hasGifsBlocked = prefs?.getBoolean("block_gifs", false) ?: false
                val hasWebViewBlocked = try {
                    com.ejemplo.locksuite.mdm.WebViewBlockManager.getBlockedPackages(context).isNotEmpty()
                } catch (e: Exception) {
                    false
                }

                if (isVpnConfigBlocked || hasAdBlocking || hasGifsBlocked || hasWebViewBlocked) {
                    val vpnIntent = Intent(context, com.ejemplo.locksuite.service.KosherVpnService::class.java)
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                        context.startForegroundService(vpnIntent)
                    } else {
                        context.startService(vpnIntent)
                    }
                    android.util.Log.i("BootReceiver", "Re-arrancando KosherVpnService (VpnBlocked=$isVpnConfigBlocked, WebView=$hasWebViewBlocked, AdBlock=$hasAdBlocking, Gifs=$hasGifsBlocked).")
                }
            } catch (e: Exception) {
                android.util.Log.e("BootReceiver", "Fallo al intentar iniciar KosherVpnService: ${e.message}")
            }
        }
    }
}

