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
    }

    private var lastBlockLaunchTime = 0L
    private var lastSyncTime = 0L
    private var lastPrivateDnsEnforceTime = 0L
    private var accessibilityObserver: android.database.ContentObserver? = null
    private val handler = android.os.Handler(android.os.Looper.getMainLooper())
    private val checkRunnable = object : Runnable {
        override fun run() {
            checkAccessibilityStatus()

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

    private fun checkAccessibilityStatus() {
        val context = applicationContext
        val now = android.os.SystemClock.elapsedRealtime()
        if (now < temporaryPauseUntil) return
        // Con LockSuite suspendido no se exige Accesibilidad ni se suspenden
        // navegadores: si no, la pantalla de bloqueo de accesibilidad aparecería
        // justo cuando el administrador acaba de liberar el equipo.
        val policyManager = com.ejemplo.locksuite.mdm.PolicyManager(context)
        if (policyManager.isLockSuiteSuspended()) return
        if (!policyManager.isAccessibilityProtectionEnabled()) return
        if (!com.ejemplo.locksuite.security.PinManager.isPinConfigured(context)) return
        if (com.ejemplo.locksuite.security.SessionManager.isActive()) return

        val enabledServices = Settings.Secure.getString(
            context.contentResolver,
            Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES
        ) ?: ""

        val shortId = "com.ejemplo.locksuite/.service.LockSuiteAccessibilityService"
        val longId = "com.ejemplo.locksuite/com.ejemplo.locksuite.service.LockSuiteAccessibilityService"
        val isAccessibilityActive = enabledServices.contains(shortId) || enabledServices.contains(longId)

        if (!isAccessibilityActive) {
            // Suspender navegadores
            if (!policyManager.areBrowsersSuspended()) {
                com.ejemplo.locksuite.util.PrefsHelper.getMdmPrefs(context)
                    .edit()
                    .putBoolean("browsers_suspended_by_watchdog", true)
                    .apply()
                policyManager.setBrowsersSuspended(true)
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
        accessibilityObserver?.let {
            try { contentResolver.unregisterContentObserver(it) } catch (e: Exception) { }
        }
        accessibilityObserver = null
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null
}
