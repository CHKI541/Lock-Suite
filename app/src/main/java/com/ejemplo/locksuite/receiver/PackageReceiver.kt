package com.ejemplo.locksuite.receiver

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import com.ejemplo.locksuite.mdm.AppController
import com.ejemplo.locksuite.util.FirebaseDeviceSync
import com.ejemplo.locksuite.util.PrefsHelper
import com.ejemplo.locksuite.util.UpdateFlowManager

class PackageReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val action = intent.action
        Log.i("PackageReceiver", "Acción de paquete recibida: $action")
        
        val prefs = PrefsHelper.getMdmPrefs(context)

        if (action == "INSTALL_SAFETY_TIMEOUT") {
            Log.w("PackageReceiver", "⏰ Timeout de seguridad de instalación alcanzado. Restaurando restricciones MDM...")
            try {
                val policyManager = com.ejemplo.locksuite.mdm.PolicyManager(context)
                policyManager.restoreInstallRestrictions()
            } catch (e: Exception) {
                e.printStackTrace()
            }
            return
        }

        if (action == UpdateFlowManager.ACTION_TIMEOUT) {
            // BUG CORREGIDO (16/8/2026): este camino limpiaba las preferencias y
            // restauraba las restricciones, pero NO sacaba el overlay negro. Como
            // el overlay lo dibuja el servicio de accesibilidad y nadie más lo
            // tocaba, la pantalla "Actualizando..." quedaba fija para siempre y el
            // equipo no respondía a nada. Ese era exactamente el síntoma reportado.
            // Ahora se delega en el camino único de salida, que siempre lo saca.
            Log.w("PackageReceiver", "⏳ Tiempo límite de actualización alcanzado. Cerrando el flujo y re-bloqueando...")
            UpdateFlowManager.forceCleanup(context, UpdateFlowManager.RESULT_TIMEOUT)
            return
        }

        // Mientras LockSuite está suspendido no se re-suspende ni se desinstala
        // nada: el administrador levantó las restricciones a propósito.
        if (com.ejemplo.locksuite.mdm.PolicyManager(context).isLockSuiteSuspended()) {
            if (action == Intent.ACTION_PACKAGE_ADDED ||
                action == Intent.ACTION_PACKAGE_REMOVED ||
                action == Intent.ACTION_PACKAGE_REPLACED) {
                try {
                    FirebaseDeviceSync.syncDeviceInfo(context)
                } catch (e: Exception) {
                    e.printStackTrace()
                }
            }
            return
        }

        val packageName = intent.data?.schemeSpecificPart ?: return
        val isReplacing = intent.getBooleanExtra(Intent.EXTRA_REPLACING, false)

        val updatingPkg = prefs.getString("updating_package", null)
        if (action == Intent.ACTION_PACKAGE_REPLACED || action == Intent.ACTION_PACKAGE_ADDED) {
            if (updatingPkg != null && updatingPkg == packageName) {
                // Señal del sistema de que la instalación terminó de verdad.
                // Todo el cierre (overlay, HOME, restaurar restricciones, cancelar
                // la alarma) vive en un solo lugar para que no haya dos caminos de
                // salida que se pisen ni ninguno que se olvide del overlay.
                Log.i("PackageReceiver", "✅ Actualización/Instalación de $packageName completada. Cerrando el flujo...")
                UpdateFlowManager.finish(context, UpdateFlowManager.RESULT_UPDATED, null)
            }
        }

        if (action == Intent.ACTION_PACKAGE_ADDED && !isReplacing) {
            val isInstallBlocked = prefs.getBoolean("install_apps_blocked_admin", false) || prefs.getBoolean("install_blocked_programmatic", false)
            if (isInstallBlocked) {
                val allowed = prefs.getStringSet("allowed_packages", null) ?: emptySet()
                val appController = AppController(context)
                // Evitar desinstalar nuestra propia app, las apps permitidas, o
                // cualquier paquete crítico del sistema (systemUI, telefonía, GMS,
                // teclado, etc). ANTES solo se excluían "allowed_packages" (pensado
                // para apps de usuario de la tienda kosher) y la propia app: una
                // actualización silenciosa de un componente del sistema — p.ej.
                // Google Play Services actualizándose en segundo plano vía Play
                // Store — también dispara ACTION_PACKAGE_ADDED, y si ese paquete no
                // estaba en "allowed_packages" este receptor intentaba desinstalarlo
                // automáticamente. Desinstalar GMS rompería FCM (el canal de control
                // remoto) y gran parte del teléfono: exactamente lo que el requisito
                // de "nunca debe trabarse ni perder el control remoto" prohíbe.
                // updatingPkg también queda exento: si el paquete es justamente el
                // que el administrador mandó actualizar, desinstalarlo acá sería
                // deshacer la actualización que se acaba de pedir.
                val isAllowed = allowed.contains(packageName) || packageName == context.packageName ||
                    packageName == updatingPkg ||
                    appController.isCritical(packageName) || appController.isPartialBlockOnly(packageName)
                if (!isAllowed) {
                    Log.w("PackageReceiver", "🚫 Intento de instalación no autorizado: $packageName. Desinstalando...")
                    try {
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
            
            // ACTION_PACKAGE_REMOVED se dispara con EXTRA_REPLACING = true cuando se está actualizando la app.
            // Para evitar doble sincronización durante una actualización (quitar + añadir),
            // ignoramos el REMOVED si es parte de un reemplazo.
            if (action == Intent.ACTION_PACKAGE_REMOVED && isReplacing) {
                return
            }

            prefs.edit().putLong("launcher_packages_version", System.currentTimeMillis()).apply()

            // Re-suspender de forma inmediata el paquete si fue instalado o actualizado y debe estar suspendido
            if (action == Intent.ACTION_PACKAGE_ADDED || action == Intent.ACTION_PACKAGE_REPLACED) {
                try {
                    val appController = AppController(context)
                    val policyManager = com.ejemplo.locksuite.mdm.PolicyManager(context)
                    
                    // 1. Si es Play Store y debe estar suspendida
                    if (packageName == "com.android.vending") {
                        val shouldSuspendPlayStore = prefs.getBoolean("suspend_com.android.vending", prefs.getBoolean("install_apps_blocked_admin", false))
                        if (shouldSuspendPlayStore) {
                            appController.suspendApp(packageName, true)
                        }
                    }
                    
                    // 2. Si es un navegador y los navegadores deben estar suspendidos
                    if (policyManager.areBrowsersSuspended() && isPackageBrowser(context, packageName)) {
                        appController.suspendApp(packageName, true)
                    }
                    
                    // 3. Si es Android System WebView y debe estar suspendido
                    val isWebView = packageName == "com.google.android.webview" || packageName == "com.android.webview"
                    if (isWebView && policyManager.isSystemWebViewSuspended()) {
                        appController.suspendApp(packageName, true)
                    }
                    
                    // 4. Si la app estaba suspendida individualmente por el administrador
                    val isIndividuallySuspended = prefs.getBoolean("suspend_$packageName", false)
                    if (isIndividuallySuspended) {
                        appController.suspendApp(packageName, true)
                    }

                    // 5. Si la app estaba oculta explícitamente por el administrador
                    if (prefs.getBoolean("hide_$packageName", false)) {
                        appController.hideApp(packageName, true)
                    }
                } catch (e: Exception) {
                    Log.e("PackageReceiver", "Error al re-suspender paquete tras actualización: $packageName", e)
                }
            }

            try {
                Log.i("PackageReceiver", "Sincronizando información de apps tras cambio en los paquetes.")
                FirebaseDeviceSync.syncDeviceInfo(context)
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }
    }

    private fun isPackageBrowser(context: Context, packageName: String): Boolean {
        val pm = context.packageManager
        val intent = Intent(Intent.ACTION_VIEW, android.net.Uri.parse("https://www.google.com"))
        val list = if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.TIRAMISU) {
            pm.queryIntentActivities(intent, android.content.pm.PackageManager.ResolveInfoFlags.of(android.content.pm.PackageManager.MATCH_ALL.toLong()))
        } else {
            @Suppress("DEPRECATION")
            pm.queryIntentActivities(intent, android.content.pm.PackageManager.MATCH_ALL)
        }
        return list.any { it.activityInfo.packageName == packageName }
    }

}
