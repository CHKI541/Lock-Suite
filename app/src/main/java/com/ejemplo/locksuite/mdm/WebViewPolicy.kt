package com.ejemplo.locksuite.mdm

object WebViewPolicy {

    // Whitelist estricta: Dominios esenciales ("core") de apps conocidas.
    // Si la app está en esta lista, se bloquea TODO excepto lo que declare aquí.
    private val CORE_DOMAINS: Map<String, Set<String>> = mapOf(
        "com.waze" to setOf(
            "waze.com",          // API principal de Waze
            "wazestatic.com",    // Assets estáticos de Waze
            "waze-cdn.com",      // Servidores de CDN
            "googleapis.com",    // Carga de mapas
            "gstatic.com"        // Recursos de Google
            // Bloqueado: support.waze.com, help.waze.com, etc. (Zendesk / Ayuda)
        ),
        "com.didiglobal.passenger" to setOf(
            "didiglobal.com",    // API principal
            "xiaojukeji.com",    // Backend DiDi
            "diditaxi.com.cn"    // Servidores de taxi
            // Bloqueado: game.diditaxi.com.cn, minigame.didiglobal.com, drama.didiglobal.com, etc.
        )
    )

    // Blacklist global de dominios que suelen cargar los WebViews (ayuda, juegos, navegación, anuncios).
    // Si una app común (no registrada en CORE_DOMAINS) tiene el bloqueo de WebView activo,
    // interceptamos sus consultas y bloqueamos si coinciden con estos sufijos de dominios.
    private val GLOBAL_BLACKLIST: Set<String> = setOf(
        // Helpdesks y Soporte
        "zendesk.com",
        "zdassets.com",
        "ekr.zdassets.com",
        "freshdesk.com",
        "intercom.io",
        "salesforce.com",
        "zoho.com",

        // Portales y Buscadores (para evitar navegación libre en WebViews ocultos)
        "google.com",
        "bing.com",
        "yahoo.com",
        "duckduckgo.com",
        "wikipedia.org",

        // Redes Sociales y Videos
        "facebook.com",
        "facebook.net",
        "instagram.com",
        "twitter.com",
        "t.co",
        "youtube.com",
        "youtu.be",
        "vimeo.com",
        "tiktok.com",

        // Juegos y Entretenimiento
        "game.diditaxi.com.cn",
        "minigame.didiglobal.com",
        "play.didiglobal.com",
        "xiaojukeji.com/game",
        "drama.didiglobal.com",
        "webcast.didiglobal.com",

        // Anuncios y Trackers comunes en WebViews
        "doubleclick.net",
        "googleadservices.com",
        "admob.com",
        "app-measurement.com",
        "crashlytics.com"
    )

    // Servicios de infraestructura globales indispensables que permitimos para que las apps no se rompan
    private val SHARED_INFRASTRUCTURE_DOMAINS: Set<String> = setOf(
        "googleapis.com",
        "gstatic.com",
        "firebaseio.com",
        "firebase.com",
        "firebaseinstallations.googleapis.com",
        "app-measurement.com",
        "crashlytics.com",
        "doubleclick.net" // A veces necesario para mapas de Google de manera interna
    )

    /**
     * Obtiene los dominios core autorizados para una app específica.
     * Retorna null si la app no tiene una whitelist estricta definida.
     */
    fun getCoreDomainsFor(packageName: String): Set<String>? {
        return CORE_DOMAINS[packageName]
    }

    /**
     * Retorna la blacklist global de dominios no-kosher comúnmente cargados en WebViews.
     */
    fun getGlobalBlacklist(): Set<String> {
        return GLOBAL_BLACKLIST
    }

    /**
     * Determina si un dominio consultado está permitido para una aplicación genérica
     * que no tiene una whitelist estricta configurada.
     * 
     * Implementa "Auto-Whitelist": Permite dominios que contienen la palabra clave de la app
     * y servicios globales de infraestructura de Android. Bloquea todo lo demás (Zendesk, webs, etc.).
     */
    fun isDomainAllowedForGenericApp(packageName: String, queriedDomain: String): Boolean {
        // 1. Si es un dominio de infraestructura global indispensable, permitir
        val isSharedInfra = SHARED_INFRASTRUCTURE_DOMAINS.any { 
            queriedDomain == it || queriedDomain.endsWith(".$it") 
        }
        if (isSharedInfra) return true

        // 2. Extraer palabras clave del packageName (ej: "com.cabify.passenger" -> ["cabify", "passenger"])
        // Ignoramos palabras genéricas como "com", "android", "apps", "client", "passenger", "mobile", "user"
        val ignoredTokens = setOf("com", "android", "apps", "client", "passenger", "mobile", "user", "app", "play")
        val tokens = packageName.split(".")
            .map { it.lowercase() }
            .filter { it.length > 2 && !ignoredTokens.contains(it) }

        if (tokens.isEmpty()) return true // Si no hay tokens válidos, dejamos pasar por seguridad

        // 3. Si el dominio consultado contiene al menos una palabra clave de la app, permitir
        val isAppSpecific = tokens.any { token -> queriedDomain.contains(token) }
        if (isAppSpecific) return true

        // 4. Si no es infraestructura ni contiene el nombre de la app, bloqueamos (Zendesk, búsquedas libres, etc.)
        return false
    }

    /**
     * Verifica si un dominio pertenece a la sección de ofertas/comercio de Mercado Libre / Mercado Pago.
     */
    fun isMercadoPagoOffersDomain(queriedDomain: String): Boolean {
        val lower = queriedDomain.lowercase()
        if (lower.contains("mercadolibre.")) return true
        if (lower.contains("ofertas.mercadopago") || lower.contains("promociones.mercadopago") || lower.contains("deals.mercadopago")) return true
        return false
    }
}
