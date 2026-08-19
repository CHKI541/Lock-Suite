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

    private companion object {
        /**
         * `ApplicationInfo.FLAG_SUSPENDED`. Es API pública desde Android 7 (API 24) pero
         * la constante está marcada como oculta en algunas versiones del SDK, así que se
         * usa el valor literal. Permite saber si una app está suspendida **sin una
         * llamada extra por app**: viene en los flags que ya devuelve
         * `getInstalledApplications()`.
         */
        private const val FLAG_SUSPENDED = 1 shl 30

        /**
         * Qué apps son candidatas a la suspensión de emergencia. Se cachea porque el
         * reconciliador corre cada 5 segundos mientras la accesibilidad está caída, y
         * calcular esto implica un `getLaunchIntentForPackage()` —una llamada al
         * sistema— **por cada app del sistema**. En un equipo con 150 apps de sistema
         * eso serían 150 llamadas cada 5 segundos: justo el tipo de gasto de batería
         * que el dueño pidió evitar. La lista solo cambia al instalar o desinstalar,
         * así que un cache con vencimiento alcanza de sobra.
         */
        @Volatile private var eligibleCache: Set<String>? = null
        @Volatile private var eligibleCacheAt = 0L
        @Volatile private var eligibleCacheSize = 0
        private const val ELIGIBLE_TTL_MS = 5 * 60 * 1000L
    }

    /**
     * Además del vencimiento por tiempo, el cache se recalcula si cambió la cantidad de
     * apps instaladas: así una app recién instalada entra a la suspensión de emergencia
     * en el ciclo siguiente y no dentro de cinco minutos.
     */
    private fun eligiblePackages(installed: List<ApplicationInfo>): Set<String> {
        val now = android.os.SystemClock.elapsedRealtime()
        val cached = eligibleCache
        if (cached != null &&
            now - eligibleCacheAt < ELIGIBLE_TTL_MS &&
            eligibleCacheSize == installed.size
        ) return cached

        val fresh = HashSet<String>(installed.size)
        for (app in installed) {
            val pkg = app.packageName
            if (isCritical(pkg) || isPartialBlockOnly(pkg)) continue
            // Las apps del sistema sin lanzador no aportan nada al suspenderlas y sí
            // pueden romper cosas.
            if ((app.flags and ApplicationInfo.FLAG_SYSTEM) != 0 &&
                pm.getLaunchIntentForPackage(pkg) == null
            ) continue
            fresh.add(pkg)
        }
        eligibleCache = fresh
        eligibleCacheAt = now
        eligibleCacheSize = installed.size
        return fresh
    }

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

    /**
     * Suspende (o des-suspende) DE UNA SOLA VEZ todas las apps de usuario que no sean
     * críticas para el sistema.
     *
     * Lo usa la Protección de Accesibilidad cuando detecta que el servicio quedó
     * apagado (interruptor `acc_protect_suspend_all`). Hasta ahora en ese caso solo se
     * suspendían los navegadores, así que el equipo seguía siendo usable: se podía
     * abrir WhatsApp, la galería o cualquier otra cosa con el filtro visual caído.
     *
     * Tres decisiones de implementación que importan:
     *
     *  • Una sola llamada a `setPackagesSuspended` con TODO el arreglo, en vez de una
     *    por app. Cada llamada es un IPC al sistema; en un equipo con 80 apps la
     *    diferencia es entre una llamada y ochenta.
     *  • NO escribe las preferencias `suspend_<paquete>`. Esas preferencias representan
     *    lo que el administrador decidió; esto es un estado de emergencia temporal. Si
     *    lo escribiera, al reactivar la accesibilidad las apps quedarían suspendidas
     *    "a propósito" para siempre.
     *  • Al levantar la emergencia se respeta lo que el administrador SÍ había
     *    suspendido: esas apps no se reactivan.
     */
    fun setEmergencySuspendAll(suspend: Boolean): Boolean =
        reconcileEmergencySuspend(suspend) >= 0

    /**
     * RECONCILIADOR de la suspensión de emergencia.
     *
     * ⚠️ REESCRITO EL 18/8/2026. La versión anterior ordenaba "suspendé todo" o "soltá
     * todo" de una sola vez y se olvidaba. El problema que causaba, textual del dueño:
     * *"a veces las apps se suspenden y a veces no, o se suspenden y vuelven a
     * aparecer"*. Porque hay varios mecanismos que tocan la suspensión de apps
     * —`reapplyAllRestrictions()` del Watchdog de 15 minutos, el flujo de actualización
     * de Play Store, los comandos del panel— y cualquiera de ellos podía dejar una app
     * abierta de nuevo sin que nadie se enterara.
     *
     * Ahora esto no ordena: **compara y corrige**. Para cada app calcula cuál debería
     * ser su estado y lo contrasta con el estado REAL en el sistema; solo toca las que
     * no coinciden. Consecuencias:
     *
     *  • **Se auto-repara.** No importa quién pisó qué ni cuándo: en el próximo ciclo
     *    vuelve a quedar como corresponde.
     *  • **Es idempotente.** Llamarla mil veces seguidas cuesta lo mismo que una: si no
     *    hay nada distinto, no hace ninguna llamada al DevicePolicyManager.
     *  • **Es barata.** El estado real de suspensión viene en `ApplicationInfo.flags`
     *    (`FLAG_SUSPENDED`), así que sale de la MISMA llamada que ya enumeraba las
     *    apps. No hay una consulta por app.
     *
     * @return cuántas apps hubo que cambiar (0 = todo estaba bien), o -1 si falló.
     */
    fun reconcileEmergencySuspend(emergencyActive: Boolean): Int {
        return try {
            val prefs = PrefsHelper.getMdmPrefs(context)
            // Con LockSuite suspendido (B.11) el equipo tiene que quedar como si la app
            // no estuviera instalada: acá solo se LIBERA, nunca se vuelve a suspender
            // nada, ni siquiera lo que el administrador tenía marcado. Sin esta guarda,
            // una reconciliación durante la suspensión reimponía `suspend_<paquete>` y
            // pisaba lo que acababa de hacer liftAllForSuspension().
            val locksuiteSuspendido = prefs.getBoolean("locksuite_suspended", false)

            val installed = pm.getInstalledApplications(0)
            val elegibles = eligiblePackages(installed)
            val aSuspender = ArrayList<String>()
            val aLiberar = ArrayList<String>()

            for (app in installed) {
                val pkg = app.packageName
                if (pkg !in elegibles) continue

                // Estado que DEBERÍA tener: durante la emergencia, suspendida; fuera de
                // la emergencia, lo que el administrador haya decidido para esa app.
                // Con LockSuite suspendido, nada: ver la guarda de arriba.
                val deberia = !locksuiteSuspendido &&
                    (emergencyActive || prefs.getBoolean("suspend_$pkg", false))

                // Estado REAL, sin una llamada extra: viene en los flags.
                val esta = (app.flags and FLAG_SUSPENDED) != 0

                if (deberia && !esta) aSuspender.add(pkg)
                else if (!deberia && esta) aLiberar.add(pkg)
            }

            var cambios = 0
            // En tandas: `setPackagesSuspended` con listas enormes puede fallar entera
            // por un solo paquete problemático, y así se acota el daño.
            aSuspender.chunked(50).forEach { tanda ->
                try {
                    dpm.setPackagesSuspended(adminComponent, tanda.toTypedArray(), true)
                    cambios += tanda.size
                } catch (e: Exception) {
                    android.util.Log.w("AppController", "No se pudo suspender una tanda: ${e.message}")
                }
            }
            aLiberar.chunked(50).forEach { tanda ->
                try {
                    dpm.setPackagesSuspended(adminComponent, tanda.toTypedArray(), false)
                    cambios += tanda.size
                } catch (e: Exception) {
                    android.util.Log.w("AppController", "No se pudo liberar una tanda: ${e.message}")
                }
            }
            cambios
        } catch (e: Exception) {
            android.util.Log.e("AppController", "reconcileEmergencySuspend($emergencyActive) falló: ${e.message}")
            -1
        }
    }

    fun hideApp(packageName: String, hide: Boolean): Boolean {
        // Ver la nota de PolicyManager.setRestriction: mientras LockSuite está
        // suspendido se registra la intención sin tocar el sistema.
        if (hide && PrefsHelper.getMdmPrefs(context).getBoolean("locksuite_suspended", false)) {
            PrefsHelper.getMdmPrefs(context).edit().putBoolean("hide_$packageName", true).apply()
            return true
        }
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
        if (suspend && PrefsHelper.getMdmPrefs(context).getBoolean("locksuite_suspended", false)) {
            PrefsHelper.getMdmPrefs(context).edit().putBoolean("suspend_$packageName", true).apply()
            return true
        }
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
