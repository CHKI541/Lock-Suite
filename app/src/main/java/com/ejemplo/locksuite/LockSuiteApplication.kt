package com.ejemplo.locksuite

import android.app.Application
import android.content.Intent
import android.os.Build
import com.ejemplo.locksuite.mdm.PolicyManager
import com.ejemplo.locksuite.service.WatchdogForegroundService
import com.google.firebase.FirebaseApp
import com.ejemplo.locksuite.dns.DomainRuleEngine
import com.ejemplo.locksuite.dns.DnsActivityBuffer
import com.ejemplo.locksuite.dns.DomainRuleManager

class LockSuiteApplication : Application() {

    companion object {
        lateinit var domainRuleEngine: DomainRuleEngine
            private set
        lateinit var dnsActivityBuffer: DnsActivityBuffer
            private set
        lateinit var domainRuleManager: DomainRuleManager
            private set
    }

    override fun onCreate() {
        super.onCreate()

        // 0. Inicializar motor de reglas DNS (ANTES de que arranque la VPN)
        domainRuleEngine = DomainRuleEngine()
        dnsActivityBuffer = DnsActivityBuffer()
        domainRuleManager = DomainRuleManager(this, domainRuleEngine)
        domainRuleManager.loadRules()
        
        // 1. Inicializar Firebase
        try {
            FirebaseApp.initializeApp(this)
        } catch (e: Exception) {
            e.printStackTrace()
        }

        // Inicializar LocaleManager
        com.ejemplo.locksuite.util.LocaleManager.init(this)

        // 2. Re-aplicar restricciones MDM locales.
        // Envuelto en try/catch: Application.onCreate() es el punto de entrada de
        // todo el proceso — antes, si esta llamada fallaba (p.ej. un fallo
        // transitorio de Binder con DevicePolicyManager al arrancar muy temprano
        // durante el boot), una excepción sin capturar acá aborta el resto de
        // onCreate() y además puede tirar abajo el proceso entero (crash), de
        // modo que ni el Watchdog ni la VPN ni la sincronización con Firebase
        // llegan a iniciarse en ese ciclo. Exactamente lo que "no debe trabarse
        // ni crashear" prohíbe.
        try {
            PolicyManager(this).reapplyAllRestrictions()
        } catch (e: Exception) {
            e.printStackTrace()
        }

        // 2b. Activar la licencia Knox (Samsung) — no hace nada en otras marcas ni
        // mientras el SDK de Knox no este integrado (ver KnoxHardening.kt).
        try {
            com.ejemplo.locksuite.mdm.KnoxHardening.activateLicense(this)
        } catch (e: Exception) {
            e.printStackTrace()
        }

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

        // 3b. Garantizar que la VPN se inicie inmediatamente si la requiere la configuración
        try {
            com.ejemplo.locksuite.receiver.BootReceiver.ensureVpnRunning(this)
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
