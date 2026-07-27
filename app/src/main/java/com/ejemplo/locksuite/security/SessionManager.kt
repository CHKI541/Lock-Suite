package com.ejemplo.locksuite.security

object SessionManager {
    // @Volatile: este campo se escribe desde el hilo principal (DashboardActivity,
    // LoginActivity) y también desde el hilo en el que corre
    // LockSuiteFirebaseService (p.ej. al recibir un cambio remoto de PIN, que
    // llama a closeSession() para forzar el re-login). Sin @Volatile, una
    // escritura en un hilo no tiene garantía de visibilidad inmediata para una
    // lectura en otro — para un mecanismo de sesión/autenticación es mejor no
    // depender de que la práctica coincida con la teoría.
    @Volatile
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
