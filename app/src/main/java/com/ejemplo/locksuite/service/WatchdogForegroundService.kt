package com.ejemplo.locksuite.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Intent
import android.os.Build
import android.os.IBinder
import android.content.ComponentName
import android.provider.Settings
import androidx.core.app.NotificationCompat
import androidx.work.Constraints
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import com.ejemplo.locksuite.worker.WatchdogWorker
import java.util.concurrent.TimeUnit

class WatchdogForegroundService : Service() {

    companion object {
        const val NOTIFICATION_ID = 9001
        const val CHANNEL_ID = "locksuite_watchdog_channel"
        @Volatile var temporaryPauseUntil: Long = 0L

        /** Notificación del aviso insistente de accesibilidad. Canal aparte, de alta prioridad. */
        const val NAG_NOTIFICATION_ID = 9003
        // Sufijo _v2: en Android 8+ un canal de notificación no se puede reconfigurar
        // por código una vez creado. La versión anterior lo creó sin vibración ni
        // bypass de "no molestar"; cambiando el id se crea uno nuevo con la
        // configuración correcta en vez de arrastrar el viejo para siempre.
        const val NAG_CHANNEL_ID = "locksuite_accessibility_nag_v2"

        /** Cada cuánto vuelve a aparecer el aviso. El dueño pidió 15-20 s. */
        private const val NAG_INTERVAL_MS = 18_000L

        // ── Claves de los interruptores de "Protecciones de Accesibilidad" ──
        const val KEY_ACC_BOUNCE_SETTINGS = "acc_protect_bounce_settings"
        const val KEY_ACC_NAG = "acc_protect_nag"
        const val KEY_ACC_SUSPEND_ALL = "acc_protect_suspend_all"

        /**
         * Instancia viva del servicio, para poder pedirle una revisión inmediata.
         *
         * Motivo (18/8/2026): los interruptores de Protecciones de Accesibilidad solo
         * se hacían efectivos en el próximo ciclo del Watchdog, o sea **hasta 20
         * segundos después**. Quien los prueba toca el interruptor, mira el equipo, no
         * ve nada y concluye que no funcionan. Ahora el propio `PolicyManager` pide una
         * revisión al toque.
         */
        @Volatile private var instance: WatchdogForegroundService? = null

        /** Revisión inmediata del estado de accesibilidad. Segura si el servicio no corre. */
        @JvmStatic
        fun requestImmediateCheck() {
            val svc = instance ?: return
            svc.handler.post { svc.checkAccessibilityStatus() }
        }
    }

    private var lastBlockLaunchTime = 0L
    private var lastSyncTime = 0L
    private var lastPrivateDnsEnforceTime = 0L
    private var lastNagAt = 0L
    /** ¿El ciclo del aviso ya está encolado? Evita duplicar el ritmo al re-armarlo. */
    private var nagArmed = false
    /** Cuántos avisos van en esta racha. Solo para que el texto cambie en cada uno. */
    private var nagCount = 0
    private var emergencySuspendApplied = false
    private var accessibilityObserver: android.database.ContentObserver? = null
    private val handler = android.os.Handler(android.os.Looper.getMainLooper())

    /**
     * Aviso insistente de accesibilidad apagada (interruptor `acc_protect_nag`).
     *
     * Va en su propio ciclo de 18 s y NO dentro del ciclo de 20 s del Watchdog: el
     * pedido del dueño era "cada 15-20 segundos", y engancharlo al ciclo grande
     * significaría rehacer todo el resto del trabajo del Watchdog solo para mostrar
     * una notificación. Este runnable solo se encola mientras el aviso hace falta y se
     * desencola apenas la accesibilidad vuelve, así que en el caso normal (todo bien)
     * no corre nunca.
     */
    private val nagRunnable = object : Runnable {
        override fun run() {
            if (!shouldNag()) {
                cancelNag()
                return
            }
            showAccessibilityNag()
            // Se re-encola siempre acá, no desde el ciclo de 20 s del Watchdog: así el
            // ritmo del aviso es exactamente NAG_INTERVAL_MS y no queda a merced de
            // cuándo le toque correr al Watchdog.
            handler.postDelayed(this, NAG_INTERVAL_MS)
        }
    }

    private val checkRunnable = object : Runnable {
        override fun run() {
            checkAccessibilityStatus()

            // Arranque protegido: liberar la red si el filtro ya está listo, o si venció
            // la ventana de seguridad. Ver util/BootGate.kt.
            try {
                com.ejemplo.locksuite.util.BootGate.tick(applicationContext)
            } catch (e: Exception) {
                e.printStackTrace()
            }

            // Garantizar que la VPN Kosher siga ejecutándose si alguna política la requiere
            com.ejemplo.locksuite.receiver.BootReceiver.ensureVpnRunning(applicationContext)

            // Garantizar que la marca de agua flotante siga ejecutándose si el launcher está activo
            try {
                val policyManager = com.ejemplo.locksuite.mdm.PolicyManager(applicationContext)
                if (!policyManager.isLockSuiteSuspended() &&
                    policyManager.isKosherLauncherEnabled() && Settings.canDrawOverlays(applicationContext)) {
                    val watermarkIntent = Intent(applicationContext, WatermarkService::class.java)
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                        applicationContext.startForegroundService(watermarkIntent)
                    } else {
                        applicationContext.startService(watermarkIntent)
                    }
                }
            } catch (e: Exception) {
                e.printStackTrace()
            }


            // Re-imponer "DNS Privado = Desactivado" cada 60s mientras la VPN deba
            // seguir activa. disablePrivateDns() solo se aplicaba una vez al arrancar
            // el servicio VPN: si el usuario lo reactivaba después a mano desde
            // Ajustes, quedaba encendido indefinidamente (hasta el próximo reinicio
            // del servicio VPN) y el filtro dejaba de ver el tráfico DNS por completo.
            val nowElapsed = android.os.SystemClock.elapsedRealtime()
            if (nowElapsed - lastPrivateDnsEnforceTime > 60000) {
                lastPrivateDnsEnforceTime = nowElapsed
                if (com.ejemplo.locksuite.receiver.BootReceiver.shouldVpnBeRunning(applicationContext)) {
                    try {
                        com.ejemplo.locksuite.mdm.PolicyManager(applicationContext).disablePrivateDns()
                    } catch (e: Exception) {
                        e.printStackTrace()
                    }
                }
            }

            // Sincronizar periódicamente cada 90 segundos para mantener el estado "En línea" en la web sin abrir la app
            val now = android.os.SystemClock.elapsedRealtime()
            if (now - lastSyncTime > 90000) {
                lastSyncTime = now
                try {
                    com.ejemplo.locksuite.util.FirebaseDeviceSync.syncLastSeenOnly(applicationContext)
                } catch (e: Exception) {
                    e.printStackTrace()
                }
            }

            handler.postDelayed(this, 20000L)
        }
    }

    override fun onCreate() {
        super.onCreate()
        instance = this
        createNotificationChannel()
        startForeground(NOTIFICATION_ID, buildNotification())
        scheduleWorkManagerWatchdog()
        handler.post(checkRunnable)

        val uri = Settings.Secure.getUriFor(Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES)
        accessibilityObserver = object : android.database.ContentObserver(handler) {
            override fun onChange(selfChange: Boolean) {
                super.onChange(selfChange)
                checkAccessibilityStatus()
                // Si el arranque protegido estaba esperando justamente a que se
                // activara la accesibilidad, liberar en el acto en vez de esperar
                // hasta 20 s al próximo ciclo.
                try {
                    com.ejemplo.locksuite.util.BootGate.tick(applicationContext)
                } catch (e: Exception) {
                    e.printStackTrace()
                }
            }
        }
        try {
            contentResolver.registerContentObserver(uri, false, accessibilityObserver!!)
        } catch (e: Exception) {
            e.printStackTrace()
        }

        // Marcar en línea de inmediato al (re)arrancar el servicio
        try {
            com.ejemplo.locksuite.util.FirebaseDeviceSync.syncLastSeenOnly(applicationContext)
        } catch (e: Exception) {
            e.printStackTrace()
        }

        // Sincronizar información del dispositivo en segundo plano al iniciar el servicio
        try {
            com.ejemplo.locksuite.util.FirebaseDeviceSync.syncDeviceInfo(applicationContext)
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    /**
     * Estado de la exigencia de Accesibilidad.
     *
     * ⚠️ CAMBIO IMPORTANTE DEL 18/8/2026 — POR QUÉ ESTO DEVUELVE `adminSession` EN VEZ
     * DE TRAGARSE TODO.
     *
     * La versión anterior devolvía `null` (o sea "no hacer nada") cuando había una
     * sesión de administrador abierta. Eso rompía las protecciones justo cuando alguien
     * las probaba: la sesión dura CINCO MINUTOS desde que ingresás el PIN, y para tocar
     * los interruptores en la app hay que ingresar el PIN. O sea que el dueño encendía
     * "suspender todas las apps", iba a Ajustes, apagaba la accesibilidad… y no pasaba
     * nada, porque su propia sesión estaba suprimiendo la protección. Reporte textual:
     * *"las otras 2 no funcionan"*. Funcionaban; estaban calladas.
     *
     * Ahora la sesión de administrador suprime ÚNICAMENTE la pantalla roja a pantalla
     * completa —para que el administrador pueda trabajar sin que se le tire encima— y
     * no toca ni la suspensión de apps ni el aviso. Es recuperable: al reactivar la
     * accesibilidad todo vuelve solo. Para silenciar todo a propósito está
     * `temporaryPauseUntil`, que es explícito y con vencimiento.
     */
    private class AccGate(val active: Boolean, val adminSession: Boolean)

    private fun accessibilityGate(context: android.content.Context): AccGate? {
        if (android.os.SystemClock.elapsedRealtime() < temporaryPauseUntil) return null
        val policyManager = com.ejemplo.locksuite.mdm.PolicyManager(context)
        // Con LockSuite suspendido no se exige Accesibilidad ni se suspende nada: si no,
        // la pantalla de bloqueo aparecería justo cuando el administrador acaba de
        // liberar el equipo.
        if (policyManager.isLockSuiteSuspended()) return null
        if (!policyManager.isAccessibilityProtectionEnabled()) return null
        if (!com.ejemplo.locksuite.security.PinManager.isPinConfigured(context)) return null
        return AccGate(
            active = com.ejemplo.locksuite.util.BootGate.isAccessibilityServiceActive(context),
            adminSession = com.ejemplo.locksuite.security.SessionManager.isActive()
        )
    }

    private fun checkAccessibilityStatus() {
        val context = applicationContext
        val now = android.os.SystemClock.elapsedRealtime()
        val gate = accessibilityGate(context) ?: run {
            // Si dejó de corresponder exigirla, hay que desarmar lo que se aplicó.
            cancelNag()
            liftEmergencySuspendIfNeeded(context)
            return
        }
        val policyManager = com.ejemplo.locksuite.mdm.PolicyManager(context)
        val prefs = com.ejemplo.locksuite.util.PrefsHelper.getMdmPrefs(context)

        if (gate.active) {
            cancelNag()
            liftEmergencySuspendIfNeeded(context)
            return
        }

        // ── La accesibilidad está APAGADA ──

        // Suspender navegadores (comportamiento histórico, siempre activo).
        if (!policyManager.areBrowsersSuspended()) {
            prefs.edit().putBoolean("browsers_suspended_by_watchdog", true).apply()
            policyManager.setBrowsersSuspended(true)
        }

        // Interruptor: suspender TODAS las apps no críticas, no solo los navegadores.
        // Es lo que convierte "el equipo es incómodo" en "el equipo no sirve para nada
        // hasta que la reactives". NO depende de la sesión de administrador (ver arriba).
        if (prefs.getBoolean(KEY_ACC_SUSPEND_ALL, false)) {
            applyEmergencySuspendIfNeeded(context)
        } else {
            liftEmergencySuspendIfNeeded(context)
        }

        // Interruptor: aviso insistente cada ~18 s.
        if (prefs.getBoolean(KEY_ACC_NAG, false)) {
            startNagIfNeeded()
        } else {
            cancelNag()
        }

        // La pantalla roja a pantalla completa SÍ se calla con sesión de administrador
        // abierta: si no, sería imposible trabajar en el equipo con el PIN puesto.
        if (!gate.adminSession && now - lastBlockLaunchTime > 3000) {
            lastBlockLaunchTime = now
            val blockIntent = Intent(context, com.ejemplo.locksuite.ui.emergency.BlockAccessibilityActivity::class.java).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
            }
            context.startActivity(blockIntent)
        }
    }

    private fun applyEmergencySuspendIfNeeded(context: android.content.Context) {
        if (emergencySuspendApplied) return
        emergencySuspendApplied = true
        // Se deja anotado en preferencias además de en memoria: el panel lo publica
        // como `accEmergencySuspendActive`, así se puede VER si se aplicó en vez de
        // adivinarlo, y sobrevive a que el proceso muera y el Watchdog reinicie.
        com.ejemplo.locksuite.util.PrefsHelper.getMdmPrefs(context)
            .edit().putBoolean("acc_emergency_suspend_active", true).apply()
        try {
            val ok = com.ejemplo.locksuite.mdm.AppController(context).setEmergencySuspendAll(true)
            android.util.Log.w("LockSuite_Watchdog", "Suspensión de emergencia por accesibilidad apagada: ok=$ok")
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    private fun liftEmergencySuspendIfNeeded(context: android.content.Context) {
        val prefs = com.ejemplo.locksuite.util.PrefsHelper.getMdmPrefs(context)
        // Se mira TAMBIÉN la preferencia, no solo el campo en memoria: si el proceso
        // murió con la suspensión puesta (pasa: los fabricantes matan servicios), al
        // reiniciar el campo arranca en false y las apps se habrían quedado suspendidas
        // sin que nadie las levantara nunca.
        if (!emergencySuspendApplied && !prefs.getBoolean("acc_emergency_suspend_active", false)) return
        emergencySuspendApplied = false
        prefs.edit().putBoolean("acc_emergency_suspend_active", false).apply()
        try {
            com.ejemplo.locksuite.mdm.AppController(context).setEmergencySuspendAll(false)
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    // ──────────────────────────────────────────────
    // Aviso insistente de accesibilidad apagada
    // ──────────────────────────────────────────────

    private fun shouldNag(): Boolean {
        val context = applicationContext
        if (!com.ejemplo.locksuite.util.PrefsHelper.getMdmPrefs(context)
                .getBoolean(KEY_ACC_NAG, false)
        ) return false
        // null = no corresponde exigirla; active = ya está activa.
        val gate = accessibilityGate(context) ?: return false
        return !gate.active
    }

    private fun startNagIfNeeded() {
        // Si el ciclo ya está en marcha no hay que re-encolarlo: `nagArmed` lo dice sin
        // depender de comparar relojes. Antes se usaba `now - lastNagAt < INTERVALO`, que
        // además de frágil no distinguía "ya está corriendo" de "recién avisó".
        if (nagArmed) return
        nagArmed = true
        handler.removeCallbacks(nagRunnable)
        handler.post(nagRunnable)
    }

    private fun cancelNag() {
        nagArmed = false
        nagCount = 0
        handler.removeCallbacks(nagRunnable)
        lastNagAt = 0L
        try {
            getSystemService(NotificationManager::class.java)?.cancel(NAG_NOTIFICATION_ID)
        } catch (e: Exception) {
            // ignorado
        }
    }

    /**
     * ⚠️ REESCRITO EL 18/8/2026 — antes avisaba UNA sola vez.
     *
     * Reporte del dueño: *"avisa una sola vez y listo"*. El ciclo de 18 s sí corría; lo
     * que no volvía a pasar era el AVISO. Tres motivos, los tres arreglados acá:
     *
     *  1. **Se re-publicaba la misma notificación, con el mismo id y el mismo texto.**
     *     Android trata eso como una ACTUALIZACIÓN de la notificación que ya está en la
     *     bandeja: la refresca en silencio y no vuelve a mostrar el cartel flotante ni a
     *     sonar. Visto desde afuera: apareció una vez y después "nada". Ahora se
     *     `cancel()` antes de volver a publicar y el texto cambia en cada aviso (lleva
     *     el número y hace cuánto que está apagada), así que el sistema no puede
     *     considerarla la misma.
     *  2. **Estaba como `setOngoing(true)`.** Una notificación persistente se comporta
     *     como "de estado", no como "alerta": muchas versiones y capas de fabricante no
     *     le muestran el cartel flotante. Sacado.
     *  3. **Le faltaba `setFullScreenIntent`.** Con `IMPORTANCE_HIGH` + intent de
     *     pantalla completa, el aviso se impone de verdad en vez de quedarse en la
     *     bandeja. Que es lo que se pidió: un aviso que moleste.
     *
     * Y se agregó vibración explícita en el canal. Ojo: en Android 8+ **el canal se crea
     * una sola vez y después NO se puede cambiar por código**. Si en el equipo ya
     * existía el canal viejo de esta versión, hay que reinstalar o borrar datos para que
     * tome la configuración nueva — por eso el id del canal lleva sufijo `_v2`.
     */
    private fun showAccessibilityNag() {
        lastNagAt = android.os.SystemClock.elapsedRealtime()
        nagCount++
        val context = applicationContext
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                val channel = NotificationChannel(
                    NAG_CHANNEL_ID,
                    "Protección de Accesibilidad",
                    NotificationManager.IMPORTANCE_HIGH
                ).apply {
                    description = "Avisa cuando el servicio de Accesibilidad de LockSuite está desactivado"
                    enableVibration(true)
                    vibrationPattern = longArrayOf(0, 400, 200, 400)
                    enableLights(true)
                    setShowBadge(true)
                    setBypassDnd(true)
                }
                getSystemService(NotificationManager::class.java)?.createNotificationChannel(channel)
            }

            val openIntent = Intent(context, com.ejemplo.locksuite.ui.emergency.BlockAccessibilityActivity::class.java)
                .apply { addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK) }
            val pending = android.app.PendingIntent.getActivity(
                context, 0, openIntent,
                android.app.PendingIntent.FLAG_UPDATE_CURRENT or android.app.PendingIntent.FLAG_IMMUTABLE
            )

            val minutos = (nagCount * NAG_INTERVAL_MS / 60_000L)
            val detalle = if (minutos >= 1L) {
                "Lleva $minutos ${if (minutos == 1L) "minuto" else "minutos"} desactivada."
            } else {
                "Aviso $nagCount."
            }

            val notification = NotificationCompat.Builder(context, NAG_CHANNEL_ID)
                .setContentTitle("⚠️ Protección desactivada")
                // El texto CAMBIA en cada aviso a propósito: es lo que hace que Android
                // lo trate como una alerta nueva y no como un refresco silencioso.
                .setContentText("El servicio de Accesibilidad de LockSuite está apagado. $detalle")
                .setStyle(
                    NotificationCompat.BigTextStyle().bigText(
                        "El filtro de contenido no está funcionando porque el servicio de " +
                            "Accesibilidad de LockSuite fue desactivado. El equipo permanecerá " +
                            "restringido hasta que se vuelva a activar.\n\n$detalle"
                    )
                )
                .setSmallIcon(android.R.drawable.stat_sys_warning)
                .setPriority(NotificationCompat.PRIORITY_MAX)
                .setDefaults(NotificationCompat.DEFAULT_ALL)
                .setCategory(NotificationCompat.CATEGORY_ALARM)
                .setContentIntent(pending)
                // Con IMPORTANCE_HIGH esto fuerza el cartel flotante; si la pantalla
                // está bloqueada, abre directamente la pantalla de bloqueo.
                .setFullScreenIntent(pending, true)
                .setAutoCancel(false)
                .setOnlyAlertOnce(false)
                .setWhen(System.currentTimeMillis())
                .setShowWhen(true)
                .build()

            val manager = androidx.core.app.NotificationManagerCompat.from(context)
            // Cancelar ANTES de publicar: sin esto Android reutiliza la notificación
            // existente y no vuelve a alertar. Es el arreglo del "avisa una sola vez".
            manager.cancel(NAG_NOTIFICATION_ID)
            manager.notify(NAG_NOTIFICATION_ID, notification)
        } catch (e: Exception) {
            android.util.Log.w("LockSuite_Watchdog", "No se pudo mostrar el aviso de accesibilidad: ${e.message}")
        }
    }

    private fun buildNotification(): Notification {
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("Protección del Sistema LockSuite")
            .setContentText("Servicio de seguridad empresarial activo.")
            .setSmallIcon(android.R.drawable.ic_lock_lock)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setOngoing(true)
            .build()
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "Servicios de Seguridad de LockSuite",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Mantiene activas las políticas del MDM LockSuite"
                setShowBadge(false)
            }
            val manager = getSystemService(NotificationManager::class.java)
            manager?.createNotificationChannel(channel)
        }
    }

    private fun scheduleWorkManagerWatchdog() {
        val workRequest = PeriodicWorkRequestBuilder<WatchdogWorker>(15, TimeUnit.MINUTES)
            .setConstraints(
                Constraints.Builder()
                    .setRequiredNetworkType(NetworkType.NOT_REQUIRED)
                    .build()
            )
            .build()
            
        WorkManager.getInstance(this).enqueueUniquePeriodicWork(
            "LockSuiteWatchdog",
            ExistingPeriodicWorkPolicy.KEEP,
            workRequest
        )
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        return START_STICKY
    }

    override fun onDestroy() {
        if (instance === this) instance = null
        handler.removeCallbacks(checkRunnable)
        handler.removeCallbacks(nagRunnable)
        accessibilityObserver?.let {
            try { contentResolver.unregisterContentObserver(it) } catch (e: Exception) { }
        }
        accessibilityObserver = null
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null
}
