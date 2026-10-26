package com.ejemplo.locksuite.util

import android.content.Context
import android.os.SystemClock
import com.ejemplo.locksuite.service.CommandProcessor
import com.google.firebase.database.DataSnapshot
import com.google.firebase.database.DatabaseError
import com.google.firebase.database.DatabaseReference
import com.google.firebase.database.FirebaseDatabase
import com.google.firebase.database.ValueEventListener
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/**
 * BUZÓN DE COMANDOS  (22/9/2026)
 *
 * ─────────────────────────────────────────────────────────────────────────────
 * POR QUÉ EXISTE
 * ─────────────────────────────────────────────────────────────────────────────
 * Pedido del dueño para ir a producción: *"que el panel web siempre mande y funcione no
 * importa cómo esté el celular"*. Hasta hoy la ÚNICA vía de un comando era FCM, y FCM
 * pierde mensajes en casos que en una flota real pasan todas las semanas:
 *
 *  • El equipo estuvo apagado o sin red más de 24 h: el mensaje llega, pero
 *    `timestampOutOfWindow()` lo rechaza (y hace bien: FCM no es un buzón confiable).
 *  • FCM entrega fuera de orden: el piso monotónico rechaza el más viejo.
 *  • Más de 100 mensajes en cola para un equipo: FCM los descarta todos y avisa con
 *    `onDeletedMessages()`, que no estaba implementado.
 *  • Token viejo, Play Services restringido o desactualizado (típico en un equipo
 *    kosher): el mensaje directamente no llega.
 *
 * En todos esos casos el comando se perdía EN SILENCIO y el panel quedaba mostrando un
 * estado que el equipo no tenía.
 *
 * ─────────────────────────────────────────────────────────────────────────────
 * CÓMO FUNCIONA
 * ─────────────────────────────────────────────────────────────────────────────
 * La Cloud Function, además de mandar el FCM, deja una copia del comando FIRMADO en
 * `devices/<id>/pendingCommands/<commandId>` = `{ data: {…el mismo payload del FCM…},
 * queuedAt, expiresAt, family }`. El equipo la drena:
 *
 *  • en cada vuelta del `WatchdogWorker` (15 min, sobrevive a que muera el proceso);
 *  • al arrancar el proceso;
 *  • cuando FCM avisa que descartó mensajes (`onDeletedMessages`);
 *  • cuando un FCM llega fuera de ventana (el legítimo está en el buzón).
 *
 * Cada entrada pasa por [CommandProcessor] con la MISMA verificación de firma HMAC que
 * un FCM, y con el MISMO registro de `commandId` ya procesados (ahora de 35 días), así
 * que un comando que llegó por las dos vías se aplica UNA sola vez. Lo que NO se exige
 * al buzón es la ventana de 24 h ni el piso monotónico: un comando del buzón es viejo
 * por definición, y su protección contra repetición es que la entrada se borra al
 * aplicarse y que el id queda registrado.
 *
 * La Function reemplaza la entrada vieja de la MISMA política cuando llega una nueva
 * (ver `commandFamily()` en functions/index.js): si el administrador bloqueó y después
 * desbloqueó el Wi-Fi con el equipo apagado, en el buzón queda solo "desbloquear". Por
 * eso el buzón nunca crece más que la cantidad de políticas distintas.
 *
 * ⚠️ Un intruso NO puede meter comandos acá: las reglas nuevas de la base (22/9) solo
 * dejan escribir el nodo del equipo al propio equipo y a los administradores, y aunque
 * pudiera, sin el secreto del equipo la firma no verifica.
 *
 * ─────────────────────────────────────────────────────────────────────────────
 * 26/10/2026 (B.95) — LOS EQUIPOS SIN FCM ESCUCHAN SU BUZÓN EN VIVO
 * ─────────────────────────────────────────────────────────────────────────────
 * Hasta hoy el buzón era la RED DE SEGURIDAD de FCM. Pero hay equipos que no tienen FCM
 * en absoluto (sin Play Services, o con el registro roto: el panel los marca «Sin FCM»),
 * y para ellos el buzón era la ÚNICA vía y se revisaba cada 15 minutos. Desde el panel
 * eso se veía como "le mando y no pasa nada": cada cambio tardaba hasta un cuarto de hora
 * y el interruptor volvía solo atrás mientras tanto.
 *
 * Ahora, si el equipo NO tiene FCM, el servicio de primer plano mantiene un listener en
 * vivo sobre `devices/<id>/pendingCommands` ([ensureLiveChannel]): apenas la Cloud
 * Function deja un comando, la base se lo empuja al equipo y se drena en el acto. Es lo
 * mismo que hace FCM, pero por la conexión de Realtime Database.
 *
 * Por qué SOLO sin FCM: un listener permanente mantiene abierta la conexión con la base
 * (un latido cada ~45 s mientras haya red). Con FCM eso es batería tirada —Google ya
 * despierta al equipo cuando hay un comando—, y este proyecto ya pagó varias sesiones
 * sacando gastos así (B.30, B.80, B.89). Sin FCM es el precio de que el panel funcione.
 * En Doze la conexión se corta igual; se reconecta en la ventana de mantenimiento y la
 * base le manda lo que haya, que es lo mismo que haría el Worker.
 *
 * Y para que esto se pueda DIAGNOSTICAR desde el panel (la lección de B.41 y B.69), el
 * equipo publica `mailboxLive` (si está escuchando, y si no por qué) y
 * `mailboxLastCheckAt` / `mailboxLastResult` (cuándo revisó el buzón por última vez y
 * qué encontró). Antes no había forma de saber desde el panel si un equipo levantaba su
 * buzón o no.
 */
object CommandMailbox {

    private const val TAG = "CommandMailbox"

    /** Nunca dos drenados a la vez (el Worker y un FCM pueden coincidir). */
    private val draining = AtomicBoolean(false)

    /**
     * Un pedido de drenado que llegó MIENTRAS otro corría (26/10/2026). Antes se
     * descartaba sin más: si un comando entraba al buzón justo mientras se drenaba —con
     * el listener en vivo pasa seguido, porque el propio drenado borra entradas y eso
     * dispara otro aviso—, ese comando quedaba esperando a la vuelta del Worker, hasta
     * 15 min. Ahora el drenado en curso, al terminar, vuelve a mirar una vez.
     */
    private val redrainRequested = AtomicBoolean(false)

    /** 1/1/2024: por debajo de esto el reloj del equipo no es creíble (ver CommandProcessor). */
    private const val CREDIBLE_CLOCK_FLOOR_MS = 1_704_067_200_000L

    /** Entrada leída del buzón. Función pura de ordenamiento/vencimiento: ver [plan]. */
    data class Entry(
        val key: String,
        val data: Map<String, String>,
        val expiresAt: Long,
        val queuedAt: Long
    ) {
        val commandId: String get() = data["commandId"] ?: key
        val timestamp: Long get() = data["timestamp"]?.toLongOrNull() ?: queuedAt
    }

    /**
     * Decide qué hacer con cada entrada: devuelve (a aplicar en orden, a descartar).
     * Pura a propósito: sin Android ni Firebase, así se puede ejercitar en un test.
     *
     * - Vencidas → descartar (solo si el reloj del equipo es creíble: con el reloj roto
     *   no se puede saber, y es preferible aplicar a perder un comando).
     * - Ya procesadas (llegaron antes por FCM) → descartar.
     * - El resto → aplicar en el ORDEN en que el administrador las mandó (timestamp del
     *   servidor), que es lo que hace que "bloquear y después desbloquear" termine
     *   desbloqueado aunque la Function no hubiera podido reemplazar la vieja.
     */
    fun plan(
        entries: List<Entry>,
        nowMs: Long,
        alreadyProcessed: (String) -> Boolean
    ): Pair<List<Entry>, List<Entry>> {
        val clockCredible = nowMs > CREDIBLE_CLOCK_FLOOR_MS
        val aplicar = mutableListOf<Entry>()
        val descartar = mutableListOf<Entry>()
        for (e in entries) {
            val vencida = clockCredible && e.expiresAt > 0L && nowMs > e.expiresAt
            val incompleta = e.data["command"].isNullOrBlank() || e.data["signature"].isNullOrBlank()
            if (vencida || incompleta || alreadyProcessed(e.commandId)) {
                descartar += e
            } else {
                aplicar += e
            }
        }
        aplicar.sortWith(compareBy<Entry> { it.timestamp }.thenBy { it.key })
        return aplicar to descartar
    }

    /** Drena el buzón en un hilo propio. Idempotente y barato si está vacío (una lectura). */
    fun drainAsync(context: Context, reason: String) {
        val ctx = context.applicationContext
        Thread({ drainNow(ctx, reason) }, "LockSuiteMailbox").start()
    }

    /**
     * Drena el buzón en el hilo que llama. NO llamar desde el hilo principal: espera la
     * lectura de Firebase (con tope).
     */
    fun drainNow(context: Context, reason: String) {
        if (!draining.compareAndSet(false, true)) {
            redrainRequested.set(true)
            return
        }
        val ctx = context.applicationContext
        // Qué se le informa al panel de esta revisión (null = no informar nada).
        var resultado: String? = null
        try {
            val dpm = ctx.getSystemService(Context.DEVICE_POLICY_SERVICE) as? android.app.admin.DevicePolicyManager
            if (dpm == null || !dpm.isDeviceOwnerApp(ctx.packageName)) return
            val id = FirebaseDeviceSync.deviceId(ctx)
            val ref = FirebaseDatabase.getInstance().getReference("devices/$id/pendingCommands")
            val latch = CountDownLatch(1)
            var snapshot: DataSnapshot? = null
            var errorLectura: String? = null
            FirebaseDeviceSync.runAuthenticated {
                ref.get()
                    .addOnSuccessListener { snapshot = it; latch.countDown() }
                    .addOnFailureListener { e ->
                        errorLectura = e.message ?: e.javaClass.simpleName
                        android.util.Log.w(TAG, "No se pudo leer el buzón ($reason): ${e.message}")
                        latch.countDown()
                    }
            }
            latch.await(30, TimeUnit.SECONDS)
            val snap = snapshot
            if (snap == null) {
                resultado = "error al leer: " + (errorLectura ?: "la base no contestó en 30 s")
                return
            }
            if (!snap.exists() || !snap.hasChildren()) {
                resultado = RESULTADO_VACIO
                return
            }

            val entries = snap.children.mapNotNull { child ->
                val key = child.key ?: return@mapNotNull null
                val data = mutableMapOf<String, String>()
                child.child("data").children.forEach { f ->
                    val k = f.key ?: return@forEach
                    val v = f.value ?: return@forEach
                    data[k] = v.toString()
                }
                Entry(
                    key = key,
                    data = data,
                    expiresAt = (child.child("expiresAt").value as? Number)?.toLong() ?: 0L,
                    queuedAt = (child.child("queuedAt").value as? Number)?.toLong() ?: 0L
                )
            }
            val (aplicar, descartar) = plan(entries, System.currentTimeMillis()) { cid ->
                CommandProcessor.alreadyProcessed(ctx, cid)
            }
            for (e in descartar) removeEntry(ctx, e.key)
            if (aplicar.isEmpty()) {
                resultado = RESULTADO_VACIO
                return
            }

            android.util.Log.i(TAG, "Buzón ($reason): aplicando ${aplicar.size} comando(s) pendientes")
            val processor = CommandProcessor(ctx)
            var conError = 0
            for (e in aplicar) {
                try {
                    processor.process(e.data, CommandProcessor.Source.MAILBOX)
                } catch (ex: Exception) {
                    conError++
                    android.util.Log.e(TAG, "Comando del buzón falló: ${e.data["command"]}: ${ex.message}", ex)
                } finally {
                    // Se borra SIEMPRE: si falló, el ack ya dice por qué, y dejarlo haría
                    // que se reintente cada 15 minutos para siempre.
                    removeEntry(ctx, e.key)
                }
            }
            resultado = "aplicó ${aplicar.size} (${aplicar.joinToString(", ") { it.data["command"] ?: "?" }.take(120)})" +
                (if (conError > 0) " — $conError con error" else "") + " · vía $reason"
        } catch (e: Exception) {
            resultado = "error: ${e.message ?: e.javaClass.simpleName}"
            android.util.Log.e(TAG, "Drenado del buzón falló ($reason): ${e.message}", e)
        } finally {
            draining.set(false)
            resultado?.let { reportCheck(ctx, it) }
            // Algo pidió drenar mientras este corría: se mira una vez más. No hay rulo:
            // si no queda nada, la vuelta siguiente no borra nada ni dispara avisos.
            if (redrainRequested.getAndSet(false)) drainAsync(ctx, "re-drenado")
        }
    }

    // ─────────────────────────────────────────────────────────────────────────
    // ESCUCHA EN VIVO (solo equipos sin FCM) — ver el encabezado, 26/10/2026.
    // ─────────────────────────────────────────────────────────────────────────

    private const val RESULTADO_VACIO = "sin pendientes"

    /**
     * Después de un error (permiso, la base cerró la consulta), cuánto esperar para
     * reintentar: 1 min, y se duplica en cada error seguido hasta 15 min. El caso típico
     * es un equipo reinstalado esperando que lo re-vinculen (PERMISSION_DENIED hasta que
     * un administrador apruebe): reintentar cada minuto durante horas sería red tirada.
     */
    private const val LIVE_RETRY_AFTER_ERROR_MS = 60_000L
    private const val LIVE_RETRY_MAX_MS = 15 * 60_000L

    private val liveLock = Any()
    @Volatile private var liveRef: DatabaseReference? = null
    @Volatile private var liveListener: ValueEventListener? = null
    @Volatile private var liveRetryAtMs = 0L
    @Volatile private var liveBackoffMs = LIVE_RETRY_AFTER_ERROR_MS

    /** ¿Hay un listener en vivo puesto en este proceso? */
    val isLive: Boolean get() = liveListener != null

    /**
     * ¿Este equipo tiene que escuchar su buzón en vivo? Solo si se SABE que no tiene FCM.
     * Mientras no se sabe (la primera sincronización lo averigua en segundos) no se
     * arranca nada: arrancar "por las dudas" en cada equipo sería el gasto que el
     * encabezado explica que no hay que pagar.
     */
    fun shouldListenLive(context: Context): Boolean {
        val prefs = PrefsHelper.getMdmPrefs(context)
        if (prefs.getBoolean("locksuite_purged", false)) return false
        return FirebaseDeviceSync.fcmAvailable(context) == false
    }

    /**
     * Pone o saca el listener en vivo según haga falta. Barata a propósito: la llama el
     * ciclo de 20 s del servicio de primer plano, y en el caso normal son dos lecturas
     * de preferencias y una comparación.
     */
    fun ensureLiveChannel(context: Context) {
        val ctx = context.applicationContext
        if (shouldListenLive(ctx)) {
            attachLive(ctx)
        } else if (liveListener != null) {
            stopLiveChannel(ctx, if (FirebaseDeviceSync.fcmAvailable(ctx) == true) "tiene FCM" else "no hace falta")
        }
    }

    private fun attachLive(ctx: Context) {
        synchronized(liveLock) {
            if (liveListener != null) return
            val ahora = SystemClock.elapsedRealtime()
            if (ahora < liveRetryAtMs) return
            liveRetryAtMs = ahora + LIVE_RETRY_AFTER_ERROR_MS
            val dpm = ctx.getSystemService(Context.DEVICE_POLICY_SERVICE) as? android.app.admin.DevicePolicyManager
            if (dpm == null || !dpm.isDeviceOwnerApp(ctx.packageName)) return
            val ref = FirebaseDatabase.getInstance()
                .getReference("devices/${FirebaseDeviceSync.deviceId(ctx)}/pendingCommands")
            val listener = object : ValueEventListener {
                override fun onDataChange(snapshot: DataSnapshot) {
                    // Llega al poner el listener (con lo que ya hubiera) y con cada cambio.
                    // Los borrados del propio drenado también avisan: si no queda nada,
                    // no se hace nada; si queda, drainNow() ya sabe juntarlos.
                    liveBackoffMs = LIVE_RETRY_AFTER_ERROR_MS
                    if (snapshot.hasChildren()) drainAsync(ctx, "en vivo")
                }

                override fun onCancelled(error: DatabaseError) {
                    // Casi siempre es PERMISSION_DENIED: el equipo cambió de uid anónimo
                    // (reinstalación) y espera que un administrador lo re-vincule
                    // (FirebaseDeviceSync.requestRebind). Se reintenta con espera creciente.
                    android.util.Log.w(TAG, "Escucha en vivo del buzón cancelada: ${error.message}")
                    synchronized(liveLock) {
                        if (liveListener === this) {
                            liveListener = null
                            liveRef = null
                        }
                    }
                    liveRetryAtMs = SystemClock.elapsedRealtime() + liveBackoffMs
                    liveBackoffMs = (liveBackoffMs * 2).coerceAtMost(LIVE_RETRY_MAX_MS)
                    reportLive(ctx, false, "la base cortó la escucha: ${error.message}")
                }
            }
            liveListener = listener
            liveRef = ref
            FirebaseDeviceSync.runAuthenticated {
                val sigue = synchronized(liveLock) { liveListener === listener }
                if (sigue) {
                    ref.addValueEventListener(listener)
                    android.util.Log.i(TAG, "Buzón: escuchando en vivo (el equipo no tiene FCM).")
                    reportLive(ctx, true, "sin FCM: escucha en vivo")
                }
            }
        }
    }

    /** Saca el listener en vivo, si había. */
    fun stopLiveChannel(context: Context?, motivo: String) {
        val (ref, listener) = synchronized(liveLock) {
            val par = liveRef to liveListener
            liveRef = null
            liveListener = null
            par
        }
        if (ref != null && listener != null) {
            try {
                ref.removeEventListener(listener)
            } catch (e: Exception) {
                android.util.Log.w(TAG, "No se pudo sacar la escucha en vivo: ${e.message}")
            }
            if (context != null) reportLive(context.applicationContext, false, motivo)
        }
    }

    // ── Lo que se le cuenta al panel ─────────────────────────────────────────

    @Volatile private var ultimoInformado: String? = null
    @Volatile private var ultimoInformadoAt = 0L
    @Volatile private var ultimoVivoInformado: Boolean? = null

    /**
     * Publica cuándo se revisó el buzón y qué se encontró. Un "sin pendientes" igual al
     * anterior se publica como mucho cada 14 min (una vez por vuelta del Worker), y no
     * pisa durante 10 min el aviso de lo último que se aplicó: si no, el panel nunca
     * llegaría a ver "aplicó 2", porque el drenado siguiente lo borraría al instante.
     */
    private fun reportCheck(ctx: Context, resultado: String) {
        val ahora = SystemClock.elapsedRealtime()
        val previo = ultimoInformado
        val desde = ahora - ultimoInformadoAt
        if (resultado == RESULTADO_VACIO && previo != null) {
            if (previo == RESULTADO_VACIO && desde < 14 * 60_000L) return
            if (previo.startsWith("aplicó") && desde < 10 * 60_000L) return
        }
        ultimoInformado = resultado
        ultimoInformadoAt = ahora
        FirebaseDeviceSync.reportFields(
            ctx,
            mapOf(
                "mailboxLastCheckAt" to com.google.firebase.database.ServerValue.TIMESTAMP,
                "mailboxLastResult" to resultado.take(200)
            )
        )
    }

    private fun reportLive(ctx: Context, vivo: Boolean, motivo: String) {
        if (ultimoVivoInformado == vivo) return
        ultimoVivoInformado = vivo
        FirebaseDeviceSync.reportFields(
            ctx,
            mapOf("mailboxLive" to vivo, "mailboxLiveReason" to motivo.take(160))
        )
    }

    /** Borra una entrada del buzón. Best effort: si falla, el próximo drenado la ve ya procesada. */
    fun removeEntry(context: Context, commandId: String) {
        if (commandId.isBlank() || commandId.contains('/') || commandId.contains('.')) return
        try {
            val id = FirebaseDeviceSync.deviceId(context.applicationContext)
            FirebaseDeviceSync.runAuthenticated {
                FirebaseDatabase.getInstance()
                    .getReference("devices/$id/pendingCommands/$commandId")
                    .removeValue()
                    .addOnFailureListener { e ->
                        android.util.Log.w(TAG, "No se pudo borrar $commandId del buzón: ${e.message}")
                    }
            }
        } catch (e: Exception) {
            android.util.Log.w(TAG, "removeEntry: ${e.message}")
        }
    }
}
