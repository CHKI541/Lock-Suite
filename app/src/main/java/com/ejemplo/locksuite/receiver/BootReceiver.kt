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

        // 0. ARRANQUE PROTEGIDO — lo PRIMERO de todo, antes que cualquier otra cosa.
        //
        // Las restricciones de red las aplica nuestra VPN de filtrado, y esa VPN tarda
        // unos segundos en levantar después del arranque. En ese hueco el equipo tiene
        // internet sin filtrar. Acá se cierra la red con una sola llamada al sistema
        // (proxy global a un puerto muerto) y se vuelve a abrir recién cuando el túnel
        // está leyendo paquetes de verdad. El porqué completo, y qué cubre y qué no,
        // está en el comentario de cabecera de util/BootGate.kt.
        //
        // Va antes de reaplicar restricciones a propósito: reapplyAllRestrictions() hace
        // decenas de llamadas al DevicePolicyManager y puede tardar cientos de ms, que es
        // justo el tiempo que estamos tratando de cubrir.
        try {
            com.ejemplo.locksuite.util.BootGate.engage(context)
        } catch (e: Exception) {
            android.util.Log.e("BootReceiver", "Error activando el arranque protegido: ${e.message}")
        }

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

        // 3. Iniciar el servicio Watermark si el modo Kosher Launcher está activo y tiene permisos de overlay
        try {
            val policyManager = PolicyManager(context)
            if (policyManager.isKosherLauncherEnabled() && android.provider.Settings.canDrawOverlays(context)) {
                val watermarkIntent = Intent(context, com.ejemplo.locksuite.service.WatermarkService::class.java)
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    context.startForegroundService(watermarkIntent)
                } else {
                    context.startService(watermarkIntent)
                }
            }
        } catch (e: Exception) {
            android.util.Log.e("BootReceiver", "Error iniciando WatermarkService en boot: ${e.message}")
        }

        // 4. Garantizar que la VPN se inicie inmediatamente si está activa cualquier política que la requiera
        ensureVpnRunning(context)

    }

    companion object {
        /**
         * Determina si alguna política activa requiere que la VPN Kosher esté
         * corriendo. Extraído a una función compartida (antes esta condición
         * vivía duplicada/implícita) para que este chequeo y el que hace el
         * Watchdog para re-imponer DNS Privado periódicamente usen exactamente
         * el mismo criterio y no se desincronicen con el tiempo.
         */
        fun shouldVpnBeRunning(context: Context): Boolean {
            return try {
                val prefs = try {
                    PrefsHelper.getMdmPrefs(context)
                } catch (e: Exception) {
                    null
                }

                // Con LockSuite suspendido no corre ningún filtro de red: la VPN
                // de DNS es una restricción más y tiene que quedar apagada, si no
                // el Watchdog la volvería a levantar cada 20 segundos.
                if (prefs?.getBoolean("locksuite_suspended", false) == true) return false

                val isVpnConfigBlocked = prefs?.getBoolean(android.os.UserManager.DISALLOW_CONFIG_VPN, false) ?: false
                val hasAdBlocking = prefs?.getBoolean("global_ad_blocking", false) ?: false
                val hasGifsBlocked = prefs?.getBoolean("block_gifs", false) ?: false
                val hasWebViewBlocked = try {
                    com.ejemplo.locksuite.mdm.WebViewBlockManager.getBlockedPackages(context).isNotEmpty()
                } catch (e: Exception) {
                    false
                }
                val hasPerAppInternetBlocked = try {
                    com.ejemplo.locksuite.mdm.PolicyManager(context)
                        .getPerAppInternetBlockedPackages()
                        .isNotEmpty()
                } catch (e: Exception) {
                    false
                }
                val hasMercadoPagoVpnBlock = try {
                    com.ejemplo.locksuite.mdm.PolicyManager(context)
                        .isMercadoPagoBlockOffersVpnEnabled()
                } catch (e: Exception) {
                    false
                }
                // Reglas DNS personalizadas (seccion DNS del dashboard): si hay
                // al menos una, la VPN tiene que estar corriendo para poder
                // aplicarla. Antes no se chequeaba esto y una regla podia
                // quedar guardada sin efecto real si ninguna otra politica
                // (webview/adblock/gifs) mantenia la VPN activa.
                val hasCustomDnsRules = try {
                    com.ejemplo.locksuite.LockSuiteApplication.domainRuleManager.getAllRules().isNotEmpty()
                } catch (e: Exception) {
                    false
                }

                isVpnConfigBlocked || hasAdBlocking || hasGifsBlocked || hasWebViewBlocked ||
                    hasPerAppInternetBlocked || hasMercadoPagoVpnBlock || hasCustomDnsRules
            } catch (e: Exception) {
                android.util.Log.e("BootReceiver", "Fallo evaluando shouldVpnBeRunning: ${e.message}")
                false
            }
        }

        fun ensureVpnRunning(context: Context) {
            try {
                if (shouldVpnBeRunning(context)) {
                    val vpnIntent = Intent(context, com.ejemplo.locksuite.service.KosherVpnService::class.java)
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                        context.startForegroundService(vpnIntent)
                    } else {
                        context.startService(vpnIntent)
                    }
                    android.util.Log.i("BootReceiver", "Verificando KosherVpnService (shouldVpnBeRunning=true).")
                }
            } catch (e: Exception) {
                android.util.Log.e("BootReceiver", "Fallo al intentar iniciar KosherVpnService: ${e.message}")
            }
        }
    }
}
