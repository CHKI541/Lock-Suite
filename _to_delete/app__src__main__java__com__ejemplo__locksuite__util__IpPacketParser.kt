package com.ejemplo.locksuite.util

import java.net.InetAddress

object IpPacketParser {
    const val PROTO_UDP = 17

    data class ParsedPacket(
        val protocol: Int,
        val sourceIp: InetAddress,
        val sourcePort: Int,
        val destIp: InetAddress,
        val destPort: Int,
        val payload: ByteArray,
        val isIpv6: Boolean = false
    )

    /**
     * Parsea un paquete binario IPv4 o IPv6 UDP.
     * Ignora cualquier otro protocolo (ej. TCP) para mayor eficiencia del túnel.
     */
    fun parse(buffer: ByteArray, length: Int): ParsedPacket? {
        if (length < 20) return null

        val versionAndIhl = buffer[0].toInt() and 0xFF
        val version = versionAndIhl shr 4

        if (version == 4) {
            val ihl = versionAndIhl and 0x0F
            val ipHeaderLength = ihl * 4
            if (ipHeaderLength < 20 || length < ipHeaderLength) return null

            val protocol = buffer[9].toInt() and 0xFF
            if (protocol != PROTO_UDP) return null // Solo UDP

            val sourceIp = InetAddress.getByAddress(buffer.copyOfRange(12, 16))
            val destIp = InetAddress.getByAddress(buffer.copyOfRange(16, 20))

            val udpHeaderLength = 8
            if (length < ipHeaderLength + udpHeaderLength) return null

            val sourcePort = ((buffer[ipHeaderLength].toInt() and 0xFF) shl 8) or
                              (buffer[ipHeaderLength + 1].toInt() and 0xFF)
            val destPort = ((buffer[ipHeaderLength + 2].toInt() and 0xFF) shl 8) or
                            (buffer[ipHeaderLength + 3].toInt() and 0xFF)

            val payloadStart = ipHeaderLength + udpHeaderLength
            if (payloadStart > length) return null
            val payload = buffer.copyOfRange(payloadStart, length)

            return ParsedPacket(protocol, sourceIp, sourcePort, destIp, destPort, payload, isIpv6 = false)
        } else if (version == 6) {
            if (length < 40) return null

            val nextHeader = buffer[6].toInt() and 0xFF
            if (nextHeader != PROTO_UDP) return null // Solo UDP

            val sourceIp = InetAddress.getByAddress(buffer.copyOfRange(8, 24))
            val destIp = InetAddress.getByAddress(buffer.copyOfRange(24, 40))

            val ipHeaderLength = 40
            val udpHeaderLength = 8
            if (length < ipHeaderLength + udpHeaderLength) return null

            val sourcePort = ((buffer[ipHeaderLength].toInt() and 0xFF) shl 8) or
                              (buffer[ipHeaderLength + 1].toInt() and 0xFF)
            val destPort = ((buffer[ipHeaderLength + 2].toInt() and 0xFF) shl 8) or
                            (buffer[ipHeaderLength + 3].toInt() and 0xFF)

            val payloadStart = ipHeaderLength + udpHeaderLength
            if (payloadStart > length) return null
            val payload = buffer.copyOfRange(payloadStart, length)

            return ParsedPacket(PROTO_UDP, sourceIp, sourcePort, destIp, destPort, payload, isIpv6 = true)
        }

        return null
    }
}

