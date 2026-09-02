package com.ejemplo.locksuite.worker

import android.content.Context
import android.content.Intent
import android.os.Build
import androidx.work.Worker
import androidx.work.WorkerParameters
import com.ejemplo.locksuite.mdm.PolicyManager
import com.ejemplo.locksuite.service.WatchdogForegroundService
import com.ejemplo.locksuite.util.PrefsHelper

class WatchdogWorker(context: Context, params: WorkerParameters) : Worker(context, params) {

    override fun doWork(): Result {
        if (PrefsHelper.getMdmPrefs(applicationContext).getBoolean("locksuite_purged", false)) {
            return Result.success()
        }

        // LATIDO PRIMERO, Y BARATO (2/9/2026) — "a veces los celulares no quedan en línea".
        //
        // El único latido que había en la práctica era el del servicio de primer plano:
        // `syncLastSeenOnly()` cada 90 s, dentro de un `Handler.postDelayed(20_000)`. Y
        // `postDelayed` cuenta con `SystemClock.uptimeMillis()`, que NO AVANZA mientras el
        // equipo está en sueño profundo. Un celular con la pantalla apagada un rato deja
        // de latir; el panel considera "En línea" solo los últimos minutos, así que el
        // equipo aparece "Desconectado" aunque esté perfecto y reciba comandos sin
        // problema (FCM de alta prioridad despierta el equipo igual).
        //
        // Este Worker es lo único que WorkManager garantiza que corre aunque el proceso
        // haya muerto y aunque el equipo esté en Doze (en la ventana de mantenimiento).
        // Antes sincronizaba, sí — pero al FINAL, con `syncDeviceInfo()`, que es la
        // sincronización pesada (pide el token de FCM, enumera políticas, escribe ~40
        // campos) y encima quedaba después de `reapplyAllRestrictions()`, o sea que si esa
        // parte tardaba o el proceso se moría antes, el latido no salía nunca.
        //
        // Escribir solo `lastSeen` es una escritura de dos campos y va PRIMERO.
        try {
            com.ejemplo.locksuite.util.FirebaseDeviceSync.syncLastSeenOnly(applicationContext)
        } catch (e: Exception) {
            e.printStackTrace()
        }

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

        // Sincronización COMPLETA: una de cada cuatro vueltas, o sea ~1 vez por hora.
        //
        // 2/9/2026 (batería). `syncDeviceInfo()` no es barata: pide el token de FCM,
        // construye un PolicyManager, enumera el estado de ~40 políticas, consulta
        // `getUserRestrictions()` y escribe un nodo grande en Firebase — y encima llama a
        // `syncAppsListInternal()`, que enumera TODAS las apps del equipo. Hacer eso cada
        // 15 minutos es cuatro veces por hora un trabajo que casi nunca cambia entre una
        // vuelta y la siguiente.
        //
        // Lo que sí tiene que pasar siempre —que el panel vea el equipo en línea— ya se
        // hizo arriba con `syncLastSeenOnly()`, que son dos campos.
        //
        // Y lo que hace que esto NO retrase un cambio real: cada comando del panel llama a
        // `syncDeviceInfo()` al terminar de aplicarse (ver LockSuiteFirebaseService), así
        // que el panel refleja los cambios al instante. Esta vuelta periódica solo existe
        // para reconciliar lo que haya cambiado SIN pasar por el panel.
        val vuelta = PrefsHelper.getMdmPrefs(applicationContext)
            .getInt("watchdog_worker_tick", 0)
        PrefsHelper.getMdmPrefs(applicationContext).edit()
            .putInt("watchdog_worker_tick", (vuelta + 1) % 4).apply()
        if (vuelta == 0) {
            try {
                com.ejemplo.locksuite.util.FirebaseDeviceSync.syncDeviceInfo(applicationContext)
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }

        return Result.success()
    }
}
