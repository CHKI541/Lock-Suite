package com.ejemplo.locksuite.util

import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.net.InetAddress
import java.nio.ByteBuffer

class NetworkForwarderTest {

    // Helper to build a DNS question for "test.com"
    // Format: \x04test\x03com\x00 (total 10 bytes)
    // QTYPE: 2 bytes
    // QCLASS: 2 bytes (0x0001)
    private fun buildDnsPayload(qtype: Short, addEdns: Boolean = false): ByteArray {
        val domainBytes = byteArrayOf(
            4, 't'.code.toByte(), 'e'.code.toByte(), 's'.code.toByte(), 't'.code.toByte(),
            3, 'c'.code.toByte(), 'o'.code.toByte(), 'm'.code.toByte(),
            0
        )
        val questionSize = domainBytes.size + 4 // domain + qtype (2) + qclass (2)
        val ednsSize = if (addEdns) 11 else 0 // OPT RR size is 11 bytes

        val buffer = ByteBuffer.allocate(12 + questionSize + ednsSize)
        
        // Transaction ID: 0x1234
        buffer.putShort(0x1234.toShort())
        // Flags: Standard query, recursion desired (0x0100)
        buffer.putShort(0x0100.toShort())
        // QDCOUNT = 1
        buffer.putShort(1.toShort())
        // ANCOUNT, NSCOUNT, ARCOUNT = 0
        buffer.putShort(0.toShort())
        buffer.putShort(0.toShort())
        
        // We set ARCOUNT to 1 if EDNS is present (header offset 10)
        buffer.putShort(10, (if (addEdns) 1 else 0).toShort())

        // Question name
        buffer.put(12, domainBytes)
        // QTYPE
        buffer.putShort(12 + domainBytes.size, qtype)
        // QCLASS
        buffer.putShort(12 + domainBytes.size + 2, 1.toShort()) // IN

        if (addEdns) {
            val optOffset = 12 + questionSize
            // OPT pseudo-RR: name = 0 (root)
            buffer.put(optOffset, 0.toByte())
            // type = 41 (OPT)
            buffer.putShort(optOffset + 1, 41.toShort())
            // class = 4096 (UDP payload size)
            buffer.putShort(optOffset + 3, 4096.toShort())
            // TTL / Ext RCODE & flags = 0
            buffer.putInt(optOffset + 5, 0)
            // RD_LEN = 0
            buffer.putShort(optOffset + 9, 0.toShort())
        }

        return buffer.array()
    }

    @Test
    fun testBlockedDnsResponseIPv4_A_Record() {
        val payload = buildDnsPayload(1) // QTYPE = 1 (A)
        val parsedPacket = IpPacketParser.ParsedPacket(
            protocol = IpPacketParser.PROTO_UDP,
            sourceIp = InetAddress.getByName("192.168.1.5"),
            sourcePort = 12345,
            destIp = InetAddress.getByName("8.8.8.8"),
            destPort = 53,
            payload = payload,
            isIpv6 = false
        )

        val tempFile = File.createTempFile("dns_test_ipv4_a", ".bin")
        tempFile.deleteOnExit()
        
        val fos = FileOutputStream(tempFile)
        NetworkForwarder.sendBlockedDnsResponse(
            parsedPacket,
            fos
        )
        fos.close()

        val responseBytes = tempFile.readBytes()
        assertTrue("Response should not be empty", responseBytes.isNotEmpty())

        val parsedResponse = IpPacketParser.parse(responseBytes, responseBytes.size)
        assertNotNull("Response should be a valid UDP packet", parsedResponse)
        
        // Check IPs and ports (they should be swapped)
        assertEquals(InetAddress.getByName("8.8.8.8"), parsedResponse!!.sourceIp)
        assertEquals(InetAddress.getByName("192.168.1.5"), parsedResponse.destIp)
        assertEquals(53, parsedResponse.sourcePort)
        assertEquals(12345, parsedResponse.destPort)

        val dnsResponsePayload = parsedResponse.payload
        val dnsBuffer = ByteBuffer.wrap(dnsResponsePayload)

        // Transaction ID
        assertEquals(0x1234.toShort(), dnsBuffer.getShort())
        // Flags: Response, No Error, RD=1, RA=1 (0x8180)
        val flags = dnsBuffer.getShort().toInt() and 0xFFFF
        assertEquals(0x8180, flags)
        // QDCOUNT = 1
        assertEquals(1.toShort(), dnsBuffer.getShort())
        // ANCOUNT = 1
        assertEquals(1.toShort(), dnsBuffer.getShort())
        // NSCOUNT, ARCOUNT = 0
        assertEquals(0.toShort(), dnsBuffer.getShort())
        assertEquals(0.toShort(), dnsBuffer.getShort())

        // Question: name (10 bytes) + qtype (2) + qclass (2) = 14 bytes
        val questionName = ByteArray(10)
        dnsBuffer.get(questionName)
        assertArrayEquals(byteArrayOf(
            4, 't'.code.toByte(), 'e'.code.toByte(), 's'.code.toByte(), 't'.code.toByte(),
            3, 'c'.code.toByte(), 'o'.code.toByte(), 'm'.code.toByte(),
            0
        ), questionName)
        assertEquals(1.toShort(), dnsBuffer.getShort()) // QTYPE = A
        assertEquals(1.toShort(), dnsBuffer.getShort()) // QCLASS = IN

        // Answer Section: Name (pointer 0xC00C) + TYPE (2) + CLASS (2) + TTL (4) + RDLENGTH (2) + RDATA (4 bytes of 0.0.0.0)
        assertEquals(0xC00C.toShort(), dnsBuffer.getShort()) // Pointer to name
        assertEquals(1.toShort(), dnsBuffer.getShort()) // TYPE = A
        assertEquals(1.toShort(), dnsBuffer.getShort()) // CLASS = IN
        assertEquals(60, dnsBuffer.getInt()) // TTL = 60
        assertEquals(4.toShort(), dnsBuffer.getShort()) // RDLENGTH = 4
        
        val ipAddress = ByteArray(4)
        dnsBuffer.get(ipAddress)
        assertArrayEquals(byteArrayOf(0, 0, 0, 0), ipAddress) // IP = 0.0.0.0
    }

    @Test
    fun testBlockedDnsResponseIPv6_AAAA_Record_With_EDNS() {
        // Query for AAAA (28) with EDNS (addEdns = true)
        val payload = buildDnsPayload(28, addEdns = true)
        val parsedPacket = IpPacketParser.ParsedPacket(
            protocol = IpPacketParser.PROTO_UDP,
            sourceIp = InetAddress.getByName("2001:db8::1"),
            sourcePort = 12345,
            destIp = InetAddress.getByName("2001:4860:4860::8888"),
            destPort = 53,
            payload = payload,
            isIpv6 = true
        )

        val tempFile = File.createTempFile("dns_test_ipv6_aaaa", ".bin")
        tempFile.deleteOnExit()
        
        val fos = FileOutputStream(tempFile)
        NetworkForwarder.sendBlockedDnsResponse(
            parsedPacket,
            fos
        )
        fos.close()

        val responseBytes = tempFile.readBytes()
        assertTrue("Response should not be empty", responseBytes.isNotEmpty())

        val parsedResponse = IpPacketParser.parse(responseBytes, responseBytes.size)
        assertNotNull("Response should be parsed as a valid UDP packet", parsedResponse)
        assertTrue("Response should be IPv6", parsedResponse!!.isIpv6)
        
        // Check IPs and ports
        assertEquals(InetAddress.getByName("2001:4860:4860::8888"), parsedResponse.sourceIp)
        assertEquals(InetAddress.getByName("2001:db8::1"), parsedResponse.destIp)

        val dnsResponsePayload = parsedResponse.payload
        val dnsBuffer = ByteBuffer.wrap(dnsResponsePayload)

        // Transaction ID
        assertEquals(0x1234.toShort(), dnsBuffer.getShort())
        // Flags
        val flags = dnsBuffer.getShort().toInt() and 0xFFFF
        assertEquals(0x8180, flags)
        // QDCOUNT = 1
        assertEquals(1.toShort(), dnsBuffer.getShort())
        // ANCOUNT = 1
        assertEquals(1.toShort(), dnsBuffer.getShort())
        // NSCOUNT, ARCOUNT = 0 (EDNS OPT should be stripped from response)
        assertEquals(0.toShort(), dnsBuffer.getShort())
        assertEquals(0.toShort(), dnsBuffer.getShort())

        // Question Name & QTYPE & QCLASS
        val questionName = ByteArray(10)
        dnsBuffer.get(questionName)
        assertEquals(28.toShort(), dnsBuffer.getShort()) // QTYPE = AAAA (28)
        assertEquals(1.toShort(), dnsBuffer.getShort()) // QCLASS = IN

        // Answer Section: Name (pointer 0xC00C) + TYPE (28) + CLASS (1) + TTL (60) + RDLENGTH (16) + RDATA (16 bytes of ::)
        assertEquals(0xC00C.toShort(), dnsBuffer.getShort())
        assertEquals(28.toShort(), dnsBuffer.getShort()) // TYPE = AAAA
        assertEquals(1.toShort(), dnsBuffer.getShort()) // CLASS = IN
        assertEquals(60, dnsBuffer.getInt())
        assertEquals(16.toShort(), dnsBuffer.getShort()) // RDLENGTH = 16
        
        val ipAddress = ByteArray(16)
        dnsBuffer.get(ipAddress)
        assertArrayEquals(ByteArray(16), ipAddress) // IP = :: (all zeros)
        
        // Verify that there are no remaining bytes in the buffer (meaning the EDNS OPT record was completely stripped)
        assertFalse("Should have no extra bytes", dnsBuffer.hasRemaining())
    }
}
