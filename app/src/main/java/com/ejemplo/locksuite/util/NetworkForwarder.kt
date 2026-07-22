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
            socket.send(DatagramPacket(packet.payload, packet.payload.size, upstream, UPSTREAM_DNS_PORT))

            val responseBuffer = ByteArray(4096)
            val responsePacket = DatagramPacket(responseBuffer, responseBuffer.size)
            socket.receive(responsePacket)

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
     * Responde inmediatamente con una dirección IP 0.0.0.0 para bloquear la consulta DNS en 0ms a nivel de red VPN.
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

            val questionPayload = originalPayload.copyOfRange(12, originalPayload.size)

            val answerRecord = byteArrayOf(
                0xc0.toByte(), 0x0c.toByte(),
                0x00.toByte(), 0x01.toByte(),
                0x00.toByte(), 0x01.toByte(),
                0x00.toByte(), 0x00.toByte(), 0x00.toByte(), 0x3c.toByte(),
                0x00.toByte(), 0x04.toByte(),
                0x00.toByte(), 0x00.toByte(), 0x00.toByte(), 0x00.toByte()
            )

            val dnsResponse = ByteBuffer.allocate(12 + questionPayload.size + answerRecord.size)
            dnsResponse.put(id0)
            dnsResponse.put(id1)
            dnsResponse.putShort(0x8180.toShort()) // Response, No Error
            dnsResponse.putShort(1.toShort()) // QDCOUNT = 1
            dnsResponse.putShort(1.toShort()) // ANCOUNT = 1
            dnsResponse.putShort(0.toShort()) // NSCOUNT = 0
            dnsResponse.putShort(0.toShort()) // ARCOUNT = 0
            dnsResponse.put(questionPayload)
            dnsResponse.put(answerRecord)

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
