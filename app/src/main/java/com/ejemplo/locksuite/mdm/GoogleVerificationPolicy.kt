package com.ejemplo.locksuite.mdm

import java.net.URI
import java.net.URLDecoder
import java.nio.charset.StandardCharsets
import java.util.Locale

/**
 * GoogleVerificationPolicy — Motor de confinamiento hermético para el Microvisor
 * de Verificaciones de Google (B.92, 20/10/2026).
 *
 * ═════════════════════════════════════════════════════════════════════════════
 * QUÉ PROBLEMA RESUELVE
 * ═════════════════════════════════════════════════════════════════════════════
 * En un celular kosher administrado por LockSuite, los navegadores están
 * suspendidos para impedir la navegación libre por internet.
 *
 * Cuando el usuario inicia sesión en una computadora, Google puede exigir
 * un desafío IAP (Identity / Anti-Abuse Protection) escaneando un código QR
 * con el teléfono para validar la posesión del dispositivo y la línea SIM:
 *   `https://accounts.google.com/devicephoneverification/begin?flow=browser&plt=...`
 *
 * Esta clase es una FUNCIÓN PURA (sin dependencias del framework de Android)
 * que garantiza que el WebView del microvisor solo pueda cargar y navegar en
 * pantallas legítimas de verificación de Google, bloqueando de forma absoluta
 * cualquier intento de escape hacia búsquedas, YouTube, Mi Actividad, creación
 * de cuentas o la web abierta.
 */
object GoogleVerificationPolicy {

    /** Host único y exacto al que se permite NAVEGAR */
    const val ALLOWED_NAV_HOST = "accounts.google.com"

    /**
     * Prefijos de ruta canónicos permitidos en [ALLOWED_NAV_HOST].
     * Se evalúan sobre la ruta normalizada y exigen límite de segmento ('/' o '?').
     */
    private val ALLOWED_PATH_PREFIXES = listOf(
        // Desafío IAP / QR de verificación de dispositivo y número de teléfono
        "/devicephoneverification",
        // Desafíos modernos v3 de inicio de sesión (prompt, sms, totp, passkey, select)
        "/v3/signin/challenge",
        // Desafíos clásicos v2 de inicio de sesión
        "/signin/challenge",
        // Desafíos de recuperación de cuenta legítimos
        "/signin/recovery/challenge",
        "/v3/signin/recovery/challenge",
        // Handlers de sesión y validación de cookies de Google Auth
        "/checkcookie",
        "/accounts/checkcookie",
        // Flujos de configuración embebidos de Google Play Services
        "/embedded/setup",
        // Verificación de seguridad intermedia
        "/speedbump/gapverify"
    )

    /**
     * Rutas expresamente PROHIBIDAS dentro de [ALLOWED_NAV_HOST], aunque
     * coincidieran con algo general.
     */
    private val FORBIDDEN_PATH_PREFIXES = listOf(
        "/signup",          // Creación de nuevas cuentas
        "/createaccount",   // Creación de nuevas cuentas alternativa
        "/manageaccount",   // Gestión de cuenta / datos personales
        "/myaccount",       // Redirección a myaccount
        "/b"                // Selector multi-cuenta que conduce a gestión general (/b/0/...)
    )

    /**
     * Hosts de subrecursos indispensables para renderizar la UI de Google
     * (scripts de cierre/Wiz, hojas de estilo, fuentes, APIs de autenticación).
     */
    private val ALLOWED_RESOURCE_HOSTS = setOf(
        "accounts.google.com",
        "www.gstatic.com",
        "ssl.gstatic.com",
        "fonts.gstatic.com",
        "apis.google.com",
        "play.google.com"
    )

    /**
     * Sufijos de dominio para subrecursos (nunca para navegación).
     */
    private val ALLOWED_RESOURCE_SUFFIXES = listOf(
        ".gstatic.com",
        ".googleapis.com",
        ".googleusercontent.com"
    )

    /**
     * Dominios de contenido explícitamente bloqueados como subrecursos
     * (cierran imágenes de YouTube, videos y publicidad).
     */
    private val FORBIDDEN_RESOURCE_DOMAINS = listOf(
        "youtube.com",
        "ytimg.com",
        "googlevideo.com",
        "doubleclick.net",
        "google-analytics.com",
        "myactivity.google.com",
        "myaccount.google.com"
    )

    data class ParsedUri(
        val scheme: String,
        val host: String?,
        val port: Int,
        val canonicalPath: String,
        val rawQuery: String?
    )

    /**
     * Parsea y valida de forma estricta según RFC 3986 sin fallbacks ambiguos.
     * Rechaza terminantemente:
     * - Caracteres de control o espacios en blanco
     * - Presencia de '@' (userinfo / credenciales en autoridad)
     * - Barras invertidas '\'
     * - Caracteres de escape de traversal '%2e' / '%2E' o separadores '%2f' / '%2F'
     */
    fun parseStrictUri(url: String?): ParsedUri? {
        if (url.isNullOrBlank()) return null
        val trimmed = url.trim()

        // 1. Rechazar caracteres de control o espacios no codificados
        for (ch in trimmed) {
            if (ch <= ' ' || ch == '\\') return null
        }

        // 2. about:blank estricto
        if (trimmed == "about:blank") {
            return ParsedUri("about", null, -1, "blank", null)
        }

        // 3. Prohibir userinfo en la autoridad (ej. https://user:pass@evil.com)
        // El caracter '@' antes de la barra del path indica userinfo malicioso.
        val doubleSlashIdx = trimmed.indexOf("://")
        if (doubleSlashIdx > 0) {
            val authorityStart = doubleSlashIdx + 3
            val slashIdx = trimmed.indexOf('/', authorityStart)
            val questionIdx = trimmed.indexOf('?', authorityStart)
            val authorityEnd = listOf(slashIdx, questionIdx)
                .filter { it >= 0 }
                .minOrNull() ?: trimmed.length
            val authority = trimmed.substring(authorityStart, authorityEnd)
            if (authority.contains("@")) {
                return null // Rechazo estricto de P1-01
            }
        }

        // 4. Parseo estricto con java.net.URI
        val uri = try {
            URI(trimmed)
        } catch (_: Exception) {
            return null
        }

        val scheme = uri.scheme?.lowercase(Locale.ROOT) ?: return null
        if (scheme != "https") return null // Único esquema permitido además de about:blank

        if (uri.userInfo != null) return null // Doble verificación de userinfo

        val host = uri.host?.lowercase(Locale.ROOT) ?: return null
        val port = uri.port // -1 si no fue especificado

        // 5. Normalizar la ruta y validar traversal
        val rawPath = uri.rawPath ?: "/"
        val lowerRawPath = rawPath.lowercase(Locale.ROOT)

        // Rechazar codificaciones sospechosas de traversal o backslashes
        if (lowerRawPath.contains("%2e") || lowerRawPath.contains("%2f") || lowerRawPath.contains("%5c")) {
            return null
        }

        // Rechazar segmentos de traversal (/.. o /.)
        val segments = rawPath.split('/')
        if (segments.any { it == ".." || it == "." }) {
            return null
        }

        // Normalización canónica (resuelve . y ..)
        val normalizedUri = uri.normalize()
        val canonicalPath = normalizedUri.rawPath ?: "/"

        if (!canonicalPath.startsWith("/")) {
            return null
        }

        return ParsedUri(scheme, host, port, canonicalPath, uri.rawQuery)
    }

    /**
     * ¿Puede el WebView NAVEGAR a esta URL?
     *
     * Reglas de máxima hermeticidad:
     * - Esquema https:// o exacto about:blank
     * - Host canónico exacto accounts.google.com (sin subdominios atacantes ni comodines)
     * - Puerto estándar 443 o no especificado (-1)
     * - Sin userinfo ni ambigüedad de autoridad
     * - Ruta normalizada que coincida con un prefijo autorizado con delimitador de segmento
     * - No coincidir con ninguna ruta prohibida (creación de cuenta, gestión, etc.)
     */
    fun isNavigationAllowed(url: String?): Boolean {
        if (url == null) return false
        val parsed = parseStrictUri(url) ?: return false

        if (parsed.scheme == "about") {
            return parsed.canonicalPath == "blank"
        }

        if (parsed.scheme != "https") return false
        if (parsed.port != -1 && parsed.port != 443) return false
        if (parsed.host != ALLOWED_NAV_HOST) return false

        val pathLower = parsed.canonicalPath.lowercase(Locale.ROOT)

        // 1. Verificar límites de segmentos en rutas prohibidas
        for (forbidden in FORBIDDEN_PATH_PREFIXES) {
            if (pathLower == forbidden || pathLower.startsWith("$forbidden/") || pathLower.startsWith("$forbidden?")) {
                return false
            }
        }

        // 2. Verificar límites de segmentos en rutas permitidas
        for (allowed in ALLOWED_PATH_PREFIXES) {
            if (pathLower == allowed || pathLower.startsWith("$allowed/") || pathLower.startsWith("$allowed?")) {
                return true
            }
        }

        return false
    }

    /**
     * ¿Puede la página descargar este subrecurso (JS, CSS, fuentes, imágenes de UI)?
     *
     * Restricciones:
     * - Solo HTTPS en puerto 443 (o data:image/ para svgs/pngs de UI embebidos)
     * - No se permite WSS (WebSockets innecesarios para esta pantalla)
     * - Hosts exactos o sufijos estrictamente autorizados
     * - Bloqueo absoluto de dominios de video, tracking y publicidad
     */
    fun isResourceAllowed(url: String?): Boolean {
        if (url == null) return false
        val trimmed = url.trim()

        if (trimmed == "about:blank") return true

        // Permitir imágenes embebidas data:image/ (íconos y assets de UI)
        if (trimmed.startsWith("data:image/")) return true
        if (trimmed.startsWith("data:")) return false // Bloquear data:text/html u otros scripts

        // Parseo estricto
        val parsed = parseStrictUri(trimmed) ?: return false
        if (parsed.scheme != "https") return false
        if (parsed.port != -1 && parsed.port != 443) return false

        val host = parsed.host ?: return false

        // Bloqueo explícito de dominios prohibidos (YouTube, publicidad, analytics)
        if (FORBIDDEN_RESOURCE_DOMAINS.any { host == it || host.endsWith(".$it") }) {
            return false
        }

        // Si el host es accounts.google.com, verificar que sea un subrecurso válido o endpoint de verificación
        if (host == ALLOWED_NAV_HOST) {
            val path = parsed.canonicalPath.lowercase(Locale.ROOT)
            return path.startsWith("/_/mss/") ||
                    path.startsWith("/_/js/") ||
                    path.startsWith("/devicephoneverification") ||
                    path.startsWith("/v3/signin/challenge") ||
                    path.startsWith("/signin/challenge") ||
                    path.startsWith("/checkcookie")
        }

        // Host exacto permitido
        if (host in ALLOWED_RESOURCE_HOSTS) return true

        // Sufijos permitidos
        return ALLOWED_RESOURCE_SUFFIXES.any { host.endsWith(it) }
    }

    /**
     * ¿La URL indica que la verificación terminó con éxito?
     *
     * ⚠️ SEGURIDAD CRÍTICA (P1-02):
     * Debe pertenecer OBLIGATORIAMENTE a una navegación permitida en accounts.google.com.
     * Jamás evalúa subcadenas sueltas en URLs externas.
     */
    fun isVerificationSuccess(url: String?): Boolean {
        if (url == null) return false
        // 1. Debe ser una URL de navegación permitida
        if (!isNavigationAllowed(url)) return false

        val parsed = parseStrictUri(url) ?: return false
        val pathLower = parsed.canonicalPath.lowercase(Locale.ROOT)
        val queryLower = parsed.rawQuery?.lowercase(Locale.ROOT) ?: ""

        return pathLower.endsWith("/success") ||
                pathLower.contains("/success/") ||
                pathLower.contains("/challenge/iap/success") ||
                queryLower.contains("flowcomplete=true") ||
                queryLower.contains("status=success")
    }
}
