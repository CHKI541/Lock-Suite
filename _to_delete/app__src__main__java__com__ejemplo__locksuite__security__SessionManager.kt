package com.ejemplo.locksuite.security

object SessionManager {
    private var lastAuthAt: Long = 0L
    private const val TIMEOUT_MS = 5 * 60 * 1000L // 5 minutos de inactividad

    fun openSession() {
        lastAuthAt = System.currentTimeMillis()
    }

    fun closeSession() {
        lastAuthAt = 0L
    }

    fun updateInteraction() {
        if (isActive()) {
            lastAuthAt = System.currentTimeMillis()
        }
    }

    fun isActive(): Boolean {
        return lastAuthAt != 0L && (System.currentTimeMillis() - lastAuthAt) < TIMEOUT_MS
    }
}
