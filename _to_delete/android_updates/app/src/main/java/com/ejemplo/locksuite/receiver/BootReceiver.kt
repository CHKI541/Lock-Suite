package com.ejemplo.locksuite.receiver

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import com.ejemplo.locksuite.mdm.PolicyManager
import com.ejemplo.locksuite.service.WatchdogForegroundService
import com.ejemplo.locksuite.ui.kiosk.KioskLauncherActivity
import com.ejemplo.locksuite.util.FirebaseDeviceSync

class BootReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_BOOT_COMPLETED &&
            intent.action != "android.intent.action.LOCKED_BOOT_COMPLETED"
        ) return

        val policyManager = PolicyManager(context)

        // 1. Reaplicar todas las políticas MDM guardadas
        policyManager.reapplyAllRestrictions()

        // 1b. Reportar estado al panel de administración sin esperar a que
        //     alguien abra el Dashboard o cambie el token FCM.
        FirebaseDeviceSync.syncDeviceInfo(context)

        // 2. Levantar el servicio Watchdog
        val serviceIntent = Intent(context, WatchdogForegroundService::class.java)
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(serviceIntent)
            } else {
                context.startService(serviceIntent)
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }

        // 3. Si el modo kiosco está configurado, asegurar que el launcher
        //    quede al frente tras el reinicio.
        //    NOTA: en la versión anterior este bloque leía
        //    Constants.KIOSK_MODE_KEY pero ningún lugar del código lo
        //    escribía nunca — el flag quedaba siempre en false y este bloque
        //    jamás se ejecutaba. Ahora PolicyManager.setAllowedPackages()
        //    es quien lo setea, así que queda conectado de punta a punta.
        if (policyManager.isKioskModeEnabled()) {
            val kioskIntent = Intent(context, KioskLauncherActivity::class.java).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
            }
            try {
                context.startActivity(kioskIntent)
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }
    }
}
