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

        /** Notificación del aviso insistente de accesibilidad. Canal aparte, de alta prioridad. */
        const val NAG_NOTIFICATION_ID = 9003
        // Sufijo _v2: en Android 8+ un canal de notificación no se puede reconfigurar
        // por código una vez creado. La versión anterior lo creó sin vibración ni
        // bypass de "no molestar"; cambiando el id se crea uno nuevo con la
        // configuración correcta en vez de arrastrar el viejo para siempre.
        const val NAG_CHANNEL_ID = "locksuite_accessibility_nag_v2"

        /** Cada cuánto vuelve a aparecer el aviso. El dueño pidió 15-20 s. */
        private const val NAG_INTERVAL_MS = 18_000L

        /** Ritmo de la vigilancia de accesibilidad cuando todo está bien. */
        private const val ENFORCER_IDLE_MS = 20_000L

        /**
         * Ritmo mientras la accesibilidad está caída. Más seguido porque es el momento
         * en que hay que corregir, y el equipo ya está inutilizado de todas formas.
         */
        private const val ENFORCER_FAST_MS = 5_000L

        // ── Claves de los interruptores de "Protecciones de Accesibilidad" ──
        const val KEY_ACC_BOUNCE_SETTINGS = "acc_protect_bounce_settings"
        const val KEY_ACC_NAG = "acc_protect_nag"
        const val KEY_ACC_SUSPEND_ALL = "acc_protect_suspend_all"

        /**
         * Instancia viva del servicio, para poder pedirle una revisión inmediata.
         *
         * Motivo (18/8/2026): los interruptores de Protecciones de Accesibilidad solo
         * se hacían efectivos en el próximo ciclo del Watchdog, o sea **hasta 20
         * segundos después**. Quien los prueba toca el interruptor, mira el equipo, no
         * ve nada y concluye que no funcionan. Ahora el propio `PolicyManager` pide una
         * revisión al toque.
         */
        @Volatile private var instance: WatchdogForegroundService? = null

        /** Revisión inmediata del estado de accesibilidad. Segura si el servicio no corre. */
        @JvmStatic
        fun requestImmediateCheck() {
            val svc = instance ?: return
            // Se reinicia el antirrebote: quien llama acaba de cambiar algo a propósito,
            // así que no hay nada que "esperar a confirmar".
            com.ejemplo.locksuite.util.AccessibilityEnforcer.resetDebounce()
            svc.handler.post {
                svc.checkAccessibilityStatus()
                // Re-armar el ciclo con el ritmo que corresponda al estado nuevo.
                svc.handler.removeCallbacks(svc.enforcerRunnable)
                svc.handler.postDelayed(svc.enforcerRunnable, ENFORCER_FAST_MS)
            }
        }
    }

    private var lastBlockLaunchTime = 0L
    private var lastSyncTime = 0L
    private var lastPrivateDnsEnforceTime = 0L
    private var lastNagAt = 0L
    /** ¿El ciclo del aviso ya está encolado? Evita duplicar el ritmo al re-armarlo. */
    private var nagArmed = false
    /** Cuántos avisos van en esta racha. Solo para que el texto cambie en cada uno. */
    private var nagCount = 0
    private var accessibilityObserver: android.database.ContentObserver? = null
    private val handler = android.os.Handler(android.os.Looper.getMainLooper())

    /**
     * Aviso insistente de accesibilidad apagada (interruptor `acc_protect_nag`).
     *
     * Va en su propio ciclo de 18 s y NO dentro del ciclo de 20 s del Watchdog: el
     * pedido del dueño era "cada 15-20 segundos", y engancharlo al ciclo grande
     * significaría rehacer todo el resto del trabajo del Watchdog solo para mostrar
     * una notificación. Este runnable solo se encola mientras el aviso hace falta y se
     * desencola apenas la accesibilidad vuelve, así que en el caso normal (todo bien)
     * no corre nunca.
     */
    private val nagRunnable = object : Runnable {
        override fun run() {
            if (!shouldNag()) {
                cancelNag()
                return
            }
            showAccessibilityNag()
            // Se re-encola siempre acá, no desde el ciclo de 20 s del Watchdog: así el
            // ritmo del aviso es exactamente NAG_INTERVAL_MS y no queda a merced de
            // cuándo le toque correr al Watchdog.
            handler.postDelayed(this, NAG_INTERVAL_MS)
        }
    }

    /**
     * Vigilancia de la accesibilidad, en su propio ciclo y a ritmo variable.
     *
     * Va SEPARADO del ciclo grande del Watchdog a propósito. El pedido del dueño fue
     * *"tendría que detectar todo el tiempo si está activada la accesibilidad, y según
     * eso suspender/desuspender las apps"*, y acelerar el ciclo grande para lograrlo
     * habría multiplicado por cuatro también el trabajo caro que hace (sincronizar con
     * Firebase, reimponer DNS privado, revisar la VPN y la marca de agua). Esto en
     * cambio es barato: con la accesibilidad activa —el 99,9 % del tiempo— es una
     * consulta al AccessibilityManager y una comparación, y ni siquiera enumera apps.
     *
     * El ritmo se adapta solo: 20 s cuando todo está bien, 5 s mientras la accesibilidad
     * está caída, que es cuando conviene corregir rápido y el equipo está de todas
     * formas inutilizable. Y además hay dos avisos instantáneos que no cuestan nada: el
     * ContentObserver de este servicio y el propio servicio de accesibilidad, que avisa
     * al conectarse y al destruirse. O sea que este ciclo es la red de seguridad, no el
     * mecanismo principal.
     */
    private val enforcerRunnable = object : Runnable {
        override fun run() {
            try {
                checkAccessibilityStatus()
            } catch (e: Exception) {
                e.printStackTrace()
            }
            val violada = com.ejemplo.locksuite.util.AccessibilityEnforcer.lastVerdict ==
                com.ejemplo.locksuite.util.AccessibilityEnforcer.Verdict.VIOLATED
            handler.postDelayed(this, if (violada) ENFORCER_FAST_MS else ENFORCER_IDLE_MS)
        }
    }

    private val checkRunnable = object : Runnable {
        override fun run() {
            // Arranque protegido: liberar la red si el filtro ya está listo, o si venció
            // la ventana de seguridad. Ver util/BootGate.kt.
            try {
                com.ejemplo.locksuite.util.BootGate.tick(applicationContext)
            } catch (e: Exception) {
                e.printStackTrace()
            }

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
        instance = this
        createNotificationChannel()
        startForeground(NOTIFICATION_ID, buildNotification())
        scheduleWorkManagerWatchdog()
        handler.post(checkRunnable)
        handler.post(enforcerRunnable)

        val uri = Settings.Secure.getUriFor(Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES)
        accessibilityObserver = object : android.database.ContentObserver(handler) {
            override fun onChange(selfChange: Boolean) {
                super.onChange(selfChange)
                // Este observador dispara con CADA escritura de la lista de servicios de
                // accesibilidad, incluidas las intermedias de una transición. Por eso NO
                // se salta el antirrebote: se limita a despertar la revisión, y quien
                // decide si el estado cambió de verdad es el reconciliador.
                checkAccessibilityStatus()
                // Re-armar el ciclo con el ritmo que corresponda al estado nuevo.
                handler.removeCallbacks(enforcerRunnable)
                handler.postDelayed(enforcerRunnable, 1_500L)
                // Si el arranque protegido estaba esperando justamente a que se
                // activara la accesibilidad, liberar en el acto en vez de esperar
                // hasta 20 s al próximo ciclo.
                try {
                    com.ejemplo.locksuite.util.BootGate.tick(applicationContext)
                } catch (e: Exception) {
                    e.printStackTrace()
                }
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

    /**
     * Estado de la exigencia de Accesibilidad.
     *
     * ⚠️ CAMBIO IMPORTANTE DEL 18/8/2026 — POR QUÉ ESTO DEVUELVE `adminSession` EN VEZ
     * DE TRAGARSE TODO.
     *
     * La versión anterior devolvía `null` (o sea "no hacer nada") cuando había una
     * sesión de administrador abierta. Eso rompía las protecciones justo cuando alguien
     * las probaba: la sesión dura CINCO MINUTOS desde que ingresás el PIN, y para tocar
     * los interruptores en la app hay que ingresar el PIN. O sea que el dueño encendía
     * "suspender todas las apps", iba a Ajustes, apagaba la accesibilidad… y no pasaba
     * nada, porque su propia sesión estaba suprimiendo la protección. Reporte textual:
     * *"las otras 2 no funcionan"*. Funcionaban; estaban calladas.
     *
     * Ahora la sesión de administrador suprime ÚNICAMENTE la pantalla roja a pantalla
     * completa —para que el administrador pueda trabajar sin que se le tire encima— y
     * no toca ni la suspensión de apps ni el aviso. Es recuperable: al reactivar la
     * accesibilidad todo vuelve solo. Para silenciar todo a propósito está
     * `temporaryPauseUntil`, que es explícito y con vencimiento.
     */
    /**
     * ⚠️ REESCRITO EL 18/8/2026 — ahora esto NO decide nada por su cuenta.
     *
     * La lógica de "¿está la accesibilidad?" y "¿qué apps deberían estar suspendidas?"
     * se mudó entera a `util/AccessibilityEnforcer.kt`, que reconcilia contra el estado
     * REAL del sistema en vez de recordar lo que hizo la última vez. Acá solo queda lo
     * que necesita el contexto de un servicio: el aviso y la pantalla roja.
     *
     * El porqué del cambio está en el comentario de cabecera del Enforcer. Resumen: las
     * apps y el aviso fallaban igual y al mismo tiempo porque las dos cosas colgaban de
     * la misma pregunta mal respondida, y encima se recordaba "ya lo hice" en una
     * variable que otros mecanismos invalidaban por atrás.
     */
    private fun checkAccessibilityStatus() {
        val context = applicationContext
        val now = android.os.SystemClock.elapsedRealtime()

        // null = lectura todavía sin confirmar (antirrebote). No tocar nada: actuar
        // sobre estados de transición era exactamente lo que producía el vaivén.
        val verdict = com.ejemplo.locksuite.util.AccessibilityEnforcer.evaluate(context) ?: return

        // Las apps se reconcilian SIEMPRE, con cualquier veredicto: es lo que hace que
        // se auto-repare si otro mecanismo las liberó por atrás.
        com.ejemplo.locksuite.util.AccessibilityEnforcer.reconcileApps(context, verdict)

        if (verdict != com.ejemplo.locksuite.util.AccessibilityEnforcer.Verdict.VIOLATED) {
            cancelNag()
            return
        }

        // ── La accesibilidad está APAGADA (confirmado) ──
        val policyManager = com.ejemplo.locksuite.mdm.PolicyManager(context)
        val prefs = com.ejemplo.locksuite.util.PrefsHelper.getMdmPrefs(context)

        // Suspender navegadores (comportamiento histórico, siempre activo).
        if (!policyManager.areBrowsersSuspended()) {
            prefs.edit().putBoolean("browsers_suspended_by_watchdog", true).apply()
            policyManager.setBrowsersSuspended(true)
        }

        // Interruptor: aviso insistente cada ~18 s.
        if (prefs.getBoolean(KEY_ACC_NAG, false)) {
            startNagIfNeeded()
        } else {
            cancelNag()
        }

        // La pantalla roja a pantalla completa SÍ se calla con sesión de administrador
        // abierta: si no, sería imposible trabajar en el equipo con el PIN puesto.
        val adminSession = com.ejemplo.locksuite.security.SessionManager.isActive()
        if (!adminSession && now - lastBlockLaunchTime > 3000) {
            lastBlockLaunchTime = now
            val blockIntent = Intent(context, com.ejemplo.locksuite.ui.emergency.BlockAccessibilityActivity::class.java).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
            }
            context.startActivity(blockIntent)
        }
    }

    // ──────────────────────────────────────────────
    // Aviso insistente de accesibilidad apagada
    // ──────────────────────────────────────────────

    private fun shouldNag(): Boolean {
        val context = applicationContext
        if (!com.ejemplo.locksuite.util.PrefsHelper.getMdmPrefs(context)
                .getBoolean(KEY_ACC_NAG, false)
        ) return false
        // Se usa el ÚLTIMO veredicto confirmado, no una lectura nueva: si el aviso
        // consultara por su cuenta podría leer un estado de transición y cortarse solo
        // a mitad de la racha. Eso era, en parte, el "a veces avisa y a veces no".
        return com.ejemplo.locksuite.util.AccessibilityEnforcer.lastVerdict ==
            com.ejemplo.locksuite.util.AccessibilityEnforcer.Verdict.VIOLATED
    }

    private fun startNagIfNeeded() {
        // Si el ciclo ya está en marcha no hay que re-encolarlo: `nagArmed` lo dice sin
        // depender de comparar relojes. Antes se usaba `now - lastNagAt < INTERVALO`, que
        // además de frágil no distinguía "ya está corriendo" de "recién avisó".
        if (nagArmed) return
        nagArmed = true
        handler.removeCallbacks(nagRunnable)
        handler.post(nagRunnable)
    }

    private fun cancelNag() {
        nagArmed = false
        nagCount = 0
        handler.removeCallbacks(nagRunnable)
        lastNagAt = 0L
        try {
            getSystemService(NotificationManager::class.java)?.cancel(NAG_NOTIFICATION_ID)
        } catch (e: Exception) {
            // ignorado
        }
    }

    /**
     * ⚠️ REESCRITO EL 18/8/2026 — antes avisaba UNA sola vez.
     *
     * Reporte del dueño: *"avisa una sola vez y listo"*. El ciclo de 18 s sí corría; lo
     * que no volvía a pasar era el AVISO. Tres motivos, los tres arreglados acá:
     *
     *  1. **Se re-publicaba la misma notificación, con el mismo id y el mismo texto.**
     *     Android trata eso como una ACTUALIZACIÓN de la notificación que ya está en la
     *     bandeja: la refresca en silencio y no vuelve a mostrar el cartel flotante ni a
     *     sonar. Visto desde afuera: apareció una vez y después "nada". Ahora se
     *     `cancel()` antes de volver a publicar y el texto cambia en cada aviso (lleva
     *     el número y hace cuánto que está apagada), así que el sistema no puede
     *     considerarla la misma.
     *  2. **Estaba como `setOngoing(true)`.** Una notificación persistente se comporta
     *     como "de estado", no como "alerta": muchas versiones y capas de fabricante no
     *     le muestran el cartel flotante. Sacado.
     *  3. **Le faltaba `setFullScreenIntent`.** Con `IMPORTANCE_HIGH` + intent de
     *     pantalla completa, el aviso se impone de verdad en vez de quedarse en la
     *     bandeja. Que es lo que se pidió: un aviso que moleste.
     *
     * Y se agregó vibración explícita en el canal. Ojo: en Android 8+ **el canal se crea
     * una sola vez y después NO se puede cambiar por código**. Si en el equipo ya
     * existía el canal viejo de esta versión, hay que reinstalar o borrar datos para que
     * tome la configuración nueva — por eso el id del canal lleva sufijo `_v2`.
     */
    private fun showAccessibilityNag() {
        lastNagAt = android.os.SystemClock.elapsedRealtime()
        nagCount++
        val context = applicationContext
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                val channel = NotificationChannel(
                    NAG_CHANNEL_ID,
                    "Protección de Accesibilidad",
                    NotificationManager.IMPORTANCE_HIGH
                ).apply {
                    description = "Avisa cuando el servicio de Accesibilidad de LockSuite está desactivado"
                    enableVibration(true)
                    vibrationPattern = longArrayOf(0, 400, 200, 400)
                    enableLights(true)
                    setShowBadge(true)
                    setBypassDnd(true)
                }
                getSystemService(NotificationManager::class.java)?.createNotificationChannel(channel)
            }

            val openIntent = Intent(context, com.ejemplo.locksuite.ui.emergency.BlockAccessibilityActivity::class.java)
                .apply { addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK) }
            val pending = android.app.PendingIntent.getActivity(
                context, 0, openIntent,
                android.app.PendingIntent.FLAG_UPDATE_CURRENT or android.app.PendingIntent.FLAG_IMMUTABLE
            )

            val minutos = (nagCount * NAG_INTERVAL_MS / 60_000L)
            val detalle = if (minutos >= 1L) {
                "Lleva $minutos ${if (minutos == 1L) "minuto" else "minutos"} desactivada."
            } else {
                "Aviso $nagCount."
            }

            val notification = NotificationCompat.Builder(context, NAG_CHANNEL_ID)
                .setContentTitle("⚠️ Protección desactivada")
                // El texto CAMBIA en cada aviso a propósito: es lo que hace que Android
                // lo trate como una alerta nueva y no como un refresco silencioso.
                .setContentText("El servicio de Accesibilidad de LockSuite está apagado. $detalle")
                .setStyle(
                    NotificationCompat.BigTextStyle().bigText(
                        "El filtro de contenido no está funcionando porque el servicio de " +
                            "Accesibilidad de LockSuite fue desactivado. El equipo permanecerá " +
                            "restringido hasta que se vuelva a activar.\n\n$detalle"
                    )
                )
                .setSmallIcon(android.R.drawable.stat_sys_warning)
                .setPriority(NotificationCompat.PRIORITY_MAX)
                .setDefaults(NotificationCompat.DEFAULT_ALL)
                .setCategory(NotificationCompat.CATEGORY_ALARM)
                .setContentIntent(pending)
                // Con IMPORTANCE_HIGH esto fuerza el cartel flotante; si la pantalla
                // está bloqueada, abre directamente la pantalla de bloqueo.
                .setFullScreenIntent(pending, true)
                .setAutoCancel(false)
                .setOnlyAlertOnce(false)
                .setWhen(System.currentTimeMillis())
                .setShowWhen(true)
                .build()

            val manager = androidx.core.app.NotificationManagerCompat.from(context)
            // Cancelar ANTES de publicar: sin esto Android reutiliza la notificación
            // existente y no vuelve a alertar. Es el arreglo del "avisa una sola vez".
            manager.cancel(NAG_NOTIFICATION_ID)
            manager.notify(NAG_NOTIFICATION_ID, notification)
        } catch (e: Exception) {
            android.util.Log.w("LockSuite_Watchdog", "No se pudo mostrar el aviso de accesibilidad: ${e.message}")
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
        if (instance === this) instance = null
        handler.removeCallbacks(checkRunnable)
        handler.removeCallbacks(enforcerRunnable)
        handler.removeCallbacks(nagRunnable)
        accessibilityObserver?.let {
            try { contentResolver.unregisterContentObserver(it) } catch (e: Exception) { }
        }
        accessibilityObserver = null
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null
}
