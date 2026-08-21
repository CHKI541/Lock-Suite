package com.ejemplo.locksuite

import android.app.Application
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.Build
import android.os.UserManager
import com.ejemplo.locksuite.mdm.PolicyManager
import com.ejemplo.locksuite.service.WatchdogForegroundService
import com.google.firebase.FirebaseApp
import com.ejemplo.locksuite.dns.DomainRuleEngine
import com.ejemplo.locksuite.dns.DnsActivityBuffer
import com.ejemplo.locksuite.dns.DomainRuleManager

/**
 * Punto de entrada del proceso.
 *
 * ─────────────────────────────────────────────────────────────────────────────
 * ARRANQUE DIRECTO (Direct Boot) — POR QUÉ ESTE ARCHIVO ESTÁ ESCRITO ASÍ
 * ─────────────────────────────────────────────────────────────────────────────
 * (arreglo del 21/8/2026 — ver B.20)
 *
 * `BootReceiver` está declarado `android:directBootAware="true"` en el Manifest y
 * escucha `LOCKED_BOOT_COMPLETED`. Eso significa que Android arranca ESTE proceso
 * **antes del primer desbloqueo del equipo**, con el almacenamiento cifrado por
 * credencial (CE) todavía sin montar. Y arrancar el proceso implica construir esta
 * clase y ejecutar este `onCreate()`.
 *
 * En ese estado, CUALQUIER llamada a `getSharedPreferences(...)` lanza
 * `IllegalStateException: SharedPreferences in credential encrypted storage are not
 * available until after user is unlocked`. Si esa excepción sale de `onCreate()` de
 * la Application, Android no puede crear la aplicación y **mata el proceso entero**:
 *
 *     FATAL EXCEPTION: main
 *     java.lang.RuntimeException: Unable to create application ...LockSuiteApplication
 *     Caused by: java.lang.IllegalStateException: SharedPreferences in credential
 *                encrypted storage are not available until after user is unlocked
 *
 * Eso es exactamente lo que pasaba: `domainRuleManager.loadRules()` era el único
 * bloque de este método que NO estaba envuelto en try/catch, y `LocaleManager.init()`
 * tampoco lo estaba. Consecuencias en cadena, todas reales y todas medidas en el
 * equipo del dueño:
 *
 *   1. El proceso muere en cada arranque, antes de que corra `BootReceiver`.
 *   2. Ni el Watchdog ni la VPN de filtrado llegan a iniciarse en ese ciclo.
 *   3. Al reiniciarse el proceso tras el desbloqueo, `BootGate.engage()` cierra la
 *      red con el proxy global a puerto muerto (127.0.0.1:9999)… y como el Watchdog
 *      quedó sin arrancar, nadie ejecuta el `tick()` que la vuelve a abrir a los
 *      120 s. **El equipo se queda sin internet hasta que alguien abre la app y
 *      apaga y prende la VPN a mano.**
 *
 * Reglas que hay que sostener acá, y que no son adornos:
 *
 * - **Los tres objetos del motor DNS se construyen SIEMPRE**, desbloqueado o no. Son
 *   `lateinit`: si no se asignan, cualquier acceso posterior revienta con
 *   `UninitializedPropertyAccessException` (por ejemplo `shouldVpnBeRunning()`, que
 *   los consulta). Construirlos no toca disco; lo que toca disco es `loadRules()`.
 * - **`loadRules()` se difiere, no se saltea.** Si el proceso arrancó bloqueado y
 *   sobrevive hasta el desbloqueo, el motor se quedaría con CERO reglas y el filtro
 *   DNS correría vacío en silencio durante toda la vida de ese proceso — una
 *   regresión de seguridad muda. Por eso se registra un receptor de
 *   `ACTION_USER_UNLOCKED` y ahí se completa la inicialización. Además
 *   `BootReceiver` llama a `ensureDomainRulesLoaded()` en `BOOT_COMPLETED` como
 *   segunda red.
 * - **Nada en este método puede lanzar hacia afuera.** Es el punto de entrada del
 *   proceso entero: una excepción acá no rompe una función, mata la app.
 */
class LockSuiteApplication : Application() {

    companion object {
        private const val TAG = "LockSuiteApplication"

        lateinit var domainRuleEngine: DomainRuleEngine
            private set
        lateinit var dnsActivityBuffer: DnsActivityBuffer
            private set
        lateinit var domainRuleManager: DomainRuleManager
            private set

        /** Las reglas DNS ya se cargaron en este proceso. */
        @Volatile
        private var rulesLoaded = false

        /**
         * ¿Está montado el almacenamiento cifrado por credencial (o sea, ya se puede
         * leer `SharedPreferences`)?
         *
         * Antes de API 24 no existe el Arranque Directo, así que siempre es `true`.
         * Ante un fallo raro consultando el `UserManager` se devuelve `true` a
         * propósito: mantiene el comportamiento histórico y quien llama igual está
         * envuelto en try/catch. Devolver `false` ahí dejaría al equipo sin
         * inicializar para siempre por un error transitorio, que es peor.
         */
        fun isStorageUnlocked(context: Context): Boolean = try {
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.N) {
                true
            } else {
                context.getSystemService(UserManager::class.java)?.isUserUnlocked ?: true
            }
        } catch (e: Exception) {
            true
        }

        /**
         * Carga las reglas DNS si todavía no se cargaron y el almacenamiento está
         * disponible. Idempotente y segura de llamar desde cualquier lado.
         *
         * @return true si el motor de reglas ya tiene las reglas cargadas.
         */
        fun ensureDomainRulesLoaded(context: Context): Boolean {
            if (rulesLoaded) return true
            if (!isStorageUnlocked(context)) return false
            return try {
                domainRuleManager.loadRules()
                rulesLoaded = true
                android.util.Log.i(TAG, "Reglas DNS cargadas.")
                true
            } catch (e: Exception) {
                // Incluye UninitializedPropertyAccessException si alguien llama a esto
                // antes de que corra onCreate().
                android.util.Log.w(TAG, "No se pudieron cargar las reglas DNS: ${e.message}")
                false
            }
        }
    }

    /**
     * Receptor de `ACTION_USER_UNLOCKED`, solo presente si el proceso arrancó en
     * Arranque Directo. Se guarda en un campo para poder darlo de baja una vez que
     * hizo su trabajo.
     */
    private var unlockReceiver: BroadcastReceiver? = null

    override fun onCreate() {
        super.onCreate()

        // 0. Motor de reglas DNS. Los objetos se construyen SIEMPRE (ver cabecera):
        //    son `lateinit` y medio proyecto los consulta. Lo que se difiere si el
        //    equipo todavía no se desbloqueó es leerlas del disco.
        domainRuleEngine = DomainRuleEngine()
        dnsActivityBuffer = DnsActivityBuffer()
        domainRuleManager = DomainRuleManager(this, domainRuleEngine)

        if (!isStorageUnlocked(this)) {
            android.util.Log.w(
                TAG,
                "Arranque Directo: el almacenamiento cifrado todavía no está montado. " +
                    "Se difiere la inicialización hasta que el usuario desbloquee."
            )
            registerUnlockReceiver()
            return
        }

        initializeUnlocked()
    }

    /**
     * Espera al desbloqueo del equipo para completar la inicialización.
     *
     * Si el sistema mata este proceso antes de que llegue el broadcast no se pierde
     * nada: al desbloquear llega `BOOT_COMPLETED`, arranca un proceso nuevo con el
     * almacenamiento ya montado y `onCreate()` hace la inicialización completa por el
     * camino normal.
     */
    private fun registerUnlockReceiver() {
        if (unlockReceiver != null) return
        try {
            val receiver = object : BroadcastReceiver() {
                override fun onReceive(context: Context, intent: Intent) {
                    android.util.Log.i(TAG, "Usuario desbloqueado: completando la inicialización diferida.")
                    try {
                        unregisterReceiver(this)
                    } catch (ignored: Exception) {
                    }
                    unlockReceiver = null
                    initializeUnlocked()
                }
            }
            val filter = IntentFilter(Intent.ACTION_USER_UNLOCKED)
            // ACTION_USER_UNLOCKED es un broadcast protegido del sistema: el sistema lo
            // entrega igual con RECEIVER_NOT_EXPORTED, y con targetSdk 34 declarar la
            // bandera evita la excepción de registro en Android 14.
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                registerReceiver(receiver, filter, Context.RECEIVER_NOT_EXPORTED)
            } else {
                registerReceiver(receiver, filter)
            }
            unlockReceiver = receiver
        } catch (e: Exception) {
            android.util.Log.w(TAG, "No se pudo registrar el receptor de desbloqueo: ${e.message}")
        }
    }

    /**
     * Todo lo que necesita el almacenamiento cifrado montado. Se llama desde
     * `onCreate()` cuando el equipo ya está desbloqueado, o desde el receptor de
     * `ACTION_USER_UNLOCKED` cuando el proceso arrancó en Arranque Directo.
     *
     * Cada paso va en su propio try/catch a propósito: un fallo transitorio en uno
     * (típicamente Binder con el DevicePolicyManager, muy común en los primeros
     * segundos del arranque) no debe impedir que corran los demás.
     */
    private fun initializeUnlocked() {
        // 0b. Reglas DNS desde disco.
        ensureDomainRulesLoaded(this)

        // 0c. ARRANQUE PROTEGIDO — red de seguridad contra un proxy huérfano.
        //
        // Si un arranque anterior dejó puesto el proxy global a puerto muerto y nadie
        // llegó a sacarlo (proceso muerto, Watchdog sin arrancar, ventana vencida en
        // un boot anterior), el equipo queda sin internet. Cualquier arranque de este
        // proceso —incluido el que provoca el usuario al abrir la app— lo detecta y lo
        // limpia. Ver util/BootGate.kt y B.20.
        try {
            com.ejemplo.locksuite.util.BootGate.healStuckProxy(this, "arranque del proceso")
        } catch (e: Exception) {
            e.printStackTrace()
        }

        // 1. Inicializar Firebase
        try {
            FirebaseApp.initializeApp(this)
        } catch (e: Exception) {
            e.printStackTrace()
        }

        // 1b. Inicializar LocaleManager.
        // Envuelto en try/catch: lee SharedPreferences, así que era la SEGUNDA bomba de
        // Arranque Directo esperando detrás de loadRules(). Ver la cabecera del archivo.
        try {
            com.ejemplo.locksuite.util.LocaleManager.init(this)
        } catch (e: Exception) {
            e.printStackTrace()
        }

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
