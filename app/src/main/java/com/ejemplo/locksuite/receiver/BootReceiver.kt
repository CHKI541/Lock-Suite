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
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

class BootReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        val action = intent.action

        if (!com.ejemplo.locksuite.LockSuiteApplication.isStorageUnlocked(context)) {
            android.util.Log.i("BootReceiver", "Almacenamiento bloqueado (Direct Boot) en $action. Esperando ACTION_BOOT_COMPLETED.")
            return
        }

        // Alarma de respaldo: si la ventana de arranque protegido venció sin que la VPN
        // o el Watchdog hayan podido confirmar el filtro, se libera el proxy huérfano.
        if (action == ACTION_BOOT_GATE_TIMEOUT) {
            android.util.Log.w("BootReceiver", "Alarma de seguridad de arranque protegido disparada. Verificando proxy huérfano.")
            com.ejemplo.locksuite.util.BootGate.healStuckProxy(context, "alarma de seguridad")
            return
        }

        if (action != Intent.ACTION_BOOT_COMPLETED &&
            action != "android.intent.action.LOCKED_BOOT_COMPLETED" &&
            action != Intent.ACTION_MY_PACKAGE_REPLACED) return

        android.util.Log.i("BootReceiver", "Recibido broadcast de sistema: $action")

        if (com.ejemplo.locksuite.util.PrefsHelper.getMdmPrefs(context).getBoolean("locksuite_purged", false)) {
            android.util.Log.i("BootReceiver", "Dispositivo purgado de emergencia. Ignorando arranque.")
            return
        }

        // 0. ARRANQUE PROTEGIDO — lo PRIMERO de todo, antes que cualquier otra cosa.
        try {
            com.ejemplo.locksuite.util.BootGate.engage(context)
        } catch (e: Exception) {
            android.util.Log.e("BootReceiver", "Error activando el arranque protegido: ${e.message}")
        }

        // 0b. Reglas DNS
        try {
            com.ejemplo.locksuite.LockSuiteApplication.ensureDomainRulesLoaded(context)
        } catch (e: Exception) {
            android.util.Log.w("BootReceiver", "No se pudieron cargar las reglas DNS: ${e.message}")
        }

        // 1. Iniciar los servicios INMEDIATAMENTE (Watchdog, VPN, Watermark)
        // No postergamos esto detrás de la reconciliación pesada de políticas: el proceso
        // necesita que el Watchdog y la VPN arranquen de inmediato para no arriesgar
        // que el arranque protegido quede clavado ni que Android cierre el proceso por ANR.
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

        ensureVpnRunning(context)

        try {
            val policyManager = PolicyManager(context)
            if (!policyManager.isLockSuiteSuspended() &&
                policyManager.isKosherLauncherEnabled() && android.provider.Settings.canDrawOverlays(context)) {
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

        // 2. Re-aplicar restricciones MDM y sincronizar FUERA del hilo principal.
        // reapplyAllRestrictions() realiza decenas de llamadas IPC a DevicePolicyManager
        // y chequeos en disco. Ejecutarlo síncrono en onReceive() bloqueaba el hilo principal
        // de la app durante varios segundos en equipos modestos (como Qin F21 Pro), arriesgando
        // un ANR de broadcast. goAsync() mantiene vivo el receptor hasta completar el trabajo.
        val pendingResult = goAsync()
        CoroutineScope(Dispatchers.IO).launch {
            try {
                val policyManager = PolicyManager(context)
                policyManager.reapplyAllRestrictions()
                com.ejemplo.locksuite.mdm.GracePeriodManager.checkAndHarden(context)
                com.ejemplo.locksuite.util.FirebaseDeviceSync.requestSync(context)
            } catch (e: Exception) {
                android.util.Log.e("BootReceiver", "Error en tareas asíncronas de boot: ${e.message}")
            } finally {
                try {
                    pendingResult.finish()
                } catch (ignored: Exception) { }
            }
        }
    }

    companion object {
        const val ACTION_BOOT_GATE_TIMEOUT = "com.ejemplo.locksuite.action.BOOT_GATE_TIMEOUT"

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

                if (prefs?.getBoolean("locksuite_purged", false) == true) {
                    return false
                }

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

        /**
         * @param force `true` ignora el espejo `isTunnelRunning` y manda arrancar igual.
         *   Es la red de seguridad para el caso en que el servicio esté vivo pero el túnel
         *   roto (por ejemplo `establish()` devolvió null y nadie lo reintentó). El
         *   Watchdog lo fuerza cada pocos minutos; el resto de las llamadas no.
         */
        @JvmOverloads
        fun ensureVpnRunning(context: Context, force: Boolean = false) {
            try {
                // 2/9/2026 (batería). Sin esta guarda se hacía un
                // startForegroundService() cada 20 s durante todo el día para un servicio
                // que ya estaba corriendo: puro IPC contra ActivityManagerService, más un
                // onStartCommand → startVpn() que sale por su propio `if (running) return`.
                // Ver KosherVpnService.isTunnelRunning.
                if (!force && com.ejemplo.locksuite.service.KosherVpnService.isTunnelRunning) {
                    return
                }
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
