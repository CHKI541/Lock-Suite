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
        // ARRANQUE PROTEGIDO — red de seguridad de último recurso (21/8/2026).
        //
        // Va PRIMERO y a propósito. Este Worker es el único mecanismo del proyecto que
        // sobrevive a que el proceso muera: WorkManager lo vuelve a levantar cada 15
        // minutos aunque el servicio de primer plano esté caído. Antes NO llamaba al
        // arranque protegido, que es justamente lo que hacía falta: si el proxy global
        // a puerto muerto quedaba puesto y el Watchdog de primer plano no estaba vivo
        // para correr su `tick()` de 20 s, el equipo se quedaba sin internet
        // indefinidamente — hasta que alguien abría la app y apagaba y prendía la VPN
        // a mano. Ese era, textual, el reporte del dueño. Ver util/BootGate.kt y B.20.
        //
        // Con esto el peor caso posible pasa a ser 15 minutos sin internet, y se
        // arregla solo. `healStuckProxy()` es barata: una lectura de Settings.Global
        // que en el caso normal no encuentra nada y sale.
        // Los dos van en try/catch SEPARADOS por el mismo motivo que explica el
        // comentario de abajo: si `tick()` falla, la limpieza del proxy —que es la que
        // devuelve el internet— tiene que correr igual.
        try {
            com.ejemplo.locksuite.util.BootGate.tick(applicationContext)
        } catch (e: Exception) {
            e.printStackTrace()
        }
        try {
            com.ejemplo.locksuite.util.BootGate.healStuckProxy(applicationContext, "WatchdogWorker 15 min")
        } catch (e: Exception) {
            e.printStackTrace()
        }

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
