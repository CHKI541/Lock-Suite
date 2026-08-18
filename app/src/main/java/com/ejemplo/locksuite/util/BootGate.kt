package com.ejemplo.locksuite.util

import android.content.Context
import android.os.SystemClock
import android.provider.Settings
import com.ejemplo.locksuite.mdm.PolicyManager
import com.ejemplo.locksuite.receiver.BootReceiver

/**
 * ARRANQUE PROTEGIDO — cierra el hueco entre que el equipo enciende y que el filtro
 * está realmente funcionando.  (nuevo el 17/8/2026)
 *
 * ─────────────────────────────────────────────────────────────────────────────
 * EL PROBLEMA, EN CRIOLLO
 * ─────────────────────────────────────────────────────────────────────────────
 * Las restricciones de red de LockSuite (bloqueo de anuncios, de WebView por app,
 * de dominios, de internet por app) no las aplica Android: las aplica la VPN de
 * filtrado de esta app. Y esa VPN es un servicio como cualquier otro: al prender el
 * teléfono, Android primero arranca el sistema, después reparte el broadcast de
 * arranque, después nuestro servicio pide la interfaz de VPN, después la establece.
 * Eso son varios segundos —más en un equipo lento, más todavía si el sistema decide
 * postergar servicios— y en TODO ese rato el equipo tiene internet sin filtrar. Es
 * exactamente el agujero que reportó el dueño: "al reiniciar tarda en activarse y
 * mientras queda todo abierto".
 *
 * ─────────────────────────────────────────────────────────────────────────────
 * POR QUÉ NO SE ARREGLA CON `lockdown=true`
 * ─────────────────────────────────────────────────────────────────────────────
 * Android trae exactamente la herramienta para esto: `setAlwaysOnVpnPackage(...,
 * lockdown = true)` hace que el SISTEMA no deje pasar un solo paquete hasta que la
 * VPN esté conectada. Es gratis en batería porque lo hace el kernel. Pero exige que
 * la VPN se haga cargo de TODO el tráfico, y esta VPN es un túnel dividido que solo
 * enruta DNS: con lockdown, todo lo que no sea DNS deja de tener por dónde salir y
 * el equipo se queda sin internet para siempre, no unos segundos. Ya se probó dos
 * veces (ver walkthrough v0.4.3 y el comentario en `PolicyManager.setVpnConfigBlocked`).
 * Usarlo requiere reescribir la VPN como túnel completo: mucha más batería, mucha
 * más CPU y mucha más superficie de bugs. Es el punto B.4, y sigue sin decidirse.
 *
 * ─────────────────────────────────────────────────────────────────────────────
 * LO QUE HACE ESTO EN CAMBIO
 * ─────────────────────────────────────────────────────────────────────────────
 * Reutiliza el mecanismo que la app YA tiene para "bloquear todo internet": un proxy
 * global apuntado a un puerto muerto (127.0.0.1:9999). Es una sola llamada al
 * DevicePolicyManager, instantánea, sin ningún costo de batería mientras está puesta
 * (no hay nada corriendo: es una preferencia del sistema).
 *
 *   1. Al arrancar, el `BootReceiver` llama a `engage()` ANTES que cualquier otra
 *      cosa. El proxy queda puesto en milisegundos.
 *   2. Cuando el bucle de lectura del túnel arranca de verdad —no cuando el servicio
 *      arranca: cuando ya está leyendo paquetes— la VPN llama a `onFilterReady()`.
 *   3. `onFilterReady()` saca el proxy y el equipo queda con internet, ya filtrado.
 *
 * ─────────────────────────────────────────────────────────────────────────────
 * LO QUE NO HACE — DECIRLO CLARO
 * ─────────────────────────────────────────────────────────────────────────────
 * `setRecommendedGlobalProxy` es un proxy RECOMENDADO. Lo respetan las bibliotecas
 * HTTP normales de Android (WebView, Chrome, OkHttp, HttpURLConnection), o sea la
 * enorme mayoría de las apps. NO lo respeta una app que abra un socket crudo o que
 * use QUIC ignorando la configuración del sistema. O sea: cierra la puerta de adelante
 * en los primeros segundos, no es un firewall.
 *
 * Para hacerlo hermético habría que suspender también todas las apps durante esa
 * ventana (`AppController.setPackagesSuspended`) — es instantáneo y también gratis en
 * batería, y está escrito y listo detrás del interruptor `boot_gate_suspend_apps`,
 * apagado por defecto porque es más invasivo.
 *
 * ─────────────────────────────────────────────────────────────────────────────
 * RED DE SEGURIDAD
 * ─────────────────────────────────────────────────────────────────────────────
 * Si la VPN nunca levanta (permiso revocado, error del sistema), el equipo NO puede
 * quedarse sin internet para siempre: pasados `MAX_WINDOW_MS` el Watchdog libera el
 * bloqueo igual y lo deja anotado. Un equipo sin internet y sin forma de recibir un
 * comando del panel es un equipo que hay que ir a buscar a mano.
 */
object BootGate {

    private const val TAG = "LockSuite_BootGate"

    /** Interruptor del administrador. Encendido por defecto. */
    const val KEY_ENABLED = "boot_gate_enabled"

    /** Además del filtro de red, esperar a que la Accesibilidad esté activa. */
    const val KEY_WAIT_ACCESSIBILITY = "boot_gate_wait_accessibility"

    /** Endurecimiento opcional: suspender las apps durante la ventana. */
    const val KEY_SUSPEND_APPS = "boot_gate_suspend_apps"

    /** Estamos dentro de la ventana de arranque protegido. */
    private const val KEY_ACTIVE = "boot_gate_active"

    /** Momento (elapsedRealtime) en que se activó la ventana. */
    private const val KEY_SINCE = "boot_gate_since"

    /** El túnel de filtrado ya confirmó que está leyendo paquetes en esta ventana. */
    private const val KEY_FILTER_READY = "boot_gate_filter_ready"

    /** Último resultado, para mostrarlo en el panel. */
    const val KEY_LAST_RESULT = "boot_gate_last_result"

    /**
     * Techo duro de la ventana cuando se espera al FILTRO DE RED. Pasado esto se libera
     * aunque el filtro no haya levantado: es preferible un equipo sin filtrar unos
     * minutos a un equipo sin internet y sin forma de recibir un comando de rescate.
     *
     * Levantar el túnel VPN es cuestión de segundos; dos minutos es holgadísimo.
     */
    private const val MAX_WINDOW_MS = 120_000L

    /**
     * Techo cuando se espera a la ACCESIBILIDAD (`boot_gate_wait_accessibility`).
     *
     * ⚠️ ARREGLO DEL 18/8/2026 — antes se usaba el mismo techo de 2 minutos para las
     * dos cosas, y estaba mal: son esperas de naturaleza distinta. Esperar al filtro es
     * esperar a que arranque un servicio (segundos). Esperar a la accesibilidad es
     * esperar a que UNA PERSONA vaya a Ajustes y la active, que puede tardar lo que
     * tarde. Con 2 minutos, quien probaba reiniciaba el equipo, se demoraba un poco en
     * mirar, y para cuando miraba el bloqueo ya se había liberado solo: parecía que el
     * interruptor no hacía nada. Reporte textual del dueño: *"no funciona"*.
     *
     * Sigue habiendo techo, y a propósito: un equipo sin internet no puede recibir un
     * comando de rescate del panel. 30 minutos es tiempo de sobra para activarla y
     * poco para quedarse tirado.
     */
    private const val MAX_ACCESSIBILITY_WINDOW_MS = 30 * 60 * 1000L

    fun isEnabled(context: Context): Boolean =
        PrefsHelper.getMdmPrefs(context).getBoolean(KEY_ENABLED, true)

    fun setEnabled(context: Context, enabled: Boolean) {
        PrefsHelper.getMdmPrefs(context).edit().putBoolean(KEY_ENABLED, enabled).apply()
        if (!enabled) release(context, "interruptor apagado")
    }

    fun isActive(context: Context): Boolean =
        PrefsHelper.getMdmPrefs(context).getBoolean(KEY_ACTIVE, false)

    fun lastResult(context: Context): String =
        PrefsHelper.getMdmPrefs(context).getString(KEY_LAST_RESULT, "") ?: ""

    // ──────────────────────────────────────────────────────────────────────────

    /**
     * Cierra la red preventivamente. La llama `BootReceiver` lo más temprano posible.
     * Es idempotente y barata: si no corresponde, sale enseguida.
     */
    fun engage(context: Context) {
        try {
            // En Arranque Directo (LOCKED_BOOT_COMPLETED, antes de que el usuario
            // desbloquee por primera vez) el almacenamiento cifrado por credencial
            // todavía no está montado, así que las preferencias no se pueden leer y
            // cualquier intento lanza excepción. Tampoco hace falta: hasta ese momento
            // las apps de usuario ni siquiera pueden arrancar. El BootReceiver recibe
            // los dos broadcasts, así que el bloqueo se aplica en BOOT_COMPLETED.
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.N) {
                val um = context.getSystemService(android.os.UserManager::class.java)
                if (um != null && !um.isUserUnlocked) return
            }

            val policy = PolicyManager(context)

            // Con LockSuite suspendido el administrador levantó todo a propósito.
            if (policy.isLockSuiteSuspended()) return
            if (!isEnabled(context)) return

            // El administrador ya tenía "bloquear internet" puesto a mano: no hay nada
            // que agregar y, sobre todo, no hay que tocarlo al liberar.
            if (policy.isInternetBlocked()) return

            // Si ninguna política necesita la VPN, no hay filtro que esperar.
            val waitAccessibility = PrefsHelper.getMdmPrefs(context)
                .getBoolean(KEY_WAIT_ACCESSIBILITY, false)
            if (!BootReceiver.shouldVpnBeRunning(context) && !waitAccessibility) return

            val prefs = PrefsHelper.getMdmPrefs(context)
            prefs.edit()
                .putBoolean(KEY_ACTIVE, true)
                .putLong(KEY_SINCE, SystemClock.elapsedRealtime())
                .putBoolean(KEY_FILTER_READY, false)   // ventana nueva, marca en cero
                .putString(KEY_LAST_RESULT, "en curso")
                .apply()

            policy.setNetworkGateBlocked(true)

            // ⚠️ CAMBIO DEL 18/8/2026 — por qué acá se suspenden apps.
            //
            // El proxy global solo, cuando lo que se espera es la ACCESIBILIDAD, no se
            // nota: el usuario reinicia, abre WhatsApp, y WhatsApp abre igual (el proxy
            // frena tráfico HTTP de las bibliotecas estándar, no impide que una app
            // arranque ni cubre sockets crudos). Desde afuera, el interruptor "esperar
            // también a la Accesibilidad" parecía no hacer nada — que es justo lo que
            // reportó el dueño.
            //
            // Cuando lo que se espera es que UNA PERSONA vaya a activar la accesibilidad,
            // el bloqueo tiene que ser algo que se vea. Suspender las apps es
            // instantáneo, gratis en batería (lo hace el sistema, no nosotros) y
            // totalmente reversible.
            //
            // Para la espera del filtro de red, en cambio, se mantiene solo el proxy:
            // dura segundos y el dueño eligió explícitamente el 17/8 que alcanzaba.
            val suspendApps = prefs.getBoolean(KEY_SUSPEND_APPS, false) || waitAccessibility
            if (suspendApps) {
                try {
                    policy.setBrowsersSuspended(true)
                    if (waitAccessibility) {
                        com.ejemplo.locksuite.mdm.AppController(context).setEmergencySuspendAll(true)
                        prefs.edit().putBoolean("acc_emergency_suspend_active", true).apply()
                    }
                } catch (e: Exception) {
                    android.util.Log.w(TAG, "No se pudieron suspender apps en el arranque: ${e.message}")
                }
            }

            android.util.Log.i(
                TAG,
                "Arranque protegido ACTIVO (esperaFiltro=${BootReceiver.shouldVpnBeRunning(context)} " +
                    "esperaAccesibilidad=$waitAccessibility)."
            )
        } catch (e: Exception) {
            android.util.Log.e(TAG, "Fallo activando el arranque protegido: ${e.message}")
            // Ante cualquier duda, no dejar el equipo cerrado.
            try { release(context, "error al activar") } catch (ignored: Exception) { }
        }
    }

    /**
     * La llama `KosherVpnService` cuando el bucle de lectura del túnel ya está leyendo.
     * Es el único momento en que se puede afirmar que el filtro está funcionando: que
     * el servicio esté "arrancado" no alcanza, `establish()` puede haber devuelto null.
     */
    fun onFilterReady(context: Context) {
        try {
            if (!isActive(context)) return
            // Se ANOTA que el filtro ya levantó, no solo se libera.
            //
            // Sin esta marca había un agujero real: con `boot_gate_wait_accessibility`
            // encendido y la VPN haciendo falta, el filtro levantaba, esta función
            // salía sin liberar (falta la accesibilidad), y cuando después el usuario
            // activaba la accesibilidad el `tick()` no tenía forma de saber que el
            // filtro ya estaba listo. Terminaba liberando por vencimiento y con el
            // mensaje equivocado ("vencido sin confirmar el filtro"), varios segundos
            // tarde y mintiéndole al panel.
            PrefsHelper.getMdmPrefs(context).edit().putBoolean(KEY_FILTER_READY, true).apply()

            if (stillWaitingForAccessibility(context)) {
                android.util.Log.i(TAG, "Filtro de red listo, pero falta la Accesibilidad: se mantiene cerrado.")
                return
            }
            release(context, "filtro listo")
        } catch (e: Exception) {
            android.util.Log.w(TAG, "onFilterReady: ${e.message}")
        }
    }

    /**
     * La llama el Watchdog en cada ciclo (cada 20 s). Cubre dos casos que
     * `onFilterReady()` no puede cubrir solo: que la Accesibilidad se active DESPUÉS
     * del filtro, y que el filtro no levante nunca (vence la ventana).
     */
    fun tick(context: Context) {
        try {
            if (!isActive(context)) return

            val since = PrefsHelper.getMdmPrefs(context).getLong(KEY_SINCE, 0L)
            val elapsed = SystemClock.elapsedRealtime() - since
            val waitingAccessibility = stillWaitingForAccessibility(context)
            // Dos techos distintos porque son dos esperas distintas. Ver el comentario
            // de MAX_ACCESSIBILITY_WINDOW_MS.
            val techo = if (waitingAccessibility) MAX_ACCESSIBILITY_WINDOW_MS else MAX_WINDOW_MS

            if (since <= 0L || elapsed > techo) {
                val motivo = if (waitingAccessibility) {
                    "vencido sin que se activara la Accesibilidad"
                } else {
                    "vencido sin confirmar el filtro"
                }
                android.util.Log.w(TAG, "Venció la ventana del arranque protegido ($motivo); liberando.")
                release(context, motivo)
                return
            }

            if (waitingAccessibility) return

            // Llegados acá, la accesibilidad ya no es un impedimento. Se libera si el
            // filtro de red no hace falta, o si ya confirmó que está funcionando.
            val filterReady = PrefsHelper.getMdmPrefs(context).getBoolean(KEY_FILTER_READY, false)
            if (!BootReceiver.shouldVpnBeRunning(context)) {
                release(context, "sin filtro de red pendiente")
            } else if (filterReady) {
                release(context, "filtro listo")
            }
        } catch (e: Exception) {
            android.util.Log.w(TAG, "tick: ${e.message}")
        }
    }

    private fun stillWaitingForAccessibility(context: Context): Boolean {
        val prefs = PrefsHelper.getMdmPrefs(context)
        if (!prefs.getBoolean(KEY_WAIT_ACCESSIBILITY, false)) return false
        return !isAccessibilityServiceActive(context)
    }

    /** Reabre la red. Idempotente. */
    fun release(context: Context, reason: String) {
        val prefs = PrefsHelper.getMdmPrefs(context)
        if (!prefs.getBoolean(KEY_ACTIVE, false)) return

        prefs.edit()
            .putBoolean(KEY_ACTIVE, false)
            .putString(KEY_LAST_RESULT, reason)
            .apply()

        try {
            val policy = PolicyManager(context)
            // No pisar un bloqueo de internet puesto por el administrador a propósito.
            if (!policy.isInternetBlocked()) {
                policy.setNetworkGateBlocked(false)
            }
            // Levantar la suspensión de emergencia si la puso el arranque protegido.
            // Se consulta la preferencia, no un campo en memoria: quien libera puede ser
            // un proceso distinto del que suspendió (el Watchdog tras un reinicio).
            // Si el interruptor "suspender TODAS las apps" está encendido y la
            // accesibilidad sigue apagada, el Watchdog la va a volver a poner en el
            // próximo ciclo: no tiene sentido levantarla acá y que parpadee.
            val watchdogLaSostiene = prefs.getBoolean(
                com.ejemplo.locksuite.service.WatchdogForegroundService.KEY_ACC_SUSPEND_ALL, false
            ) && !isAccessibilityServiceActive(context)

            if (prefs.getBoolean("acc_emergency_suspend_active", false) && !watchdogLaSostiene) {
                com.ejemplo.locksuite.mdm.AppController(context).setEmergencySuspendAll(false)
                prefs.edit().putBoolean("acc_emergency_suspend_active", false).apply()
            }
            if (prefs.getBoolean(KEY_SUSPEND_APPS, false) &&
                !prefs.getBoolean("browsers_suspended_by_watchdog", false)
            ) {
                policy.setBrowsersSuspended(false)
            }
        } catch (e: Exception) {
            android.util.Log.e(TAG, "Fallo liberando el arranque protegido: ${e.message}")
        }
        android.util.Log.i(TAG, "Arranque protegido LIBERADO ($reason).")
    }

    /**
     * ¿Está activo NUESTRO servicio de accesibilidad? Se lee de la misma preferencia
     * segura del sistema que mira el Watchdog, para que las dos cosas no puedan
     * desincronizarse.
     */
    fun isAccessibilityServiceActive(context: Context): Boolean {
        return try {
            val enabled = Settings.Secure.getString(
                context.contentResolver,
                Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES
            ) ?: ""
            val shortId = "com.ejemplo.locksuite/.service.LockSuiteAccessibilityService"
            val longId = "com.ejemplo.locksuite/com.ejemplo.locksuite.service.LockSuiteAccessibilityService"
            enabled.contains(shortId) || enabled.contains(longId)
        } catch (e: Exception) {
            false
        }
    }
}
