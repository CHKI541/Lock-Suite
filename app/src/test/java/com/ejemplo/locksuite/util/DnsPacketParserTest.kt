package com.ejemplo.locksuite.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * 22/9/2026 — el parser de la pregunta DNS. La prueba que importa es la del puntero de
 * compresión: antes devolvía un nombre PARCIAL ("youtube") que no matcheaba ninguna
 * regla y el servidor de arriba resolvía completo — una forma de esquivar el filtro.
 */
class DnsPacketParserTest {

    private fun header(): ByteArray = byteArrayOf(
        0x12, 0x34, 0x01, 0x00, 0x00, 0x01, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00
    )

    private fun label(s: String): ByteArray = byteArrayOf(s.length.toByte()) + s.toByteArray()

    @Test
    fun nombreNormal() {
        val q = header() + label("www") + label("youtube") + label("com") + byteArrayOf(0, 0, 1, 0, 1)
        assertEquals("www.youtube.com", DnsPacketParser.extractQueriedDomain(q))
    }

    @Test
    fun punteroDeCompresionEnLaPreguntaNoDevuelveNombreParcial() {
        // "youtube" + puntero a offset 12: un resolutor legítimo nunca arma esto.
        val q = header() + label("youtube") + byteArrayOf(0xC0.toByte(), 0x0C) + byteArrayOf(0, 1, 0, 1)
        assertNull(DnsPacketParser.extractQueriedDomain(q))
    }

    @Test
    fun etiquetaReservadaTambienEsNoParseable() {
        val q = header() + byteArrayOf(0x41) + "x".repeat(10).toByteArray()
        assertNull(DnsPacketParser.extractQueriedDomain(q))
    }

    @Test
    fun truncadoEsNoParseable() {
        val q = header() + byteArrayOf(10) + "abc".toByteArray()
        assertNull(DnsPacketParser.extractQueriedDomain(q))
    }

    @Test
    fun raizEsNoParseable() {
        val q = header() + byteArrayOf(0, 0, 2, 0, 1)
        assertNull(DnsPacketParser.extractQueriedDomain(q))
    }
}
