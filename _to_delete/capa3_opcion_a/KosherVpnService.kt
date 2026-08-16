package com.ejemplo.locksuite.service

import android.net.ConnectivityManager
import android.net.VpnService
import android.content.Intent
import android.os.ParcelFileDescriptor
import android.system.OsConstants
import com.ejemplo.locksuite.util.DnsPacketParser
import com.ejemplo.locksuite.util.IpPacketParser
import com.ejemplo.locksuite.util.NetworkForwarder
import java.io.FileInputStream
import java.io.FileOutputStream
import java.net.InetSocketAddress

class KosherVpnService : VpnService() {

    private var vpnInterface: ParcelFileDescriptor? = null
    @Volatile private var running = false
    private lateinit var connectivityManager: ConnectivityManager

    // Mapeo inicial de políticas de filtrado para Waze y DiDi.
    // Los dominios de WebView secundarios que queremos bloquear NO deben ser listados en la whitelist.
    private val perAppDomainPolicy: Map<String, Set<String>> = mapOf(
        "com.waze" to setOf(
            "waze.com",
            "wazestatic.com",
            "waze-cdn.com"
            // Se bloquea el acceso de la sección Ayuda al omitir sus dominios específicos de Zendesk/Help
        ),
        "com.didiglobal.passenger" to setOf(
            "didiglobal.com",
            "xiaojukeji.com",
            "diditaxi.com.cn"
            // Se bloquea el acceso a minijuegos/minidramas al omitir dominios de CDN de juegos/módulos web
        )
    )

    override fun onCreate() {
        super.onCreate()
        connectivityManager = getSystemService(ConnectivityManager::class.java)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val action = intent?.action
        if (action == "STOP_VPN") {
            stopVpn()
            stopSelf()
        } else {
            startVpn()
        }
        return START_STICKY
    }

    private fun startVpn() {
        if (running) return
        try {
            vpnInterface = Builder()
                .setSession("Filtro Kosher DNS")
                .addAddress("10.0.0.2", 32)
                .addDnsServer("10.0.0.1")
                .addRoute("10.0.0.1", 32) // Solo captura las consultas dirigidas al DNS virtual
                .setBlocking(true)
                .setMtu(1500)
                .establish()

            running = true
            Thread { runFilterLoop() }.start()
            android.util.Log.i("KosherVPN", "Servicio VPN iniciado exitosamente.")
        } catch (e: Exception) {
            android.util.Log.e("KosherVPN", "Error al iniciar VPN: ${e.message}")
        }
    }

    private fun runFilterLoop() {
        val iface = vpnInterface ?: return
        val input = FileInputStream(iface.fileDescriptor)
        val output = FileOutputStream(iface.fileDescriptor)
        val buffer = ByteArray(4096)

        try {
            while (running) {
                val length = input.read(buffer)
                if (length <= 0) continue

                // Solo decodificar paquetes UDP dirigidos al puerto 53
                val packet = IpPacketParser.parse(buffer, length) ?: continue
                if (packet.protocol != IpPacketParser.PROTO_UDP || packet.destPort != 53) continue

                handleDnsQuery(packet, output)
            }
        } catch (e: Exception) {
            android.util.Log.e("KosherVPN", "Error en bucle de filtrado VPN: ${e.message}")
        } finally {
            try {
                input.close()
                output.close()
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }
    }

    private fun handleDnsQuery(packet: IpPacketParser.ParsedPacket, output: FileOutputStream) {
        // Encontrar la app origen de la consulta DNS usando la API del sistema
        val ownerUid = connectivityManager.getConnectionOwnerUid(
            OsConstants.IPPROTO_UDP,
            InetSocketAddress(packet.sourceIp, packet.sourcePort),
            InetSocketAddress(packet.destIp, packet.destPort)
        )
        if (ownerUid == android.os.Process.INVALID_UID) return

        val packageName = packageManager.getPackagesForUid(ownerUid)?.firstOrNull() ?: "desconocido"
        val queriedDomain = DnsPacketParser.extractQueriedDomain(packet.payload) ?: return

        // Aplicar la lógica de whitelist
        val allowedDomains = perAppDomainPolicy[packageName]
        val isRestrictedApp = allowedDomains != null
        val isAllowed = !isRestrictedApp || allowedDomains.any { queriedDomain.endsWith(it) }

        android.util.Log.d(
            "KosherVPN",
            "pkg=$packageName uid=$ownerUid dominio=$queriedDomain permitido=$isAllowed"
        )

        if (isAllowed) {
            NetworkForwarder.forwardDnsQuery(packet, output, this)
        }
        // Si no está permitido: se omite el reenvío de red (causa DNS timeout en la app original, bloqueando el WebView)
    }

    private fun stopVpn() {
        running = false
        try {
            vpnInterface?.close()
        } catch (e: Exception) {
            e.printStackTrace()
        }
        vpnInterface = null
        android.util.Log.i("KosherVPN", "Servicio VPN detenido.")
    }

    override fun onDestroy() {
        stopVpn()
        super.onDestroy()
    }
}
