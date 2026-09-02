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

        val MERCADO_LIBRE_MP_DOMAINS = listOf(
            "click1.mercadolibre.com.ar",
            "listado.mercadolibre.com.ar",
            "mobile.mercadolibre.com.ar",
            "snoopy.mercadolibre.com.ar",
            "www.mercadolibre.com.ar"
        )
    }

    private fun setRestriction(restriction: String, enable: Boolean): Boolean {
        // Con LockSuite suspendido, activar una restricción guarda la INTENCIÓN
        // pero no la aplica al sistema: el equipo tiene que seguir libre hasta
        // que se reanude. reapplyAllRestrictions() la va a aplicar en ese momento
        // leyendo esta misma preferencia. Desactivar sí se aplica siempre: quitar
        // algo nunca puede romper la promesa de "sin restricciones".
        if (enable && isLockSuiteSuspended()) {
            saveState(restriction, true)
            android.util.Log.i("PolicyManager", "LockSuite suspendido: $restriction queda guardada pero sin aplicar")
            return true
        }
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

    /**
     * Con la suspensión activa, activar una política guarda la INTENCIÓN en las
     * preferencias pero no toca el sistema: el equipo tiene que seguir libre
     * hasta que se reanude, y en ese momento reapplyAllRestrictions() la aplica
     * leyendo esta misma preferencia.
     *
     * Desactivar nunca se difiere: quitar algo no puede romper la promesa de
     * "sin restricciones".
     *
     * @return true si el cambio quedó diferido (el llamador debe cortar acá).
     */
    private fun deferIfSuspended(prefKey: String, enable: Boolean): Boolean {
        if (!enable || !isLockSuiteSuspended()) return false
        PrefsHelper.getMdmPrefs(context).edit().putBoolean(prefKey, true).apply()
        android.util.Log.i("PolicyManager", "LockSuite suspendido: '$prefKey' se guarda pero no se aplica")
        return true
    }

    // ─────────────────────────────────────────────
    // POLÍTICAS DE SISTEMA
    // ─────────────────────────────────────────────

    fun setFactoryResetBlocked(block: Boolean): Boolean {
        if (deferIfSuspended(UserManager.DISALLOW_FACTORY_RESET, block)) return true
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
        if (deferIfSuspended("flashing_blocked", block)) return true
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
        if (prefs.getBoolean("locksuite_suspended", false)) {
            android.util.Log.i("PolicyManager", "LockSuite suspendido: omitiendo refreshInstallRestriction")
            return true
        }
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
        if (prefs.getBoolean("locksuite_suspended", false)) {
            android.util.Log.i("PolicyManager", "LockSuite suspendido: no se re-imponen los bloqueos de instalación")
            return
        }
        
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
        // Nota sobre la guarda "if (SDK >= P)" que había acá: no aportaba compatibilidad,
        // la sacaba. UserManager.DISALLOW_CONFIG_MOBILE_NETWORKS es una constante String
        // de compilación, así que el compilador la reemplaza por su literal
        // ("no_config_mobile_networks") y el bytecode queda idéntico al de pasar el string
        // a mano — nunca hubo riesgo de que "faltara la constante" en Android 7/8. Y si el
        // sistema rechazara la clave, setRestriction ya atrapa la excepción y devuelve
        // false. Con la guarda, en Android 7/8 no se intentaba aplicar la restricción y
        // aun así se devolvía true: el panel informaba un bloqueo que no existía. Se vuelve
        // a intentar siempre y se reporta el resultado real.
        val r3 = setRestriction(UserManager.DISALLOW_CONFIG_MOBILE_NETWORKS, block)
        return r1 && r2 && r3
    }

    // ─────────────────────────────────────────────
    // HARDWARE Y PANTALLA
    // ─────────────────────────────────────────────

    fun setCameraDisabled(disabled: Boolean): Boolean {
        if (deferIfSuspended("camera_disabled", disabled)) return true
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
        if (deferIfSuspended("screen_capture_blocked", block)) return true
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
        if (deferIfSuspended("statusbar_disabled", disabled)) return true
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
    // ─────────────────────────────────────────────
    // KIOSCO REAL DEL SISTEMA OPERATIVO  (Lock Task, 2/9/2026)
    //
    // Copiado de A Bloq (`kiosk/manager/KioskLockManager.kt`), que es donde LockSuite
    // estaba claramente más flojo.
    //
    // QUÉ PROBLEMA RESUELVE. `KosherLauncherActivity` es una Activity de Compose común,
    // registrada como launcher preferido con `addPersistentPreferredActivity()`. Eso hace
    // que sea LA pantalla de inicio, pero **no impide salir de ella**: basta con que algo
    // lance otra Activity —una notificación, un enlace profundo, un intent de otra app, el
    // botón de recientes en algunos equipos— para estar fuera del launcher kosher, con la
    // app abierta. La lista de apps permitidas del launcher es, hoy, una decisión de
    // interfaz: nadie la hace cumplir.
    //
    // Con Lock Task la hace cumplir **el sistema operativo**: solo se pueden abrir los
    // paquetes de la lista blanca, y el intento de abrir cualquier otro lo rechaza Android,
    // no LockSuite. Es la misma diferencia que hay entre el proxy "recomendado" y una ruta
    // de VPN: una es una sugerencia y la otra la aplica el kernel.
    //
    // POR QUÉ NO SE USA `LOCK_TASK_FEATURE_NONE` COMO A BLOQ. `NONE` bloquea también el
    // menú de encendido y el bloqueo de pantalla. Un equipo del que no se puede salir Y que
    // no se puede apagar Y que no tiene pantalla de bloqueo es un ladrillo cómodo de crear
    // y muy incómodo de deshacer, sobre todo probando. El conjunto elegido acá deja:
    //   • HOME — el botón de inicio vuelve al launcher kosher (que es lo que queremos);
    //   • GLOBAL_ACTIONS — se puede apagar y reiniciar el equipo;
    //   • KEYGUARD — sigue habiendo pantalla de bloqueo;
    //   • NOTIFICATIONS — se ven las notificaciones (si no, no se ve ni una llamada perdida).
    // y deja fuera OVERVIEW (recientes), que es la vía de escape que importa.
    // Si algún día se quiere el kiosco total, es cambiar esta constante por
    // `LOCK_TASK_FEATURE_NONE` — pero probalo en un equipo de descarte primero.
    //
    // ⚠️ ADVERTENCIA QUE HAY QUE LEER ANTES DE ENCENDERLO. Estando en Lock Task solo se
    // abren los paquetes de la lista. **Si el marcador telefónico no está en la lista de
    // apps permitidas del launcher, el código de emergencia `*#*#9999#*#*` no se puede
    // marcar**, y esa es la vía de recuperación del equipo. LockSuite se agrega siempre a
    // sí misma; el marcador es decisión del administrador. Por eso el interruptor viene
    // APAGADO por defecto.
    // ─────────────────────────────────────────────

    // ─────────────────────────────────────────────
    // MODO TELÉFONO DE TECLAS  (2/9/2026)
    //
    // Pantalla de inicio estilo Nokia manejada con la cruceta y los números, con íconos
    // propios por FUNCIÓN (Llamadas, Contactos, Mensajes…) en vez del ícono original de
    // cada app. Ver ui/launcher/NokiaKeypadScreen.kt y NokiaIconSet.kt.
    //
    // Son DOS interruptores separados a propósito:
    //   • `nokia_keypad_mode` cambia la pantalla del launcher. Inofensivo: se puede
    //     encender y apagar sin riesgo.
    //   • `nokia_touch_enabled` apaga el táctil DENTRO del launcher. Ese sí tiene filo:
    //     en un equipo sin teclas físicas deja la pantalla de inicio manejable solo por
    //     el panel (o con el gesto de emergencia de 3 s en la esquina superior derecha).
    //     Por eso viene ENCENDIDO por defecto: apagar el táctil es una decisión explícita.
    // ─────────────────────────────────────────────

    fun isNokiaKeypadMode(): Boolean =
        PrefsHelper.getMdmPrefs(context).getBoolean("nokia_keypad_mode", false)

    fun setNokiaKeypadMode(enable: Boolean): Boolean {
        if (deferIfSuspended("nokia_keypad_mode", enable)) return true
        PrefsHelper.getMdmPrefs(context).edit().putBoolean("nokia_keypad_mode", enable).apply()
        // El launcher decide qué pantalla dibuja en onCreate(), así que hay que recrearlo
        // para que el cambio se vea sin que el usuario tenga que reiniciar el equipo.
        if (isKosherLauncherEnabled()) {
            try {
                val i = Intent(context, com.ejemplo.locksuite.ui.launcher.KosherLauncherActivity::class.java)
                i.flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
                context.startActivity(i)
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }
        return true
    }

    fun isNokiaTouchEnabled(): Boolean =
        PrefsHelper.getMdmPrefs(context).getBoolean("nokia_touch_enabled", true)

    fun setNokiaTouchEnabled(enable: Boolean): Boolean {
        if (deferIfSuspended("nokia_touch_enabled", enable)) return true
        PrefsHelper.getMdmPrefs(context).edit().putBoolean("nokia_touch_enabled", enable).apply()
        return true
    }

    fun isKioskLockTaskEnabled(): Boolean =
        PrefsHelper.getMdmPrefs(context).getBoolean("kiosk_lock_task_enabled", false)

    /**
     * Paquetes que pueden correr en Lock Task: los permitidos del launcher kosher, más
     * LockSuite. LockSuite va siempre y sin excepción: es la que entra a Lock Task, la que
     * lo tiene que poder soltar, y la que muestra la pantalla de recuperación.
     */
    private fun lockTaskAllowedPackages(): Array<String> {
        val allowed = PrefsHelper.getMdmPrefs(context)
            .getStringSet("allowed_packages", null) ?: emptySet()
        return (allowed + context.packageName).toTypedArray()
    }

    fun setKioskLockTaskEnabled(enable: Boolean): Boolean {
        if (deferIfSuspended("kiosk_lock_task_enabled", enable)) return true
        return try {
            PrefsHelper.getMdmPrefs(context).edit()
                .putBoolean("kiosk_lock_task_enabled", enable).apply()
            applyKioskLockTask(enable)
            true
        } catch (e: Exception) {
            e.printStackTrace()
            false
        }
    }

    /**
     * Aplica el estado de Lock Task al sistema. Se llama al encender el interruptor, al
     * reaplicar restricciones tras un reinicio, y cada vez que cambia la lista de apps
     * permitidas del launcher (si no, una app recién permitida no se podría abrir).
     */
    fun applyKioskLockTask(enable: Boolean) {
        try {
            if (enable) {
                dpm.setLockTaskPackages(adminComponent, lockTaskAllowedPackages())
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                    dpm.setLockTaskFeatures(
                        adminComponent,
                        DevicePolicyManager.LOCK_TASK_FEATURE_HOME or
                            DevicePolicyManager.LOCK_TASK_FEATURE_GLOBAL_ACTIONS or
                            DevicePolicyManager.LOCK_TASK_FEATURE_KEYGUARD or
                            DevicePolicyManager.LOCK_TASK_FEATURE_NOTIFICATIONS
                    )
                }
            } else {
                // Vaciar la lista PRIMERO: con la lista vacía, cualquier tarea que siguiera
                // anclada se suelta sola. Al revés (devolver las features y después vaciar)
                // deja una ventana en que el equipo sigue anclado sin lista.
                dpm.setLockTaskPackages(adminComponent, arrayOf())
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                    dpm.setLockTaskFeatures(
                        adminComponent,
                        DevicePolicyManager.LOCK_TASK_FEATURE_HOME or
                            DevicePolicyManager.LOCK_TASK_FEATURE_GLOBAL_ACTIONS or
                            DevicePolicyManager.LOCK_TASK_FEATURE_KEYGUARD or
                            DevicePolicyManager.LOCK_TASK_FEATURE_NOTIFICATIONS or
                            DevicePolicyManager.LOCK_TASK_FEATURE_OVERVIEW or
                            DevicePolicyManager.LOCK_TASK_FEATURE_SYSTEM_INFO
                    )
                }
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    fun setKosherLauncherEnabled(enabled: Boolean, openImmediately: Boolean = true): Boolean {
        if (deferIfSuspended("kosher_launcher_enabled", enabled)) return true
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
        if (deferIfSuspended("keyguard_disabled", disabled)) return true
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

    /**
     * BLOQUEAR CAMBIO DE IDIOMA  (18/8/2026)
     *
     * Es la respuesta directa a un hallazgo del dueño que vale más que cualquier
     * ajuste fino del filtro visual: **"si el usuario cambia el idioma a uno raro se
     * evade todo, porque ya lee distinto la pantalla"**. Tiene razón, y aplica a TODA
     * la Capa 3 que compara texto: ofertas de Mercado Pago, pestañas de WhatsApp, el
     * menú de Accesibilidad de Ajustes, la anti-evasión de Ajustes. Cambiar el idioma
     * a uno que no está en las listas las deja mudas de golpe, sin tocar nada más.
     *
     * `DISALLOW_CONFIG_LOCALE` es una restricción real de Android (API 21+) que un
     * Device Owner puede imponer: el usuario no puede cambiar el idioma del sistema.
     * Es una línea de código y cierra la puerta de par en par, así que es de lejos la
     * defensa más barata de toda la lista.
     *
     * Dos cosas que hay que decir igual:
     *
     *  • **No cubre el idioma POR APP de Android 13+.** Desde Android 13 se puede
     *    ponerle a una sola app un idioma distinto (Ajustes → Apps → X → Idioma), y eso
     *    no pasa por `DISALLOW_CONFIG_LOCALE`. Contra eso, lo que sirve es no depender
     *    de texto: los bloqueos de Estados y Canales de WhatsApp van por nombre de
     *    ACTIVIDAD, y la detección del menú de Accesibilidad se reescribió el 18/8 para
     *    ir por componente resuelto y por título pedido a los recursos de la propia app
     *    de Ajustes. Los que siguen dependiendo de texto son Mercado Pago y la
     *    anti-evasión de Ajustes.
     *  • **Tiene un costo de usabilidad real:** si el equipo se entrega en el idioma
     *    equivocado, el usuario final ya no lo puede corregir solo. Por eso es un
     *    interruptor y no algo forzado.
     */
    fun setLocaleChangeBlocked(block: Boolean) =
        setRestriction(UserManager.DISALLOW_CONFIG_LOCALE, block)

    fun isLocaleChangeBlocked(): Boolean =
        isRestrictionEnabled(UserManager.DISALLOW_CONFIG_LOCALE)

    // ─────────────────────────────────────────────
    // RESTRICCIONES DEL REGISTRO DECLARATIVO  (2/9/2026)
    //
    // Ver `mdm/PolicySpec.kt` para el porqué. Estas tres funciones son TODO lo que hace
    // falta del lado de PolicyManager para cualquier restricción nueva que se agregue a
    // esa lista: no hay un setter por restricción.
    // ─────────────────────────────────────────────

    /**
     * Restricciones que LockSuite cree tener aplicadas pero que el sistema NO tiene puestas.
     *
     * ─────────────────────────────────────────────────────────────────────────────
     * POR QUÉ ESTO EXISTE (patrón copiado de A Bloq, 2/9/2026)
     * ─────────────────────────────────────────────────────────────────────────────
     *
     * `isRestrictionEnabled()` lee `SharedPreferences`: devuelve **lo que LockSuite quiso
     * aplicar**, no lo que el equipo tiene. A Bloq, en cambio, resuelve el equivalente
     * (`isPolicyActive()`) consultando `dpm.getUserRestrictions(admin)`, que es **lo que el
     * sistema tiene puesto de verdad**.
     *
     * La diferencia importa porque `addUserRestriction()` puede no surtir efecto sin fallar:
     * un fabricante que la ignora, un Binder que falló en el momento justo, una restricción
     * que esta versión de Android no conoce (las acepta y las descarta en silencio), o Knox
     * pisando la política. En todos esos casos, hasta ahora, **el panel mostraba el
     * interruptor encendido sobre un equipo desprotegido** y no había forma de enterarse.
     *
     * Es el mismo tipo de defecto que ya costó caro en este proyecto: B.15 punto 3
     * ("se recordaba 'ya lo apliqué' en una variable en memoria" — y eso era, textual, el
     * vaivén de "se suspenden y vuelven a aparecer"). La lección de aquella vez fue
     * **comparar y corregir en vez de ordenar**. Esto es lo mismo, para las restricciones
     * del DPM.
     *
     * Solo REPORTA, no corrige: corregir es trabajo de `reapplyAllRestrictions()`, que ya
     * corre al arrancar y cada 15 minutos. Lo que faltaba era **verlo**.
     */
    fun divergentRestrictions(): List<String> {
        return try {
            val reales = dpm.getUserRestrictions(adminComponent)
            val candidatas = PolicySpec.EXTRA_RESTRICTIONS
                .filter { it.supportedHere }
                .map { it.restriction } + listOf(
                UserManager.DISALLOW_FACTORY_RESET,
                UserManager.DISALLOW_INSTALL_APPS,
                UserManager.DISALLOW_UNINSTALL_APPS,
                UserManager.DISALLOW_DEBUGGING_FEATURES,
                UserManager.DISALLOW_SAFE_BOOT,
                UserManager.DISALLOW_INSTALL_UNKNOWN_SOURCES,
                UserManager.DISALLOW_CONFIG_WIFI,
                UserManager.DISALLOW_CONFIG_VPN,
                UserManager.DISALLOW_BLUETOOTH,
                UserManager.DISALLOW_CONFIG_TETHERING,
                UserManager.DISALLOW_CONFIG_LOCALE,
                UserManager.DISALLOW_APPS_CONTROL,
                UserManager.DISALLOW_MODIFY_ACCOUNTS
            )
            candidatas.distinct().filter { r ->
                // Solo interesa el caso peligroso: la pedimos y NO está puesta.
                // Al revés (puesta sin pedirla) puede ser política de otro origen y no es
                // un agujero de seguridad.
                isRestrictionEnabled(r) && !reales.getBoolean(r, false)
            }
        } catch (e: Exception) {
            e.printStackTrace()
            emptyList()
        }
    }

    /** Aplica o quita una restricción del registro. Respeta la suspensión igual que el resto. */
    fun setExtraRestriction(spec: ExtraRestriction, block: Boolean): Boolean =
        setRestriction(spec.restriction, block)

    fun isExtraRestrictionEnabled(spec: ExtraRestriction): Boolean =
        isRestrictionEnabled(spec.restriction)

    /**
     * Estado de todas las restricciones del registro, listo para reportar al panel.
     *
     * Devuelve dos campos por restricción: si está pedida, y si este equipo la soporta.
     * Lo segundo importa porque Android **acepta y descarta en silencio** una restricción
     * que su versión no conoce — el mismo problema que ya estaba documentado para las
     * reglas DNS por app, donde el panel mostraba el interruptor encendido en equipos
     * donde la regla no regía. Reportarlo evita esa mentira.
     */
    fun extraRestrictionsStatus(): Map<String, Any> {
        val out = mutableMapOf<String, Any>()
        for (spec in PolicySpec.EXTRA_RESTRICTIONS) {
            out[spec.reportField] = isExtraRestrictionEnabled(spec)
            out["${spec.reportField}Supported"] = spec.supportedHere
        }
        return out
    }

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
        if (deferIfSuspended(UserManager.DISALLOW_CONFIG_VPN, block)) return true
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
        if (deferIfSuspended("internet_blocked", block)) return true
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

    /**
     * Igual que [setInternetBlocked] pero SIN escribir la preferencia `internet_blocked`.
     *
     * Lo usa el Arranque Protegido (`util/BootGate.kt`), que cierra la red unos segundos
     * mientras la VPN de filtrado termina de levantar. La diferencia importa: la
     * preferencia representa la INTENCIÓN del administrador ("este equipo tiene que estar
     * sin internet"), y `reapplyAllRestrictions()` la usa para reconstruir el estado. Si
     * el arranque protegido la escribiera, un equipo cuyo bloqueo preventivo no llegara a
     * limpiarse quedaría marcado como "sin internet a propósito" para siempre.
     *
     * Por el mismo motivo, BootGate no llama a esto si `isInternetBlocked()` ya es true:
     * ahí no hay nada que agregar, y al liberar no hay que tocar nada.
     */
    fun setNetworkGateBlocked(block: Boolean): Boolean {
        return try {
            if (block) {
                val proxyInfo = android.net.ProxyInfo.buildDirectProxy("127.0.0.1", 9999)
                dpm.setRecommendedGlobalProxy(adminComponent, proxyInfo)
            } else {
                dpm.setRecommendedGlobalProxy(adminComponent, null)
            }
            true
        } catch (e: Exception) {
            android.util.Log.w("PolicyManager", "setNetworkGateBlocked($block) falló: ${e.message}")
            false
        }
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

    fun isMercadoLibreInMpBlocked(): Boolean {
        return PrefsHelper.getMdmPrefs(context).getBoolean("block_ml_in_mp", false)
    }

    fun setMercadoLibreInMpBlocked(enabled: Boolean) {
        PrefsHelper.getMdmPrefs(context).edit().putBoolean("block_ml_in_mp", enabled).apply()
        try {
            val ruleManager = com.ejemplo.locksuite.LockSuiteApplication.domainRuleManager
            for (domain in MERCADO_LIBRE_MP_DOMAINS) {
                if (enabled) {
                    ruleManager.setRule(domain, com.ejemplo.locksuite.dns.RuleType.BLOCK)
                } else {
                    ruleManager.clearRule(domain)
                }
            }
        } catch (e: Exception) {
            android.util.Log.e("PolicyManager", "Error actualizando DomainRuleManager para ML en MP: ${e.message}")
        }
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
            UserManager.DISALLOW_CONFIG_TETHERING,
            // 2/9/2026 — faltaban en el perfil aunque la app y el panel sí las manejan.
            // Un perfil que no las incluye no las apaga NI las prende al aplicarse: el
            // equipo queda a medio configurar y parece que "el preset no anduvo".
            UserManager.DISALLOW_BLUETOOTH,
            UserManager.DISALLOW_CONFIG_LOCALE,
            UserManager.DISALLOW_CONFIG_MOBILE_NETWORKS,
            UserManager.DISALLOW_CONFIG_DATE_TIME
        )
        for (r in allRestrictions) {
            restrictionsObj.put(r, isRestrictionEnabled(r))
        }
        // Las del registro declarativo se suman con su clave real de UserManager, igual que
        // el resto: la importación recorre las claves del objeto, así que no hace falta
        // ningún caso especial del otro lado. Ver mdm/PolicySpec.kt.
        for (spec in PolicySpec.EXTRA_RESTRICTIONS) {
            restrictionsObj.put(spec.restriction, isExtraRestrictionEnabled(spec))
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
        dataObj.put("blockMlInMp", isMercadoLibreInMpBlocked())
        dataObj.put("kosherLauncherEnabled", isKosherLauncherEnabled())
        // 2/9/2026 — interruptores que existen en la app y en el panel pero que el perfil
        // no guardaba. Sin esto, "exportar el perfil de un equipo bien configurado y
        // aplicarlo a otro" dejaba afuera justamente las protecciones más nuevas.
        dataObj.put("flashingBlocked", isFlashingBlocked())
        dataObj.put("hideSuspendedApps", isHideSuspendedApps())
        dataObj.put("accessibilityProtection", isAccessibilityProtectionEnabled())
        dataObj.put("accBounceSettings", isAccBounceSettingsEnabled())
        dataObj.put("accNag", isAccNagEnabled())
        dataObj.put("accSuspendAll", isAccSuspendAllEnabled())
        dataObj.put("bootGateEnabled", isBootGateEnabled())
        dataObj.put("bootGateWaitAccessibility", isBootGateWaitAccessibilityEnabled())
        dataObj.put("imageBlockStrictScroll", isImageBlockStrictScrollEnabled())
        dataObj.put("kioskLockTask", isKioskLockTaskEnabled())
        dataObj.put("nokiaKeypadMode", isNokiaKeypadMode())
        dataObj.put("nokiaTouchEnabled", isNokiaTouchEnabled())

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

            // Verificación HMAC Anti-Evasión con fallbacks retrocompatibles.
            //
            // ⚠️ 2/9/2026 — POR QUÉ HAY UN TERCER FALLBACK ("array vacío perdido").
            //
            // Realtime Database NO GUARDA arrays vacíos: son equivalentes a null, así que
            // la clave desaparece del nodo. El panel arma el perfil con
            // `perAppInternetBlocked: []` cuando el equipo no tiene ninguna app con
            // internet bloqueado —el caso normal—, FIRMA sobre ese objeto, y recién
            // después lo guarda en `presets/`. Al leerlo de vuelta para aplicarlo, la clave
            // ya no está: el objeto que llega al celular NO es el que se firmó, la firma no
            // puede coincidir nunca, y el perfil se rechaza con "archivo alterado".
            //
            // O sea: TODO perfil creado desde el panel en un equipo sin bloqueos de
            // internet por app era imposible de aplicar. Ese es, textual, "los presets no
            // andan bien". El fallback reconstruye la clave que RTDB se comió y verifica
            // contra eso. El panel también se corrigió para no firmar claves que no va a
            // guardar (ver admin-backend/public/app.js), así que esto es para los perfiles
            // que ya quedaron guardados de antes.
            val canonicalData = canonicalizeJson(dataObj)
            val computedSignature = computeHmacSha256(canonicalData)
            if (!computedSignature.equals(signature, ignoreCase = true)) {
                // Fallback 1: formato heredado (toString() sin canonicalizar).
                val legacyComputed = computeHmacSha256(dataObj.toString())
                // Fallback 2: el array vacío que Realtime Database descarta al guardar.
                val restoredComputed = if (!dataObj.has("perAppInternetBlocked")) {
                    val restored = org.json.JSONObject(dataObj.toString())
                    restored.put("perAppInternetBlocked", org.json.JSONArray())
                    computeHmacSha256(canonicalizeJson(restored))
                } else {
                    ""
                }
                if (!legacyComputed.equals(signature, ignoreCase = true) &&
                    (restoredComputed.isEmpty() || !restoredComputed.equals(signature, ignoreCase = true))
                ) {
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
                // normalizeRestrictionKey(): el panel venía escribiendo "no_apps_control",
                // que NO es la constante de Android (es "no_control_apps"). Ese bloqueo
                // simplemente no se aplicaba nunca al importar un perfil hecho en el panel.
                setRestriction(normalizeRestrictionKey(key), enabled)
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
            setMercadoLibreInMpBlocked(dataObj.optBoolean("blockMlInMp", false))
            setKosherLauncherEnabled(dataObj.optBoolean("kosherLauncherEnabled", false))

            // 2/9/2026 — interruptores nuevos del perfil. El valor por omisión es EL ACTUAL,
            // no `false`, a propósito: un perfil viejo (guardado antes de que existieran
            // estas claves) no debe APAGAR protecciones que el equipo ya tiene puestas —
            // en particular el arranque protegido y la protección de accesibilidad, que
            // vienen encendidas por defecto. Un perfil solo cambia lo que dice.
            setFlashingBlocked(dataObj.optBoolean("flashingBlocked", isFlashingBlocked()))
            setHideSuspendedApps(dataObj.optBoolean("hideSuspendedApps", isHideSuspendedApps()))
            setAccessibilityProtection(dataObj.optBoolean("accessibilityProtection", isAccessibilityProtectionEnabled()))
            setAccBounceSettings(dataObj.optBoolean("accBounceSettings", isAccBounceSettingsEnabled()))
            setAccNag(dataObj.optBoolean("accNag", isAccNagEnabled()))
            setAccSuspendAll(dataObj.optBoolean("accSuspendAll", isAccSuspendAllEnabled()))
            setBootGateEnabled(dataObj.optBoolean("bootGateEnabled", isBootGateEnabled()))
            setBootGateWaitAccessibility(dataObj.optBoolean("bootGateWaitAccessibility", isBootGateWaitAccessibilityEnabled()))
            setImageBlockStrictScroll(dataObj.optBoolean("imageBlockStrictScroll", isImageBlockStrictScrollEnabled()))
            setKioskLockTaskEnabled(dataObj.optBoolean("kioskLockTask", isKioskLockTaskEnabled()))
            setNokiaKeypadMode(dataObj.optBoolean("nokiaKeypadMode", isNokiaKeypadMode()))
            setNokiaTouchEnabled(dataObj.optBoolean("nokiaTouchEnabled", isNokiaTouchEnabled()))

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

    /**
     * Traduce claves de restricción mal escritas en perfiles viejos a la constante real
     * de `UserManager`. Una clave que Android no conoce se acepta sin error y no hace
     * absolutamente nada — falla en silencio, que es lo peor posible para una restricción
     * de seguridad. Mapear en vez de ignorar deja funcionando los perfiles ya guardados.
     */
    private fun normalizeRestrictionKey(key: String): String = when (key) {
        // El panel escribía "no_apps_control"; la constante real es DISALLOW_APPS_CONTROL
        // == "no_control_apps".
        "no_apps_control" -> UserManager.DISALLOW_APPS_CONTROL
        else -> key
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

    // ──────────────────────────────────────────────
    // PROTECCIÓN DEL SERVICIO DE ACCESIBILIDAD
    // ──────────────────────────────────────────────

    fun isAccessibilityProtectionEnabled(): Boolean =
        PrefsHelper.getMdmPrefs(context).getBoolean("accessibility_protection_enabled", true)

    fun setAccessibilityProtection(enable: Boolean): Boolean {
        if (deferIfSuspended("accessibility_protection_enabled", enable)) return true
        val ok = applyAccessibilityProtection(enable)
        PrefsHelper.getMdmPrefs(context).edit().putBoolean("accessibility_protection_enabled", enable).apply()
        return ok
    }

    // ──────────────────────────────────────────────
    // Sub-interruptores de "Protecciones de Accesibilidad"  (17/8/2026)
    //
    // Todos cuelgan del interruptor maestro `accessibility_protection_enabled`: si el
    // maestro está apagado, ninguno de estos hace nada. Van APAGADOS por defecto a
    // propósito — cada uno tiene un costo de comodidad para el usuario final, y quién
    // lo paga lo decide el administrador, no el código.
    //
    // Aclaración que hay que repetir cada vez que se toca esto: Android NO tiene
    // ninguna API para impedir que se desactive un servicio de accesibilidad. No existe
    // `DISALLOW_CONFIG_ACCESSIBILITY`, `setSecureSetting()` de Device Owner no acepta
    // `ENABLED_ACCESSIBILITY_SERVICES`, y volver a encenderlo por código necesita
    // `WRITE_SECURE_SETTINGS`, que un Device Owner no tiene. Es deliberado de Google:
    // si un MDM pudiera clavar un servicio de accesibilidad, cualquier spyware con
    // Device Owner también podría. Lo que sigue no cierra esa puerta — la hace lo
    // bastante molesta como para que no valga la pena cruzarla.
    // ──────────────────────────────────────────────

    /** Rebotar al usuario si entra al menú de Accesibilidad de Ajustes. */
    fun isAccBounceSettingsEnabled(): Boolean =
        PrefsHelper.getMdmPrefs(context).getBoolean("acc_protect_bounce_settings", false)

    fun setAccBounceSettings(enable: Boolean): Boolean {
        if (deferIfSuspended("acc_protect_bounce_settings", enable)) return true
        PrefsHelper.getMdmPrefs(context).edit().putBoolean("acc_protect_bounce_settings", enable).apply()
        return true
    }

    /**
     * Pide al Watchdog una revisión inmediata del estado de accesibilidad.
     *
     * Sin esto, los interruptores de abajo tardaban hasta 20 segundos en hacer efecto
     * (el ciclo del Watchdog). Quien los prueba toca el interruptor, mira el equipo, no
     * ve nada, y concluye que están rotos — que fue exactamente lo que pasó el 17/8.
     */
    private fun kickWatchdog() {
        try {
            com.ejemplo.locksuite.service.WatchdogForegroundService.requestImmediateCheck()
        } catch (e: Exception) {
            // El Watchdog puede no estar corriendo todavía; no es un error.
        }
    }

    /** Aviso insistente cada ~18 s mientras la accesibilidad esté apagada. */
    fun isAccNagEnabled(): Boolean =
        PrefsHelper.getMdmPrefs(context).getBoolean("acc_protect_nag", false)

    fun setAccNag(enable: Boolean): Boolean {
        if (deferIfSuspended("acc_protect_nag", enable)) return true
        PrefsHelper.getMdmPrefs(context).edit().putBoolean("acc_protect_nag", enable).apply()
        kickWatchdog()
        return true
    }

    /** Suspender TODAS las apps no críticas (no solo navegadores) mientras esté apagada. */
    fun isAccSuspendAllEnabled(): Boolean =
        PrefsHelper.getMdmPrefs(context).getBoolean("acc_protect_suspend_all", false)

    fun setAccSuspendAll(enable: Boolean): Boolean {
        if (deferIfSuspended("acc_protect_suspend_all", enable)) return true
        PrefsHelper.getMdmPrefs(context).edit().putBoolean("acc_protect_suspend_all", enable).apply()
        // El reconciliador decide y aplica: sabe combinar este interruptor con el del
        // arranque protegido y corrige contra el estado real de cada app. Acá no hay que
        // ordenar nada — ordenar por separado era justamente lo que hacía que los dos
        // mecanismos se pisaran. Ver util/AccessibilityEnforcer.kt.
        try {
            com.ejemplo.locksuite.util.AccessibilityEnforcer.reconcileNow(context)
        } catch (e: Exception) {
            android.util.Log.w("PolicyManager", "No se pudo reconciliar la suspensión: ${e.message}")
        }
        kickWatchdog()
        return true
    }

    // ──────────────────────────────────────────────
    // Arranque protegido  (ver util/BootGate.kt)
    // ──────────────────────────────────────────────

    /** Encendido por defecto: es el que tapa el hueco del reinicio. */
    fun isBootGateEnabled(): Boolean =
        PrefsHelper.getMdmPrefs(context).getBoolean("boot_gate_enabled", true)

    fun setBootGateEnabled(enable: Boolean): Boolean {
        if (deferIfSuspended("boot_gate_enabled", enable)) return true
        com.ejemplo.locksuite.util.BootGate.setEnabled(context, enable)
        return true
    }

    /** Además del filtro de red, esperar a que la Accesibilidad esté activa. */
    fun isBootGateWaitAccessibilityEnabled(): Boolean =
        PrefsHelper.getMdmPrefs(context).getBoolean("boot_gate_wait_accessibility", false)

    fun setBootGateWaitAccessibility(enable: Boolean): Boolean {
        if (deferIfSuspended("boot_gate_wait_accessibility", enable)) return true
        PrefsHelper.getMdmPrefs(context).edit().putBoolean("boot_gate_wait_accessibility", enable).apply()
        return true
    }

    // ──────────────────────────────────────────────
    // Bloqueo de imágenes: tapado estricto al desplazar
    // ──────────────────────────────────────────────

    /**
     * Con esto encendido, mientras el usuario desplaza rápido se tapa el contenedor
     * ENTERO y al frenar vuelven los recuadros exactos: cero riesgo de que se vea una
     * imagen un par de frames, a cambio de tapar de más durante el movimiento.
     * Apagado = solo seguimiento rápido, nunca tapa de más.
     */
    fun isImageBlockStrictScrollEnabled(): Boolean =
        PrefsHelper.getMdmPrefs(context).getBoolean("image_block_strict_scroll", false)

    fun setImageBlockStrictScroll(enable: Boolean): Boolean {
        if (deferIfSuspended("image_block_strict_scroll", enable)) return true
        PrefsHelper.getMdmPrefs(context).edit().putBoolean("image_block_strict_scroll", enable).apply()
        return true
    }

    /**
     * Aplica la protección al sistema vía DevicePolicyManager.
     * Si enable=true, solo LockSuite puede tener un servicio de accesibilidad activo.
     * Si enable=false o null, cualquier servicio permitido por el usuario funciona.
     */
    fun applyAccessibilityProtection(enable: Boolean): Boolean {
        return try {
            val list = if (enable) listOf(context.packageName) else null
            dpm.setPermittedAccessibilityServices(adminComponent, list)
            android.util.Log.i("PolicyManager", "setPermittedAccessibilityServices: $list")
            true
        } catch (e: Exception) {
            android.util.Log.w("PolicyManager", "Error aplicando protección de accesibilidad: ${e.message}")
            false
        }
    }

    // ─────────────────────────────────────────────
    // PERSISTENCIA Y REAPLICACIÓN
    // ─────────────────────────────────────────────

    fun isRestrictionEnabled(restriction: String): Boolean {
        val prefs = PrefsHelper.getMdmPrefs(context)
        if (restriction == UserManager.DISALLOW_CONFIG_PRIVATE_DNS && !prefs.contains(restriction)) {
            return true // Bloquear configuración de DNS privado por defecto para proteger el filtro
        }
        return prefs.getBoolean(restriction, false)
    }

    fun reapplyAllRestrictions() {
        // Mientras LockSuite está suspendido nadie vuelve a aplicar nada: ni el
        // arranque, ni el Watchdog de 15 min, ni un comando del panel. La única
        // forma de volver a aplicar es quitar la suspensión, que llama a esta
        // misma función después de bajar la marca.
        if (isLockSuiteSuspended()) {
            android.util.Log.i("PolicyManager", "LockSuite suspendido: omitiendo reapplyAllRestrictions")
            return
        }
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
            UserManager.DISALLOW_CONFIG_VPN,
            // 18/8/2026: sin esto, cambiar el idioma del equipo evade cualquier filtro
            // que dependa de leer texto de la pantalla. Ver setLocaleChangeBlocked().
            UserManager.DISALLOW_CONFIG_LOCALE,
            // ⚠️ BUG ENCONTRADO EL 2/9/2026 CON UN CHEQUEO AUTOMÁTICO DE SIMETRÍA.
            //
            // `DISALLOW_CONFIG_DATE_TIME` estaba en `liftAllForSuspension()` y en
            // `clearAllRestrictions()` (las dos se lo agregaron el 1/9) pero NO acá. O sea
            // que era una restricción que el proyecto sabía levantar y limpiar, pero **no
            // sabía volver a aplicar después de un reinicio**: si alguna vez quedaba
            // activada, desaparecía sola al bootear, en silencio.
            //
            // Hasta el 2/9 era inofensivo porque nada la activaba (B.10 la tiene como "no
            // implementada"). Pero al sumarla al perfil exportable pasó a ser activable, y
            // ahí la asimetría se vuelve un bug real: aplicás un perfil, el equipo bloquea
            // la fecha, reiniciás, y el bloqueo se fue sin que nada lo diga. Se completa
            // acá para que las cuatro listas —aplicar, suspender, purgar y perfil— digan lo
            // mismo. Es exactamente el mismo defecto que S-3 y P-3, encontrado esta vez
            // antes de que costara una sesión de diagnóstico.
            UserManager.DISALLOW_CONFIG_DATE_TIME
        )

        // Las del registro declarativo se suman acá, así una restricción nueva sobrevive a
        // un reinicio sin que nadie tenga que acordarse de agregarla a esta lista también.
        // Ver mdm/PolicySpec.kt.
        val todas = restrictions + PolicySpec.EXTRA_RESTRICTIONS.map { it.restriction }

        val isInstallInProgress = PrefsHelper.getMdmPrefs(context).getBoolean("mdm_install_in_progress", false)
        todas.forEach { restriction ->
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

        // Kiosco real del SO. Se reaplica SIEMPRE (con el valor que corresponda), no solo
        // cuando está encendido: la lista blanca de Lock Task es estado del sistema, no una
        // preferencia nuestra, y si quedó puesta con el interruptor apagado el equipo
        // seguiría anclado sin que el panel lo muestre. Ver applyKioskLockTask().
        applyKioskLockTask(isKioskLockTaskEnabled())



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
        // BUG CORREGIDO (16/8/2026): este bloque no miraba "mdm_install_in_progress".
        // El Watchdog corre cada 15 minutos y el flujo de actualización dura hasta
        // 10, así que se pisaban: el Watchdog volvía a suspender Play Store con la
        // descarga a medias, Android mostraba "aplicación en pausa" debajo del
        // overlay negro, la automatización ya no encontraba ningún botón y la
        // pantalla quedaba trabada hasta el watchdog de 10 minutos.
        if (!isInstallInProgress) {
            val shouldSuspendPlayStore = prefs.getBoolean("suspend_com.android.vending", prefs.getBoolean("install_apps_blocked_admin", false))
            try {
                appController.suspendApp("com.android.vending", shouldSuspendPlayStore)
                android.util.Log.i("PolicyManager", "reapplyAllRestrictions: Google Play Store estado de suspensión aplicado ($shouldSuspendPlayStore)")
            } catch (e: Exception) {
                e.printStackTrace()
            }
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

        // Re-aplicar suspensiones individuales de aplicaciones (solo si están suspendidas explícitamente).
        // Igual que arriba: la app que se está actualizando queda exenta mientras
        // dure el flujo, o el Watchdog la suspendería en plena instalación.
        val updatingPkgNow = prefs.getString("updating_package", null)
        val userApps = appController.getUserApps(loadIcon = false)
        for (app in userApps) {
            if (!app.isCritical && app.packageName != "com.android.vending" &&
                app.packageName != updatingPkgNow) {
                // ARREGLO 1/9/2026 (S-2): el ocultamiento va PRIMERO y antes faltaba
                // entero. liftAllForSuspension() des-oculta todas las apps, y al reanudar
                // nadie las volvía a ocultar: `hide_<paquete>` no se leía en ningún lado
                // salvo para Play Store. O sea que suspender y reanudar dejaba las apps
                // ocultas visibles PARA SIEMPRE, con el panel diciendo lo contrario.
                //
                // El orden importa: hideApp(pkg, true) deshabilita el paquete, y
                // suspenderlo después no aporta nada; además hideApp(pkg, false) ya
                // re-suspende solo si corresponde. Por eso: primero ocultar, y suspender
                // solo si NO quedó oculta.
                val debeOcultarse = prefs.getBoolean("hide_${app.packageName}", false)
                if (debeOcultarse) {
                    appController.hideApp(app.packageName, true)
                    continue
                }

                val isIndividuallySuspended = prefs.getBoolean("suspend_${app.packageName}", false)
                if (isIndividuallySuspended) {
                    appController.suspendApp(app.packageName, true)
                }
            }
        }

        // Re-aplicar restricciones de instalación
        refreshInstallRestriction()

        // Re-aplicar protección del servicio de accesibilidad
        if (isAccessibilityProtectionEnabled()) {
            applyAccessibilityProtection(true)
        }
    }

    // ─────────────────────────────────────────────
    // SUSPENSIÓN TEMPORAL DE LOCKSUITE
    //
    // "Suspender" deja el equipo exactamente como si LockSuite no estuviera
    // instalado, y al desactivarla vuelve todo a como estaba.
    //
    // La clave del diseño es que la suspensión NO borra ni cambia ninguna de las
    // preferencias que guardan la configuración deseada. Solo levanta el estado
    // real en el sistema operativo. Como reapplyAllRestrictions() reconstruye
    // todo leyendo esas mismas preferencias, quitar la suspensión es simplemente
    // volver a llamarla: no hace falta guardar ninguna copia del estado previo,
    // que es justo donde este tipo de función se suele romper (copia incompleta,
    // copia pisada por otro cambio, copia perdida al reiniciar).
    //
    // Por eso acá se llama directo a `dpm.*` en vez de a los setters de esta
    // misma clase: los setters escriben la preferencia, y eso destruiría la
    // configuración que hay que restaurar después.
    //
    // Alcance (decidido con el dueño del proyecto el 16/8/2026): se levanta
    // ABSOLUTAMENTE TODO, incluidas las protecciones anti-manipulación (bloqueo
    // de restauración de fábrica, Knox/flasheo, FRP y el bloqueo de desinstalar
    // LockSuite). Es una suspensión literal. Implica que, mientras dure, el
    // usuario puede desinstalar LockSuite o formatear el equipo y no habría
    // vuelta atrás: usarla solo con el equipo a la vista.
    // ─────────────────────────────────────────────

    fun isLockSuiteSuspended(): Boolean {
        return PrefsHelper.getMdmPrefs(context).getBoolean("locksuite_suspended", false)
    }

    fun getLockSuiteSuspendedAt(): Long {
        return PrefsHelper.getMdmPrefs(context).getLong("locksuite_suspended_at", 0L)
    }

    fun setLockSuiteSuspended(suspend: Boolean): Boolean {
        val prefs = PrefsHelper.getMdmPrefs(context)
        if (isLockSuiteSuspended() == suspend) return true

        return try {
            if (suspend) {
                // Cortar cualquier actualización en curso ANTES de soltar las
                // políticas: si no, quedaría una pantalla negra encima de un
                // equipo que ya no tiene ninguna restricción.
                try {
                    if (com.ejemplo.locksuite.util.UpdateFlowManager.isRunning(context)) {
                        com.ejemplo.locksuite.util.UpdateFlowManager.forceCleanup(
                            context,
                            com.ejemplo.locksuite.util.UpdateFlowManager.RESULT_CANCELLED
                        )
                    }
                } catch (e: Exception) {
                    e.printStackTrace()
                }

                // La marca va primero: los watchdogs (Foreground cada 20 s,
                // Worker cada 15 min, BootReceiver) la leen para no volver a
                // aplicar nada mientras dure la suspensión. Si se levantaran las
                // políticas antes de marcar, el watchdog podía re-imponerlas en
                // el medio y dejar el equipo a mitad de camino.
                prefs.edit()
                    .putBoolean("locksuite_suspended", true)
                    .putLong("locksuite_suspended_at", System.currentTimeMillis())
                    .apply()
                liftAllForSuspension()
            } else {
                prefs.edit()
                    .putBoolean("locksuite_suspended", false)
                    .remove("locksuite_suspended_at")
                    .apply()
                // reapplyAllRestrictions() reconstruye TODO desde las preferencias
                // (restricciones, hardware, launcher, VPN, FRP, Knox, suspensiones
                // individuales de apps y bloqueo de instalación).
                reapplyAllRestrictions()
                try {
                    com.ejemplo.locksuite.receiver.BootReceiver.ensureVpnRunning(context)
                } catch (e: Exception) {
                    e.printStackTrace()
                }
            }
            try {
                com.ejemplo.locksuite.util.FirebaseDeviceSync.syncDeviceInfo(context)
            } catch (e: Exception) {
                e.printStackTrace()
            }
            true
        } catch (e: Exception) {
            e.printStackTrace()
            false
        }
    }

    /**
     * Levanta el estado real en el sistema sin tocar ninguna preferencia.
     * Cada paso va en su propio try/catch: que un fabricante rechace una
     * llamada puntual no puede dejar el equipo a medio liberar.
     */
    private fun liftAllForSuspension() {
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
            "no_config_mobile_networks",
            UserManager.DISALLOW_ADJUST_VOLUME,
            UserManager.DISALLOW_APPS_CONTROL,
            UserManager.DISALLOW_BLUETOOTH,
            UserManager.DISALLOW_BLUETOOTH_SHARING,
            UserManager.DISALLOW_MOUNT_PHYSICAL_MEDIA,
            UserManager.DISALLOW_CONFIG_TETHERING,
            UserManager.DISALLOW_CONFIG_VPN,
            UserManager.DISALLOW_CONFIG_DATE_TIME,
            // ARREGLO 1/9/2026 (S-3): faltaba. Está en reapplyAllRestrictions() desde el
            // 18/8 (B.19) pero no acá, así que un equipo "suspendido" seguía sin poder
            // cambiar de idioma — justo lo contrario de "queda como si LockSuite no
            // estuviera instalado".
            UserManager.DISALLOW_CONFIG_LOCALE
        ) + PolicySpec.EXTRA_RESTRICTIONS.map { it.restriction }
        // ⚠️ 2/9/2026 — Las del registro declarativo se suman acá SIEMPRE, y esto no es
        // opcional: una restricción que se aplica y no se levanta convierte la suspensión
        // en mentira. Es literalmente el defecto S-3 del 1/9 (DISALLOW_CONFIG_LOCALE
        // estaba en reapply y no acá). Sumarlas desde la misma lista que las aplica hace
        // que el bug no se pueda repetir por olvido.
        // El kiosco se suelta SIEMPRE al suspender: si no, "queda como si LockSuite no
        // estuviera instalado" sería mentira — el equipo seguiría sin poder abrir nada
        // fuera de la lista blanca. Mismo razonamiento que S-3 con el idioma.
        applyKioskLockTask(false)
        // El táctil vuelve SIEMPRE al suspender. Un equipo "sin LockSuite" que no responde
        // al dedo no es un equipo sin LockSuite. (El modo de pantalla se deja como está:
        // es solo estético y no impide usar el equipo.)
        PrefsHelper.getMdmPrefs(context).edit().putBoolean("nokia_touch_enabled", true).apply()

        allRestrictions.forEach { restriction ->
            try {
                dpm.clearUserRestriction(adminComponent, restriction)
            } catch (e: Exception) {
                android.util.Log.w("PolicyManager", "No se pudo levantar $restriction: ${e.message}")
            }
        }

        // Hardware y pantalla
        safely { dpm.setCameraDisabled(adminComponent, false) }
        safely { dpm.setScreenCaptureDisabled(adminComponent, false) }
        safely { dpm.setStatusBarDisabled(adminComponent, false) }
        safely { dpm.setKeyguardDisabled(adminComponent, false) }

        // Proxy global de "bloquear todo el internet"
        safely { dpm.setRecommendedGlobalProxy(adminComponent, null) }

        // Always-on VPN designada por el MDM
        safely {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                dpm.setAlwaysOnVpnPackage(adminComponent, null, false)
            }
        }

        // Launcher Kosher: devolver el launcher nativo y parar la marca de agua
        safely { dpm.clearPackagePersistentPreferredActivities(adminComponent, context.packageName) }
        safely {
            context.stopService(Intent(context, com.ejemplo.locksuite.service.WatermarkService::class.java))
        }

        // Protecciones anti-manipulación (decisión del 16/8/2026: se levantan)
        safely { dpm.setUninstallBlocked(adminComponent, context.packageName, false) }
        safely { dpm.setPermittedAccessibilityServices(adminComponent, null) }
        safely { clearFrpPolicy() }
        safely { KnoxHardening.setFactoryResetBlocked(context, false) }
        safely { KnoxHardening.setFlashingBlocked(context, false) }

        // Cortar el filtro de red
        safely {
            context.stopService(Intent(context, com.ejemplo.locksuite.service.KosherVpnService::class.java))
        }

        // Desbloquear TODAS las apps: des-suspender en un solo llamado (mucho más
        // rápido que uno por paquete) y des-ocultar solo las que están ocultas.
        try {
            val pm = context.packageManager
            val flags = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                PackageManager.MATCH_UNINSTALLED_PACKAGES
            } else {
                @Suppress("DEPRECATION")
                PackageManager.GET_UNINSTALLED_PACKAGES
            }
            val packages = pm.getInstalledApplications(flags).map { it.packageName }

            packages.chunked(50).forEach { chunk ->
                safely { dpm.setPackagesSuspended(adminComponent, chunk.toTypedArray(), false) }
            }
            packages.forEach { pkg ->
                try {
                    if (dpm.isApplicationHidden(adminComponent, pkg)) {
                        dpm.setApplicationHidden(adminComponent, pkg, false)
                    }
                } catch (e: Exception) {
                    // Paquetes del sistema que no admiten el cambio: se ignoran.
                }
            }
        } catch (e: Exception) {
            android.util.Log.e("PolicyManager", "Error desbloqueando apps para la suspensión", e)
        }

        android.util.Log.w("PolicyManager", "LockSuite SUSPENDIDO: todas las restricciones levantadas")
    }

    private inline fun safely(block: () -> Unit) {
        try {
            block()
        } catch (e: Exception) {
            android.util.Log.w("PolicyManager", "Paso de suspensión omitido: ${e.message}")
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
            UserManager.DISALLOW_CONFIG_VPN,
            // ARREGLO 1/9/2026 (P-3): faltaban las dos. Sin la de idioma, un equipo al
            // que se le encendió el interruptor de B.19 queda SIN PODER CAMBIAR DE
            // IDIOMA para siempre después de la purga, y ya no hay ninguna app con
            // Device Owner que pueda deshacerlo.
            UserManager.DISALLOW_CONFIG_DATE_TIME,
            UserManager.DISALLOW_CONFIG_LOCALE
        ) + PolicySpec.EXTRA_RESTRICTIONS.map { it.restriction }
        // ⚠️ 2/9/2026 — Igual que en liftAllForSuspension(): las del registro se suman
        // desde la MISMA lista que las aplica. Este es el defecto P-3 del 1/9 llevado a su
        // conclusión: una restricción que la purga no limpia deja el equipo PEOR que antes
        // de instalar LockSuite, y sin ninguna app con Device Owner que pueda deshacerlo.

        // ⚠️ ANTES QUE NADA. Un equipo purgado que quede en Lock Task no puede abrir
        // ninguna app fuera de la lista, y ya NO hay ninguna app con Device Owner que pueda
        // soltarlo: quedaría inutilizable para siempre. Es el mismo error que P-3 con el
        // idioma, pero peor. Va primero por si algo falla más abajo.
        applyKioskLockTask(false)
        // Igual que arriba, y por un motivo más fuerte: después de la purga ya no queda
        // ninguna app con Device Owner que pueda devolver el táctil ni sacar el modo teclas.
        PrefsHelper.getMdmPrefs(context).edit()
            .putBoolean("nokia_touch_enabled", true)
            .putBoolean("nokia_keypad_mode", false)
            .apply()

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

        // Permitir otros servicios de accesibilidad tras la purga
        try {
            dpm.setPermittedAccessibilityServices(adminComponent, null)
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

        // ARREGLO 1/9/2026 (P-1 y P-4). Esto faltaba entero: la purga solo des-suspendía
        // Play Store, los navegadores y el WebView, así que toda app suspendida una por
        // una desde el panel quedaba SUSPENDIDA después de que LockSuite ya no estaba —
        // y como abajo se limpian las preferencias, se perdía hasta la lista de cuáles
        // eran. El código es el mismo que ya usa liftAllForSuspension(); la única razón
        // de que no estuviera acá es que las dos funciones se escribieron por separado.
        //
        // Va DESPUÉS de todo lo demás y ANTES de limpiar las preferencias, a propósito:
        // el paso 1 de executeFullPurge() re-suspende apps al des-ocultarlas
        // (hideApp(pkg,false) vuelve a suspender si existe suspend_<paquete>), así que
        // esto tiene que ser lo último que toque la suspensión.
        try {
            val pm = context.packageManager
            val flags = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                PackageManager.MATCH_UNINSTALLED_PACKAGES
            } else {
                @Suppress("DEPRECATION")
                PackageManager.GET_UNINSTALLED_PACKAGES
            }
            val packages = pm.getInstalledApplications(flags).map { it.packageName }
            // En tandas de 50: setPackagesSuspended con listas enormes puede fallar entera.
            packages.chunked(50).forEach { chunk ->
                try {
                    dpm.setPackagesSuspended(adminComponent, chunk.toTypedArray(), false)
                } catch (e: Exception) {
                    android.util.Log.w("PolicyManager", "Purga: no se pudo liberar una tanda: ${e.message}")
                }
            }
            // Y des-ocultar lo que haya quedado oculto. executeFullPurge() ya lo hace en
            // su paso 1, pero esto es la red de seguridad: si aquel paso falló para
            // alguna app, una app oculta es una app DESHABILITADA, que es lo peor que se
            // le puede dejar a un equipo del que ya no tenemos control.
            packages.forEach { pkg ->
                try {
                    if (dpm.isApplicationHidden(adminComponent, pkg)) {
                        dpm.setApplicationHidden(adminComponent, pkg, false)
                    }
                } catch (e: Exception) {
                    // Paquetes del sistema que no admiten el cambio: se ignoran.
                }
            }
        } catch (e: Exception) {
            android.util.Log.e("PolicyManager", "Purga: error liberando apps", e)
        }

        // P-4: soltar la designación de Always-on VPN, igual que hace
        // liftAllForSuspension(). Si no, el sistema sigue teniendo a LockSuite anotada
        // como la VPN permanente de un paquete que ya no aplica ninguna política.
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                dpm.setAlwaysOnVpnPackage(adminComponent, null, false)
            }
        } catch (e: Exception) {
            android.util.Log.w("PolicyManager", "Purga: no se pudo limpiar Always-on VPN: ${e.message}")
        }

        // Clear local preferences (incluida la marca de suspensión: tras la purga
        // no queda ninguna política que suspender)
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
        if (deferIfSuspended("browsers_suspended", suspend)) return true
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
        if (deferIfSuspended("system_webview_suspended", suspend)) return true
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
