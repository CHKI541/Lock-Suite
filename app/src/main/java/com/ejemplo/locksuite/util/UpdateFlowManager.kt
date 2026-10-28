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
import com.ejemplo.locksuite.mdm.UpdateRoutePlanner
import com.ejemplo.locksuite.mdm.UpdateStepLog
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
 *
 * ── 26/10/2026 (B.97): DOS CAMINOS, Y TODO EL PROCESO A LA VISTA ──
 *
 *  5. Hay dos caminos y los elige `UpdateRoutePlanner` (puro, con pruebas): Google
 *     Play manejada por la Accesibilidad (lo de siempre) o la copia de la Tienda de
 *     LockSuite instalada EN SILENCIO como Device Owner (`HostedAppUpdater`), que no
 *     depende de idioma, versión de Play Store, velocidad del equipo ni pantalla. En
 *     "automático" se usa la copia de la Tienda solo si existe, tiene huella y resulta
 *     MÁS NUEVA, de la misma firma y de una arquitectura que corra en el equipo; si no,
 *     Google Play. El porqué completo está en la cabecera de UpdateRoutePlanner.
 *  6. La ruta efectiva vive en KEY_ROUTE y manda sobre TODO lo que toca la pantalla:
 *     el overlay negro, el ticker de la Accesibilidad, el relanzamiento de Play Store y
 *     el ir a INICIO al terminar solo existen en la ruta de Google Play. En la ruta de
 *     la Tienda el usuario sigue usando el teléfono (con todos los filtros andando) y
 *     ve una notificación con el progreso.
 *  7. Cada decisión deja un PASO con su hora en KEY_STEPS (`UpdateStepLog`): se ve
 *     en vivo en la pantalla negra y entero en la ficha del panel, y queda guardado
 *     hasta la próxima actualización. Era el pedido textual del dueño: "que muestre
 *     exactamente el proceso y qué está pasando".
 *  8. Con el celular BLOQUEADO no se tapa la pantalla: el overlay es una ventana de
 *     accesibilidad y queda POR ENCIMA del keyguard y de la barra de navegación (capa
 *     31 contra 17 de la pantalla de bloqueo, medido en WindowManagerPolicy de AOSP),
 *     así que antes el usuario no podía desbloquear y Google Play —abierta detrás del
 *     keyguard— nunca se veía. Ahora se espera a que lo desbloqueen (ver
 *     LockSuiteAccessibilityService.scanAndAct) con un aviso en la pantalla de bloqueo.
 *  9. finish() corre SIEMPRE en el hilo principal. Ahora hay cierres que llegan desde
 *     hilos de fondo (la ruta de la Tienda, el WatchdogWorker): serializarlos en un solo
 *     hilo es lo que hace seguro el `finishing` de abajo.
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

    // ── 26/10/2026 (B.97) ──
    /** Ruta efectiva: ROUTE_PLAY_STORE, ROUTE_HOSTED o "" (todavía eligiendo). */
    const val KEY_ROUTE = "update_flow_route"
    /** Lo que pidió el panel: auto / play / hosted. */
    const val KEY_ROUTE_PREF = "update_flow_route_pref"
    /** Por qué se eligió esa ruta, en castellano. */
    const val KEY_ROUTE_WHY = "update_flow_route_why"
    /** Registro paso a paso (formato de UpdateStepLog). Sobrevive al cierre. */
    const val KEY_STEPS = "update_flow_steps"
    /** Porcentaje de la descarga en curso, o -1. */
    const val KEY_PROGRESS = "update_flow_progress"
    /** Quién instaló la app que se está actualizando (Play Store, la Tienda, ADB…). */
    const val KEY_INSTALLER = "update_flow_installer"
    const val KEY_BASE_VERSION_NAME = "update_flow_base_version_name"
    /** Versión con la que quedó la app al cerrar el último flujo. */
    const val KEY_VERSION_AFTER = "update_flow_version_after"

    // ── Rutas ──
    const val ROUTE_PLAY_STORE = UpdateRoutePlanner.ROUTE_PLAY_STORE
    const val ROUTE_HOSTED = UpdateRoutePlanner.ROUTE_HOSTED

    // ── Etapas ── (los textos viven en UpdateRoutePlanner, que es puro y tiene pruebas)
    const val STAGE_IDLE = UpdateRoutePlanner.STAGE_IDLE
    const val STAGE_PREPARING = UpdateRoutePlanner.STAGE_PREPARING
    const val STAGE_CHOOSING_ROUTE = UpdateRoutePlanner.STAGE_CHOOSING_ROUTE
    const val STAGE_OPENING_STORE = UpdateRoutePlanner.STAGE_OPENING_STORE
    const val STAGE_WAITING_STORE = UpdateRoutePlanner.STAGE_WAITING_STORE
    const val STAGE_WAITING_UNLOCK = UpdateRoutePlanner.STAGE_WAITING_UNLOCK
    const val STAGE_LOOKING_BUTTON = UpdateRoutePlanner.STAGE_LOOKING_BUTTON
    const val STAGE_CONFIRMING = UpdateRoutePlanner.STAGE_CONFIRMING
    const val STAGE_QUEUED = UpdateRoutePlanner.STAGE_QUEUED
    const val STAGE_DOWNLOADING = UpdateRoutePlanner.STAGE_DOWNLOADING
    const val STAGE_INSTALLING = UpdateRoutePlanner.STAGE_INSTALLING
    const val STAGE_HOSTED_DOWNLOADING = UpdateRoutePlanner.STAGE_HOSTED_DOWNLOADING
    const val STAGE_HOSTED_VERIFYING = UpdateRoutePlanner.STAGE_HOSTED_VERIFYING
    const val STAGE_HOSTED_INSTALLING = UpdateRoutePlanner.STAGE_HOSTED_INSTALLING
    const val STAGE_FINISHING = UpdateRoutePlanner.STAGE_FINISHING

    // ── Resultados ──
    const val RESULT_UPDATED = UpdateRoutePlanner.RESULT_UPDATED
    const val RESULT_UP_TO_DATE = UpdateRoutePlanner.RESULT_UP_TO_DATE
    const val RESULT_CANCELLED = UpdateRoutePlanner.RESULT_CANCELLED
    const val RESULT_TIMEOUT = UpdateRoutePlanner.RESULT_TIMEOUT
    const val RESULT_ERROR = UpdateRoutePlanner.RESULT_ERROR

    // Resultados nuevos (3/9/2026). Antes TODOS estos casos terminaban como
    // RESULT_UP_TO_DATE ("la app ya esta actualizada") o como un RESULT_ERROR
    // generico. Los dos son mentira y los dos hacen que el dueno busque el
    // problema en el lugar equivocado: el sintoma "actualizo y no pasa nada" del
    // 3/9 en el CAT S22 Flip era, medido en el equipo, falta de espacio.
    /** No hay espacio suficiente en el almacenamiento del equipo. */
    const val RESULT_NO_SPACE = UpdateRoutePlanner.RESULT_NO_SPACE
    /** Play Store pide iniciar sesion: el equipo no tiene cuenta de Google. */
    const val RESULT_NEEDS_ACCOUNT = UpdateRoutePlanner.RESULT_NEEDS_ACCOUNT
    /** La ficha no existe, o Play Store dice que la app no es compatible. */
    const val RESULT_NOT_AVAILABLE = UpdateRoutePlanner.RESULT_NOT_AVAILABLE
    /** Error de red o del servidor de Play Store. */
    const val RESULT_STORE_ERROR = UpdateRoutePlanner.RESULT_STORE_ERROR

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

    /** Corre en el hilo principal (ya, si se está en él). Lo usa la ruta de la Tienda. */
    fun runOnMain(block: () -> Unit) {
        if (Looper.myLooper() == Looper.getMainLooper()) block() else mainHandler.post(block)
    }

    @Volatile private var lastSyncAt = 0L
    @Volatile private var finishing = false
    private var finishRunnable: Runnable? = null
    private var restoreRunnable: Runnable? = null
    private const val KEY_PREV_UNINSTALL_BLOCKED = "update_flow_prev_uninstall_blocked"
    private const val KEY_PREV_UNINSTALL_PKG = "update_flow_prev_uninstall_pkg"

    private fun restorePreviousUninstallBlock(context: Context) {
        val prefs = PrefsHelper.getMdmPrefs(context)
        val prevPkg = prefs.getString(KEY_PREV_UNINSTALL_PKG, null)
        if (!prevPkg.isNullOrBlank()) {
            try {
                val dpm = context.getSystemService(Context.DEVICE_POLICY_SERVICE) as DevicePolicyManager
                val admin = ComponentName(context, DeviceAdminReceiver::class.java)
                dpm.setUninstallBlocked(
                    admin,
                    prevPkg,
                    prefs.getBoolean(KEY_PREV_UNINSTALL_BLOCKED, false)
                )
            } catch (e: Exception) {
                Log.w(TAG, "No se pudo restaurar setUninstallBlocked para $prevPkg: ${e.message}")
            }
        }
        if (prefs.contains(KEY_PREV_UNINSTALL_BLOCKED) || prefs.contains(KEY_PREV_UNINSTALL_PKG)) {
            prefs.edit()
                .remove(KEY_PREV_UNINSTALL_BLOCKED)
                .remove(KEY_PREV_UNINSTALL_PKG)
                .apply()
        }
    }

    // ──────────────────────────────────────────────
    // Consultas
    // ──────────────────────────────────────────────

    /**
     * ¿Hay un flujo con las restricciones de instalación levantadas? Es la definición de
     * siempre: `mdm_install_in_progress` + paquete. Desde B.97 la marca solo se prende en
     * la ruta de Google Play (y, por segundos, durante la instalación silenciosa de la
     * Tienda, la prende SelfUpdater): mientras se elige el camino o se baja la copia de la
     * Tienda, el equipo sigue con todas sus restricciones — ningún "exento porque hay una
     * instalación en curso" de PolicyManager, AppController o la Accesibilidad aplica.
     * Para "¿hay una actualización abierta, por el camino que sea?" está [hasOpenFlow].
     */
    fun isRunning(context: Context): Boolean {
        val p = PrefsHelper.getMdmPrefs(context)
        return p.getBoolean(KEY_IN_PROGRESS, false) && !p.getString(KEY_PKG, null).isNullOrBlank()
    }

    /** ¿Hay una actualización abierta, por cualquier camino (B.97)? Solo mira el paquete. */
    fun hasOpenFlow(context: Context): Boolean =
        !PrefsHelper.getMdmPrefs(context).getString(KEY_PKG, null).isNullOrBlank()

    /**
     * ¿El flujo abierto sigue VIVO? Lo usa [start] para no rechazar un pedido nuevo por
     * culpa de uno muerto. Muerto es: un flujo de Google Play al que alguien le bajó la
     * marca de instalación (restoreInstallRestrictions() desde otro lado: sin la marca, el
     * ciclo de la Accesibilidad ya no lo atiende), o uno que lleva más que cualquier flujo
     * legítimo (STALE_AFTER_MS). Antes de B.97 [start] miraba [isRunning], que daba lo
     * mismo para el primer caso; el segundo es nuevo porque la ruta de la Tienda no tiene
     * la marca prendida mientras baja.
     */
    fun hasLiveFlow(context: Context): Boolean {
        val p = PrefsHelper.getMdmPrefs(context)
        if (p.getString(KEY_PKG, null).isNullOrBlank()) return false
        val route = p.getString(KEY_ROUTE, ROUTE_PLAY_STORE) ?: ROUTE_PLAY_STORE
        if (route == ROUTE_PLAY_STORE && !p.getBoolean(KEY_IN_PROGRESS, false)) return false
        val started = p.getLong(KEY_STARTED_AT, 0L)
        val now = System.currentTimeMillis()
        if (started > 0L && now >= started && now - started > STALE_AFTER_MS) return false
        return true
    }

    /**
     * Identidad del flujo abierto: su hora de arranque. Quien trabaja en segundo plano
     * para un flujo (la ruta de la Tienda) la guarda al empezar y pregunta con
     * [isSameFlow]: así un flujo NUEVO del mismo paquete nunca se confunde con el viejo.
     */
    fun flowToken(context: Context): Long =
        PrefsHelper.getMdmPrefs(context).getLong(KEY_STARTED_AT, 0L)

    fun isSameFlow(context: Context, packageName: String, token: Long): Boolean =
        isActiveFor(context, packageName) && flowToken(context) == token

    /**
     * La ruta efectiva del flujo. Una clave AUSENTE se lee como Google Play a propósito:
     * es el estado que deja un flujo arrancado por una versión anterior a B.97 (por
     * ejemplo, el proceso murió a mitad y volvió ya actualizado), y para esos el único
     * camino que existía era Play Store. Los flujos nuevos escriben "" mientras eligen.
     */
    fun currentRoute(context: Context): String =
        PrefsHelper.getMdmPrefs(context).getString(KEY_ROUTE, ROUTE_PLAY_STORE) ?: ROUTE_PLAY_STORE

    /**
     * ¿Hay un flujo POR GOOGLE PLAY en curso? Es lo único que habilita lo que toca la
     * pantalla: el overlay negro, el ticker, relanzar Play Store y dejar abrir Play Store
     * sin rebotarla. Con la ruta de la Tienda (o mientras se elige) el teléfono se usa
     * normal y los filtros siguen andando.
     */
    fun isPlayStoreFlowRunning(context: Context): Boolean =
        isRunning(context) && currentRoute(context) == ROUTE_PLAY_STORE

    /**
     * 28/10/2026 (B.99) — ¿Google Play tiene que quedar DESTAPADA? Mientras dura la ruta de
     * Google Play, nada puede volver a ocultarla ni suspenderla.
     *
     * Medido en el logcat del QEMAY-QM01 del 27/10: el flujo destapaba Play Store
     * (`setApplicationHidden(false)`), Android avisaba PACKAGE_ADDED de com.android.vending
     * —para el sistema una app oculta "no está instalada", y destaparla es "instalarla"—, y
     * el PackageReceiver de LockSuite, al ver "se instaló Play Store", la volvía a ocultar y
     * suspender según la preferencia del administrador. 0,2 s después de abrirse, Android la
     * cerraba ("Force stopping com.android.vending: hiding pkg"; Play Store caía con "is
     * package not installed?"). El ciclo la volvía a abrir cada 1,5 s y el registro decía
     * "Google Play no estaba al frente", 81 veces en 2 minutos, hasta rendirse a los 75 s.
     * Pasaba en todo equipo con Play Store OCULTA (o suspendida con "ocultar íconos de lo
     * suspendido"): otra forma del "depende del celular".
     *
     * Es la única fuente de verdad que miran AppController (ocultar/suspender),
     * PackageReceiver, PolicyManager.restoreInstallRestrictions y la lista del kiosco antes
     * de tocar Google Play. Lo que se pida en el medio queda anotado como intención y lo
     * aplica el cierre del flujo (restoreInstallRestrictions), como siempre.
     */
    fun mustKeepPlayStoreOpen(context: Context): Boolean = isPlayStoreFlowRunning(context)

    /** Lo que se puede MEDIR de por qué Google Play no aparece (B.99). */
    data class PlayStoreState(val hidden: Boolean, val suspended: Boolean, val lockTaskBlocks: Boolean)

    fun playStoreState(context: Context): PlayStoreState {
        val ctx = context.applicationContext
        val dpm = ctx.getSystemService(Context.DEVICE_POLICY_SERVICE) as? DevicePolicyManager
            ?: return PlayStoreState(false, false, false)
        val admin = ComponentName(ctx, DeviceAdminReceiver::class.java)
        val oculta = try { dpm.isApplicationHidden(admin, PKG_PLAY_STORE) } catch (e: Exception) { false }
        val suspendida = try { dpm.isPackageSuspended(admin, PKG_PLAY_STORE) } catch (e: Exception) { false }
        val kiosco = try {
            val am = ctx.getSystemService(Context.ACTIVITY_SERVICE) as? android.app.ActivityManager
            val enLockTask = am != null && am.lockTaskModeState != android.app.ActivityManager.LOCK_TASK_MODE_NONE
            enLockTask && !dpm.isLockTaskPermitted(PKG_PLAY_STORE)
        } catch (e: Exception) {
            false
        }
        return PlayStoreState(oculta, suspendida, kiosco)
    }

    /**
     * Con el modo kiosco (Lock Task) encendido, la lista de apps que pueden pasar al frente
     * incluye Google Play mientras dura la ruta de Google Play (ver
     * PolicyManager.lockTaskAllowedPackages). Esto la vuelve a escribir. No hace nada si el
     * kiosco está apagado.
     */
    fun refreshKioskForUpdate(context: Context) {
        try {
            val pm = PolicyManager(context.applicationContext)
            if (pm.isKioskLockTaskEnabled() && !pm.isLockSuiteSuspended()) pm.applyKioskLockTask(true)
        } catch (e: Exception) {
            Log.w(TAG, "No se pudo actualizar la lista del kiosco: ${e.message}")
        }
    }

    /**
     * ¿El flujo de ESTE paquete sigue abierto? A diferencia de [isRunning] no mira
     * `mdm_install_in_progress`: `SelfUpdater` lo pone en false cuando falla a mitad de
     * una instalación (restoreInstallRestrictions) y eso no significa que el flujo haya
     * terminado — puede seguir por Google Play. Lo que dice que terminó es que [finish]
     * borró el paquete.
     */
    fun isActiveFor(context: Context, packageName: String): Boolean =
        !finishing && PrefsHelper.getMdmPrefs(context).getString(KEY_PKG, null) == packageName

    /**
     * ¿Está la pantalla de bloqueo puesta? Con el keyguard arriba no se tapa nada: ver la
     * regla 8 de la cabecera.
     */
    @Volatile private var keyguardCachedAt = 0L
    @Volatile private var keyguardCached = false

    /**
     * Se consulta en cada evento de accesibilidad mientras dura el flujo (hasta diez por
     * segundo) y es una llamada al sistema: se recuerda 300 ms.
     */
    fun isKeyguardLocked(context: Context): Boolean {
        val now = SystemClock.elapsedRealtime()
        if (keyguardCachedAt != 0L && now - keyguardCachedAt < 300L) return keyguardCached
        val v = try {
            (context.getSystemService(Context.KEYGUARD_SERVICE) as? android.app.KeyguardManager)
                ?.isKeyguardLocked == true
        } catch (e: Exception) {
            false
        }
        keyguardCached = v
        keyguardCachedAt = now
        return v
    }

    fun wasRecentlyUpdated(context: Context, packageName: String): Boolean {
        val p = PrefsHelper.getMdmPrefs(context)
        val lastPkg = p.getString(KEY_LAST_RESULT_PKG, null) ?: return false
        if (lastPkg != packageName) return false
        val lastAt = p.getLong(KEY_LAST_RESULT_AT, 0L)
        return lastAt > 0L && kotlin.math.abs(System.currentTimeMillis() - lastAt) < 30_000L
    }

    /** Lo anota SelfUpdater al abrir su ventana de instalación (APK de la Tienda / OTA). */
    const val KEY_INSTALL_STARTED_AT = "mdm_install_started_at"
    private const val KEY_STALE_TOKEN = "install_stale_token"
    private const val KEY_STALE_SEEN_AT = "install_stale_seen_at"
    private const val STALE_AFTER_MS = 25 * 60 * 1000L

    /**
     * 22/9/2026 — RED DE SEGURIDAD PARA UNA INSTALACIÓN QUE QUEDÓ "EN CURSO" PARA SIEMPRE.
     *
     * Mientras `mdm_install_in_progress` está en true, `refreshInstallRestriction()` y
     * `reapplyAllRestrictions()` NO tocan el bloqueo de instalación: es lo que permite que
     * Play Store (o el instalador de la Tienda) instale. Los dos cierres normales son
     * alarmas (10 min este flujo, 2 min el de SelfUpdater) y **las alarmas no sobreviven a
     * un reinicio**. Un celular que se reiniciaba (o se quedaba sin batería) en el medio
     * quedaba con la marca puesta y, en el caso de SelfUpdater, con `DISALLOW_INSTALL_APPS`
     * y `DISALLOW_INSTALL_UNKNOWN_SOURCES` levantadas: instalación libre, sin fecha de fin.
     * El flujo de Play Store lo rescataba la Accesibilidad al reconectar; el de la Tienda,
     * nadie.
     *
     * La llama WatchdogWorker en cada vuelta (~15 min; sobrevive a reinicios y a que muera
     * el proceso). No compara relojes contra el inicio del flujo: guarda qué flujo vio
     * (sus marcas de inicio, como identidad) y desde cuándo. Si el MISMO flujo sigue "en
     * curso" 25 minutos después, está muerto — ninguno legítimo dura eso — y se cierra por
     * el camino normal. Un flujo nuevo cambia la identidad y reinicia la cuenta, así que
     * nunca se corta una actualización que arrancó hace un minuto.
     */
    fun healStaleInstall(context: Context, source: String): Boolean {
        val ctx = context.applicationContext
        val p = PrefsHelper.getMdmPrefs(ctx)
        // B.97: un flujo de la ruta de la Tienda puede quedar con el paquete puesto y
        // `mdm_install_in_progress` en false (SelfUpdater la baja si un intento falla y el
        // flujo sigue por Google Play). Si el proceso muere justo ahí, eso también es un
        // flujo colgado: se cuenta como "en curso" para esta red de seguridad.
        val hayFlujo = p.getBoolean(KEY_IN_PROGRESS, false) || !p.getString(KEY_PKG, null).isNullOrBlank()
        if (!hayFlujo) {
            if (p.contains(KEY_PREV_UNINSTALL_PKG)) {
                restorePreviousUninstallBlock(ctx)
            }
            if (p.contains(KEY_STALE_TOKEN)) {
                p.edit().remove(KEY_STALE_TOKEN).remove(KEY_STALE_SEEN_AT).apply()
            }
            return false
        }
        val token = p.getLong(KEY_STARTED_AT, 0L).toString() + "|" +
            p.getLong(KEY_INSTALL_STARTED_AT, 0L) + "|" + (p.getString(KEY_PKG, null) ?: "")
        val now = System.currentTimeMillis()
        val seenAt = p.getLong(KEY_STALE_SEEN_AT, 0L)
        if (p.getString(KEY_STALE_TOKEN, null) != token || seenAt <= 0L || now < seenAt) {
            // Primera vez que se ve este flujo (o el reloj fue para atrás): se empieza a contar.
            p.edit().putString(KEY_STALE_TOKEN, token).putLong(KEY_STALE_SEEN_AT, now).apply()
            return false
        }
        if (now - seenAt < STALE_AFTER_MS) return false

        Log.w(TAG, "Instalación colgada desde hace ${(now - seenAt) / 60000} min ($token), vista desde $source: se cierra.")
        p.edit().remove(KEY_STALE_TOKEN).remove(KEY_STALE_SEEN_AT).apply()
        restorePreviousUninstallBlock(ctx)
        if (isRunning(ctx) || !currentPackage(ctx).isNullOrBlank()) {
            forceCleanup(ctx, RESULT_TIMEOUT)
        } else {
            PolicyManager(ctx).restoreInstallRestrictions()
        }
        return true
    }

    /**
     * 26/10/2026 (B.97) — CIERRA UN FLUJO QUE QUEDÓ HUÉRFANO AL REINICIARSE EL PROCESO.
     *
     * Elegir el camino y bajar/verificar la copia de la Tienda corre en una corrutina de
     * este proceso (HostedAppUpdater): si el proceso muere a mitad, nadie lo termina. Sin
     * esto el flujo quedaba "en curso" hasta el watchdog de 10 minutos —el panel mostraba
     * una descarga que ya no existía y un pedido nuevo se rechazaba con "ya hay una
     * actualización en curso"—. Qué se cierra y qué no lo decide
     * UpdateRoutePlanner.orphanReasonAfterRestart (con pruebas): la ruta de Google Play la
     * retoma la Accesibilidad (B.54) y una instalación ya entregada al sistema la termina
     * Android.
     *
     * La llama LockSuiteApplication al arrancar el proceso, que es justo cuando ningún
     * trabajo de la Tienda puede estar vivo. No la llames desde otro lado sin pensar en la
     * ventana entre "HostedAppUpdater terminó" y "beginPlayStoreRoute marcó la ruta".
     */
    fun closeOrphanedAfterRestart(context: Context) {
        val ctx = context.applicationContext
        val pkg = currentPackage(ctx)
        if (pkg.isNullOrBlank()) return
        if (HostedAppUpdater.isWorkingOn(flowToken(ctx))) return
        val motivo = UpdateRoutePlanner.orphanReasonAfterRestart(currentRoute(ctx), currentStage(ctx)) ?: return
        Log.w(TAG, "Flujo huérfano al arrancar el proceso ($pkg): $motivo")
        if (targetAlreadyUpdated(ctx)) {
            step(ctx, UpdateStepLog.OK, "$motivo, pero la versión nueva ya quedó instalada.")
            finish(ctx, RESULT_UPDATED, null, "$motivo; la versión nueva ya estaba instalada.")
            return
        }
        // El paso final ("Terminó sin actualizar: <motivo>") lo anota finish().
        finish(
            ctx, RESULT_ERROR,
            "La actualización de ${appLabel(ctx, pkg)} se interrumpió porque LockSuite se reinició. Volvé a intentarlo.",
            "$motivo."
        )
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

    fun versionCodeOf(context: Context, packageName: String): Long = try {
        val info = context.packageManager.getPackageInfo(packageName, 0)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) info.longVersionCode
        else @Suppress("DEPRECATION") info.versionCode.toLong()
    } catch (e: Exception) {
        -1L
    }

    fun versionNameOf(context: Context, packageName: String): String = try {
        context.packageManager.getPackageInfo(packageName, 0).versionName ?: ""
    } catch (e: Exception) {
        ""
    }

    /**
     * Quién instaló la app (26/10/2026, B.97). Es la primera pregunta cuando "Play Store
     * no la actualiza": Google Play solo puede reemplazar una app firmada igual que la
     * suya, y una app que vino de la Tienda de LockSuite, de un APK o de ADB puede estar
     * firmada por otro (B.66: el Waze de la Tienda lo firma un tercero).
     */
    fun installerOf(context: Context, packageName: String): String = try {
        val pm = context.packageManager
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            val src = pm.getInstallSourceInfo(packageName)
            src.installingPackageName ?: src.initiatingPackageName ?: ""
        } else {
            @Suppress("DEPRECATION")
            pm.getInstallerPackageName(packageName) ?: ""
        }
    } catch (e: Exception) {
        ""
    }

    fun installerLabel(context: Context, installer: String): String = when {
        installer.isBlank() -> "desconocido (APK suelto, ADB o de fábrica)"
        installer == PKG_PLAY_STORE -> "Google Play"
        installer == context.packageName -> "la Tienda de LockSuite"
        else -> installer
    }

    /**
     * ¿Es una app que maneja Google Play? La instaló Play Store, o vino de fábrica (Play
     * actualiza las apps de Google preinstaladas aunque el instalador figure vacío). Decide
     * qué camino va primero en automático: ver UpdateRoutePlanner.
     */
    fun isPlayManaged(context: Context, packageName: String, installer: String): Boolean {
        if (installer == PKG_PLAY_STORE) return true
        if (installer.isNotBlank()) return false
        return try {
            val ai = context.packageManager.getApplicationInfo(packageName, 0)
            (ai.flags and android.content.pm.ApplicationInfo.FLAG_SYSTEM) != 0
        } catch (e: Exception) {
            false
        }
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
            STAGE_CHOOSING_ROUTE -> "Buscando la mejor forma de actualizar..."
            STAGE_OPENING_STORE -> "Abriendo Google Play..."
            STAGE_WAITING_STORE -> "Esperando a Google Play..."
            STAGE_WAITING_UNLOCK -> "Desbloqueá el celular para continuar la actualización"
            STAGE_LOOKING_BUTTON -> "Buscando actualización..."
            STAGE_CONFIRMING -> "Confirmando descarga..."
            STAGE_QUEUED -> "Google Play está preparando la descarga..."
            STAGE_DOWNLOADING -> "Descargando actualización..."
            STAGE_INSTALLING -> "Instalando..."
            STAGE_HOSTED_DOWNLOADING -> "Descargando de la Tienda de LockSuite..."
            STAGE_HOSTED_VERIFYING -> "Verificando la copia de la Tienda..."
            STAGE_HOSTED_INSTALLING -> "Instalando en silencio..."
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
    // Registro paso a paso (26/10/2026, B.97)
    // ──────────────────────────────────────────────

    private val stepsLock = Any()
    /** Líneas del registro que se muestran en la pantalla negra. */
    private const val OVERLAY_STEP_LINES = 4

    /**
     * Anota un paso del flujo: qué decidió, qué vio, qué apretó, qué contestó el
     * sistema. Se ve en vivo en la pantalla negra (las últimas líneas) y entero en la
     * ficha del panel. Un paso igual al anterior no se repite (lo garantiza
     * UpdateStepLog), así que se puede llamar desde el ciclo de 700 ms sin llenarlo.
     *
     * @param evenIfIdle anotar aunque no haya un flujo abierto (lo usan [finish] para el
     *   paso final y [start] para dejar escrito un rechazo previo).
     */
    fun step(context: Context, kind: String, msg: String, evenIfIdle: Boolean = false) {
        val ctx = context.applicationContext
        val prefs = PrefsHelper.getMdmPrefs(ctx)
        if (!evenIfIdle && prefs.getString(KEY_PKG, null).isNullOrBlank()) return
        val raw: String
        synchronized(stepsLock) {
            val before = prefs.getString(KEY_STEPS, null)
            raw = UpdateStepLog.append(before, UpdateStepLog.Step(System.currentTimeMillis(), kind, msg))
            if (raw == before) return
            prefs.edit().putString(KEY_STEPS, raw).apply()
        }
        Log.i(TAG, "[paso/$kind] $msg")
        LockSuiteAccessibilityService.instance?.overlayManager
            ?.updateBlockingSteps(UpdateStepLog.tailForOverlay(raw, OVERLAY_STEP_LINES))
        syncToPanel(ctx, force = false)
    }

    fun stepsTail(context: Context): String =
        UpdateStepLog.tailForOverlay(PrefsHelper.getMdmPrefs(context).getString(KEY_STEPS, null), OVERLAY_STEP_LINES)

    // ──────────────────────────────────────────────
    // Aviso en la barra de notificaciones (26/10/2026, B.97)
    //
    // Dos usos, y los dos son casos donde NO hay pantalla negra: la ruta de la Tienda
    // (silenciosa: el usuario sigue usando el teléfono) y el celular bloqueado (ahí la
    // pantalla negra taparía la pantalla de bloqueo). VISIBILITY_PUBLIC para que se lea
    // también con el teléfono bloqueado, que es justamente cuando más hace falta.
    // ──────────────────────────────────────────────

    private const val NOTIF_CHANNEL = "locksuite_app_updates"
    private const val NOTIF_ID = 47_900

    private fun ensureNotifChannel(context: Context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        try {
            val nm = context.getSystemService(Context.NOTIFICATION_SERVICE) as? android.app.NotificationManager ?: return
            if (nm.getNotificationChannel(NOTIF_CHANNEL) != null) return
            val canal = android.app.NotificationChannel(
                NOTIF_CHANNEL, "Actualización de apps", android.app.NotificationManager.IMPORTANCE_DEFAULT
            ).apply {
                description = "Progreso de las actualizaciones de apps que manda el administrador"
                // Sin sonido ni vibración: se actualiza seguido mientras baja.
                setSound(null, null)
                enableVibration(false)
                lockscreenVisibility = android.app.Notification.VISIBILITY_PUBLIC
            }
            nm.createNotificationChannel(canal)
        } catch (e: Exception) {
            Log.w(TAG, "No se pudo crear el canal de notificaciones: ${e.message}")
        }
    }

    /**
     * @param progress 0..100, -1 = indeterminado, null = sin barra.
     * @param ongoing true mientras dura el flujo (no se puede deslizar).
     */
    fun notifyUser(context: Context, title: String, text: String, progress: Int?, ongoing: Boolean) {
        val ctx = context.applicationContext
        try {
            ensureNotifChannel(ctx)
            val nm = ctx.getSystemService(Context.NOTIFICATION_SERVICE) as? android.app.NotificationManager ?: return
            val b = androidx.core.app.NotificationCompat.Builder(ctx, NOTIF_CHANNEL)
                .setSmallIcon(if (ongoing) android.R.drawable.stat_sys_download else android.R.drawable.stat_sys_download_done)
                .setContentTitle(title)
                .setContentText(text)
                .setStyle(androidx.core.app.NotificationCompat.BigTextStyle().bigText(text))
                .setOnlyAlertOnce(true)
                .setOngoing(ongoing)
                .setAutoCancel(!ongoing)
                .setVisibility(androidx.core.app.NotificationCompat.VISIBILITY_PUBLIC)
                .setPriority(androidx.core.app.NotificationCompat.PRIORITY_DEFAULT)
            if (progress != null) {
                if (progress < 0) b.setProgress(0, 0, true) else b.setProgress(100, progress.coerceIn(0, 100), false)
            }
            if (!ongoing) b.setTimeoutAfter(10 * 60 * 1000L)
            nm.notify(NOTIF_ID, b.build())
        } catch (e: Exception) {
            // Sin permiso de notificaciones (Android 13+ sin B.55) o un fabricante raro:
            // el flujo sigue igual, el panel y el registro dicen lo mismo.
            Log.w(TAG, "No se pudo mostrar la notificación: ${e.message}")
        }
    }

    fun cancelUserNotification(context: Context) {
        try {
            (context.getSystemService(Context.NOTIFICATION_SERVICE) as? android.app.NotificationManager)
                ?.cancel(NOTIF_ID)
        } catch (e: Exception) {
            // nada
        }
    }

    // ──────────────────────────────────────────────
    // Arranque
    // ──────────────────────────────────────────────

    /**
     * @param routePref lo que pidió el panel: "auto" (por omisión), "play" o "hosted". Ver
     *   UpdateRoutePlanner. Los llamadores viejos (la pantalla del celular, el widget) no
     *   lo pasan y quedan en automático.
     * @return null si arranco bien; un mensaje de error si no se pudo arrancar.
     */
    fun start(
        context: Context,
        packageName: String,
        source: String,
        cancelable: Boolean,
        routePref: String = UpdateRoutePlanner.PREF_AUTO
    ): String? {
        val ctx = context.applicationContext
        val policyManager = PolicyManager(ctx)
        val pref = UpdateRoutePlanner.normalizePref(routePref)

        if (packageName.isBlank()) return "No se indico ninguna aplicacion."

        if (policyManager.isLockSuiteSuspended()) {
            return "LockSuite esta suspendido: Google Play ya esta libre, actualiza la app directamente."
        }

        // B.97: hasLiveFlow y no isRunning — un flujo por la Tienda está abierto sin la
        // marca de instalación prendida, y uno muerto no tiene que trabar los pedidos nuevos.
        if (hasLiveFlow(ctx)) {
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

        // B.97: la Accesibilidad hace falta SOLO para el camino de Google Play. En
        // automático todavía puede servir la copia de la Tienda (silenciosa, no toca la
        // pantalla), así que acá se rechaza únicamente si se pidió Google Play a la fuerza;
        // en automático lo decide UpdateRoutePlanner con el resto de los datos.
        if (pref == UpdateRoutePlanner.PREF_PLAY && LockSuiteAccessibilityService.instance == null) {
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
        //    revive y el flujo abriria la nada. (B.97: si se pidió Google Play a la
        //    fuerza se rechaza acá como antes; en automático es un dato más del plan.)
        val playProblem = playStoreProblem(ctx)
        if (pref == UpdateRoutePlanner.PREF_PLAY && playProblem != null) return playProblem

        val prefs = PrefsHelper.getMdmPrefs(ctx)
        val label = appLabel(ctx, packageName)
        val baseVc = versionCodeOf(ctx, packageName)
        val baseVn = versionNameOf(ctx, packageName)
        val installer = installerOf(ctx, packageName)
        val origen = if (source == SOURCE_LOCAL) "desde el celular" else "desde el panel"

        // 2. Espacio libre. Esta es la que aparecio en el CAT S22 Flip del 3/9: el
        //    equipo estaba sin lugar, Play Store creaba la sesion de instalacion y
        //    la descartaba, y el flujo se quedaba diez minutos en "Descargando..."
        //    sin decir nunca por que.
        val space = checkSpace(ctx, packageName)
        if (space.freeMb != Long.MAX_VALUE) {
            prefs.edit().putLong(KEY_LAST_FREE_MB, space.freeMb).apply()
        }
        if (!space.ok) {
            val reason = "No hay espacio suficiente: quedan ${space.freeMb} MB libres y hacen " +
                "falta al menos ${space.neededMb} MB. Liberá ${space.missingMb} MB y volvé a intentar."
            // B.97: que el rechazo también quede en el registro paso a paso, así la
            // ficha del panel muestra qué pasó con el último pedido aunque no arrancó.
            prefs.edit().putString(KEY_STEPS, "").apply()
            step(ctx, UpdateStepLog.INFO, "Pedido $origen: actualizar $label (versión $baseVn).", evenIfIdle = true)
            step(ctx, UpdateStepLog.ERROR, "No se arrancó: $reason", evenIfIdle = true)
            recordResult(ctx, RESULT_NO_SPACE, packageName, reason)
            Log.w(TAG, "Actualizacion de $packageName rechazada por espacio: $reason")
            return reason
        }

        finishRunnable?.let { mainHandler.removeCallbacks(it) }
        restoreRunnable?.let { mainHandler.removeCallbacks(it) }
        finishRunnable = null
        restoreRunnable = null
        finishing = false

        restorePreviousUninstallBlock(ctx)

        prefs.edit()
            .putString(KEY_PKG, packageName)
            // B.97: la marca de instalación NO se prende acá. La prende el camino que la
            // necesita, cuando la necesita: beginPlayStoreRoute() para Google Play, y
            // SelfUpdater por los segundos que dura la instalación silenciosa. Mientras se
            // elige el camino o se baja la copia de la Tienda, el equipo queda con todas
            // sus restricciones puestas (ver isRunning).
            .putString(KEY_STAGE, STAGE_PREPARING)
            .remove(KEY_DETAIL)
            .remove(KEY_DEBUG_LABELS)
            .putLong(KEY_STARTED_AT, System.currentTimeMillis())
            .putString(KEY_SOURCE, source)
            .putBoolean(KEY_CANCELABLE, cancelable)
            .putLong(KEY_BASE_VERSION, baseVc)
            // B.97 — ruta todavía sin elegir: hasta que se elija, nada tapa la pantalla
            // ni maneja Play Store (ver isPlayStoreFlowRunning).
            .putString(KEY_ROUTE, UpdateRoutePlanner.ROUTE_UNDECIDED)
            .putString(KEY_ROUTE_PREF, pref)
            .remove(KEY_ROUTE_WHY)
            .putInt(KEY_PROGRESS, -1)
            .putString(KEY_INSTALLER, installer)
            .putString(KEY_BASE_VERSION_NAME, baseVn)
            .remove(KEY_VERSION_AFTER)
            .putString(KEY_STEPS, "")
            .apply()

        LockSuiteAccessibilityService.instance?.invalidateFlagsCache()
        LockSuiteAccessibilityService.instance?.overlayManager?.clearBlockingSteps()

        step(ctx, UpdateStepLog.INFO,
            "Pedido $origen: actualizar $label (versión $baseVn, código $baseVc). La instaló: ${installerLabel(ctx, installer)}.")
        if (installer.isNotBlank() && installer != PKG_PLAY_STORE) {
            step(ctx, UpdateStepLog.WARN,
                "Ojo: a esta app no la instaló Google Play. Si su firma no es la de la versión de Google Play, " +
                    "Play Store no va a poder actualizarla (Android no deja reemplazar una app firmada por otro).")
        }
        if (space.freeMb != Long.MAX_VALUE) {
            step(ctx, UpdateStepLog.OK, "Espacio libre: ${space.freeMb} MB (hacen falta ~${space.neededMb} MB).")
        }

        armWatchdog(ctx)
        syncToPanel(ctx, force = true)
        Log.i(TAG, "Flujo de actualizacion iniciado para $packageName (origen=$source, ruta pedida=$pref)")

        if (pref == UpdateRoutePlanner.PREF_PLAY) {
            return beginPlayStoreRoute(ctx, packageName, "Elegido por el administrador.")
        }

        // Automático o Tienda: hay que mirar la Tienda de LockSuite, y eso es red. Se
        // hace en segundo plano; el ack del panel dice "arrancó" y el resto lo cuentan
        // la etapa, los pasos y el resultado — igual que con Google Play.
        setStage(ctx, STAGE_CHOOSING_ROUTE)
        HostedAppUpdater.chooseAndRun(ctx, packageName, pref)
        return null
    }

    /**
     * ¿Se puede usar Google Play en este equipo? `null` = sí; si no, por qué no, listo
     * para mostrar.
     */
    fun playStoreProblem(context: Context): String? {
        val storeState = try {
            context.packageManager.getApplicationEnabledSetting(PKG_PLAY_STORE)
        } catch (e: Exception) {
            return "Google Play no esta instalada en este equipo, asi que no se puede actualizar por la tienda."
        }
        if (storeState == PackageManager.COMPONENT_ENABLED_STATE_DISABLED ||
            storeState == PackageManager.COMPONENT_ENABLED_STATE_DISABLED_USER
        ) {
            return "Google Play esta deshabilitada en este equipo. Habilitala antes de actualizar."
        }
        return null
    }

    /**
     * El camino de Google Play: destapar Play Store, tapar la pantalla, abrir la ficha y
     * dejar que la Accesibilidad la maneje. Es lo que hacía start() hasta el 26/10, sin
     * cambios de comportamiento; ahora se puede llegar también desde la ruta de la
     * Tienda cuando la copia de la Tienda no sirvió (y entonces se llama desde un hilo
     * de fondo: todo lo de acá es seguro fuera del hilo principal — el overlay y el
     * ticker se postean al principal).
     *
     * @return null si arrancó; si no, el motivo (y el flujo ya quedó cerrado con él).
     */
    fun beginPlayStoreRoute(context: Context, packageName: String, why: String): String? {
        val ctx = context.applicationContext
        if (!isActiveFor(ctx, packageName)) return "El flujo de actualizacion ya se cerro."
        if (LockSuiteAccessibilityService.instance == null) {
            val msg = "El servicio de accesibilidad de LockSuite no esta activo. Sin el, la actualizacion dejaria Google Play abierto sin proteccion."
            step(ctx, UpdateStepLog.ERROR, "No se puede ir por Google Play: la Accesibilidad no está activa.")
            finish(ctx, RESULT_ERROR, msg, msg)
            return msg
        }
        playStoreProblem(ctx)?.let { msg ->
            step(ctx, UpdateStepLog.ERROR, msg)
            finish(ctx, RESULT_STORE_ERROR, msg, msg)
            return msg
        }

        val prefs = PrefsHelper.getMdmPrefs(ctx)
        val cancelable = isCancelable(ctx)
        prefs.edit()
            .putString(KEY_ROUTE, ROUTE_PLAY_STORE)
            .putString(KEY_ROUTE_WHY, why)
            // Si se llegó acá después de un intento por la Tienda que falló, SelfUpdater
            // pudo haber bajado esta marca al restaurar las restricciones (ver
            // isActiveFor): sin ella el resto del flujo creería que no hay nada en curso.
            .putBoolean(KEY_IN_PROGRESS, true)
            .putInt(KEY_PROGRESS, -1)
            .apply()
        step(ctx, UpdateStepLog.INFO, "Camino elegido: Google Play. $why")

        LockSuiteAccessibilityService.instance?.invalidateFlagsCache()
        // B.99: con el kiosco encendido, Google Play entra a su lista mientras dure esto.
        refreshKioskForUpdate(ctx)

        // Con el celular bloqueado, showOverlay no tapa nada y el ciclo de la Accesibilidad
        // espera a que lo desbloqueen (lo anota en el registro y avisa en la pantalla de
        // bloqueo): ver scanAndAct, paso 2-bis.
        showOverlay(ctx, packageName, STAGE_PREPARING, null, cancelable)

        try {
            val dpm = ctx.getSystemService(Context.DEVICE_POLICY_SERVICE) as DevicePolicyManager
            val admin = ComponentName(ctx, DeviceAdminReceiver::class.java)

            val wasUninstallBlocked = try { dpm.isUninstallBlocked(admin, packageName) } catch (e: Exception) { false }
            prefs.edit()
                .putBoolean(KEY_PREV_UNINSTALL_BLOCKED, wasUninstallBlocked)
                .putString(KEY_PREV_UNINSTALL_PKG, packageName)
                .apply()
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
        step(ctx, UpdateStepLog.ACT,
            "Google Play destapada y la instalación habilitada; la app quedó protegida contra desinstalación mientras dure.")

        wakeScreen(ctx)

        setStage(ctx, STAGE_OPENING_STORE)
        val opened = openStore(ctx, packageName)
        if (!opened) {
            step(ctx, UpdateStepLog.ERROR, "Android no pudo abrir la ficha de la app en Google Play.")
            finish(ctx, RESULT_STORE_ERROR, "No se pudo abrir Google Play en este equipo.",
                "startActivity(market://details) fallo para $packageName")
            return "No se pudo abrir Google Play en este equipo."
        }
        step(ctx, UpdateStepLog.ACT, "Abrí la ficha de la app en Google Play.")

        setStage(ctx, STAGE_WAITING_STORE)
        syncToPanel(ctx, force = true)

        PlayUpdateSessionWatcher.start(
            ctx, packageName,
            onProgress = { pct -> setStage(ctx, STAGE_DOWNLOADING, "Descargando... $pct%", progress = pct) },
            onFinished = { ok ->
                if (ok) {
                    step(ctx, UpdateStepLog.OK, "El sistema confirmó que terminó la instalación.")
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
                    step(ctx, UpdateStepLog.WARN,
                        "Una sesión de instalación de Google Play terminó sin éxito (puede ser una intermedia; se sigue mirando).")
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

    /**
     * @param progress porcentaje de una descarga (0..100). Al cambiar a una etapa que no
     *   es de descarga se vuelve a -1 solo, así el panel no muestra una barra vieja.
     */
    fun setStage(context: Context, stage: String, detail: String? = null, progress: Int? = null) {
        val ctx = context.applicationContext
        // B.97: isActiveFor y no isRunning — en la ruta de la Tienda, SelfUpdater baja
        // `mdm_install_in_progress` si un intento falla, y el flujo sigue abierto.
        val pkg = currentPackage(ctx) ?: return
        if (!isActiveFor(ctx, pkg)) return
        val prefs = PrefsHelper.getMdmPrefs(ctx)
        val prevStage = prefs.getString(KEY_STAGE, STAGE_IDLE)
        val prevDetail = prefs.getString(KEY_DETAIL, null)
        val effectiveDetail = detail ?: if (stage == prevStage) prevDetail else null
        val esDescarga = stage == STAGE_DOWNLOADING || stage == STAGE_HOSTED_DOWNLOADING
        val prevProgress = prefs.getInt(KEY_PROGRESS, -1)
        val newProgress = when {
            progress != null -> progress
            esDescarga -> prevProgress
            else -> -1
        }

        if (prevStage == stage && prevDetail == effectiveDetail && prevProgress == newProgress) return

        val editor = prefs.edit().putString(KEY_STAGE, stage).putInt(KEY_PROGRESS, newProgress)
        if (effectiveDetail == null) editor.remove(KEY_DETAIL) else editor.putString(KEY_DETAIL, effectiveDetail)
        editor.apply()

        LockSuiteAccessibilityService.instance?.overlayManager?.let { om ->
            om.updateBlockingMessage(null, stageLabel(stage, effectiveDetail))
            // La barra con el porcentaje real en la pantalla negra (si no se sabe, la ruedita).
            om.updateBlockingProgress(if (newProgress >= 0) newProgress else null)
        }

        // Un paso por cada CAMBIO de etapa (no por cada punto del porcentaje: eso va en
        // KEY_PROGRESS). Las decisiones finas —qué botón, por qué— las anota quien decide.
        // Si la etapa ya figura entre los últimos pasos no se repite: el ciclo de Google
        // Play puede ir y venir entre "Esperando a Google Play" y "Buscando actualización"
        // mientras la ficha se dibuja, y eso llenaría el registro de idas y vueltas
        // empujando afuera lo que importa (qué apretó).
        if (prevStage != stage && stage != STAGE_IDLE) {
            val etiqueta = stageLabel(stage, null).removeSuffix("...").removeSuffix("…")
            val recientes = UpdateStepLog.decode(prefs.getString(KEY_STEPS, null)).takeLast(4).map { it.msg }
            if (etiqueta !in recientes) step(ctx, UpdateStepLog.INFO, etiqueta)
        }

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
        // B.97 — la pantalla negra existe SOLO en la ruta de Google Play: es la que
        // protege a Play Store destapada. En la de la Tienda (o mientras se elige) no hay
        // nada que proteger y el usuario sigue usando el teléfono.
        if (currentRoute(ctx) != ROUTE_PLAY_STORE) return
        // Con la pantalla de bloqueo puesta NO se tapa: el overlay queda encima del
        // keyguard y de la barra de navegación, y el usuario no podría desbloquear. La
        // Accesibilidad lo vuelve a poner apenas lo desbloquean (ver scanAndAct).
        if (isKeyguardLocked(ctx)) {
            if (service.overlayManager.isBlockingMessageVisible()) {
                service.overlayManager.hideBlockingMessageOverlay()
            }
            return
        }
        val effectiveDetail = detail ?: currentDetail(ctx)
        service.overlayManager.showBlockingMessageOverlay(
            title = "Actualizando ${appLabel(ctx, packageName)}",
            subtitle = stageLabel(stage, effectiveDetail),
            cancelable = cancelable,
            onCancel = { requestCancel(ctx) }
        )
        service.overlayManager.updateBlockingSteps(stepsTail(ctx))
        val pct = PrefsHelper.getMdmPrefs(ctx).getInt(KEY_PROGRESS, -1)
        service.overlayManager.updateBlockingProgress(if (pct >= 0) pct else null)
    }

    // ──────────────────────────────────────────────
    // Cancelacion y cierre
    // ──────────────────────────────────────────────

    fun requestCancel(context: Context) {
        val ctx = context.applicationContext
        Log.i(TAG, "Cancelacion pedida por el usuario/panel")
        // B.97: con la instalación silenciosa ya en manos de PackageInstaller no hay forma
        // de frenarla, y cerrar el flujo acá dejaría el panel diciendo "cancelada" de una
        // app que se termina de instalar dos segundos después. Se dice la verdad.
        if (currentRoute(ctx) == ROUTE_HOSTED && currentStage(ctx) == STAGE_HOSTED_INSTALLING) {
            step(ctx, UpdateStepLog.WARN,
                "Se pidió cancelar, pero la instalación ya está en manos del sistema y no se puede frenar: se deja terminar.")
            return
        }
        step(ctx, UpdateStepLog.WARN, "Cancelada por el usuario o el administrador.")
        finish(ctx, RESULT_CANCELLED, "Actualización cancelada.")
    }

    /**
     * Resultado de la instalación SILENCIOSA de la copia de la Tienda, tal como lo
     * informa PackageInstaller (lo llama PackageInstallStatusReceiver).
     *
     * @return true si correspondía a la actualización en curso (y quien llama no tiene
     *   que hacer nada más, ni mostrar su propio aviso).
     */
    fun onHostedInstallStatus(context: Context, packageName: String?, status: Int, message: String?): Boolean {
        val ctx = context.applicationContext
        val pkg = currentPackage(ctx) ?: return false
        if (packageName != null && packageName != pkg) return false
        if (currentRoute(ctx) != ROUTE_HOSTED) return false

        if (status == UpdateRoutePlanner.STATUS_SUCCESS) {
            step(ctx, UpdateStepLog.OK, "El sistema confirmó la instalación de la copia de la Tienda.")
            finish(ctx, RESULT_UPDATED, null)
            return true
        }
        val falla = UpdateRoutePlanner.hostedInstallFailure(status, message)
        val pref = PrefsHelper.getMdmPrefs(ctx).getString(KEY_ROUTE_PREF, UpdateRoutePlanner.PREF_AUTO)
        if (falla.fallbackToPlay && pref == UpdateRoutePlanner.PREF_AUTO) {
            fallbackToPlayStore(ctx, pkg, "El sistema rechazó la copia de la Tienda: ${falla.reason}.")
        } else {
            step(ctx, UpdateStepLog.ERROR, "El sistema rechazó la copia de la Tienda: ${falla.reason}.")
            finish(ctx, falla.result, "No se pudo instalar la actualización: ${falla.reason}.", falla.reason)
        }
        return true
    }

    /**
     * La copia de la Tienda no sirvió (B.97): seguir por Google Play, si se puede, como
     * si el flujo hubiera arrancado por ahí. Corre en el hilo principal.
     *
     * Antes de abrir Google Play se vuelve a cerrar TODO lo que la ruta de la Tienda pudo
     * haber abierto (SelfUpdater levanta también DISALLOW_INSTALL_UNKNOWN_SOURCES, que el
     * camino de Google Play no necesita): restoreInstallRestrictions() deja el equipo
     * como antes del pedido y beginPlayStoreRoute() abre exactamente lo de siempre. Y el
     * watchdog se vuelve a armar: Google Play tiene que tener sus diez minutos aunque la
     * descarga de la Tienda se haya llevado unos cuantos.
     */
    fun fallbackToPlayStore(context: Context, packageName: String, reason: String) {
        val ctx = context.applicationContext
        if (Looper.myLooper() != Looper.getMainLooper()) {
            mainHandler.post { fallbackToPlayStore(ctx, packageName, reason) }
            return
        }
        if (!isActiveFor(ctx, packageName)) return
        val planB = UpdateRoutePlanner.plan(
            UpdateRoutePlanner.PREF_PLAY, packageName, null, versionCodeOf(ctx, packageName), null,
            LockSuiteAccessibilityService.instance != null, playStoreProblem(ctx)
        )
        when (planB) {
            is UpdateRoutePlanner.Plan.PlayStore -> {
                step(ctx, UpdateStepLog.WARN, "$reason Se sigue por Google Play.")
                cancelUserNotification(ctx)
                try {
                    PolicyManager(ctx).restoreInstallRestrictions()
                } catch (e: Exception) {
                    Log.w(TAG, "No se pudieron restaurar las restricciones antes de ir a Google Play: ${e.message}")
                }
                armWatchdog(ctx)
                beginPlayStoreRoute(ctx, packageName, "La copia de la Tienda no sirvió. $reason")
            }
            is UpdateRoutePlanner.Plan.Fail -> {
                step(ctx, UpdateStepLog.ERROR, "$reason Y por Google Play tampoco: ${planB.message}")
                finish(ctx, RESULT_ERROR, "No se pudo actualizar. $reason ${planB.message}", "$reason | ${planB.message}")
            }
            is UpdateRoutePlanner.Plan.Hosted -> finish(ctx, RESULT_ERROR, reason, reason)
        }
    }

    /**
     * @param message lo que ve el usuario en la pantalla negra antes de que se
     *   saque; puede ser null para el texto por omision del resultado.
     * @param reason motivo tecnico para el panel. Se guarda aunque el usuario ya
     *   no este mirando: es lo unico que queda despues, y es lo que evita la
     *   siguiente sesion de diagnostico a ciegas.
     */
    fun finish(context: Context, result: String, message: String? = null, reason: String? = null) {
        // B.97 — SIEMPRE en el hilo principal. Desde el 26/10 hay cierres que llegan de
        // hilos de fondo (la ruta de la Tienda, el WatchdogWorker vía healStaleInstall) y
        // otros del principal (PackageReceiver, el ticker, el botón Cancelar). Con un
        // solo hilo, la marca `finishing` de abajo no puede tener carreras.
        if (Looper.myLooper() != Looper.getMainLooper()) {
            val appCtx = context.applicationContext
            mainHandler.post { finish(appCtx, result, message, reason) }
            return
        }
        val ctx = context.applicationContext
        val prefs = PrefsHelper.getMdmPrefs(ctx)
        val pkg = prefs.getString(KEY_PKG, null)
        val wasRunning = isRunning(ctx)

        if (finishing) {
            LockSuiteAccessibilityService.instance?.overlayManager?.hideBlockingMessageOverlay()
            return
        }

        // B.97: lo que hace falta saber del flujo ANTES de borrarlo.
        val route = currentRoute(ctx)
        val stageAlCerrar = prefs.getString(KEY_STAGE, STAGE_IDLE)
        // Un cierre por TIEMPO dice dónde se quedó (antes era siempre "tardó demasiado").
        val effectiveReason = reason ?: message
            ?: if (result == RESULT_TIMEOUT) UpdateRoutePlanner.timeoutReasonForStage(stageAlCerrar) else null
        val versionDespues = if (!pkg.isNullOrBlank()) {
            val vn = versionNameOf(ctx, pkg)
            val vc = versionCodeOf(ctx, pkg)
            if (vc >= 0L) "$vn ($vc)" else ""
        } else ""

        // El último paso del registro: el desenlace, con su motivo. Va ANTES de borrar el
        // paquete (step() no anota sin flujo abierto, salvo evenIfIdle).
        if (!pkg.isNullOrBlank()) {
            val kind = when (result) {
                RESULT_UPDATED, RESULT_UP_TO_DATE -> UpdateStepLog.OK
                RESULT_CANCELLED -> UpdateStepLog.WARN
                else -> UpdateStepLog.ERROR
            }
            val texto = when (result) {
                RESULT_UPDATED -> "Listo: actualizada" + (if (versionDespues.isNotEmpty()) " a la versión $versionDespues." else ".")
                RESULT_UP_TO_DATE -> "Listo: la app ya estaba en la última versión."
                RESULT_CANCELLED -> "Cancelada."
                else -> "Terminó sin actualizar ($result)" + (if (!effectiveReason.isNullOrBlank()) ": $effectiveReason" else ".")
            }
            step(ctx, kind, texto, evenIfIdle = true)
        }

        finishing = true

        Log.i(TAG, "Cerrando flujo de actualizacion: result=$result pkg=$pkg running=$wasRunning route=$route")

        PlayUpdateSessionWatcher.stop(ctx)
        val service = LockSuiteAccessibilityService.instance
        service?.stopUpdateTicker()

        val cierre = prefs.edit()
            .remove(KEY_PKG)
            .putBoolean(KEY_IN_PROGRESS, false)
            .putString(KEY_STAGE, STAGE_IDLE)
            .remove(KEY_DETAIL)
            .remove(KEY_BASE_VERSION)
            .putInt(KEY_PROGRESS, -1)
        // B.97: un cierre SIN flujo (forceCleanup desde "Cancelar" con todo ya limpio, o la
        // alarma vieja de un flujo que ya terminó) no pisa el resultado del último flujo
        // real: antes lo reemplazaba por "CANCELLED" / "TIMEOUT" de un paquete vacío, y el
        // panel perdía el motivo de la última actualización de verdad.
        if (!pkg.isNullOrBlank()) {
            cierre.putString(KEY_VERSION_AFTER, versionDespues)
                .putString(KEY_LAST_RESULT, result)
                .putString(KEY_LAST_RESULT_PKG, pkg)
                .putString(KEY_LAST_RESULT_REASON, effectiveReason ?: "")
                .putLong(KEY_LAST_RESULT_AT, System.currentTimeMillis())
        }
        cierre.apply()

        // B.99: si Google Play había entrado a la lista del kiosco por esta actualización,
        // sale ahora (el flujo ya figura cerrado, así que la lista se arma sin ella).
        if (route == ROUTE_PLAY_STORE) refreshKioskForUpdate(ctx)

        // B.86: Restaurar setUninstallBlocked sincrónicamente al cerrar el flujo,
        // sin esperar los 5.6 segundos de lectura del aviso. Así si el proceso muere
        // mientras se muestra el cartel final, la app no queda con desinstalación
        // bloqueada para siempre.
        restorePreviousUninstallBlock(ctx)

        service?.invalidateFlagsCache()
        service?.resetUpdateSession()

        val label = if (!pkg.isNullOrBlank()) appLabel(ctx, pkg) else "App"
        val finalNotice = when (result) {
            RESULT_UP_TO_DATE -> message ?: "$label ya está actualizada."
            RESULT_UPDATED -> message ?: "✓ Actualización completada con éxito."
            RESULT_CANCELLED -> message ?: "Actualización cancelada."
            RESULT_ERROR -> message ?: "No se pudo completar la actualización."
            RESULT_TIMEOUT -> message ?: effectiveReason ?: "La actualización tardó demasiado y se canceló."
            RESULT_NO_SPACE -> message ?: "No hay espacio suficiente para actualizar $label."
            RESULT_NEEDS_ACCOUNT -> message
                ?: "Google Play pide iniciar sesión: este equipo no tiene una cuenta de Google configurada."
            RESULT_NOT_AVAILABLE -> message
                ?: "Google Play no ofrece una actualización de $label para este equipo."
            RESULT_STORE_ERROR -> message ?: "Google Play devolvió un error. Probá de nuevo más tarde."
            else -> message
        }

        // B.97 — el aviso de la barra de notificaciones. En la ruta de la Tienda es lo
        // ÚNICO que ve el usuario (no hubo pantalla negra), y en la de Google Play cierra
        // el "desbloqueá el celular" si quedó puesto.
        if (route == ROUTE_HOSTED && !pkg.isNullOrBlank() && !finalNotice.isNullOrBlank()) {
            notifyUser(ctx, label, finalNotice, null, ongoing = false)
        } else {
            cancelUserNotification(ctx)
        }

        val showNoticeOnOverlay = !finalNotice.isNullOrBlank() &&
            service?.overlayManager?.isBlockingMessageVisible() == true

        if (showNoticeOnOverlay) {
            service?.overlayManager?.updateBlockingMessage(
                title = label,
                subtitle = finalNotice
            )
            // Que el cartel final no quede encima de una barra vieja ("No se pudo…" al 45 %).
            service?.overlayManager?.updateBlockingProgress(null)
        }

        // Un aviso que explica QUE hacer (liberar espacio, configurar la cuenta)
        // tiene que quedar en pantalla el tiempo suficiente para leerlo. 1,8 s
        // alcanzan para "✓ listo" y no alcanzan para una frase de dos renglones.
        val needsReading = result == RESULT_NO_SPACE || result == RESULT_NEEDS_ACCOUNT ||
            result == RESULT_NOT_AVAILABLE || result == RESULT_STORE_ERROR ||
            result == RESULT_ERROR
        val delayMs = if (!showNoticeOnOverlay) 0L else if (needsReading) 5000L else 1800L

        val outerRunnable = Runnable {
            finishRunnable = null
            service?.overlayManager?.hideBlockingMessageOverlay()
            service?.overlayManager?.clearBlockingSteps()

            // Volver al INICIO solo si se abrió Google Play (es para sacarla de adelante).
            // En la ruta de la Tienda el usuario estaba usando el teléfono: mandarlo al
            // inicio le cerraría lo que tenía abierto sin ningún motivo. Lo mismo si el
            // flujo nunca llegó a elegir camino.
            if (route == ROUTE_PLAY_STORE) {
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
            }

            val innerRunnable = Runnable {
                restoreRunnable = null
                try {
                    PolicyManager(ctx).restoreInstallRestrictions()
                } catch (e: Exception) {
                    Log.e(TAG, "Error restaurando restricciones: ${e.message}")
                }
                restorePreviousUninstallBlock(ctx)

                cancelWatchdog(ctx)
                releaseWake()
                finishing = false
                LockSuiteAccessibilityService.instance?.overlayManager?.hideBlockingMessageOverlay()
                syncToPanel(ctx, force = true)
            }
            restoreRunnable = innerRunnable
            mainHandler.postDelayed(innerRunnable, RESTORE_DELAY_MS)
        }
        finishRunnable = outerRunnable
        mainHandler.postDelayed(outerRunnable, delayMs)

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
        val am = context.getSystemService(Context.ALARM_SERVICE) as? AlarmManager ?: return
        val at = SystemClock.elapsedRealtime() + WATCHDOG_TIMEOUT_MS
        val pi = watchdogPendingIntent(context)
        // Esta alarma es la ULTIMA garantia de que la pantalla negra se saque y de
        // que el equipo se vuelva a bloquear si todo lo demas falla: el proceso
        // muere, el ticker se corta al re-vincularse el servicio de accesibilidad
        // (ver LockSuiteAccessibilityService.onServiceConnected), o el hilo principal
        // se traba. Tiene que quedar programada SI O SI.
        //
        // ⚠️ 9/9/2026 (B.54) — POR QUE YA NO ES UN setExactAndAllowWhileIdle A SECAS.
        //
        // Desde Android 12 (API 31) setExact* y setExactAndAllowWhileIdle exigen el
        // permiso de alarmas exactas; sin el, la llamada tira SecurityException. El
        // Manifest no declaraba NINGUNO, asi que en Android 12/13/14 —incluido el
        // equipo Android 13 del dueno (B.8)— la excepcion caia en el catch y la
        // alarma watchdog NO se programaba nunca: el respaldo final del flujo estaba
        // apagado EN SILENCIO, que es justo la clase de silencio que este subsistema
        // tiene que evitar. Ahora: (1) el Manifest declara USE_EXACT_ALARM +
        // SCHEDULE_EXACT_ALARM; (2) igual se verifica canScheduleExactAlarms() en
        // caliente, porque un OEM puede revocarlo; (3) si no se puede exacta, se cae
        // a setAndAllowWhileIdle, que NO necesita permiso y dispara igual en Doze —el
        // sistema la puede correr unos minutos, tolerable para un tope de 10 minutos.
        try {
            val canExact = Build.VERSION.SDK_INT < Build.VERSION_CODES.S || am.canScheduleExactAlarms()
            if (canExact) {
                am.setExactAndAllowWhileIdle(AlarmManager.ELAPSED_REALTIME_WAKEUP, at, pi)
            } else {
                am.setAndAllowWhileIdle(AlarmManager.ELAPSED_REALTIME_WAKEUP, at, pi)
                Log.w(TAG, "Sin permiso de alarma exacta: watchdog programado inexacto (setAndAllowWhileIdle)")
            }
        } catch (e: Exception) {
            // Ultimo recurso: inexacta, que no exige ningun permiso. Es preferible un
            // watchdog que se corra unos minutos a no tener watchdog.
            try {
                am.setAndAllowWhileIdle(AlarmManager.ELAPSED_REALTIME_WAKEUP, at, pi)
            } catch (e2: Exception) {
                Log.w(TAG, "No se pudo programar el watchdog ni siquiera inexacto: ${e2.message}")
            }
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

    @Volatile private var syncPendiente = false

    /**
     * Publica el estado del flujo en Firebase para que el panel lo vea en vivo.
     *
     * Con tope de una publicación cada 1,5 s, pero SIN perder la última (B.97): antes, lo
     * que pasaba dentro de esa ventana no se publicaba hasta el próximo cambio, y con el
     * registro paso a paso eso dejaba al panel un paso atrás justo cuando el flujo se
     * quedaba esperando (el celular bloqueado, una descarga lenta). Ahora, si se saltea
     * una, queda programada una al final de la ventana.
     */
    fun syncToPanel(context: Context, force: Boolean) {
        val now = SystemClock.elapsedRealtime()
        if (!force && now - lastSyncAt < 1500L) {
            if (!syncPendiente) {
                syncPendiente = true
                val appCtx = context.applicationContext
                mainHandler.postDelayed({
                    syncPendiente = false
                    syncToPanel(appCtx, force = true)
                }, (1500L - (now - lastSyncAt)).coerceAtLeast(0L) + 50L)
            }
            return
        }
        lastSyncAt = now
        try {
            FirebaseDeviceSync.syncUpdateFlow(context.applicationContext)
        } catch (e: Exception) {
            // Sin red no pasa nada: el overlay local sigue mostrando el estado.
        }
    }
}
