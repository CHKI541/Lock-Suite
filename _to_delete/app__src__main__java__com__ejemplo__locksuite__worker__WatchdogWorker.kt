package com.ejemplo.locksuite.worker

import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.work.Worker
import androidx.work.WorkerParameters
import com.ejemplo.locksuite.mdm.PolicyManager
import com.ejemplo.locksuite.service.WatchdogForegroundService

class WatchdogWorker(context: Context, params: WorkerParameters) : Worker(context, params) {

    override fun doWork(): Result {
        // Re-aplicar todas las restricciones MDM guardadas
        PolicyManager(applicationContext).reapplyAllRestrictions()

        // Verificar que el servicio de primer plano siga ejecutándose
        try {
            val serviceIntent = Intent(applicationContext, WatchdogForegroundService::class.java)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                applicationContext.startForegroundService(serviceIntent)
            } else {
                applicationContext.startService(serviceIntent)
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }

        // Garantizar que KosherVpnService siga activo si cualquier política lo requiere
        com.ejemplo.locksuite.receiver.BootReceiver.ensureVpnRunning(applicationContext)

        // Sincronizar información del dispositivo periódicamente en segundo plano
        try {
            com.ejemplo.locksuite.util.FirebaseDeviceSync.syncDeviceInfo(applicationContext)
        } catch (e: Exception) {
            e.printStackTrace()
        }

        return Result.success()
    }
}
