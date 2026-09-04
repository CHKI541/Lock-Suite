package com.ejemplo.locksuite.mdm

/**
 * PhotoPickerPolicy — el selector de foto de contacto (5/9/2026).
 *
 * ─────────────────────────────────────────────────────────────────────────────
 * QUÉ CIERRA
 * ─────────────────────────────────────────────────────────────────────────────
 *
 * Reporte del dueño: *"en contactos de Google (y quizás en algún otro lado) para
 * elegir foto de contacto se puede entrar a Google Fotos y a un gran catálogo de
 * ilustraciones"*. El catálogo de ilustraciones de Google es contenido navegable
 * servido dentro de la app de Contactos, sin ningún navegador de por medio — la
 * misma forma de agujero que B.43.
 *
 * ─────────────────────────────────────────────────────────────────────────────
 * POR QUÉ LA PRIMERA VERSIÓN "A VECES NO REBOTABA"  (diagnóstico del 5/9)
 * ─────────────────────────────────────────────────────────────────────────────
 *
 * El dueño reportó que el rebote fallaba de forma intermitente. Se encontraron
 * **cuatro causas**, y las dos primeras explican solas el "a veces":
 *
 *  1. **La sesión de administrador lo apagaba en silencio durante 5 minutos.**
 *     El rebote salía por `SessionManager.isActive()`. Esa sesión dura 5 minutos
 *     desde que se ingresa el PIN… que es exactamente lo que hay que hacer para
 *     encender el interruptor y después ir a probarlo. **Es, textual, el mismo
 *     bug que B.15 primera corrección puntos 3 y 4: "no estaban rotas, estaban
 *     calladas".** Acá no hay ninguna razón para esa excepción: un administrador
 *     no necesita abrir un selector de fotos. Se sacó.
 *
 *  2. **El selector de fotos DEL SISTEMA no estaba contemplado.** En Android 13+
 *     (y en el backport por Play services) el selector vive en
 *     `com.google.android.providers.media.module`, clase
 *     `com.android.providers.media.photopicker.PhotoPickerActivity`. La versión
 *     anterior buscaba `photopickerintentactivity` (que no es ese nombre) y su
 *     única regla que sí decía `photopicker` estaba condicionada a que el paquete
 *     fuera de Contactos — y el del sistema no lo es. O sea que no matcheaba nunca.
 *
 *  3. **Google Fotos como selector externo tampoco.** Su clase es del estilo
 *     `com.google.android.apps.photos.picker.external.ExternalPickerActivity`:
 *     contiene "picker", pero NO "photopicker" (hay un punto en el medio), así que
 *     ninguna de las reglas la agarraba.
 *
 *  4. **El antirrebote de 2 s.** Si el usuario volvía a abrir el selector enseguida,
 *     el segundo intento no rebotaba. Bajado a 1,2 s, que alcanza para no encadenar
 *     "atrás" pero no para dejar pasar un reintento.
 *
 * ─────────────────────────────────────────────────────────────────────────────
 * LA DECISIÓN DE ALCANCE, QUE ES LO IMPORTANTE DE ESTE ARCHIVO
 * ─────────────────────────────────────────────────────────────────────────────
 *
 * El selector de fotos del sistema **no es solo el de la foto de contacto**: es el
 * mismo que usa WhatsApp para adjuntar una foto. Rebotarlo siempre dejaría al
 * equipo sin poder mandar fotos, que no es lo que se pidió y sería una regresión
 * grande y silenciosa.
 *
 * Por eso hay dos niveles:
 *
 *   • **SIEMPRE se rebota** el selector de *avatar / ilustraciones / foto de perfil*
 *     (`avatarpicker`, `artpicker`, `illustration`, `user.profile.photopicker`).
 *     Esas pantallas existen solo para elegir una foto de perfil: no se usan nunca
 *     para adjuntar algo en un chat, así que rebotarlas no le quita nada al equipo.
 *     **Es el catálogo de ilustraciones que reportó el dueño.**
 *
 *   • **El selector genérico de fotos y Google Fotos se rebotan SOLO si se llegó
 *     desde una app de contactos** (Contactos, Teléfono/Dialer, People). Con eso el
 *     agujero queda cerrado y adjuntar una foto en WhatsApp sigue funcionando.
 *
 * Si alguna vez se quiere cerrar también el adjuntar fotos, eso es otra decisión y
 * otro interruptor — no hay que hacerlo ampliando este.
 */
object PhotoPickerPolicy {

    /**
     * Paquetes que SON un selector: si están al frente, la pantalla es un selector
     * sin importar cómo se llame la clase.
     *
     * Esto es lo que arregla el caso 2 del diagnóstico: el selector del sistema abre
     * su propia Activity en su propio paquete, así que mirar el paquete es una señal
     * más confiable que adivinar el nombre de la clase — y no depende del idioma ni
     * de la versión del módulo.
     */
    private val PICKER_PACKAGES = setOf(
        "com.android.avatarpicker",
        "com.google.android.avatarpicker",
        // Selector de fotos del sistema (Android 13+ y el backport por Play services)
        "com.google.android.providers.media.module",
        "com.android.providers.media.module",
        "com.android.providers.media"
    )

    /** Google Fotos. Se trata aparte: la app entera NO se rebota, solo el selector. */
    private const val PKG_PHOTOS = "com.google.android.apps.photos"

    /**
     * Selectores de foto de PERFIL. Se rebotan siempre, vengan de donde vengan:
     * son pantallas que solo existen para elegir un avatar, nunca para adjuntar.
     * Acá está el catálogo de ilustraciones de Google.
     */
    private val PROFILE_PICKER_MARKERS = listOf(
        "avatarpicker",
        "artpicker",
        "artactivity",
        "illustration",
        "user.profile.photopicker",
        "libraries.user.profile"
    )

    /** Selector genérico de fotos. Se rebota solo si se llegó desde Contactos. */
    private val GENERIC_PICKER_MARKERS = listOf(
        "photopicker",
        "photoselection",
        "attachimage",
        "picker.external",
        "externalpicker"
    )

    /**
     * Nunca rebotar. El recorte ocurre DESPUÉS de que el usuario ya eligió una foto
     * (por ejemplo sacada con la cámara): a esa altura no hay ningún catálogo que
     * cerrar, y rebotarlo solo rompe poner una foto propia.
     */
    private val CLASS_EXCLUSIONS = listOf(
        "crop",
        "camera"
    )

    /** ¿Este paquete es una app de contactos / agenda? */
    fun isContactsPackage(packageName: String?): Boolean {
        if (packageName.isNullOrEmpty()) return false
        val p = packageName.lowercase()
        return p.contains("contacts") || p.contains("dialer") || p.contains("people")
    }

    /** Vale la pena mirar esta ventana (y anotar su clase para diagnóstico). */
    fun isRelevantPackage(packageName: String?): Boolean {
        if (packageName.isNullOrEmpty()) return false
        return PICKER_PACKAGES.contains(packageName) ||
            packageName == PKG_PHOTOS ||
            isContactsPackage(packageName)
    }

    /**
     * Decisión completa.
     *
     * @param packageName paquete de la ventana que se acaba de abrir.
     * @param className   nombre de clase del evento (puede venir vacío).
     * @param cameFromContacts si en las últimas ventanas hubo una app de contactos.
     */
    fun shouldBounce(packageName: String?, className: String?, cameFromContacts: Boolean): Boolean {
        if (packageName.isNullOrEmpty()) return false
        val cls = (className ?: "").lowercase()
        if (cls.isNotEmpty() && CLASS_EXCLUSIONS.any { cls.contains(it) }) return false

        // 1. Selector de foto de PERFIL: siempre. Incluye el catálogo de ilustraciones.
        if (PROFILE_PICKER_MARKERS.any { cls.contains(it) }) return true
        if (packageName == "com.android.avatarpicker" || packageName == "com.google.android.avatarpicker") {
            return true
        }

        // 2. Selector genérico de fotos (del sistema o Google Fotos): solo desde Contactos,
        //    para no romper "adjuntar una foto" en el resto de las apps.
        val esSelectorGenerico =
            PICKER_PACKAGES.contains(packageName) ||
            (packageName == PKG_PHOTOS && GENERIC_PICKER_MARKERS.any { cls.contains(it) }) ||
            GENERIC_PICKER_MARKERS.any { cls.contains(it) }
        return esSelectorGenerico && cameFromContacts
    }
}
