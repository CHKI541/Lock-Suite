package com.ejemplo.locksuite.mdm

/**
 * SOLICITUDES DE APPS — el usuario pide, el administrador aprueba (10/9/2026, B.59)
 * ═══════════════════════════════════════════════════════════════════════════════
 *
 * La segunda mitad del PROMPT A. La primera (el checksum obligatorio de la Tienda)
 * se cerró en B.58 y no se rehace acá.
 *
 * EL PROBLEMA QUE RESUELVE: hasta hoy, para que un equipo pudiera instalar una app
 * nueva hacía falta que el administrador **adivinara de antemano** qué iba a querer
 * esa persona en seis meses. Si no lo adivinó, el usuario ve la app en la Tienda con
 * el botón "Bloqueada" y no tiene forma de decir nada. El único canal era llamar por
 * teléfono al administrador y dictarle el nombre del paquete.
 *
 * ─────────────────────────────────────────────────────────────────────────────
 * POR QUÉ ESTA CLASE ES CASI TODA FUNCIONES PURAS
 * ─────────────────────────────────────────────────────────────────────────────
 *
 * Ninguna sesión de IA de este proyecto puede compilar con Gradle ni probar en un
 * equipo real. Lo único que agarra los errores que importan es dejar la decisión en
 * una función pura y correrle aserciones de comportamiento de verdad — es lo que se
 * hizo con `WhitelistManager.buildRules()` (B.53), `EnrollmentProfiles.buildData()`
 * (B.57) y `ApkChecksum` (B.58). Acá el estado (Firebase, prefs, red) queda afuera:
 * entra un retrato de la situación, sale un veredicto.
 *
 * ─────────────────────────────────────────────────────────────────────────────
 * LOS TRES MODOS DE FALLA QUE ESTO TIENE QUE EVITAR, Y NO SON HIPOTÉTICOS
 * ─────────────────────────────────────────────────────────────────────────────
 *
 * 1. **Una clave de Firebase inválida rompe la escritura entera, en silencio.** Las
 *    claves no admiten `.`, `#`, `$`, `[`, `]` ni `/`. El nombre del paquete viaja
 *    con `_` en vez de `.` — el mismo patrón exacto de la auditoría de la lista
 *    blanca en `FirebaseDeviceSync.syncWhitelistState()`. Pero acá el texto puede
 *    venir **escrito a mano por el usuario final** en el campo "pedir otra app", así
 *    que además hay que validarlo: `normalizarPaquete()` acepta únicamente nombres de
 *    paquete de forma válida, y cualquier otra cosa se rechaza ANTES de tocar la red.
 *    Un `$` colado en una clave es un `setValue()` que falla y un pedido que el
 *    usuario cree haber mandado.
 *
 * 2. **Spam.** Esto corre en un celular restringido, y el botón "Pedir" es lo único
 *    nuevo que se puede tocar en esa pantalla. Sin tope, un chico aburrido escribe
 *    quinientos nodos en `devices/<id>/appRequests` y el panel queda inutilizable —
 *    y son quinientas escrituras de datos móviles del usuario. Por eso hay tope de
 *    pendientes (`MAX_PENDIENTES`) y espera para volver a pedir algo ya rechazado
 *    (`ESPERA_REPEDIDO_MS`): que se pueda insistir mañana es razonable; que se pueda
 *    insistir cuarenta veces por minuto, no.
 *
 * 3. **El pedido mudo.** Si pedir no dice nada y aprobar tampoco, el usuario toca el
 *    botón, no pasa nada visible, y vuelve a tocarlo. Es exactamente la familia de
 *    bugs que este proyecto viene pagando desde B.28 (`no_apps_control` aceptada en
 *    silencio, cuatro veces), B.42 (el panel llamaba "✓" al silencio) y B.54 (tres
 *    silencios en el flujo de actualización). Por eso `decidir()` **nunca** devuelve
 *    "no" sin un motivo que la pantalla pueda escribir, y `avisosPendientes()` existe
 *    para que la respuesta del administrador le llegue al usuario aunque no vuelva a
 *    abrir la Tienda.
 *
 * ⚠️ LO QUE ESTA CLASE **NO** DECIDE, Y ES A PROPÓSITO:
 * si una app se puede instalar **no depende de este nodo**. Depende de
 * `globalSettings/allowedPackages`, que solo puede escribir un administrador
 * autenticado (ver `database.rules.json`). O sea que un equipo que se inventara un
 * `status: "approved"` en su propio subárbol **no consigue nada**: la Tienda mira la
 * lista de permitidas, no el estado del pedido. El estado es para la interfaz.
 */
object AppRequestManager {

    /** Tope de pedidos sin responder por equipo. Ver modo de falla 2 del encabezado. */
    const val MAX_PENDIENTES = 20

    /** Espera para volver a pedir algo ya rechazado: 24 h. Ver modo de falla 2. */
    const val ESPERA_REPEDIDO_MS = 24L * 60L * 60L * 1000L

    /** Tope del texto libre del usuario. Firebase aguanta mucho más; el panel no. */
    const val MAX_NOTA = 140

    const val PENDIENTE = "pending"
    const val APROBADA = "approved"
    const val RECHAZADA = "rejected"

    /**
     * Por qué se aceptó o no un pedido. **Todos los "no" traen motivo**: la pantalla
     * escribe uno distinto para cada uno. Ver modo de falla 3 del encabezado.
     */
    enum class Veredicto {
        OK,
        PAQUETE_INVALIDO,
        YA_PERMITIDA,
        YA_PEDIDA,
        DEMASIADAS_PENDIENTES,
        ESPERAR_PARA_REPEDIR
    }

    /**
     * Un pedido tal como está guardado. `resueltaEnMs` y `avisada` solo tienen
     * sentido cuando `estado` no es [PENDIENTE].
     */
    data class Solicitud(
        val packageName: String,
        val label: String = "",
        val estado: String = PENDIENTE,
        val pedidaEnMs: Long = 0L,
        val resueltaEnMs: Long = 0L,
        val avisada: Boolean = false
    )

    /** Lo que hay que mostrarle al usuario cuando el administrador ya contestó. */
    data class Aviso(
        val packageName: String,
        val label: String,
        val aprobada: Boolean
    )

    /**
     * Nombre de paquete → clave de Firebase. Mismo patrón que la auditoría de la
     * lista blanca (`FirebaseDeviceSync`, B.53): el `.` no se admite en una clave.
     *
     * Solo se llama sobre un paquete que ya pasó por [normalizarPaquete], así que
     * no puede traer ningún otro carácter prohibido.
     */
    fun claveFirebase(packageName: String): String = packageName.replace(".", "_")

    /** Clave de Firebase → nombre de paquete. Inversa exacta de [claveFirebase]. */
    fun paqueteDesdeClave(clave: String): String = clave.replace("_", ".")

    /**
     * Valida y limpia un nombre de paquete escrito a mano. Devuelve `null` si no es
     * un nombre de paquete de forma válida.
     *
     * La forma es la de Android: dos o más segmentos separados por `.`, cada uno
     * arrancando con una letra y siguiendo con letras, dígitos o `_`. Que sea
     * estricto es lo que garantiza que la clave de Firebase que sale de
     * [claveFirebase] no pueda contener `#`, `$`, `[`, `]` ni `/` — la validación y
     * la seguridad de la clave son la misma cosa. Ver modo de falla 1.
     *
     * ⚠️ NO se valida "que la app exista": el usuario está pidiendo justamente algo
     * que no tiene instalado, y el equipo no puede consultar Play Store. Que el
     * paquete sea real lo decide el administrador al aprobar, mirando el nombre.
     */
    fun normalizarPaquete(crudo: String?): String? {
        val limpio = crudo?.trim()?.lowercase() ?: return null
        if (limpio.isEmpty() || limpio.length > 255) return null
        val segmentos = limpio.split(".")
        if (segmentos.size < 2) return null
        for (s in segmentos) {
            if (s.isEmpty()) return null
            if (!s[0].isLetter()) return null
            for (c in s) {
                if (!c.isLetterOrDigit() && c != '_') return null
                // isLetterOrDigit() de Kotlin acepta letras Unicode (griego, hebreo,
                // cirílico). Un nombre de paquete de Android es ASCII, y además una
                // clave de Firebase con caracteres raros es una fuente de dolor que
                // no hace falta estrenar acá.
                if (c.code > 127) return null
            }
        }
        return limpio
    }

    /** Recorta la nota del usuario a algo que el panel pueda mostrar en una tarjeta. */
    fun normalizarNota(crudo: String?): String {
        val limpio = crudo?.trim() ?: return ""
        return if (limpio.length <= MAX_NOTA) limpio else limpio.take(MAX_NOTA)
    }

    /**
     * **LA FUNCIÓN PURA CENTRAL.** Decide si un pedido se acepta, con el retrato de
     * la situación como único insumo.
     *
     * @param crudoPaquete lo que el usuario tocó o escribió (sin validar).
     * @param permitidas   `globalSettings/allowedPackages` tal como lo lee la Tienda.
     * @param existentes   lo que ya hay en `devices/<id>/appRequests`.
     * @param ahoraMs      reloj de pared.
     *
     * El orden de las comprobaciones importa y no es arbitrario: primero lo que hace
     * al pedido imposible (paquete inválido), después lo que lo hace innecesario (ya
     * está permitida — que es una **buena** noticia y hay que decirla como tal, no
     * como un rechazo), y recién al final los topes anti-spam. Si los topes fueran
     * primero, un usuario con veinte pendientes vería "demasiadas solicitudes" al
     * intentar pedir algo que en realidad ya podía instalar.
     */
    fun decidir(
        crudoPaquete: String?,
        permitidas: Set<String>,
        existentes: Collection<Solicitud>,
        ahoraMs: Long
    ): Veredicto {
        val paquete = normalizarPaquete(crudoPaquete) ?: return Veredicto.PAQUETE_INVALIDO

        if (permitidas.contains(paquete)) return Veredicto.YA_PERMITIDA

        val propia = existentes.firstOrNull { it.packageName == paquete }
        if (propia != null) {
            when (propia.estado) {
                PENDIENTE -> return Veredicto.YA_PEDIDA
                RECHAZADA -> {
                    // Se puede volver a pedir, pero no a los gritos. Un `resueltaEnMs`
                    // en 0 (dato viejo o incompleto) NO habilita el repedido inmediato:
                    // se trata como "recién rechazada". Fallar del lado restrictivo
                    // ante un dato ausente es la misma regla que la Tienda de B.58.
                    val desde = if (propia.resueltaEnMs > 0L) propia.resueltaEnMs else propia.pedidaEnMs
                    if (ahoraMs - desde < ESPERA_REPEDIDO_MS) return Veredicto.ESPERAR_PARA_REPEDIR
                }
                APROBADA -> {
                    // Aprobada pero todavía no permitida: el administrador aprobó y el
                    // catálogo aún no llegó al equipo (o falló el SYNC). Volver a pedir
                    // no arregla nada y taparía el problema real.
                    return Veredicto.YA_PEDIDA
                }
            }
        }

        val pendientes = existentes.count { it.estado == PENDIENTE }
        if (pendientes >= MAX_PENDIENTES) return Veredicto.DEMASIADAS_PENDIENTES

        return Veredicto.OK
    }

    /**
     * Cuerpo del nodo a escribir. Pura: no toca el reloj ni la red, el llamador pasa
     * `ahoraMs`. Así la prueba puede fijar el tiempo.
     */
    fun cuerpo(paquete: String, etiqueta: String, nota: String?, ahoraMs: Long): Map<String, Any> =
        mapOf(
            "packageName" to paquete,
            "label" to (etiqueta.trim().ifEmpty { paquete }),
            "requestedAt" to ahoraMs,
            "note" to normalizarNota(nota),
            "status" to PENDIENTE
        )

    /**
     * Qué avisos hay que mostrarle al usuario: los pedidos ya resueltos que todavía
     * no se avisaron. Ver modo de falla 3 del encabezado.
     *
     * Orden estable por nombre de paquete: si el usuario tiene tres pedidos resueltos
     * a la vez, que el aviso salga siempre en el mismo orden hace que el
     * comportamiento sea reproducible en una prueba y predecible para el usuario.
     */
    fun avisosPendientes(existentes: Collection<Solicitud>): List<Aviso> =
        existentes
            .filter { !it.avisada && (it.estado == APROBADA || it.estado == RECHAZADA) }
            .sortedBy { it.packageName }
            .map {
                Aviso(
                    packageName = it.packageName,
                    label = it.label.ifEmpty { it.packageName },
                    aprobada = it.estado == APROBADA
                )
            }

    /**
     * Texto del motivo, en el idioma del equipo. Vive acá y no en la pantalla para
     * que el banco de pruebas pueda afirmar que **ningún veredicto se queda sin
     * texto** — que es la forma concreta de garantizar que no vuelva el silencio.
     */
    fun motivo(veredicto: Veredicto, idioma: String): String = when (veredicto) {
        Veredicto.OK -> when (idioma) {
            "he" -> "הבקשה נשלחה למנהל"
            "en" -> "Request sent to your administrator"
            else -> "Pedido enviado al administrador"
        }
        Veredicto.PAQUETE_INVALIDO -> when (idioma) {
            "he" -> "שם החבילה אינו תקין (לדוגמה: com.whatsapp)"
            "en" -> "That is not a valid package name (example: com.whatsapp)"
            else -> "Ese no es un nombre de paquete válido (ejemplo: com.whatsapp)"
        }
        Veredicto.YA_PERMITIDA -> when (idioma) {
            "he" -> "האפליקציה כבר מותרת — אפשר להתקין אותה מהחנות"
            "en" -> "That app is already allowed — you can install it from the Store"
            else -> "Esa app ya está permitida: podés instalarla desde la Tienda"
        }
        Veredicto.YA_PEDIDA -> when (idioma) {
            "he" -> "כבר ביקשת את זה — ממתין לתשובת המנהל"
            "en" -> "You already asked for this one — waiting for your administrator"
            else -> "Ya la pediste: está esperando respuesta del administrador"
        }
        Veredicto.DEMASIADAS_PENDIENTES -> when (idioma) {
            "he" -> "יש יותר מדי בקשות ממתינות. חכה לתשובה"
            "en" -> "Too many requests are still waiting. Wait for an answer first"
            else -> "Hay demasiados pedidos esperando respuesta. Esperá a que te contesten"
        }
        Veredicto.ESPERAR_PARA_REPEDIR -> when (idioma) {
            "he" -> "הבקשה הזו נדחתה. אפשר לבקש שוב מחר"
            "en" -> "That request was declined. You can ask again tomorrow"
            else -> "Ese pedido fue rechazado. Podés volver a pedirlo mañana"
        }
    }
}
