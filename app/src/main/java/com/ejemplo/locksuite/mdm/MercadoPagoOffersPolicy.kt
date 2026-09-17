package com.ejemplo.locksuite.mdm

/**
 * MercadoPagoOffersPolicy — decidir si la pantalla de Mercado Pago que está adelante
 * es REALMENTE la sección de ofertas (16/9/2026).
 *
 * ═════════════════════════════════════════════════════════════════════════════
 * POR QUÉ ESTE ARCHIVO EXISTE
 * ═════════════════════════════════════════════════════════════════════════════
 *
 * La decisión vivía adentro de `LockSuiteAccessibilityService`, mezclada con el
 * recorrido del árbol de nodos. Eso tiene dos consecuencias que este proyecto ya
 * pagó varias veces: no se puede ejercitar sin un equipo, y cada ajuste se discute
 * en vez de medirse. Acá la decisión es una **función pura** sobre un retrato de la
 * pantalla —sin `Context`, sin Android, sin preferencias— así que el banco de
 * pruebas la corre con los casos reales y un cambio de umbral se verifica en
 * segundos. Es el mismo reparto que `EmbeddedBrowserDetector` (B.60) y
 * `WhitelistManager.buildRules()` (B.53): el servicio arma el retrato en UN
 * recorrido, la política decide.
 *
 * ═════════════════════════════════════════════════════════════════════════════
 * QUÉ ESTABA MAL (reporte del dueño, 16/9/2026)
 * ═════════════════════════════════════════════════════════════════════════════
 *
 * Textual: *"bloquea de más y a veces no puedo hablar con el asistente (mago)
 * quizás por alguna palabra que dice, o no pude entrar a cobros de anses"*.
 *
 * Las dos cosas salen de la misma regla. La versión anterior tenía, como red de
 * seguridad, *"pantalla WebView de Mercado Pago + UNA palabra débil → bloquear"*.
 * El problema es que **casi toda Mercado Pago es una pantalla WebView**
 * (`WebkitPageActivity` / `mlwebkit`): cobros, ANSES, ayuda, comprobantes y el
 * asistente se renderizan así. Entonces esa "red de seguridad" no era una red: era
 * la regla principal, y bastaba que UNA palabra de una lista de dieciséis
 * —`beneficio`, `descuento`, `promocion`, `puntos`…— apareciera en CUALQUIER nodo
 * para sacar al usuario de la app.
 *
 *   • **El asistente (Mago).** Es una conversación: lo que contesta es texto libre.
 *     Si la respuesta nombra un beneficio o un descuento, el filtro lo leía como si
 *     el usuario hubiera ENTRADO a la sección de ofertas. De ahí el *"quizás por
 *     alguna palabra que dice"* — el dueño tenía razón y era literal.
 *   • **Cobros de ANSES.** ANSES llama "beneficios" a sus propias prestaciones, así
 *     que la pantalla de cobro trae esa palabra por su cuenta. Una palabra, pantalla
 *     web, afuera.
 *
 * La causa conceptual, que es lo que conviene conservar: **se estaba tratando la
 * MENCIÓN de una palabra como si fuera la SECCIÓN.** Nombrar un descuento y estar
 * parado en el catálogo de descuentos no son lo mismo, y el árbol de accesibilidad
 * no distingue una cosa de la otra si uno solo busca palabras sueltas.
 *
 * ═════════════════════════════════════════════════════════════════════════════
 * LAS TRES COSAS QUE NO HAY QUE "SIMPLIFICAR"
 * ═════════════════════════════════════════════════════════════════════════════
 *
 * 1. **El veto por campo editable va ANTES que cualquier palabra, y es estructural.**
 *    Una pantalla con un campo de texto editable es una conversación, un buscador o
 *    un formulario — nunca un catálogo de ofertas, que es una lista para mirar. Es
 *    la regla de B.19 punto 3 (*"si hay una señal estructural, usarla antes que una
 *    palabra"*) y es lo único que arregla el asistente **sin depender de qué diga**.
 *    Si alguien lo saca "porque las ofertas también tienen buscador", vuelve el bug.
 *
 * 2. **Las palabras solo se leen de textos CORTOS.** Un título de sección entra en
 *    un renglón; una respuesta del asistente, la letra chica de una promoción, la
 *    descripción de un movimiento o un artículo de ayuda, no. El corte por longitud
 *    es lo que separa "esto es el encabezado de la pantalla" de "esto es contenido
 *    que habla de algo". Misma idea que el tope de 90 caracteres con el que B.41
 *    evita que la descripción de una app dispare un diagnóstico de Play Store.
 *
 * 3. **La lista de pantallas seguras le gana a las palabras de ofertas, pero NO al
 *    identificador de vista.** Un `view-id` propio de la app es una señal de la
 *    estructura de Mercado Pago, no una palabra que alguien escribió: no depende del
 *    idioma y no puede aparecer "de casualidad". Por eso es lo primero que se mira.
 *
 * ═════════════════════════════════════════════════════════════════════════════
 * QUÉ HACER SI SE ESCAPA UNA PANTALLA DE OFERTAS
 * ═════════════════════════════════════════════════════════════════════════════
 *
 * Agregar su título a [FRASES_FUERTES] o su id a [IDS_DE_VISTA]. **No volver a la
 * regla de una sola palabra débil** — es exactamente de donde venimos. El servicio
 * publica al panel lo que vio (`mpOffersAudit`), así que el título exacto que hay
 * que agregar sale del equipo en vez de adivinarse; sin ese dato, esta sesión
 * hubiera sido otra ronda de suposiciones.
 */
object MercadoPagoOffersPolicy {

    /**
     * Un título de sección entra cómodo en esto. Arriba de acá ya es contenido: una
     * frase del asistente, la descripción de un movimiento, un párrafo de ayuda.
     */
    const val MAX_CARACTERES_TITULO = 48

    /**
     * Frases fuertes: una sola alcanza. Son títulos de sección propios, no palabras
     * que puedan caer en una oración cualquiera.
     *
     * ⚠️ Se sacaron `"tus beneficios"` y `"mis beneficios"` que estaban acá: ANSES
     * llama "beneficios" a sus prestaciones y esos dos títulos aparecen en la
     * pantalla de cobro. La sección real de ofertas igual cae por las otras frases y
     * por las palabras débiles.
     */
    private val FRASES_FUERTES = listOf(
        "novedades y ofertas",
        "ofertas y descuentos",
        "descuentos y promociones",
        "beneficios y descuentos",
        "cupones de descuento",
        "mercado puntos",
        "canjea tus puntos",
        "todas las ofertas",
        "todos los descuentos"
    )

    /**
     * Palabras débiles: hacen falta DOS distintas, y en textos cortos.
     *
     * ⚠️ Se sacaron `"puntos"` y `"supermercado"`: son vocabulario normal de una app
     * de pagos ("puntos de venta", "pagaste en un supermercado") y no dicen nada
     * sobre en qué sección está parado el usuario. `"mercado puntos"` sigue cubierto
     * como frase fuerte, que es el caso que de verdad importaba.
     */
    private val PALABRAS_DEBILES = listOf(
        "oferta", "ofertas",
        "promocion", "promociones",
        "descuento", "descuentos",
        "cupon", "cupones",
        "beneficio", "beneficios",
        "recompensa", "recompensas",
        "reintegro", "reintegros"
    )

    /**
     * Identificadores de vista de la propia app. Señal estructural: no depende del
     * idioma y no aparece por casualidad. Es lo único que le gana al veto.
     */
    private val IDS_DE_VISTA = listOf(
        "offers", "offer_", "discounts", "promos", "promotions",
        "loyalty", "benefits", "coupon", "mercadopuntos", "deals"
    )

    /**
     * Pantallas que tienen que funcionar SIEMPRE. Si una de estas palabras aparece en
     * un texto corto, esta pantalla no es el catálogo de ofertas y no se rebota.
     *
     * Son términos de alta precisión a propósito: `"anses"` o `"cvu"` no pueden estar
     * en un catálogo de promociones, mientras que algo como `"pagar"` o `"ayuda"`
     * está en todos lados y apagaría el bloqueo entero. Cuando dudes entre agregar
     * una palabra ancha acá o dejarla afuera, dejala afuera y agregá la pantalla a la
     * auditoría del panel para verla primero.
     */
    private val PANTALLAS_SEGURAS = listOf(
        // ANSES y haberes — el caso que reportó el dueño
        "anses", "cobros", "cobro", "cobrar", "cobra", "haberes",
        "jubilacion", "jubilados", "pension", "pensiones",
        "asignacion", "asignaciones", "auh", "suaf",
        "prestacion", "prestaciones",
        // El asistente — el otro caso que reportó el dueño
        "asistente", "mago",
        // Plata entrando y saliendo
        "transferir", "transferencia", "transferencias",
        "comprobante", "comprobantes",
        "cvu", "cbu",
        "ingresar dinero", "retirar dinero", "sacar plata"
    )

    enum class Veredicto { OFERTAS, PERMITIR }

    /**
     * Lo que el servicio junta en UN recorrido del árbol.
     *
     * @param idsDeVista        `viewIdResourceName` en minúscula.
     * @param textosCortos      textos y descripciones ya normalizados (minúscula, sin
     *                          tildes) cuya longitud no pasa [MAX_CARACTERES_TITULO].
     * @param hayTextoLargo     informativo para la auditoría: hubo prosa en pantalla.
     * @param hayCampoEditable  algún nodo `isEditable` — conversación, buscador o
     *                          formulario. Es el veto estructural.
     * @param esPantallaWeb     `WebkitPageActivity` / `mlwebkit`. **Ya no decide
     *                          nada**: se conserva solo para que la auditoría del
     *                          panel diga en qué clase de pantalla pasó.
     */
    class Retrato(
        val idsDeVista: List<String> = emptyList(),
        val textosCortos: List<String> = emptyList(),
        val hayTextoLargo: Boolean = false,
        val hayCampoEditable: Boolean = false,
        val esPantallaWeb: Boolean = false
    )

    /** El id de vista que disparó, o null. */
    fun idDeOfertas(r: Retrato): String? {
        for (id in r.idsDeVista) {
            for (pista in IDS_DE_VISTA) {
                if (id.contains(pista)) return pista
            }
        }
        return null
    }

    /** La palabra de pantalla segura que vetó, o null. */
    fun palabraSegura(r: Retrato): String? {
        for (t in r.textosCortos) {
            for (p in PANTALLAS_SEGURAS) {
                if (contienePalabraCompleta(t, p)) return p
            }
        }
        return null
    }

    /** La frase fuerte encontrada, o null. */
    fun fraseFuerte(r: Retrato): String? {
        for (t in r.textosCortos) {
            for (f in FRASES_FUERTES) {
                if (t.contains(f)) return f
            }
        }
        return null
    }

    /** Las palabras débiles distintas encontradas, en orden de aparición. */
    fun palabrasDebiles(r: Retrato): List<String> {
        val vistas = ArrayList<String>(4)
        for (t in r.textosCortos) {
            for (p in PALABRAS_DEBILES) {
                if (vistas.contains(p)) continue
                if (contienePalabraCompleta(t, p)) vistas.add(p)
            }
        }
        return vistas
    }

    /**
     * La decisión. El ORDEN es la especificación — ver las tres cosas que no hay que
     * simplificar, arriba.
     *
     *   1. id de vista de ofertas          → OFERTAS  (estructural, gana a todo)
     *   2. hay un campo editable           → PERMITIR (conversación / buscador / form)
     *   3. palabra de pantalla segura      → PERMITIR (ANSES, cobros, transferencias)
     *   4. frase fuerte en un texto corto  → OFERTAS
     *   5. dos palabras débiles distintas  → OFERTAS
     *   6. cualquier otra cosa             → PERMITIR
     */
    fun evaluar(r: Retrato): Veredicto {
        if (idDeOfertas(r) != null) return Veredicto.OFERTAS
        if (r.hayCampoEditable) return Veredicto.PERMITIR
        if (palabraSegura(r) != null) return Veredicto.PERMITIR
        if (fraseFuerte(r) != null) return Veredicto.OFERTAS
        if (palabrasDebiles(r).size >= 2) return Veredicto.OFERTAS
        return Veredicto.PERMITIR
    }

    /**
     * Una línea legible para la auditoría del panel. Se calcula tanto cuando bloquea
     * como cuando deja pasar: la mitad del valor está en poder mirar POR QUÉ una
     * pantalla legítima estuvo cerca de rebotar, que es la pregunta que trajo el
     * dueño hoy y que no se podía contestar desde el panel.
     */
    fun motivo(r: Retrato): String {
        idDeOfertas(r)?.let { return "id de vista «$it»" }
        if (r.hayCampoEditable) return "permitida: hay un campo de texto editable (conversación o formulario)"
        palabraSegura(r)?.let { return "permitida: pantalla segura «$it»" }
        fraseFuerte(r)?.let { return "frase «$it»" }
        val debiles = palabrasDebiles(r)
        if (debiles.size >= 2) return "palabras " + debiles.take(3).joinToString("+") { "«$it»" }
        if (debiles.size == 1) return "permitida: una sola palabra «${debiles[0]}» (hacen falta dos)"
        return "permitida: ninguna señal de ofertas"
    }

    /**
     * Minúsculas y sin tildes en un solo recorrido de caracteres.
     *
     * Se evita `java.text.Normalizer` a propósito: es bastante más caro y esto corre
     * sobre cada texto de cada nodo, en el hilo principal. Sin esto, `"promoción"`
     * nunca coincidía con `"promocion"` de la lista y media lista estaba muerta
     * (bug de arrastre encontrado en B.13).
     */
    fun plegarAcentos(cs: CharSequence): String {
        val sb = StringBuilder(cs.length)
        for (c in cs) {
            val lc = c.lowercaseChar()
            sb.append(
                when (lc) {
                    'á', 'à', 'ä', 'â', 'ã' -> 'a'
                    'é', 'è', 'ë', 'ê' -> 'e'
                    'í', 'ì', 'ï', 'î' -> 'i'
                    'ó', 'ò', 'ö', 'ô', 'õ' -> 'o'
                    'ú', 'ù', 'ü', 'û' -> 'u'
                    else -> lc
                }
            )
        }
        return sb.toString()
    }

    /**
     * Coincidencia por palabra completa. Sin esto `"cupon"` matchea dentro de
     * cualquier palabra que lo contenga, y una palabra suelta alcanzaba para expulsar
     * al usuario de la pantalla.
     */
    fun contienePalabraCompleta(pajar: String, palabra: String): Boolean {
        if (palabra.isEmpty()) return false
        var i = pajar.indexOf(palabra)
        while (i >= 0) {
            val antesOk = i == 0 || !pajar[i - 1].isLetterOrDigit()
            val fin = i + palabra.length
            val despuesOk = fin >= pajar.length || !pajar[fin].isLetterOrDigit()
            if (antesOk && despuesOk) return true
            i = pajar.indexOf(palabra, i + 1)
        }
        return false
    }
}
