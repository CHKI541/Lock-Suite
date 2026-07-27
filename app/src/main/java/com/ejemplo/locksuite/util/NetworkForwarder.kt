package com.ejemplo.locksuite.util

import android.net.VpnService
import java.io.FileOutputStream
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.Inet4Address
import java.net.SocketTimeoutException
import java.nio.ByteBuffer

object NetworkForwarder {

    private const val UPSTREAM_DNS_PORT = 53
    private const val TIMEOUT_MS = 3500

    private fun getUpstreamDnsAddress(vpnService: VpnService): InetAddress {
        try {
            val cm = vpnService.getSystemService(android.net.ConnectivityManager::class.java)
            val activeNetwork = cm?.activeNetwork
            if (activeNetwork != null) {
                val linkProps = cm.getLinkProperties(activeNetwork)
                val dnsList = linkProps?.dnsServers
                if (!dnsList.isNullOrEmpty()) {
                    for (dns in dnsList) {
                        if (dns is Inet4Address && !dns.isLoopbackAddress && dns.hostAddress != "10.0.0.1") {
                            return dns
                        }
                    }
                }
            }
        } catch (e: Exception) {
            // Fallback
        }
        val customIp = PrefsHelper.getMdmPrefs(vpnService).getString("upstream_dns_ip", "8.8.8.8") ?: "8.8.8.8"
        return InetAddress.getByName(customIp)
    }

    fun forwardDnsQuery(
        packet: IpPacketParser.ParsedPacket,
        output: FileOutputStream,
        vpnService: VpnService
    ) {
        var socket: DatagramSocket? = null
        try {
            socket = DatagramSocket()
            vpnService.protect(socket) // CRÍTICO: Evita bucle infinito de reentrada de red
            socket.soTimeout = TIMEOUT_MS

            val upstream = getUpstreamDnsAddress(vpnService)
            // connect() hace que el kernel descarte cualquier datagrama que no venga
            // exactamente del resolutor al que le preguntamos. Antes el socket quedaba
            // sin conectar y receive() aceptaba el PRIMER datagrama que llegara, viniera
            // de donde viniera: alguien en la misma red Wi-Fi (un locutorio, un café, una
            // red abierta) podía adelantarse al resolutor real y responder por él,
            // apuntando cualquier dominio permitido a la IP que quisiera. Con estos
            // celulares conectándose a redes que no controlamos, es una defensa barata
            // que conviene tener.

            socket.send(DatagramPacket(packet.payload, packet.payload.size, upstream, UPSTREAM_DNS_PORT))

            val responseBuffer = ByteArray(4096)
            val responsePacket = DatagramPacket(responseBuffer, responseBuffer.size)
            socket.receive(responsePacket)

            // Segunda verificación, independiente de la anterior: el ID de transacción
            // de la respuesta tiene que coincidir con el de la consulta. Cubre el caso
            // de un atacante en la misma red que además falsifique la IP de origen.
            if (responsePacket.length < 12 || packet.payload.size < 2 ||
                responseBuffer[0] != packet.payload[0] ||
                responseBuffer[1] != packet.payload[1]
            ) {
                android.util.Log.w("KosherVPN", "Respuesta DNS descartada: no coincide el ID de transacción.")
                return
            }

            val responseBytes = responseBuffer.copyOfRange(0, responsePacket.length)
            val ipResponse = if (packet.isIpv6) {
                buildResponseIpPacketV6(packet, responseBytes)
            } else {
                buildResponseIpPacket(packet, responseBytes)
            }
            synchronized(output) {
                output.write(ipResponse)
            }

        } catch (e: SocketTimeoutException) {
            // Sin respuesta, la app original recibirá timeout nativo.
        } catch (e: Exception) {
            android.util.Log.w("KosherVPN", "Fallo reenviando consulta DNS: ${e.message}")
        } catch (e: java.lang.Error) {
            android.util.Log.e("KosherVPN", "Error crítico en envío de red: ${e.message}")
        } finally {
            socket?.close()
        }
    }

    /**
     * Calcula dónde termina la PRIMERA pregunta del payload DNS (QNAME + QTYPE +
     * QCLASS), empezando en el byte 12. Devuelve -1 si la estructura no es válida.
     *
     * Hace falta para no copiar de más al armar la respuesta de bloqueo: ver el
     * comentario largo en sendBlockedDnsResponse().
     */
    private fun findQuestionEnd(payload: ByteArray): Int {
        var pos = 12
        while (pos < payload.size) {
            val len = payload[pos].toInt() and 0xFF
            if (len == 0) {
                pos += 1
                return if (pos + 4 <= payload.size) pos + 4 else -1
            }
            if ((len and 0xC0) == 0xC0) {
                // Puntero de compresión: ocupa 2 bytes y da por terminado el nombre.
                pos += 2
                return if (pos + 4 <= payload.size) pos + 4 else -1
            }
            pos += 1 + len
        }
        return -1
    }

    /**
     * Responde inmediatamente con una dirección "nula" para bloquear la consulta DNS
     * en 0ms a nivel de red VPN.
     *
     * Se corrigieron dos defectos que hacían que la respuesta de bloqueo fuera
     * malformada en buena parte de los casos reales:
     *
     *  1) Se copiaba desde el byte 12 hasta el FINAL del paquete y se declaraba todo
     *     eso como "la pregunta", con ARCOUNT=0. Pero el resolutor de Android manda
     *     EDNS(0) en casi todas las consultas, o sea un registro OPT extra al final:
     *     ese OPT terminaba metido en la respuesta donde el cliente esperaba el
     *     registro A. Resultado: el cliente no encontraba la dirección, daba la
     *     respuesta por inválida y reintentaba hasta agotar el timeout, en vez de
     *     descartar el dominio al instante. Es exactamente el síntoma de "se traba /
     *     tarda un montón" al abrir algo bloqueado. Ahora se copia SOLO la pregunta.
     *
     *  2) Se devolvía siempre un registro A (IPv4) aunque la consulta fuera AAAA
     *     (IPv6), que es lo que preguntan primero muchas apps y navegadores modernos.
     *     Un tipo de registro distinto al preguntado también se interpreta como
     *     respuesta inválida. Ahora se responde con el mismo tipo que se preguntó
     *     (A -> 0.0.0.0, AAAA -> ::) y, para cualquier otro tipo, con una respuesta
     *     vacía correcta (NOERROR sin registros).
     */
    fun sendBlockedDnsResponse(
        packet: IpPacketParser.ParsedPacket,
        output: FileOutputStream
    ) {
        try {
            val originalPayload = packet.payload
            if (originalPayload.size < 12) return

            val id0 = originalPayload[0]
            val id1 = originalPayload[1]

            val questionEnd = findQuestionEnd(originalPayload)
            // Si no podemos leer la pregunta, no inventamos una respuesta: no responder
            // hace que la consulta falle, que es el lado seguro para un filtro.
            if (questionEnd < 0) return

            val questionPayload = originalPayload.copyOfRange(12, questionEnd)
            val qtype = ((originalPayload[questionEnd - 4].toInt() and 0xFF) shl 8) or
                        (originalPayload[questionEnd - 3].toInt() and 0xFF)

            val answerRecord: ByteArray? = when (qtype) {
                1 -> byteArrayOf( // A -> 0.0.0.0
                    0xC0.toByte(), 0x0C,
                    0x00, 0x01,
                    0x00, 0x01,
                    0x00, 0x00, 0x00, 0x3C,
                    0x00, 0x04,
                    0x00, 0x00, 0x00, 0x00
                )
                28 -> byteArrayOf( // AAAA -> ::
                    0xC0.toByte(), 0x0C,
                    0x00, 0x1C,
                    0x00, 0x01,
                    0x00, 0x00, 0x00, 0x3C,
                    0x00, 0x10,
                    0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00,
                    0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x00
                )
                else -> null // NOERROR sin registros (NODATA): correcto para el resto de tipos
            }

            // Conservar el bit RD (recursión pedida) tal como venía en la consulta.
            val originalFlags = ((originalPayload[2].toInt() and 0xFF) shl 8) or
                                (originalPayload[3].toInt() and 0xFF)
            val responseFlags = 0x8000 or (originalFlags and 0x0100) or 0x0080

            val answerSize = answerRecord?.size ?: 0
            val dnsResponse = ByteBuffer.allocate(12 + questionPayload.size + answerSize)
            dnsResponse.put(id0)
            dnsResponse.put(id1)
            dnsResponse.putShort(responseFlags.toShort()) // Response, No Error
            dnsResponse.putShort(1.toShort()) // QDCOUNT = 1
            dnsResponse.putShort((if (answerRecord != null) 1 else 0).toShort()) // ANCOUNT
            dnsResponse.putShort(0.toShort()) // NSCOUNT = 0
            dnsResponse.putShort(0.toShort()) // ARCOUNT = 0
            dnsResponse.put(questionPayload)
            if (answerRecord != null) dnsResponse.put(answerRecord)

            val ipResponse = if (packet.isIpv6) {
                buildResponseIpPacketV6(packet, dnsResponse.array())
            } else {
                buildResponseIpPacket(packet, dnsResponse.array())
            }
            synchronized(output) {
                output.write(ipResponse)
            }
        } catch (e: Exception) {
            android.util.Log.w("KosherVPN", "Fallo enviando respuesta DNS bloqueada: ${e.message}")
        }
    }

    /**
     * Reconstruye un paquete IPv4 y UDP invertido con los datos reales de la respuesta.
     */
    private fun buildResponseIpPacket(
        original: IpPacketParser.ParsedPacket,
        dnsResponsePayload: ByteArray
    ): ByteArray {
        val udpLength = 8 + dnsResponsePayload.size
        val totalLength = 20 + udpLength

        val buffer = ByteBuffer.allocate(totalLength)

        // Header IPv4 (20 bytes)
        buffer.put((4 shl 4 or 5).toByte())
        buffer.put(0)
        buffer.putShort(totalLength.toShort())
        buffer.putShort(0)
        buffer.putShort(0)
        buffer.put(64.toByte())
        buffer.put(IpPacketParser.PROTO_UDP.toByte())
        buffer.putShort(0) // Checksum IP temporal
        buffer.put(original.destIp.address)
        buffer.put(original.sourceIp.address)

        // Header UDP (8 bytes)
        buffer.putShort(original.destPort.toShort())
        buffer.putShort(original.sourcePort.toShort())
        buffer.putShort(udpLength.toShort())
        buffer.putShort(0) // Checksum UDP temporal

        buffer.put(dnsResponsePayload)

        val result = buffer.array()
        insertIpChecksum(result)

        // Checksum UDP IPv4 opcional pero recomendado
        val udpChecksum = calculateUdpChecksumV4(original.destIp.address, original.sourceIp.address, udpLength, result)
        result[26] = (udpChecksum shr 8).toByte()
        result[27] = (udpChecksum and 0xFF).toByte()

        return result
    }

    /**
     * Reconstruye un paquete IPv6 y UDP invertido con checksum RFC 2460 válido.
     */
    private fun buildResponseIpPacketV6(
        original: IpPacketParser.ParsedPacket,
        dnsResponsePayload: ByteArray
    ): ByteArray {
        val udpLength = 8 + dnsResponsePayload.size
        val totalLength = 40 + udpLength

        val buffer = ByteBuffer.allocate(totalLength)

        // Header IPv6 (40 bytes)
        buffer.put(0x60.toByte()) // Version 6, Traffic Class 0
        buffer.put(0.toByte())
        buffer.putShort(0.toShort()) // Flow label
        buffer.putShort(udpLength.toShort()) // Payload length
        buffer.put(IpPacketParser.PROTO_UDP.toByte()) // Next Header
        buffer.put(64.toByte()) // Hop Limit
        buffer.put(original.destIp.address)
        buffer.put(original.sourceIp.address)

        // Header UDP (8 bytes)
        buffer.putShort(original.destPort.toShort())
        buffer.putShort(original.sourcePort.toShort())
        buffer.putShort(udpLength.toShort())
        buffer.putShort(0) // Checksum UDP temporal

        buffer.put(dnsResponsePayload)

        val result = buffer.array()
        // Checksum UDP IPv6 OBLIGATORIO (RFC 2460 / RFC 8200)
        val udpChecksum = calculateUdpChecksumV6(original.destIp.address, original.sourceIp.address, udpLength, result)
        result[46] = (udpChecksum shr 8).toByte()
        result[47] = (udpChecksum and 0xFF).toByte()

        return result
    }

    private fun calculateUdpChecksumV4(
        srcIp: ByteArray,
        dstIp: ByteArray,
        udpLength: Int,
        packet: ByteArray
    ): Int {
        var sum = 0L

        for (i in 0 until 4 step 2) {
            sum += ((srcIp[i].toInt() and 0xFF) shl 8) or (srcIp[i + 1].toInt() and 0xFF)
        }
        for (i in 0 until 4 step 2) {
            sum += ((dstIp[i].toInt() and 0xFF) shl 8) or (dstIp[i + 1].toInt() and 0xFF)
        }
        sum += IpPacketParser.PROTO_UDP
        sum += udpLength and 0xFFFF

        val udpStart = 20
        var i = 0
        while (i < udpLength - 1) {
            val word = ((packet[udpStart + i].toInt() and 0xFF) shl 8) or (packet[udpStart + i + 1].toInt() and 0xFF)
            sum += word
            i += 2
        }
        if (i < udpLength) {
            val word = (packet[udpStart + i].toInt() and 0xFF) shl 8
            sum += word
        }

        while (sum shr 16 != 0L) {
            sum = (sum and 0xFFFFL) + (sum shr 16)
        }

        var checksum = sum.toInt().inv() and 0xFFFF
        if (checksum == 0) checksum = 0xFFFF
        return checksum
    }

    private fun calculateUdpChecksumV6(
        srcIp: ByteArray,
        dstIp: ByteArray,
        udpLength: Int,
        packet: ByteArray
    ): Int {
        var sum = 0L

        for (i in 0 until 16 step 2) {
            sum += ((srcIp[i].toInt() and 0xFF) shl 8) or (srcIp[i + 1].toInt() and 0xFF)
        }
        for (i in 0 until 16 step 2) {
            sum += ((dstIp[i].toInt() and 0xFF) shl 8) or (dstIp[i + 1].toInt() and 0xFF)
        }
        sum += (udpLength shr 16) and 0xFFFF
        sum += udpLength and 0xFFFF
        sum += IpPacketParser.PROTO_UDP

        val udpStart = 40
        var i = 0
        while (i < udpLength - 1) {
            val word = ((packet[udpStart + i].toInt() and 0xFF) shl 8) or (packet[udpStart + i + 1].toInt() and 0xFF)
            sum += word
            i += 2
        }
        if (i < udpLength) {
            val word = (packet[udpStart + i].toInt() and 0xFF) shl 8
            sum += word
        }

        while (sum shr 16 != 0L) {
            sum = (sum and 0xFFFFL) + (sum shr 16)
        }

        var checksum = sum.toInt().inv() and 0xFFFF
        if (checksum == 0) checksum = 0xFFFF
        return checksum
    }

    /**
     * Calcula e inyecta el checksum obligatorio para la cabecera de IPv4 (RFC 791).
     */
    private fun insertIpChecksum(packet: ByteArray) {
        packet[10] = 0
        packet[11] = 0

        var sum = 0
        var i = 0
        while (i < 20) {
            val word = ((packet[i].toInt() and 0xFF) shl 8) or (packet[i + 1].toInt() and 0xFF)
            sum += word
            i += 2
        }
        while (sum shr 16 != 0) {
            sum = (sum and 0xFFFF) + (sum shr 16)
        }
        val checksum = sum.inv() and 0xFFFF
        packet[10] = (checksum shr 8).toByte()
        packet[11] = (checksum and 0xFF).toByte()
    }
}
