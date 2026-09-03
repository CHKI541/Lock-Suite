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
        /**
         * Logs del camino caliente (un log POR PAQUETE). Dejar en `false` en producción.
         *
         * Antes había tres `Log.i` incondicionales dentro del bucle de lectura del túnel:
         * uno por cada paquete leído, otro por cada paquete descartado y otro por cada
         * paquete que no era DNS. Abrir cualquier app dispara decenas de consultas, y
         * `Log.i` no es gratis: arma el string (concatenación + boxing de los enteros) y
         * hace una escritura al buffer de logs del sistema. Eso corría en el ÚNICO hilo
         * que lee del túnel, así que cada milisegundo gastado ahí es un milisegundo en el
         * que ninguna app del equipo puede resolver un dominio. Al ser `const val false`,
         * R8 elimina los bloques enteros de la versión final: no queda ni la rama.
         */
        private const val VERBOSE = false

        /**
         * MTU del túnel. Se sube por encima de los 1500 habituales a propósito.
         *
         * Por acá pasa SOLO tráfico DNS (el túnel es dividido: únicamente se enrutan las
         * IP de los resolutores). Con MTU 1500, una respuesta DNS grande —DNSSEC, un
         * dominio con muchos registros, EDNS0 anunciando 4096— generaba un paquete de
         * respuesta más grande que la interfaz, que el kernel descarta en silencio: la
         * consulta se quedaba sin respuesta y la app reintentaba o daba error. El
         * síntoma es "hay sitios puntuales que no cargan" con el resto funcionando.
         * Subir el MTU no afecta a ningún otro tráfico porque ningún otro tráfico pasa
         * por acá. Si `establish()` lo rechaza en algún equipo, se cae a 1500.
         */
        private const val TUNNEL_MTU = 4000
        private const val TUNNEL_MTU_FALLBACK = 1500

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

        /**
         * ¿El túnel está realmente leyendo paquetes en este proceso?
         *
         * 2/9/2026 (batería). `BootReceiver.ensureVpnRunning()` llamaba a
         * `startForegroundService(KosherVpnService)` en CADA ciclo de 20 s del Watchdog
         * mientras alguna política pidiera la VPN — o sea siempre, en el uso normal. Cada
         * llamada es una transacción Binder contra ActivityManagerService y un
         * `onStartCommand` que termina en `startVpn()`, que a su vez toma `lifecycleLock`
         * y sale por el `if (running) return` de la línea 166. Trabajo tirado 4.320 veces
         * por día, en el proceso que además tiene el hilo lector del túnel.
         *
         * Este espejo del campo `running` deja que quien va a arrancar el servicio lo
         * consulte SIN pagar el IPC. Es un espejo y no la fuente de verdad: se actualiza
         * junto a `running`, y si el proceso muere vuelve a `false` solo al recargarse la
         * clase — nunca puede quedar diciendo "está arriba" después de un reinicio.
         */
        @Volatile
        var isTunnelRunning: Boolean = false
    }

    private var vpnInterface: ParcelFileDescriptor? = null
    @Volatile private var running = false
    private val lifecycleLock = Any()
    private lateinit var connectivityManager: ConnectivityManager
    private var dnsExecutor: java.util.concurrent.ExecutorService? = null
    private var networkCallback: ConnectivityManager.NetworkCallback? = null
    @Volatile private var lastNetworkRestartAtMs: Long = 0L

    /**
     * Identificador de la última red física vista. Sirve para NO reestablecer el túnel
     * cuando `onAvailable` se dispara por la misma red de siempre — pasa muy seguido
     * (revalidaciones, cambios de capacidades) y cada reestablecimiento innecesario es
     * un par de segundos sin poder resolver dominios.
     */
    @Volatile private var lastNetworkHandle: Long = 0L

    /**
     * Instancias caras que antes se construían en CADA consulta DNS. `PolicyManager`
     * resuelve el DevicePolicyManager y arma un ComponentName en su constructor, y
     * `getMdmPrefs` toca el gestor de SharedPreferences. Con una app abriéndose eso son
     * decenas de construcciones por segundo, en los hilos que tienen que responder DNS
     * rápido. Se leen igual siempre frescas: PolicyManager consulta las preferencias en
     * cada getter.
     */
    private val policyManager by lazy { com.ejemplo.locksuite.mdm.PolicyManager(applicationContext) }
    private val mdmPrefs by lazy { PrefsHelper.getMdmPrefs(applicationContext) }

    /**
     * ¿Hace falta averiguar qué app hizo cada consulta?
     *
     * Resolver el UID dueño del socket cuesta hasta cuatro llamadas al sistema por
     * consulta, y solo sirve si hay alguna regla POR APP configurada. Si no hay ninguna
     * —que es la configuración más común— el resultado se descarta igual y se aplica la
     * lista global. Se cachea con TTL corto para no releer preferencias por consulta.
     */
    @Volatile private var needsUidLookup = false
    @Volatile private var needsUidLookupAt = 0L

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
        } else if (action == "RESTART_VPN") {
            val isCurrentlyRunning = synchronized(lifecycleLock) { running }
            if (isCurrentlyRunning) {
                android.util.Log.i("KosherVPN", "Forzando reinicio de VPN por cambio de reglas DNS.")
                stopVpn()
            }
            startVpn()
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

            // Recargar reglas DNS desde SharedPrefs al (re)iniciar la VPN
            try {
                com.ejemplo.locksuite.LockSuiteApplication.domainRuleManager.loadRules()
            } catch (e: Exception) {
                android.util.Log.w("KosherVPN", "No se pudieron recargar reglas DNS: ${e.message}")
            }

            // Desactivar DNS privado para evitar que Android envíe consultas cifradas por TCP 853 saltándose la VPN
            try {
                val policyManager = com.ejemplo.locksuite.mdm.PolicyManager(applicationContext)
                policyManager.disablePrivateDns()
            } catch (e: Exception) {
                e.printStackTrace()
            }

            // El resolutor de salida se vuelve a calcular al establecer el túnel: la red
            // pudo haber cambiado desde la última vez, y quedarse con un DNS viejo es la
            // forma más rápida de dejar el equipo sin resolver nada.
            NetworkForwarder.invalidateUpstreamCache()

            // Se intenta primero con el MTU grande y, SI Y SOLO SI el sistema lo
            // rechaza, se rehace el túnel con 1500. Sin este reintento, un equipo que no
            // aceptara el MTU grande devolvería null en establish() y se quedaría
            // directamente SIN FILTRO — mucho peor que perder alguna respuesta DNS
            // grande. Vale la pena el par de líneas.
            var establishedInterface = try {
                buildTunnel(TUNNEL_MTU).establish()
            } catch (e: Exception) {
                android.util.Log.w("KosherVPN", "establish() con MTU $TUNNEL_MTU lanzó: ${e.message}")
                null
            }
            if (establishedInterface == null) {
                android.util.Log.w(
                    "KosherVPN",
                    "El sistema no aceptó el túnel con MTU $TUNNEL_MTU; reintentando con $TUNNEL_MTU_FALLBACK."
                )
                establishedInterface = try {
                    buildTunnel(TUNNEL_MTU_FALLBACK).establish()
                } catch (e: Exception) {
                    android.util.Log.w("KosherVPN", "establish() con MTU $TUNNEL_MTU_FALLBACK lanzó: ${e.message}")
                    null
                }
            }
            if (establishedInterface == null) {
                android.util.Log.e("KosherVPN", "Android did not authorize the VPN interface; filter not started.")
                // ARREGLO 1/9/2026 (V-7): si el túnel no levanta, el arranque protegido se
                // entera recién cuando vence su techo de 120 s — dos minutos sin internet
                // evitables. Acá ya sabemos que no va a haber filtro.
                try {
                    com.ejemplo.locksuite.util.BootGate.release(applicationContext, "el sistema no autorizo el tunel")
                } catch (ignored: Exception) { }
                stopForeground(true)
                stopSelf()
                return
            }
            vpnInterface = establishedInterface

            // ──────────────────────────────────────────────────────────────────
            // EL POOL QUE RESUELVE LAS CONSULTAS
            //
            // ⚠️ SEGUNDA CAUSA DEL SÍNTOMA "SE CAE INTERNET ENTERO".
            //
            // Antes este pool usaba `CallerRunsPolicy`: cuando la cola se llenaba, la
            // consulta la ejecutaba EL HILO QUE LLAMÓ. Y el que llama es el único hilo
            // que lee del túnel. O sea que ante una ráfaga —abrir una app que consulta
            // treinta dominios de golpe, o una red lenta— el hilo lector se ponía a
            // esperar una respuesta de red de hasta 3,5 segundos y DEJABA DE LEER el
            // túnel. Mientras tanto ninguna consulta de ninguna app del equipo entraba
            // al filtro. Se realimenta solo: la cola sigue llena, el lector vuelve a
            // frenarse, y el equipo queda "sin internet" hasta que algo lo destraba.
            //
            // Ahora el lector NUNCA se bloquea. Si el pool está saturado se descarta la
            // consulta y listo: el cliente DNS reintenta a los ~1 s por su cuenta (es
            // parte normal del protocolo), en vez de congelar el equipo entero. Se sube
            // también el techo de hilos y la cola, porque el costo real de un hilo acá
            // es dormir esperando la red, no CPU.
            // ──────────────────────────────────────────────────────────────────
            dnsExecutor = java.util.concurrent.ThreadPoolExecutor(
                2, 8, 30, java.util.concurrent.TimeUnit.SECONDS,
                java.util.concurrent.ArrayBlockingQueue(256),
                java.util.concurrent.ThreadPoolExecutor.DiscardPolicy()
            )
            synchronized(lifecycleLock) {
                running = true
                isTunnelRunning = true
            }
            Thread({ runFilterLoop() }, "LockSuiteDnsFilter").start()
            android.util.Log.i("KosherVPN", "Servicio VPN iniciado exitosamente.")
        } catch (e: Exception) {
            android.util.Log.e("KosherVPN", "Error al iniciar VPN: ${e.message}")
            stopVpn()
            stopSelf()
        }
    }

    /**
     * Arma el túnel dividido con el MTU indicado. Extraído a una función porque hay que
     * poder construirlo dos veces (ver el reintento de MTU en startVpn()): un
     * `VpnService.Builder` no se puede reutilizar después de un `establish()` fallido.
     */
    private fun buildTunnel(mtu: Int): Builder {
        val builder = Builder()
            .setSession("Filtro Kosher DNS")
            .addAddress("10.0.0.2", 32)
            .addDnsServer("10.0.0.1")
            .addRoute("10.0.0.1", 32) // Captura todas las consultas dirigidas al DNS virtual IPv4
            .addAddress("fd00::2", 128)
            .addDnsServer("fd00::1")
            .addRoute("fd00::1", 128) // Captura todas las consultas dirigidas al DNS virtual IPv6
            .setBlocking(true)
            .setMtu(mtu)

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

        // Excluir la propia app LockSuite para que sus peticiones upstream/FCM no pasen
        // por el túnel. Esto también es lo que hace que `registerDefaultNetworkCallback`
        // vea la red FÍSICA y no la nuestra.
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
        return builder
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
            val buffer = ByteArray(TUNNEL_MTU)

            // El túnel ya está leyendo: recién ahora el filtro es real. Se avisa al
            // arranque protegido para que levante el bloqueo preventivo de red.
            com.ejemplo.locksuite.util.BootGate.onFilterReady(applicationContext)

            while (running) {
                val length = tunnelInput.read(buffer)
                if (length < 0) {
                    android.util.Log.i("KosherVPN", "TUN EOF recibido (length < 0), saliendo del bucle")
                    break
                }
                if (length == 0) {
                    try {
                        Thread.sleep(30) // Evitar ocupación inútil de CPU y salvar batería
                    } catch (e: InterruptedException) {
                        // Ignorar
                    }
                    continue
                }

                if (VERBOSE) {
                    android.util.Log.i("KosherVPN", "TUN_READ: len=$length version=${(buffer[0].toInt() and 0xFF) shr 4}")
                }

                // Solo decodificar paquetes UDP dirigidos al puerto 53 (DNS)
                val packet = IpPacketParser.parse(buffer, length)
                if (packet == null) {
                    if (VERBOSE) android.util.Log.i("KosherVPN", "TUN_READ: IpPacketParser.parse devolvio null")
                    continue
                }
                if (packet.protocol != IpPacketParser.PROTO_UDP || packet.destPort != 53) {
                    if (VERBOSE) {
                        android.util.Log.i("KosherVPN", "TUN_READ: No es UDP port 53 (proto=${packet.protocol} port=${packet.destPort})")
                    }
                    continue
                }

                val executor = dnsExecutor
                if (executor != null && !executor.isShutdown) {
                    // Si el pool está saturado, DiscardPolicy tira la consulta sin
                    // bloquear a este hilo. Ver el comentario largo en startVpn().
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
                    isTunnelRunning = false
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
        val queriedDomain = DnsPacketParser.extractQueriedDomain(packet.payload)?.lowercase()?.trimEnd('.')
        if (queriedDomain == null) {
            NetworkForwarder.forwardDnsQuery(packet, output, this)
            return
        }

        // Intentar resolver el UID y paquete dueño del socket al principio para logging y reglas personalizadas.
        //
        // Solo si hay alguna regla POR APP configurada: resolver el UID cuesta hasta
        // cuatro llamadas al sistema por consulta y, sin reglas por app, el resultado se
        // descarta igual (se aplica la lista global). Ver needsUidLookup().
        val ownerUid = if (needsUidLookup()) resolveOwnerUid(packet) else android.os.Process.INVALID_UID
        var logPackage = "desconocido"
        // La inmensa mayoría de las consultas DNS NO las emite la app: las emite netd, el
        // proxy DNS del sistema, en nombre de la app (así funciona getaddrinfo en Android).
        // O sea que el UID dueño del socket es el del sistema, no el de la app.
        //
        // Antes ese UID del sistema se resolvía igual a un nombre de paquete real
        // (getPackagesForUid(1000) devuelve "android" o similar), así que logPackage
        // quedaba distinto de "desconocido" y el código entraba en la rama de reglas POR
        // APP con el paquete equivocado. Ahí no coincidía ninguna regla y —lo peor— ya
        // nunca se llegaba a la rama de reserva, que es la que aplica la lista negra
        // global de WebView y la regla de Mercado Pago. Resultado: esas dos protecciones
        // quedaban sin efecto en la práctica, en TODAS las versiones de Android.
        //
        // Cualquier UID por debajo de 10000 (Process.FIRST_APPLICATION_UID) no pertenece a
        // una app instalada sino al sistema. Se usa el número literal a propósito: esa
        // constante es @hide en varias versiones del SDK.
        if (ownerUid != android.os.Process.INVALID_UID && ownerUid >= 10000) {
            val packageName = packageManager.getPackagesForUid(ownerUid)?.firstOrNull()
            if (packageName != null) {
                logPackage = packageName
            }
        }

        // Reglas DNS personalizadas: FORCE_BLOCK/FORCE_ALLOW le ganan a
        // cualquier otra politica (webview, adblock, gifs, etc.) y se
        // resuelven ahora mismo. BLOCK/ALLOW "normales" NO se resuelven aca:
        // quedan en normalCustomRule y solo se aplican mas abajo, como
        // ultimo recurso, si ninguna otra politica ya decidio algo para este
        // dominio (ver "otherPolicyDecided" mas abajo).
        val customRule = com.ejemplo.locksuite.LockSuiteApplication.domainRuleEngine.effectiveRule(queriedDomain)
        var normalCustomRule: com.ejemplo.locksuite.dns.RuleType? = null
        when (customRule) {
            com.ejemplo.locksuite.dns.RuleType.FORCE_BLOCK -> {
                android.util.Log.i("KosherVPN", "🚫 BLOQUEADO DNS CUSTOM FORZADO 0.0.0.0 dominio=$queriedDomain de la app=$logPackage")
                com.ejemplo.locksuite.LockSuiteApplication.dnsActivityBuffer.record(queriedDomain, logPackage, com.ejemplo.locksuite.dns.DnsAction.BLOCKED)
                NetworkForwarder.sendBlockedDnsResponse(packet, output)
                return
            }
            com.ejemplo.locksuite.dns.RuleType.FORCE_ALLOW -> {
                android.util.Log.i("KosherVPN", "✅ PERMITIDO DNS CUSTOM FORZADO dominio=$queriedDomain de la app=$logPackage")
                com.ejemplo.locksuite.LockSuiteApplication.dnsActivityBuffer.record(queriedDomain, logPackage, com.ejemplo.locksuite.dns.DnsAction.ALLOWED)
                NetworkForwarder.forwardDnsQuery(packet, output, this)
                return
            }
            else -> normalCustomRule = customRule
        }

        // 1. Bloqueo global de anuncios (AdBlocker) si la opción está activa por el administrador
        val isAdBlockerActive = mdmPrefs.getBoolean("global_ad_blocking", false)
        if (isAdBlockerActive && AdBlocker.isBlocked(queriedDomain)) {
            android.util.Log.i("KosherVPN", "BLOQUEADO ANUNCIO GLOBAL: $queriedDomain")
            com.ejemplo.locksuite.LockSuiteApplication.dnsActivityBuffer.record(queriedDomain, logPackage, com.ejemplo.locksuite.dns.DnsAction.BLOCKED)
            NetworkForwarder.sendBlockedDnsResponse(packet, output)
            return
        }

        // 2. Bloqueo global de GIFs/Tenor si la opción está activa por el administrador
        val isGifsBlocked = mdmPrefs.getBoolean("block_gifs", false)
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
        // Marca si alguna politica especifica (no las reglas DNS "normales")
        // ya tomo una decision real para este dominio. Una regla BLOCK/ALLOW
        // normal (no forzada) solo se aplica mas abajo cuando esto sigue en
        // false - es el modo "no sobreescribe otra cosa" pedido, a diferencia
        // de FORCE_BLOCK/FORCE_ALLOW que ya se resolvieron mas arriba.
        var otherPolicyDecided = false

        if (logPackage != "desconocido") {
            if (policyManager.isPerAppInternetBlocked(logPackage)) {
                isBlocked = true
                otherPolicyDecided = true
                android.util.Log.i("KosherVPN", "🚫 BLOQUEADO INTERNET TOTAL POR APP ($logPackage): $queriedDomain")
            } else if (WebViewBlockManager.isBlocked(this, logPackage)) {
                // El bloqueo de WebView, una vez activo para esta app, gobierna
                // TODOS sus dominios (permitidos o no) - cuenta como decision
                // propia aunque el resultado puntual sea "permitir".
                otherPolicyDecided = true
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
                        otherPolicyDecided = true
                    }
                }
            }
        } else {
            // Fallback: Si no se pudo obtener el UID del socket (carrera de hilos), aplicamos la blacklist global
            if (policyManager.isMercadoPagoBlockOffersVpnEnabled() && WebViewPolicy.isMercadoPagoOffersDomain(queriedDomain)) {
                isBlocked = true
                otherPolicyDecided = true
            } else {
                val globalBlacklist = WebViewPolicy.getGlobalBlacklist()
                isBlocked = globalBlacklist.any { queriedDomain == it || queriedDomain.endsWith(".$it") }
                if (isBlocked) otherPolicyDecided = true
            }
            logPackage = "fallback-global"
        }

        // Reglas DNS "normales" (no forzadas): solo entran en juego si ninguna
        // otra politica de arriba ya decidio algo para este dominio.
        if (!otherPolicyDecided && normalCustomRule != null) {
            isBlocked = (normalCustomRule == com.ejemplo.locksuite.dns.RuleType.BLOCK)
            android.util.Log.i("KosherVPN", "Regla DNS normal aplicada (sin otra politica activa) dominio=$queriedDomain regla=$normalCustomRule")
        }

        // Registrar en el buffer de actividad
        com.ejemplo.locksuite.LockSuiteApplication.dnsActivityBuffer.record(
            queriedDomain,
            logPackage,
            if (isBlocked) com.ejemplo.locksuite.dns.DnsAction.BLOCKED
            else com.ejemplo.locksuite.dns.DnsAction.ALLOWED
        )

        if (VERBOSE) {
            android.util.Log.d(
                "KosherVPN",
                "pkg=$logPackage uid=$ownerUid dominio=$queriedDomain bloqueado=$isBlocked"
            )
        }

        if (isBlocked) {
            // Retorna una respuesta 0.0.0.0 inmediatamente a la app (0ms) para que el Webview/Socket falle de inmediato
            if (VERBOSE) {
                android.util.Log.i("KosherVPN", "BLOQUEADO VPN 0.0.0.0 dominio=$queriedDomain de la app=$logPackage")
            }
            NetworkForwarder.sendBlockedDnsResponse(packet, output)
        } else {
            NetworkForwarder.forwardDnsQuery(packet, output, this)
        }
    }

    /**
     * ¿Hay alguna regla que dependa de saber QUÉ APP hizo la consulta?
     *
     * Si no la hay, se saltea `resolveOwnerUid()` por completo. Ese método hace hasta
     * cuatro `getConnectionOwnerUid()` (llamadas al sistema) y antes además dormía 15 ms
     * en el medio — todo eso por consulta, en los hilos que tienen que contestar rápido.
     * Sin reglas por app el resultado no cambia ninguna decisión: se aplica igual la
     * rama global. El valor se recalcula cada 10 s como mucho.
     */
    private fun needsUidLookup(): Boolean {
        val now = android.os.SystemClock.elapsedRealtime()
        if (needsUidLookupAt != 0L && now - needsUidLookupAt < 10_000L) return needsUidLookup
        val fresh = try {
            WebViewBlockManager.getBlockedPackages(applicationContext).isNotEmpty() ||
                policyManager.getPerAppInternetBlockedPackages().isNotEmpty()
        } catch (e: Exception) {
            true // ante la duda, resolver: es más lento pero no cambia el resultado
        }
        needsUidLookup = fresh
        needsUidLookupAt = now
        return fresh
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

        // Dos intentos, SIN dormir en el medio.
        //
        // Antes había un `Thread.sleep(15)` entre los dos intentos "para esperar a que el
        // kernel actualice la tabla". El problema es dónde corría: en los hilos del pool
        // que resuelven DNS, que son pocos. Con el pool anterior (2 a 4 hilos), dormir
        // 15 ms por consulta fallida limitaba el equipo a unas 130 consultas por segundo
        // en el mejor caso, y era lo que llenaba la cola y disparaba el freno del hilo
        // lector (ver el comentario del pool en startVpn()). Las cuatro llamadas al
        // sistema que hace el propio bucle ya toman más que esos 15 ms de espera, así
        // que el segundo intento sigue llegando "tarde" igual, pero sin frenar a nadie.
        repeat(2) {
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
        }
        return android.os.Process.INVALID_UID
    }

    private fun stopVpn() {
        val interfaceToClose: ParcelFileDescriptor?
        val executorToStop: java.util.concurrent.ExecutorService?
        synchronized(lifecycleLock) {
            running = false
            isTunnelRunning = false
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
                    // El resolutor de salida SIEMPRE se recalcula: es barato y es lo que
                    // evita quedarse hablando con el DNS de una red que ya no existe.
                    NetworkForwarder.invalidateUpstreamCache()

                    // Reestablecer el túnel, en cambio, solo si la red es OTRA.
                    //
                    // `onAvailable` no significa "cambiaste de red": el sistema lo llama
                    // también al revalidar la misma red, al recuperar señal, al cambiar
                    // de celda. Antes cada una de esas llamadas tiraba abajo el túnel y
                    // lo levantaba de nuevo, y durante ese hueco ninguna app podía
                    // resolver un dominio: para el usuario, "se cortó internet un rato".
                    val handle = try {
                        network.networkHandle
                    } catch (e: Exception) {
                        0L
                    }
                    if (handle != 0L && handle == lastNetworkHandle) {
                        if (VERBOSE) android.util.Log.i("KosherVPN", "onAvailable de la misma red; no se reestablece el tunel.")
                        return
                    }
                    android.util.Log.i("KosherVPN", "Cambio la red fisica por defecto; reestableciendo tunel VPN.")
                    // ARREGLO 1/9/2026 (V-3): el handle se anota SOLO si el
                    // reestablecimiento efectivamente ocurrió. Antes se anotaba siempre,
                    // y si el antirrebote de 8 s cancelaba el reinicio quedaba el túnel
                    // de la red vieja marcado como si fuera el de la nueva — con lo cual
                    // el siguiente aviso de la red buena se descartaba por "es la misma".
                    if (restartVpn()) {
                        lastNetworkHandle = handle
                    }
                }

                override fun onLinkPropertiesChanged(network: Network, lp: android.net.LinkProperties) {
                    // Los servidores DNS de una red cambian sin que la red cambie. No hace
                    // falta rehacer el túnel: alcanza con volver a elegir el resolutor.
                    NetworkForwarder.invalidateUpstreamCache()
                }

                override fun onLost(network: Network) {
                    // Al perder la red, el resolutor cacheado deja de servir.
                    NetworkForwarder.invalidateUpstreamCache()
                    lastNetworkHandle = 0L
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
    private fun restartVpn(): Boolean {
        val isCurrentlyRunning = synchronized(lifecycleLock) { running }
        if (!isCurrentlyRunning) return false // La VPN no esta activa; no reiniciar por cuenta propia.

        // Debounce de 8 s (antes 3 s). Un handoff de Wi-Fi a datos móviles genera una
        // ráfaga de callbacks durante varios segundos; con 3 s se alcanzaban a encadenar
        // dos o tres reestablecimientos seguidos, y cada uno es un hueco sin resolver.
        val now = android.os.SystemClock.elapsedRealtime()
        if (now - lastNetworkRestartAtMs < 8000) return false
        lastNetworkRestartAtMs = now

        android.util.Log.i("KosherVPN", "Reestableciendo tunel VPN tras cambio de conectividad.")
        stopVpn()
        startVpn()
        return true
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
