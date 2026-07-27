package com.ejemplo.locksuite.dns

import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow

enum class DnsAction { ALLOWED, BLOCKED }

data class DnsLogEntry(
    val domain: String,
    val packageName: String,
    val timestampMillis: Long,
    val action: DnsAction
)

data class DomainActivitySummary(
    val domain: String,
    val firstSeenMillis: Long,
    val lastSeenMillis: Long,
    val hitCount: Int,
    val lastAction: DnsAction,
    val explicitRule: RuleType?,     // regla puesta a mano en ESTE dominio exacto
    val effectiveRule: RuleType?     // regla vigente (puede venir heredada del padre)
)

class DnsActivityBuffer(
    private val maxEntries: Int = 2000,
    private val maxAgeMillis: Long = 60 * 60 * 1000L // 1 hora tope
) {
    private val lock = Any()
    private val entries = ArrayDeque<DnsLogEntry>()

    private val _events = MutableSharedFlow<Unit>(
        replay = 0,
        extraBufferCapacity = 1,
        onBufferOverflow = BufferOverflow.DROP_OLDEST
    )
    val events: SharedFlow<Unit> = _events.asSharedFlow()

    /** Llamar desde el hilo de paquetes DNS. No bloquea. No escribe a disco. */
    fun record(domain: String, packageName: String, action: DnsAction) {
        val entry = DnsLogEntry(domain, packageName, System.currentTimeMillis(), action)
        synchronized(lock) {
            entries.addLast(entry)
            trim()
        }
        _events.tryEmit(Unit)
    }

    private fun trim() {
        val cutoff = System.currentTimeMillis() - maxAgeMillis
        while (entries.isNotEmpty() && entries.first().timestampMillis < cutoff)
            entries.removeFirst()
        while (entries.size > maxEntries)
            entries.removeFirst()
    }

    /** Copia inmutable dentro de la ventana pedida. Segura para leer desde la UI. */
    fun snapshot(windowMillis: Long): List<DnsLogEntry> {
        val cutoff = System.currentTimeMillis() - windowMillis
        return synchronized(lock) {
            entries.filter { it.timestampMillis >= cutoff }
        }
    }
}
