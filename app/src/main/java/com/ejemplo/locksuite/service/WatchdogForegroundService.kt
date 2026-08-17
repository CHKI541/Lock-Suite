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
        const val NAG_CHANNEL_ID = "locksuite_accessibility_nag"

        /** Cada cuánto vuelve a aparecer el aviso. El dueño pidió 15-20 s. */
        private const val NAG_INTERVAL_MS = 18_000L

        // ── Claves de los interruptores de "Protecciones de Accesibilidad" ──
        const val KEY_ACC_BOUNCE_SETTINGS = "acc_protect_bounce_settings"
        const val KEY_ACC_NAG = "acc_protect_nag"
        const val KEY_ACC_SUSPEND_ALL = "acc_protect_suspend_all"
    }

    private var lastBlockLaunchTime = 0L
    private var lastSyncTime = 0L
    private var lastPrivateDnsEnforceTime = 0L
    private var lastNagAt = 0L
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
        createNotificationChannel()
        startForeground(NOTIFICATION_ID, buildNotification())
        scheduleWorkManagerWatchdog()
        handler.post(checkRunnable)

        val uri = Settings.Secure.getUriFor(Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES)
        accessibilityObserver = object : android.database.ContentObserver(handler) {
            override fun onChange(selfChange: Boolean) {
                super.onChange(selfChange)
                checkAccessibilityStatus()
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
     * ¿Corresponde exigir la Accesibilidad ahora mismo? Devuelve null si no hay que
     * hacer nada (suspensión, protección apagada, sin PIN configurado, sesión de
     * administrador abierta); si corresponde, devuelve si el servicio está activo.
     */
    private fun accessibilityRequirementState(context: android.content.Context): Boolean? {
        if (android.os.SystemClock.elapsedRealtime() < temporaryPauseUntil) return null
        val policyManager = com.ejemplo.locksuite.mdm.PolicyManager(context)
        // Con LockSuite suspendido no se exige Accesibilidad ni se suspende nada: si no,
        // la pantalla de bloqueo aparecería justo cuando el administrador acaba de
        // liberar el equipo.
        if (policyManager.isLockSuiteSuspended()) return null
        if (!policyManager.isAccessibilityProtectionEnabled()) return null
        if (!com.ejemplo.locksuite.security.PinManager.isPinConfigured(context)) return null
        if (com.ejemplo.locksuite.security.SessionManager.isActive()) return null
        return com.ejemplo.locksuite.util.BootGate.isAccessibilityServiceActive(context)
    }

    private fun checkAccessibilityStatus() {
        val context = applicationContext
        val now = android.os.SystemClock.elapsedRealtime()
        val active = accessibilityRequirementState(context) ?: run {
            // Si dejó de corresponder exigirla, hay que desarmar lo que se aplicó.
            cancelNag()
            liftEmergencySuspendIfNeeded(context)
            return
        }
        val policyManager = com.ejemplo.locksuite.mdm.PolicyManager(context)
        val prefs = com.ejemplo.locksuite.util.PrefsHelper.getMdmPrefs(context)

        if (active) {
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
        // hasta que la reactives".
        if (prefs.getBoolean(KEY_ACC_SUSPEND_ALL, false) && !emergencySuspendApplied) {
            emergencySuspendApplied = true
            try {
                com.ejemplo.locksuite.mdm.AppController(context).setEmergencySuspendAll(true)
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }

        // Interruptor: aviso insistente cada ~18 s.
        if (prefs.getBoolean(KEY_ACC_NAG, false)) {
            startNagIfNeeded()
        } else {
            cancelNag()
        }

        // Evitar relanzar la actividad repetidamente si ya se lanzó hace menos de 3 segundos
        if (now - lastBlockLaunchTime > 3000) {
            lastBlockLaunchTime = now
            // Abrir pantalla de bloqueo de accesibilidad
            val blockIntent = Intent(context, com.ejemplo.locksuite.ui.emergency.BlockAccessibilityActivity::class.java).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
            }
            context.startActivity(blockIntent)
        }
    }

    private fun liftEmergencySuspendIfNeeded(context: android.content.Context) {
        if (!emergencySuspendApplied) return
        emergencySuspendApplied = false
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
        // null = no corresponde exigirla; true = ya está activa.
        return accessibilityRequirementState(context) == false
    }

    private fun startNagIfNeeded() {
        val now = android.os.SystemClock.elapsedRealtime()
        // Si ya está en marcha, no re-encolar: duplicaría el ritmo del aviso.
        if (now - lastNagAt < NAG_INTERVAL_MS) return
        handler.removeCallbacks(nagRunnable)
        handler.post(nagRunnable)
    }

    private fun cancelNag() {
        handler.removeCallbacks(nagRunnable)
        lastNagAt = 0L
        try {
            getSystemService(NotificationManager::class.java)?.cancel(NAG_NOTIFICATION_ID)
        } catch (e: Exception) {
            // ignorado
        }
    }

    private fun showAccessibilityNag() {
        lastNagAt = android.os.SystemClock.elapsedRealtime()
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
                    setShowBadge(true)
                }
                getSystemService(NotificationManager::class.java)?.createNotificationChannel(channel)
            }

            val openIntent = Intent(context, com.ejemplo.locksuite.ui.emergency.BlockAccessibilityActivity::class.java)
                .apply { addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK) }
            val pending = android.app.PendingIntent.getActivity(
                context, 0, openIntent,
                android.app.PendingIntent.FLAG_UPDATE_CURRENT or android.app.PendingIntent.FLAG_IMMUTABLE
            )

            val notification = NotificationCompat.Builder(context, NAG_CHANNEL_ID)
                .setContentTitle("Protección desactivada")
                .setContentText("El servicio de Accesibilidad de LockSuite está apagado. Tocá para reactivarlo.")
                .setStyle(
                    NotificationCompat.BigTextStyle().bigText(
                        "El filtro de contenido no está funcionando porque el servicio de " +
                            "Accesibilidad de LockSuite fue desactivado. El equipo permanecerá " +
                            "restringido hasta que se vuelva a activar."
                    )
                )
                .setSmallIcon(android.R.drawable.stat_sys_warning)
                .setPriority(NotificationCompat.PRIORITY_HIGH)
                .setCategory(NotificationCompat.CATEGORY_ALARM)
                .setContentIntent(pending)
                .setAutoCancel(false)
                .setOngoing(true)
                .setOnlyAlertOnce(false)
                .build()

            androidx.core.app.NotificationManagerCompat.from(context)
                .notify(NAG_NOTIFICATION_ID, notification)
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
