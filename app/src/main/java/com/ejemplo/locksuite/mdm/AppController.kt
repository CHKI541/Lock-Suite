package com.ejemplo.locksuite.mdm

import android.app.PendingIntent
import android.app.admin.DevicePolicyManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.content.pm.PackageInstaller
import android.content.pm.PackageManager
import android.os.Build
import android.graphics.Bitmap
import androidx.core.graphics.drawable.toBitmap
import com.ejemplo.locksuite.receiver.DeviceAdminReceiver
import com.ejemplo.locksuite.util.PrefsHelper

class AppController(private val context: Context) {

    private val dpm = context.getSystemService(Context.DEVICE_POLICY_SERVICE) as DevicePolicyManager
    private val pm = context.packageManager
    private val adminComponent = ComponentName(context, DeviceAdminReceiver::class.java)

    private val systemEssential = setOf(
        "com.android.systemui", 
        "com.android.settings",
        "com.android.phone", 
        "com.android.providers.telephony",
        "com.ejemplo.locksuite",
        "com.google.android.gms",
        "com.google.android.gsf",
        "com.google.android.packageinstaller",
        "com.android.packageinstaller",
        "com.sec.android.inputmethod" // Samsung Keyboard — no bloquear ocultar/suspender
    )

    // Apps que NO se pueden ocultar/suspender (rompería el sistema) pero SÍ pueden
    // tener restricciones de contenido: bloqueo de WebView y de imágenes.
    private val partialBlockOnly = setOf(
        "com.google.android.inputmethod.latin" // Gboard
    )

    fun isCritical(packageName: String): Boolean {
        return packageName in systemEssential || getLauncherPackages().contains(packageName)
    }

    // Devuelve true si la app NO puede ocultarse ni suspenderse (pero sí puede tener
    // restricciones de contenido como WebView o imágenes).
    fun isPartialBlockOnly(packageName: String): Boolean {
        return packageName in partialBlockOnly
    }

    fun hideApp(packageName: String, hide: Boolean): Boolean {
        if (isCritical(packageName) || isPartialBlockOnly(packageName)) {
            // Si la app es crítica o especial y se solicita des-ocultarla (hide = false), el estado deseado
            // ya se cumple (no está oculta por protección del sistema) -> retornar true (éxito).
            // Si se solicita ocultarla (hide = true), se rechaza por seguridad del SO -> retornar false.
            return !hide
        }
        return try {
            dpm.setApplicationHidden(adminComponent, packageName, hide)
            PrefsHelper.getMdmPrefs(context).edit().putBoolean("hide_$packageName", hide).apply()
            
            // Si des-ocultamos la app y estaba marcada como suspendida, aplicamos la suspensión física en este momento
            if (!hide) {
                val shouldSuspend = PrefsHelper.getMdmPrefs(context).getBoolean("suspend_$packageName", false)
                if (shouldSuspend) {
                    try {
                        dpm.setPackagesSuspended(adminComponent, arrayOf(packageName), true)
                    } catch (e: Exception) {
                        e.printStackTrace()
                    }
                }
            }
            true
        } catch (e: Exception) {
            e.printStackTrace()
            !hide
        }
    }

    fun isAppHidden(packageName: String): Boolean {
        return try {
            dpm.isApplicationHidden(adminComponent, packageName)
        } catch (e: Exception) {
            PrefsHelper.getMdmPrefs(context).getBoolean("hide_$packageName", false)
        }
    }

    fun suspendApp(packageName: String, suspend: Boolean): Boolean {
        if (isCritical(packageName) || isPartialBlockOnly(packageName)) {
            // Si la app es crítica y se solicita des-suspenderla (suspend = false), el estado deseado
            // ya se cumple (no está suspended) -> retornar true (éxito).
            return !suspend
        }
        
        android.util.Log.i("AppController", "suspendApp: $packageName -> suspend=$suspend")
        // Siempre guardar la preferencia de estado deseado
        PrefsHelper.getMdmPrefs(context).edit().putBoolean("suspend_$packageName", suspend).apply()

        val isCurrentlyOsSuspended = try {
            dpm.isPackageSuspended(adminComponent, packageName)
        } catch (e: Exception) {
            false
        }

        if (suspend) {
            var osSuspendedSuccess = false
            if (!isCurrentlyOsSuspended) {
                try {
                    val unapplied = dpm.setPackagesSuspended(adminComponent, arrayOf(packageName), true)
                    osSuspendedSuccess = unapplied.isEmpty() || !unapplied.contains(packageName)
                } catch (e: Exception) {
                    e.printStackTrace()
                }
            } else {
                osSuspendedSuccess = true
            }

            // Si el SO prohibió suspender el paquete (típico en com.android.vending o instaladores protegidos por Android),
            // usar el fallback nativo de ocultar/deshabilitar la aplicación (setApplicationHidden)
            if (!osSuspendedSuccess) {
                android.util.Log.w("AppController", "setPackagesSuspended no pudo suspender $packageName en el SO. Aplicando fallback de deshabilitado (setApplicationHidden).")
                try {
                    dpm.setApplicationHidden(adminComponent, packageName, true)
                } catch (e: Exception) {
                    e.printStackTrace()
                }
            }
        } else {
            // Des-suspender en el SO si estaba suspendida
            try {
                dpm.setPackagesSuspended(adminComponent, arrayOf(packageName), false)
            } catch (e: Exception) {
                e.printStackTrace()
            }

            // Si fue ocultada mediante el fallback de suspensión (y no por preferencia explícita del usuario), des-ocultarla
            val isExplicitlyHiddenByUser = PrefsHelper.getMdmPrefs(context).getBoolean("hide_$packageName", false)
            if (!isExplicitlyHiddenByUser) {
                try {
                    dpm.setApplicationHidden(adminComponent, packageName, false)
                } catch (e: Exception) {
                    e.printStackTrace()
                }
            }
        }

        return true
    }

    fun isAppSuspended(packageName: String): Boolean {
        val prefsSuspended = PrefsHelper.getMdmPrefs(context).getBoolean("suspend_$packageName", false)
        val osSuspended = try {
            dpm.isPackageSuspended(adminComponent, packageName)
        } catch (e: Exception) {
            false
        }
        val isHidden = try {
            dpm.isApplicationHidden(adminComponent, packageName)
        } catch (e: Exception) {
            false
        }
        return prefsSuspended || osSuspended || (isHidden && prefsSuspended)
    }

    fun uninstallApp(packageName: String): Boolean {
        return try {
            val packageInstaller = pm.packageInstaller
            val intent = Intent(context, com.ejemplo.locksuite.receiver.UninstallReceiver::class.java)
            val pendingIntent = PendingIntent.getBroadcast(
                context,
                0,
                intent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE
            )
            packageInstaller.uninstall(packageName, pendingIntent.intentSender)
            true
        } catch (e: Exception) {
            e.printStackTrace()
            false
        }
    }

    fun getUserApps(loadIcon: Boolean = true): List<AppInfoData> {
        val flags = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            PackageManager.MATCH_UNINSTALLED_PACKAGES
        } else {
            @Suppress("DEPRECATION")
            PackageManager.GET_UNINSTALLED_PACKAGES
        }
        val installedApps = pm.getInstalledApplications(flags)

        return installedApps
            .mapNotNull { app ->
                try {
                    val label = pm.getApplicationLabel(app).toString()
                    val bitmap = if (loadIcon) {
                        try {
                            pm.getApplicationIcon(app).toBitmap()
                        } catch (e: Exception) {
                            null
                        }
                    } else {
                        null
                    }

                    val isSystem = (app.flags and ApplicationInfo.FLAG_SYSTEM) != 0
                    val isUpdatedSystem = (app.flags and ApplicationInfo.FLAG_UPDATED_SYSTEM_APP) != 0
                    val hasLauncher = pm.getLaunchIntentForPackage(app.packageName) != null

                    // Clasificación de la aplicación
                    val appType = when {
                        !isSystem -> "Usuario"
                        isUpdatedSystem -> "Preinstalada"
                        hasLauncher -> "Preinstalada"
                        else -> "Sistema"
                    }

                    AppInfoData(
                        packageName = app.packageName,
                        label = label,
                        icon = bitmap,
                        isHidden = isAppHidden(app.packageName),
                        isSuspended = isAppSuspended(app.packageName),
                        appType = appType,
                        isWebViewBlocked = WebViewBlockManager.isBlocked(context, app.packageName),
                        isCritical = isCritical(app.packageName),
                        imageBlockingMode = ImageBlockManager.getMode(context, app.packageName)
                    )
                } catch (e: Exception) {
                    null
                }
            }
            .sortedBy { it.label }
    }

    private fun getLauncherPackages(): Set<String> {
        val intent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME)
        val list = pm.queryIntentActivities(intent, PackageManager.MATCH_DEFAULT_ONLY)
        return list.map { it.activityInfo.packageName }.toSet()
    }
}

data class AppInfoData(
    val packageName: String,
    val label: String,
    val icon: Bitmap?,
    var isHidden: Boolean,
    var isSuspended: Boolean,
    val appType: String,
    var isWebViewBlocked: Boolean = false,
    val isCritical: Boolean = false,
    val imageBlockingMode: String = "none"
)
