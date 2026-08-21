package com.ejemplo.locksuite.util

import android.content.Context
import android.os.SystemClock
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
 *
 * ─────────────────────────────────────────────────────────────────────────────
 * ⚠️ ARREGLO DEL 21/8/2026 — EL PROXY SE QUEDABA CLAVADO (ver B.20)
 * ─────────────────────────────────────────────────────────────────────────────
 * Reporte del dueño: *"a veces se cae el internet cuando tengo LockSuite"*, y volvía
 * apagando y prendiendo la VPN. Un diagnóstico forense sobre el equipo real encontró
 * el proxy global puesto en `127.0.0.1:9999` con la red física perfecta (ping y
 * socket TCP a 443 funcionando por debajo del proxy).
 *
 * La causa de fondo era el crash de Arranque Directo de `LockSuiteApplication` (ver
 * la cabecera de ese archivo): el proceso moría antes de que arrancara el Watchdog,
 * así que **nadie corría el `tick()` que libera el bloqueo**. Pero la red de
 * seguridad de este archivo tampoco alcanzaba, por tres defectos propios:
 *
 *   1. **`release()` salía antes de tocar el proxy si `KEY_ACTIVE` ya estaba en
 *      false.** O sea: si la marca se perdía (proceso muerto entre el `apply()` y la
 *      llamada al DPM, excepción a mitad de `engage()`, ventana de un arranque
 *      anterior) el proxy quedaba puesto y NINGÚN camino del código lo volvía a
 *      mirar nunca. No había reconciliación: se recordaba lo que habíamos hecho en
 *      vez de preguntarle al sistema qué había.
 *   2. **`KEY_SINCE` guarda `SystemClock.elapsedRealtime()`, que vuelve a cero en
 *      cada arranque.** Si una ventana sobrevivía a un reinicio, `elapsed` daba
 *      NEGATIVO, `elapsed > techo` nunca se cumplía y **el techo de 120 s no vencía
 *      jamás**. Sin internet indefinidamente, con la ventana "abierta" para siempre.
 *   3. **`WatchdogWorker` (WorkManager, cada 15 min) no llamaba al `tick()`.** Era
 *      justo el único mecanismo que sobrevive a que el proceso muera, o sea el único
 *      que podría haber salvado el caso.
 *
 * Los tres están arreglados, y la regla que salió de la sesión del 18/8 —
 * **reconciliar, no recordar**— ahora se aplica también acá: `healStuckProxy()` le
 * pregunta al sistema si el proxy está puesto (`Settings.Global`) en vez de deducirlo
 * de una preferencia nuestra, y lo saca si no hay ninguna razón legítima para que
 * esté. Se la llama desde el arranque del proceso, desde `engage()`, desde el
 * `tick()` del Watchdog, desde el `WatchdogWorker` de 15 minutos y desde
 * `onFilterReady()`. Con eso el peor caso pasó de "sin internet hasta que alguien
 * abra la app y toque la VPN" a "sin internet 15 minutos como mucho, y se arregla
 * solo".
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

    /**
     * La misma clave que [KEY_ACTIVE], expuesta para que `AccessibilityEnforcer` pueda
     * combinar los dos motivos por los que las apps pueden tener que estar suspendidas
     * (el interruptor y esta ventana de arranque) en un solo lugar, en vez de que cada
     * uno actúe por su cuenta y se pisen.
     */
    const val KEY_ACTIVE_PUBLIC = KEY_ACTIVE

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

    /**
     * El proxy a puerto muerto que usa tanto este arranque protegido como el
     * interruptor "bloquear internet" (`PolicyManager.setInternetBlocked`).
     *
     * Están acá para poder RECONOCERLO en la configuración del sistema, no solo para
     * ponerlo. Es la diferencia entre "creo que lo saqué" y "el sistema dice que no
     * está".
     */
    private const val GATE_PROXY_HOST = "127.0.0.1"
    private const val GATE_PROXY_PORT = "9999"

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
    // RECONCILIACIÓN DEL PROXY (21/8/2026)
    // ──────────────────────────────────────────────────────────────────────────

    /**
     * ¿El sistema tiene puesto AHORA MISMO el proxy a puerto muerto?
     *
     * Se le pregunta a `Settings.Global`, que es lo que el sistema realmente aplica,
     * en vez de deducirlo de nuestras preferencias. Ese es el punto: la preferencia
     * puede mentir (proceso muerto a mitad de camino, ventana de otro arranque), el
     * ajuste del sistema no.
     *
     * Se miran las DOS formas en que Android guarda esto, porque cambian según la
     * versión y el fabricante: el par `global_http_proxy_host` / `global_http_proxy_port`
     * y la cadena `http_proxy` ("host:puerto"). En el equipo del dueño (Android 13) el
     * volcado mostró exactamente esto:
     *
     *     http_proxy=null
     *     global_http_proxy_host=127.0.0.1
     *     global_http_proxy_port=9999
     *
     * O sea que mirar solo `Settings.Global.HTTP_PROXY` —que es lo que parece la clave
     * "buena" por ser la constante pública— habría dado `false` justo en el caso real
     * que hay que detectar. Mirar las dos no cuesta nada y no depende de la versión.
     *
     * Costo: las lecturas de `Settings.Global` están cacheadas en el proceso por
     * `NameValueCache` con seguimiento de generación, así que después de la primera son
     * de memoria salvo que la tabla haya cambiado. Igual se ordenan para cortar cuanto
     * antes: en el caso normal (sin proxy) son dos lecturas.
     *
     * Ante cualquier fallo de lectura devuelve `false` a propósito: este valor solo
     * habilita a SACAR el proxy, nunca a ponerlo, así que ante la duda no se toca nada.
     */
    fun isGateProxyPresent(context: Context): Boolean = try {
        val cr = context.contentResolver
        val host = android.provider.Settings.Global
            .getString(cr, "global_http_proxy_host")?.trim()

        if (host == GATE_PROXY_HOST &&
            android.provider.Settings.Global.getString(cr, "global_http_proxy_port")?.trim() ==
            GATE_PROXY_PORT
        ) {
            true
        } else {
            android.provider.Settings.Global
                .getString(cr, android.provider.Settings.Global.HTTP_PROXY)?.trim() ==
                "$GATE_PROXY_HOST:$GATE_PROXY_PORT"
        }
    } catch (e: Exception) {
        false
    }

    /**
     * ¿Hay una ventana de arranque protegido abierta Y todavía en plazo?
     *
     * Es la única razón legítima por la que este módulo puede tener la red cerrada.
     * Ojo con el `elapsed < 0`: [KEY_SINCE] guarda `SystemClock.elapsedRealtime()`,
     * que se reinicia en cada arranque del equipo. Una ventana que sobrevivió a un
     * reinicio da un `elapsed` negativo — antes eso hacía que el techo no venciera
     * NUNCA y el equipo se quedaba sin internet para siempre. Un tiempo negativo no
     * es "recién empezada": es "de otro arranque", o sea vencida.
     */
    private fun isWindowLegitimatelyOpen(context: Context): Boolean {
        val prefs = PrefsHelper.getMdmPrefs(context)
        if (!prefs.getBoolean(KEY_ACTIVE, false)) return false
        val since = prefs.getLong(KEY_SINCE, 0L)
        if (since <= 0L) return false
        val elapsed = SystemClock.elapsedRealtime() - since
        if (elapsed < 0L) return false
        val techo = if (stillWaitingForAccessibility(context)) {
            MAX_ACCESSIBILITY_WINDOW_MS
        } else {
            MAX_WINDOW_MS
        }
        return elapsed <= techo
    }

    /**
     * Saca el proxy a puerto muerto si quedó puesto sin ninguna razón legítima.
     *
     * Esta es la red de seguridad de último recurso: es la única función del módulo
     * que NO confía en ninguna marca nuestra para decidir si hay algo que limpiar.
     * Mira el estado real del sistema y compara. Es idempotente y barata (una lectura
     * de `Settings.Global`; en el caso normal —que es el 99,99 % de las veces— sale
     * ahí mismo sin tocar nada más).
     *
     * NO toca el proxy si:
     *  - no está puesto el nuestro (puede haber un proxy corporativo real configurado);
     *  - el administrador tiene "bloquear internet" encendido a propósito;
     *  - hay una ventana de arranque protegido abierta y todavía en plazo.
     *
     * @return true si efectivamente hubo que limpiar un proxy huérfano.
     */
    fun healStuckProxy(context: Context, reason: String): Boolean {
        return try {
            if (!isGateProxyPresent(context)) return false

            val policy = PolicyManager(context)
            if (policy.isInternetBlocked()) return false
            if (isWindowLegitimatelyOpen(context)) return false

            android.util.Log.w(
                TAG,
                "Proxy huérfano detectado ($GATE_PROXY_HOST:$GATE_PROXY_PORT) sin ventana " +
                    "de arranque protegido válida. Liberando la red ($reason)."
            )
            policy.setNetworkGateBlocked(false)

            PrefsHelper.getMdmPrefs(context).edit()
                .putBoolean(KEY_ACTIVE, false)
                .putString(KEY_LAST_RESULT, "proxy huerfano liberado ($reason)")
                .apply()

            // Que no queden apps suspendidas por una ventana que ya no existe.
            try {
                AccessibilityEnforcer.reconcileNow(context)
            } catch (ignored: Exception) {
            }
            true
        } catch (e: Exception) {
            android.util.Log.w(TAG, "healStuckProxy: ${e.message}")
            false
        }
    }

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

            // ARREGLO 21/8/2026 — antes que nada, limpiar un proxy huérfano de un
            // arranque anterior. Va ACÁ ARRIBA, antes de todas las salidas tempranas
            // de abajo, justamente porque el caso peligroso es que alguna de ellas
            // corte: si `engage()` se va sin abrir ventana pero el arranque anterior
            // dejó el proxy puesto, no queda nadie mirando y el equipo arranca sin
            // internet. Ver B.20.
            healStuckProxy(context, "arranque")

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
                        // Se delega en el reconciliador en vez de suspender a mano: él
                        // sabe combinar este motivo con el del interruptor, y evita que
                        // los dos se pisen (uno suspendía y el otro liberaba en el ciclo
                        // siguiente — parte del vaivén que reportó el dueño).
                        AccessibilityEnforcer.reconcileNow(context)
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
            if (!isActive(context)) {
                // ARREGLO 21/8/2026 — el túnel está leyendo y no hay ninguna ventana
                // abierta: si quedó un proxy huérfano, este es el mejor momento posible
                // para sacarlo (el filtro ya está funcionando, no hay nada que esperar).
                //
                // Esto es, además, lo que el dueño venía haciendo A MANO para destrabar
                // el equipo: apagar y prender la VPN. Antes funcionaba solo cuando la
                // marca `KEY_ACTIVE` seguía puesta; si se había perdido, ni siquiera eso
                // servía. Ahora el camino manual funciona siempre — y encima el mismo
                // camino se dispara solo cada vez que la VPN se reestablece.
                healStuckProxy(context, "tunel leyendo, sin ventana abierta")
                return
            }
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
            if (!isActive(context)) {
                // ARREGLO 21/8/2026 — antes se salía acá y listo, y ese era el agujero:
                // con la ventana ya cerrada NADIE volvía a mirar el proxy nunca más. Si
                // se había perdido el paso de sacarlo (proceso muerto entre el `apply()`
                // y la llamada al DPM, excepción del DevicePolicyManager, ventana de un
                // arranque anterior), el equipo quedaba sin internet indefinidamente.
                // Confirmado en el equipo del dueño: los tres servicios vivos, el
                // Watchdog corriendo su ciclo… y el proxy puesto igual.
                //
                // Cuesta una lectura de Settings.Global cada 20 s, y en el caso normal
                // (sin proxy puesto) sale ahí mismo.
                healStuckProxy(context, "ciclo del Watchdog")
                return
            }

            val since = PrefsHelper.getMdmPrefs(context).getLong(KEY_SINCE, 0L)
            val elapsed = SystemClock.elapsedRealtime() - since
            val waitingAccessibility = stillWaitingForAccessibility(context)
            // Dos techos distintos porque son dos esperas distintas. Ver el comentario
            // de MAX_ACCESSIBILITY_WINDOW_MS.
            val techo = if (waitingAccessibility) MAX_ACCESSIBILITY_WINDOW_MS else MAX_WINDOW_MS

            // `elapsed < 0` significa que la ventana viene de OTRO arranque del equipo:
            // KEY_SINCE guarda `elapsedRealtime()`, que vuelve a cero al reiniciar. Antes
            // ese caso no entraba por ninguna rama —`elapsed > techo` es falso con un
            // número negativo— así que el techo no vencía NUNCA y el bloqueo quedaba
            // puesto para siempre. Ver B.20.
            if (since <= 0L || elapsed < 0L || elapsed > techo) {
                val motivo = when {
                    since <= 0L || elapsed < 0L -> "ventana de un arranque anterior"
                    waitingAccessibility -> "vencido sin que se activara la Accesibilidad"
                    else -> "vencido sin confirmar el filtro"
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

    /**
     * Reabre la red. Idempotente.
     *
     * ⚠️ ARREGLO 21/8/2026 — acá estaba el defecto más caro de todo el módulo.
     *
     * Antes la primera línea era `if (!prefs.getBoolean(KEY_ACTIVE, false)) return`:
     * o sea que si la marca ya figuraba apagada, esta función se iba **sin mirar
     * siquiera si el proxy seguía puesto**. Y hay varias formas de llegar a esa
     * combinación (marca apagada + proxy puesto): que el proceso muera entre el
     * `apply()` de abajo y la llamada al DevicePolicyManager, que el DPM lance, que
     * la ventana venga de otro arranque. En cualquiera de esos casos el equipo
     * quedaba sin internet y **ningún camino del código volvía a mirar nunca**.
     *
     * Ahora la salida rápida exige las dos cosas: ventana cerrada Y proxy ausente. La
     * salida rápida sigue siendo barata (una lectura de `Settings.Global`), y el caso
     * normal —que es "no hay nada puesto"— sale igual de rápido que antes.
     */
    fun release(context: Context, reason: String) {
        val prefs = PrefsHelper.getMdmPrefs(context)
        if (!prefs.getBoolean(KEY_ACTIVE, false) && !isGateProxyPresent(context)) return

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
            if (prefs.getBoolean(KEY_SUSPEND_APPS, false) &&
                !prefs.getBoolean("browsers_suspended_by_watchdog", false)
            ) {
                policy.setBrowsersSuspended(false)
            }
            // La suspensión de apps la resuelve el reconciliador: ya no hay una ventana
            // de arranque activa, así que si el interruptor tampoco la pide, las libera
            // él. Si el interruptor SÍ la pide (accesibilidad todavía apagada), las
            // mantiene. Un solo lugar decide, y por eso no pueden pelearse.
            AccessibilityEnforcer.reconcileNow(context)
        } catch (e: Exception) {
            android.util.Log.e(TAG, "Fallo liberando el arranque protegido: ${e.message}")
        }
        android.util.Log.i(TAG, "Arranque protegido LIBERADO ($reason).")
    }

    /**
     * ¿Está activo NUESTRO servicio de accesibilidad?
     *
     * Delega en `AccessibilityEnforcer` a propósito: es EL único lugar del proyecto que
     * responde esa pregunta. Antes esto leía por su cuenta la cadena
     * `Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES`, que no refleja el interruptor
     * maestro de accesibilidad y cambia en pasos durante una transición — o sea que el
     * arranque protegido y el Watchdog podían tener opiniones distintas sobre lo mismo
     * en el mismo instante. Una sola fuente de verdad.
     */
    fun isAccessibilityServiceActive(context: Context): Boolean =
        AccessibilityEnforcer.isServiceRunning(context)
}
