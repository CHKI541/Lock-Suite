package com.ejemplo.locksuite.mdm

/**
 * Busca en la pantalla los códigos secretos del marcador (`*#*#1234#*#*` abre el
 * administrador, `*#*#9999#*#*` abre la purga de emergencia), en UN solo recorrido.
 *
 * 27/9/2026 (batería). Antes eran DOS recorridos completos del árbol por evento —uno por
 * cada código— y cada texto se pasaba a minúscula (una cadena nueva por nodo). Corre en el
 * hilo principal, en cada evento de cualquier app cuyo paquete diga "dialer", "phone" o
 * "contact": la pantalla de llamada (`com.android.phone`) actualiza el cronómetro una vez
 * por segundo, o sea dos recorridos por segundo durante TODA la llamada.
 *
 * Mismo resultado que antes, y está probado en un banco contra la versión vieja
 * (`DialerCodeScanTest`, árboles al azar):
 *
 *  • Mismo orden de visita (en profundidad, desde la raíz), mismos topes de profundidad y
 *    de nodos (`maxNodos` por código, igual que antes, porque se recorre UNA vez los
 *    mismos nodos que recorría cada búsqueda por separado).
 *  • Si aparecen los dos códigos, gana ABRIR, como antes (se buscaba primero).
 *  • Sin `lowercase()`: los códigos son solo `*`, `#` y dígitos, que no tienen mayúscula,
 *    y pasar a minúscula no puede crearlos ni romperlos.
 *  • La lista vieja tenía `"*#*#1234#*#*"` y `"*#*#1234#*#"`: la primera contiene a la
 *    segunda, así que buscar la segunda alcanza y es lo mismo.
 *
 * Es genérica sobre el tipo de nodo para poder probarla sin Android; el servicio le pasa
 * cómo leer un `AccessibilityNodeInfo`.
 */
object DialerCodeScan {

    const val CODIGO_ABRIR = "*#*#1234#*#"
    const val CODIGO_EMERGENCIA = "*#*#9999#*#"

    enum class Resultado { NINGUNO, ABRIR, EMERGENCIA }

    private class Estado(var presupuesto: Int) {
        var abrir = false
        var emergencia = false
        var agotado = false
    }

    /**
     * @param soltar se llama con cada hijo obtenido, al terminar de visitarlo (el servicio
     *   lo usa para `recycle()`). La raíz NO se suelta acá: es de quien llama.
     */
    fun <N : Any> buscar(
        raiz: N,
        maxProfundidad: Int,
        maxNodos: Int,
        cantidadHijos: (N) -> Int,
        hijo: (N, Int) -> N?,
        soltar: (N) -> Unit,
        texto: (N) -> CharSequence?,
        descripcion: (N) -> CharSequence?
    ): Resultado {
        val e = Estado(maxNodos)
        visitar(raiz, 0, e, maxProfundidad, cantidadHijos, hijo, soltar, texto, descripcion)
        return when {
            e.abrir -> Resultado.ABRIR
            e.emergencia -> Resultado.EMERGENCIA
            else -> Resultado.NINGUNO
        }
    }

    private fun <N : Any> visitar(
        nodo: N,
        profundidad: Int,
        e: Estado,
        maxProfundidad: Int,
        cantidadHijos: (N) -> Int,
        hijo: (N, Int) -> N?,
        soltar: (N) -> Unit,
        texto: (N) -> CharSequence?,
        descripcion: (N) -> CharSequence?
    ) {
        // Mismo orden de guardas que la versión vieja: primero la profundidad (no gasta
        // presupuesto), después el presupuesto.
        if (profundidad > maxProfundidad) return
        if (e.presupuesto-- <= 0) {
            e.agotado = true
            return
        }

        val t = texto(nodo)?.toString()
        val d = descripcion(nodo)?.toString()
        if (contiene(t, CODIGO_ABRIR) || contiene(d, CODIGO_ABRIR)) {
            // ABRIR gana siempre: no hace falta mirar nada más.
            e.abrir = true
            return
        }
        // EMERGENCIA se anota pero se sigue bajando: la búsqueda vieja de ABRIR también
        // bajaba por acá, y si ABRIR aparece más adelante tiene que ganar igual.
        if (!e.emergencia && (contiene(t, CODIGO_EMERGENCIA) || contiene(d, CODIGO_EMERGENCIA))) {
            e.emergencia = true
        }

        val n = cantidadHijos(nodo)
        for (i in 0 until n) {
            if (e.abrir || e.agotado) return
            val h = hijo(nodo, i) ?: continue
            try {
                visitar(h, profundidad + 1, e, maxProfundidad, cantidadHijos, hijo, soltar, texto, descripcion)
            } finally {
                soltar(h)
            }
        }
    }

    private fun contiene(s: String?, codigo: String): Boolean = s != null && s.contains(codigo)
}
