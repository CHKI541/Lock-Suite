package com.ejemplo.locksuite.util

import android.net.Network
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
    /**
     * Paciencia de los reintentos. Más corta que la del primer intento a propósito: si el
     * resolutor de la red no contestó, lo que importa es darle una respuesta a la app
     * antes de que ELLA se dé por vencida, no agotar los 3,5 s con cada alternativa.
     */
    private const val RETRY_TIMEOUT_MS = 2000

    private data class UpstreamResult(
        val address: InetAddress,
        val network: Network?
    )

    // ──────────────────────────────────────────────────────────────────────────
    // EL RESOLUTOR DE SALIDA  (reescrito el 17/8/2026, endurecido el 25/8/2026)
    //
    // ⚠️ ACÁ ESTABA LA CAUSA MÁS PROBABLE DEL SÍNTOMA "SE CAE INTERNET ENTERO Y
    //    VUELVE APAGANDO Y PRENDIENDO LA VPN".
    //
    // La versión anterior preguntaba `cm.activeNetwork` y se quedaba con el primer
    // DNS de esa red que no fuera loopback ni `10.0.0.1`. El problema: `10.0.0.1` es
    // el DNS virtual IPv4 del propio túnel, pero el túnel TAMBIÉN publica uno IPv6,
    // `fd00::1`, y ESE no estaba en la lista de exclusiones. Alcanzaba con que en
    // algún momento `activeNetwork` devolviera la red del propio VPN (pasa según el
    // fabricante, al reconectar, y en el instante posterior a establecer el túnel)
    // para que el bucle IPv6 de reserva devolviera `fd00::1`.
    //
    // A partir de ahí, cada consulta DNS se mandaba al DNS virtual del túnel desde un
    // socket protegido, o sea hacia afuera del túnel, o sea a ninguna parte. Nadie
    // contesta: 3,5 segundos de timeout por consulta, TODAS las consultas, todas las
    // apps. Para el usuario eso es exactamente "se cayó internet", aunque la red
    // estuviera perfecta — y como el valor se recalculaba igual en cada consulta, no
    // se arreglaba solo; apagar y prender la VPN lo arreglaba porque volvía a
    // consultar en un momento en que `activeNetwork` sí era la red física.
    //
    // Ahora:
    //   • Se elige explícitamente una red SIN `TRANSPORT_VPN`, en vez de confiar en
    //     cuál es "la activa".
    //   • Ninguna dirección del propio túnel puede salir elegida, ni v4 ni v6.
    //   • El resultado se cachea: antes cada consulta hacía tres llamadas al sistema
    //     (getSystemService + activeNetwork + getLinkProperties). Con 40 consultas por
    //     segundo abriendo una app, eso era CPU y batería tirada.
    //   • Se guarda también el objeto `Network` físico asociado y se invoca
    //     `network.bindSocket(socket)` en el socket de salida. Esto evita que
    //     conflictos de rutas entre Wi-Fi y datos móviles (ej. DNS privado de ISP en
    //     rango CGNAT 100.64/10 contra ruta celular 100.0.0.0/8) desvíen los paquetes
    //     hacia la interfaz equivocada causando timeouts.
    //   • KosherVpnService invalida el cache cuando cambia la red.
    // ──────────────────────────────────────────────────────────────────────────

    /** Direcciones del propio túnel: nunca pueden ser el resolutor de salida. */
    private val TUNNEL_ADDRESSES = setOf("10.0.0.1", "10.0.0.2", "fd00::1", "fd00::2")

    /** Red de seguridad por si algo cambia sin avisar por el callback. */
    private const val UPSTREAM_TTL_MS = 30_000L

    @Volatile private var cachedUpstream: InetAddress? = null
    @Volatile private var cachedUpstreamNetwork: Network? = null
    @Volatile private var cachedUpstreamAt = 0L

    /** La llama KosherVpnService ante cualquier cambio de red o al (re)establecer el túnel. */
    fun invalidateUpstreamCache() {
        cachedUpstreamAt = 0L
        cachedUpstream = null
        cachedUpstreamNetwork = null
    }

    private fun isUsableResolver(dns: InetAddress): Boolean =
        !dns.isLoopbackAddress &&
        !dns.isAnyLocalAddress &&
        (dns.hostAddress ?: "") !in TUNNEL_ADDRESSES

    private fun getUpstreamDnsAddress(vpnService: VpnService): UpstreamResult {
        val now = android.os.SystemClock.elapsedRealtime()
        val cached = cachedUpstream
        val cachedNet = cachedUpstreamNetwork
        if (cached != null && now - cachedUpstreamAt < UPSTREAM_TTL_MS) {
            return UpstreamResult(cached, cachedNet)
        }

        val resolved = resolveUpstreamDns(vpnService)
        cachedUpstream = resolved.address
        cachedUpstreamNetwork = resolved.network
        cachedUpstreamAt = now
        return resolved
    }

    private fun resolveUpstreamDns(vpnService: VpnService): UpstreamResult {
        try {
            val cm = vpnService.getSystemService(android.net.ConnectivityManager::class.java)
            if (cm != null) {
                // ⚠️ ARREGLO 1/9/2026 (V-2). Antes esto era un único for sobre
                // cm.allNetworks que devolvía LA PRIMERA red con DNS IPv4 usable. Dos
                // problemas: el orden de allNetworks no está definido (con Wi-Fi y datos
                // arriba a la vez podía elegir la que NO se está usando), y
                // NET_CAPABILITY_INTERNET es la capacidad PEDIDA, no la validada — una
                // Wi-Fi "conectada sin internet" la cumple igual. Ahora hay tres niveles
                // de preferencia, y recién se baja al siguiente si el anterior no dio
                // nada:
                //
                //   1º la red ACTIVA, si no es la nuestra (esto es lo que se había
                //      perdido en B.18: ahí se sacó activeNetwork entero porque podía
                //      devolver el VPN, cuando alcanzaba con descartarlo si lo es);
                //   2º cualquier red VALIDADA (o sea, que el sistema confirmó que sale
                //      a internet de verdad);
                //   3º cualquier red con capacidad de internet — el comportamiento viejo,
                //      que se conserva como último recurso para no empeorar ningún caso
                //      que hoy funcione.
                fun esUsable(net: Network, exigirValidada: Boolean): UpstreamResult? {
                    val caps = cm.getNetworkCapabilities(net) ?: return null
                    if (caps.hasTransport(android.net.NetworkCapabilities.TRANSPORT_VPN)) return null
                    if (!caps.hasCapability(android.net.NetworkCapabilities.NET_CAPABILITY_INTERNET)) return null
                    if (exigirValidada &&
                        !caps.hasCapability(android.net.NetworkCapabilities.NET_CAPABILITY_VALIDATED)
                    ) return null

                    val dnsList = cm.getLinkProperties(net)?.dnsServers ?: return null
                    var v6: InetAddress? = null
                    for (dns in dnsList) {
                        if (!isUsableResolver(dns)) continue
                        // Preferencia a IPv4: es el que existe en prácticamente todas las
                        // redes y el que menos sorpresas da.
                        if (dns is Inet4Address) return UpstreamResult(dns, net)
                        if (v6 == null) v6 = dns
                    }
                    return v6?.let { UpstreamResult(it, net) }   // redes IPv6 puras / DNS64
                }

                // 1º — la red activa.
                cm.activeNetwork?.let { activa ->
                    esUsable(activa, exigirValidada = false)?.let { return it }
                }
                // 2º — cualquier red validada.
                @Suppress("DEPRECATION")
                for (network in cm.allNetworks) {
                    esUsable(network, exigirValidada = true)?.let { return it }
                }
                // 3º — comportamiento anterior, como último recurso.
                @Suppress("DEPRECATION")
                for (network in cm.allNetworks) {
                    esUsable(network, exigirValidada = false)?.let { return it }
                }
            }
        } catch (e: Exception) {
            android.util.Log.w("KosherVPN", "No se pudo resolver el DNS de salida: ${e.message}")
        }

        val customIp = PrefsHelper.getMdmPrefs(vpnService).getString("upstream_dns_ip", "8.8.8.8") ?: "8.8.8.8"
        val fallbackAddress = try {
            val fallback = InetAddress.getByName(customIp)
            if (isUsableResolver(fallback)) fallback else InetAddress.getByName("8.8.8.8")
        } catch (e: Exception) {
            InetAddress.getByName("8.8.8.8")
        }
        return UpstreamResult(fallbackAddress, null)
    }

    private val FALLBACK_DNS_PRIMARY: InetAddress by lazy { InetAddress.getByName("8.8.8.8") }
    private val FALLBACK_DNS_SECONDARY: InetAddress by lazy { InetAddress.getByName("1.1.1.1") }

    /**
     * Verifica si una dirección IPv4 está en el bloque 100.0.0.0/8 (especialmente CGNAT 100.64.0.0/10).
     * En dispositivos con módem celular (ej. MediaTek/Qualcomm), la interfaz de datos móviles suele
     * registrar una ruta amplia 100.0.0.0/8. Si la red Wi-Fi provee un DNS de ISP en ese rango (como
     * Telecentro 100.72.3.101) y no se logra hacer bindSocket a la interfaz Wi-Fi, el kernel desviará
     * el paquete a la red móvil y fallará.
     */
    private fun isCgnatConflictRisk(dns: InetAddress): Boolean {
        val bytes = dns.address
        return bytes.size == 4 && (bytes[0].toInt() and 0xFF) == 100
    }

    fun forwardDnsQuery(
        packet: IpPacketParser.ParsedPacket,
        output: FileOutputStream,
        vpnService: VpnService
    ) {
        var socket: DatagramSocket? = null
        try {
            var sock: DatagramSocket = DatagramSocket().also {
                socket = it
                vpnService.protect(it) // CRÍTICO: Evita bucle infinito de reentrada de red
                it.soTimeout = TIMEOUT_MS
            }

            val upstreamResult = getUpstreamDnsAddress(vpnService)
            val initialUpstream = upstreamResult.address
            var boundSuccessfully = false

            // Vincular el socket a la red física de donde sale el DNS.
            // Esto evita que el kernel elija la interfaz equivocada cuando
            // hay varias redes activas (Wi-Fi + datos móviles). Sin esto,
            // una ruta más específica de otra interfaz puede "robar" el
            // paquete y mandarlo a una red que no conoce el servidor DNS.
            upstreamResult.network?.let { physicalNetwork ->
                try {
                    physicalNetwork.bindSocket(sock)
                    boundSuccessfully = true
                } catch (e: Exception) {
                    android.util.Log.w("KosherVPN", "bindSocket a red física falló: ${e.message}")
                }
            }

            // Si el DNS está en riesgo de conflicto CGNAT (100.x.x.x) y bindSocket no tuvo éxito,
            // evitamos mandar el paquete a 100.x.x.x (el kernel lo desviaría al módem celular).
            var activeResolver = if (!boundSuccessfully && isCgnatConflictRisk(initialUpstream)) {
                android.util.Log.w("KosherVPN", "DNS $initialUpstream en rango CGNAT sin bindSocket; usando fallback $FALLBACK_DNS_PRIMARY")
                FALLBACK_DNS_PRIMARY
            } else {
                initialUpstream
            }

            val responseBuffer = ByteArray(4096)
            val responsePacket = DatagramPacket(responseBuffer, responseBuffer.size)
            var responseReceived = false

            // ⚠️ ARREGLO 1/9/2026 (V-1). Antes esta cadena de reintentos colgaba de
            // `catch (e: SocketTimeoutException)`. Ese era el defecto: cuando el socket
            // quedó vinculado (bindSocket) a una red que se cayó, o que no tiene ruta al
            // DNS elegido, el sistema NO devuelve timeout. Devuelve:
            //
            //   • SocketException: sendto failed: ENETUNREACH / EHOSTUNREACH  (desde send)
            //   • PortUnreachableException                                    (desde receive)
            //
            // Ninguna de las dos es SocketTimeoutException, así que la consulta caía al
            // catch general del final y se descartaba SIN probar ningún otro resolutor.
            // O sea: toda la red de seguridad de B.21 no corría justo en el caso más
            // común. Ahora se captura IOException, que es la superclase de las tres
            // (SocketTimeoutException incluida), y los reintentos se hacen en un bucle
            // en vez de tres try/catch anidados — así agregar un resolutor más es
            // agregar un elemento a una lista, no otro nivel de anidamiento.
            //
            // El orden importa: primero el resolutor que corresponde a la red, después
            // Google, después Cloudflare. Y no se repite el mismo dos veces (V-6: antes,
            // si el resolutor inicial YA era 8.8.8.8, no había ningún reintento).
            val resolvers = LinkedHashSet<InetAddress>().apply {
                add(activeResolver)
                add(FALLBACK_DNS_PRIMARY)
                add(FALLBACK_DNS_SECONDARY)
            }

            for ((intento, resolver) in resolvers.withIndex()) {
                try {
                    if (intento > 0) {
                        // Socket nuevo por intento: uno que ya falló con ENETUNREACH
                        // puede quedar asociado a la red muerta.
                        sock.close()
                        sock = DatagramSocket()
                        socket = sock
                        vpnService.protect(sock)
                        // En el reintento NO vinculamos a upstreamResult.network: si el primer
                        // intento falló por red caída (ENETUNREACH), re-vincular al mismo
                        // Network garantiza que el fallback a 8.8.8.8/1.1.1.1 falle también.
                        // Sin vincular (pero protegido por vpnService.protect), el socket sale
                        // por la ruta por defecto del sistema.
                        // Los reintentos van con menos paciencia que el primero: el
                        // objetivo es responderle rápido a la app, no insistir.
                        sock.soTimeout = RETRY_TIMEOUT_MS
                        android.util.Log.w("KosherVPN", "DNS $activeResolver no respondió; reintentando con $resolver")
                    }
                    activeResolver = resolver
                    sock.send(DatagramPacket(packet.payload, packet.payload.size, resolver, UPSTREAM_DNS_PORT))
                    sock.receive(responsePacket)
                    responseReceived = true
                    break
                } catch (e: java.io.IOException) {
                    // Timeout, red inalcanzable, puerto cerrado: los tres se tratan igual
                    // y se pasa al siguiente resolutor. Se registra el motivo porque la
                    // diferencia entre "timeout" y "ENETUNREACH" es la que dice si el
                    // problema es el servidor o la ruta.
                    android.util.Log.w("KosherVPN", "Consulta a $resolver falló (${e.javaClass.simpleName}: ${e.message})")
                }
            }

            if (!responseReceived) return

            // Verificación 1: la respuesta tiene que venir del resolutor al que le
            // preguntamos. Un socket UDP sin conectar acepta el PRIMER datagrama que
            // llegue, venga de donde venga: alguien en la misma red Wi-Fi (un locutorio,
            // un café, una red abierta) puede adelantarse al resolutor real y responder
            // por él, apuntando cualquier dominio permitido a la IP que quiera.
            //
            // Antes esto se resolvía con socket.connect(), pero se quitó a propósito
            // (commit "Fix split-tunnel internet blockage... removing UDP socket connect")
            // porque connect() después de protect() puede re-asociar el socket a la red
            // del túnel y cortar internet. Comparar la dirección de origen a mano logra lo
            // mismo sin tocar el enrutamiento, así que es el reemplazo seguro.
            if (responsePacket.address != activeResolver) {
                android.util.Log.w("KosherVPN", "Respuesta DNS descartada: vino de ${responsePacket.address}, no del resolutor consultado ($activeResolver).")
                return
            }

            // Verificación 2, independiente de la anterior: el ID de transacción de la
            // respuesta tiene que coincidir con el de la consulta. Cubre el caso de un
            // atacante en la misma red que además falsifique la IP de origen.
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
