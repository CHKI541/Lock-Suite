package com.ejemplo.locksuite.util

import android.app.AlarmManager
import android.app.PendingIntent
import android.app.admin.DevicePolicyManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.os.SystemClock
import android.os.UserManager
import android.util.Log
import com.ejemplo.locksuite.mdm.PolicyManager
import com.ejemplo.locksuite.receiver.DeviceAdminReceiver
import com.ejemplo.locksuite.receiver.PackageReceiver
import com.ejemplo.locksuite.service.LockSuiteAccessibilityService

/**
 * Punto UNICO de control del flujo "actualizar una app por Google Play con la
 * pantalla tapada".
 *
 * Antes esta logica vivia repartida en cuatro archivos (LockSuiteFirebaseService
 * arrancaba, LockSuiteAccessibilityService automatizaba y a veces terminaba,
 * PackageReceiver terminaba por otro lado, y PolicyManager restauraba). Cada uno
 * escribia las mismas SharedPreferences por su cuenta, y no habia un camino de
 * salida unico: cuando el flujo terminaba por un camino que nadie habia previsto
 * (el watchdog de 10 minutos, por ejemplo) el overlay negro quedaba en pantalla
 * para siempre porque ese camino no sabia que tenia que sacarlo.
 *
 * Reglas de este archivo:
 *
 *  1. TODO arranque pasa por [start] y TODA salida pasa por [finish]. No hay
 *     ninguna otra forma valida de tocar "mdm_install_in_progress".
 *  2. [finish] es idempotente y siempre saca el overlay, aunque el flujo ya
 *     figure como terminado. Es la red de seguridad contra pantallas trabadas.
 *  3. Las preferencias se limpian ANTES de mandar HOME. Al reves habia una
 *     carrera real: el evento de accesibilidad del launcher llegaba con
 *     "mdm_install_in_progress" todavia en true y el servicio relanzaba Play
 *     Store, dejando al usuario en un bucle del que no podia salir.
 *  4. La alarma watchdog se cancela recien cuando las restricciones ya quedaron
 *     restauradas, no antes: si el proceso muere en el medio, la alarma sigue
 *     siendo la unica garantia de que el equipo no quede desbloqueado.
 */
object UpdateFlowManager {

    private const val TAG = "LockSuite_Update"
    const val PKG_PLAY_STORE = "com.android.vending"

    // ── Claves de SharedPreferences ──
    const val KEY_PKG = "updating_package"
    const val KEY_IN_PROGRESS = "mdm_install_in_progress"
    const val KEY_STAGE = "update_flow_stage"
    const val KEY_DETAIL = "update_flow_detail"
    const val KEY_STARTED_AT = "update_flow_started_at"
    const val KEY_SOURCE = "update_flow_source"
    const val KEY_CANCELABLE = "update_flow_cancelable"
    const val KEY_BASE_VERSION = "update_flow_base_version"
    const val KEY_LAST_RESULT = "update_flow_last_result"
    const val KEY_LAST_RESULT_AT = "update_flow_last_result_at"
    const val KEY_LAST_RESULT_PKG = "update_flow_last_result_pkg"

    // ── Etapas ──
    const val STAGE_IDLE = "IDLE"
    const val STAGE_PREPARING = "PREPARING"
    const val STAGE_OPENING_STORE = "OPENING_STORE"
    const val STAGE_WAITING_STORE = "WAITING_STORE"
    const val STAGE_LOOKING_BUTTON = "LOOKING_BUTTON"
    const val STAGE_CONFIRMING = "CONFIRMING"
    const val STAGE_DOWNLOADING = "DOWNLOADING"
    const val STAGE_INSTALLING = "INSTALLING"
    const val STAGE_FINISHING = "FINISHING"

    // ── Resultados ──
    const val RESULT_UPDATED = "UPDATED"
    const val RESULT_UP_TO_DATE = "UP_TO_DATE"
    const val RESULT_CANCELLED = "CANCELLED"
    const val RESULT_TIMEOUT = "TIMEOUT"
    const val RESULT_ERROR = "ERROR"

    // ── Origenes ──
    const val SOURCE_PANEL = "panel"
    const val SOURCE_LOCAL = "local"

    const val ACTION_TIMEOUT = "UPDATE_TIMEOUT"
    const val WATCHDOG_REQUEST_CODE = 9911

    /** Tope duro del flujo completo. Pasado esto se restaura si o si. */
    const val TIMEOUT_MS = 10 * 60 * 1000L

    /** Demora entre HOME y re-suspender Play Store, para no disparar el cartel
     *  "aplicacion en pausa" del sistema sobre una app que todavia esta arriba. */
    private const val RESTORE_DELAY_MS = 600L

    private val mainHandler = Handler(Looper.getMainLooper())
    @Volatile private var lastSyncAt = 0L
    @Volatile private var finishing = false

    // ──────────────────────────────────────────────
    // Consultas
    // ──────────────────────────────────────────────

    fun isRunning(context: Context): Boolean {
        val p = PrefsHelper.getMdmPrefs(context)
        return p.getBoolean(KEY_IN_PROGRESS, false) && !p.getString(KEY_PKG, null).isNullOrBlank()
    }

    fun currentPackage(context: Context): String? =
        PrefsHelper.getMdmPrefs(context).getString(KEY_PKG, null)

    fun currentStage(context: Context): String =
        PrefsHelper.getMdmPrefs(context).getString(KEY_STAGE, STAGE_IDLE) ?: STAGE_IDLE

    fun isCancelable(context: Context): Boolean =
        PrefsHelper.getMdmPrefs(context).getBoolean(KEY_CANCELABLE, true)

    fun appLabel(context: Context, packageName: String): String = try {
        context.packageManager.getApplicationLabel(
            context.packageManager.getApplicationInfo(packageName, 0)
        ).toString()
    } catch (e: Exception) {
        packageName
    }

    private fun versionCodeOf(context: Context, packageName: String): Long = try {
        val info = context.packageManager.getPackageInfo(packageName, 0)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) info.longVersionCode
        else @Suppress("DEPRECATION") info.versionCode.toLong()
    } catch (e: Exception) {
        -1L
    }

    /**
     * Senal mas confiable que cualquier texto en pantalla: si el versionCode del
     * paquete cambio respecto del que tenia al arrancar, la actualizacion YA se
     * instalo, sin importar que muestre Play Store ni si llego el broadcast
     * ACTION_PACKAGE_REPLACED.
     */
    fun targetAlreadyUpdated(context: Context): Boolean {
        val pkg = currentPackage(context) ?: return false
        val base = PrefsHelper.getMdmPrefs(context).getLong(KEY_BASE_VERSION, -1L)
        if (base < 0L) return false
        val now = versionCodeOf(context, pkg)
        return now >= 0L && now != base
    }

    /** Texto que ve el usuario en la pantalla negra para cada etapa. */
    fun stageLabel(stage: String?, detail: String?): String {
        if (!detail.isNullOrBlank()) return detail
        return when (stage) {
            STAGE_PREPARING -> "Preparando la actualizacion..."
            STAGE_OPENING_STORE -> "Abriendo Google Play..."
            STAGE_WAITING_STORE -> "Esperando a Google Play..."
            STAGE_LOOKING_BUTTON -> "Buscando el boton Actualizar..."
            STAGE_CONFIRMING -> "Confirmando..."
            STAGE_DOWNLOADING -> "Descargando la actualizacion..."
            STAGE_INSTALLING -> "Instalando..."
            STAGE_FINISHING -> "Terminando y volviendo a bloquear..."
            else -> "Por favor, no toque la pantalla..."
        }
    }

    // ──────────────────────────────────────────────
    // Arranque
    // ──────────────────────────────────────────────

    /**
     * @return null si arranco bien; un mensaje de error si no se pudo arrancar.
     */
    fun start(
        context: Context,
        packageName: String,
        source: String,
        cancelable: Boolean
    ): String? {
        val ctx = context.applicationContext
        val policyManager = PolicyManager(ctx)

        if (packageName.isBlank()) return "No se indico ninguna aplicacion."

        if (policyManager.isLockSuiteSuspended()) {
            return "LockSuite esta suspendido: Google Play ya esta libre, actualiza la app directamente."
        }

        if (isRunning(ctx)) {
            val other = currentPackage(ctx)
            return if (other == packageName) {
                "Ya hay una actualizacion en curso para esta aplicacion."
            } else {
                "Ya hay una actualizacion en curso (${appLabel(ctx, other ?: "")}). Espera a que termine o cancelala."
            }
        }

        // SelfUpdater (auto-actualizacion de LockSuite y Tienda administrada) usa
        // la MISMA bandera "mdm_install_in_progress" pero sin "updating_package".
        // Si arrancaramos encima, el finish() de este flujo la pondria en false y
        // le sacaria la red de seguridad a esa instalacion a mitad de camino.
        if (PrefsHelper.getMdmPrefs(ctx).getBoolean(KEY_IN_PROGRESS, false)) {
            return "Hay otra instalacion en curso en este equipo. Espera a que termine."
        }

        // Sin el servicio de accesibilidad no hay pantalla que tape Play Store:
        // arrancar igual dejaria la tienda abierta y navegable, que es
        // exactamente lo que este flujo existe para impedir.
        if (LockSuiteAccessibilityService.instance == null) {
            return "El servicio de accesibilidad de LockSuite no esta activo. Sin el, la actualizacion dejaria Google Play abierto sin proteccion."
        }

        val installed = try {
            ctx.packageManager.getPackageInfo(packageName, 0)
            true
        } catch (e: Exception) {
            false
        }
        if (!installed) return "La aplicacion $packageName no esta instalada en este equipo."

        val prefs = PrefsHelper.getMdmPrefs(ctx)
        finishing = false

        // 1. Estado persistido ANTES de tocar politicas: si el proceso muere en
        //    el medio, el watchdog encuentra el flujo marcado y lo revierte.
        prefs.edit()
            .putString(KEY_PKG, packageName)
            .putBoolean(KEY_IN_PROGRESS, true)
            .putString(KEY_STAGE, STAGE_PREPARING)
            .remove(KEY_DETAIL)
            .putLong(KEY_STARTED_AT, System.currentTimeMillis())
            .putString(KEY_SOURCE, source)
            .putBoolean(KEY_CANCELABLE, cancelable)
            .putLong(KEY_BASE_VERSION, versionCodeOf(ctx, packageName))
            .apply()

        LockSuiteAccessibilityService.instance?.invalidateFlagsCache()

        // 2. Tapar la pantalla YA. Antes el overlay recien aparecia cuando
        //    llegaba el primer evento de accesibilidad DE Play Store, asi que
        //    habia una ventana de uno o dos segundos con la tienda a la vista.
        showOverlay(ctx, packageName, STAGE_PREPARING, null, cancelable)

        // 3. Destapar Play Store y levantar las restricciones de instalacion.
        try {
            val dpm = ctx.getSystemService(Context.DEVICE_POLICY_SERVICE) as DevicePolicyManager
            val admin = ComponentName(ctx, DeviceAdminReceiver::class.java)
            try {
                dpm.setApplicationHidden(admin, PKG_PLAY_STORE, false)
            } catch (e: Exception) {
                Log.w(TAG, "No se pudo des-ocultar Play Store: ${e.message}")
            }
            try {
                dpm.setPackagesSuspended(admin, arrayOf(PKG_PLAY_STORE), false)
            } catch (e: Exception) {
                Log.w(TAG, "No se pudo des-suspender Play Store: ${e.message}")
            }
            try {
                dpm.clearUserRestriction(admin, UserManager.DISALLOW_INSTALL_APPS)
                dpm.clearUserRestriction(admin, UserManager.DISALLOW_INSTALL_UNKNOWN_SOURCES)
            } catch (e: Exception) {
                Log.w(TAG, "No se pudieron remover restricciones de instalacion: ${e.message}")
            }
        } catch (e: Exception) {
            Log.w(TAG, "Error preparando politicas para la actualizacion: ${e.message}")
        }

        // 4. Alarma de seguridad ANTES de abrir nada que pueda fallar.
        armWatchdog(ctx)

        // 5. Pantalla encendida.
        wakeScreen(ctx)

        // 6. Abrir Play Store.
        setStage(ctx, STAGE_OPENING_STORE)
        val opened = openStore(ctx, packageName)
        if (!opened) {
            finish(ctx, RESULT_ERROR, "No se encontro Google Play ni un navegador para actualizar.")
            return "No se encontro Google Play ni un navegador disponible en este equipo."
        }

        setStage(ctx, STAGE_WAITING_STORE)
        syncToPanel(ctx, force = true)
        Log.i(TAG, "Flujo de actualizacion iniciado para $packageName (origen=$source)")
        return null
    }

    /**
     * Abre (o trae al frente) la ficha de la app en Play Store.
     *
     * Ojo con los flags: la version anterior usaba FLAG_ACTIVITY_CLEAR_TASK y se
     * llamaba desde onAccessibilityEvent en CADA evento (hasta diez por segundo).
     * Eso destruia y recreaba la tarea de Play Store una y otra vez, y la
     * descarga nunca llegaba a arrancar. Ahora solo NEW_TASK: si la tarea ya
     * existe, se trae al frente tal como estaba.
     */
    fun openStore(context: Context, packageName: String): Boolean {
        val ctx = context.applicationContext
        try {
            val intent = Intent(Intent.ACTION_VIEW, Uri.parse("market://details?id=$packageName")).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                setPackage(PKG_PLAY_STORE)
            }
            ctx.startActivity(intent)
            return true
        } catch (e: Exception) {
            Log.w(TAG, "market:// fallo (${e.message}); probando enlace web")
        }
        return try {
            val web = Intent(
                Intent.ACTION_VIEW,
                Uri.parse("https://play.google.com/store/apps/details?id=$packageName")
            ).apply { addFlags(Intent.FLAG_ACTIVITY_NEW_TASK) }
            ctx.startActivity(web)
            true
        } catch (e: Exception) {
            Log.e(TAG, "No se pudo abrir ni Play Store ni el navegador: ${e.message}")
            false
        }
    }

    // ──────────────────────────────────────────────
    // Progreso
    // ──────────────────────────────────────────────

    fun setStage(context: Context, stage: String, detail: String? = null) {
        val ctx = context.applicationContext
        if (!isRunning(ctx)) return
        val prefs = PrefsHelper.getMdmPrefs(ctx)
        val prevStage = prefs.getString(KEY_STAGE, STAGE_IDLE)
        val prevDetail = prefs.getString(KEY_DETAIL, null)
        if (prevStage == stage && prevDetail == detail) return

        val editor = prefs.edit().putString(KEY_STAGE, stage)
        if (detail == null) editor.remove(KEY_DETAIL) else editor.putString(KEY_DETAIL, detail)
        editor.apply()

        LockSuiteAccessibilityService.instance?.overlayManager
            ?.updateBlockingMessage(null, stageLabel(stage, detail))

        syncToPanel(ctx, force = false)
    }

    /** Muestra (o refresca) la pantalla negra de actualizacion. */
    fun showOverlay(
        context: Context,
        packageName: String,
        stage: String,
        detail: String?,
        cancelable: Boolean
    ) {
        val service = LockSuiteAccessibilityService.instance ?: return
        val ctx = context.applicationContext
        service.overlayManager.showBlockingMessageOverlay(
            title = "Actualizando ${appLabel(ctx, packageName)}",
            subtitle = stageLabel(stage, detail),
            cancelable = cancelable,
            onCancel = { requestCancel(ctx) }
        )
    }

    // ──────────────────────────────────────────────
    // Cancelacion y cierre
    // ──────────────────────────────────────────────

    fun requestCancel(context: Context) {
        val ctx = context.applicationContext
        Log.i(TAG, "Cancelacion pedida por el usuario/panel")
        finish(ctx, RESULT_CANCELLED, "Actualizacion cancelada.")
    }

    /**
     * Camino UNICO de salida. Idempotente: se puede llamar de mas sin romper
     * nada, y siempre saca el overlay aunque el flujo ya figurara terminado
     * (esa es justamente la red contra la pantalla negra trabada).
     */
    fun finish(context: Context, result: String, message: String? = null) {
        val ctx = context.applicationContext
        val prefs = PrefsHelper.getMdmPrefs(ctx)
        val pkg = prefs.getString(KEY_PKG, null)
        val wasRunning = isRunning(ctx)

        // Guarda de reentrada. Antes decia `finishing && wasRunning`, y como
        // finish() limpia las preferencias apenas empieza, la segunda llamada
        // siempre llegaba con wasRunning=false: la guarda no cortaba nunca y se
        // repetia todo el cierre (un segundo GLOBAL_ACTION_HOME que sacaba al
        // usuario de lo que estuviera usando, otro Toast, otra restauracion).
        // forceCleanup() sigue pudiendo forzar el paso porque pone finishing=false.
        if (finishing) {
            LockSuiteAccessibilityService.instance?.overlayManager?.hideBlockingMessageOverlay()
            return
        }
        finishing = true

        Log.i(TAG, "Cerrando flujo de actualizacion: result=$result pkg=$pkg running=$wasRunning")

        // 1. Estado PRIMERO. Con esto el servicio de accesibilidad deja de
        //    redirigir a Play Store en el mismo instante, antes del HOME.
        prefs.edit()
            .remove(KEY_PKG)
            .putBoolean(KEY_IN_PROGRESS, false)
            .putString(KEY_STAGE, STAGE_IDLE)
            .remove(KEY_DETAIL)
            .remove(KEY_BASE_VERSION)
            .putString(KEY_LAST_RESULT, result)
            .putString(KEY_LAST_RESULT_PKG, pkg ?: "")
            .putLong(KEY_LAST_RESULT_AT, System.currentTimeMillis())
            .apply()

        val service = LockSuiteAccessibilityService.instance
        service?.invalidateFlagsCache()
        service?.resetUpdateSession()

        // 2. Sacar la pantalla negra SIEMPRE, pase lo que pase despues.
        service?.overlayManager?.hideBlockingMessageOverlay()

        // 3. Cerrar Play Store volviendo al inicio.
        try {
            if (service != null) {
                service.performGlobalAction(android.accessibilityservice.AccessibilityService.GLOBAL_ACTION_HOME)
            } else {
                val home = Intent(Intent.ACTION_MAIN).apply {
                    addCategory(Intent.CATEGORY_HOME)
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
                ctx.startActivity(home)
            }
        } catch (e: Exception) {
            Log.w(TAG, "No se pudo volver al inicio: ${e.message}")
        }

        // 4. Re-imponer los bloqueos con una demora corta: si se suspende Play
        //    Store mientras todavia esta arriba, Android muestra el cartel
        //    "aplicacion en pausa" encima del launcher.
        //    La alarma watchdog NO se cancela hasta que esto termine: si el
        //    proceso muere en el medio, sigue siendo la unica garantia.
        mainHandler.postDelayed({
            try {
                PolicyManager(ctx).restoreInstallRestrictions()
            } catch (e: Exception) {
                Log.e(TAG, "Error restaurando restricciones: ${e.message}")
            }
            cancelWatchdog(ctx)
            releaseWake()
            finishing = false
            // Por las dudas: si algo volvio a dibujar el overlay entre medio.
            LockSuiteAccessibilityService.instance?.overlayManager?.hideBlockingMessageOverlay()
            syncToPanel(ctx, force = true)
        }, RESTORE_DELAY_MS)

        if (!message.isNullOrBlank() && result != RESULT_UPDATED) {
            try {
                mainHandler.post {
                    android.widget.Toast.makeText(ctx, message, android.widget.Toast.LENGTH_LONG).show()
                }
            } catch (e: Exception) {
                // Toast desde un contexto sin UI: no es critico.
            }
        }
    }

    /**
     * Llamado por el watchdog de 10 minutos y por cualquiera que sospeche que
     * quedo estado colgado. Restaura aunque las preferencias ya digan que no hay
     * nada en curso, porque el sintoma clasico era justamente ese: preferencias
     * limpias y overlay todavia en pantalla.
     */
    fun forceCleanup(context: Context, result: String) {
        val ctx = context.applicationContext
        finishing = false
        finish(ctx, result, null)
    }

    // ──────────────────────────────────────────────
    // Watchdog / pantalla / sincronizacion
    // ──────────────────────────────────────────────

    private fun watchdogPendingIntent(context: Context): PendingIntent {
        val intent = Intent(context, PackageReceiver::class.java).apply { action = ACTION_TIMEOUT }
        return PendingIntent.getBroadcast(
            context,
            WATCHDOG_REQUEST_CODE,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
    }

    private fun armWatchdog(context: Context) {
        try {
            val am = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
            val at = SystemClock.elapsedRealtime() + TIMEOUT_MS
            // Exacta y a prueba de Doze: es la unica garantia de que el equipo
            // vuelva a bloquearse y de que la pantalla negra se saque si todo lo
            // demas falla. Con am.set() normal el sistema puede correrla mucho.
            am.setExactAndAllowWhileIdle(AlarmManager.ELAPSED_REALTIME_WAKEUP, at, watchdogPendingIntent(context))
        } catch (e: Exception) {
            Log.w(TAG, "No se pudo programar el watchdog: ${e.message}")
        }
    }

    fun cancelWatchdog(context: Context) {
        try {
            val am = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager
            am.cancel(watchdogPendingIntent(context))
        } catch (e: Exception) {
            Log.w(TAG, "No se pudo cancelar el watchdog: ${e.message}")
        }
    }

    private var wakeLock: PowerManager.WakeLock? = null

    private fun wakeScreen(context: Context) {
        try {
            val pm = context.getSystemService(Context.POWER_SERVICE) as PowerManager
            if (pm.isInteractive) return
            val existing = wakeLock
            if (existing != null && existing.isHeld) return
            @Suppress("DEPRECATION")
            val wl = pm.newWakeLock(
                PowerManager.SCREEN_BRIGHT_WAKE_LOCK or
                    PowerManager.ACQUIRE_CAUSES_WAKEUP or
                    PowerManager.ON_AFTER_RELEASE,
                "LockSuite:UpdateFlowWake"
            )
            wl.acquire(30_000L)
            wakeLock = wl
        } catch (e: Exception) {
            Log.w(TAG, "No se pudo encender la pantalla: ${e.message}")
        }
    }

    private fun releaseWake() {
        try {
            wakeLock?.let { if (it.isHeld) it.release() }
        } catch (e: Exception) {
            // el wake lock puede haber vencido solo
        }
        wakeLock = null
    }

    /** Publica el estado del flujo en Firebase para que el panel lo vea en vivo. */
    fun syncToPanel(context: Context, force: Boolean) {
        val now = SystemClock.elapsedRealtime()
        if (!force && now - lastSyncAt < 1500L) return
        lastSyncAt = now
        try {
            FirebaseDeviceSync.syncUpdateFlow(context.applicationContext)
        } catch (e: Exception) {
            // Sin red no pasa nada: el overlay local sigue mostrando el estado.
        }
    }
}
