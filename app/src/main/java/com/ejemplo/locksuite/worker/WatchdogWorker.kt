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
        // Re-aplicar todas las restricciones MDM guardadas.
        // Envuelto en try/catch: antes, una excepción acá (p.ej. un fallo transitorio
        // de Binder con DevicePolicyManager) abortaba el resto de doWork() sin
        // ejecutarse — es decir, ese ciclo también se saltaba la verificación de
        // que la VPN siga viva y la sincronización con Firebase, justo las dos
        // comprobaciones que existen para garantizar "el filtro nunca se cae" y
        // "siempre controlable desde la web". Un Worker de auto-sanación redundante
        // no debe dejar que un fallo puntual en un chequeo cancele los demás.
        try {
            PolicyManager(applicationContext).reapplyAllRestrictions()
        } catch (e: Exception) {
            e.printStackTrace()
        }

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
        try {
            com.ejemplo.locksuite.receiver.BootReceiver.ensureVpnRunning(applicationContext)
        } catch (e: Exception) {
            e.printStackTrace()
        }

        // Sincronizar información del dispositivo periódicamente en segundo plano
        try {
            com.ejemplo.locksuite.util.FirebaseDeviceSync.syncDeviceInfo(applicationContext)
        } catch (e: Exception) {
            e.printStackTrace()
        }

        return Result.success()
    }
}
