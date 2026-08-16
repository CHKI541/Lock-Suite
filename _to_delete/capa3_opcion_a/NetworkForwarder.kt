package com.ejemplo.locksuite.util

import android.net.VpnService
import java.io.FileOutputStream
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.SocketTimeoutException
import java.nio.ByteBuffer

object NetworkForwarder {

    private const val UPSTREAM_DNS_IP = "8.8.8.8"
    private const val UPSTREAM_DNS_PORT = 53
    private const val TIMEOUT_MS = 4000

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

            val upstream = InetAddress.getByName(UPSTREAM_DNS_IP)
            socket.send(DatagramPacket(packet.payload, packet.payload.size, upstream, UPSTREAM_DNS_PORT))

            val responseBuffer = ByteArray(4096)
            val responsePacket = DatagramPacket(responseBuffer, responseBuffer.size)
            socket.receive(responsePacket)

            val responseBytes = responseBuffer.copyOfRange(0, responsePacket.length)
            output.write(buildResponseIpPacket(packet, responseBytes))

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
        buffer.putShort(0) // Checksum temporal
        buffer.put(original.destIp.address)
        buffer.put(original.sourceIp.address)

        // Header UDP (8 bytes)
        buffer.putShort(original.destPort.toShort())
        buffer.putShort(original.sourcePort.toShort())
        buffer.putShort(udpLength.toShort())
        buffer.putShort(0) // Opcional para IPv4 UDP

        buffer.put(dnsResponsePayload)

        val result = buffer.array()
        insertIpChecksum(result)
        return result
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
