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
                
                // Puntero de compresión DNS (o tipo de etiqueta reservado) en la PREGUNTA de
                // una consulta. El resolutor del sistema nunca lo manda. 22/9/2026: antes
                // se cortaba acá y se devolvía lo leído hasta el momento — un nombre
                // PARCIAL ("youtube" en vez de "youtube.com") que no matchea ninguna regla
                // y que el servidor de arriba igual resuelve completo. Era una forma de
                // esquivar el filtro armando el paquete a mano. Ahora es "no parseable",
                // y KosherVpnService contesta bloqueado.
                if ((len and 0xC0) != 0) {
                    return null
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
