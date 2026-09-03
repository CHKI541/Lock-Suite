package com.ejemplo.locksuite.util

import android.content.Context
import android.content.pm.PackageInstaller
import android.os.Handler
import android.os.Looper
import android.util.Log

/**
 * Observa las sesiones de instalación del SISTEMA para saber, sin depender del
 * idioma ni de lo que diga la pantalla, si Google Play arrancó a descargar una
 * app, cuánto lleva y si terminó bien.
 *
 * Por qué existe (16/8/2026)
 * ─────────────────────────
 * La primera versión del flujo de actualización leía la pantalla de Play Store
 * buscando las palabras "Descargando" / "Instalando" / "Abrir". Eso falla de tres
 * formas distintas: no anda si el equipo no está en español, se rompe cada vez que
 * Google cambia el texto, y confunde la descripción de la app con el estado real
 * (la ficha de Mercado Pago dice "pagos pendientes", y "pendiente" estaba en la
 * lista de palabras de progreso).
 *
 * `PackageInstaller` reporta las sesiones de TODOS los instaladores del equipo,
 * Play Store incluida. `SessionInfo.getAppPackageName()` dice qué app se está
 * instalando y `getProgress()` devuelve un número de 0 a 1. Es la misma
 * información que muestra Play Store, pero de la fuente, en números y sin idioma.
 *
 * Uso:
 *   PlayUpdateSessionWatcher.start(ctx, "com.waze",
 *       onProgress = { pct -> ... },
 *       onFinished = { ok -> ... })
 *   ...
 *   PlayUpdateSessionWatcher.stop(ctx)
 *
 * `stop()` es obligatorio y tiene que estar en TODOS los caminos de salida
 * (éxito, cancelación, timeout, error): un SessionCallback sin desregistrar
 * sobrevive al flujo y dispara callbacks de una actualización que ya no existe.
 */
object PlayUpdateSessionWatcher {

    private const val TAG = "LockSuite_Session"

    private val mainHandler = Handler(Looper.getMainLooper())

    @Volatile private var targetPackage: String? = null
    @Volatile private var registered = false
    @Volatile private var installer: PackageInstaller? = null

    private fun matchesTarget(sessionId: Int): Boolean {
        val target = targetPackage ?: return false
        val info = try {
            installer?.getSessionInfo(sessionId)
        } catch (e: Exception) {
            null
        } ?: return false
        return info.appPackageName == target
    }

    /**
     * true apenas se vio UNA sesión de instalación para el paquete objetivo.
     * No vuelve a false si esa sesión se cancela: sirve para distinguir
     * "nunca arrancó nada" de "arrancó y algo pasó", que son dos situaciones
     * que piden reacciones distintas.
     */
    @Volatile var sawSession = false
        private set

    /** Último porcentaje informado (0..100), o -1 si todavía no hubo ninguno. */
    @Volatile var lastProgress = -1
        private set

    // ──────────────────────────────────────────────
    // Marcas de tiempo (nuevas el 3/9/2026)
    //
    // Por qué hacían falta: `sawSession` no vuelve nunca a false, y el ciclo del
    // flujo salía por un `return` temprano apenas valía true. O sea que cuando
    // Play Store creaba la sesión y después la DESCARTABA —falta de espacio es la
    // causa clásica, y es la que se midió en el CAT S22 Flip el 3/9— el flujo se
    // quedaba mostrando "Descargando..." para siempre: el freno por estancamiento
    // estaba más abajo en la función y no se alcanzaba nunca. Recién lo destrababa
    // el watchdog de 10 minutos, con un mensaje genérico.
    //
    // Con estas tres marcas el ciclo puede distinguir tres cosas que antes eran
    // una sola: "está bajando bien", "arrancó y se quedó sin avanzar" y "arrancó
    // y el sistema la abortó".
    // ──────────────────────────────────────────────

    /** `elapsedRealtime` de la última vez que el porcentaje cambió. 0 si nunca. */
    @Volatile var lastProgressAt = 0L
        private set

    /** `elapsedRealtime` del último `onFinished(success=false)`. 0 si no hubo. */
    @Volatile var sessionFailedAt = 0L
        private set

    /** `elapsedRealtime` de la primera sesión vista para el paquete. 0 si ninguna. */
    @Volatile var sawSessionAt = 0L
        private set

    private fun markSeen() {
        sawSession = true
        if (sawSessionAt == 0L) sawSessionAt = android.os.SystemClock.elapsedRealtime()
    }

    private var onProgressCb: ((Int) -> Unit)? = null
    private var onFinishedCb: ((Boolean) -> Unit)? = null

    private val callback = object : PackageInstaller.SessionCallback() {

        override fun onCreated(sessionId: Int) {
            if (matchesTarget(sessionId)) {
                markSeen()
                Log.i(TAG, "Sesión de instalación creada para ${targetPackage} (id=$sessionId)")
            }
        }

        override fun onBadgingChanged(sessionId: Int) {
            // El appPackageName de una sesión de Play Store a veces recién queda
            // resuelto acá, no en onCreated. Sin este re-chequeo se perdía la
            // asociación entre la sesión y el paquete que estamos actualizando.
            if (matchesTarget(sessionId)) markSeen()
        }

        override fun onActiveChanged(sessionId: Int, active: Boolean) {
            if (active && matchesTarget(sessionId)) markSeen()
        }

        override fun onProgressChanged(sessionId: Int, progress: Float) {
            if (!matchesTarget(sessionId)) return
            markSeen()
            val pct = (progress * 100f).toInt().coerceIn(0, 100)
            if (pct == lastProgress) return
            lastProgress = pct
            lastProgressAt = android.os.SystemClock.elapsedRealtime()
            val cb = onProgressCb ?: return
            mainHandler.post { cb(pct) }
        }

        override fun onFinished(sessionId: Int, success: Boolean) {
            if (!matchesTarget(sessionId)) return
            Log.i(TAG, "Sesión terminada para ${targetPackage}: success=$success")
            if (!success) sessionFailedAt = android.os.SystemClock.elapsedRealtime()
            val cb = onFinishedCb ?: return
            // OJO: success=false NO significa necesariamente que la actualización
            // falló. Play Store a veces crea y descarta sesiones intermedias, o
            // parte una descarga grande en dos. Quien reciba esto no debe cerrar
            // el flujo con un fracaso: tiene que confirmar contra el versionCode
            // del paquete. Solo success=true es concluyente.
            mainHandler.post { cb(success) }
        }
    }

    /**
     * Empieza a observar. Idempotente: si ya estaba observando otro paquete,
     * se reapunta al nuevo.
     */
    fun start(
        context: Context,
        packageName: String,
        onProgress: (Int) -> Unit,
        onFinished: (Boolean) -> Unit
    ) {
        val ctx = context.applicationContext
        targetPackage = packageName
        onProgressCb = onProgress
        onFinishedCb = onFinished
        sawSession = false
        lastProgress = -1
        lastProgressAt = 0L
        sessionFailedAt = 0L
        sawSessionAt = 0L

        val inst = ctx.packageManager.packageInstaller
        installer = inst

        if (registered) return
        try {
            inst.registerSessionCallback(callback, mainHandler)
            registered = true
            Log.i(TAG, "Observando sesiones de instalación para $packageName")
        } catch (e: Exception) {
            // Si el fabricante no lo soporta, el flujo sigue funcionando con las
            // otras señales (versionCode y ACTION_PACKAGE_REPLACED); solo pierde
            // el porcentaje en vivo.
            Log.w(TAG, "No se pudo registrar el SessionCallback: ${e.message}")
        }
    }

    fun stop(context: Context) {
        val ctx = context.applicationContext
        targetPackage = null
        onProgressCb = null
        onFinishedCb = null
        if (!registered) {
            installer = null
            return
        }
        val inst = installer ?: ctx.packageManager.packageInstaller
        try {
            inst.unregisterSessionCallback(callback)
        } catch (e: Exception) {
            Log.w(TAG, "No se pudo desregistrar el SessionCallback: ${e.message}")
        }
        installer = null
        registered = false
    }

    /**
     * Consulta directa, sin depender de que hayan llegado callbacks.
     * Se usa desde el tick del flujo como red de seguridad: en algunos equipos
     * los callbacks llegan tarde o no llegan, pero `getAllSessions()` sí muestra
     * la sesión activa.
     */
    fun hasActiveSessionFor(context: Context, packageName: String): Boolean {
        return try {
            context.applicationContext.packageManager.packageInstaller.allSessions.any {
                it.appPackageName == packageName
            }
        } catch (e: Exception) {
            false
        }
    }

    /** Progreso 0..100 leído directo de la sesión activa, o -1 si no hay ninguna. */
    fun currentProgressFor(context: Context, packageName: String): Int {
        return try {
            val session = context.applicationContext.packageManager.packageInstaller.allSessions
                .firstOrNull { it.appPackageName == packageName } ?: return -1
            (session.progress * 100f).toInt().coerceIn(0, 100)
        } catch (e: Exception) {
            -1
        }
    }
}
