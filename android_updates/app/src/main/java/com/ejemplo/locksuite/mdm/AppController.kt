package com.ejemplo.locksuite.mdm

import android.app.admin.DevicePolicyManager
import android.content.ComponentName
import android.content.Context
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import com.ejemplo.locksuite.receiver.DeviceAdminReceiver

data class InstalledAppInfo(
    val packageName: String,
    val label: String,
    val icon: android.graphics.drawable.Drawable,
    val isSystemApp: Boolean
)

class AppController(private val context: Context) {

    private val pm: PackageManager = context.packageManager
    private val dpm: DevicePolicyManager =
        context.getSystemService(Context.DEVICE_POLICY_SERVICE) as DevicePolicyManager
    private val adminComponent = ComponentName(context, DeviceAdminReceiver::class.java)

    /**
     * CORRECCIÓN: la versión anterior llamaba pm.getApplicationLabel()/
     * getApplicationIcon() dentro del .map{} sin try/catch — si una app se
     * desinstalaba justo mientras se iteraba la lista (carrera poco común
     * pero real), tiraba NameNotFoundException sin capturar y crasheaba
     * toda la pestaña de Aplicaciones. Ahora cada app problemática se
     * omite en vez de tirar abajo toda la lista.
     */
    fun getUserApps(): List<InstalledAppInfo> {
        val flags = PackageManager.GET_META_DATA or (
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                PackageManager.MATCH_UNINSTALLED_PACKAGES
            } else {
                @Suppress("DEPRECATION")
                PackageManager.GET_UNINSTALLED_PACKAGES
            }
        )
        val installedApps = pm.getInstalledApplications(flags)
        return installedApps.mapNotNull { appInfo ->
            try {
                InstalledAppInfo(
                    packageName = appInfo.packageName,
                    label = pm.getApplicationLabel(appInfo).toString(),
                    icon = pm.getApplicationIcon(appInfo.packageName),
                    isSystemApp = (appInfo.flags and ApplicationInfo.FLAG_SYSTEM) != 0
                )
            } catch (e: PackageManager.NameNotFoundException) {
                null
            }
        }.sortedBy { it.label.lowercase() }
    }

    fun setAppHidden(packageName: String, hidden: Boolean): Boolean {
        return try {
            dpm.setApplicationHidden(adminComponent, packageName, hidden)
        } catch (e: SecurityException) {
            e.printStackTrace()
            false
        }
    }

    fun setAppSuspended(packageName: String, suspended: Boolean): Boolean {
        return try {
            val failed = dpm.setPackagesSuspended(adminComponent, arrayOf(packageName), suspended)
            failed.isEmpty()
        } catch (e: SecurityException) {
            e.printStackTrace()
            false
        }
    }
}
