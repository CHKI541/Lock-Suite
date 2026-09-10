package com.ejemplo.locksuite.mdm

import android.content.Context
import com.ejemplo.locksuite.dns.DomainRuleTrie
import com.ejemplo.locksuite.dns.RuleType
import com.ejemplo.locksuite.dns.normalizeDomain
import com.ejemplo.locksuite.util.PrefsHelper
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.atomic.AtomicReference

/**
 * MODO LISTA BLANCA (8/9/2026).
 *
 * Pedido del dueño: *"una opción de filtro estricto con lista blanca y permitir nada más
 * apps específicas con todos sus dominios y subdominios que sean kosher (…). En caso de
 * permitir una app, que se permita también la descarga del paquete de mi tienda y sus
 * dominios. En caso de que prohíba una app, si está en el dispositivo se bloqueará (y
 * ocultará), también se bloqueará descargarla de la tienda, y se bloquearán sus dominios.
 * Todo eso con un solo toque."*
 *
 * ─────────────────────────────────────────────────────────────────────────────
 * LAS TRES DECISIONES DE DISEÑO QUE HAY QUE CONOCER ANTES DE TOCAR ESTO
 * ─────────────────────────────────────────────────────────────────────────────
 *
 * **1. La lista blanca es GLOBAL por dominio, no por app.** Y no es una simplificación:
 *    es la única forma que funciona. Las consultas DNS de Android salen por `netd` en
 *    nombre de la app, así que el UID del socket es el del sistema y el filtro NO puede
 *    saber qué app preguntó (B.10, y B.52 midió que por eso la rama "por app" casi nunca
 *    corre). Una lista blanca implementada "por app" sería código muerto. Lo que se hace
 *    entonces es la unión: los dominios de todas las apps permitidas forman UN conjunto,
 *    y lo que no está en ese conjunto no resuelve para nadie.
 *
 * **2. De ahí sale, gratis, la respuesta a "las apps sin marcar no tienen internet".**
 *    No hace falta bloquearlas una por una ni ocultarlas: si sus dominios no están en la
 *    lista, no resuelven, y la app queda sin conexión sola. **Y hay que decir el límite
 *    con todas las letras: una app que hable directo a una IP, o que use DNS sobre HTTPS,
 *    esquiva esto** — es la limitación conocida de la Capa 2 entera (B.4), no algo propio
 *    del modo lista blanca.
 *
 * **3. Arranca en SIMULACIÓN y eso no es timidez.** Ninguna lista de dominios escrita de
 *    antemano puede estar completa (ver el comentario de `WhitelistCatalog`). En modo
 *    simulación no se bloquea nada: se anota qué se HABRÍA bloqueado y se publica al
 *    panel. Con eso el dueño completa las listas mirando datos reales del equipo en vez
 *    de adivinar, y recién ahí pasa a ESTRICTO. Pasar a estricto sin haber simulado es
 *    la forma más rápida de dejar un celular a medio funcionar sin saber por qué.
 *
 * ─────────────────────────────────────────────────────────────────────────────
 * ORDEN DE PRECEDENCIA (el mismo que aplica KosherVpnService.handleDnsQuery)
 * ─────────────────────────────────────────────────────────────────────────────
 *
 *   1. FORCE_BLOCK / FORCE_ALLOW de la sección DNS  ← le ganan a TODO, siempre.
 *   2. BLOCK de la lista blanca                     ← incluso con el modo apagado.
 *   3. ALLOW de la lista blanca / infraestructura   ← solo con el modo encendido.
 *   4. Con el modo encendido: lo que no matcheó, se bloquea.
 *   5. Con el modo apagado: sigue la cadena de siempre (adblock, GIF, cuenta Google…).
 *
 * El punto 1 es literal el pedido *"si yo quiero permitir o prohibir específicamente un
 * dominio lo pueda hacer desde la sección dns en forzar prohibir/permitir"*: esas dos
 * reglas se resuelven ANTES que esto y por eso siguen siendo la palabra final.
 *
 * El punto 2 con el modo apagado es lo que hace que "prohibir una app" sirva por sí solo:
 * se le cierran los dominios aunque el filtro estricto no esté activo.
 */
class WhitelistManager(private val context: Context) {

    companion object {
        const val KEY_ENABLED = "whitelist_mode_enabled"
        const val KEY_SIMULATION = "whitelist_mode_simulation"
        const val KEY_ALLOW_SHARED_CDN = "whitelist_allow_shared_cdn"
        /** JSON: {"pkg": "allow"|"block"} — decisión GLOBAL por app (catálogo de la flota). */
        const val KEY_DECISIONS = "whitelist_decisions"

        /**
         * JSON: {"pkg": "allow"|"block"|"unset"} — decisión PROPIA DE ESTE EQUIPO, que le
         * gana a la global.
         *
         * ★ 10/9/2026. B.53 dejó anotado como pendiente que *"el catálogo se aplica igual
         * a todos los equipos; si algún día hace falta una app permitida en un celular y
         * no en otro, el lugar natural es un `devices/<id>/whitelistOverrides` que se lea
         * después del global"*. Esto es eso, y resultó ser lo que hacía falta para que la
         * ficha de un celular pueda ser una pantalla de verdad: sin overrides, todo lo
         * que el administrador tocara en la ficha de UN equipo se lo aplicaba a la flota
         * entera, que es la sorpresa más cara que puede dar un panel de MDM.
         *
         * `unset` se guarda EXPLÍCITO y no se borra la clave: "en este equipo esta app no
         * está marcada" es una decisión distinta de "en este equipo no dije nada", porque
         * la primera tiene que poder anular un `allow` global. Es el mismo cuidado que
         * `EnrollmentProfiles.POST_ALTA` con la diferencia entre "clave ausente" y
         * "clave en false" (B.61).
         */
        const val KEY_DEVICE_DECISIONS = "whitelist_device_decisions"
        /** JSON: {"pkg": {"label":…, "allow":[…], "block":[…], "apkUrl":…}} — apps agregadas desde el panel. */
        const val KEY_CUSTOM = "whitelist_custom_apps"

        const val STATE_ALLOW = "allow"
        const val STATE_BLOCK = "block"
        const val STATE_UNSET = "unset"

        /**
         * Tope de dominios distintos que la auditoría publica al panel. 300 alcanza de
         * sobra para completar las listas de 25 apps y acota el nodo de Firebase: sin
         * tope, un equipo con la lista blanca mal configurada publicaría miles de
         * dominios por hora.
         */
        private const val AUDIT_MAX_DOMAINS = 300

        /**
         * El Trie se comparte entre el hilo lector del túnel y la UI. Se reconstruye
         * entero y se publica de una (AtomicReference), igual que DomainRuleEngine: el
         * camino caliente nunca toma un lock.
         *
         * Es estático a propósito. `handleDnsQuery` corre hasta cientos de veces por
         * minuto y no puede construir un WhitelistManager (ni leer SharedPreferences)
         * por consulta — ese fue exactamente el costo que B.13 sacó de la Capa 3.
         */
        private val trieRef = AtomicReference(DomainRuleTrie.build(emptyMap()))
        private val loadedRef = AtomicReference(false)

        /** Copia en memoria de los interruptores, para no leer preferencias por consulta. */
        @Volatile private var cachedEnabled = false
        @Volatile private var cachedSimulation = true

        /** Auditoría: dominio → cuántas veces se bloqueó (o se habría bloqueado). */
        private val auditLock = Any()
        private val auditCounts = LinkedHashMap<String, Int>()
        @Volatile private var auditDroppedDomains = 0

        /**
         * ¿Está encendido el modo? Lee el espejo en memoria; si nunca se cargó, cae a
         * preferencias UNA vez. Con `false` el llamador ni siquiera consulta el Trie
         * para decidir si bloquea lo no listado (pero sí para los BLOCK explícitos).
         */
        fun isEnabledCached(): Boolean = cachedEnabled

        fun isSimulationCached(): Boolean = cachedSimulation

        /**
         * Decisión para un dominio. Es LA función del camino caliente: una resolución de
         * Trie y nada más. Devuelve:
         *   - `RuleType.BLOCK`       → bloquear (dominio de app prohibida o bloqueo fijo)
         *   - `RuleType.ALLOW`       → permitido por una app permitida
         *   - `RuleType.FORCE_ALLOW` → infraestructura: permitido pase lo que pase
         *   - `null`                 → no figura en ninguna lista
         */
        fun decide(domain: String): RuleType? = trieRef.get().resolve(domain)

        /** Registra un dominio bloqueado (o que se habría bloqueado) por la lista blanca. */
        fun recordAudit(domain: String) {
            synchronized(auditLock) {
                val current = auditCounts[domain]
                if (current != null) {
                    auditCounts[domain] = current + 1
                } else {
                    if (auditCounts.size >= AUDIT_MAX_DOMAINS) {
                        auditDroppedDomains++
                        return
                    }
                    auditCounts[domain] = 1
                }
            }
        }

        /** Copia ordenada por cantidad de golpes, para publicar al panel. */
        fun auditSnapshot(): List<Pair<String, Int>> = synchronized(auditLock) {
            auditCounts.entries.map { it.key to it.value }.sortedByDescending { it.second }
        }

        fun auditDropped(): Int = auditDroppedDomains

        fun clearAudit() {
            synchronized(auditLock) {
                auditCounts.clear()
                auditDroppedDomains = 0
            }
        }

        fun isLoaded(): Boolean = loadedRef.get()

        internal fun publish(trie: DomainRuleTrie, enabled: Boolean, simulation: Boolean) {
            trieRef.set(trie)
            cachedEnabled = enabled
            cachedSimulation = simulation
            loadedRef.set(true)
        }

        /**
         * Arma el mapa dominio → regla. **Función pura a propósito**: no toca
         * preferencias, no toca el sistema y no depende de un Context, así que se puede
         * ejercitar entera fuera del equipo. Es la parte del modo lista blanca donde un
         * error se paga caro (un orden mal puesto y `tenor.googleapis.com` queda
         * permitido, o `mtalk.google.com` bloqueado y el equipo sordo al panel), y es
         * justo la parte que no se puede verificar con un type-check.
         *
         * **EL ORDEN DE INSERCIÓN ES LA ESPECIFICACIÓN, NO UN DETALLE.** El Trie guarda
         * una regla por nodo: la última escritura sobre el MISMO dominio gana, y entre
         * dominios distintos gana el más específico. De menos a más prioritario:
         *
         *   1. CDN compartidos             (ALLOW, y solo si el interruptor está)
         *   2. dominios de apps permitidas (ALLOW)
         *   3. dominios de apps prohibidas (BLOCK)  ← se cierran enteros
         *   4. infraestructura             (FORCE_ALLOW)
         *   5. no kosher dentro de apps    (BLOCK)  ← ofertas de MP, foro de Waze, Tenor…
         *   6. bloqueos fijos              (BLOCK)  ← le ganan hasta a la infraestructura
         *
         * **El 5 es nuevo del 10/9/2026 y antes era el viejo paso 3.** Era "bloqueos de
         * las apps PERMITIDAS" y corría antes de la infraestructura; ahora es "bloqueos de
         * TODAS las apps del catálogo, marcadas o no" y corre después. El porqué completo
         * está en `alwaysBlockedInAppDomains()`: en resumen, que un host sea contenido no
         * kosher es una propiedad del host y no de la decisión sobre la app, así que no
         * puede depender de que el administrador haya marcado esa app.
         *
         * El 5 y el 6 van después de TODOS los permisos para que no dependa del orden del
         * mapa cuál de dos apps ganó sobre un dominio compartido, y van últimos porque
         * `tenor.googleapis.com` tiene que ganarle a `googleapis.com` del 4 aunque alguien
         * agregue una app personalizada que lo permita.
         */
        /**
         * Dominios permitidos de una app: los del catálogo de fábrica **más** los que el
         * panel le haya agregado.
         *
         * **Se SUMAN, no se reemplazan, y esa es una corrección deliberada.** La primera
         * versión hacía `custom[pkg] ?: catálogo`, o sea que agregar un solo dominio
         * desde la auditoría del panel dejaba a la app con ESE dominio y le sacaba los
         * demás. El flujo principal del modo lista blanca es justamente ese —ver un
         * dominio que faltó y sumarlo con un clic—, así que la variante que reemplaza
         * convertía el camino más usado en la forma más fácil de romper una app.
         *
         * Para SACAR un dominio de fábrica no hace falta poder reemplazar la lista:
         * alcanza con ponerlo en la lista de bloqueados de esa app, que se escriben
         * después de todos los permisos y por lo tanto ganan.
         */
        private fun allowedDomainsOf(pkg: String, custom: Map<String, CustomApp>): List<String> =
            (WhitelistCatalog.entryFor(pkg)?.allow ?: emptyList()) + (custom[pkg]?.allow ?: emptyList())

        /**
         * Dominios no kosher de una app: los del catálogo de fábrica **menos los que el
         * panel haya desbloqueado explícitamente**, más los que el panel haya agregado.
         *
         * **El `unblock` existe porque el catálogo de fábrica lo escribió una IA leyendo
         * documentación, no midiendo la app.** Si una de esas decisiones está mal —por
         * ejemplo marcar como "marketplace" un host que Mercado Pago necesita para
         * pagar—, antes no había forma de corregirla desde el panel: los bloqueos se
         * escriben después de todos los permisos, así que ganaban siempre y la única
         * salida era un `FORCE_ALLOW` por equipo en la sección DNS. Ahora la corrección
         * es global y vive donde se ve la lista.
         *
         * **No es simétrico con `allow` a propósito.** Para SACAR un dominio permitido de
         * fábrica no hace falta nada nuevo: alcanza con ponerlo en `block`, que se
         * escribe después. Para sacar un BLOQUEO no alcanzaba con nada, porque no hay
         * nada que se escriba después. Por eso el campo nuevo es este y solo este.
         */
        private fun blockedDomainsOf(pkg: String, custom: Map<String, CustomApp>): List<String> {
            val factory = WhitelistCatalog.entryFor(pkg)?.block ?: emptyList()
            val unblocked = (custom[pkg]?.unblock ?: emptyList()).map { normalizeDomain(it) }.toSet()
            return factory.filter { normalizeDomain(it) !in unblocked } + (custom[pkg]?.block ?: emptyList())
        }

        /**
         * ★ 10/9/2026 — LOS DOMINIOS NO KOSHER DE UNA APP SE CIERRAN AUNQUE LA APP ESTÉ
         * PERMITIDA, Y AUNQUE EL MODO LISTA BLANCA ESTÉ APAGADO.
         *
         * Pedido textual del dueño: *"aunque hagamos modo lista negra, las apps mismas
         * que permita tienen que quedar bloqueados sus dominios no kosher"*.
         *
         * **Tenía razón y era un agujero medido.** Hasta hoy, las listas `block` del
         * catálogo solo entraban al Trie para las apps que tuvieran una DECISIÓN
         * explícita guardada (`allow` o `block`). O sea que en un equipo normal —modo
         * lista negra, catálogo sin tocar, `decisions` vacío— los **39 dominios no
         * kosher que viven dentro de apps que el equipo usa** resolvían todos:
         * `ofertas.mercadopago.com` y los 9 hosts del marketplace de Mercado Libre,
         * `support/help/forum/blog.waze.com`, los 6 de juegos y streaming de DiDi,
         * `translate.google.com` (que traduce páginas enteras, o sea un proxy de
         * navegación), y Tenor/Giphy dentro de Mensajes. Solo 8 dominios se cerraban
         * siempre, los de `BLOCK_ALWAYS`.
         *
         * La decisión de permitir o prohibir una app es una cosa; que un host de esa app
         * sea contenido no kosher es **una propiedad del host**, no de la decisión. Por
         * eso ahora se aplica sobre TODO el catálogo y TODAS las apps personalizadas, sin
         * mirar el estado, y se escribe en la misma pasada final que `BLOCK_ALWAYS`.
         *
         * **NO lleva interruptor, y es deliberado.** Esta sesión existe porque el panel
         * tenía 75 interruptores y el dueño lo describió como "mareador": agregar el 76
         * para algo que él pidió de forma incondicional sería ir para atrás. Y vale la
         * lección de B.43: *"un interruptor apagado por defecto solo protege a quien se
         * acuerde de encenderlo"*. La salida de emergencia ya existe y es mejor que un
         * interruptor porque es por dominio y por equipo: un `FORCE_ALLOW` de la sección
         * DNS se resuelve ANTES que esto y gana (punto 1 del orden de precedencia). Para
         * corregir el catálogo para toda la flota está `unblock`, arriba.
         */
        private fun alwaysBlockedInAppDomains(custom: Map<String, CustomApp>): List<String> {
            val out = mutableListOf<String>()
            for (entry in WhitelistCatalog.APPS) out.addAll(blockedDomainsOf(entry.packageName, custom))
            // Las apps personalizadas que el panel agregó y que NO están en el catálogo
            // de fábrica: sus bloqueos valen igual. `blockedDomainsOf` ya fusiona las dos
            // fuentes, así que para las que sí están en el catálogo esto no duplica nada
            // (y un duplicado sería inocuo: misma regla sobre el mismo nodo del Trie).
            for (pkg in custom.keys) {
                if (WhitelistCatalog.entryFor(pkg) == null) out.addAll(blockedDomainsOf(pkg, custom))
            }
            return out
        }

        fun buildRules(
            decisions: Map<String, String>,
            custom: Map<String, CustomApp>,
            includeSharedCdn: Boolean
        ): Map<String, RuleType> {
            val rules = mutableMapOf<String, RuleType>()

            if (includeSharedCdn) {
                for (d in WhitelistCatalog.SHARED_CDN) rules[normalizeDomain(d)] = RuleType.ALLOW
            }

            for ((pkg, state) in decisions) {
                if (state != STATE_ALLOW) continue
                for (d in allowedDomainsOf(pkg, custom)) rules[normalizeDomain(d)] = RuleType.ALLOW
            }

            for ((pkg, state) in decisions) {
                if (state != STATE_BLOCK) continue
                val todos = allowedDomainsOf(pkg, custom) + blockedDomainsOf(pkg, custom)
                for (d in todos) rules[normalizeDomain(d)] = RuleType.BLOCK
            }

            for (d in WhitelistCatalog.INFRASTRUCTURE) rules[normalizeDomain(d)] = RuleType.FORCE_ALLOW

            // ── PASADA FINAL DE BLOQUEOS ──
            // Va DESPUÉS de la infraestructura a propósito: si un host estuviera por
            // error en las dos listas, acá gana el bloqueo. Es el mismo motivo por el que
            // BLOCK_ALWAYS ya estaba último — `tenor.googleapis.com` tiene que ganarle a
            // `googleapis.com`, y ninguna app personalizada puede destapar un bloqueo
            // fijo escribiendo un permiso.
            //
            // Los dos van juntos porque son la misma idea: contenido no kosher que vive
            // adentro de algo que por lo demás hace falta. Ver alwaysBlockedInAppDomains.
            for (d in alwaysBlockedInAppDomains(custom)) rules[normalizeDomain(d)] = RuleType.BLOCK

            for (d in WhitelistCatalog.BLOCK_ALWAYS) rules[normalizeDomain(d)] = RuleType.BLOCK

            return rules
        }
    }

    private fun prefs() = PrefsHelper.getMdmPrefs(context)

    // ─────────────────────────────────────────────────────────────────────────
    // INTERRUPTORES
    // ─────────────────────────────────────────────────────────────────────────

    fun isEnabled(): Boolean = prefs().getBoolean(KEY_ENABLED, false)

    /** Simulación ENCENDIDA por omisión: ver punto 3 de la cabecera. */
    fun isSimulation(): Boolean = prefs().getBoolean(KEY_SIMULATION, true)

    fun isSharedCdnAllowed(): Boolean = prefs().getBoolean(KEY_ALLOW_SHARED_CDN, true)

    fun setEnabled(enabled: Boolean) {
        prefs().edit().putBoolean(KEY_ENABLED, enabled).apply()
        // Al apagarlo se limpia la auditoría: los datos viejos describen otra
        // configuración y confunden más de lo que ayudan.
        if (!enabled) clearAudit()
        reload()
    }

    fun setSimulation(simulation: Boolean) {
        prefs().edit().putBoolean(KEY_SIMULATION, simulation).apply()
        reload()
    }

    fun setSharedCdnAllowed(allowed: Boolean) {
        prefs().edit().putBoolean(KEY_ALLOW_SHARED_CDN, allowed).apply()
        reload()
    }

    // ─────────────────────────────────────────────────────────────────────────
    // DECISIONES POR APP
    // ─────────────────────────────────────────────────────────────────────────

    private fun readDecisions(): JSONObject =
        try { JSONObject(prefs().getString(KEY_DECISIONS, "{}") ?: "{}") } catch (e: Exception) { JSONObject() }

    private fun readDeviceDecisions(): JSONObject =
        try { JSONObject(prefs().getString(KEY_DEVICE_DECISIONS, "{}") ?: "{}") } catch (e: Exception) { JSONObject() }

    private fun readCustom(): JSONObject =
        try { JSONObject(prefs().getString(KEY_CUSTOM, "{}") ?: "{}") } catch (e: Exception) { JSONObject() }

    /** Estado EFECTIVO: lo propio de este equipo si existe, si no lo global. */
    fun stateOf(packageName: String): String =
        allDecisions()[packageName] ?: STATE_UNSET

    /** Solo el catálogo de la flota, sin el override. Para que la UI pueda decir de dónde viene. */
    fun globalStateOf(packageName: String): String =
        readDecisions().optString(packageName, STATE_UNSET).ifEmpty { STATE_UNSET }

    /** `null` si este equipo no tiene decisión propia para esa app. */
    fun deviceStateOf(packageName: String): String? {
        val json = readDeviceDecisions()
        if (!json.has(packageName)) return null
        return json.optString(packageName, STATE_UNSET).ifEmpty { STATE_UNSET }
    }

    /**
     * Todas las decisiones EFECTIVAS, para la UI, para el Trie y para el reporte al panel.
     * El override del equipo se aplica encima del catálogo global, y por eso se escribe
     * segundo: la última escritura sobre la misma clave gana, igual que en el Trie.
     */
    fun allDecisions(): Map<String, String> {
        val out = mutableMapOf<String, String>()
        val global = readDecisions()
        for (key in global.keys()) out[key] = global.optString(key, STATE_UNSET)
        val device = readDeviceDecisions()
        for (key in device.keys()) out[key] = device.optString(key, STATE_UNSET)
        return out
    }

    /**
     * UN SOLO TOQUE: permitir una app.
     *
     * Hace las tres cosas del pedido en el mismo llamado, y **en este orden a propósito**:
     * primero se guarda la decisión (para que cualquier reconciliación posterior vea el
     * estado nuevo), después se recarga el Trie (los dominios ya resuelven), y recién
     * después se destapa la app. Al revés, la app aparecería visible unos instantes
     * todavía sin poder resolver nada, que es la peor combinación para el usuario:
     * parece rota en vez de bloqueada.
     *
     * La descarga desde la tienda administrada se habilita agregando el paquete a
     * `allowedPackages` en Firebase, que es el mecanismo que ya existía (lo consume
     * `LoginActivity`); eso lo hace el panel, y acá se deja registrado en el reporte
     * para que el panel pueda reconciliarlo.
     */
    fun allowApp(packageName: String): Boolean = setState(packageName, STATE_ALLOW)

    /** UN SOLO TOQUE: prohibir una app (oculta + suspende + cierra sus dominios). */
    fun blockApp(packageName: String): Boolean = setState(packageName, STATE_BLOCK)

    /** Vuelve la app a "sin marcar": no se toca, pero tampoco se le abren dominios. */
    fun unsetApp(packageName: String): Boolean = setState(packageName, STATE_UNSET)

    /**
     * UN SOLO TOQUE, pero SOLO PARA ESTE EQUIPO. Es lo que usan la ficha del celular en
     * el panel y la pantalla del propio teléfono: tocar una app ahí no puede cambiarle la
     * configuración a toda la flota.
     *
     * `state` puede ser `unset`, y ahí el override se guarda igual (queda "sin marcar en
     * este equipo", anulando un `allow`/`block` global). Para volver a heredar el
     * catálogo global está `clearDeviceState()`.
     */
    fun setDeviceState(packageName: String, state: String): Boolean =
        setState(packageName, state, deviceLevel = true)

    /** Este equipo vuelve a heredar lo que diga el catálogo global para esa app. */
    fun clearDeviceState(packageName: String): Boolean {
        val pkg = packageName.trim()
        if (pkg.isEmpty()) return false
        val json = readDeviceDecisions()
        if (!json.has(pkg)) return true
        json.remove(pkg)
        prefs().edit().putString(KEY_DEVICE_DECISIONS, json.toString()).apply()
        reload()
        return try {
            applyAppSideEffects(pkg, stateOf(pkg), STATE_BLOCK)
            true
        } catch (e: Exception) {
            e.printStackTrace()
            false
        }
    }

    /**
     * Reemplaza los overrides de este equipo con lo que mandó el panel.
     *
     * Reemplaza en vez de fusionar, por el mismo motivo que `replaceCustomApps()`:
     * fusionar hace imposible QUITAR un override desde el panel. El panel siempre manda
     * el mapa completo del equipo.
     */
    fun replaceDeviceDecisions(json: String): Boolean {
        return try {
            val parsed = JSONObject(json)
            prefs().edit().putString(KEY_DEVICE_DECISIONS, parsed.toString()).apply()
            reload()
            true
        } catch (e: Exception) {
            android.util.Log.e("WhitelistManager", "JSON de overrides por equipo inválido: ${e.message}")
            false
        }
    }

    /**
     * Reemplaza el catálogo GLOBAL con lo que mandó el panel.
     *
     * ★ Corrige de paso un bug de B.53 que nadie había mirado: `pullWhitelistConfig`
     * recorría solo los paquetes presentes en el nodo de Firebase, así que **una app
     * borrada del catálogo se quedaba con su decisión vieja en el equipo para siempre**.
     * Prohibías una app, la sacabas del catálogo, y seguía oculta sin que el panel
     * mostrara ningún motivo. Reemplazar el mapa entero lo cierra: lo que ya no está
     * queda en `unset`, y `reconcileApps()` la destapa en la próxima vuelta del Watchdog
     * (comparar y corregir, la lección de B.15 punto 3).
     */
    fun replaceGlobalDecisions(json: String): Boolean {
        return try {
            val parsed = JSONObject(json)
            prefs().edit().putString(KEY_DECISIONS, parsed.toString()).apply()
            reload()
            true
        } catch (e: Exception) {
            android.util.Log.e("WhitelistManager", "JSON de decisiones globales inválido: ${e.message}")
            false
        }
    }

    private fun setState(packageName: String, state: String, deviceLevel: Boolean = false): Boolean {
        val pkg = packageName.trim()
        if (pkg.isEmpty()) return false

        // Las apps críticas no se prohíben aunque lo pida el panel: dejar el equipo sin
        // Play Services es dejarlo sin FCM, o sea sin forma de recibir el comando que
        // deshaga el error. Se acepta el llamado y se ignora el bloqueo, en vez de
        // fallar en silencio.
        if (state == STATE_BLOCK && isNeverBlockable(pkg)) {
            android.util.Log.w("WhitelistManager", "No se prohíbe $pkg: es una app crítica del equipo")
            return false
        }

        val previous = stateOf(pkg)
        val key = if (deviceLevel) KEY_DEVICE_DECISIONS else KEY_DECISIONS
        val json = if (deviceLevel) readDeviceDecisions() else readDecisions()
        // En el mapa del EQUIPO, `unset` se guarda explícito: tiene que poder anular un
        // `allow` global. En el GLOBAL se borra la clave, que es lo que siempre hizo.
        if (state == STATE_UNSET && !deviceLevel) json.remove(pkg) else json.put(pkg, state)
        prefs().edit().putString(key, json.toString()).apply()

        reload()

        return try {
            // El efecto sobre la app se decide con el estado EFECTIVO, no con el que se
            // acaba de escribir: si el catálogo global dice `block` y este equipo escribe
            // `unset`, lo que corresponde es destapar, no "no hacer nada".
            applyAppSideEffects(pkg, stateOf(pkg), previous)
            true
        } catch (e: Exception) {
            e.printStackTrace()
            false
        }
    }

    fun isNeverBlockable(packageName: String): Boolean {
        if (packageName == context.packageName) return true
        if (WhitelistCatalog.NEVER_BLOCK_PACKAGES.contains(packageName)) return true
        return try {
            AppController(context).isCritical(packageName)
        } catch (e: Exception) {
            false
        }
    }

    /**
     * El lado "apps" del toque único: ocultar/suspender al prohibir, destapar al permitir.
     *
     * Con LockSuite suspendido no se toca nada del sistema: la suspensión promete un
     * equipo literalmente libre y ocultar una app la rompería (misma regla que
     * `PolicyManager.deferIfSuspended`). La decisión igual queda guardada y
     * `reconcileApps()` la aplica cuando se reanude.
     */
    private fun applyAppSideEffects(packageName: String, state: String, previous: String = STATE_UNSET) {
        val policyManager = PolicyManager(context)
        if (policyManager.isLockSuiteSuspended()) {
            android.util.Log.i("WhitelistManager", "LockSuite suspendido: $packageName queda guardado sin aplicar")
            return
        }
        if (!isInstalled(packageName)) return

        val controller = AppController(context)
        when (state) {
            STATE_BLOCK -> {
                controller.hideApp(packageName, true)
                controller.suspendApp(packageName, true)
            }
            STATE_ALLOW -> {
                // Se destapa SOLO lo que la lista blanca había cerrado. Si el
                // administrador ocultó esta app a mano desde la sección Aplicaciones,
                // eso es una intención distinta y no se pisa acá.
                controller.hideApp(packageName, false)
                controller.suspendApp(packageName, false)
            }
            STATE_UNSET -> {
                // Volver a "sin marcar" no toca la app… SALVO que veníamos de
                // `block`, en cuyo caso la que la ocultó fue la lista blanca y
                // tiene que destaparla. Sin esta rama, quitarle el override a un
                // equipo (o sacar la app del catálogo global) dejaba la app
                // oculta para siempre y el panel mostrándola como "sin marcar":
                // el administrador ve una app sin bloquear que el usuario no
                // encuentra por ningún lado, sin ningún motivo a la vista.
                //
                // Se mira `previous` y no el estado real de la app a propósito:
                // así una app que el administrador ocultó A MANO desde la sección
                // Aplicaciones no se destapa sola por pasar por acá.
                if (previous == STATE_BLOCK) {
                    controller.hideApp(packageName, false)
                    controller.suspendApp(packageName, false)
                }
            }
        }
    }

    private fun isInstalled(packageName: String): Boolean = try {
        context.packageManager.getPackageInfo(packageName, 0)
        true
    } catch (e: Exception) {
        false
    }

    /**
     * Compara el estado real de cada app contra la decisión guardada y corrige solo lo
     * que difiere. Es el mismo patrón de `AppController.reconcileEmergencySuspend()` y
     * está por el mismo motivo (B.15 punto 3): otros mecanismos —el Watchdog de 15 min,
     * un comando del panel, el flujo de Play Store— pueden des-ocultar una app por su
     * cuenta, y sin reconciliar nadie se entera. Ordenar "ocultá" una vez no alcanza.
     *
     * @return cuántas apps hubo que corregir.
     */
    fun reconcileApps(): Int {
        val policyManager = PolicyManager(context)
        if (policyManager.isLockSuiteSuspended()) return 0
        val controller = AppController(context)
        var fixed = 0
        for ((pkg, state) in allDecisions()) {
            if (!isInstalled(pkg)) continue
            if (isNeverBlockable(pkg)) continue
            try {
                val shouldHide = (state == STATE_BLOCK)
                if (controller.isAppHidden(pkg) != shouldHide) {
                    controller.hideApp(pkg, shouldHide)
                    fixed++
                }
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }
        return fixed
    }

    // ─────────────────────────────────────────────────────────────────────────
    // APPS PERSONALIZADAS (agregadas desde el panel)
    // ─────────────────────────────────────────────────────────────────────────

    data class CustomApp(
        val packageName: String,
        val label: String,
        val allow: List<String>,
        val block: List<String>,
        /**
         * Dominios del catálogo DE FÁBRICA que el panel desbloqueó a mano. Ver
         * `blockedDomainsOf()`: es la única forma de corregir una decisión del catálogo
         * sin recompilar, y hace falta porque esas listas las escribió una IA leyendo
         * documentación y no midiendo la app.
         */
        val unblock: List<String> = emptyList()
    )

    fun customApps(): List<CustomApp> {
        val json = readCustom()
        val out = mutableListOf<CustomApp>()
        for (key in json.keys()) {
            val obj = json.optJSONObject(key) ?: continue
            out.add(
                CustomApp(
                    packageName = key,
                    label = obj.optString("label", key),
                    allow = jsonArrayToList(obj.optJSONArray("allow")),
                    block = jsonArrayToList(obj.optJSONArray("block")),
                    unblock = jsonArrayToList(obj.optJSONArray("unblock"))
                )
            )
        }
        return out
    }

    /**
     * Reemplaza el catálogo personalizado entero con lo que mandó el panel.
     *
     * Reemplaza en vez de fusionar a propósito: fusionar hace imposible BORRAR una app
     * desde el panel (quedaría para siempre en el equipo, y el panel mostraría una
     * configuración que no es la que rige). El panel siempre manda la lista completa.
     */
    fun replaceCustomApps(json: String): Boolean {
        return try {
            val parsed = JSONObject(json) // valida antes de guardar
            prefs().edit().putString(KEY_CUSTOM, parsed.toString()).apply()
            reload()
            true
        } catch (e: Exception) {
            android.util.Log.e("WhitelistManager", "JSON de apps personalizadas inválido: ${e.message}")
            false
        }
    }

    private fun jsonArrayToList(array: JSONArray?): List<String> {
        if (array == null) return emptyList()
        val out = mutableListOf<String>()
        for (i in 0 until array.length()) {
            val v = array.optString(i, "").trim()
            if (v.isNotEmpty()) out.add(v)
        }
        return out
    }

    // ─────────────────────────────────────────────────────────────────────────
    // CONSTRUCCIÓN DEL TRIE
    // ─────────────────────────────────────────────────────────────────────────

    /**
     * Reconstruye el Trie desde las preferencias y lo publica. Barato (decenas de
     * entradas) y se llama solo cuando cambia algo, nunca por consulta DNS.
     *
     * **El orden de inserción importa y no es cosmético.** El Trie guarda una regla por
     * nodo, así que la última escritura sobre el mismo dominio gana. Se inserta de menos
     * a más prioritario:
     *   CDN compartidos → dominios de apps permitidas → dominios de apps prohibidas →
     *   infraestructura → bloqueos fijos.
     * Así `tenor.googleapis.com` (bloqueo fijo) le gana a `googleapis.com`
     * (infraestructura) por ser más específico Y por escribirse después, y ninguna app
     * personalizada puede abrir por accidente algo que BLOCK_ALWAYS cierra.
     */
    fun reload() {
        val rules = buildRules(allDecisions(), customApps().associateBy { it.packageName }, isSharedCdnAllowed())
        publish(DomainRuleTrie.build(rules), isEnabled(), isSimulation())
    }

    /**
     * Cuántos dominios tiene cargados la lista blanca ahora mismo (para el panel).
     *
     * Se calcula llamando a `buildRules()` en vez de sumando a mano las mismas listas.
     * Antes eran dos cuentas en paralelo y ya se habían desincronizado: la versión vieja
     * ignoraba a las apps sin marcar, que desde el 10/9 sí aportan sus bloqueos no
     * kosher, así que el panel habría mostrado menos reglas de las que rigen. Es barato
     * (decenas de entradas) y por construcción no puede volver a diferir.
     */
    fun ruleCount(): Int =
        buildRules(allDecisions(), customApps().associateBy { it.packageName }, isSharedCdnAllowed()).size
}
