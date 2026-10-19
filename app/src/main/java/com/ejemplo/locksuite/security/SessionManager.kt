package com.ejemplo.locksuite.security

import android.os.SystemClock

object SessionManager {
    // @Volatile: este campo se escribe desde el hilo principal (DashboardActivity,
    // LoginActivity) y también desde el hilo en el que corre
    // LockSuiteFirebaseService (p.ej. al recibir un cambio remoto de PIN, que
    // llama a closeSession() para forzar el re-login). Sin @Volatile, una
    // escritura en un hilo no tiene garantía de visibilidad inmediata para una
    // lectura en otro — para un mecanismo de sesión/autenticación es mejor no
    // depender de que la práctica coincida con la teoría.
    // Se usa SystemClock.elapsedRealtime() (monotónico) en lugar de currentTimeMillis()
    // para que cambios manuales o automáticos (NITZ/NTP) del reloj de pared no puedan
    // extender indefinidamente ni cortar prematuramente la sesión de administrador.
    @Volatile
    private var lastAuthAt: Long = 0L
    private const val TIMEOUT_MS = 5 * 60 * 1000L // 5 minutos de inactividad

    fun openSession() {
        lastAuthAt = SystemClock.elapsedRealtime()
    }

    fun closeSession() {
        lastAuthAt = 0L
    }

    fun updateInteraction() {
        if (isActive()) {
            lastAuthAt = SystemClock.elapsedRealtime()
        }
    }

    fun isActive(): Boolean {
        val authAt = lastAuthAt
        if (authAt == 0L) return false
        val elapsed = SystemClock.elapsedRealtime() - authAt
        return elapsed in 0 until TIMEOUT_MS
    }
}

