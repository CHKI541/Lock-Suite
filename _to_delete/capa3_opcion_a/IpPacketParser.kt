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
        val payload: ByteArray
    )

    /**
     * Parsea un paquete binario IPv4 y UDP.
     * Ignora cualquier otro protocolo (ej. TCP) para mayor eficiencia del túnel.
     */
    fun parse(buffer: ByteArray, length: Int): ParsedPacket? {
        if (length < 20) return null

        val versionAndIhl = buffer[0].toInt() and 0xFF
        val version = versionAndIhl shr 4
        if (version != 4) return null // Solo IPv4

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

        return ParsedPacket(protocol, sourceIp, sourcePort, destIp, destPort, payload)
    }
}
