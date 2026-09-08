package com.ejemplo.locksuite.mdm

/**
 * CaptivePortalPolicy — la ventana de "Iniciar sesión en la red" (5/9/2026).
 *
 * ═════════════════════════════════════════════════════════════════════════════
 * EL HALLAZGO QUE CAMBIA TODO EL PLANTEO: EL PORTAL CAUTIVO NO PASA POR LA VPN
 * ═════════════════════════════════════════════════════════════════════════════
 *
 * `CaptivePortalLoginActivity` —el WebView de "Iniciar sesión en la red Wi-Fi"—
 * llama a **`ConnectivityManager.bindProcessToNetwork(mNetwork)`** dentro de
 * `initializeWebView()`, y cuando usa Custom Tabs llama además a
 * `bypassVpnForCustomTabsProvider()` (que usa `setDelegateUid()` por reflexión).
 *
 * O sea: **fija su tráfico a la red física del hotel y esquiva cualquier VPN, por
 * diseño de Android.** Tiene que hacerlo — si su tráfico saliera por la VPN, no
 * podría hablar con el portal, que solo existe dentro de esa red sin validar.
 *
 * **Consecuencia directa, y hay que decirla sin adornos: la Capa 2 de LockSuite no
 * puede ver ni bloquear NADA de lo que pase en esa ventana.** Ni una consulta DNS
 * llega al túnel. Cualquier lista de dominios pensada para el portal cautivo es
 * código muerto, por más correcta que sea la lista.
 *
 * *(La versión anterior de esta protección tenía además un segundo motivo para no
 * ejecutarse nunca: vivía detrás de `logPackage != "desconocido"`, y las consultas
 * DNS de Android salen por `netd`, no por la app — el problema de atribución que
 * ya está documentado en B.10.)*
 *
 * ═════════════════════════════════════════════════════════════════════════════
 * ENTONCES, ¿QUÉ SÍ SE PUEDE HACER? SOLO CAPA 3, Y ESTO ES LO QUE HAY
 * ═════════════════════════════════════════════════════════════════════════════
 *
 * El objetivo del dueño: *"bloquear navegación por portal cautivo, sin bloquear al
 * usuario a que se conecte a la red"*. Las tres palancas que quedan, todas
 * estructurales (nada de leer texto de pantalla, así que el idioma no las evade):
 *
 *  1. **Tapar las imágenes mientras la ventana está abierta.** Queda el texto y los
 *     formularios —o sea que iniciar sesión sigue funcionando— pero deja de ser un
 *     visor de contenido. Reusa el tapado de imágenes que ya existe (Capa 1).
 *
 *  2. **Cerrarla apenas la red valida.** Es el momento exacto en que la ventana dejó
 *     de tener una razón para existir: el usuario ya está conectado. Se detecta con
 *     `NET_CAPABILITY_VALIDATED` y la ausencia de `NET_CAPABILITY_CAPTIVE_PORTAL`
 *     sobre la red Wi-Fi — dos banderas del sistema, no una heurística.
 *
 *  3. **Tope de tiempo duro.** Un login legítimo tarda menos de un minuto; el tope
 *     está en tres. Cubre el caso de un portal que nunca valida y la ventana queda
 *     abierta a propósito.
 *
 * **Lo que NO cubre, dicho de frente:** mientras la ventana está abierta y antes de
 * validar, el dominio del propio portal es alcanzable —tiene que serlo— así que un
 * portal que sirva contenido en su propia página se ve igual. No hay forma de
 * distinguirlo desde afuera sin leer la URL, y la ventana no la publica. Por eso la
 * cuarta pata de esto es **visibilidad**: se reporta al panel cuántas veces se abrió
 * y cuánto tiempo estuvo abierta, para que el administrador VEA si alguien la está
 * usando de navegador aunque no se pueda impedir del todo.
 */
object CaptivePortalPolicy {

    // ═════════════════════════════════════════════════════════════════════════════
    // 8/9/2026 — CORRECCIÓN, CON EL REPORTE DEL PRIMER USUARIO QUE PISÓ UN PORTAL
    // REAL. Leer esto antes de tocar nada de acá abajo.
    // ═════════════════════════════════════════════════════════════════════════════
    //
    // Reporte, textual: *"estoy en el aeropuerto y cuando me conecto al wifi hay
    // [una página] que tengo que aceptar desde la página, se me conecta pero a los
    // dos minutos se desconecta, entonces tengo que entrar, conectar de vuelta,
    // todo. En el avión había wifi gratis, pero como de vuelta tiene que entrar por
    // la página. ¿Hay una forma?"*
    //
    // Dos cosas de la versión del 5/9 estaban mal, y las dos por el mismo motivo:
    // describían lo que se quiso hacer, no lo que el código hace.
    //
    //  1. **"Se tapan las imágenes; queda el texto y los formularios, o sea que
    //     iniciar sesión sigue funcionando."** Es falso. La palanca reusa el tapado
    //     de Capa 1, y la lista de nodos que la Capa 1 tapa (`visualNodeClassNames`
    //     en el servicio de accesibilidad) incluye **`android.webkit.WebView`** —
    //     y el contenido entero de esta ventana ES un WebView. O sea que la Capa 1
    //     encontraba ese único nodo, lo tapaba entero y ni siquiera bajaba a sus
    //     hijos ("no hace falta descender", dice el comentario). **La página de
    //     inicio de sesión quedaba pintada de negro.** Nadie puede iniciar sesión
    //     en un rectángulo negro: es exactamente lo contrario de la condición que
    //     puso el dueño ("sin bloquear al usuario a que se conecte a la red").
    //     Arreglado en `scanNode()`: en esta ventana el WebView NO se tapa, se baja
    //     por sus hijos y se tapan solo los nodos de imagen de verdad
    //     (`android.widget.Image` / `ImageView`) que además no sean controles.
    //
    //  2. **"Tope duro de 3 minutos. Un login legítimo tarda menos de un minuto."**
    //     También es falso fuera del laboratorio. Un portal de aeropuerto o de avión
    //     pide elegir un plan, aceptar términos, cargar nombre/asiento/vuelo/mail,
    //     esperar un código por SMS o mirar un anuncio. Tres minutos de reloj de
    //     pared se acaban a mitad del trámite, y ahí el guard mandaba HOME y el
    //     usuario tenía que empezar de cero — que es, palabra por palabra, el
    //     "a los dos minutos se desconecta, tengo que entrar, conectar de vuelta,
    //     todo" del reporte. Ahora el tope mide **inactividad**, no tiempo total:
    //     mientras la página cambie (el usuario está tocando y escribiendo) el reloj
    //     se reinicia. El techo absoluto queda igual pero mucho más arriba, para que
    //     nadie la deje abierta de navegador para siempre.
    //
    // Lo que NO cambió: la ventana se sigue cerrando apenas la red valida, se sigue
    // reportando al panel cuántas veces se abrió y cuánto duró, y las imágenes de
    // contenido se siguen tapando. El guard hace lo mismo; lo que dejó de hacer es
    // impedir el login.

    /** Interruptor. Encendido por defecto. */
    const val KEY_ENABLED = "captive_portal_guard"

    /**
     * Interruptor aparte, SOLO para el tapado de imágenes de esta ventana. Encendido
     * por defecto.
     *
     * Existe porque el modo de falla de esta palanca deja al usuario sin poder
     * conectarse, y eso puede pasarle a alguien que está en otro país, sin datos
     * móviles y sin nadie al lado. Con esto el administrador apaga **solo el tapado**
     * desde el panel, en un comando, sin tener que apagar el guard entero (que es lo
     * único que se podía hacer antes y deja la ventana completamente libre).
     *
     * Comandos: `ENABLE_CAPTIVE_PORTAL_IMAGES` / `DISABLE_CAPTIVE_PORTAL_IMAGES`.
     */
    const val KEY_COVER_IMAGES = "captive_portal_cover_images"

    /**
     * Inactividad tolerada dentro de la ventana antes de cerrarla.
     *
     * Es el reemplazo del tope de 3 minutos de reloj de pared, y el motivo del cambio
     * está arriba. Lo que este tope tiene que matar es una ventana **abandonada** o
     * usada de visor, no un trámite largo: mientras el contenido de la página cambie
     * —o sea, mientras el usuario esté tocando, escribiendo o navegando el portal— el
     * reloj se reinicia. Tres minutos sin que la página cambie ni una vez no es
     * alguien iniciando sesión.
     */
    const val IDLE_CLOSE_MS = 3 * 60 * 1000L

    /**
     * Techo absoluto de la ventana, pase lo que pase.
     *
     * Acota el abuso de "la dejo abierta y la uso de navegador scrolleando", que sí
     * genera cambios de contenido y por lo tanto no lo agarra el tope de inactividad.
     * Quince minutos es de sobra para cualquier portal real y sigue siendo un techo.
     */
    const val MAX_OPEN_MS = 15 * 60 * 1000L

    /**
     * Gracia antes de cerrar por "red validada".
     *
     * Sin esto, abrir la ventana sobre una red que YA está validada la cerraría en el
     * mismo frame y el usuario no llegaría a ver el aviso de por qué. Con 1,5 s ve el
     * cartel y entiende. No debilita nada: 1,5 segundos no alcanzan para navegar.
     */
    const val VALIDATED_GRACE_MS = 1_500L

    /** Cada cuánto se consulta el estado de la red mientras la ventana está abierta. */
    const val TICK_MS = 1_000L

    /**
     * Paquete de la ventana en AOSP. Se comprueba TAMBIÉN por nombre de clase, porque
     * desde Android 10 el login de portal cautivo viaja dentro del módulo actualizable
     * NetworkStack y el paquete que la aloja puede ser `com.google.android.networkstack`
     * — pero la clase sigue siendo `com.android.captiveportallogin.CaptivePortalLoginActivity`
     * en las dos formas. Mirar las dos cosas es lo que hace que ande en toda la flota.
     */
    private const val PKG_AOSP = "com.android.captiveportallogin"
    private const val CLASS_MARKER = "captiveportallogin"

    fun isCaptivePortalWindow(packageName: String?, className: String?): Boolean {
        if (packageName == PKG_AOSP) return true
        val cls = className?.lowercase() ?: return false
        return cls.contains(CLASS_MARKER)
    }

    /**
     * Qué nodo es "una imagen" DENTRO del WebView del portal.
     *
     * No se puede reusar `visualNodeClassNames` del servicio para esto por dos motivos
     * independientes, y los dos hacen falta para entender el arreglo:
     *
     *  - esa lista incluye `android.webkit.WebView`, o sea el contenedor entero: taparlo
     *    pinta la página de negro (ver la nota de arriba);
     *  - y NO incluye `android.widget.Image`, que es la clase con la que WebView expone
     *    un `<img>` de HTML a la accesibilidad. `ImageView` es la de las vistas nativas.
     *    Bajando por dentro del WebView con la lista vieja no se taparía absolutamente
     *    nada — el bloqueo quedaría anunciado y sin efecto, que es la forma de bug que
     *    este proyecto ya se comió en B.45 y B.47.
     */
    val WEB_IMAGE_CLASS_NAMES = setOf(
        "android.widget.Image",
        "android.widget.ImageView"
    )

    /** El contenedor que NO hay que tapar en esta ventana. */
    const val WEB_CONTAINER_CLASS = "android.webkit.WebView"
}
