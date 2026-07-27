package com.ejemplo.locksuite.util

object Constants {
    // AUDITORÍA DE SEGURIDAD (ver informe adjunto): acá vivía un hash+salt
    // "maestro" FIJO, idéntico en cada instalación de LockSuite, ofuscado con
    // un XOR de un solo byte. Cualquiera que decompilara la APK (jadx/apktool)
    // podía recuperarlo en segundos y quedaba con la llave de purga de
    // emergencia de TODOS los celulares kosher corriendo esta build, para
    // siempre. Se reemplazó por un código de recuperación aleatorio POR
    // DISPOSITIVO (mismo esquema hash+salt que ya usaba el PIN de admin) —
    // ver PinManager.getOrCreateRecoveryCode() / verifyMasterPassword().

    const val PREFS_NAME = "locksuite_secure_prefs"
    const val KEY_PIN_HASH = "pin_hash"
    const val KEY_PIN_SALT = "pin_salt"
    const val KEY_RECOVERY_HASH = "recovery_hash"
    const val KEY_RECOVERY_SALT = "recovery_salt"
    const val KEY_RECOVERY_PLAINTEXT_CACHE = "recovery_plaintext_cache"
    const val LOCKOUT_COUNT_KEY = "lockout_count"
    const val LOCKOUT_TIME_KEY = "lockout_timestamp"
    // Marca monotónica (SystemClock.elapsedRealtime) del momento del bloqueo, además
    // del reloj de pared. Sin esto, el bloqueo de 5 minutos tras 5 PINs fallidos se
    // saltaba simplemente adelantando la hora del celular en Ajustes — ver
    // PinManager.getLockoutState().
    const val LOCKOUT_ELAPSED_KEY = "lockout_elapsed_realtime"
    const val MAX_ATTEMPTS = 5
    const val LOCKOUT_DURATION_MS = 5 * 60 * 1000L // 5 minutos
    
    const val MDM_STATE_PREFS = "mdm_state"
    const val KIOSK_MODE_KEY = "kiosk_mode"
    
    fun getDefaultFrpAccounts(): List<String> {
        return listOf("117658682816902650896", "104618586569590320127")
    }

}
