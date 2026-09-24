package com.ejemplo.locksuite.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pruebas de la parte pura del buzón de comandos (22/9/2026): qué se aplica, en qué
 * orden y qué se descarta. Lo que habla con Firebase no se prueba acá.
 */
class CommandMailboxTest {

    private val ahora = 1_790_000_000_000L          // septiembre 2026: reloj creíble
    private val relojRoto = 1_000_000_000_000L      // 2001: reloj NO creíble

    private fun entrada(
        key: String,
        comando: String = "BLOCK_WIFI",
        ts: Long = ahora - 60_000,
        vence: Long = ahora + 3_600_000,
        firma: String? = "firma"
    ): CommandMailbox.Entry {
        val data = mutableMapOf(
            "command" to comando,
            "commandId" to key,
            "timestamp" to ts.toString()
        )
        if (firma != null) data["signature"] = firma
        return CommandMailbox.Entry(key = key, data = data, expiresAt = vence, queuedAt = ts)
    }

    @Test
    fun aplicaEnElOrdenEnQueElPanelLosMando() {
        val nuevo = entrada("b", comando = "UNBLOCK_WIFI", ts = ahora - 10_000)
        val viejo = entrada("a", comando = "BLOCK_WIFI", ts = ahora - 50_000)
        val (aplicar, descartar) = CommandMailbox.plan(listOf(nuevo, viejo), ahora) { false }
        assertEquals(listOf("a", "b"), aplicar.map { it.key })
        assertTrue(descartar.isEmpty())
    }

    @Test
    fun descartaLoQueYaLlegoPorFcm() {
        val (aplicar, descartar) = CommandMailbox.plan(
            listOf(entrada("x"), entrada("y")), ahora
        ) { it == "x" }
        assertEquals(listOf("y"), aplicar.map { it.key })
        assertEquals(listOf("x"), descartar.map { it.key })
    }

    @Test
    fun descartaLoVencidoSoloConRelojCreible() {
        val vencida = entrada("v", vence = ahora - 1)
        val (aplicar1, descartar1) = CommandMailbox.plan(listOf(vencida), ahora) { false }
        assertTrue(aplicar1.isEmpty())
        assertEquals(listOf("v"), descartar1.map { it.key })

        // Con el reloj del equipo roto no se puede saber si venció: se aplica.
        val (aplicar2, _) = CommandMailbox.plan(listOf(vencida), relojRoto) { false }
        assertEquals(listOf("v"), aplicar2.map { it.key })
    }

    @Test
    fun sinVencimientoNoVence() {
        val (aplicar, _) = CommandMailbox.plan(listOf(entrada("s", vence = 0L)), ahora) { false }
        assertEquals(listOf("s"), aplicar.map { it.key })
    }

    @Test
    fun descartaEntradasSinFirmaOSinComando() {
        val sinFirma = entrada("f", firma = null)
        val sinComando = CommandMailbox.Entry("c", mapOf("signature" to "x"), 0L, ahora)
        val (aplicar, descartar) = CommandMailbox.plan(listOf(sinFirma, sinComando), ahora) { false }
        assertTrue(aplicar.isEmpty())
        assertEquals(setOf("f", "c"), descartar.map { it.key }.toSet())
    }

    @Test
    fun mismoTimestampDesempataPorClave() {
        val (aplicar, _) = CommandMailbox.plan(
            listOf(entrada("z", ts = ahora), entrada("m", ts = ahora)), ahora
        ) { false }
        assertEquals(listOf("m", "z"), aplicar.map { it.key })
    }
}
