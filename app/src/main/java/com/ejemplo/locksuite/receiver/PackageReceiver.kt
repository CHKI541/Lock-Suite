package com.ejemplo.locksuite.receiver

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import com.ejemplo.locksuite.mdm.AppController
import com.ejemplo.locksuite.util.FirebaseDeviceSync
import com.ejemplo.locksuite.util.PrefsHelper

class PackageReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val action = intent.action
        Log.i("PackageReceiver", "Acción de paquete recibida: $action")
        
        val prefs = PrefsHelper.getMdmPrefs(context)

        if (action == "UPDATE_TIMEOUT") {
            Log.w("PackageReceiver", "⏳ Tiempo límite de actualización alcanzado. Re-suspendiendo Google Play Store...")
            try {
                val policyManager = com.ejemplo.locksuite.mdm.PolicyManager(context)
                policyManager.refreshInstallRestriction()
            } catch (e: Exception) {
                e.printStackTrace()
            }
            prefs.edit().remove("updating_package").apply()
            return
        }

        val packageName = intent.data?.schemeSpecificPart ?: return

        val updatingPkg = prefs.getString("updating_package", null)
        if (action == Intent.ACTION_PACKAGE_REPLACED || action == Intent.ACTION_PACKAGE_ADDED) {
            if (updatingPkg != null && updatingPkg == packageName) {
                Log.i("PackageReceiver", "✅ Actualización/Instalación de $packageName completada. Re-suspendiendo Google Play Store...")
                try {
                    val policyManager = com.ejemplo.locksuite.mdm.PolicyManager(context)
                    policyManager.refreshInstallRestriction()
                } catch (e: Exception) {
                    e.printStackTrace()
                }
                prefs.edit().remove("updating_package").apply()
                cancelUpdateTimeoutAlarm(context)
            }
        }

        if (action == Intent.ACTION_PACKAGE_ADDED) {
            val isInstallBlocked = prefs.getBoolean("install_apps_blocked_admin", false) || prefs.getBoolean("install_blocked_programmatic", false)
            if (isInstallBlocked) {
                val allowed = prefs.getStringSet("allowed_packages", null) ?: emptySet()
                // Evitar desinstalar nuestra propia app o las apps permitidas
                val isAllowed = allowed.contains(packageName) || packageName == context.packageName
                if (!isAllowed) {
                    Log.w("PackageReceiver", "🚫 Intento de instalación no autorizado: $packageName. Desinstalando...")
                    try {
                        val appController = AppController(context)
                        appController.uninstallApp(packageName)
                    } catch (e: Exception) {
                        e.printStackTrace()
                    }
                    return
                }
            }
        }

        if (action == Intent.ACTION_PACKAGE_ADDED ||
            action == Intent.ACTION_PACKAGE_REMOVED ||
            action == Intent.ACTION_PACKAGE_REPLACED) {
            
            val isReplacing = intent.getBooleanExtra(Intent.EXTRA_REPLACING, false)
            // ACTION_PACKAGE_REMOVED se dispara con EXTRA_REPLACING = true cuando se está actualizando la app.
            // Para evitar doble sincronización durante una actualización (quitar + añadir),
            // ignoramos el REMOVED si es parte de un reemplazo.
            if (action == Intent.ACTION_PACKAGE_REMOVED && isReplacing) {
                return
            }

            try {
                Log.i("PackageReceiver", "Sincronizando información de apps tras cambio en los paquetes.")
                FirebaseDeviceSync.syncDeviceInfo(context)
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }
    }

    private fun cancelUpdateTimeoutAlarm(context: Context) {
        try {
            val watchdogIntent = Intent(context, PackageReceiver::class.java).apply {
                action = "UPDATE_TIMEOUT"
            }
            val pendingIntent = android.app.PendingIntent.getBroadcast(
                context,
                9911,
                watchdogIntent,
                android.app.PendingIntent.FLAG_UPDATE_CURRENT or android.app.PendingIntent.FLAG_IMMUTABLE
            )
            val alarmManager = context.getSystemService(Context.ALARM_SERVICE) as android.app.AlarmManager
            alarmManager.cancel(pendingIntent)
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }
}
