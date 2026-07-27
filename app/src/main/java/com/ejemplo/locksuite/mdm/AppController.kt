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

    // La lista de launchers no cambia durante la vida corta de este controlador.
    // Resolverla una sola vez evita una consulta al PackageManager por cada app de
    // la grilla (y por cada verificacion de ocultamiento/suspension).
    private val launcherPackages: Set<String> by lazy { queryLauncherPackages() }

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
        return packageName in systemEssential || packageName in launcherPackages
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
            val applied = dpm.setApplicationHidden(adminComponent, packageName, hide)
            if (!applied) {
                android.util.Log.w("AppController", "Android no aplico ocultamiento para $packageName")
                return false
            }
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
            android.util.Log.e("AppController", "No se pudo cambiar ocultamiento de $packageName", e)
            false
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

        val isCurrentlyOsSuspended = try {
            dpm.isPackageSuspended(adminComponent, packageName)
        } catch (e: Exception) {
            false
        }

        return try {
            val unapplied = if (suspend && isCurrentlyOsSuspended) {
                emptyArray()
            } else {
                dpm.setPackagesSuspended(adminComponent, arrayOf(packageName), suspend)
            }
            if (unapplied.contains(packageName)) {
                android.util.Log.w("AppController", "Android no aplico suspension para $packageName")
                false
            } else {
                // Solo reflejar como aplicada una politica que el SO confirmo.
                PrefsHelper.getMdmPrefs(context).edit().putBoolean("suspend_$packageName", suspend).apply()
                true
            }
        } catch (e: Exception) {
            android.util.Log.e("AppController", "No se pudo cambiar suspension de $packageName", e)
            false
        }
    }

    fun isAppSuspended(packageName: String): Boolean {
        val prefsSuspended = PrefsHelper.getMdmPrefs(context).getBoolean("suspend_$packageName", false)
        val osSuspended = try {
            dpm.isPackageSuspended(adminComponent, packageName)
        } catch (e: Exception) {
            false
        }
        // Antes había un tercer término "|| (isHidden && prefsSuspended)": como
        // prefsSuspended ya aparece antes en el OR, ese término era código muerto
        // (nunca podía cambiar el resultado) y solo costaba una llamada Binder extra
        // a isApplicationHidden en cada consulta. Eliminado.
        return prefsSuspended || osSuspended
    }

    fun uninstallApp(packageName: String): Boolean {
        if (isCritical(packageName) || isPartialBlockOnly(packageName)) {
            // A diferencia de hideApp/suspendApp, esta función no tenía este resguardo:
            // cualquier llamador (código futuro, o el auto-desinstalador de
            // PackageReceiver) podía pedir desinstalar com.android.systemui,
            // com.android.phone, el teclado, o incluso LockSuite mismo. Device Owner
            // suele bloquear la auto-desinstalación, pero no hay protección
            // equivalente de la plataforma para el resto de systemEssential/Gboard.
            android.util.Log.w("AppController", "Se rechazó desinstalar app crítica/protegida: $packageName")
            return false
        }
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
        val policyManager = PolicyManager(context)

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
                    val isHiddenNow = isAppHidden(app.packageName)

                    // Clasificación de la aplicación.
                    // "isHiddenNow" se agrega como tercer criterio para "Preinstalada":
                    // dpm.setApplicationHidden(true) deshabilita el paquete, y con el
                    // paquete deshabilitado pm.getLaunchIntentForPackage(...) devuelve
                    // null aunque la app normalmente tenga ícono. Sin este criterio, una
                    // app preinstalada con ícono (p.ej. Cámara) que el admin oculta
                    // cambiaba de categoría a "Sistema" en la grilla mientras estuviera
                    // oculta, y volvía a "Preinstalada" sola al des-ocultarla.
                    val appType = when {
                        !isSystem -> "Usuario"
                        isUpdatedSystem -> "Preinstalada"
                        hasLauncher -> "Preinstalada"
                        isHiddenNow -> "Preinstalada"
                        else -> "Sistema"
                    }

                    AppInfoData(
                        packageName = app.packageName,
                        label = label,
                        icon = bitmap,
                        isHidden = isHiddenNow,
                        isSuspended = isAppSuspended(app.packageName),
                        appType = appType,
                        isWebViewBlocked = WebViewBlockManager.isBlocked(context, app.packageName),
                        isInternetBlocked = policyManager.isPerAppInternetBlocked(app.packageName),
                        isCritical = isCritical(app.packageName),
                        imageBlockingMode = ImageBlockManager.getMode(context, app.packageName)
                    )
                } catch (e: Exception) {
                    null
                }
            }
            .sortedBy { it.label }
    }

    private fun queryLauncherPackages(): Set<String> {
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
    var isInternetBlocked: Boolean = false,
    val isCritical: Boolean = false,
    val imageBlockingMode: String = "none"
)
