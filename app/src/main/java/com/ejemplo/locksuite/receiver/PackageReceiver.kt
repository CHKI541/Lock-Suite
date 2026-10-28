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

    companion object {
        /**
         * Paquete que la TIENDA ADMINISTRADA está instalando ahora (22/9/2026).
         *
         * `SelfUpdater.downloadAndInstallApk()` lo anota justo antes del commit. Sin esto,
         * con la instalación bloqueada, el bloque de "instalación no autorizada" de acá
         * abajo DESINSTALABA la app recién bajada de la Tienda: la Tienda instala paquetes
         * que están en `globalSettings/allowedPackages`, no en la lista local
         * `allowed_packages`, que es lo único que este receptor miraba.
         */
        const val KEY_STORE_INSTALL_PKG = "store_install_pkg"
        const val KEY_STORE_INSTALL_AT = "store_install_at"
        private const val KEY_STORE_INSTALL_PREFIX = "store_install_ts_"
        private const val STORE_INSTALL_WINDOW_MS = 15 * 60 * 1000L

        /** Registra un paquete que la Tienda o el instalador local está por instalar. */
        fun markStoreInstall(context: Context, packageName: String) {
            val now = System.currentTimeMillis()
            PrefsHelper.getMdmPrefs(context).edit()
                .putString(KEY_STORE_INSTALL_PKG, packageName)
                .putLong(KEY_STORE_INSTALL_AT, now)
                .putLong(KEY_STORE_INSTALL_PREFIX + packageName, now)
                .commit()
        }

        fun isStoreInstalling(context: Context, packageName: String): Boolean {
            val prefs = PrefsHelper.getMdmPrefs(context)
            val now = System.currentTimeMillis()
            val perPkgAt = prefs.getLong(KEY_STORE_INSTALL_PREFIX + packageName, 0L)
            if (perPkgAt > 0L && Math.abs(now - perPkgAt) < STORE_INSTALL_WINDOW_MS) {
                return true
            }
            val storePkg = prefs.getString(KEY_STORE_INSTALL_PKG, null)
            val storeAt = prefs.getLong(KEY_STORE_INSTALL_AT, 0L)
            return storePkg == packageName && Math.abs(now - storeAt) < STORE_INSTALL_WINDOW_MS
        }

        /** Antirrebote: en algunos equipos el mismo evento llega dos veces seguidas. */
        @Volatile private var lastEventKey: String = ""
        @Volatile private var lastEventAt: Long = 0L
    }

    override fun onReceive(context: Context, intent: Intent) {
        if (!com.ejemplo.locksuite.LockSuiteApplication.isStorageUnlocked(context)) return
        val action = intent.action
        Log.i("PackageReceiver", "Acción de paquete recibida: $action")

        // Mismo evento repetido en menos de 2 s (receptor de Manifest + el registrado en
        // tiempo de ejecución, o un fabricante que duplica el broadcast): se atiende una
        // sola vez. Desinstalar dos veces, o sincronizar dos veces, no suma nada.
        if (action == Intent.ACTION_PACKAGE_ADDED || action == Intent.ACTION_PACKAGE_REMOVED ||
            action == Intent.ACTION_PACKAGE_REPLACED) {
            val key = "$action|${intent.data?.schemeSpecificPart}|${intent.getBooleanExtra(Intent.EXTRA_REPLACING, false)}"
            val now = android.os.SystemClock.elapsedRealtime()
            synchronized(PackageReceiver::class.java) {
                if (key == lastEventKey && now - lastEventAt < 2_000L) return
                lastEventKey = key
                lastEventAt = now
            }
        }
        
        val prefs = PrefsHelper.getMdmPrefs(context)

        if (action == "INSTALL_SAFETY_TIMEOUT") {
            Log.w("PackageReceiver", "⏰ Timeout de seguridad de instalación alcanzado. Restaurando restricciones MDM...")
            try {
                if (!UpdateFlowManager.isRunning(context)) {
                    val policyManager = com.ejemplo.locksuite.mdm.PolicyManager(context)
                    policyManager.restoreInstallRestrictions()
                }
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
                    FirebaseDeviceSync.requestSync(context)
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
                val isSystemApp = try {
                    val ai = context.packageManager.getApplicationInfo(packageName, 0)
                    (ai.flags and (android.content.pm.ApplicationInfo.FLAG_SYSTEM or android.content.pm.ApplicationInfo.FLAG_UPDATED_SYSTEM_APP)) != 0
                } catch (_: Exception) {
                    false
                }
                val isWebViewOrTrichrome = packageName == "com.google.android.webview" ||
                    packageName == "com.android.webview" ||
                    packageName.startsWith("com.google.android.trichromelibrary")
                val storeInstalling = isStoreInstalling(context, packageName)
                val recentlyUpdated = UpdateFlowManager.wasRecentlyUpdated(context, packageName)
                val wlDecision = try {
                    com.ejemplo.locksuite.mdm.WhitelistManager(context).allDecisions()[packageName]
                } catch (_: Exception) { null }

                val isAllowed = allowed.contains(packageName) ||
                    wlDecision == com.ejemplo.locksuite.mdm.WhitelistManager.STATE_ALLOW ||
                    packageName == context.packageName ||
                    packageName == updatingPkg ||
                    recentlyUpdated ||
                    storeInstalling ||
                    isSystemApp ||
                    isWebViewOrTrichrome ||
                    appController.isCritical(packageName) ||
                    appController.isPartialBlockOnly(packageName)
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

            // 28/10/2026 (B.99) — ESTA ERA LA CAUSA DE "GOOGLE PLAY NO ESTABA AL FRENTE".
            // Destapar Play Store (lo hace el flujo de actualización para poder abrirla) es,
            // para Android, "instalarla": llega PACKAGE_ADDED de com.android.vending. Este
            // bloque la volvía a suspender y ocultar en el acto, Android la cerraba a los
            // 0,2 s y el flujo la reabría sin fin (logcat del QEMAY-QM01, 27/10: 81 cierres
            // "hiding pkg" en 2 minutos). Mientras dura una actualización por Google Play no
            // se toca: la vuelve a bloquear el cierre del flujo, como siempre.
            val playStoreDelFlujo = packageName == "com.android.vending" &&
                UpdateFlowManager.mustKeepPlayStoreOpen(context)
            if (playStoreDelFlujo) {
                Log.i("PackageReceiver", "Google Play destapada por la actualización en curso: no se re-bloquea ahora")
            }

            // Re-suspender de forma inmediata el paquete si fue instalado o actualizado y debe estar suspendido
            if (!playStoreDelFlujo &&
                (action == Intent.ACTION_PACKAGE_ADDED || action == Intent.ACTION_PACKAGE_REPLACED)
            ) {
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
                    
                    // 4. Si la app estaba suspendida individualmente por el administrador o es popular no kosher
                    val isPopularBlocked = policyManager.isBlockPopularNonKosherEnabled() &&
                        com.ejemplo.locksuite.mdm.PopularNonKosherApps.isPopularNonKosher(packageName)
                    val isIndividuallySuspended = prefs.getBoolean("suspend_$packageName", false)
                    if (isIndividuallySuspended || isPopularBlocked) {
                        appController.suspendApp(packageName, true)
                        if (policyManager.isHideSuspendedApps()) {
                            appController.hideApp(packageName, true)
                        }
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
                // Coalescida: una actualización de Play Store o una restauración dispara
                // decenas de eventos seguidos, y cada syncDeviceInfo enumera TODAS las apps.
                FirebaseDeviceSync.requestSync(context)
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
