package com.ejemplo.locksuite.service

import android.net.ConnectivityManager
import android.net.VpnService
import android.content.Intent
import android.os.ParcelFileDescriptor
import android.os.Build
import android.system.OsConstants
import com.ejemplo.locksuite.mdm.WebViewBlockManager
import com.ejemplo.locksuite.mdm.WebViewPolicy
import com.ejemplo.locksuite.util.AdBlocker
import com.ejemplo.locksuite.util.DnsPacketParser
import com.ejemplo.locksuite.util.IpPacketParser
import com.ejemplo.locksuite.util.NetworkForwarder
import com.ejemplo.locksuite.util.PrefsHelper
import java.io.FileInputStream
import java.io.FileOutputStream
import java.net.InetSocketAddress

class KosherVpnService : VpnService() {

    private var vpnInterface: ParcelFileDescriptor? = null
    @Volatile private var running = false
    private lateinit var connectivityManager: ConnectivityManager
    private var dnsExecutor: java.util.concurrent.ExecutorService? = null

    override fun onCreate() {
        super.onCreate()
        connectivityManager = getSystemService(ConnectivityManager::class.java)
        // Cargar la lista de bloqueo de anuncios de forma asíncrona al iniciar
        AdBlocker.loadAsync(applicationContext)
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

    private fun buildNotification(): android.app.Notification {
        val channelId = "locksuite_vpn_channel"
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = android.app.NotificationChannel(
                channelId,
                "Servicio de VPN Kosher",
                android.app.NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Filtro de seguridad DNS de LockSuite"
                setShowBadge(false)
            }
            val manager = getSystemService(android.app.NotificationManager::class.java)
            manager?.createNotificationChannel(channel)
        }
        return androidx.core.app.NotificationCompat.Builder(this, channelId)
            .setContentTitle("Filtro de Contenido LockSuite")
            .setContentText("Filtrando conexiones a internet.")
            .setSmallIcon(android.R.drawable.ic_lock_lock)
            .setPriority(androidx.core.app.NotificationCompat.PRIORITY_LOW)
            .setOngoing(true)
            .build()
    }

    private fun startVpn() {
        if (running) return
        try {
            startForeground(9002, buildNotification())

            // Desactivar DNS privado para evitar que Android envíe consultas cifradas por TCP 853 saltándose la VPN
            try {
                val policyManager = com.ejemplo.locksuite.mdm.PolicyManager(applicationContext)
                policyManager.disablePrivateDns()
            } catch (e: Exception) {
                e.printStackTrace()
            }

            val builder = Builder()
                .setSession("Filtro Kosher DNS")
                .addAddress("10.0.0.2", 32)
                .addDnsServer("10.0.0.1")
                .addRoute("10.0.0.1", 32) // Captura todas las consultas dirigidas al DNS virtual IPv4
                .addAddress("fd00::2", 128)
                .addDnsServer("fd00::1")
                .addRoute("fd00::1", 128) // Captura todas las consultas dirigidas al DNS virtual IPv6
                .setBlocking(true)
                .setMtu(1500)

            // Excluir la propia app LockSuite para que sus peticiones upstream/FCM no pasen por el túnel
            try {
                builder.addDisallowedApplication(packageName)
            } catch (e: Exception) {
                android.util.Log.w("KosherVPN", "No se pudo desautorizar la propia app de la VPN: ${e.message}")
            }

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                try {
                    builder.setUnderlyingNetworks(null) // Usar las redes físicas activas del sistema (Wi-Fi / Móvil)
                } catch (e: Exception) {
                    e.printStackTrace()
                }
            }

            vpnInterface = builder.establish()

            dnsExecutor = java.util.concurrent.Executors.newFixedThreadPool(16)
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
                if (length <= 0) {
                    try {
                        Thread.sleep(30) // Evitar ocupación inútil de CPU y salvar batería
                    } catch (e: InterruptedException) {
                        // Ignorar
                    }
                    continue
                }

                // Solo decodificar paquetes UDP dirigidos al puerto 53 (DNS)
                val packet = IpPacketParser.parse(buffer, length) ?: continue
                if (packet.protocol != IpPacketParser.PROTO_UDP || packet.destPort != 53) continue

                val executor = dnsExecutor
                if (executor != null && !executor.isShutdown) {
                    executor.execute {
                        try {
                            handleDnsQuery(packet, output)
                        } catch (e: Exception) {
                            android.util.Log.e("KosherVPN", "Error en consulta DNS asíncrona: ${e.message}")
                        }
                    }
                } else {
                    handleDnsQuery(packet, output)
                }
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
        val queriedDomain = DnsPacketParser.extractQueriedDomain(packet.payload)
        if (queriedDomain == null) {
            NetworkForwarder.forwardDnsQuery(packet, output, this)
            return
        }

        // 1. Bloqueo global de anuncios (AdBlocker) si la opción está activa por el administrador
        val isAdBlockerActive = PrefsHelper.getMdmPrefs(this).getBoolean("global_ad_blocking", false)
        if (isAdBlockerActive && AdBlocker.isBlocked(queriedDomain)) {
            android.util.Log.i("KosherVPN", "BLOQUEADO ANUNCIO GLOBAL: $queriedDomain")
            // No responder para causar timeout DNS en la petición del anuncio
            return
        }

        // 2. Bloqueo global de GIFs/Tenor si la opción está activa por el administrador
        val isGifsBlocked = PrefsHelper.getMdmPrefs(this).getBoolean("block_gifs", false)
        if (isGifsBlocked) {
            val isTenorOrGiphy = queriedDomain.contains("tenor") || 
                                 queriedDomain.contains("giphy") ||
                                 queriedDomain.contains("gboard-stickers")
            if (isTenorOrGiphy) {
                android.util.Log.i("KosherVPN", "🚫 BLOQUEADO GIFS/STICKERS/TENOR: $queriedDomain")
                return
            }
        }

        // 1. Intentar resolver el UID dueño del socket
        val ownerUid = resolveOwnerUid(packet)
        var isBlocked = false
        var logPackage = "desconocido"

        if (ownerUid != android.os.Process.INVALID_UID) {
            val packageName = packageManager.getPackagesForUid(ownerUid)?.firstOrNull()
            if (packageName != null) {
                logPackage = packageName
                val policyManager = com.ejemplo.locksuite.mdm.PolicyManager(this)
                if (policyManager.isPerAppInternetBlocked(packageName)) {
                    isBlocked = true
                    android.util.Log.i("KosherVPN", "🚫 BLOQUEADO INTERNET TOTAL POR APP ($packageName): $queriedDomain")
                } else if (WebViewBlockManager.isBlocked(this, packageName)) {
                    val coreDomains = WebViewPolicy.getCoreDomainsFor(packageName)
                    if (coreDomains != null) {
                        // Whitelist estricta para apps conocidas (ej. Waze/DiDi)
                        val isCore = coreDomains.any { queriedDomain == it || queriedDomain.endsWith(".$it") }
                        isBlocked = !isCore
                    } else {
                        // Auto-Whitelist dinámica basada en packageName + infraestructura común para cualquier app genérica
                        val isAllowed = WebViewPolicy.isDomainAllowedForGenericApp(packageName, queriedDomain)
                        isBlocked = !isAllowed
                    }
                } else if (packageName == "com.mercadopago.wallet") {
                    if (policyManager.isMercadoPagoBlockOffersVpnEnabled()) {
                        if (WebViewPolicy.isMercadoPagoOffersDomain(queriedDomain)) {
                            isBlocked = true
                        }
                    }
                }
            }
        } else {
            // Fallback: Si no se pudo obtener el UID del socket (carrera de hilos), aplicamos la blacklist global
            val policyManager = com.ejemplo.locksuite.mdm.PolicyManager(this)
            if (policyManager.isMercadoPagoBlockOffersVpnEnabled() && WebViewPolicy.isMercadoPagoOffersDomain(queriedDomain)) {
                isBlocked = true
            } else {
                val globalBlacklist = WebViewPolicy.getGlobalBlacklist()
                isBlocked = globalBlacklist.any { queriedDomain == it || queriedDomain.endsWith(".$it") }
            }
            logPackage = "fallback-global"
        }

        android.util.Log.d(
            "KosherVPN",
            "pkg=$logPackage uid=$ownerUid dominio=$queriedDomain bloqueado=$isBlocked"
        )

        if (isBlocked) {
            // Retorna una respuesta 0.0.0.0 inmediatamente a la app (0ms) para que el Webview/Socket falle de inmediato
            android.util.Log.i("KosherVPN", "BLOQUEADO VPN 0.0.0.0 dominio=$queriedDomain de la app=$logPackage")
            NetworkForwarder.sendBlockedDnsResponse(packet, output)
        } else {
            NetworkForwarder.forwardDnsQuery(packet, output, this)
        }
    }

    /**
     * Resuelve el UID del socket UDP asociando el puerto de origen.
     * Prueba con "0.0.0.0" ya que los sockets UDP de DNS locales no suelen estar conectados
     * a una IP de interfaz específica en las tablas del kernel.
     */
    private fun resolveOwnerUid(packet: IpPacketParser.ParsedPacket): Int {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) {
            return android.os.Process.INVALID_UID
        }
        val destAddr = InetSocketAddress(packet.destIp, packet.destPort)
        
        // Candidatos de dirección local para la consulta del socket
        val localCandidates = listOf(
            InetSocketAddress("0.0.0.0", packet.sourcePort),
            InetSocketAddress(packet.sourceIp, packet.sourcePort)
        )

        // 2 intentos separados por un pequeño delay para resolver la condición de carrera
        repeat(2) { attempt ->
            for (local in localCandidates) {
                try {
                    val uid = connectivityManager.getConnectionOwnerUid(
                        OsConstants.IPPROTO_UDP,
                        local,
                        destAddr
                    )
                    if (uid != android.os.Process.INVALID_UID) {
                        return uid
                    }
                } catch (e: Exception) {
                    // Ignorar fallos de llamadas de red locales
                }
            }
            if (attempt == 0) {
                try {
                    Thread.sleep(15) // Esperar 15ms a que el kernel actualice la tabla
                } catch (e: InterruptedException) {
                    // Ignorar
                }
            }
        }
        return android.os.Process.INVALID_UID
    }

    private fun stopVpn() {
        running = false
        try {
            dnsExecutor?.shutdownNow()
            dnsExecutor = null
        } catch (e: Exception) {
            e.printStackTrace()
        }
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
