package com.ejemplo.locksuite.mdm

import com.ejemplo.locksuite.mdm.DialerCodeScan.Resultado
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.IdentityHashMap
import kotlin.random.Random

/**
 * 27/9/2026 (batería) — el recorrido único de los códigos del marcador tiene que dar
 * EXACTAMENTE lo mismo que los dos recorridos de antes.
 *
 * `Viejo` es copia literal del código anterior (`searchNodeByText` de
 * LockSuiteAccessibilityService, dos pasadas con el presupuesto reiniciado y con
 * `lowercase()`), sobre un árbol de mentira. Se comparan, en miles de árboles al azar:
 *  • el resultado (ABRIR / EMERGENCIA / nada);
 *  • la SECUENCIA de nodos leídos: la pasada nueva tiene que leer exactamente los mismos
 *    nodos, en el mismo orden, que la primera pasada vieja (la de ABRIR);
 *  • que cada hijo pedido se suelte una sola vez y la raíz nunca (en el servicio, soltar =
 *    `recycle()`; soltar de más o de menos rompe el marcador o pierde memoria).
 */
class DialerCodeScanTest {

    private class Nodo(
        val texto: CharSequence?,
        val descripcion: CharSequence?,
        val hijos: List<Nodo?>
    )

    // ── La versión vieja, tal cual estaba ────────────────────────────────────────────

    private class Viejo(private val maxProfundidad: Int, private val maxNodos: Int) {
        private var nodeBudget = 0
        val leidosPrimeraPasada = ArrayList<Nodo>()
        var pedidosPrimeraPasada = 0
        private var pasada = 0

        fun correr(root: Nodo): Resultado {
            pasada = 1
            nodeBudget = maxNodos
            val isOpenCode = searchNodeByText(root, listOf("*#*#1234#*#*", "*#*#1234#*#"), 0)
            pasada = 2
            nodeBudget = maxNodos
            val isEmergencyCode = searchNodeByText(root, listOf("*#*#9999#*#*", "*#*#9999#*#"), 0)
            return when {
                isOpenCode -> Resultado.ABRIR
                isEmergencyCode -> Resultado.EMERGENCIA
                else -> Resultado.NINGUNO
            }
        }

        private fun searchNodeByText(root: Nodo, keywords: List<String>, depth: Int): Boolean {
            if (depth > maxProfundidad) return false
            if (nodeBudget-- <= 0) return false

            if (pasada == 1) leidosPrimeraPasada.add(root)
            val text = root.texto?.toString()?.lowercase() ?: ""
            val desc = root.descripcion?.toString()?.lowercase() ?: ""
            if (keywords.any { text.contains(it) || desc.contains(it) }) return true
            val childCount = root.hijos.size
            for (i in 0 until childCount) {
                if (pasada == 1) pedidosPrimeraPasada++
                val child = root.hijos[i] ?: continue
                val found = searchNodeByText(child, keywords, depth + 1)
                if (found) return true
            }
            return false
        }
    }

    // ── La versión nueva, instrumentada ──────────────────────────────────────────────

    private class Corrida(val resultado: Resultado, val leidos: List<Nodo>, val pedidos: Int)

    private fun nuevo(raiz: Nodo, maxProfundidad: Int, maxNodos: Int): Corrida {
        val leidos = ArrayList<Nodo>()
        val obtenidos = IdentityHashMap<Nodo, Int>()
        val soltados = IdentityHashMap<Nodo, Int>()
        var pedidos = 0
        val r = DialerCodeScan.buscar(
            raiz,
            maxProfundidad = maxProfundidad,
            maxNodos = maxNodos,
            cantidadHijos = { it.hijos.size },
            hijo = { n, i ->
                pedidos++
                n.hijos[i]?.also { obtenidos[it] = (obtenidos[it] ?: 0) + 1 }
            },
            soltar = { soltados[it] = (soltados[it] ?: 0) + 1 },
            texto = { leidos.add(it); it.texto },
            descripcion = { it.descripcion }
        )
        assertTrue("la raíz no se suelta: es de quien llama", !soltados.containsKey(raiz))
        assertEquals("cada hijo obtenido se suelta una vez", obtenidos.size, soltados.size)
        for ((n, veces) in obtenidos) {
            assertEquals(1, veces)
            assertEquals(1, soltados[n])
        }
        return Corrida(r, leidos, pedidos)
    }

    private fun comparar(raiz: Nodo, maxProfundidad: Int, maxNodos: Int, contexto: String) {
        val viejo = Viejo(maxProfundidad, maxNodos)
        val esperado = viejo.correr(raiz)
        val corrida = nuevo(raiz, maxProfundidad, maxNodos)
        assertEquals("resultado — $contexto", esperado, corrida.resultado)
        assertEquals("cantidad de nodos leídos — $contexto", viejo.leidosPrimeraPasada.size, corrida.leidos.size)
        for (i in corrida.leidos.indices) {
            assertTrue("mismo nodo en la posición $i — $contexto", viejo.leidosPrimeraPasada[i] === corrida.leidos[i])
        }
        assertTrue("nunca pide más hijos que la pasada vieja — $contexto", corrida.pedidos <= viejo.pedidosPrimeraPasada)
        assertTrue("nunca lee más nodos que el tope — $contexto", corrida.leidos.size <= maxOf(maxNodos, 0))
    }

    // ── Árboles al azar ──────────────────────────────────────────────────────────────

    private val textosComunes = listOf<CharSequence?>(
        null, "", "Llamando…", "00:12", "Teléfono", "Marcador", "123", "*#06#",
        "*#*#1234#*", // incompleto: NO es código
        "*#*#9999#", // incompleto: NO es código
        "*#*#12345#*#*", // otro número: NO es código
        "＊＃＊＃１２３４＃＊＃＊", // ancho completo: NO es código (ni antes ni ahora)
        "İSTANBUL Σ K ﬀ", // letras que cambian de largo o de forma al pasar a minúscula
        StringBuilder("texto que no es String")
    )

    private val textosConCodigo = listOf<CharSequence?>(
        "*#*#1234#*#*", "*#*#1234#*#", "Marcar *#*#1234#*#* ahora", "İ*#*#1234#*#İ",
        "K*#*#1234#*#Σ", StringBuilder("*#*#1234#*#*"),
        "*#*#9999#*#*", "*#*#9999#*#", "ABC*#*#9999#*#DEF", "Σ*#*#9999#*#*İ",
        StringBuilder("x*#*#9999#*#"),
        "*#*#1234#*#**#*#9999#*#*", "*#*#9999#*#*#*#1234#*#*" // los dos en el mismo texto
    )

    private fun texto(r: Random, probCodigo: Double): CharSequence? =
        if (r.nextDouble() < probCodigo) textosConCodigo[r.nextInt(textosConCodigo.size)]
        else textosComunes[r.nextInt(textosComunes.size)]

    /** `profundo` = casi una cadena (para pasar el tope de profundidad); si no, ancho. */
    private fun arbol(r: Random, nodos: Int, maxProf: Int, profundo: Boolean, probCodigo: Double): Nodo {
        var restantes = nodos
        fun generar(prof: Int): Nodo {
            restantes--
            val hijos = ArrayList<Nodo?>()
            if (prof < maxProf && restantes > 0) {
                val cantidad = if (profundo) {
                    when (r.nextInt(10)) { 0 -> 0; 1, 2, 3, 4, 5, 6, 7 -> 1; 8 -> 2; else -> 3 }
                } else {
                    when (r.nextInt(10)) { 0, 1, 2 -> 0; 3, 4 -> 1; 5, 6 -> 2; 7 -> 3; 8 -> 5; else -> r.nextInt(12) }
                }
                repeat(cantidad) {
                    if (restantes > 0) hijos.add(if (r.nextInt(15) == 0) null else generar(prof + 1))
                }
            }
            return Nodo(texto(r, probCodigo), texto(r, probCodigo), hijos)
        }
        return generar(0)
    }

    @Test
    fun igualAlViejo_arbolesAlAzar_topesReales() {
        // Los topes que usa el servicio: profundidad 40, 2500 nodos.
        val r = Random(20260927)
        for (caso in 0 until 1500) {
            val probCodigo = listOf(0.0, 1.0 / 8000, 1.0 / 2500, 1.0 / 400, 1.0 / 30)[caso % 5]
            val profundo = caso % 3 == 0
            val nodos = 1 + r.nextInt(if (caso % 7 == 0) 7000 else 3500)
            val raiz = arbol(r, nodos, maxProf = 55, profundo = profundo, probCodigo = probCodigo)
            comparar(raiz, maxProfundidad = 40, maxNodos = 2500, contexto = "caso $caso")
        }
    }

    @Test
    fun igualAlViejo_arbolesAlAzar_topesChicos() {
        // Topes chicos para que el corte por presupuesto y por profundidad caiga en
        // cualquier lugar del árbol, incluso en la raíz.
        val r = Random(7)
        for (caso in 0 until 4000) {
            val maxProfundidad = r.nextInt(0, 12)
            val maxNodos = r.nextInt(0, 60)
            val raiz = arbol(r, 1 + r.nextInt(200), maxProf = 16, profundo = caso % 2 == 0, probCodigo = 0.03)
            comparar(raiz, maxProfundidad, maxNodos, contexto = "caso $caso prof=$maxProfundidad nodos=$maxNodos")
        }
    }

    // ── Casos puntuales ──────────────────────────────────────────────────────────────

    private fun hoja(texto: CharSequence?, descripcion: CharSequence? = null) = Nodo(texto, descripcion, emptyList())

    private fun correrReal(raiz: Nodo) = nuevo(raiz, 40, 2500).resultado

    @Test
    fun ambosCodigos_ganaAbrirAunqueEmergenciaAparezcaAntes() {
        val raiz = Nodo(null, null, listOf(hoja("*#*#9999#*#*"), hoja("*#*#1234#*#*")))
        assertEquals(Resultado.ABRIR, correrReal(raiz))
        comparar(raiz, 40, 2500, "ambos")
    }

    @Test
    fun codigoEnLaDescripcion() {
        assertEquals(Resultado.EMERGENCIA, correrReal(Nodo(null, null, listOf(hoja(null, "*#*#9999#*#")))))
        assertEquals(Resultado.ABRIR, correrReal(Nodo(null, null, listOf(hoja("nada", "*#*#1234#*#")))))
    }

    @Test
    fun codigosIncompletosNoCuentan() {
        val raiz = Nodo("*#*#1234#*", "*#*#9999#", listOf(hoja("＊＃＊＃１２３４＃＊＃＊"), hoja("*#*#12345#*#*")))
        assertEquals(Resultado.NINGUNO, correrReal(raiz))
    }

    @Test
    fun abrirFueraDelPresupuesto_quedaEmergencia() {
        // Emergencia en el nodo 2; abrir en el 4. Con presupuesto 3, abrir no se llega a leer.
        val raiz = Nodo("a", null, listOf(hoja("*#*#9999#*#*"), hoja("b"), hoja("*#*#1234#*#*")))
        assertEquals(Resultado.EMERGENCIA, nuevo(raiz, 40, 3).resultado)
        assertEquals(Resultado.ABRIR, nuevo(raiz, 40, 4).resultado)
        comparar(raiz, 40, 3, "presupuesto 3")
        comparar(raiz, 40, 4, "presupuesto 4")
    }

    @Test
    fun codigoMasAbajoDelTopeDeProfundidad_noCuenta() {
        var n = hoja("*#*#1234#*#*")
        repeat(41) { n = Nodo("nivel", null, listOf(n)) } // el código queda a profundidad 41
        assertEquals(Resultado.NINGUNO, correrReal(n))
        comparar(n, 40, 2500, "profundidad 41")
        var m = hoja("*#*#1234#*#*")
        repeat(40) { m = Nodo("nivel", null, listOf(m)) } // a profundidad 40: sí cuenta
        assertEquals(Resultado.ABRIR, correrReal(m))
    }

    @Test
    fun presupuestoAgotado_dejaDePedirHijos() {
        // Raíz con 3000 hijos vacíos: la versión vieja, ya sin presupuesto, seguía pidiendo
        // cada uno de los hijos restantes (cada `getChild` puede ser una llamada entre
        // procesos). La nueva corta.
        val raiz = Nodo(null, null, List(3000) { hoja("x") })
        val corrida = nuevo(raiz, 40, 2500)
        assertEquals(Resultado.NINGUNO, corrida.resultado)
        assertEquals(2500, corrida.leidos.size)
        assertTrue("pidió ${corrida.pedidos} hijos", corrida.pedidos <= 2500)
        comparar(raiz, 40, 2500, "3000 hojas")
    }
}
