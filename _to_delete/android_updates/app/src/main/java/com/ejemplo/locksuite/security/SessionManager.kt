package com.ejemplo.locksuite.security

/**
 * Sesión de administrador en memoria (vive mientras el proceso esté vivo;
 * tras matar el proceso hace falta reautenticar con el PIN).
 */
object SessionManager {
    private var sessionStartedAt: Long = 0L
    private const val SESSION_TIMEOUT_MS = 10 * 60 * 1000L // 10 minutos de inactividad

    fun startSession() {
        sessionStartedAt = System.currentTimeMillis()
    }

    fun isActive(): Boolean {
        if (sessionStartedAt == 0L) return false
        return System.currentTimeMillis() - sessionStartedAt < SESSION_TIMEOUT_MS
    }

    fun touch() {
        if (sessionStartedAt != 0L) {
            sessionStartedAt = System.currentTimeMillis()
        }
    }

    fun endSession() {
        sessionStartedAt = 0L
    }
}
