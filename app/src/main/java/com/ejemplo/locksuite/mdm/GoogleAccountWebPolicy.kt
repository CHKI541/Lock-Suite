package com.ejemplo.locksuite.mdm

/**
 * GoogleAccountWebPolicy — el agujero de "Ajustes de la cuenta de Google" (4/9/2026).
 *
 * ─────────────────────────────────────────────────────────────────────────────
 * QUÉ AGUJERO CIERRA, DICHO COMO LO ENCONTRÓ EL DUEÑO
 * ─────────────────────────────────────────────────────────────────────────────
 *
 * Textual: *"estuve viendo los ajustes de mi cuenta de Google en mi celular, y al
 * entrar a ajustes de privacidad logré entrar a mi historial de YouTube y ver los
 * videos"*.
 *
 * El camino es: Ajustes → Google → Gestionar tu cuenta de Google → Datos y
 * privacidad → Configuración del historial → Historial de YouTube → "Administrar
 * toda la actividad". Ahí se ven los videos vistos, con título y miniatura. Lo
 * mismo pasa con "Actividad web y de aplicaciones" (muestra TODAS las búsquedas
 * que hizo el usuario, y las deja repetir con un toque) y con "Cronología"
 * (ubicaciones, con fotos).
 *
 * ─────────────────────────────────────────────────────────────────────────────
 * POR QUÉ NINGUNA DE LAS TRES CAPAS LO VEÍA — Y ES LO IMPORTANTE DE ESTE ARCHIVO
 * ─────────────────────────────────────────────────────────────────────────────
 *
 * No es un descuido puntual, es un hueco de forma. Las tres capas de LockSuite
 * miran cosas distintas y ninguna miraba ESTA:
 *
 *   • CAPA 1 (DevicePolicyManager). Android **no tiene** ninguna restricción
 *     `DISALLOW_*` para "ver los ajustes de la cuenta de Google", y un Device Owner
 *     tampoco puede deshabilitar una Activity de OTRA app
 *     (`setComponentEnabledSetting` solo rige sobre el propio paquete). O sea que
 *     por Capa 1 esto no se cierra. Hay que decirlo así, sin adornos.
 *     `DISALLOW_MODIFY_ACCOUNTS` impide AGREGAR o QUITAR cuentas — no impide VERLAS.
 *
 *   • CAPA 2 (DNS). El filtro solo bloquea un dominio si hay una regla para él, y
 *     nadie había puesto reglas para `myaccount.google.com` / `myactivity.google.com`.
 *     Encima la lista negra de WebView (`WebViewPolicy.GLOBAL_BLACKLIST`) SOLO se
 *     aplica a apps que tengan el bloqueo de WebView encendido a mano, y
 *     `com.google.android.gms` no lo tiene (ni conviene: rompería el inicio de sesión).
 *
 *   • CAPA 3 (Accesibilidad). Es una lista de apps CONOCIDAS: WhatsApp, Mercado Pago,
 *     Play Store. Google Play services nunca estuvo en esa lista.
 *
 * **La lección generalizable, que vale más que este parche:** el contenido web no
 * llega solo por un navegador. Llega por CUALQUIER app del sistema que tenga un
 * WebView adentro y una página de Google del otro lado. Play services es la más
 * grande, pero no es la única (ver el análisis de la sesión del 4/9 en el contexto).
 *
 * ─────────────────────────────────────────────────────────────────────────────
 * CÓMO SE CIERRA: DOS CAPAS, A PROPÓSITO
 * ─────────────────────────────────────────────────────────────────────────────
 *
 *   CAPA 3 — rebote por nombre de clase de la ventana. Es instantáneo y el usuario
 *   ni llega a ver la pantalla. Es la que da la buena experiencia.
 *
 *   CAPA 2 — bloqueo de los dominios que sirven ese contenido. Es la que de verdad
 *   cierra: no depende del idioma, ni del fabricante, ni de que el servicio de
 *   Accesibilidad esté prendido, ni de que Google no le cambie el nombre a la
 *   Activity mañana. Si la Capa 3 falla, la pantalla carga vacía.
 *
 * ─────────────────────────────────────────────────────────────────────────────
 * DOS MODOS  (corrección del 4/9 a la tarde — la primera versión bloqueaba de más)
 * ─────────────────────────────────────────────────────────────────────────────
 *
 * Reporte del dueño horas después de instalar la primera versión: *"ahora no puedo
 * entrar a toda la configuración de mi cuenta de Google, ¿no hay manera de bloquear
 * solo lo problemático?"*. Tenía razón, y el motivo es concreto:
 *
 *   `com.google.android.gms.accountsettings.*` es **UNA SOLA Activity** que dibuja
 *   todo "Gestionar tu cuenta de Google". Ir de "Seguridad" a "Historial de YouTube"
 *   NO cambia de Activity: pasa dentro de la misma ventana. Entonces **rebotar por
 *   nombre de clase es todo o nada**, y la primera versión eligió "todo".
 *
 * Donde el corte fino SÍ existe es en la Capa 2, porque las dos mitades vienen de
 * dominios distintos: el historial de `myactivity.google.com` y la administración de
 * la cuenta de `myaccount.google.com`. De ahí salen los dos modos:
 *
 * | | NORMAL (por defecto) | ESTRICTO |
 * |---|---|---|
 * | Administrar la cuenta (datos, seguridad, dispositivos, pagos) | **funciona** | bloqueado |
 * | Historial de YouTube / Mi Actividad / Cronología / Takeout | bloqueado | bloqueado |
 * | Configuración de anuncios y Uso y diagnóstico | rebotados | rebotados |
 * | Cómo lo cierra | solo Capa 2 (dominios) | Capa 2 + rebote de la pantalla |
 *
 * **Por qué NORMAL es el valor por defecto:** cierra lo que el dueño reportó y deja
 * el equipo administrable. ESTRICTO es para un equipo que no tiene por qué mostrar
 * ninguna pantalla de cuenta de Google — es más seguro y menos usable, y esa decisión
 * es del administrador, no nuestra.
 *
 * **Honestidad sobre el modo normal:** el corte depende de que el módulo traiga el
 * historial desde `myactivity.google.com`. Si en la prueba el historial igual se ve,
 * la respuesta no es mover `myaccount` al modo normal (eso vuelve al problema de hoy)
 * sino aceptar que ahí los dos modos son el mismo. Cómo medirlo está en el documento
 * de instrucciones.
 *
 * Esta división es deliberada y sigue el mismo criterio que B.19: **si hay una señal
 * estructural, usarla antes que una palabra**. Acá no se compara ni un solo texto de
 * pantalla, así que cambiar el idioma del equipo no evade nada de esto.
 *
 * ─────────────────────────────────────────────────────────────────────────────
 * LÍMITES REALES (para no vender de más)
 * ─────────────────────────────────────────────────────────────────────────────
 *
 *  1. El filtro DNS solo ve UDP/53. Si alguna vez Play services resolviera por
 *     DoH/DoT por su cuenta, este bloqueo no lo vería. Contra el DNS privado que
 *     configura el USUARIO ya está `DISALLOW_CONFIG_PRIVATE_DNS` (B.33); contra el
 *     que pudiera usar Google internamente no hay defensa desde una app.
 *  2. El rebote de Capa 3 necesita que la Accesibilidad esté activa (B.15).
 *  3. Los nombres de clase de Play services cambian con las versiones del módulo
 *     (Chimera). Por eso el servicio publica al panel las clases que VIO y no supo
 *     clasificar (`googleAccountWebSeenClasses`): si en un equipo el rebote no
 *     dispara, ahí está el dato exacto para agregar, en vez de adivinar. Es la misma
 *     idea que `debugLabels` de B.41.
 */
object GoogleAccountWebPolicy {

    /** Paquetes que pueden alojar estas pantallas. */
    const val PKG_GMS = "com.google.android.gms"

    /** Clave de la preferencia que guarda el modo. */
    const val KEY_MODE = "google_account_block_mode"

    /** Deja administrar la cuenta; bloquea historial, actividad y cronología. */
    const val MODE_NORMAL = "normal"

    /** No deja abrir ninguna pantalla de la cuenta de Google en el equipo. */
    const val MODE_STRICT = "strict"

    /** Normaliza cualquier valor guardado a uno de los dos modos válidos. */
    fun isStrict(mode: String?): Boolean = mode == MODE_STRICT

    /**
     * Marcadores de nombre de clase, comparados en MINÚSCULA por `contains`.
     *
     * Se eligió el segmento de PAQUETE del módulo y no el nombre de la Activity
     * concreta: `com.google.android.gms.accountsettings.mg.ui.main.MainActivity` es
     * el nombre de hoy, pero el segmento `accountsettings` es lo que identifica al
     * módulo y sobrevive a que Google reorganice las clases de adentro.
     */
    private val CLASS_MARKERS_NORMAL = listOf(
        // Configuración de anuncios: muestra intereses inferidos, con imágenes, y es
        // una Activity propia — se puede rebotar sin llevarse puesto el resto.
        "adssettings",
        // Uso y diagnóstico. Misma familia de módulos, Activity propia, rebotarlo no
        // cuesta nada y no es parte de administrar la cuenta.
        "usagereporting"
    )

    /**
     * Solo en modo ESTRICTO. Acá está la razón de que existan dos modos:
     *
     * `com.google.android.gms.accountsettings.*` es **una sola Activity** que dibuja
     * TODO "Gestionar tu cuenta de Google" — datos personales, seguridad, dispositivos,
     * pagos, contraseñas — y también las pantallas de historial. Navegar de "Seguridad"
     * a "Historial de YouTube" NO cambia de Activity: pasa dentro de la misma ventana.
     *
     * O sea que **el nombre de clase no puede distinguir la parte problemática de la
     * legítima**: rebotar por clase es todo o nada. En la primera versión (4/9, mañana)
     * quedó en "todo", y el dueño reportó al toque que ya no podía entrar a NINGUNA
     * configuración de su cuenta. Tenía razón.
     *
     * Por eso en modo normal esto NO se rebota y el trabajo lo hace la Capa 2, que sí
     * distingue: el historial se sirve desde `myactivity.google.com` y el resto de la
     * cuenta desde `myaccount.google.com`.
     */
    private val CLASS_MARKERS_STRICT = listOf(
        "accountsettings",
        "googlesettings",
        "myaccount",
        "myactivity"
    )

    /**
     * Nunca rebotar, aunque hubiera coincidido un marcador.
     *
     * El flujo de AGREGAR una cuenta de Google es un WebView de Play services
     * (`...auth.uiflows.minutemaid.MinuteMaidActivity` renderiza accounts.google.com).
     * Si esto rebotara ahí, el equipo no se podría dar de alta — y LockSuite se
     * instala justamente con el celular SIN cuenta de Google, así que ese flujo es
     * parte del procedimiento normal de instalación, no un caso raro.
     *
     * Es la misma clase de error que el bug 3 de B.41 ("Iniciar sesión" leído como
     * "Abrir"): la pantalla de alta de cuenta es el estado de fábrica, no una rareza.
     */
    private val CLASS_EXCLUSIONS = listOf(
        "minutemaid",
        "signin",
        "auth.uiflows",
        "authzen",
        "setupwizard",
        "consent"
    )

    /**
     * Dominios que sirven este contenido. Se bloquean como lista negra GLOBAL
     * (igual que `block_gifs`), no como regla por app: las consultas DNS de Android
     * salen casi siempre por `netd` y NO se pueden atribuir a la app que las pidió
     * (está documentado en B.10), así que una regla por app no regiría acá.
     *
     * Lo que NO está en esta lista, y no hay que agregar sin pensarlo dos veces:
     *   • `accounts.google.com`      — inicio de sesión. Sin esto no se da de alta el equipo
     *                                  ni entra la app de administración (`:admin-app`).
     *   • `play.google.com`, `android.clients.google.com` — Play Store y actualizaciones.
     *   • `mtalk.google.com`, `fcm.googleapis.com`        — FCM: es el canal de comandos
     *                                  del panel. Bloquearlo deja el equipo inadministrable.
     *   • `googleapis.com`, `gstatic.com`, `googleusercontent.com` — infraestructura
     *                                  compartida; tirarla abajo rompe medio sistema.
     */
    private val BLOCKED_NORMAL = listOf(
        // ── EL CONTENIDO, que es lo que hay que cerrar ──
        "myactivity.google.com",    // Mi Actividad: historial de YouTube, búsquedas, etc.
        "history.google.com",       // nombre viejo, sigue redirigiendo a Mi Actividad
        "timeline.google.com",      // Cronología de ubicaciones (con fotos)
        "takeout.google.com",       // "Descargar tus datos": exporta y muestra
        "adssettings.google.com",   // intereses publicitarios, con imágenes
        "photos.google.com",        // "Tus fotos" desde la cuenta
        // Miniaturas de YouTube. Es un dominio de imágenes de YouTube y nada más, así
        // que bloquearlo no puede romper otra cosa; mata las miniaturas también dentro
        // de cualquier otro WebView o resultado de búsqueda que las incruste.
        "ytimg.com"
    )

    /**
     * Solo en modo ESTRICTO.
     *
     * `myaccount.google.com` sirve **la interfaz entera** de "Gestionar tu cuenta de
     * Google", no solo las pantallas de historial. Bloquearlo deja al administrador sin
     * poder cambiar la contraseña, revisar dispositivos, tocar la verificación en dos
     * pasos ni ver los datos de la cuenta **desde el equipo**. Eso es exactamente lo que
     * reportó el dueño el 4/9 y es lo que separa los dos modos.
     *
     * ⚠️ **Si en la prueba resultara que el historial de YouTube igual se ve en modo
     * normal**, quiere decir que el módulo trae ese contenido desde `myaccount` y no
     * desde `myactivity`. En ese caso la respuesta NO es mover esta línea a
     * `BLOCKED_NORMAL` —eso vuelve al problema de hoy— sino aceptar que ahí los dos
     * modos son el mismo y que el corte fino no existe. La forma de medirlo está en el
     * documento de instrucciones: abrir la pantalla y mirar qué dominios consulta.
     */
    private val BLOCKED_STRICT = listOf(
        "myaccount.google.com"
    )

    /**
     * ¿Vale la pena mirar el nombre de clase de una ventana de este paquete?
     * Se llama en el camino caliente, así que es una comparación de strings y nada más.
     */
    fun isCandidatePackage(packageName: String): Boolean =
        packageName == PKG_GMS ||
        packageName == "com.android.settings" ||
        packageName.endsWith(".settings")

    /**
     * ¿Esta ventana es una de las pantallas web de la cuenta de Google que hay que
     * rebotar en el modo pedido?
     *
     * @param strict `true` = modo estricto (rebota TODO "Gestionar tu cuenta de Google"
     *   y el hub de Ajustes → Google). `false` = modo normal: solo las pantallas que
     *   tienen Activity propia y no son parte de administrar la cuenta.
     */
    fun isAccountWebClass(className: String?, strict: Boolean): Boolean {
        if (className.isNullOrEmpty()) return false
        val lower = className.lowercase()
        if (CLASS_EXCLUSIONS.any { lower.contains(it) }) return false
        if (CLASS_MARKERS_NORMAL.any { lower.contains(it) }) return true
        return strict && CLASS_MARKERS_STRICT.any { lower.contains(it) }
    }

    /**
     * ¿Este dominio hay que bloquear?
     *
     * Normaliza a minúscula y saca el punto final antes de comparar.
     *
     * Hoy el que llama ya normaliza —`KosherVpnService` hace
     * `extractQueriedDomain(...)?.lowercase()?.trimEnd('.')` desde el commit `547cdeb`
     * (0.6.39), que es lo que cierra el hallazgo 4 de B.40— así que acá es redundante.
     * Se hace igual, a propósito, por dos motivos: la función queda correcta si algún
     * día la llama otro lado que no normalice, y el costo es cero comparado con el
     * resto del camino de una consulta DNS. `MyActivity.Google.COM` no evade esto.
     */
    fun isBlockedHost(domain: String?, strict: Boolean): Boolean {
        if (domain.isNullOrEmpty()) return false
        val d = domain.lowercase().trimEnd('.')
        if (d.isEmpty()) return false
        if (BLOCKED_NORMAL.any { d == it || d.endsWith(".$it") }) return true
        return strict && BLOCKED_STRICT.any { d == it || d.endsWith(".$it") }
    }

    /** Solo para mostrar en el panel qué cierra cada modo. */
    fun blockedSuffixes(strict: Boolean): List<String> =
        if (strict) BLOCKED_NORMAL + BLOCKED_STRICT else BLOCKED_NORMAL
}
