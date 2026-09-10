package com.ejemplo.locksuite.mdm

import android.content.Context
import android.os.SystemClock
import android.util.Log
import com.ejemplo.locksuite.util.PrefsHelper

/**
 * GracePeriodManager — PERÍODO DE GRACIA CON CIERRE AUTOMÁTICO (10/9/2026).
 *
 * ─────────────────────────────────────────────────────────────────────────────
 * QUÉ PIDIÓ EL DUEÑO, TEXTUAL
 * ─────────────────────────────────────────────────────────────────────────────
 *
 * *"tiene que haber un perfil de auto personalización, donde se bloqueará todo lo no
 * kosher, y lo que no es tan claro quedará a decisión del usuario por un tiempo que yo
 * especifique, y lo mismo la tienda de apps quedará abierta, y yo pondré que por ejemplo
 * después de 2 días se cierre todo"*.
 *
 * ─────────────────────────────────────────────────────────────────────────────
 * POR QUÉ ESTO VALE MUCHO MÁS QUE UN TEMPORIZADOR
 * ─────────────────────────────────────────────────────────────────────────────
 *
 * Lo interesante no es que se cierre solo a los dos días: es **qué pasa durante esos dos
 * días**. Con el modo lista blanca en SIMULACIÓN (B.53), el equipo va anotando cada
 * dominio que quedaría afuera y lo publica al panel agregado por dominio y cantidad. O
 * sea que el período de gracia **es el mecanismo que llena el catálogo**.
 *
 * Sin esto, pasar un equipo a estricto es adivinar qué apps y qué dominios necesita esa
 * persona — y B.53 ya dejó anotado que ninguna lista escrita de antemano puede estar
 * completa (los backends cambian sin aviso y varían por país). Con esto, se le da el
 * equipo, se lo usa normal dos días, y al cerrar se cierra **con la lista de lo que esa
 * persona realmente usó**.
 *
 * ─────────────────────────────────────────────────────────────────────────────
 * LOS TRES PROBLEMAS REALES, Y CÓMO SE RESUELVE CADA UNO
 * ─────────────────────────────────────────────────────────────────────────────
 *
 * **1. El reloj del equipo se puede atrasar, y entonces el vencimiento no llega nunca.**
 *
 * No es hipotético: este proyecto ya lo pagó dos veces. B.26 causa 3 — un equipo sin SIM
 * ni hora de red rechazaba TODOS los comandos para siempre porque el reloj estaba corrido.
 * Y B.29 — el latido no avanzaba en sueño profundo porque `Handler.postDelayed` cuenta con
 * `SystemClock.uptimeMillis()`.
 *
 * Acá hay **dos relojes y se cierra con el que primero venza**:
 *
 *   · **Reloj de pared** (`System.currentTimeMillis()`): cuenta aunque el equipo esté
 *     apagado, que es el caso normal. Lo puede mover el usuario.
 *   · **Acumulador de tiempo real** (`SystemClock.elapsedRealtime()`): avanza con el
 *     equipo encendido incluso en sueño profundo, y **no lo puede tocar nadie** — pero se
 *     reinicia en cada arranque, así que se acumula a mano en cada vuelta del Watchdog.
 *
 * Ninguno alcanza solo: el de pared se puede atrasar, y el acumulador no cuenta el tiempo
 * con el equipo apagado. Juntos, atrasar el reloj solo consigue que el cierre lo dispare
 * el otro. Y encima el perfil enciende `DISALLOW_CONFIG_DATE_TIME`, así que el usuario ni
 * siquiera puede tocar la hora — esa es la defensa de adelante; estos dos relojes son la
 * red de atrás.
 *
 * **2. Si el cierre no dispara, el equipo queda abierto para siempre.**
 *
 * Es literal el riesgo que B.11 dejó anotado para la suspensión: *"un equipo que quede
 * suspendido por olvido es un equipo sin ninguna protección — no hay expiración
 * automática"*. Acá el cierre lo fuerza el **`WatchdogWorker`**, que corre cada 15 minutos
 * y es **lo único del proyecto que sobrevive a que muera el proceso** (así lo describe el
 * mapa de archivos de la sección A). Se chequea además en el ciclo de 20 s del servicio de
 * primer plano y al arrancar el equipo, pero el Worker es el que garantiza que pase.
 *
 * **3. Al cerrar NO se pasa la lista blanca a "bloquear de verdad".**
 *
 * Sería tentador —el catálogo ya está lleno— y sería exactamente lo que B.53 pidió evitar:
 * ese paso *"va en UN equipo de prueba"*, con una persona mirando, después de revisar la
 * auditoría. El cierre aplica el perfil destino y deja la lista blanca donde estaba. Que
 * el catálogo esté completo es el REGALO del período de gracia; usarlo es una decisión.
 */
object GracePeriodManager {

    private const val TAG = "GracePeriod"

    /** Momento (reloj de pared) en que vence. 0 = no hay período activo. */
    private const val KEY_DEADLINE_WALL = "grace_deadline_wall"

    /** Cuánto dura en total, en ms. Se guarda para poder comparar contra el acumulador. */
    private const val KEY_DURATION_MS = "grace_duration_ms"

    /** Tiempo real acumulado con el equipo encendido, en ms. Nadie lo puede mover. */
    private const val KEY_ELAPSED_ACCUM = "grace_elapsed_accum"

    /** Valor de `elapsedRealtime()` en el último chequeo, para calcular el delta. */
    private const val KEY_LAST_REALTIME = "grace_last_realtime"

    /** Qué perfil se aplica al cerrar. */
    private const val KEY_TARGET_PROFILE = "grace_target_profile"

    /** Cuándo arrancó (reloj de pared). Solo informativo, para el panel. */
    private const val KEY_STARTED_AT = "grace_started_at"

    /** Por qué se cerró la última vez. Visibilidad, igual que B.46/B.50. */
    private const val KEY_LAST_CLOSE_REASON = "grace_last_close_reason"

    /**
     * Duraciones que ofrece el panel. Se cierran acá en vez de aceptar un número libre
     * porque un valor mal tipeado —"2" interpretado como 2 ms, o como 2 años— no da error
     * en ningún lado: simplemente el equipo se cierra al instante o no se cierra nunca.
     */
    val DURACIONES: List<Pair<String, Long>> = listOf(
        "2 horas" to 2L * 60 * 60 * 1000,
        "12 horas" to 12L * 60 * 60 * 1000,
        "1 día" to 24L * 60 * 60 * 1000,
        "2 días" to 2L * 24 * 60 * 60 * 1000,
        "3 días" to 3L * 24 * 60 * 60 * 1000,
        "1 semana" to 7L * 24 * 60 * 60 * 1000,
        "2 semanas" to 14L * 24 * 60 * 60 * 1000,
        "1 mes" to 30L * 24 * 60 * 60 * 1000
    )

    private fun prefs(context: Context) = PrefsHelper.getMdmPrefs(context)

    /** ¿Hay un período de gracia corriendo? */
    fun isActive(context: Context): Boolean =
        prefs(context).getLong(KEY_DEADLINE_WALL, 0L) > 0L

    /** Perfil que se va a aplicar al cerrar. */
    fun targetProfile(context: Context): String =
        prefs(context).getString(KEY_TARGET_PROFILE, EnrollmentProfiles.LEVEL_STRICT)
            ?: EnrollmentProfiles.LEVEL_STRICT

    fun startedAt(context: Context): Long = prefs(context).getLong(KEY_STARTED_AT, 0L)

    fun deadline(context: Context): Long = prefs(context).getLong(KEY_DEADLINE_WALL, 0L)

    fun lastCloseReason(context: Context): String =
        prefs(context).getString(KEY_LAST_CLOSE_REASON, "") ?: ""

    /**
     * Cuánto falta, en ms, según el reloj que esté MÁS ADELANTADO de los dos.
     *
     * Se devuelve el mínimo a propósito: si el usuario atrasó el reloj de pared, el que
     * manda pasa a ser el acumulador, y lo que se muestra tiene que ser el que va a
     * disparar de verdad. Mostrar el otro sería mentirle al administrador.
     */
    fun remainingMs(context: Context): Long {
        val p = prefs(context)
        val deadline = p.getLong(KEY_DEADLINE_WALL, 0L)
        if (deadline <= 0L) return 0L
        val porPared = deadline - System.currentTimeMillis()
        val duracion = p.getLong(KEY_DURATION_MS, 0L)
        val porAcumulador = duracion - p.getLong(KEY_ELAPSED_ACCUM, 0L)
        return maxOf(0L, minOf(porPared, porAcumulador))
    }

    /**
     * Arranca el período de gracia. Lo llama `PolicyManager.applyMasterProfile()` cuando el
     * perfil aplicado es el de gracia.
     *
     * @param durationMs cuánto dura.
     * @param targetProfile qué perfil se aplica al cerrar (por omisión, Kosher estricto).
     */
    fun start(
        context: Context,
        durationMs: Long,
        targetProfile: String = EnrollmentProfiles.LEVEL_STRICT
    ) {
        val ahora = System.currentTimeMillis()
        prefs(context).edit()
            .putLong(KEY_DEADLINE_WALL, ahora + durationMs)
            .putLong(KEY_DURATION_MS, durationMs)
            .putLong(KEY_ELAPSED_ACCUM, 0L)
            .putLong(KEY_LAST_REALTIME, SystemClock.elapsedRealtime())
            .putLong(KEY_STARTED_AT, ahora)
            .putString(KEY_TARGET_PROFILE, targetProfile)
            .remove(KEY_LAST_CLOSE_REASON)
            .apply()
        Log.i(TAG, "Período de gracia iniciado: ${durationMs / 60000} min, cierra con '$targetProfile'.")
    }

    /** Cancela el período sin aplicar el perfil destino. Lo usa el comando del panel. */
    fun cancel(context: Context, reason: String) {
        if (!isActive(context)) return
        clear(context, reason)
        Log.i(TAG, "Período de gracia cancelado: $reason")
    }

    private fun clear(context: Context, reason: String) {
        prefs(context).edit()
            .remove(KEY_DEADLINE_WALL)
            .remove(KEY_DURATION_MS)
            .remove(KEY_ELAPSED_ACCUM)
            .remove(KEY_LAST_REALTIME)
            .remove(KEY_STARTED_AT)
            .putString(KEY_LAST_CLOSE_REASON, reason)
            .apply()
    }

    /**
     * El corazón: avanza el acumulador y cierra si venció.
     *
     * **Es idempotente y barato**, porque lo llaman tres ciclos distintos: el Watchdog de
     * 15 min (el que garantiza que pase), el servicio de primer plano cada 20 s (para que
     * el cierre se sienta puntual) y `BootReceiver` al arrancar. Cuando no hay período
     * activo son dos lecturas de preferencias y nada más — es la misma disciplina que B.30
     * le impuso al ciclo de 20 s cuando se midió que gastaba 8.640 arranques de servicio
     * por día al pedo.
     *
     * @return `true` si en esta llamada se cerró el período.
     */
    fun checkAndHarden(context: Context): Boolean {
        val ctx = context.applicationContext
        val p = prefs(ctx)
        val deadline = p.getLong(KEY_DEADLINE_WALL, 0L)
        if (deadline <= 0L) return false

        // ── Avanzar el acumulador de tiempo real ──
        //
        // `elapsedRealtime()` se reinicia en cada arranque, así que un valor guardado
        // MAYOR que el actual significa "hubo un reinicio en el medio". En ese caso el
        // delta correcto es el valor actual entero (el tiempo desde que bootea), no la
        // resta, que daría negativo y haría retroceder el acumulador — o sea, le
        // regalaría tiempo al que reinicie el equipo a propósito.
        val realtimeAhora = SystemClock.elapsedRealtime()
        val realtimePrevio = p.getLong(KEY_LAST_REALTIME, realtimeAhora)
        val delta = if (realtimeAhora >= realtimePrevio) realtimeAhora - realtimePrevio else realtimeAhora
        val acumulado = p.getLong(KEY_ELAPSED_ACCUM, 0L) + maxOf(0L, delta)
        val duracion = p.getLong(KEY_DURATION_MS, 0L)

        p.edit()
            .putLong(KEY_ELAPSED_ACCUM, acumulado)
            .putLong(KEY_LAST_REALTIME, realtimeAhora)
            .apply()

        val vencioPorPared = System.currentTimeMillis() >= deadline
        val vencioPorAcumulador = duracion > 0L && acumulado >= duracion
        if (!vencioPorPared && !vencioPorAcumulador) return false

        // El motivo se guarda y se publica al panel. No es adorno: si un equipo cierra por
        // el acumulador y no por el reloj de pared, eso significa que ALGUIEN MOVIÓ LA
        // HORA, y es un dato que el administrador quiere ver. Misma filosofía que B.34
        // (cuando no se puede corregir, al menos que se vea) y que los contadores de
        // portal cautivo de B.46.
        val motivo = when {
            vencioPorPared && vencioPorAcumulador -> "vencido"
            vencioPorPared -> "vencido (reloj)"
            else -> "vencido (tiempo de uso; el reloj del equipo no llegó — puede estar atrasado)"
        }

        val destino = targetProfile(ctx)
        // Se limpia ANTES de aplicar, y el orden importa: el perfil destino pasa por
        // `applyMasterProfile()`, y si esa función viera el período todavía activo lo
        // volvería a arrancar. Quedaría un equipo que se "cierra" y vuelve a abrirse solo,
        // para siempre — el mismo vaivén que B.15 punto 3 costó una sesión entera.
        clear(ctx, motivo)

        val aplicado = try {
            PolicyManager(ctx).applyMasterProfile(destino)
        } catch (e: Exception) {
            Log.e(TAG, "No se pudo aplicar el perfil de cierre '$destino': ${e.message}", e)
            false
        }
        Log.i(TAG, "Período de gracia cerrado ($motivo). Perfil '$destino' aplicado: $aplicado")

        try {
            com.ejemplo.locksuite.util.FirebaseDeviceSync.syncDeviceInfo(ctx)
        } catch (e: Exception) {
            e.printStackTrace()
        }
        return true
    }
}
