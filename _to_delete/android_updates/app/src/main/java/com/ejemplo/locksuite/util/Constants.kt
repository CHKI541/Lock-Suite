package com.ejemplo.locksuite.util

object Constants {
    // Preferencias
    const val PREFS_MDM = "locksuite_mdm_prefs"
    const val PREFS_SECURE = "locksuite_secure_prefs"

    // PIN de administrador (único PIN del sistema; ver CHANGELOG sobre por qué
    // se eliminó el "PIN maestro" separado que estaba hardcodeado en el código)
    const val KEY_PIN_HASH = "admin_pin_hash"
    const val KEY_PIN_SALT = "admin_pin_salt"
    const val LOCKOUT_COUNT_KEY = "lockout_count"
    const val LOCKOUT_TIME_KEY = "lockout_time"
    const val MAX_ATTEMPTS = 5
    const val LOCKOUT_DURATION_MS = 5 * 60 * 1000L // 5 minutos

    // Modo Kiosco / lista blanca de apps permitidas
    const val KIOSK_MODE_KEY = "kiosk_mode_enabled"
    const val KEY_ALLOWED_PACKAGES = "kiosk_allowed_packages"

    // Consentimiento inicial
    const val KEY_CONSENT_ACCEPTED = "setup_consent_accepted"
}
