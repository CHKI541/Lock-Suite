package com.ejemplo.locksuite.mdm

import java.net.URI
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
 * Al escanearlo, el celular no tiene con qué abrirlo.
 *
 * Esta clase es una FUNCIÓN PURA (sin Android Context ni dependencias del framework)
 * que garantiza que el WebView del microvisor solo pueda cargar y navegar en
 * pantallas legítimas de verificación de Google, bloqueando cualquier intento de
 * escape hacia búsquedas, YouTube, Mi Actividad o la web abierta.
 */
object GoogleVerificationPolicy {

    /** Host único y exacto al que se permite NAVEGAR */
    const val ALLOWED_NAV_HOST = "accounts.google.com"

    /**
     * Prefijos de ruta permitidos en [ALLOWED_NAV_HOST].
     * Coincidencia estricta por prefijo en minúsculas.
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
     * coincidieran por prefijo con algo general.
     */
    private val FORBIDDEN_PATH_PREFIXES = listOf(
        "/signup",          // Creación de nuevas cuentas
        "/createaccount",   // Creación de nuevas cuentas alternativa
        "/manageaccount",   // Gestión de cuenta / datos personales
        "/myaccount",       // Redirección a myaccount
        "/b/"               // Selector multi-cuenta que conduce a gestión general
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

    /**
     * Extrae de forma segura (scheme, host, path) sin depender de android.net.Uri
     * para que sea 100% testeable en pruebas unitarias puras en JVM.
     */
    fun parseUriParts(url: String?): UriParts? {
        if (url.isNullOrBlank()) return null
        val trimmed = url.trim()

        // 1. Intentar con java.net.URI
        try {
            val uri = URI(trimmed)
            val scheme = uri.scheme?.lowercase(Locale.ROOT)
            val host = uri.host?.lowercase(Locale.ROOT)
            val port = uri.port
            val path = if (scheme == "about" || scheme == "sms" || scheme == "tel") {
                uri.schemeSpecificPart ?: ""
            } else {
                uri.rawPath ?: ""
            }
            if (scheme != null) {
                return UriParts(scheme, host, path, port)
            }
        } catch (_: Exception) {
            // Continuar con fallback manual
        }

        // 2. Fallback de parseo robusto para URLs con caracteres especiales
        try {
            val schemeEnd = trimmed.indexOf(":")
            if (schemeEnd <= 0) return null
            val scheme = trimmed.substring(0, schemeEnd).lowercase(Locale.ROOT)
            val rest = trimmed.substring(schemeEnd + 1)

            if (scheme == "sms" || scheme == "tel" || scheme == "about") {
                return UriParts(scheme, host = null, path = rest, port = -1)
            }

            if (!rest.startsWith("//")) return null
            val afterSlashes = rest.substring(2)
            val pathSlash = afterSlashes.indexOf('/')
            val queryQuestion = afterSlashes.indexOf('?')
            val hashMark = afterSlashes.indexOf('#')

            val hostEnd = listOf(pathSlash, queryQuestion, hashMark)
                .filter { it >= 0 }
                .minOrNull() ?: afterSlashes.length

            val hostPort = afterSlashes.substring(0, hostEnd).lowercase(Locale.ROOT)
            val (host, port) = if (hostPort.contains(":")) {
                val colonIdx = hostPort.indexOf(":")
                val parsedHost = hostPort.substring(0, colonIdx)
                val parsedPort = hostPort.substring(colonIdx + 1).toIntOrNull() ?: -1
                Pair(parsedHost, parsedPort)
            } else {
                Pair(hostPort, -1)
            }

            val path = if (pathSlash >= 0) {
                val pathEnd = listOf(queryQuestion, hashMark)
                    .filter { it >= 0 && it > pathSlash }
                    .minOrNull() ?: afterSlashes.length
                afterSlashes.substring(pathSlash, pathEnd)
            } else {
                ""
            }

            return UriParts(scheme, host, path, port)
        } catch (_: Exception) {
            return null
        }
    }

    data class UriParts(
        val scheme: String,
        val host: String?,
        val path: String,
        val port: Int = -1
    )

    /**
     * ¿Puede el WebView NAVEGAR a esta URL?
     *
     * Reglas estrictas:
     * - Debe ser https://
     * - Host exacto accounts.google.com
     * - Puerto estándar 443 o no especificado (-1)
     * - Debe empezar con un prefijo permitido de verificación
     * - No debe coincidir con ningún prefijo prohibido
     */
    fun isNavigationAllowed(url: String?): Boolean {
        if (url == null) return false
        val parts = parseUriParts(url) ?: return false

        // Únicamente about:blank se usa internamente por el WebView al inicializar
        if (parts.scheme == "about") {
            return parts.path.lowercase(Locale.ROOT) == "blank"
        }

        // Solo https permitido para navegar
        if (parts.scheme != "https") return false

        // Puerto estándar HTTPS (443 o -1 omitido)
        if (parts.port != -1 && parts.port != 443) return false

        val host = parts.host ?: return false
        // Coincidencia exacta de host. Jamás sufijo (.google.com abriría media web).
        if (host != ALLOWED_NAV_HOST) return false

        val pathLower = parts.path.lowercase(Locale.ROOT)

        // Verificar que no sea una ruta prohibida
        if (FORBIDDEN_PATH_PREFIXES.any { pathLower.startsWith(it) }) {
            return false
        }

        // Verificar que empiece con un prefijo permitido
        return ALLOWED_PATH_PREFIXES.any { pathLower.startsWith(it) }
    }

    /**
     * ¿Es un esquema de acción nativa válido (SMS o Teléfono)?
     * Google a veces invoca sms: para verificar la SIM enviando un código
     * o tel: para recibir una llamada de verificación.
     */
    fun isSmsOrTelAction(url: String?): Boolean {
        if (url == null) return false
        val parts = parseUriParts(url) ?: return false
        return parts.scheme == "sms" || parts.scheme == "tel"
    }

    /**
     * ¿Puede la página descargar este subrecurso (JS, CSS, fuentes, imágenes de UI)?
     */
    fun isResourceAllowed(url: String?): Boolean {
        if (url == null) return false
        val parts = parseUriParts(url) ?: return false

        when (parts.scheme) {
            "https", "wss" -> Unit
            "data", "blob", "about" -> return true
            else -> return false
        }

        val host = parts.host ?: return false

        // Si pertenece a dominios prohibidos de contenido (YouTube, banners, etc.), fuera
        if (FORBIDDEN_RESOURCE_DOMAINS.any { host == it || host.endsWith(".$it") }) {
            return false
        }

        // Host exacto permitido
        if (host in ALLOWED_RESOURCE_HOSTS) return true

        // Sufijos permitidos
        return ALLOWED_RESOURCE_SUFFIXES.any { host.endsWith(it) }
    }

    /**
     * ¿La URL indica que la verificación terminó con éxito?
     */
    fun isVerificationSuccess(url: String?): Boolean {
        if (url == null) return false
        val lower = url.lowercase(Locale.ROOT)
        return lower.contains("/devicephoneverification/success") ||
                lower.contains("/challenge/iap/success") ||
                lower.contains("status=success") ||
                lower.contains("flowcomplete=true")
    }
}
