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
        /** JSON: {"pkg": "allow"|"block"} — decisión por app. */
        const val KEY_DECISIONS = "whitelist_decisions"
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
         *   1. CDN compartidos            (ALLOW, y solo si el interruptor está)
         *   2. dominios de apps permitidas (ALLOW)
         *   3. bloqueos de apps permitidas (BLOCK)  ← Tenor en Mensajes, ofertas en MP
         *   4. dominios de apps prohibidas (BLOCK)  ← se cierran enteros
         *   5. infraestructura             (FORCE_ALLOW)
         *   6. bloqueos fijos              (BLOCK)  ← le ganan hasta a la infraestructura
         *
         * El 3 va después de TODOS los permisos del 2 para que no dependa del orden del
         * mapa cuál de dos apps ganó sobre un dominio compartido. El 6 va último porque
         * `tenor.googleapis.com` tiene que ganarle a `googleapis.com` del 5 aunque
         * alguien agregue una app personalizada que lo permita.
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

        private fun blockedDomainsOf(pkg: String, custom: Map<String, CustomApp>): List<String> =
            (WhitelistCatalog.entryFor(pkg)?.block ?: emptyList()) + (custom[pkg]?.block ?: emptyList())

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
                if (state != STATE_ALLOW) continue
                for (d in blockedDomainsOf(pkg, custom)) rules[normalizeDomain(d)] = RuleType.BLOCK
            }

            for ((pkg, state) in decisions) {
                if (state != STATE_BLOCK) continue
                val todos = allowedDomainsOf(pkg, custom) + blockedDomainsOf(pkg, custom)
                for (d in todos) rules[normalizeDomain(d)] = RuleType.BLOCK
            }

            for (d in WhitelistCatalog.INFRASTRUCTURE) rules[normalizeDomain(d)] = RuleType.FORCE_ALLOW

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

    private fun readCustom(): JSONObject =
        try { JSONObject(prefs().getString(KEY_CUSTOM, "{}") ?: "{}") } catch (e: Exception) { JSONObject() }

    fun stateOf(packageName: String): String =
        readDecisions().optString(packageName, STATE_UNSET).ifEmpty { STATE_UNSET }

    /** Todas las decisiones guardadas, para la UI y para el reporte al panel. */
    fun allDecisions(): Map<String, String> {
        val json = readDecisions()
        val out = mutableMapOf<String, String>()
        for (key in json.keys()) out[key] = json.optString(key, STATE_UNSET)
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

    private fun setState(packageName: String, state: String): Boolean {
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

        val json = readDecisions()
        if (state == STATE_UNSET) json.remove(pkg) else json.put(pkg, state)
        prefs().edit().putString(KEY_DECISIONS, json.toString()).apply()

        reload()

        return try {
            applyAppSideEffects(pkg, state)
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
    private fun applyAppSideEffects(packageName: String, state: String) {
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
            // STATE_UNSET: no se toca la app. Se queda sin dominios (punto 2 de la
            // cabecera) pero visible, que es lo que se eligió.
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
        val block: List<String>
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
                    block = jsonArrayToList(obj.optJSONArray("block"))
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

    /** Cuántos dominios tiene cargados la lista blanca ahora mismo (para el panel). */
    fun ruleCount(): Int {
        var n = WhitelistCatalog.INFRASTRUCTURE.size + WhitelistCatalog.BLOCK_ALWAYS.size
        if (isSharedCdnAllowed()) n += WhitelistCatalog.SHARED_CDN.size
        val custom = customApps().associateBy { it.packageName }
        for ((pkg, state) in allDecisions()) {
            if (state == STATE_UNSET) continue
            // Mismo criterio que buildRules(): catálogo + panel, no uno u otro.
            n += (WhitelistCatalog.entryFor(pkg)?.let { it.allow.size + it.block.size } ?: 0) +
                 (custom[pkg]?.let { it.allow.size + it.block.size } ?: 0)
        }
        return n
    }
}
