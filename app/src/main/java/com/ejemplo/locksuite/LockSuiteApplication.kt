package com.ejemplo.locksuite

import android.app.Application
import android.content.Intent
import android.os.Build
import com.ejemplo.locksuite.mdm.PolicyManager
import com.ejemplo.locksuite.service.WatchdogForegroundService
import com.google.firebase.FirebaseApp

class LockSuiteApplication : Application() {

    override fun onCreate() {
        super.onCreate()
        
        // 1. Inicializar Firebase
        try {
            FirebaseApp.initializeApp(this)
        } catch (e: Exception) {
            e.printStackTrace()
        }

        // Inicializar LocaleManager
        com.ejemplo.locksuite.util.LocaleManager.init(this)

        // 2. Re-aplicar restricciones MDM locales
        PolicyManager(this).reapplyAllRestrictions()

        // 3. Iniciar el servicio Watchdog persistentemente
        val serviceIntent = Intent(this, WatchdogForegroundService::class.java)
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                startForegroundService(serviceIntent)
            } else {
                startService(serviceIntent)
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }

        // 4. Marcar el dispositivo como "En línea" de forma casi instantánea al iniciar la app
        //    (solo escribe el timestamp lastSeen, sin esperar la sincronización completa)
        try {
            com.ejemplo.locksuite.util.FirebaseDeviceSync.syncLastSeenOnly(this)
        } catch (e: Exception) {
            e.printStackTrace()
        }

        // 5. Sincronizar información completa del dispositivo de forma proactiva
        try {
            com.ejemplo.locksuite.util.FirebaseDeviceSync.syncDeviceInfo(this)
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }
}
