package com.ejemplo.locksuite.service

import android.net.ConnectivityManager
import android.net.Network
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

    companion object {
        // Resolutores DNS públicos más comunes. El filtro original solo
        // capturaba consultas dirigidas al DNS virtual del sistema (10.0.0.1 /
        // fd00::1); cualquier app que ignorase eso y apuntara directo a uno de
        // estos servidores hardcodeados salía del túnel sin pasar por el
        // filtro en absoluto. Agregarlos como rutas adicionales hace que sus
        // consultas también entren al túnel. Esto NO cubre DNS-over-HTTPS/TLS
        // ni QUIC (ver informe de auditoría §3.1: cerrar eso del todo requiere
        // un túnel completo NAT, un cambio de arquitectura mayor).
        private val KNOWN_PUBLIC_DNS_V4 = listOf(
            "8.8.8.8", "8.8.4.4",               // Google
            "1.1.1.1", "1.0.0.1",               // Cloudflare
            "9.9.9.9", "149.112.112.112",       // Quad9
            "208.67.222.222", "208.67.220.220"  // OpenDNS
        )
        private val KNOWN_PUBLIC_DNS_V6 = listOf(
            "2001:4860:4860::8888", "2001:4860:4860::8844", // Google
            "2606:4700:4700::1111", "2606:4700:4700::1001", // Cloudflare
            "2620:fe::fe", "2620:fe::9"                      // Quad9
        )
    }

    private var vpnInterface: ParcelFileDescriptor? = null
    @Volatile private var running = false
    private val lifecycleLock = Any()
    private lateinit var connectivityManager: ConnectivityManager
    private var dnsExecutor: java.util.concurrent.ExecutorService? = null
    private var networkCallback: ConnectivityManager.NetworkCallback? = null
    @Volatile private var lastNetworkRestartAtMs: Long = 0L

    override fun onCreate() {
        super.onCreate()
        connectivityManager = getSystemService(ConnectivityManager::class.java)
        // Cargar la lista de bloqueo de anuncios de forma asíncrona al iniciar
        AdBlocker.loadAsync(applicationContext)
        // Detectar cambios de red (Wi-Fi <-> datos moviles) para reestablecer
        // el tunel proactivamente; ver registerNetworkWatcher() mas abajo.
        registerNetworkWatcher()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val action = intent?.action
        if (action == "STOP_VPN") {
            stopVpn()
            stopSelf()
            return START_NOT_STICKY
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
        synchronized(lifecycleLock) {
            if (running) return
        }
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

            // Capturar también consultas dirigidas directo a resolutores públicos conocidos
            // (ver comentario en la declaración de KNOWN_PUBLIC_DNS_V4/V6 más arriba).
            KNOWN_PUBLIC_DNS_V4.forEach { dns ->
                try {
                    builder.addRoute(dns, 32)
                } catch (e: Exception) {
                    android.util.Log.w("KosherVPN", "No se pudo agregar ruta DNS pública $dns: ${e.message}")
                }
            }
            KNOWN_PUBLIC_DNS_V6.forEach { dns ->
                try {
                    builder.addRoute(dns, 128)
                } catch (e: Exception) {
                    android.util.Log.w("KosherVPN", "No se pudo agregar ruta DNS pública $dns: ${e.message}")
                }
            }

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

            val establishedInterface = builder.establish()
            if (establishedInterface == null) {
                android.util.Log.e("KosherVPN", "Android did not authorize the VPN interface; filter not started.")
                stopForeground(true)
                stopSelf()
                return
            }
            vpnInterface = establishedInterface

            // Las respuestas terminan en una sola interfaz TUN. Un pool acotado
            // evita picos de hilos/CPU ante muchas consultas DNS sin sacrificar
            // la concurrencia normal de resolución.
            dnsExecutor = java.util.concurrent.ThreadPoolExecutor(
                2, 4, 30, java.util.concurrent.TimeUnit.SECONDS,
                java.util.concurrent.ArrayBlockingQueue(128),
                java.util.concurrent.ThreadPoolExecutor.CallerRunsPolicy()
            )
            synchronized(lifecycleLock) {
                running = true
            }
            Thread({ runFilterLoop() }, "LockSuiteDnsFilter").start()
            android.util.Log.i("KosherVPN", "Servicio VPN iniciado exitosamente.")
        } catch (e: Exception) {
            android.util.Log.e("KosherVPN", "Error al iniciar VPN: ${e.message}")
            stopVpn()
            stopSelf()
        }
    }

    private fun runFilterLoop() {
        val iface = vpnInterface ?: return
        var input: FileInputStream? = null
        var output: FileOutputStream? = null

        try {
            val tunnelInput = FileInputStream(iface.fileDescriptor)
            val tunnelOutput = FileOutputStream(iface.fileDescriptor)
            input = tunnelInput
            output = tunnelOutput
            val buffer = ByteArray(4096)
            while (running) {
                val length = tunnelInput.read(buffer)
                if (length <= 0) {
                    try {
                        Thread.sleep(30) // Evitar ocupación inútil de CPU y salvar batería
                    } catch (e: InterruptedException) {
                        // Ignorar
                    }
                    continue
                }

                val firstByte = buffer[0].toInt() and 0xFF
                val version = firstByte shr 4
                android.util.Log.i("KosherVPN", "TUN_READ: len=$length version=$version")

                // Solo decodificar paquetes UDP dirigidos al puerto 53 (DNS)
                val packet = IpPacketParser.parse(buffer, length)
                if (packet == null) {
                    android.util.Log.i("KosherVPN", "TUN_READ: IpPacketParser.parse devolvio null")
                    continue
                }
                if (packet.protocol != IpPacketParser.PROTO_UDP || packet.destPort != 53) {
                    android.util.Log.i("KosherVPN", "TUN_READ: No es UDP port 53 (proto=${packet.protocol} port=${packet.destPort})")
                    continue
                }

                val executor = dnsExecutor
                if (executor != null && !executor.isShutdown) {
                    executor.execute {
                        try {
                            handleDnsQuery(packet, tunnelOutput)
                        } catch (e: Exception) {
                            android.util.Log.e("KosherVPN", "Error en consulta DNS asíncrona: ${e.message}")
                        }
                    }
                } else {
                    handleDnsQuery(packet, tunnelOutput)
                }
            }
        } catch (e: Exception) {
            android.util.Log.e("KosherVPN", "Error en bucle de filtrado VPN: ${e.message}")
        } finally {
            try {
                input?.close()
                output?.close()
            } catch (e: Exception) {
                android.util.Log.w("KosherVPN", "Error cerrando la interfaz VPN", e)
            }

            // Si el hilo termina inesperadamente, no dejar el servicio marcado como
            // activo. Así el watchdog puede iniciarlo de nuevo en vez de conservar
            // una notificación sin filtro real.
            val shouldStopService = synchronized(lifecycleLock) {
                if (vpnInterface === iface) {
                    running = false
                    vpnInterface = null
                    dnsExecutor?.shutdownNow()
                    dnsExecutor = null
                    true
                } else {
                    false
                }
            }
            if (shouldStopService) {
                stopForeground(true)
                stopSelf()
            }
        }
    }

    private fun handleDnsQuery(packet: IpPacketParser.ParsedPacket, output: FileOutputStream) {
        val queriedDomain = DnsPacketParser.extractQueriedDomain(packet.payload)
        if (queriedDomain == null) {
            NetworkForwarder.forwardDnsQuery(packet, output, this)
            return
        }

        // Intentar resolver el UID y paquete dueño del socket al principio para logging y reglas personalizadas
        val ownerUid = resolveOwnerUid(packet)
        var logPackage = "desconocido"
        if (ownerUid != android.os.Process.INVALID_UID) {
            val packageName = packageManager.getPackagesForUid(ownerUid)?.firstOrNull()
            if (packageName != null) {
                logPackage = packageName
            }
        }

        // ── Reglas personalizadas DNS ──
        val customRule = com.ejemplo.locksuite.LockSuiteApplication.domainRuleEngine.effectiveRule(queriedDomain)
        if (customRule == com.ejemplo.locksuite.dns.RuleType.BLOCK) {
            android.util.Log.i("KosherVPN", "🚫 BLOQUEADO DNS CUSTOM 0.0.0.0 dominio=$queriedDomain de la app=$logPackage")
            com.ejemplo.locksuite.LockSuiteApplication.dnsActivityBuffer.record(queriedDomain, logPackage, com.ejemplo.locksuite.dns.DnsAction.BLOCKED)
            NetworkForwarder.sendBlockedDnsResponse(packet, output)
            return
        } else if (customRule == com.ejemplo.locksuite.dns.RuleType.ALLOW) {
            android.util.Log.i("KosherVPN", "✅ PERMITIDO DNS CUSTOM dominio=$queriedDomain de la app=$logPackage")
            com.ejemplo.locksuite.LockSuiteApplication.dnsActivityBuffer.record(queriedDomain, logPackage, com.ejemplo.locksuite.dns.DnsAction.ALLOWED)
            NetworkForwarder.forwardDnsQuery(packet, output, this)
            return
        }

        // 1. Bloqueo global de anuncios (AdBlocker) si la opción está activa por el administrador
        val isAdBlockerActive = PrefsHelper.getMdmPrefs(this).getBoolean("global_ad_blocking", false)
        if (isAdBlockerActive && AdBlocker.isBlocked(queriedDomain)) {
            android.util.Log.i("KosherVPN", "BLOQUEADO ANUNCIO GLOBAL: $queriedDomain")
            com.ejemplo.locksuite.LockSuiteApplication.dnsActivityBuffer.record(queriedDomain, logPackage, com.ejemplo.locksuite.dns.DnsAction.BLOCKED)
            NetworkForwarder.sendBlockedDnsResponse(packet, output)
            return
        }

        // 2. Bloqueo global de GIFs/Tenor si la opción está activa por el administrador
        val isGifsBlocked = PrefsHelper.getMdmPrefs(this).getBoolean("block_gifs", false)
        if (isGifsBlocked) {
            // Antes: queriedDomain.contains("tenor") sobre el string completo. Eso
            // marcaba como GIF cualquier dominio que tuviera esas letras adentro de
            // una palabra más larga (por ejemplo "tenor" aparece dentro de palabras
            // normales en español), bloqueando sitios que no tienen nada que ver y
            // degradando la experiencia sin motivo. Ahora se exige que sea una
            // etiqueta completa del dominio o un segmento separado por guiones,
            // igual que en WebViewPolicy.
            val gifTokens = setOf("tenor", "giphy", "gboard-stickers")
            val labels = queriedDomain.lowercase().split(".")
            val isTenorOrGiphy = gifTokens.any { token ->
                labels.any { label -> label == token || label.split("-").contains(token) }
            } || labels.any { it == "gboard-stickers" }
            if (isTenorOrGiphy) {
                android.util.Log.i("KosherVPN", "🚫 BLOQUEADO GIFS/STICKERS/TENOR: $queriedDomain")
                com.ejemplo.locksuite.LockSuiteApplication.dnsActivityBuffer.record(queriedDomain, logPackage, com.ejemplo.locksuite.dns.DnsAction.BLOCKED)
                NetworkForwarder.sendBlockedDnsResponse(packet, output)
                return
            }
        }

        var isBlocked = false

        if (logPackage != "desconocido") {
            val policyManager = com.ejemplo.locksuite.mdm.PolicyManager(this)
            if (policyManager.isPerAppInternetBlocked(logPackage)) {
                isBlocked = true
                android.util.Log.i("KosherVPN", "🚫 BLOQUEADO INTERNET TOTAL POR APP ($logPackage): $queriedDomain")
            } else if (WebViewBlockManager.isBlocked(this, logPackage)) {
                val coreDomains = WebViewPolicy.getCoreDomainsFor(logPackage)
                if (coreDomains != null) {
                    // Whitelist estricta para apps conocidas (ej. Waze/DiDi)
                    val isCore = coreDomains.any { queriedDomain == it || queriedDomain.endsWith(".$it") }
                    isBlocked = !isCore
                } else {
                    // Auto-Whitelist dinámica basada en packageName + infraestructura común para cualquier app genérica
                    val isAllowed = WebViewPolicy.isDomainAllowedForGenericApp(logPackage, queriedDomain)
                    isBlocked = !isAllowed
                }
            } else if (logPackage == "com.mercadopago.wallet") {
                if (policyManager.isMercadoPagoBlockOffersVpnEnabled()) {
                    if (WebViewPolicy.isMercadoPagoOffersDomain(queriedDomain)) {
                        isBlocked = true
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

        // Registrar en el buffer de actividad
        com.ejemplo.locksuite.LockSuiteApplication.dnsActivityBuffer.record(
            queriedDomain,
            logPackage,
            if (isBlocked) com.ejemplo.locksuite.dns.DnsAction.BLOCKED
            else com.ejemplo.locksuite.dns.DnsAction.ALLOWED
        )

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
        val interfaceToClose: ParcelFileDescriptor?
        val executorToStop: java.util.concurrent.ExecutorService?
        synchronized(lifecycleLock) {
            running = false
            executorToStop = dnsExecutor
            dnsExecutor = null
            interfaceToClose = vpnInterface
            vpnInterface = null
        }
        try {
            executorToStop?.shutdownNow()
        } catch (e: Exception) {
            android.util.Log.w("KosherVPN", "Error cerrando ejecutor DNS", e)
        }
        try {
            interfaceToClose?.close()
        } catch (e: Exception) {
            android.util.Log.w("KosherVPN", "Error cerrando interfaz VPN", e)
        }
        stopForeground(true)
        android.util.Log.i("KosherVPN", "Servicio VPN detenido.")
    }

    /**
     * Registra un monitor de la red fisica por defecto para reestablecer el
     * tunel VPN ante cambios de conectividad (Wi-Fi <-> datos moviles,
     * reconexion tras un corte, etc.). Se apoya en que esta misma app ya se
     * excluye de su propio tunel (ver addDisallowedApplication mas arriba en
     * startVpn()), asi que su "red por defecto" refleja la red fisica real y
     * no la VPN propia.
     *
     * Motivo: un cambio de red puede dejar la interfaz TUN "viva" pero
     * efectivamente muerta (deja de enrutar trafico) sin lanzar ninguna
     * excepcion dentro de runFilterLoop(), asi que el Watchdog externo (que
     * solo verifica si el servicio sigue en pie) no lo detecta. Reestablecer
     * la VPN proactivamente en cada cambio de red cierra ese hueco.
     */
    private fun registerNetworkWatcher() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.N) return
        try {
            val callback = object : ConnectivityManager.NetworkCallback() {
                override fun onAvailable(network: Network) {
                    android.util.Log.i("KosherVPN", "Cambio la red fisica por defecto; reestableciendo tunel VPN por las dudas.")
                    restartVpn()
                }
            }
            connectivityManager.registerDefaultNetworkCallback(callback)
            networkCallback = callback
        } catch (e: Exception) {
            android.util.Log.w("KosherVPN", "No se pudo registrar el monitor de red: ${e.message}")
        }
    }

    private fun unregisterNetworkWatcher() {
        try {
            networkCallback?.let { connectivityManager.unregisterNetworkCallback(it) }
        } catch (e: Exception) {
            // Ignorar: puede fallar si nunca llego a registrarse correctamente.
        }
        networkCallback = null
    }

    /**
     * Reestablece el tunel sin depender de que el Watchdog externo lo note.
     * Tiene un debounce corto porque un mismo evento de red fisica puede
     * disparar varios callbacks casi simultaneos.
     */
    private fun restartVpn() {
        val isCurrentlyRunning = synchronized(lifecycleLock) { running }
        if (!isCurrentlyRunning) return // La VPN no esta activa; no reiniciar por cuenta propia.

        val now = android.os.SystemClock.elapsedRealtime()
        if (now - lastNetworkRestartAtMs < 3000) return
        lastNetworkRestartAtMs = now

        android.util.Log.i("KosherVPN", "Reestableciendo tunel VPN tras cambio de conectividad.")
        stopVpn()
        startVpn()
    }

    /**
     * Android invoca esto cuando revoca el permiso de VPN de la app (otra VPN
     * toma el control, o el sistema fuerza la desconexion). La implementacion
     * por defecto solo detiene el servicio; aca se reintenta reestablecer de
     * inmediato en vez de esperar al proximo ciclo del Watchdog (hasta 20s).
     */
    override fun onRevoke() {
        android.util.Log.w("KosherVPN", "onRevoke(): la VPN fue revocada externamente. Reintentando de inmediato.")
        stopVpn()
        startVpn()
    }

    override fun onDestroy() {
        unregisterNetworkWatcher()
        stopVpn()
        super.onDestroy()
    }
}
