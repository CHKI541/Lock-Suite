package com.ejemplo.locksuite.mdm

import android.app.admin.DevicePolicyManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.UserManager
import android.net.VpnService
import android.os.Bundle
import com.ejemplo.locksuite.receiver.DeviceAdminReceiver
import com.ejemplo.locksuite.util.PrefsHelper

class PolicyManager(private val context: Context) {

    private val dpm = context.getSystemService(Context.DEVICE_POLICY_SERVICE) as DevicePolicyManager
    private val adminComponent = ComponentName(context, DeviceAdminReceiver::class.java)

    private companion object {
        private const val GOOGLE_PLAY_SERVICES_PACKAGE = "com.google.android.gms"
        private const val FRP_CONFIG_CHANGED_ACTION = "com.google.android.gms.auth.FRP_CONFIG_CHANGED"

        private val LEGACY_FRP_ACCOUNT_KEYS = listOf(
            "factoryResetProtectionAccounts",
            "factoryResetProtectionAdmin",
            "factoryResetProtectionAdmins"
        )
    }

    private fun setRestriction(restriction: String, enable: Boolean): Boolean {
        return try {
            if (enable) {
                dpm.addUserRestriction(adminComponent, restriction)
            } else {
                dpm.clearUserRestriction(adminComponent, restriction)
            }
            saveState(restriction, enable)
            true
        } catch (e: Exception) {
            e.printStackTrace()
            false
        }
    }

    private fun saveState(restriction: String, enabled: Boolean) {
        val prefs = PrefsHelper.getMdmPrefs(context)
        prefs.edit().putBoolean(restriction, enabled).apply()
    }

    // ─────────────────────────────────────────────
    // POLÍTICAS DE SISTEMA
    // ─────────────────────────────────────────────

    fun setFactoryResetBlocked(block: Boolean) =
        setRestriction(UserManager.DISALLOW_FACTORY_RESET, block)

    fun setInstallAppsBlocked(block: Boolean): Boolean {
        val success = setRestriction(UserManager.DISALLOW_INSTALL_APPS, block)
        try {
            // Suspender Google Play Store (com.android.vending) para evitar instalaciones remotas o en segundo plano
            dpm.setPackagesSuspended(adminComponent, arrayOf("com.android.vending"), block)
        } catch (e: Exception) {
            e.printStackTrace()
        }
        return success
    }

    fun setUninstallAppsBlocked(block: Boolean) =
        setRestriction(UserManager.DISALLOW_UNINSTALL_APPS, block)

    fun setDebuggingFeaturesBlocked(block: Boolean) =
        setRestriction(UserManager.DISALLOW_DEBUGGING_FEATURES, block)

    fun setUserSwitchBlocked(block: Boolean) =
        setRestriction(UserManager.DISALLOW_USER_SWITCH, block)

    fun setModifyAccountsBlocked(block: Boolean) =
        setRestriction(UserManager.DISALLOW_MODIFY_ACCOUNTS, block)

    fun setSafeBootBlocked(block: Boolean) =
        setRestriction(UserManager.DISALLOW_SAFE_BOOT, block)

    fun setUnknownSourcesBlocked(block: Boolean) =
        setRestriction(UserManager.DISALLOW_INSTALL_UNKNOWN_SOURCES, block)

    fun setWifiConfigBlocked(block: Boolean): Boolean {
        val r1 = setRestriction(UserManager.DISALLOW_CONFIG_WIFI, block)
        val r2 = setRestriction(UserManager.DISALLOW_NETWORK_RESET, block)
        val r3 = setRestriction("no_config_mobile_networks", block)
        return r1 && r2 && r3
    }

    // ─────────────────────────────────────────────
    // HARDWARE Y PANTALLA
    // ─────────────────────────────────────────────

    fun setCameraDisabled(disabled: Boolean): Boolean {
        return try {
            dpm.setCameraDisabled(adminComponent, disabled)
            PrefsHelper.getMdmPrefs(context).edit().putBoolean("camera_disabled", disabled).apply()
            true
        } catch (e: SecurityException) {
            e.printStackTrace()
            false
        }
    }

    fun isCameraDisabled(): Boolean {
        return PrefsHelper.getMdmPrefs(context).getBoolean("camera_disabled", false)
    }

    fun setScreenCaptureBlocked(block: Boolean): Boolean {
        return try {
            dpm.setScreenCaptureDisabled(adminComponent, block)
            PrefsHelper.getMdmPrefs(context).edit().putBoolean("screen_capture_blocked", block).apply()
            true
        } catch (e: SecurityException) {
            e.printStackTrace()
            false
        }
    }

    fun isScreenCaptureBlocked(): Boolean {
        return PrefsHelper.getMdmPrefs(context).getBoolean("screen_capture_blocked", false)
    }

    fun setStatusBarDisabled(disabled: Boolean): Boolean {
        return try {
            dpm.setStatusBarDisabled(adminComponent, disabled)
            PrefsHelper.getMdmPrefs(context).edit().putBoolean("statusbar_disabled", disabled).apply()
            true
        } catch (e: Exception) {
            e.printStackTrace()
            false
        }
    }

    fun isStatusBarDisabled(): Boolean {
        return PrefsHelper.getMdmPrefs(context).getBoolean("statusbar_disabled", false)
    }

    fun setKeyguardDisabled(disabled: Boolean): Boolean {
        return try {
            dpm.setKeyguardDisabled(adminComponent, disabled)
            PrefsHelper.getMdmPrefs(context).edit().putBoolean("keyguard_disabled", disabled).apply()
            true
        } catch (e: Exception) {
            e.printStackTrace()
            false
        }
    }

    fun isKeyguardDisabled(): Boolean {
        return PrefsHelper.getMdmPrefs(context).getBoolean("keyguard_disabled", false)
    }

    fun setAdjustVolumeBlocked(block: Boolean) =
        setRestriction(UserManager.DISALLOW_ADJUST_VOLUME, block)

    fun setAppsControlBlocked(block: Boolean) =
        setRestriction(UserManager.DISALLOW_APPS_CONTROL, block)

    // ─────────────────────────────────────────────
    // CONECTIVIDAD
    // ─────────────────────────────────────────────

    fun setBluetoothBlocked(block: Boolean) =
        setRestriction(UserManager.DISALLOW_BLUETOOTH, block)

    fun setBluetoothSharingBlocked(block: Boolean) =
        setRestriction(UserManager.DISALLOW_BLUETOOTH_SHARING, block)

    fun setExternalMediaBlocked(block: Boolean) =
        setRestriction(UserManager.DISALLOW_MOUNT_PHYSICAL_MEDIA, block)

    fun setTetheringBlocked(block: Boolean) =
        setRestriction(UserManager.DISALLOW_CONFIG_TETHERING, block)

    fun setVpnConfigBlocked(block: Boolean): Boolean {
        return try {
            if (block) {
                // Forzar a LockSuite como la VPN permanente (Always-on) sin modo lockdown estricto.
                // Esto bloquea que el usuario pueda desactivar o quitar la VPN en Ajustes, pero permite el tráfico de internet normal.
                try {
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                        dpm.setAlwaysOnVpnPackage(adminComponent, context.packageName, false)
                        android.util.Log.i("PolicyManager", "Always-on VPN activa (lockdown=false) sobre ${context.packageName}")
                    }
                } catch (e: Exception) {
                    android.util.Log.w("PolicyManager", "No se pudo configurar Always-on VPN: ${e.message}")
                }
                setRestriction(UserManager.DISALLOW_CONFIG_VPN, true)
            } else {
                try {
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                        dpm.setAlwaysOnVpnPackage(adminComponent, null, false)
                        android.util.Log.i("PolicyManager", "Always-on VPN desactivada.")
                    }
                } catch (e: Exception) {
                    e.printStackTrace()
                }
                setRestriction(UserManager.DISALLOW_CONFIG_VPN, false)
            }
            true
        } catch (e: Exception) {
            e.printStackTrace()
            false
        }
    }

    fun setAdBlockingEnabled(enabled: Boolean): Boolean {
        return try {
            PrefsHelper.getMdmPrefs(context).edit().putBoolean("global_ad_blocking", enabled).apply()
            
            if (enabled) {
                // Arrancar la VPN para que filtre las consultas DNS de anuncios
                try {
                    val prepareIntent = VpnService.prepare(context)
                    if (prepareIntent == null) {
                        val startServiceIntent = Intent(context, com.ejemplo.locksuite.service.KosherVpnService::class.java)
                        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                            context.startForegroundService(startServiceIntent)
                        } else {
                            context.startService(startServiceIntent)
                        }
                        
                        // Si "Bloquear Ajustes de VPN" ya está activo, nos aseguramos de forzar Always-On
                        if (isRestrictionEnabled(UserManager.DISALLOW_CONFIG_VPN)) {
                            setVpnConfigBlocked(true)
                        }
                    }
                } catch (e: Exception) {
                    e.printStackTrace()
                }
            } else {
                // Si ya no hay apps bloqueadas en WebView, apagar la VPN por completo para ahorrar batería
                if (WebViewBlockManager.getBlockedPackages(context).isEmpty()) {
                    val stopServiceIntent = Intent(context, com.ejemplo.locksuite.service.KosherVpnService::class.java).apply {
                        action = "STOP_VPN"
                    }
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                        context.startForegroundService(stopServiceIntent)
                    } else {
                        context.startService(stopServiceIntent)
                    }
                }
            }
            true
        } catch (e: Exception) {
            e.printStackTrace()
            false
        }
    }

    fun isAdBlockingEnabled(): Boolean {
        return PrefsHelper.getMdmPrefs(context).getBoolean("global_ad_blocking", false)
    }

    fun setInternetBlocked(block: Boolean): Boolean {
        return try {
            if (block) {
                // Configura un proxy local inexistente (127.0.0.1:9999) para forzar el fallo de toda conexión de red (WiFi y Datos)
                val proxyInfo = android.net.ProxyInfo.buildDirectProxy("127.0.0.1", 9999)
                dpm.setRecommendedGlobalProxy(adminComponent, proxyInfo)
            } else {
                dpm.setRecommendedGlobalProxy(adminComponent, null)
            }
            PrefsHelper.getMdmPrefs(context).edit().putBoolean("internet_blocked", block).apply()
            true
        } catch (e: Exception) {
            e.printStackTrace()
            false
        }
    }

    fun isInternetBlocked(): Boolean {
        return PrefsHelper.getMdmPrefs(context).getBoolean("internet_blocked", false)
    }

    fun setWhatsAppBlockStatus(enabled: Boolean) {
        PrefsHelper.getMdmPrefs(context).edit().putBoolean("whatsapp_block_status", enabled).apply()
    }

    fun isWhatsAppBlockStatusEnabled(): Boolean {
        return PrefsHelper.getMdmPrefs(context).getBoolean("whatsapp_block_status", false)
    }

    fun setWhatsAppBlockChannels(enabled: Boolean) {
        PrefsHelper.getMdmPrefs(context).edit().putBoolean("whatsapp_block_channels", enabled).apply()
    }

    fun isWhatsAppBlockChannelsEnabled(): Boolean {
        return PrefsHelper.getMdmPrefs(context).getBoolean("whatsapp_block_channels", false)
    }

    // ─────────────────────────────────────────────
    // PERSISTENCIA Y REAPLICACIÓN
    // ─────────────────────────────────────────────

    fun isRestrictionEnabled(restriction: String): Boolean {
        return PrefsHelper.getMdmPrefs(context).getBoolean(restriction, false)
    }

    fun reapplyAllRestrictions() {
        val restrictions = listOf(
            UserManager.DISALLOW_FACTORY_RESET,
            UserManager.DISALLOW_INSTALL_APPS,
            UserManager.DISALLOW_UNINSTALL_APPS,
            UserManager.DISALLOW_DEBUGGING_FEATURES,
            UserManager.DISALLOW_USER_SWITCH,
            UserManager.DISALLOW_MODIFY_ACCOUNTS,
            UserManager.DISALLOW_SAFE_BOOT,
            UserManager.DISALLOW_INSTALL_UNKNOWN_SOURCES,
            UserManager.DISALLOW_CONFIG_WIFI,
            UserManager.DISALLOW_NETWORK_RESET,
            "no_config_mobile_networks",
            UserManager.DISALLOW_ADJUST_VOLUME,
            UserManager.DISALLOW_APPS_CONTROL,
            UserManager.DISALLOW_BLUETOOTH,
            UserManager.DISALLOW_BLUETOOTH_SHARING,
            UserManager.DISALLOW_MOUNT_PHYSICAL_MEDIA,
            UserManager.DISALLOW_CONFIG_TETHERING,
            UserManager.DISALLOW_CONFIG_VPN
        )

        restrictions.forEach { restriction ->
            if (isRestrictionEnabled(restriction)) {
                try {
                    dpm.addUserRestriction(adminComponent, restriction)
                } catch (e: Exception) {
                    e.printStackTrace()
                }
            }
        }

        // Bloquear desinstalación de LockSuite a nivel de sistema (H6)
        try {
            dpm.setUninstallBlocked(adminComponent, context.packageName, true)
        } catch (e: Exception) {
            e.printStackTrace()
        }

        // Hardware settings
        if (isCameraDisabled()) {
            setCameraDisabled(true)
        }
        if (isKeyguardDisabled()) {
            setKeyguardDisabled(true)
        }
        if (isStatusBarDisabled()) {
            setStatusBarDisabled(true)
        }
        if (isScreenCaptureBlocked()) {
            setScreenCaptureBlocked(true)
        }

        // Aplicar proxy de bloqueo de internet si está activado
        if (isInternetBlocked()) {
            setInternetBlocked(true)
        }

        // Suspender Google Play Store si el bloqueo de instalación está activado
        if (isRestrictionEnabled(UserManager.DISALLOW_INSTALL_APPS)) {
            try {
                dpm.setPackagesSuspended(adminComponent, arrayOf("com.android.vending"), true)
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }

        // Aplicar FRP si está activado
        if (isFrpEnabled()) {
            setFrpPolicy(getFrpAccounts(), useDefaultFrp(), true)
        }

        // Suspender todos los navegadores independientes instalados si la política está activa
        if (areBrowsersSuspended()) {
            setBrowsersSuspended(true)
        }

        // Suspender Android System WebView si la política está activa
        if (isSystemWebViewSuspended()) {
            setSystemWebViewSuspended(true)
        }

        // Re-aplicar suspensiones individuales y lista blanca (allowlist) de aplicaciones
        val prefs = PrefsHelper.getMdmPrefs(context)
        val allowed = prefs.getStringSet("allowed_packages", null)
        
        val appController = AppController(context)
        val userApps = appController.getUserApps(loadIcon = false)
        for (app in userApps) {
            if (!app.isCritical) {
                val isIndividuallySuspended = prefs.getBoolean("suspend_${app.packageName}", false)
                val shouldSuspend = (allowed != null && !allowed.contains(app.packageName)) || isIndividuallySuspended
                appController.suspendApp(app.packageName, shouldSuspend)
            }
        }
    }

    fun clearAllRestrictions() {
        val restrictions = listOf(
            UserManager.DISALLOW_FACTORY_RESET,
            UserManager.DISALLOW_INSTALL_APPS,
            UserManager.DISALLOW_UNINSTALL_APPS,
            UserManager.DISALLOW_DEBUGGING_FEATURES,
            UserManager.DISALLOW_USER_SWITCH,
            UserManager.DISALLOW_MODIFY_ACCOUNTS,
            UserManager.DISALLOW_SAFE_BOOT,
            UserManager.DISALLOW_INSTALL_UNKNOWN_SOURCES,
            UserManager.DISALLOW_CONFIG_WIFI,
            UserManager.DISALLOW_NETWORK_RESET,
            "no_config_mobile_networks",
            UserManager.DISALLOW_ADJUST_VOLUME,
            UserManager.DISALLOW_APPS_CONTROL,
            UserManager.DISALLOW_BLUETOOTH,
            UserManager.DISALLOW_BLUETOOTH_SHARING,
            UserManager.DISALLOW_MOUNT_PHYSICAL_MEDIA,
            UserManager.DISALLOW_CONFIG_TETHERING,
            UserManager.DISALLOW_CONFIG_VPN
        )

        restrictions.forEach { restriction ->
            try {
                dpm.clearUserRestriction(adminComponent, restriction)
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }

        // Permitir desinstalación de LockSuite tras la purga
        try {
            dpm.setUninstallBlocked(adminComponent, context.packageName, false)
        } catch (e: Exception) {
            e.printStackTrace()
        }

        // Reset hardware
        setCameraDisabled(false)
        setKeyguardDisabled(false)
        setStatusBarDisabled(false)
        setScreenCaptureBlocked(false)

        // Limpiar proxy global
        setInternetBlocked(false)

        clearFrpPolicy()

        // Habilitar Google Play Store
        try {
            dpm.setPackagesSuspended(adminComponent, arrayOf("com.android.vending"), false)
        } catch (e: Exception) {
            e.printStackTrace()
        }

        // Reactivar navegadores
        setBrowsersSuspended(false)

        // Reactivar Android System WebView
        setSystemWebViewSuspended(false)

        // Limpiar todas las restricciones de WebView e Imagen guardadas
        WebViewBlockManager.clearAll(context)
        ImageBlockManager.clearAll(context)

        // Detener servicio VPN
        try {
            val vpnIntent = Intent(context, com.ejemplo.locksuite.service.KosherVpnService::class.java)
            context.stopService(vpnIntent)
        } catch (e: Exception) {
            e.printStackTrace()
        }

        // Clear local preferences
        PrefsHelper.getMdmPrefs(context).edit().clear().apply()
    }

    private val KNOWN_BROWSER_PACKAGES = listOf(
        "com.android.chrome",
        "com.chrome.beta",
        "org.mozilla.firefox",
        "org.mozilla.focus",
        "com.opera.browser",
        "com.opera.mini.native",
        "com.microsoft.emmx",              // Edge
        "com.sec.android.app.sbrowser",    // Samsung Internet
        "com.brave.browser",
        "com.duckduckgo.mobile.android",
        "com.android.browser",             // Navegador AOSP antiguo
        "com.UCMobile.intl",
        "com.kiwibrowser.browser"
    )

    private fun getInstalledBrowserPackages(): Set<String> {
        val pm = context.packageManager
        val intent = Intent(Intent.ACTION_VIEW, Uri.parse("https://www.google.com"))
        val list = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            pm.queryIntentActivities(intent, PackageManager.ResolveInfoFlags.of(PackageManager.MATCH_ALL.toLong()))
        } else {
            @Suppress("DEPRECATION")
            pm.queryIntentActivities(intent, PackageManager.MATCH_ALL)
        }
        return list.map { it.activityInfo.packageName }
            .filterNot { it == context.packageName }
            .toSet()
    }

    fun suspendAllKnownBrowsers(suspend: Boolean) {
        val pm = context.packageManager
        val dynamicBrowsers = getInstalledBrowserPackages()
        val allBrowsers = (dynamicBrowsers + KNOWN_BROWSER_PACKAGES).filter { pkg ->
            try {
                pm.getPackageInfo(pkg, 0)
                true
            } catch (e: Exception) {
                false
            }
        }.toSet()

        if (allBrowsers.isNotEmpty()) {
            try {
                dpm.setPackagesSuspended(adminComponent, allBrowsers.toTypedArray(), suspend)
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }
    }

    fun setBrowsersSuspended(suspend: Boolean): Boolean {
        return try {
            suspendAllKnownBrowsers(suspend)
            PrefsHelper.getMdmPrefs(context).edit().putBoolean("browsers_suspended", suspend).apply()
            true
        } catch (e: Exception) {
            e.printStackTrace()
            false
        }
    }

    fun areBrowsersSuspended(): Boolean {
        return PrefsHelper.getMdmPrefs(context).getBoolean("browsers_suspended", false)
    }

    fun setSystemWebViewSuspended(suspend: Boolean): Boolean {
        val packages = listOf("com.google.android.webview", "com.android.webview")
        val pm = context.packageManager
        val installed = packages.filter { pkg ->
            try {
                pm.getPackageInfo(pkg, 0)
                true
            } catch (e: Exception) {
                false
            }
        }
        if (installed.isEmpty()) return false
        return try {
            dpm.setPackagesSuspended(adminComponent, installed.toTypedArray(), suspend)
            PrefsHelper.getMdmPrefs(context).edit().putBoolean("system_webview_suspended", suspend).apply()
            true
        } catch (e: Exception) {
            e.printStackTrace()
            false
        }
    }

    fun isSystemWebViewSuspended(): Boolean {
        return PrefsHelper.getMdmPrefs(context).getBoolean("system_webview_suspended", false)
    }

    // ─────────────────────────────────────────────
    // FACTORY RESET PROTECTION (FRP)
    // ─────────────────────────────────────────────
    fun setFrpPolicy(accountsList: List<String>, useDefault: Boolean, enabled: Boolean): Boolean {
        return try {
            val finalAccounts = if (useDefault && enabled) {
                com.ejemplo.locksuite.util.Constants.getDefaultFrpAccounts()
            } else {
                accountsList.map { it.trim() }.filter { it.isNotEmpty() }
            }

            // Si está activado pero no usa default y la lista de cuentas está vacía, no podemos configurar
            if (enabled && !useDefault && finalAccounts.isEmpty()) {
                return false
            }

            var success = false

            // 1. Intentar con la API oficial de Android 11+
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                try {
                    applyOfficialFrpPolicy(finalAccounts, enabled)
                    success = true
                } catch (e: Exception) {
                    e.printStackTrace()
                    // Si falla (ej. en el Qin F21 Pro por falta del servicio en la ROM), intentamos el fallback
                }
            }

            // 2. Fallback a restricciones de GMS si falló la API oficial o si es Android < 11
            if (!success) {
                try {
                    applyLegacyFrpPolicy(finalAccounts, enabled)
                    success = true
                } catch (e: Exception) {
                    e.printStackTrace()
                }
            }

            if (success) {
                setLegacyFrpHardening(enabled)

                val prefs = PrefsHelper.getMdmPrefs(context)
                prefs.edit()
                    .putBoolean("frp_enabled", enabled)
                    .putBoolean("frp_use_default", useDefault)
                    .putStringSet("frp_accounts", accountsList.toSet())
                    .apply()
            }
            success
        } catch (e: Exception) {
            e.printStackTrace()
            false
        }
    }

    private fun applyOfficialFrpPolicy(accounts: List<String>, enabled: Boolean) {
        if (enabled && accounts.isNotEmpty()) {
            val policy = android.app.admin.FactoryResetProtectionPolicy.Builder()
                .setFactoryResetProtectionAccounts(accounts)
                .setFactoryResetProtectionEnabled(true)
                .build()
            dpm.setFactoryResetProtectionPolicy(adminComponent, policy)
        } else {
            dpm.setFactoryResetProtectionPolicy(adminComponent, null)
        }
    }

    private fun applyLegacyFrpPolicy(accounts: List<String>, enabled: Boolean) {
        val bundle = try {
            dpm.getApplicationRestrictions(adminComponent, GOOGLE_PLAY_SERVICES_PACKAGE)
        } catch (e: Exception) {
            Bundle()
        }

        if (enabled && accounts.isNotEmpty()) {
            val accountsArray = accounts.toTypedArray()
            LEGACY_FRP_ACCOUNT_KEYS.forEach { key ->
                bundle.putStringArray(key, accountsArray)
            }
            bundle.putBoolean("factoryResetProtectionEnabled", true)
            bundle.putBoolean("disableFactoryResetProtectionAdmin", false)
        } else {
            LEGACY_FRP_ACCOUNT_KEYS.forEach { key ->
                bundle.remove(key)
            }
            bundle.putBoolean("factoryResetProtectionEnabled", false)
            bundle.putBoolean("disableFactoryResetProtectionAdmin", true)
        }

        dpm.setApplicationRestrictions(adminComponent, GOOGLE_PLAY_SERVICES_PACKAGE, bundle)
        notifyLegacyFrpChanged()
    }

    private fun clearFrpPolicy() {
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                dpm.setFactoryResetProtectionPolicy(adminComponent, null)
            }
            applyLegacyFrpPolicy(emptyList(), false)
            setLegacyFrpHardening(false)
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    private fun notifyLegacyFrpChanged() {
        val intent = Intent(FRP_CONFIG_CHANGED_ACTION).apply {
            setPackage(GOOGLE_PLAY_SERVICES_PACKAGE)
        }
        context.sendBroadcast(intent)
    }

    private fun setLegacyFrpHardening(enabled: Boolean) {
        listOf(
            UserManager.DISALLOW_FACTORY_RESET,
            UserManager.DISALLOW_MODIFY_ACCOUNTS,
            UserManager.DISALLOW_SAFE_BOOT
        ).forEach { restriction ->
            try {
                if (enabled) {
                    dpm.addUserRestriction(adminComponent, restriction)
                } else if (!isRestrictionEnabled(restriction)) {
                    dpm.clearUserRestriction(adminComponent, restriction)
                }
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }
    }

    fun isFrpEnabled(): Boolean {
        return PrefsHelper.getMdmPrefs(context).getBoolean("frp_enabled", false)
    }

    fun useDefaultFrp(): Boolean {
        return PrefsHelper.getMdmPrefs(context).getBoolean("frp_use_default", true)
    }

    fun getFrpAccounts(): List<String> {
        val set = PrefsHelper.getMdmPrefs(context).getStringSet("frp_accounts", null)
        return if (set != null && set.isNotEmpty()) {
            set.toList()
        } else {
            emptyList()
        }
    }
}
