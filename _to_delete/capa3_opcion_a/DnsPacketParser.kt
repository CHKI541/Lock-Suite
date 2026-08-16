package com.ejemplo.locksuite.util

import java.nio.charset.StandardCharsets

object DnsPacketParser {

    /**
     * Extrae el nombre de dominio consultado en una pregunta DNS (RFC 1035).
     * El payload DNS inicia con 12 bytes de cabecera. A partir del byte 12 comienza
     * la sección Question, estructurada con labels de longitud y texto.
     */
    fun extractQueriedDomain(payload: ByteArray): String? {
        if (payload.size < 12) return null
        
        val domain = StringBuilder()
        var pos = 12 // Saltar cabecera DNS de 12 bytes
        
        try {
            while (pos < payload.size) {
                val len = payload[pos].toInt() and 0xFF
                if (len == 0) {
                    break // Fin del nombre de dominio (byte nulo)
                }
                
                // Si es un puntero de compresión DNS (no debería ocurrir en la pregunta de consulta de origen, pero por seguridad)
                if ((len and 0xC0) == 0xC0) {
                    break
                }
                
                pos++
                if (pos + len > payload.size) {
                    return null // Estructura inválida
                }
                
                val label = String(payload, pos, len, StandardCharsets.US_ASCII)
                if (domain.isNotEmpty()) {
                    domain.append(".")
                }
                domain.append(label)
                pos += len
            }
            return if (domain.isEmpty()) null else domain.toString()
        } catch (e: Exception) {
            e.printStackTrace()
            return null
        }
    }
}
