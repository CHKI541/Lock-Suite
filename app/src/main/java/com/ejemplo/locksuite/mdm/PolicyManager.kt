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

    fun setFactoryResetBlocked(block: Boolean): Boolean {
        val ok = setRestriction(UserManager.DISALLOW_FACTORY_RESET, block)
        // DISALLOW_FACTORY_RESET por si solo saca la opcion de Ajustes: no hay garantia
        // publica de que tambien bloquee el menu de recovery fuera de Samsung (ver informe
        // de investigacion "bloqueo de reset/flasheo"). En Samsung con Knox SDK integrado
        // y licenciado, KnoxHardening ademas intenta el bloqueo real de recovery; si el
        // SDK no esta integrado o el equipo no es Samsung, esta llamada no hace nada (no
        // falla ni afecta el resto de la funcion).
        KnoxHardening.setFactoryResetBlocked(context, block)
        return ok
    }

    fun setFlashingBlocked(block: Boolean): Boolean {
        return try {
            PrefsHelper.getMdmPrefs(context).edit().putBoolean("flashing_blocked", block).apply()
            // Bloqueo de flasheo por Odin/Download mode: solo existe via Knox SDK en
            // Samsung — no hay equivalente en DevicePolicyManager estandar de Android.
            KnoxHardening.setFlashingBlocked(context, block)
            true
        } catch (e: Exception) {
            e.printStackTrace()
            false
        }
    }

    fun isFlashingBlocked(): Boolean {
        return PrefsHelper.getMdmPrefs(context).getBoolean("flashing_blocked", false)
    }

    fun setInstallAppsBlocked(block: Boolean): Boolean {
        val prefs = PrefsHelper.getMdmPrefs(context)
        val previousState = prefs.getBoolean("install_apps_blocked_admin", false)
        prefs.edit().putBoolean("install_apps_blocked_admin", block).apply()
        if (refreshInstallRestriction()) {
            return true
        }

        // No mostrar un estado que Android no aceptó. Restauramos la intención
        // anterior y procuramos reimponerla antes de informar el fallo.
        prefs.edit().putBoolean("install_apps_blocked_admin", previousState).apply()
        refreshInstallRestriction()
        return false
    }

    fun isInstallAppsBlocked(): Boolean {
        return PrefsHelper.getMdmPrefs(context).getBoolean("install_apps_blocked_admin", false)
    }

    fun refreshInstallRestriction(): Boolean {
        val prefs = PrefsHelper.getMdmPrefs(context)
        if (prefs.getBoolean("mdm_install_in_progress", false)) {
            android.util.Log.i("PolicyManager", "Instalación MDM en progreso: omitiendo refreshInstallRestriction")
            return true
        }

        val isBlocked = prefs.getBoolean("install_apps_blocked_admin", false)
        val allowed = prefs.getStringSet("allowed_packages", null) ?: emptySet()
        val hasAllowedApps = allowed.any { it != context.packageName && it != "com.ejemplo.locksuite" }

        val appController = AppController(context)
        if (isBlocked) {
            if (hasAllowedApps) {
                // Bloqueo programático: permite instalaciones, pero filtra por código
                if (!setRestriction(UserManager.DISALLOW_INSTALL_APPS, false)) return false
                prefs.edit().putBoolean("install_blocked_programmatic", true).apply()
            } else {
                // Bloqueo nativo estricto: bloquea a nivel de OS
                if (!setRestriction(UserManager.DISALLOW_INSTALL_APPS, true)) return false
                prefs.edit().putBoolean("install_blocked_programmatic", false).apply()
            }
            try {
                // Respetar preferencia explícita de Play Store (si suspend_com.android.vending es false, no suspender)
                val isPlayStoreSuspended = prefs.getBoolean("suspend_com.android.vending", true)
                appController.suspendApp("com.android.vending", isPlayStoreSuspended)
            } catch (e: Exception) {
                android.util.Log.w("PolicyManager", "No se pudo actualizar el estado de Play Store", e)
            }
        } else {
            // Sin bloqueo
            if (!setRestriction(UserManager.DISALLOW_INSTALL_APPS, false)) return false
            prefs.edit().putBoolean("install_blocked_programmatic", false).apply()
            try {
                val isPlayStoreSuspended = prefs.getBoolean("suspend_com.android.vending", false)
                appController.suspendApp("com.android.vending", isPlayStoreSuspended)
            } catch (e: Exception) {
                android.util.Log.w("PolicyManager", "No se pudo actualizar el estado de Play Store", e)
            }
        }
        return true
    }

    fun isPlayStoreSuspended(): Boolean {
        val prefs = PrefsHelper.getMdmPrefs(context)
        return prefs.getBoolean("suspend_com.android.vending", prefs.getBoolean("install_apps_blocked_admin", false))
    }

    fun restoreInstallRestrictions() {
        val prefs = PrefsHelper.getMdmPrefs(context)
        prefs.edit().putBoolean("mdm_install_in_progress", false).apply()
        
        // Restaurar restricción de orígenes desconocidos si estaba activada previamente
        if (isRestrictionEnabled(UserManager.DISALLOW_INSTALL_UNKNOWN_SOURCES)) {
            try {
                dpm.addUserRestriction(adminComponent, UserManager.DISALLOW_INSTALL_UNKNOWN_SOURCES)
                android.util.Log.i("PolicyManager", "Restaurada restricción DISALLOW_INSTALL_UNKNOWN_SOURCES")
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }
        
        // Restaurar restricción de apps (nativa o programática)
        refreshInstallRestriction()

        // Restaurar estado de ocultamiento y suspensión de Play Store según sus preferencias
        try {
            val shouldHidePlayStore = prefs.getBoolean("hide_com.android.vending", false)
            val shouldSuspendPlayStore = prefs.getBoolean("suspend_com.android.vending", prefs.getBoolean("install_apps_blocked_admin", false))
            
            dpm.setApplicationHidden(adminComponent, "com.android.vending", shouldHidePlayStore)
            if (!shouldHidePlayStore) {
                dpm.setPackagesSuspended(adminComponent, arrayOf("com.android.vending"), shouldSuspendPlayStore)
            }
        } catch (e: Exception) {
            android.util.Log.w("PolicyManager", "No se pudo restaurar ocultamiento/suspension de Play Store: ${e.message}")
        }
    }

    fun setHideSuspendedApps(block: Boolean) {
        val prefs = PrefsHelper.getMdmPrefs(context)
        prefs.edit().putBoolean("hide_suspended_apps", block).apply()
        // BUG: este bucle llamaba a appController.suspendApp(app.packageName, true)
        // — es decir, para toda app YA suspendida, la volvía a suspender (sin
        // depender de "block"), sin tocar nunca el ícono. "hide_suspended_apps" no
        // se lee en ningún otro lugar del código, así que el switch "Ocultar ícono
        // al suspender aplicaciones" no ocultaba ningún ícono: un botón fantasma.
        // Corregido para que realmente oculte/muestre el ícono de las apps
        // actualmente suspendidas según el nuevo valor del switch.
        val appController = AppController(context)
        val installedApps = appController.getUserApps()
        for (app in installedApps) {
            if (app.isSuspended) {
                appController.hideApp(app.packageName, block)
            }
        }
    }

    fun isHideSuspendedApps(): Boolean {
        val prefs = PrefsHelper.getMdmPrefs(context)
        return prefs.getBoolean("hide_suspended_apps", false)
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

    /**
     * @param enabled  Activa o desactiva el launcher Kosher.
     * @param openImmediately  Si true (defecto), al activar abre inmediatamente KosherLauncherActivity
     *                         y al desactivar regresa al launcher nativo. Pasar false cuando se llama
     *                         desde applyAllPolicies / Watchdog para no interrumpir al usuario.
     */
    fun setKosherLauncherEnabled(enabled: Boolean, openImmediately: Boolean = true): Boolean {
        return try {
            PrefsHelper.getMdmPrefs(context).edit().putBoolean("kosher_launcher_enabled", enabled).apply()
            
            val filter = android.content.IntentFilter(Intent.ACTION_MAIN).apply {
                addCategory(Intent.CATEGORY_HOME)
                addCategory(Intent.CATEGORY_DEFAULT)
            }
            val component = ComponentName(context, "com.ejemplo.locksuite.ui.launcher.KosherLauncherActivity")
            
            if (enabled) {
                // Registrar launcher como preferido persistente (Device Owner)
                dpm.addPersistentPreferredActivity(adminComponent, filter, component)

                // Aplicar fondo de pantalla y pantalla de bloqueo estilo MP3 oscuro
                applyKosherMp3Wallpaper()

                // Iniciar servicio de la marca de agua
                if (android.provider.Settings.canDrawOverlays(context)) {
                    val intent = Intent(context, com.ejemplo.locksuite.service.WatermarkService::class.java)
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                        context.startForegroundService(intent)
                    } else {
                        context.startService(intent)
                    }
                }

                // Solo abrir la pantalla inmediatamente cuando viene de FCM/UI, no desde el Watchdog
                if (openImmediately) {
                    val launchIntent = Intent(context, com.ejemplo.locksuite.ui.launcher.KosherLauncherActivity::class.java).apply {
                        flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
                    }
                    context.startActivity(launchIntent)
                }
            } else {
                // Limpiar launcher preferido
                dpm.clearPackagePersistentPreferredActivities(adminComponent, context.packageName)

                // Detener servicio de la marca de agua
                val intent = Intent(context, com.ejemplo.locksuite.service.WatermarkService::class.java)
                context.stopService(intent)

                // Solo redirigir al launcher nativo cuando viene de FCM/UI, no desde el Watchdog
                if (openImmediately) {
                    val homeIntent = Intent(Intent.ACTION_MAIN).apply {
                        addCategory(Intent.CATEGORY_HOME)
                        flags = Intent.FLAG_ACTIVITY_NEW_TASK
                    }
                    context.startActivity(homeIntent)
                }
            }
            true
        } catch (e: Exception) {
            e.printStackTrace()
            false
        }
    }

    private fun applyKosherMp3Wallpaper() {
        try {
            val wallpaperManager = android.app.WallpaperManager.getInstance(context)
            val bitmap = android.graphics.Bitmap.createBitmap(1080, 1920, android.graphics.Bitmap.Config.ARGB_8888)
            val canvas = android.graphics.Canvas(bitmap)
            val paint = android.graphics.Paint()
            
            // Degradado elegante: Gris azulado oscuro a Negro pizarra
            val shader = android.graphics.LinearGradient(
                0f, 0f, 0f, 1920f,
                android.graphics.Color.parseColor("#151821"),
                android.graphics.Color.parseColor("#090A0F"),
                android.graphics.Shader.TileMode.CLAMP
            )
            paint.shader = shader
            canvas.drawRect(0f, 0f, 1080f, 1920f, paint)

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                wallpaperManager.setBitmap(bitmap, null, true, android.app.WallpaperManager.FLAG_SYSTEM or android.app.WallpaperManager.FLAG_LOCK)
            } else {
                wallpaperManager.setBitmap(bitmap)
            }
        } catch (e: Exception) {
            android.util.Log.w("PolicyManager", "Error aplicando fondo de pantalla MP3", e)
        }
    }


    fun isKosherLauncherEnabled(): Boolean {
        return PrefsHelper.getMdmPrefs(context).getBoolean("kosher_launcher_enabled", false)
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

    fun disablePrivateDns() {
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                dpm.setGlobalSetting(adminComponent, "private_dns_mode", "off")
                android.util.Log.i("PolicyManager", "DNS Privado desactivado a nivel global (PRIVATE_DNS_MODE=off)")
            }
        } catch (e: Exception) {
            android.util.Log.w("PolicyManager", "No se pudo desactivar DNS Privado: ${e.message}")
        }
    }

    fun setVpnConfigBlocked(block: Boolean): Boolean {
        return try {
            if (block) {
                // Forzar a LockSuite como la VPN permanente (Always-on), con lockdown
                // DESACTIVADO a proposito. NO cambiar esto a true sin reescribir
                // KosherVpnService primero -- ver el porque abajo.
                //
                // Historial: se probo lockdown=true el 2026-07-27 para que una caida de la
                // VPN fallara cerrado (sin internet) en vez de dejar pasar trafico sin
                // filtro. Se revirtio ESE MISMO DIA tras confirmarse en un dispositivo real
                // que rompia TODO el internet general de forma permanente -- no solo durante
                // caidas puntuales de la VPN, sino todo el tiempo mientras lockdown estuviera
                // activo, incluso con el servicio de VPN sano y funcionando.
                //
                // Motivo tecnico: KosherVpnService es un tunel dividido (split-tunnel) que
                // SOLO agrega rutas para DNS -- ver los addRoute() en
                // KosherVpnService.startVpn() (el DNS virtual 10.0.0.1/fd00::1 y un puñado
                // de resolutores publicos conocidos). Nunca agrega una ruta general
                // (0.0.0.0/0), y runFilterLoop() descarta cualquier paquete que no sea
                // UDP/puerto 53. Con lockdown=true, Android exige que TODO el trafico de las
                // apps salga por la interfaz de la VPN -- pero esta VPN no sabe que hacer con
                // trafico que no sea DNS (navegacion, WhatsApp, imagenes, etc.), asi que ese
                // trafico general queda sin destino posible y se pierde. Ya habia pasado antes
                // (ver walkthrough.md v0.4.3) y volvio a pasar igual al reintentarlo ahora.
                //
                // Para que lockdown=true sea seguro haria falta reescribir KosherVpnService
                // como un tunel completo que reenvie TODO el trafico TCP/UDP (no solo DNS) --
                // agregar solo la ruta 0.0.0.0/0 sin ese reenvio real detras vuelve a romper
                // todo. Es un cambio de arquitectura mayor, con costo real de bateria/CPU (ver
                // informe de auditoria SS3.1) -- no algo para activar sin ese trabajo hecho y
                // probado en un dispositivo real primero.
                try {
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                        dpm.setAlwaysOnVpnPackage(adminComponent, context.packageName, false)
                        disablePrivateDns()
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
                disablePrivateDns()

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
                // Si ninguna política activa requiere la VPN, apagarla por completo para ahorrar batería
                if (!com.ejemplo.locksuite.receiver.BootReceiver.shouldVpnBeRunning(context)) {
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

    fun setGifsBlocked(enabled: Boolean): Boolean {
        return try {
            PrefsHelper.getMdmPrefs(context).edit().putBoolean("block_gifs", enabled).apply()
            
            if (enabled) {
                // Arrancar la VPN para filtrar Tenor/GIFs
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
                // Si ninguna política activa requiere la VPN, apagarla por completo para ahorrar batería
                if (!com.ejemplo.locksuite.receiver.BootReceiver.shouldVpnBeRunning(context)) {
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

    fun isGifsBlocked(): Boolean {
        return PrefsHelper.getMdmPrefs(context).getBoolean("block_gifs", false)
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

    fun setMercadoPagoBlockOffers(enabled: Boolean) {
        PrefsHelper.getMdmPrefs(context).edit()
            .putBoolean("mercadopago_block_offers", enabled)
            .putBoolean("mp_offers_accessibility", enabled)
            .putBoolean("mp_offers_vpn", enabled)
            .apply()
    }

    fun isMercadoPagoBlockOffersEnabled(): Boolean {
        return isMercadoPagoBlockOffersAccessibilityEnabled() || isMercadoPagoBlockOffersVpnEnabled()
    }

    fun setMercadoPagoBlockOffersAccessibility(enabled: Boolean) {
        PrefsHelper.getMdmPrefs(context).edit().putBoolean("mp_offers_accessibility", enabled).apply()
    }

    fun isMercadoPagoBlockOffersAccessibilityEnabled(): Boolean {
        val prefs = PrefsHelper.getMdmPrefs(context)
        return prefs.getBoolean("mp_offers_accessibility", prefs.getBoolean("mercadopago_block_offers", false))
    }

    fun setMercadoPagoBlockOffersVpn(enabled: Boolean) {
        PrefsHelper.getMdmPrefs(context).edit().putBoolean("mp_offers_vpn", enabled).apply()
        if (enabled) {
            // Asegurar que la VPN esté corriendo para filtrar peticiones DNS
            try {
                com.ejemplo.locksuite.receiver.BootReceiver.ensureVpnRunning(context)
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }
    }

    fun isMercadoPagoBlockOffersVpnEnabled(): Boolean {
        val prefs = PrefsHelper.getMdmPrefs(context)
        return prefs.getBoolean("mp_offers_vpn", prefs.getBoolean("mercadopago_block_offers", false))
    }

    // ─────────────────────────────────────────────
    // BLOQUEO TOTAL DE INTERNET POR APP
    // ─────────────────────────────────────────────

    fun setPerAppInternetBlocked(packageName: String, blocked: Boolean) {
        val prefs = PrefsHelper.getMdmPrefs(context)
        val currentSet = prefs.getStringSet("per_app_internet_blocked", emptySet())?.toMutableSet() ?: mutableSetOf()
        if (blocked) {
            currentSet.add(packageName)
        } else {
            currentSet.remove(packageName)
        }
        prefs.edit().putStringSet("per_app_internet_blocked", currentSet).apply()

        if (blocked) {
            try {
                com.ejemplo.locksuite.receiver.BootReceiver.ensureVpnRunning(context)
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }
        com.ejemplo.locksuite.util.FirebaseDeviceSync.syncDeviceInfo(context)
    }

    fun isPerAppInternetBlocked(packageName: String): Boolean {
        val prefs = PrefsHelper.getMdmPrefs(context)
        val currentSet = prefs.getStringSet("per_app_internet_blocked", emptySet()) ?: emptySet()
        return currentSet.contains(packageName)
    }

    fun getPerAppInternetBlockedPackages(): Set<String> {
        val prefs = PrefsHelper.getMdmPrefs(context)
        return prefs.getStringSet("per_app_internet_blocked", emptySet()) ?: emptySet()
    }

    // ─────────────────────────────────────────────
    // SISTEMA DE PERFILES GUARDADOS (PRESETS) Y RESPALDOS HMAC
    // ─────────────────────────────────────────────

    fun exportPolicyPresetJson(presetName: String = "Perfil LockSuite"): String {
        val dataObj = org.json.JSONObject()
        
        val restrictionsObj = org.json.JSONObject()
        val allRestrictions = listOf(
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
            UserManager.DISALLOW_CONFIG_VPN,
            UserManager.DISALLOW_ADJUST_VOLUME,
            UserManager.DISALLOW_APPS_CONTROL,
            UserManager.DISALLOW_BLUETOOTH_SHARING,
            UserManager.DISALLOW_MOUNT_PHYSICAL_MEDIA,
            UserManager.DISALLOW_CONFIG_TETHERING
        )
        for (r in allRestrictions) {
            restrictionsObj.put(r, isRestrictionEnabled(r))
        }
        dataObj.put("restrictions", restrictionsObj)
        dataObj.put("cameraDisabled", isCameraDisabled())
        dataObj.put("screenCaptureBlocked", isScreenCaptureBlocked())
        dataObj.put("statusBarDisabled", isStatusBarDisabled())
        dataObj.put("keyguardDisabled", isKeyguardDisabled())
        dataObj.put("internetBlocked", isInternetBlocked())
        dataObj.put("adBlockingEnabled", isAdBlockingEnabled())
        dataObj.put("gifsBlocked", isGifsBlocked())
        dataObj.put("whatsappBlockStatus", isWhatsAppBlockStatusEnabled())
        dataObj.put("whatsappBlockChannels", isWhatsAppBlockChannelsEnabled())
        dataObj.put("mercadoPagoBlockOffersAccessibility", isMercadoPagoBlockOffersAccessibilityEnabled())
        dataObj.put("mercadoPagoBlockOffersVpn", isMercadoPagoBlockOffersVpnEnabled())
        dataObj.put("kosherLauncherEnabled", isKosherLauncherEnabled())

        
        val perAppNetArr = org.json.JSONArray()
        getPerAppInternetBlockedPackages().forEach { perAppNetArr.put(it) }
        dataObj.put("perAppInternetBlocked", perAppNetArr)

        val rootObj = org.json.JSONObject()
        rootObj.put("presetName", presetName)
        rootObj.put("createdAt", System.currentTimeMillis())
        rootObj.put("version", 1)
        rootObj.put("data", dataObj)
        
        val dataString = canonicalizeJson(dataObj)
        val signature = computeHmacSha256(dataString)
        rootObj.put("signature", signature)

        return rootObj.toString(2)
    }

    fun importPolicyPresetJson(jsonString: String): Boolean {
        try {
            val rootObj = org.json.JSONObject(jsonString)
            val dataObj = rootObj.getJSONObject("data")
            val signature = rootObj.optString("signature", "")
            
            // Verificación HMAC Anti-Evasión con fallback retrocompatible
            val canonicalData = canonicalizeJson(dataObj)
            val computedSignature = computeHmacSha256(canonicalData)
            if (!computedSignature.equals(signature, ignoreCase = true)) {
                // Fallback: verificar con el formato heredado (toString())
                val legacyComputed = computeHmacSha256(dataObj.toString())
                if (!legacyComputed.equals(signature, ignoreCase = true)) {
                    android.util.Log.e("PolicyManager", "🚨 FIRMA HMAC INVÁLIDA: El archivo de respaldo ha sido alterado o corrupto.")
                    throw SecurityException("Firma del archivo de respaldo inválida. Archivo alterado no autorizado.")
                }
            }

            // Aplicar restricciones DPM
            val restrictionsObj = dataObj.getJSONObject("restrictions")
            val keys = restrictionsObj.keys()
            while (keys.hasNext()) {
                val key = keys.next()
                val enabled = restrictionsObj.getBoolean(key)
                if (enabled) {
                    setRestriction(key, true)
                } else {
                    setRestriction(key, false)
                }
            }

            setCameraDisabled(dataObj.optBoolean("cameraDisabled", false))
            setScreenCaptureBlocked(dataObj.optBoolean("screenCaptureBlocked", false))
            setStatusBarDisabled(dataObj.optBoolean("statusBarDisabled", false))
            setKeyguardDisabled(dataObj.optBoolean("keyguardDisabled", false))
            setInternetBlocked(dataObj.optBoolean("internetBlocked", false))
            setAdBlockingEnabled(dataObj.optBoolean("adBlockingEnabled", false))
            setGifsBlocked(dataObj.optBoolean("gifsBlocked", false))
            setWhatsAppBlockStatus(dataObj.optBoolean("whatsappBlockStatus", false))
            setWhatsAppBlockChannels(dataObj.optBoolean("whatsappBlockChannels", false))
            setMercadoPagoBlockOffersAccessibility(dataObj.optBoolean("mercadoPagoBlockOffersAccessibility", false))
            setMercadoPagoBlockOffersVpn(dataObj.optBoolean("mercadoPagoBlockOffersVpn", false))
            setKosherLauncherEnabled(dataObj.optBoolean("kosherLauncherEnabled", false))


            val perAppNetArr = dataObj.optJSONArray("perAppInternetBlocked")
            if (perAppNetArr != null) {
                val prefs = PrefsHelper.getMdmPrefs(context)
                val set = mutableSetOf<String>()
                for (i in 0 until perAppNetArr.length()) {
                    set.add(perAppNetArr.getString(i))
                }
                prefs.edit().putStringSet("per_app_internet_blocked", set).apply()
            }

            com.ejemplo.locksuite.util.FirebaseDeviceSync.syncDeviceInfo(context)
            return true
        } catch (e: Exception) {
            e.printStackTrace()
            return false
        }
    }

    fun saveLocalPreset(presetName: String, jsonString: String) {
        val prefs = PrefsHelper.getMdmPrefs(context)
        val currentJson = prefs.getString("local_presets_map", "{}") ?: "{}"
        val obj = org.json.JSONObject(currentJson)
        obj.put(presetName, jsonString)
        prefs.edit().putString("local_presets_map", obj.toString()).apply()
    }

    fun getLocalPresets(): Map<String, String> {
        val prefs = PrefsHelper.getMdmPrefs(context)
        val currentJson = prefs.getString("local_presets_map", "{}") ?: "{}"
        val obj = org.json.JSONObject(currentJson)
        val map = mutableMapOf<String, String>()
        val keys = obj.keys()
        while (keys.hasNext()) {
            val key = keys.next()
            map[key] = obj.getString(key)
        }
        return map
    }

    fun deleteLocalPreset(presetName: String) {
        val prefs = PrefsHelper.getMdmPrefs(context)
        val currentJson = prefs.getString("local_presets_map", "{}") ?: "{}"
        val obj = org.json.JSONObject(currentJson)
        obj.remove(presetName)
        prefs.edit().putString("local_presets_map", obj.toString()).apply()
    }

    private fun canonicalizeJson(value: Any?): String {
        return when (value) {
            null, org.json.JSONObject.NULL -> "null"
            is org.json.JSONObject -> {
                val keys = mutableListOf<String>()
                val iter = value.keys()
                while (iter.hasNext()) {
                    keys.add(iter.next())
                }
                keys.sort()
                val parts = keys.map { key ->
                    val escapedKey = org.json.JSONObject.quote(key)
                    val canonicalValue = canonicalizeJson(value.get(key))
                    "$escapedKey:$canonicalValue"
                }
                "{" + parts.joinToString(",") + "}"
            }
            is org.json.JSONArray -> {
                val parts = mutableListOf<String>()
                for (i in 0 until value.length()) {
                    parts.add(canonicalizeJson(value.get(i)))
                }
                "[" + parts.joinToString(",") + "]"
            }
            is String -> org.json.JSONObject.quote(value)
            is Number, is Boolean -> value.toString()
            else -> org.json.JSONObject.quote(value.toString())
        }
    }

    private fun computeHmacSha256(data: String): String {
        return try {
            val secretKey = "LockSuiteMDM_Preset_HMAC_SecretKey_2026"
            val sha256Hmac = javax.crypto.Mac.getInstance("HmacSHA256")
            val secretKeySpec = javax.crypto.spec.SecretKeySpec(secretKey.toByteArray(Charsets.UTF_8), "HmacSHA256")
            sha256Hmac.init(secretKeySpec)
            val hash = sha256Hmac.doFinal(data.toByteArray(Charsets.UTF_8))
            hash.joinToString("") { "%02x".format(it) }
        } catch (e: Exception) {
            ""
        }
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

        val isInstallInProgress = PrefsHelper.getMdmPrefs(context).getBoolean("mdm_install_in_progress", false)
        restrictions.forEach { restriction ->
            if (isInstallInProgress && (restriction == UserManager.DISALLOW_INSTALL_APPS || restriction == UserManager.DISALLOW_INSTALL_UNKNOWN_SOURCES)) {
                return@forEach
            }
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
        if (isKosherLauncherEnabled()) {
            setKosherLauncherEnabled(true, openImmediately = false) // No interrumpir al usuario al re-aplicar políticas
        }



        // Reforzar la designacion de Always-on VPN si la restriccion de VPN esta
        // activa. La restriccion DISALLOW_CONFIG_VPN ya se reaplica arriba en el forEach
        // generico, pero eso no reconfigura el Always-on de DevicePolicyManager en si (es
        // un ajuste de sistema aparte que Android no reasigna solo). Sin esto, si por
        // cualquier motivo el sistema perdiera esa configuracion (reset de OEM, etc.)
        // mientras la preferencia sigue guardada como activa, el dispositivo quedaria con
        // el filtro nominalmente "activo" pero sin la designacion Always-on real detras.
        // Al llamarse tambien desde WatchdogWorker cada 15 min, esto ademas propaga solo
        // un futuro cambio de este bloque a dispositivos ya aprovisionados sin necesitar
        // re-tocar el switch a mano.
        if (isRestrictionEnabled(UserManager.DISALLOW_CONFIG_VPN)) {
            setVpnConfigBlocked(true)
        }

        // Aplicar proxy de bloqueo de internet si está activado
        if (isInternetBlocked()) {
            setInternetBlocked(true)
        }

        // Suspender Google Play Store si el bloqueo de instalación está activado o si fue suspendida individualmente
        val prefs = PrefsHelper.getMdmPrefs(context)
        val appController = AppController(context)
        val shouldSuspendPlayStore = prefs.getBoolean("suspend_com.android.vending", prefs.getBoolean("install_apps_blocked_admin", false))
        try {
            appController.suspendApp("com.android.vending", shouldSuspendPlayStore)
            android.util.Log.i("PolicyManager", "reapplyAllRestrictions: Google Play Store estado de suspensión aplicado ($shouldSuspendPlayStore)")
        } catch (e: Exception) {
            e.printStackTrace()
        }

        // Aplicar FRP si está activado
        if (isFrpEnabled()) {
            setFrpPolicy(getFrpAccounts(), useDefaultFrp(), true)
        }

        // Reforzar el endurecimiento Knox (Samsung) de reset y flasheo tras reinicio.
        // El DISALLOW_FACTORY_RESET generico ya se reaplico arriba en el forEach; esto
        // solo repite la parte especifica de Knox, que no vive en ese listOf() porque
        // no es una UserManager restriction.
        if (isRestrictionEnabled(UserManager.DISALLOW_FACTORY_RESET)) {
            KnoxHardening.setFactoryResetBlocked(context, true)
        }
        if (isFlashingBlocked()) {
            KnoxHardening.setFlashingBlocked(context, true)
        }

        // Suspender todos los navegadores independientes instalados si la política está activa
        if (areBrowsersSuspended()) {
            setBrowsersSuspended(true)
        }

        // Suspender Android System WebView si la política está activa
        if (isSystemWebViewSuspended()) {
            setSystemWebViewSuspended(true)
        }

        // Re-aplicar suspensiones individuales de aplicaciones (solo si están suspendidas explícitamente)
        val userApps = appController.getUserApps(loadIcon = false)
        for (app in userApps) {
            if (!app.isCritical && app.packageName != "com.android.vending") {
                val isIndividuallySuspended = prefs.getBoolean("suspend_${app.packageName}", false)
                if (isIndividuallySuspended) {
                    appController.suspendApp(app.packageName, true)
                }
            }
        }

        // Re-aplicar restricciones de instalación
        refreshInstallRestriction()
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
        setKosherLauncherEnabled(false)


        // Limpiar proxy global
        setInternetBlocked(false)

        clearFrpPolicy()

        // Limpiar endurecimiento Knox (Samsung)
        KnoxHardening.setFactoryResetBlocked(context, false)
        KnoxHardening.setFlashingBlocked(context, false)
        PrefsHelper.getMdmPrefs(context).edit().putBoolean("flashing_blocked", false).apply()

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
