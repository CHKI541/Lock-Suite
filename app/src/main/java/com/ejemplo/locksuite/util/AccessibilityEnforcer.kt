package com.ejemplo.locksuite.util

import android.accessibilityservice.AccessibilityServiceInfo
import android.content.Context
import android.os.SystemClock
import android.provider.Settings
import android.view.accessibility.AccessibilityManager
import com.ejemplo.locksuite.mdm.AppController
import com.ejemplo.locksuite.mdm.PolicyManager

/**
 * VIGILANCIA CONTINUA DEL SERVICIO DE ACCESIBILIDAD  (nuevo el 18/8/2026)
 *
 * ─────────────────────────────────────────────────────────────────────────────
 * POR QUÉ EXISTE ESTE ARCHIVO
 * ─────────────────────────────────────────────────────────────────────────────
 * Reporte del dueño tras probar la versión anterior: *"a veces las apps se suspenden y
 * a veces no, o se suspenden y vuelven a aparecer. Lo mismo con el aviso."*
 *
 * Que las apps Y el aviso fallaran **igual y al mismo tiempo** era la pista: no eran
 * dos bugs, era uno solo aguas arriba. Las dos cosas colgaban de la misma pregunta —
 * *¿está activa la accesibilidad?*— y esa pregunta se estaba respondiendo mal.
 *
 * Tres defectos que se juntaban:
 *
 *  1. **La detección leía `Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES` como texto.**
 *     Esa cadena es la lista de servicios *habilitados*, no de servicios *funcionando*:
 *     no refleja el interruptor maestro de accesibilidad, y durante una transición el
 *     sistema la reescribe en pasos intermedios. O sea que se leía "activa" cuando no
 *     lo estaba y al revés, varias veces seguidas. Ahora se le pregunta al
 *     `AccessibilityManager`, que es la vista real del sistema sobre qué servicios
 *     están corriendo, con nuestra propia instancia viva como confirmación.
 *
 *  2. **No había antirrebote.** Cualquier lectura intermedia disparaba la acción
 *     completa. Ahora una lectura tiene que repetirse durante `CONFIRM_MS` antes de
 *     que se le crea. Esto es lo que mata el vaivén: los estados de transición duran
 *     milisegundos y no llegan a confirmarse nunca.
 *
 *  3. **Se recordaba "ya lo apliqué" en una variable en memoria.** Si otro mecanismo
 *     des-suspendía las apps por su cuenta —`reapplyAllRestrictions()` del Watchdog de
 *     15 minutos, el flujo de actualización de Play Store, un comando del panel— nadie
 *     se enteraba: la variable seguía diciendo "aplicado" y las apps ya estaban
 *     abiertas otra vez. **Ese es, textual, "se suspenden y vuelven a aparecer".**
 *
 * ─────────────────────────────────────────────────────────────────────────────
 * CÓMO FUNCIONA AHORA: RECONCILIAR, NO RECORDAR
 * ─────────────────────────────────────────────────────────────────────────────
 * `reconcile()` no recuerda nada. Cada vez que corre:
 *
 *   1. Pregunta el estado REAL de la accesibilidad (con antirrebote).
 *   2. Calcula qué DEBERÍA pasar según los interruptores.
 *   3. Compara contra el estado REAL de las apps en el sistema y corrige **solo lo
 *      que esté distinto**.
 *
 * Es idempotente: llamarla mil veces seguidas da el mismo resultado que llamarla una.
 * Y se auto-repara: no importa quién haya des-suspendido una app ni cuándo, en el
 * próximo ciclo se vuelve a poner como corresponde.
 *
 * ─────────────────────────────────────────────────────────────────────────────
 * ¿Y LA BATERÍA?
 * ─────────────────────────────────────────────────────────────────────────────
 * El dueño preguntó explícitamente si vigilar todo el tiempo gasta batería. Casi nada,
 * porque el trabajo es asimétrico:
 *
 *  • **Con la accesibilidad ACTIVA (el 99,9 % del tiempo):** el ciclo es una sola
 *    consulta al `AccessibilityManager` y una comparación. No se enumeran apps, no se
 *    tocan preferencias, no se llama al DevicePolicyManager. Es despreciable, y corre
 *    en el ciclo de 20 s que el Watchdog ya hacía igual.
 *  • **Con la accesibilidad APAGADA:** el ciclo se acelera a 5 s y ahí sí se enumeran
 *    las apps. Pero ese es un estado de emergencia que dura lo que tarda el usuario en
 *    reactivarla, y en el que el equipo está deliberadamente inutilizable. Gastar un
 *    poco más ahí es exactamente lo que se quiere.
 *  • Además hay dos avisos instantáneos que no cuestan nada: el `ContentObserver` del
 *    Watchdog y el propio servicio de accesibilidad, que avisa al conectarse y al
 *    destruirse. O sea que el ciclo periódico es la red de seguridad, no el mecanismo
 *    principal.
 *
 * La enumeración de apps, además, es UNA sola llamada al sistema
 * (`getInstalledApplications`) que ya trae el estado de suspensión de cada app en sus
 * `flags`. No hay una consulta por app.
 */
object AccessibilityEnforcer {

    private const val TAG = "LockSuite_AccEnforce"

    /**
     * Cuánto tiene que sostenerse una lectura antes de creerle.
     *
     * Es el antirrebote que mata el vaivén. Activar o desactivar un servicio de
     * accesibilidad pasa por estados intermedios de decenas o cientos de milisegundos;
     * sin esto, cada uno de esos estados disparaba una suspensión o una des-suspensión
     * de todas las apps del equipo.
     */
    private const val CONFIRM_MS = 1_200L

    /** Clave que registra que la suspensión de emergencia está puesta por nosotros. */
    const val KEY_EMERGENCY_ACTIVE = "acc_emergency_suspend_active"

    enum class Verdict {
        /** No corresponde exigir nada (suspendido, protección apagada, sin PIN, en pausa). */
        NOT_APPLICABLE,
        /** La accesibilidad está funcionando. */
        OK,
        /** La accesibilidad está caída y corresponde reaccionar. */
        VIOLATED
    }

    // ── Antirrebote ──
    @Volatile private var rawReading: Boolean? = null
    @Volatile private var rawSince = 0L
    @Volatile private var confirmedReading: Boolean? = null

    /** Último veredicto entregado, para que el Watchdog sepa a qué ritmo mirar. */
    @Volatile var lastVerdict: Verdict = Verdict.NOT_APPLICABLE
        private set

    // ──────────────────────────────────────────────────────────────────────────

    /**
     * ¿Está REALMENTE corriendo nuestro servicio de accesibilidad?
     *
     * Orden deliberado:
     *
     *  1. `AccessibilityManager` — la vista del sistema sobre qué servicios están
     *     efectivamente activos. Cubre el interruptor maestro de accesibilidad, cosa
     *     que la cadena de `Settings.Secure` NO cubre: se puede estar "habilitado" en
     *     esa lista y no estar corriendo.
     *  2. Nuestra propia instancia viva, como señal positiva adicional.
     *  3. La cadena de `Settings.Secure`, solo si lo anterior falló con excepción.
     */
    fun isServiceRunning(context: Context): Boolean {
        try {
            val am = context.getSystemService(Context.ACCESSIBILITY_SERVICE) as? AccessibilityManager
            if (am != null) {
                if (!am.isEnabled) {
                    // Interruptor maestro apagado: ningún servicio corre, punto.
                    // Ni siquiera hace falta mirar la lista.
                    return com.ejemplo.locksuite.service.LockSuiteAccessibilityService.instance != null
                }
                val enabled = am.getEnabledAccessibilityServiceList(
                    AccessibilityServiceInfo.FEEDBACK_ALL_MASK
                )
                val mine = enabled?.any {
                    it?.resolveInfo?.serviceInfo?.packageName == context.packageName
                } ?: false
                if (mine) return true
                return com.ejemplo.locksuite.service.LockSuiteAccessibilityService.instance != null
            }
        } catch (e: Exception) {
            android.util.Log.w(TAG, "AccessibilityManager falló, usando la preferencia: ${e.message}")
        }

        // Último recurso.
        return try {
            val enabled = Settings.Secure.getString(
                context.contentResolver,
                Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES
            ) ?: ""
            enabled.contains("com.ejemplo.locksuite/.service.LockSuiteAccessibilityService") ||
                enabled.contains("com.ejemplo.locksuite/com.ejemplo.locksuite.service.LockSuiteAccessibilityService")
        } catch (e: Exception) {
            false
        }
    }

    /**
     * Lectura con antirrebote. Devuelve null mientras la lectura todavía no se sostuvo
     * lo suficiente: en ese caso NO hay que hacer nada, que es justamente lo contrario
     * de lo que hacía la versión anterior.
     */
    private fun stableReading(context: Context): Boolean? {
        val now = SystemClock.elapsedRealtime()
        val raw = isServiceRunning(context)

        if (rawReading != raw) {
            rawReading = raw
            rawSince = now
            // Cambió recién: todavía no se le cree.
            return confirmedReading
        }
        if (now - rawSince >= CONFIRM_MS) {
            if (confirmedReading != raw) {
                android.util.Log.i(TAG, "Estado de accesibilidad confirmado: ${if (raw) "ACTIVA" else "APAGADA"}")
            }
            confirmedReading = raw
        }
        return confirmedReading
    }

    /**
     * Evalúa la situación. Devuelve null si todavía no hay una lectura confirmada
     * (arranque, o transición en curso): en ese caso no se toca nada.
     */
    fun evaluate(context: Context): Verdict? {
        try {
            if (SystemClock.elapsedRealtime() <
                com.ejemplo.locksuite.service.WatchdogForegroundService.temporaryPauseUntil
            ) {
                lastVerdict = Verdict.NOT_APPLICABLE
                return Verdict.NOT_APPLICABLE
            }
            val policy = PolicyManager(context)
            if (policy.isLockSuiteSuspended() ||
                !policy.isAccessibilityProtectionEnabled() ||
                !com.ejemplo.locksuite.security.PinManager.isPinConfigured(context)
            ) {
                lastVerdict = Verdict.NOT_APPLICABLE
                return Verdict.NOT_APPLICABLE
            }

            val stable = stableReading(context) ?: return null
            val verdict = if (stable) Verdict.OK else Verdict.VIOLATED
            lastVerdict = verdict
            return verdict
        } catch (e: Exception) {
            android.util.Log.w(TAG, "evaluate: ${e.message}")
            return null
        }
    }

    /**
     * Corrige el estado de las apps para que coincida con lo que corresponde.
     *
     * No recibe "aplicá" ni "sacá": recibe la situación y ella decide, mirando el
     * estado real del sistema. Por eso se auto-repara si otro mecanismo pisó lo que
     * habíamos hecho.
     */
    fun reconcileApps(context: Context, verdict: Verdict) {
        try {
            val prefs = PrefsHelper.getMdmPrefs(context)
            val quiereSuspender = shouldSuspendApps(context, verdict)
            val estabaPuesta = prefs.getBoolean(KEY_EMERGENCY_ACTIVE, false)

            // Si no corresponde suspender Y nunca lo hicimos, no hay nada que revisar:
            // este es el camino del 99,9 % del tiempo y no toca el sistema para nada.
            if (!quiereSuspender && !estabaPuesta) return

            val cambios = AppController(context).reconcileEmergencySuspend(quiereSuspender)
            if (estabaPuesta != quiereSuspender) {
                prefs.edit().putBoolean(KEY_EMERGENCY_ACTIVE, quiereSuspender).apply()
            }
            if (cambios > 0) {
                android.util.Log.w(
                    TAG,
                    "Reconciliación de apps: ${if (quiereSuspender) "suspendidas" else "liberadas"} $cambios app(s)"
                )
            }
        } catch (e: Exception) {
            android.util.Log.w(TAG, "reconcileApps: ${e.message}")
        }
    }

    /**
     * ¿Corresponde tener las apps suspendidas ahora mismo?
     *
     * Hay DOS motivos posibles y este es el único lugar donde se combinan. Antes cada
     * uno actuaba por su cuenta —el Watchdog por el interruptor y el Arranque Protegido
     * por el suyo— y se pisaban: uno suspendía y el otro liberaba en el ciclo
     * siguiente. Es otra cara del mismo vaivén.
     */
    private fun shouldSuspendApps(context: Context, verdict: Verdict): Boolean {
        val prefs = PrefsHelper.getMdmPrefs(context)

        // Motivo 1: el interruptor "Suspender TODAS las apps mientras esté apagada".
        val porInterruptor = verdict == Verdict.VIOLATED &&
            prefs.getBoolean(
                com.ejemplo.locksuite.service.WatchdogForegroundService.KEY_ACC_SUSPEND_ALL,
                false
            )

        // Motivo 2: el Arranque Protegido está esperando a que se active la
        // accesibilidad. Mientras dure esa espera el equipo tiene que quedar cerrado.
        // Se exige VIOLATED, no "distinto de OK": con NOT_APPLICABLE (LockSuite
        // suspendido, protección apagada, sin PIN) no corresponde suspender nada, y
        // dejar pasar ese caso habría suspendido el equipo justo cuando el
        // administrador acaba de liberarlo a propósito.
        val porArranque = verdict == Verdict.VIOLATED &&
            prefs.getBoolean(BootGate.KEY_ACTIVE_PUBLIC, false) &&
            prefs.getBoolean(BootGate.KEY_WAIT_ACCESSIBILITY, false)

        return porInterruptor || porArranque
    }

    /**
     * Reconciliación inmediata sin pasar por el antirrebote. La usan los avisos que son
     * certeros por naturaleza: el propio servicio de accesibilidad al conectarse o
     * destruirse, y el Arranque Protegido al cerrarse o liberarse.
     */
    fun reconcileNow(context: Context) {
        val verdict = try {
            val policy = PolicyManager(context)
            when {
                policy.isLockSuiteSuspended() ||
                    !policy.isAccessibilityProtectionEnabled() ||
                    !com.ejemplo.locksuite.security.PinManager.isPinConfigured(context) -> Verdict.NOT_APPLICABLE
                isServiceRunning(context) -> Verdict.OK
                else -> Verdict.VIOLATED
            }
        } catch (e: Exception) {
            return
        }
        // El antirrebote solo se "pisa" cuando la lectura dice algo del servicio. Con
        // NOT_APPLICABLE no hay lectura que guardar: la protección está desactivada, no
        // es que la accesibilidad esté caída.
        if (verdict != Verdict.NOT_APPLICABLE) {
            confirmedReading = verdict == Verdict.OK
            rawReading = confirmedReading
            rawSince = SystemClock.elapsedRealtime()
        }
        lastVerdict = verdict
        reconcileApps(context, verdict)
    }

    /** Reinicia el antirrebote. Útil cuando algo avisa de un cambio con certeza. */
    fun resetDebounce() {
        rawReading = null
        rawSince = 0L
    }
}
