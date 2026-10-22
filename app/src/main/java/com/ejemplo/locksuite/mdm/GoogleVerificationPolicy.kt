package com.ejemplo.locksuite.mdm

import java.io.ByteArrayOutputStream
import java.net.URI
import java.nio.ByteBuffer
import java.nio.charset.CharacterCodingException
import java.nio.charset.CodingErrorAction
import java.nio.charset.StandardCharsets
import java.util.Locale

/**
 * GoogleVerificationPolicy — Motor de confinamiento hermético para el Microvisor
 * de Verificaciones de Google (B.92, 20/10/2026; revisado el 22/10/2026).
 *
 * ═════════════════════════════════════════════════════════════════════════════
 * QUÉ PROBLEMA RESUELVE
 * ═════════════════════════════════════════════════════════════════════════════
 * En un celular kosher administrado por LockSuite, los navegadores están
 * suspendidos para impedir la navegación libre por internet.
 *
 * Cuando el usuario inicia sesión o crea una cuenta en una computadora, Google puede
 * exigir un desafío escaneando un código QR con el teléfono para validar la línea:
 *   `https://accounts.google.com/devicephoneverification/begin?flow=browser&plt=...`
 *
 * Esta clase es una FUNCIÓN PURA (sin dependencias del framework de Android)
 * que garantiza que el WebView del microvisor solo pueda cargar y navegar en
 * pantallas legítimas de verificación de Google, bloqueando de forma absoluta
 * cualquier intento de escape hacia búsquedas, YouTube, Mi Actividad, creación
 * de cuentas o la web abierta.
 *
 * ═════════════════════════════════════════════════════════════════════════════
 * ⚠️ DOS COSAS QUE HAY QUE SABER ANTES DE TOCAR ESTO (22/10/2026)
 * ═════════════════════════════════════════════════════════════════════════════
 * 1. **Este WebView NO pasa por la Capa 2.** La VPN de DNS excluye a LockSuite de su
 *    propio túnel (`addDisallowedApplication(packageName)` en `KosherVpnService`), y
 *    la Capa 3 ignora los eventos del propio paquete. O sea que lo que decide esta
 *    clase es la ÚNICA barrera del microvisor. Ante la duda, se bloquea.
 *
 * 2. **El flujo del QR TERMINA EN UN SMS.** La página de Google lo dice textual:
 *    *"Your phone will open an SMS message with a code you need to send to verify your
 *    phone"* (`/devicephoneverification/consent`, botón "Send SMS"). El botón navega a
 *    un `sms:` con el número de Google y el código. La versión del 20/10 bloqueaba
 *    todo `sms:` como "navegación externa", así que **la verificación no podía
 *    terminar nunca**. Ahora el `sms:` NO es una navegación (sigue siendo `false` en
 *    [isNavigationAllowed]): es un pedido aparte, que solo se acepta desde una página
 *    de `/devicephoneverification` ([isSmsHandoffContext]), se valida entero
 *    ([parseSmsHandoff]) y se entrega a la app de Mensajes con el borrador armado. El
 *    usuario lo envía él mismo desde Mensajes: nada sale sin que lo vea.
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

    /** La raíz de la verificación por QR: es el único lugar desde donde se acepta un `sms:`. */
    private const val DEVICE_PHONE_VERIFICATION_ROOT = "/devicephoneverification"

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

    /**
     * Subrecursos (XHR, scripts, estilos, imágenes) que la página puede pedirle a
     * `accounts.google.com`. NO son navegación: la barra principal sigue atada a
     * [ALLOWED_PATH_PREFIXES].
     *
     * 22/10: la lista del 20/10 solo dejaba `/_/mss/`, `/_/js/` y los prefijos de
     * página. Pero las apps de Google (Wiz/boq) hablan con su servidor por
     * `<raíz de la app>/_/<App>/data/batchexecute`: el desafío v3 llama a
     * `/v3/signin/_/AccountsSignInUi/data/batchexecute`, que NO empieza con
     * `/v3/signin/challenge`, y recibía un 403 — la pantalla se dibujaba y no podía
     * responder. Ahora se permite la raíz de cada app confinada y el árbol `/_/`
     * (estáticos y RPC de Google). `/checkcookie` salió de esta lista: es un
     * redirector (`continue=`) y como subrecurso no hace falta para nada.
     */
    private val ALLOWED_ACCOUNTS_RESOURCE_ROOTS = listOf(
        "/_/",
        "/devicephoneverification/",
        "/v3/signin/",
        "/signin/challenge/",
        "/signin/recovery/",
        "/embedded/setup/",
        "/speedbump/"
    )

    /** Rutas exactas de servicio (en minúscula) que la UI de sesión pide como subrecurso. */
    private val ALLOWED_ACCOUNTS_RESOURCE_EXACT = setOf(
        "/devicephoneverification",
        "/generate_204",
        "/favicon.ico",
        "/listaccounts",
        "/rotatecookies"
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
     *
     * `sms:` devuelve `false` a propósito: no es una navegación del WebView. Ver
     * [parseSmsHandoff].
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

        // accounts.google.com: solo las raíces de las apps confinadas y el árbol `/_/`.
        if (host == ALLOWED_NAV_HOST) {
            val path = parsed.canonicalPath.lowercase(Locale.ROOT)
            if (path in ALLOWED_ACCOUNTS_RESOURCE_EXACT) return true
            return ALLOWED_ACCOUNTS_RESOURCE_ROOTS.any { path.startsWith(it) }
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
     *
     * Nota (22/10): la pantalla real de Google (`/devicephoneverification/verify`)
     * cambia de estado sin cambiar de URL, así que esto casi nunca va a disparar en el
     * flujo del QR. No es un problema: el usuario cierra con la X cuando la computadora
     * avanza. Lo que no puede pasar es lo contrario (un falso éxito fuera de Google).
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

    // ─────────────────────────────────────────────────────────────────────────
    // SMS de verificación (22/10/2026)
    // ─────────────────────────────────────────────────────────────────────────

    /** Un SMS ya armado por Google, listo para dejarlo como borrador en Mensajes. */
    data class SmsHandoff(val recipient: String, val body: String)

    /** Un código de verificación entra holgado; un texto largo no es un código. */
    private const val SMS_MAX_BODY_CHARS = 320

    /** E.164 admite hasta 15 dígitos; los números cortos de servicio tienen 3 o más. */
    private const val SMS_MIN_DIGITS = 3
    private const val SMS_MAX_DIGITS = 15

    /**
     * ¿Se puede aceptar un `sms:` estando el WebView en esta página?
     *
     * Solo desde la verificación por QR (`/devicephoneverification` y lo que cuelga de
     * ahí), y solo si la página misma es una navegación permitida. Cualquier otra
     * pantalla —un desafío de inicio de sesión, `about:blank`, lo que sea— no puede
     * abrir Mensajes.
     */
    fun isSmsHandoffContext(pageUrl: String?): Boolean {
        if (!isNavigationAllowed(pageUrl)) return false
        val parsed = parseStrictUri(pageUrl) ?: return false
        if (parsed.host != ALLOWED_NAV_HOST) return false
        val path = parsed.canonicalPath.lowercase(Locale.ROOT)
        return path == DEVICE_PHONE_VERIFICATION_ROOT ||
                path.startsWith("$DEVICE_PHONE_VERIFICATION_ROOT/")
    }

    /**
     * Valida un `sms:`/`smsto:` (RFC 5724) y devuelve destinatario y texto, o `null`.
     *
     * Formatos aceptados: `sms:+15551234567?body=ABC`, `smsto:+1555…?body=…`,
     * `sms:+1555…;?&body=…` y `sms:+1555…&body=…` (los de iOS), `sms://+1555…?body=…`
     * y `intent://+1555…#Intent;scheme=smsto;S.sms_body=…;end` (de ese intent se usan
     * solo número y texto). Cómo arma Google el suyo no se pudo observar desde acá: por
     * eso se aceptan todas las formas conocidas, con las mismas reglas.
     *
     * Reglas, todas a propósito:
     * - **Un solo destinatario** de 3 a 15 dígitos (con `+` adelante opcional; los
     *   separadores visuales `-`, `.`, `(`, `)` se sacan). Una lista con comas se rechaza.
     * - El texto se decodifica por porcentaje **sin** convertir `+` en espacio
     *   (`URLDecoder` sí lo hace, y un código con `+` llegaría cambiado a Google, que
     *   entonces no verifica nada).
     * - Texto de hasta [SMS_MAX_BODY_CHARS] caracteres y sin caracteres de control.
     * - Cualquier escape mal formado o UTF-8 inválido: `null`.
     */
    fun parseSmsHandoff(url: String?): SmsHandoff? {
        if (url.isNullOrBlank()) return null
        val s = url.trim()
        if (s.length > 2048) return null
        for (ch in s) {
            if (ch < ' ' || ch == '\u007f') return null
        }

        val colon = s.indexOf(':')
        if (colon <= 0) return null
        val scheme = s.substring(0, colon).lowercase(Locale.ROOT)
        if (scheme == "intent") return parseIntentSmsHandoff(s.substring(colon + 1))
        if (scheme != "sms" && scheme != "smsto") return null

        var rest = s.substring(colon + 1)
        if (rest.startsWith("//")) rest = rest.substring(2)
        return parseSmsParts(rest, extraBody = null)
    }

    /**
     * `intent:` que en realidad es un SMS (`…#Intent;scheme=smsto;S.sms_body=…;end`):
     * algunas páginas abren Mensajes así en Android. Del intent se usan SOLO el número y
     * el texto; paquete, componente, acción y cualquier otro extra se descartan, y lo que
     * se abre después es el mismo borrador en la app de SMS predeterminada.
     */
    private fun parseIntentSmsHandoff(afterScheme: String): SmsHandoff? {
        val hash = afterScheme.indexOf("#Intent;")
        if (hash < 0 || !afterScheme.endsWith(";end")) return null
        var ssp = afterScheme.substring(0, hash)
        if (ssp.startsWith("//")) ssp = ssp.substring(2)
        val fields = afterScheme.substring(hash + "#Intent;".length, afterScheme.length - ";end".length)
        var innerScheme: String? = null
        var body: String? = null
        for (field in fields.split(';')) {
            when {
                field.startsWith("scheme=") -> innerScheme = field.substring("scheme=".length).lowercase(Locale.ROOT)
                field.startsWith("S.sms_body=") -> body = percentDecode(field.substring("S.sms_body=".length)) ?: return null
            }
        }
        if (innerScheme != "sms" && innerScheme != "smsto") return null
        return parseSmsParts(ssp, extraBody = body)
    }

    /** Destinatario + query de un `sms:` ya sin el esquema. */
    private fun parseSmsParts(rest: String, extraBody: String?): SmsHandoff? {
        // `?body=` es lo estándar; `&body=` sin `?` es la variante de iOS 8+.
        val q = rest.indexOf('?').let { if (it >= 0) it else rest.indexOf('&') }
        val recipientPart = if (q >= 0) rest.substring(0, q) else rest
        val query = if (q >= 0) rest.substring(q + 1) else ""

        // `;via=...` / `;?&body=` (iOS): todo lo que va después de ';' no es el número.
        val semi = recipientPart.indexOf(';')
        val rawRecipient = if (semi >= 0) recipientPart.substring(0, semi) else recipientPart

        val decodedRecipient = percentDecode(rawRecipient) ?: return null
        if (decodedRecipient.contains(',')) return null
        val recipient = normalizeRecipient(decodedRecipient) ?: return null

        var body = extraBody ?: ""
        if (extraBody == null && query.isNotEmpty()) {
            for (param in query.split('&')) {
                if (param.isEmpty()) continue
                val eq = param.indexOf('=')
                val key = (if (eq >= 0) param.substring(0, eq) else param).lowercase(Locale.ROOT)
                if (key == "body" || key == "sms_body") {
                    body = percentDecode(if (eq >= 0) param.substring(eq + 1) else "") ?: return null
                    break
                }
            }
        }

        if (body.length > SMS_MAX_BODY_CHARS) return null
        for (ch in body) {
            if (ch < ' ' || ch == '\u007f') return null
        }

        return SmsHandoff(recipient, body)
    }

    private fun normalizeRecipient(raw: String): String? {
        val compact = StringBuilder()
        for (ch in raw.trim()) {
            when (ch) {
                ' ', '-', '.', '(', ')' -> continue
                else -> compact.append(ch)
            }
        }
        val s = compact.toString()
        val digits = if (s.startsWith("+")) s.substring(1) else s
        if (digits.length < SMS_MIN_DIGITS || digits.length > SMS_MAX_DIGITS) return null
        if (!digits.all { it in '0'..'9' }) return null
        return s
    }

    /** Decodificación `%XX` estricta, en UTF-8, que deja el `+` como `+`. */
    private fun percentDecode(s: String): String? {
        if (s.indexOf('%') < 0) return s
        val out = ByteArrayOutputStream(s.length)
        var i = 0
        while (i < s.length) {
            val ch = s[i]
            if (ch == '%') {
                if (i + 2 >= s.length) return null
                val hi = hexValue(s[i + 1])
                val lo = hexValue(s[i + 2])
                if (hi < 0 || lo < 0) return null
                out.write((hi shl 4) or lo)
                i += 3
            } else {
                val bytes = ch.toString().toByteArray(StandardCharsets.UTF_8)
                out.write(bytes, 0, bytes.size)
                i++
            }
        }
        return try {
            StandardCharsets.UTF_8.newDecoder()
                .onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT)
                .decode(ByteBuffer.wrap(out.toByteArray()))
                .toString()
        } catch (_: CharacterCodingException) {
            null
        }
    }

    /** Solo ASCII: `Character.digit` acepta también dígitos de otros alfabetos. */
    private fun hexValue(c: Char): Int = when (c) {
        in '0'..'9' -> c - '0'
        in 'a'..'f' -> c - 'a' + 10
        in 'A'..'F' -> c - 'A' + 10
        else -> -1
    }

    // ─────────────────────────────────────────────────────────────────────────
    // Utilidades (22/10/2026)
    // ─────────────────────────────────────────────────────────────────────────

    /**
     * La FORMA de un pedido de SMS rechazado, sin su contenido: dígitos → `9`, letras →
     * `x`, el resto (`:`, `?`, `=`, `&`, `;`, `%`, `#`…) tal cual. Ejemplo:
     * `sms:+16505550123?body=G-123` → `sms:+99999999999?xxxx=x-999`. Alcanza para ver en
     * `logcat` qué formato usó Google si alguna vez no se reconoce, sin anotar el código.
     */
    fun describeSmsShapeForLog(url: String?): String {
        if (url.isNullOrBlank()) return "(vacía)"
        val s = url.trim()
        val colon = s.indexOf(':')
        if (colon <= 0) return "(inválida)"
        val out = StringBuilder(s.substring(0, colon).lowercase(Locale.ROOT)).append(':')
        for (ch in s.substring(colon + 1).take(160)) {
            out.append(
                when {
                    ch in '0'..'9' -> '9'
                    ch.isLetter() -> 'x'
                    ch < ' ' -> '?'
                    else -> ch
                }
            )
        }
        return out.toString()
    }

    /** Versión de Chrome que se usa si el WebView no publica la suya. */
    private const val FALLBACK_CHROME_MAJOR = 130

    /**
     * User-Agent de Chrome para Android, con el formato "reducido" que manda Chrome
     * desde la versión 110 (`Linux; Android 10; K`), y con la versión REAL del
     * WebView del equipo.
     *
     * Antes estaba fijo en `Chrome/120.0.0.0 … LockSuiteVerification`: cada mes más
     * viejo (Google empieza a avisar "navegador desactualizado"), y con una marca
     * propia que ningún navegador real lleva. Se le saca a la versión del WebView el
     * `; wv` y el `Version/4.0`, que son justamente lo que delata un WebView.
     */
    fun chromeLikeUserAgent(defaultWebViewUa: String?): String {
        val major = defaultWebViewUa
            ?.let { Regex("Chrome/(\\d{2,4})\\.").find(it)?.groupValues?.getOrNull(1) }
            ?.toIntOrNull()
            ?.takeIf { it in 60..999 }
            ?: FALLBACK_CHROME_MAJOR
        return "Mozilla/5.0 (Linux; Android 10; K) AppleWebKit/537.36 (KHTML, like Gecko) " +
                "Chrome/$major.0.0.0 Mobile Safari/537.36"
    }

    /**
     * `esquema://host/ruta`, sin query ni fragmento, para logcat y para el registro
     * que ve el panel. **Nunca** se anota la query: ahí viajan los tokens de Google
     * (`plt`, `TL`, `dsh`) y el código del SMS.
     */
    fun describeForLog(url: String?): String {
        if (url.isNullOrBlank()) return "(vacía)"
        val s = url.trim()
        val colon = s.indexOf(':')
        if (colon <= 0) return "(inválida)"
        val scheme = s.substring(0, colon).lowercase(Locale.ROOT)
        if (scheme == "sms" || scheme == "smsto" || scheme == "tel" || scheme == "mailto" ||
            scheme == "data" || scheme == "javascript" || scheme == "blob"
        ) {
            return "$scheme:(oculto)"
        }
        val cut = s.indexOfAny(charArrayOf('?', '#')).let { if (it >= 0) s.substring(0, it) else s }
        return cut.take(120)
    }
}
