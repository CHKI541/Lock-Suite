package com.ejemplo.locksuite.util

import android.app.AlarmManager
import android.app.PendingIntent
import android.app.admin.DevicePolicyManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.os.StatFs
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
    const val KEY_DEBUG_LABELS = "update_flow_debug_labels"

    /**
     * Motivo legible del ultimo cierre. Es lo que hace la diferencia entre "no se
     * pudo actualizar" (que no le sirve a nadie) y "faltan 180 MB de espacio libre"
     * (que el dueno puede resolver sin pedir un ADB). Se publica en el panel.
     */
    const val KEY_LAST_RESULT_REASON = "update_flow_last_result_reason"
    /** Espacio libre medido en la ultima verificacion previa, en MB. */
    const val KEY_LAST_FREE_MB = "update_flow_last_free_mb"

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

    // Resultados nuevos (3/9/2026). Antes TODOS estos casos terminaban como
    // RESULT_UP_TO_DATE ("la app ya esta actualizada") o como un RESULT_ERROR
    // generico. Los dos son mentira y los dos hacen que el dueno busque el
    // problema en el lugar equivocado: el sintoma "actualizo y no pasa nada" del
    // 3/9 en el CAT S22 Flip era, medido en el equipo, falta de espacio.
    /** No hay espacio suficiente en el almacenamiento del equipo. */
    const val RESULT_NO_SPACE = "NO_SPACE"
    /** Play Store pide iniciar sesion: el equipo no tiene cuenta de Google. */
    const val RESULT_NEEDS_ACCOUNT = "NEEDS_ACCOUNT"
    /** La ficha no existe, o Play Store dice que la app no es compatible. */
    const val RESULT_NOT_AVAILABLE = "NOT_AVAILABLE"
    /** Error de red o del servidor de Play Store. */
    const val RESULT_STORE_ERROR = "STORE_ERROR"

    // ──────────────────────────────────────────────
    // Verificacion de espacio libre
    //
    // Nuevo el 3/9/2026. Es la comprobacion mas barata y la que mas sirve: se hace
    // ANTES de tapar la pantalla y antes de levantar ninguna restriccion, asi que
    // cuando falta espacio el equipo ni siquiera entra al flujo — el panel recibe
    // el motivo exacto y el usuario no ve nunca una pantalla negra que no puede
    // sacar. Play Store SI muestra su propio cartel de "liberar espacio", pero el
    // overlay opaco del flujo lo tapaba al 100 %: el usuario no podia ni leerlo.
    // ──────────────────────────────────────────────

    /**
     * Margen de trabajo del sistema, en MB. Play Store no baja el APK y lo instala
     * y listo: descarga, verifica, y en Android 7-11 ademas compila el codigo con
     * dex2oat, que necesita su propio espacio temporal. La regla practica es tener
     * libre varias veces el tamano del paquete.
     */
    private const val INSTALL_HEADROOM_MB = 250L
    /** Multiplicador sobre el tamano del APK ya instalado. */
    private const val INSTALL_SIZE_FACTOR = 3L
    /** Piso absoluto: por debajo de esto Play Store no actualiza nada, ni lo chico. */
    private const val MIN_FREE_MB = 400L

    class SpaceCheck(val freeMb: Long, val neededMb: Long) {
        val ok: Boolean get() = freeMb >= neededMb
        val missingMb: Long get() = if (ok) 0L else neededMb - freeMb
    }

    /** MB libres en la particion de datos, o -1 si no se pudo medir. */
    fun freeSpaceMb(context: Context): Long = try {
        val stat = StatFs(context.filesDir.absolutePath)
        (stat.availableBlocksLong * stat.blockSizeLong) / (1024L * 1024L)
    } catch (e: Exception) {
        -1L
    }

    /** Tamano en MB del APK ya instalado del paquete, o -1 si no se pudo medir. */
    private fun installedApkSizeMb(context: Context, packageName: String): Long = try {
        val dir = context.packageManager.getApplicationInfo(packageName, 0).sourceDir
        val len = java.io.File(dir).length()
        if (len > 0L) len / (1024L * 1024L) else -1L
    } catch (e: Exception) {
        -1L
    }

    /**
     * Cuanto espacio hace falta y cuanto hay. Se estima a partir del APK ya
     * instalado porque el tamano de la version nueva no se puede saber sin abrir
     * Play Store — y abrirla es justo lo que se quiere evitar cuando no va a poder.
     * La estimacion es deliberadamente conservadora: es preferible pedirle al dueno
     * que libere 300 MB de mas a dejarlo diez minutos mirando una pantalla negra.
     */
    fun checkSpace(context: Context, packageName: String): SpaceCheck {
        val free = freeSpaceMb(context)
        val apk = installedApkSizeMb(context, packageName)
        val needed = if (apk > 0L) {
            maxOf(MIN_FREE_MB, apk * INSTALL_SIZE_FACTOR + INSTALL_HEADROOM_MB)
        } else {
            MIN_FREE_MB
        }
        // free = -1 significa "no se pudo medir": no se bloquea el flujo por eso.
        return SpaceCheck(if (free < 0L) Long.MAX_VALUE else free, needed)
    }

    // ── Origenes ──
    const val SOURCE_PANEL = "panel"
    const val SOURCE_LOCAL = "local"

    const val ACTION_TIMEOUT = "UPDATE_TIMEOUT"
    const val WATCHDOG_REQUEST_CODE = 9911

    /** Tiempo maximo que puede durar el flujo antes de que el watchdog lo cancele. */
    const val WATCHDOG_TIMEOUT_MS = 10 * 60 * 1000L

    /** Demora antes de re-suspender Play Store, para que no salte el cartel de pausa. */
    const val RESTORE_DELAY_MS = 600L

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

    fun currentDetail(context: Context): String? =
        PrefsHelper.getMdmPrefs(context).getString(KEY_DETAIL, null)

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
            STAGE_PREPARING -> "Preparando la actualización..."
            STAGE_OPENING_STORE -> "Abriendo Google Play..."
            STAGE_WAITING_STORE -> "Esperando a Google Play..."
            STAGE_LOOKING_BUTTON -> "Buscando actualización..."
            STAGE_CONFIRMING -> "Confirmando descarga..."
            STAGE_DOWNLOADING -> "Descargando actualización..."
            STAGE_INSTALLING -> "Instalando..."
            STAGE_FINISHING -> "Finalizando y asegurando dispositivo..."
            else -> "Por favor, no toque la pantalla..."
        }
    }

    /**
     * Guarda las etiquetas de los botones que el escaneo vio en la ficha. Es lo que
     * permite diagnosticar un equipo en un idioma no previsto sin pedirle un ADB al
     * dueño: quedan visibles en el panel.
     */
    fun reportDebugLabels(context: Context, labels: List<String>) {
        if (labels.isEmpty()) return
        val joined = labels.joinToString(" · ").take(300)
        val prefs = PrefsHelper.getMdmPrefs(context)
        if (prefs.getString(KEY_DEBUG_LABELS, null) == joined) return
        prefs.edit().putString(KEY_DEBUG_LABELS, joined).apply()
        syncToPanel(context, force = false)
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

        if (PrefsHelper.getMdmPrefs(ctx).getBoolean(KEY_IN_PROGRESS, false)) {
            return "Hay otra instalacion en curso en este equipo. Espera a que termine."
        }

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

        // ── Verificaciones previas (3/9/2026) ──
        //
        // Van ANTES de tapar la pantalla y antes de levantar una sola restriccion,
        // a proposito: si el flujo no va a poder terminar, lo mejor que puede hacer
        // es no empezar. Asi el panel recibe el motivo exacto, el equipo nunca queda
        // con Play Store destapada y el usuario nunca ve una pantalla negra encima
        // de un cartel de Play Store que no puede leer ni responder.

        // 1. Play Store instalada y habilitada. Si el administrador la deshabilito
        //    (no suspendida: DESHABILITADA), setPackagesSuspended(false) no la
        //    revive y el flujo abriria la nada.
        val storeState = try {
            ctx.packageManager.getApplicationEnabledSetting(PKG_PLAY_STORE)
        } catch (e: Exception) {
            return "Google Play no esta instalada en este equipo, asi que no se puede actualizar por la tienda."
        }
        if (storeState == PackageManager.COMPONENT_ENABLED_STATE_DISABLED ||
            storeState == PackageManager.COMPONENT_ENABLED_STATE_DISABLED_USER
        ) {
            return "Google Play esta deshabilitada en este equipo. Habilitala antes de actualizar."
        }

        // 2. Espacio libre. Esta es la que aparecio en el CAT S22 Flip del 3/9: el
        //    equipo estaba sin lugar, Play Store creaba la sesion de instalacion y
        //    la descartaba, y el flujo se quedaba diez minutos en "Descargando..."
        //    sin decir nunca por que.
        val space = checkSpace(ctx, packageName)
        if (space.freeMb != Long.MAX_VALUE) {
            PrefsHelper.getMdmPrefs(ctx).edit()
                .putLong(KEY_LAST_FREE_MB, space.freeMb).apply()
        }
        if (!space.ok) {
            val reason = "No hay espacio suficiente: quedan ${space.freeMb} MB libres y hacen " +
                "falta al menos ${space.neededMb} MB. Liberá ${space.missingMb} MB y volvé a intentar."
            recordResult(ctx, RESULT_NO_SPACE, packageName, reason)
            Log.w(TAG, "Actualizacion de $packageName rechazada por espacio: $reason")
            return reason
        }

        val prefs = PrefsHelper.getMdmPrefs(ctx)
        finishing = false

        prefs.edit()
            .putString(KEY_PKG, packageName)
            .putBoolean(KEY_IN_PROGRESS, true)
            .putString(KEY_STAGE, STAGE_PREPARING)
            .remove(KEY_DETAIL)
            .remove(KEY_DEBUG_LABELS)
            .putLong(KEY_STARTED_AT, System.currentTimeMillis())
            .putString(KEY_SOURCE, source)
            .putBoolean(KEY_CANCELABLE, cancelable)
            .putLong(KEY_BASE_VERSION, versionCodeOf(ctx, packageName))
            .apply()

        LockSuiteAccessibilityService.instance?.invalidateFlagsCache()

        showOverlay(ctx, packageName, STAGE_PREPARING, null, cancelable)

        try {
            val dpm = ctx.getSystemService(Context.DEVICE_POLICY_SERVICE) as DevicePolicyManager
            val admin = ComponentName(ctx, DeviceAdminReceiver::class.java)

            val wasUninstallBlocked = try { dpm.isUninstallBlocked(admin, packageName) } catch (e: Exception) { false }
            prefs.edit().putBoolean("update_flow_prev_uninstall_blocked", wasUninstallBlocked).apply()
            try { dpm.setUninstallBlocked(admin, packageName, true) } catch (e: Exception) { }

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
            } catch (e: Exception) {
                Log.w(TAG, "No se pudieron remover restricciones de instalacion: ${e.message}")
            }
        } catch (e: Exception) {
            Log.w(TAG, "Error preparando politicas para la actualizacion: ${e.message}")
        }

        armWatchdog(ctx)
        wakeScreen(ctx)

        setStage(ctx, STAGE_OPENING_STORE)
        val opened = openStore(ctx, packageName)
        if (!opened) {
            finish(ctx, RESULT_STORE_ERROR, "No se pudo abrir Google Play en este equipo.",
                "startActivity(market://details) fallo para $packageName")
            return "No se pudo abrir Google Play en este equipo."
        }

        setStage(ctx, STAGE_WAITING_STORE)
        syncToPanel(ctx, force = true)
        Log.i(TAG, "Flujo de actualizacion iniciado para $packageName (origen=$source)")

        PlayUpdateSessionWatcher.start(
            ctx, packageName,
            onProgress = { pct -> setStage(ctx, STAGE_DOWNLOADING, "Descargando... $pct%") },
            onFinished = { ok ->
                if (ok) {
                    finish(ctx, RESULT_UPDATED, null)
                } else {
                    // success=false NO es concluyente: Play Store parte descargas
                    // grandes en varias sesiones y descarta sesiones intermedias.
                    // Por eso acá NO se cierra el flujo. Lo unico que se hace es
                    // dejar la marca de tiempo (la escribe el watcher) para que el
                    // ciclo de scanAndAct pueda distinguir "sigue bajando" de "el
                    // sistema abortó la instalacion", que es lo que pasa cuando no
                    // hay espacio. Antes esta rama estaba vacia y el flujo se
                    // quedaba en "Descargando..." hasta el watchdog de 10 minutos.
                    Log.w(TAG, "Sesion de instalacion abortada por el sistema para $packageName")
                }
            }
        )
        LockSuiteAccessibilityService.instance?.startUpdateTicker()

        return null
    }

    /**
     * Deja Play Store en condiciones de abrirse: des-oculta y des-suspende.
     *
     * Se llama tambien en cada relanzamiento (3/9/2026), no solo al arrancar. Es
     * barato —dos llamadas al DPM que son no-op si ya estaba libre— y cierra una
     * carrera real: `setPackagesSuspended(false)` no es instantaneo, y el arranque
     * hacia `startActivity` unos milisegundos despues. En un equipo lento
     * (Snapdragon 215, 2 GB) eso alcanzaba para que el sistema mostrara el cartel
     * de "app en pausa" en vez de la ficha, y el flujo se quedaba esperando una
     * ventana de Play Store que nunca iba a llegar. Ademas el WatchdogWorker de 15
     * minutos puede volver a suspenderla en medio de un flujo largo.
     */
    private fun unlockPlayStore(context: Context) {
        try {
            val dpm = context.getSystemService(Context.DEVICE_POLICY_SERVICE) as DevicePolicyManager
            val admin = ComponentName(context, DeviceAdminReceiver::class.java)
            try { dpm.setApplicationHidden(admin, PKG_PLAY_STORE, false) } catch (e: Exception) { }
            try { dpm.setPackagesSuspended(admin, arrayOf(PKG_PLAY_STORE), false) } catch (e: Exception) { }
        } catch (e: Exception) {
            Log.w(TAG, "No se pudo destrabar Play Store: ${e.message}")
        }
    }

    fun openStore(context: Context, packageName: String): Boolean {
        val ctx = context.applicationContext
        unlockPlayStore(ctx)
        try {
            val intent = Intent(Intent.ACTION_VIEW, Uri.parse("market://details?id=$packageName")).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                setPackage(PKG_PLAY_STORE)
            }
            ctx.startActivity(intent)
            return true
        } catch (e: Exception) {
            Log.w(TAG, "market:// con paquete fijo fallo (${e.message}); probando sin fijar paquete")
        }
        // Segundo intento: el mismo esquema market:// pero dejando que lo resuelva
        // el sistema. Cubre equipos donde Play Store vive con otro nombre de
        // paquete o donde el filtro de visibilidad de Android 11 se pone raro.
        //
        // ⚠️ 3/9/2026: acá había un tercer intento que abría
        // https://play.google.com/... en un NAVEGADOR. Se quitó a proposito y no
        // hay que volver a ponerlo, por dos razones: (a) desde la web de Play
        // Store no se puede instalar nada, o sea que no arreglaba nada; (b) este
        // es un equipo kosher con los navegadores suspendidos, y el flujo levanta
        // las restricciones de instalacion por hasta 10 minutos — abrir un
        // navegador ahi es exactamente el agujero que todo el resto del proyecto
        // se ocupa de cerrar. Si Play Store no abre, el camino correcto es fallar
        // con un motivo claro, que es lo que hace start().
        return try {
            val intent = Intent(Intent.ACTION_VIEW, Uri.parse("market://details?id=$packageName"))
                .apply { addFlags(Intent.FLAG_ACTIVITY_NEW_TASK) }
            ctx.startActivity(intent)
            true
        } catch (e: Exception) {
            Log.e(TAG, "No se pudo abrir Play Store: ${e.message}")
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
        val effectiveDetail = detail ?: if (stage == prevStage) prevDetail else null

        if (prevStage == stage && prevDetail == effectiveDetail) return

        val editor = prefs.edit().putString(KEY_STAGE, stage)
        if (effectiveDetail == null) editor.remove(KEY_DETAIL) else editor.putString(KEY_DETAIL, effectiveDetail)
        editor.apply()

        LockSuiteAccessibilityService.instance?.overlayManager
            ?.updateBlockingMessage(null, stageLabel(stage, effectiveDetail))

        syncToPanel(ctx, force = false)
    }

    fun showOverlay(
        context: Context,
        packageName: String,
        stage: String,
        detail: String? = null,
        cancelable: Boolean = isCancelable(context)
    ) {
        val service = LockSuiteAccessibilityService.instance ?: return
        val ctx = context.applicationContext
        val effectiveDetail = detail ?: currentDetail(ctx)
        service.overlayManager.showBlockingMessageOverlay(
            title = "Actualizando ${appLabel(ctx, packageName)}",
            subtitle = stageLabel(stage, effectiveDetail),
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
        finish(ctx, RESULT_CANCELLED, "Actualización cancelada.")
    }

    /**
     * @param message lo que ve el usuario en la pantalla negra antes de que se
     *   saque; puede ser null para el texto por omision del resultado.
     * @param reason motivo tecnico para el panel. Se guarda aunque el usuario ya
     *   no este mirando: es lo unico que queda despues, y es lo que evita la
     *   siguiente sesion de diagnostico a ciegas.
     */
    fun finish(context: Context, result: String, message: String? = null, reason: String? = null) {
        val ctx = context.applicationContext
        val prefs = PrefsHelper.getMdmPrefs(ctx)
        val pkg = prefs.getString(KEY_PKG, null)
        val wasRunning = isRunning(ctx)

        if (finishing) {
            LockSuiteAccessibilityService.instance?.overlayManager?.hideBlockingMessageOverlay()
            return
        }
        finishing = true

        Log.i(TAG, "Cerrando flujo de actualizacion: result=$result pkg=$pkg running=$wasRunning")

        PlayUpdateSessionWatcher.stop(ctx)
        val service = LockSuiteAccessibilityService.instance
        service?.stopUpdateTicker()

        prefs.edit()
            .remove(KEY_PKG)
            .putBoolean(KEY_IN_PROGRESS, false)
            .putString(KEY_STAGE, STAGE_IDLE)
            .remove(KEY_DETAIL)
            .remove(KEY_BASE_VERSION)
            .putString(KEY_LAST_RESULT, result)
            .putString(KEY_LAST_RESULT_PKG, pkg ?: "")
            .putString(KEY_LAST_RESULT_REASON, reason ?: message ?: "")
            .putLong(KEY_LAST_RESULT_AT, System.currentTimeMillis())
            .apply()

        service?.invalidateFlagsCache()
        service?.resetUpdateSession()

        val label = if (!pkg.isNullOrBlank()) appLabel(ctx, pkg) else "App"
        val finalNotice = when (result) {
            RESULT_UP_TO_DATE -> message ?: "$label ya está actualizada."
            RESULT_UPDATED -> message ?: "✓ Actualización completada con éxito."
            RESULT_CANCELLED -> message ?: "Actualización cancelada."
            RESULT_ERROR -> message ?: "No se pudo completar la actualización."
            RESULT_TIMEOUT -> message ?: "La actualización tardó demasiado y se canceló."
            RESULT_NO_SPACE -> message ?: "No hay espacio suficiente para actualizar $label."
            RESULT_NEEDS_ACCOUNT -> message
                ?: "Google Play pide iniciar sesión: este equipo no tiene una cuenta de Google configurada."
            RESULT_NOT_AVAILABLE -> message
                ?: "Google Play no ofrece una actualización de $label para este equipo."
            RESULT_STORE_ERROR -> message ?: "Google Play devolvió un error. Probá de nuevo más tarde."
            else -> message
        }

        val showNoticeOnOverlay = !finalNotice.isNullOrBlank() &&
            service?.overlayManager?.isBlockingMessageVisible() == true

        if (showNoticeOnOverlay) {
            service?.overlayManager?.updateBlockingMessage(
                title = label,
                subtitle = finalNotice
            )
        }

        // Un aviso que explica QUE hacer (liberar espacio, configurar la cuenta)
        // tiene que quedar en pantalla el tiempo suficiente para leerlo. 1,8 s
        // alcanzan para "✓ listo" y no alcanzan para una frase de dos renglones.
        val needsReading = result == RESULT_NO_SPACE || result == RESULT_NEEDS_ACCOUNT ||
            result == RESULT_NOT_AVAILABLE || result == RESULT_STORE_ERROR ||
            result == RESULT_ERROR
        val delayMs = if (!showNoticeOnOverlay) 0L else if (needsReading) 5000L else 1800L

        mainHandler.postDelayed({
            service?.overlayManager?.hideBlockingMessageOverlay()

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

            mainHandler.postDelayed({
                try {
                    PolicyManager(ctx).restoreInstallRestrictions()
                } catch (e: Exception) {
                    Log.e(TAG, "Error restaurando restricciones: ${e.message}")
                }
                if (!pkg.isNullOrBlank()) {
                    try {
                        val dpm = ctx.getSystemService(Context.DEVICE_POLICY_SERVICE) as DevicePolicyManager
                        val admin = ComponentName(ctx, DeviceAdminReceiver::class.java)
                        dpm.setUninstallBlocked(admin, pkg,
                            prefs.getBoolean("update_flow_prev_uninstall_blocked", false))
                    } catch (e: Exception) { e.printStackTrace() }
                }
                prefs.edit().remove("update_flow_prev_uninstall_blocked").apply()

                cancelWatchdog(ctx)
                releaseWake()
                finishing = false
                LockSuiteAccessibilityService.instance?.overlayManager?.hideBlockingMessageOverlay()
                syncToPanel(ctx, force = true)
            }, RESTORE_DELAY_MS)
        }, delayMs)

        if (!finalNotice.isNullOrBlank() && result != RESULT_UPDATED) {
            try {
                mainHandler.post {
                    android.widget.Toast.makeText(ctx, finalNotice, android.widget.Toast.LENGTH_LONG).show()
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
            val at = SystemClock.elapsedRealtime() + WATCHDOG_TIMEOUT_MS
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

    /**
     * Anota el resultado de un intento y lo publica. Se usa tambien para los
     * rechazos previos, donde el flujo nunca llego a arrancar: sin esto, un
     * "no hay espacio" desde el panel se veia solo como un comando fallido, sin
     * que quedara registro de por que.
     */
    fun recordResult(context: Context, result: String, packageName: String?, reason: String?) {
        val ctx = context.applicationContext
        PrefsHelper.getMdmPrefs(ctx).edit()
            .putString(KEY_LAST_RESULT, result)
            .putString(KEY_LAST_RESULT_PKG, packageName ?: "")
            .putString(KEY_LAST_RESULT_REASON, reason ?: "")
            .putLong(KEY_LAST_RESULT_AT, System.currentTimeMillis())
            .apply()
        syncToPanel(ctx, force = true)
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
