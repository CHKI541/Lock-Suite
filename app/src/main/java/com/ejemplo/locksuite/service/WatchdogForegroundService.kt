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
    private val handler = android.os.Handler(android.os.Looper.getMainLooper())
    private val checkRunnable = object : Runnable {
        override fun run() {
            checkAccessibilityStatus()
            
            // Sincronizar periódicamente cada 90 segundos para mantener el estado "En línea" en la web sin abrir la app
            val now = System.currentTimeMillis()
            if (now - lastSyncTime > 90000) {
                lastSyncTime = now
                try {
                    com.ejemplo.locksuite.util.FirebaseDeviceSync.syncLastSeenOnly(applicationContext)
                } catch (e: Exception) {
                    e.printStackTrace()
                }
            }
            
            handler.postDelayed(this, 3000)
        }
    }

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
        startForeground(NOTIFICATION_ID, buildNotification())
        scheduleWorkManagerWatchdog()
        handler.post(checkRunnable)

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
        if (System.currentTimeMillis() < temporaryPauseUntil) return
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
            val policyManager = com.ejemplo.locksuite.mdm.PolicyManager(context)
            if (!policyManager.areBrowsersSuspended()) {
                com.ejemplo.locksuite.util.PrefsHelper.getMdmPrefs(context)
                    .edit()
                    .putBoolean("browsers_suspended_by_watchdog", true)
                    .apply()
                policyManager.setBrowsersSuspended(true)
            }

            // Evitar relanzar la actividad repetidamente si ya se lanzó hace menos de 15 segundos
            val now = System.currentTimeMillis()
            if (now - lastBlockLaunchTime > 15000) {
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
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null
}
