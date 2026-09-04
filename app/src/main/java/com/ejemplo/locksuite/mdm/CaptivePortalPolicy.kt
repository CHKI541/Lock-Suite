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

    /** Interruptor. Encendido por defecto. */
    const val KEY_ENABLED = "captive_portal_guard"

    /** Tope duro de la ventana. Un login legítimo tarda menos de un minuto. */
    const val MAX_OPEN_MS = 3 * 60 * 1000L

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
}
