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

    /**
     * Marcadores de nombre de clase, comparados en MINÚSCULA por `contains`.
     *
     * Se eligió el segmento de PAQUETE del módulo y no el nombre de la Activity
     * concreta: `com.google.android.gms.accountsettings.mg.ui.main.MainActivity` es
     * el nombre de hoy, pero el segmento `accountsettings` es lo que identifica al
     * módulo y sobrevive a que Google reorganice las clases de adentro.
     */
    private val CLASS_MARKERS = listOf(
        // "Gestionar tu cuenta de Google" → Datos y privacidad → historiales.
        // ES EL CAMINO EXACTO QUE REPORTÓ EL DUEÑO.
        "accountsettings",
        // Ajustes → Google (com.google.android.gms.app.settings.GoogleSettingsActivity):
        // es la PUERTA a todo lo anterior, así que se rebota también.
        "googlesettings",
        // Configuración de anuncios: muestra intereses inferidos, con imágenes.
        "adssettings",
        // Uso y diagnóstico. Va en la misma familia de módulos; rebotarlo no cuesta nada.
        "usagereporting",
        // Por si algún fabricante nombra la pantalla por su URL.
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
    private val BLOCKED_SUFFIXES = listOf(
        // El agujero reportado
        "myaccount.google.com",     // "Gestionar tu cuenta de Google" (la UI entera es web)
        "myactivity.google.com",    // Mi Actividad: historial de YouTube, búsquedas, etc.
        "history.google.com",       // nombre viejo, sigue redirigiendo a Mi Actividad
        // Vecinos del mismo camino, alcanzables desde "Datos y privacidad"
        "takeout.google.com",       // "Descargar tus datos": exporta y muestra
        "timeline.google.com",      // Cronología de ubicaciones (con fotos)
        "adssettings.google.com",   // intereses publicitarios
        "photos.google.com",        // "Tus fotos" desde la cuenta
        // Miniaturas de YouTube. Es un dominio de imágenes de YouTube y nada más, así
        // que bloquearlo no puede romper otra cosa; mata las miniaturas también dentro
        // de cualquier otro WebView o resultado de búsqueda que las incruste.
        "ytimg.com"
    )

    /**
     * ¿Vale la pena mirar el nombre de clase de una ventana de este paquete?
     * Se llama en el camino caliente, así que es una comparación de strings y nada más.
     */
    fun isCandidatePackage(packageName: String): Boolean =
        packageName == PKG_GMS ||
        packageName == "com.android.settings" ||
        packageName.endsWith(".settings")

    /** ¿Esta ventana es una de las pantallas web de la cuenta de Google? */
    fun isAccountWebClass(className: String?): Boolean {
        if (className.isNullOrEmpty()) return false
        val lower = className.lowercase()
        if (CLASS_EXCLUSIONS.any { lower.contains(it) }) return false
        return CLASS_MARKERS.any { lower.contains(it) }
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
    fun isBlockedHost(domain: String?): Boolean {
        if (domain.isNullOrEmpty()) return false
        val d = domain.lowercase().trimEnd('.')
        if (d.isEmpty()) return false
        return BLOCKED_SUFFIXES.any { d == it || d.endsWith(".$it") }
    }

    /** Solo para mostrar en el panel qué cierra el interruptor. */
    fun blockedSuffixes(): List<String> = BLOCKED_SUFFIXES
}
