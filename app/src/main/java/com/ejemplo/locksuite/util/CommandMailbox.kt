package com.ejemplo.locksuite.util

import android.content.Context
import com.ejemplo.locksuite.service.CommandProcessor
import com.google.firebase.database.DataSnapshot
import com.google.firebase.database.FirebaseDatabase
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
 */
object CommandMailbox {

    private const val TAG = "CommandMailbox"

    /** Nunca dos drenados a la vez (el Worker y un FCM pueden coincidir). */
    private val draining = AtomicBoolean(false)

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
        if (!draining.compareAndSet(false, true)) return
        try {
            val ctx = context.applicationContext
            val id = FirebaseDeviceSync.deviceId(ctx)
            val ref = FirebaseDatabase.getInstance().getReference("devices/$id/pendingCommands")
            val latch = CountDownLatch(1)
            var snapshot: DataSnapshot? = null
            FirebaseDeviceSync.runAuthenticated {
                ref.get()
                    .addOnSuccessListener { snapshot = it; latch.countDown() }
                    .addOnFailureListener { e ->
                        android.util.Log.w(TAG, "No se pudo leer el buzón ($reason): ${e.message}")
                        latch.countDown()
                    }
            }
            latch.await(30, TimeUnit.SECONDS)
            val snap = snapshot ?: return
            if (!snap.exists() || !snap.hasChildren()) return

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
            if (aplicar.isEmpty()) return

            android.util.Log.i(TAG, "Buzón ($reason): aplicando ${aplicar.size} comando(s) pendientes")
            val processor = CommandProcessor(ctx)
            for (e in aplicar) {
                try {
                    processor.process(e.data, CommandProcessor.Source.MAILBOX)
                } catch (ex: Exception) {
                    android.util.Log.e(TAG, "Comando del buzón falló: ${e.data["command"]}: ${ex.message}", ex)
                } finally {
                    // Se borra SIEMPRE: si falló, el ack ya dice por qué, y dejarlo haría
                    // que se reintente cada 15 minutos para siempre.
                    removeEntry(ctx, e.key)
                }
            }
        } catch (e: Exception) {
            android.util.Log.e(TAG, "Drenado del buzón falló ($reason): ${e.message}", e)
        } finally {
            draining.set(false)
        }
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
