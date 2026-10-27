package com.ejemplo.locksuite.mdm

import com.ejemplo.locksuite.mdm.UpdateStepLog.Step
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * 26/10/2026 (B.97) — el registro paso a paso de una actualización. Lo que importa: que
 * no se rompa con texto raro, que no crezca sin tope, que no se llene de repeticiones
 * del ciclo de 700 ms y que nunca pierda el primer paso (qué se pidió y por qué camino).
 */
class UpdateStepLogTest {

    @Test
    fun idaYVuelta_conSeparadoresEnElMensaje() {
        var raw = ""
        raw = UpdateStepLog.append(raw, Step(1000, UpdateStepLog.INFO, "Pedido: actualizar WhatsApp"))
        raw = UpdateStepLog.append(raw, Step(2000, UpdateStepLog.ACT, "Apreté 'Actualizar' | botón por texto\nsegunda línea"))
        val l = UpdateStepLog.decode(raw)
        assertEquals(2, l.size)
        assertEquals(1000L, l[0].at)
        assertEquals(UpdateStepLog.ACT, l[1].kind)
        // Ni la barra ni el salto de línea pueden partir el formato.
        assertTrue(l[1].msg, !l[1].msg.contains('|') && !l[1].msg.contains('\n'))
        assertTrue(l[1].msg.contains("segunda línea"))
    }

    @Test
    fun noRepiteElMismoPasoSeguido() {
        var raw: String? = null
        repeat(20) { i -> raw = UpdateStepLog.append(raw, Step(1000L + i, UpdateStepLog.INFO, "Esperando a Google Play…")) }
        assertEquals(1, UpdateStepLog.decode(raw).size)
        raw = UpdateStepLog.append(raw, Step(5000, UpdateStepLog.WARN, "Esperando a Google Play…"))
        assertEquals("mismo texto, otro tipo: sí se anota", 2, UpdateStepLog.decode(raw).size)
    }

    @Test
    fun topeConservaElPrimero() {
        var raw: String? = null
        raw = UpdateStepLog.append(raw, Step(1, UpdateStepLog.INFO, "PRIMERO"))
        for (i in 2..200) raw = UpdateStepLog.append(raw, Step(i.toLong(), UpdateStepLog.INFO, "paso $i"))
        val l = UpdateStepLog.decode(raw)
        assertEquals(UpdateStepLog.MAX_STEPS, l.size)
        assertEquals("PRIMERO", l.first().msg)
        assertEquals("paso 200", l.last().msg)
    }

    @Test
    fun mensajesLargosSeRecortan_yVaciosNoSeAnotan() {
        var raw = UpdateStepLog.append(null, Step(1, UpdateStepLog.INFO, "x".repeat(1000)))
        assertEquals(UpdateStepLog.MAX_MSG, UpdateStepLog.decode(raw)[0].msg.length)
        raw = UpdateStepLog.append(raw, Step(2, UpdateStepLog.INFO, "   "))
        assertEquals(1, UpdateStepLog.decode(raw).size)
    }

    @Test
    fun decode_ignoraLineasRotas_yTiposDesconocidos() {
        val raw = "abc|info|x\n123|rarisimo|y\n\n456|ok\n789|error|z|con|barras"
        val l = UpdateStepLog.decode(raw)
        assertEquals(2, l.size)
        assertEquals(UpdateStepLog.INFO, l[0].kind)   // "rarisimo" → info
        assertEquals("z|con|barras", l[1].msg)         // limit=3: el resto es mensaje
    }

    @Test
    fun colaParaLaPantallaDelCelular() {
        var raw: String? = null
        raw = UpdateStepLog.append(raw, Step(10_000, UpdateStepLog.INFO, "Pedido"))
        raw = UpdateStepLog.append(raw, Step(12_400, UpdateStepLog.ACT, "Abrí Google Play"))
        raw = UpdateStepLog.append(raw, Step(25_000, UpdateStepLog.OK, "Listo"))
        val t = UpdateStepLog.tailForOverlay(raw, 2)
        assertEquals("+2s › Abrí Google Play\n+15s ✓ Listo", t)
        assertEquals("", UpdateStepLog.tailForOverlay(null, 3))
    }
}
