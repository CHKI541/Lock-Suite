package com.ejemplo.locksuite.mdm

import android.app.admin.DevicePolicyManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Build
import android.os.UserManager
import com.ejemplo.locksuite.receiver.DeviceAdminReceiver
import com.ejemplo.locksuite.util.Constants
import com.ejemplo.locksuite.util.PrefsHelper

/**
 * Punto único para aplicar/leer políticas de DevicePolicyManager.
 * Todo lo que se aplica acá se refleja también en PrefsHelper.getMdmPrefs()
 * para poder reconstruir el estado tras un reinicio (reapplyAllRestrictions).
 */
class PolicyManager(private val context: Context) {

    private val dpm: DevicePolicyManager =
        context.getSystemService(Context.DEVICE_POLICY_SERVICE) as DevicePolicyManager
    private val adminComponent = ComponentName(context, DeviceAdminReceiver::class.java)

    fun isDeviceOwnerApp(): Boolean = dpm.isDeviceOwnerApp(context.packageName)

    // ──────────────────────────────────────────────────────
    // RESTRICCIONES GENERALES (UserManager.DISALLOW_*)
    // ──────────────────────────────────────────────────────

    fun setInstallAppsBlocked(blocked: Boolean) =
        applyRestriction("install_apps_blocked", UserManager.DISALLOW_INSTALL_APPS, blocked)

    fun setUninstallAppsBlocked(blocked: Boolean) =
        applyRestriction("uninstall_apps_blocked", UserManager.DISALLOW_UNINSTALL_APPS, blocked)

    fun setWifiConfigBlocked(blocked: Boolean) =
        applyRestriction("wifi_config_blocked", UserManager.DISALLOW_CONFIG_WIFI, blocked)

    fun setBluetoothBlocked(blocked: Boolean) =
        applyRestriction("bluetooth_blocked", UserManager.DISALLOW_BLUETOOTH, blocked)

    fun setVpnConfigBlocked(blocked: Boolean) =
        applyRestriction("vpn_config_blocked", UserManager.DISALLOW_CONFIG_VPN, blocked)

    fun setTetheringBlocked(blocked: Boolean) =
        applyRestriction("tethering_blocked", UserManager.DISALLOW_CONFIG_TETHERING, blocked)

    fun setFactoryResetBlocked(blocked: Boolean) =
        applyRestriction("factory_reset_blocked", UserManager.DISALLOW_FACTORY_RESET, blocked)

    fun setSafeBootBlocked(blocked: Boolean) =
        applyRestriction("safe_boot_blocked", UserManager.DISALLOW_SAFE_BOOT, blocked)

    fun setAdbBlocked(blocked: Boolean) =
        applyRestriction("adb_blocked", UserManager.DISALLOW_DEBUGGING_FEATURES, blocked)

    private fun applyRestriction(prefKey: String, restriction: String, blocked: Boolean) {
        try {
            if (blocked) {
                dpm.addUserRestriction(adminComponent, restriction)
            } else {
                dpm.clearUserRestriction(adminComponent, restriction)
            }
            PrefsHelper.getMdmPrefs(context).edit().putBoolean(prefKey, blocked).apply()
        } catch (e: SecurityException) {
            e.printStackTrace()
        }
    }

    fun isRestrictionEnabled(prefKey: String): Boolean =
        PrefsHelper.getMdmPrefs(context).getBoolean(prefKey, false)

    // ──────────────────────────────────────────────────────
    // CÁMARA Y CAPTURA DE PANTALLA
    // ──────────────────────────────────────────────────────

    fun setCameraDisabled(disabled: Boolean) {
        try {
            dpm.setCameraDisabled(adminComponent, disabled)
            PrefsHelper.getMdmPrefs(context).edit().putBoolean("camera_disabled", disabled).apply()
        } catch (e: SecurityException) {
            e.printStackTrace()
        }
    }

    fun setScreenCaptureDisabled(disabled: Boolean) {
        try {
            dpm.setScreenCaptureDisabled(adminComponent, disabled)
            PrefsHelper.getMdmPrefs(context).edit().putBoolean("screen_capture_disabled", disabled).apply()
        } catch (e: SecurityException) {
            e.printStackTrace()
        }
    }

    // ──────────────────────────────────────────────────────
    // MODO KIOSCO / LISTA BLANCA DE APPS PERMITIDAS
    //
    // Reemplaza el enfoque anterior de ocultar/suspender apps una por una.
    // Usa la Lock Task API de Android (pensada específicamente para
    // dispositivos de uso único/restringido: cajas registradoras, señalética,
    // tablets de préstamo, etc.), que además bloquea recientes/overview y
    // evita que se llegue a cualquier app fuera de la lista sin pasar por acá.
    // ──────────────────────────────────────────────────────

    fun setAllowedPackages(packages: Set<String>): Boolean {
        return try {
            dpm.setLockTaskPackages(adminComponent, packages.toTypedArray())
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                // Deja visible reloj/batería/señal; bloquea overview, power menu, etc.
                // Ajustable según necesidad — ver comentario en CHANGELOG.
                dpm.setLockTaskFeatures(
                    adminComponent,
                    DevicePolicyManager.LOCK_TASK_FEATURE_SYSTEM_INFO
                )
            }
            PrefsHelper.getMdmPrefs(context).edit()
                .putStringSet(Constants.KEY_ALLOWED_PACKAGES, packages)
                .putBoolean(Constants.KIOSK_MODE_KEY, packages.isNotEmpty())
                .apply()
            true
        } catch (e: SecurityException) {
            e.printStackTrace()
            false
        }
    }

    fun getAllowedPackages(): Set<String> =
        PrefsHelper.getMdmPrefs(context)
            .getStringSet(Constants.KEY_ALLOWED_PACKAGES, emptySet())
            ?: emptySet()

    fun isKioskModeEnabled(): Boolean =
        PrefsHelper.getMdmPrefs(context).getBoolean(Constants.KIOSK_MODE_KEY, false)

    /**
     * Registra KioskLauncherActivity como el "Inicio" del dispositivo, sin
     * mostrarle al usuario el selector de launchers (privilegio exclusivo
     * de Device Owner). Se llama una vez configurada al menos una app permitida.
     */
    fun registerKioskLauncher(launcherComponent: ComponentName) {
        try {
            val filter = IntentFilter(Intent.ACTION_MAIN).apply {
                addCategory(Intent.CATEGORY_HOME)
                addCategory(Intent.CATEGORY_DEFAULT)
            }
            dpm.addPersistentPreferredActivity(adminComponent, filter, launcherComponent)
        } catch (e: SecurityException) {
            e.printStackTrace()
        }
    }

    // ──────────────────────────────────────────────────────
    // REAPLICACIÓN TRAS REINICIO (BootReceiver / WatchdogWorker)
    // ──────────────────────────────────────────────────────

    fun reapplyAllRestrictions() {
        if (!isDeviceOwnerApp()) return
        val prefs = PrefsHelper.getMdmPrefs(context)

        setInstallAppsBlocked(prefs.getBoolean("install_apps_blocked", false))
        setUninstallAppsBlocked(prefs.getBoolean("uninstall_apps_blocked", false))
        setWifiConfigBlocked(prefs.getBoolean("wifi_config_blocked", false))
        setBluetoothBlocked(prefs.getBoolean("bluetooth_blocked", false))
        setVpnConfigBlocked(prefs.getBoolean("vpn_config_blocked", false))
        setTetheringBlocked(prefs.getBoolean("tethering_blocked", false))
        setFactoryResetBlocked(prefs.getBoolean("factory_reset_blocked", false))
        setSafeBootBlocked(prefs.getBoolean("safe_boot_blocked", false))
        setAdbBlocked(prefs.getBoolean("adb_blocked", false))
        setCameraDisabled(prefs.getBoolean("camera_disabled", false))
        setScreenCaptureDisabled(prefs.getBoolean("screen_capture_disabled", false))

        val allowed = getAllowedPackages()
        if (allowed.isNotEmpty()) {
            setAllowedPackages(allowed)
        }
    }
}
