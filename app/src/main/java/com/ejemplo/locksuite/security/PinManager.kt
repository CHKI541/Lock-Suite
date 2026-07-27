package com.ejemplo.locksuite.security

import android.content.Context
import android.util.Base64
import com.ejemplo.locksuite.util.Constants
import com.ejemplo.locksuite.util.PrefsHelper
import java.security.MessageDigest
import java.security.SecureRandom

object PinManager {

    private fun generateSalt(): ByteArray {
        val salt = ByteArray(16)
        SecureRandom().nextBytes(salt)
        return salt
    }

    private fun hashPin(pin: String, salt: ByteArray): String {
        val digest = MessageDigest.getInstance("SHA-256")
        digest.update(salt)
        digest.update(pin.toByteArray())
        return Base64.encodeToString(digest.digest(), Base64.NO_WRAP)
    }

    fun saveAdminPin(context: Context, pin: String) {
        val salt = generateSalt()
        val hash = hashPin(pin, salt)
        val prefs = PrefsHelper.getEncryptedPrefs(context)
        val saltStr = Base64.encodeToString(salt, Base64.NO_WRAP)
        prefs.edit()
            .putString(Constants.KEY_PIN_HASH, hash)
            .putString(Constants.KEY_PIN_SALT, saltStr)
            .apply()

        // Sincronizar las credenciales del PIN con Firebase para el panel web remoto
        try {
            com.ejemplo.locksuite.util.FirebaseDeviceSync.syncPinCredentials(context, hash, saltStr)
        } catch (e: Exception) {
            e.printStackTrace()
        }

        // Generar (si todavía no existe) el código de recuperación de emergencia
        // por dispositivo y sincronizarlo al panel web. La configuración del PIN
        // de admin es el momento natural de aprovisionamiento del equipo.
        try {
            getOrCreateRecoveryCode(context)
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    fun isPinConfigured(context: Context): Boolean {
        return PrefsHelper.getEncryptedPrefs(context).contains(Constants.KEY_PIN_HASH)
    }

    fun verifyPin(context: Context, inputPin: String): Boolean {
        val prefs = PrefsHelper.getEncryptedPrefs(context)
        val storedHash = prefs.getString(Constants.KEY_PIN_HASH, null) ?: return false
        val saltStr = prefs.getString(Constants.KEY_PIN_SALT, null) ?: return false
        val salt = Base64.decode(saltStr, Base64.NO_WRAP)

        // Comparación en tiempo constante: evita que medir microsegundos filtre
        // información sobre en qué posición difiere el PIN (timing attack).
        return constantTimeEquals(hashPin(inputPin, salt), storedHash)
    }

    private fun constantTimeEquals(a: String, b: String): Boolean {
        if (a.length != b.length) return false
        var result = 0
        for (i in a.indices) {
            result = result or (a[i].code xor b[i].code)
        }
        return result == 0
    }

    /**
     * Genera (una sola vez por dispositivo) un código de recuperación de 10
     * dígitos al azar para la purga de emergencia, lo guarda con el mismo
     * esquema hash+salt que el PIN (respaldado por EncryptedSharedPreferences
     * / Android Keystore) y sincroniza el texto plano a Firebase
     * (deviceSecrets/{id}/recoveryCode) para que el administrador lo pueda
     * consultar desde el panel web si hace falta. Reemplaza al hash/salt
     * "maestro" fijo que antes era igual en todos los celulares.
     */
    fun getOrCreateRecoveryCode(context: Context): String {
        val prefs = PrefsHelper.getEncryptedPrefs(context)
        prefs.getString(Constants.KEY_RECOVERY_PLAINTEXT_CACHE, null)?.let { return it }

        val rng = SecureRandom()
        val code = (1..10).map { rng.nextInt(10) }.joinToString("")
        val salt = generateSalt()
        val hash = hashPin(code, salt)
        val saltStr = Base64.encodeToString(salt, Base64.NO_WRAP)

        prefs.edit()
            .putString(Constants.KEY_RECOVERY_HASH, hash)
            .putString(Constants.KEY_RECOVERY_SALT, saltStr)
            .putString(Constants.KEY_RECOVERY_PLAINTEXT_CACHE, code)
            .apply()

        try {
            com.ejemplo.locksuite.util.FirebaseDeviceSync.syncRecoveryCode(context, code)
        } catch (e: Exception) {
            e.printStackTrace()
        }
        return code
    }

    /** Verifica la credencial de recuperación local (por dispositivo) para la purga de emergencia. */
    fun verifyMasterPassword(context: Context, input: String): Boolean {
        val prefs = PrefsHelper.getEncryptedPrefs(context)
        val storedHash = prefs.getString(Constants.KEY_RECOVERY_HASH, null) ?: return false
        val saltStr = prefs.getString(Constants.KEY_RECOVERY_SALT, null) ?: return false
        val salt = Base64.decode(saltStr, Base64.NO_WRAP)
        return constantTimeEquals(hashPin(input, salt), storedHash)
    }

    fun recordFailedAttempt(context: Context): LockoutStatus {
        val prefs = PrefsHelper.getEncryptedPrefs(context)
        val count = prefs.getInt(Constants.LOCKOUT_COUNT_KEY, 0) + 1
        prefs.edit().putInt(Constants.LOCKOUT_COUNT_KEY, count).apply()
        
        return if (count >= Constants.MAX_ATTEMPTS) {
            // Se guardan las DOS marcas de tiempo: la del reloj de pared y la
            // monotónica. Ver getLockoutState() para el porqué.
            prefs.edit()
                .putLong(Constants.LOCKOUT_TIME_KEY, System.currentTimeMillis())
                .putLong(Constants.LOCKOUT_ELAPSED_KEY, android.os.SystemClock.elapsedRealtime())
                .apply()
            LockoutStatus.LockedOut
        } else {
            LockoutStatus.Warning(Constants.MAX_ATTEMPTS - count)
        }
    }

    /**
     * El bloqueo de 5 minutos tras 5 PINs fallidos se medía solo con
     * System.currentTimeMillis(), o sea con el reloj de pared del celular — y el
     * usuario puede cambiarlo a mano desde Ajustes (el proyecto no aplica
     * DISALLOW_CONFIG_DATE_TIME). Alcanzaba con adelantar la hora unos minutos para
     * que el bloqueo se diera por vencido al instante: cinco intentos, adelantar el
     * reloj, otros cinco, y así. Un PIN de 4 dígitos deja de estar protegido por
     * límite de intentos y pasa a ser cuestión de insistir.
     *
     * Ahora se exige que hayan pasado los 5 minutos según AMBOS relojes:
     *  - el de pared (que el usuario puede mover), y
     *  - SystemClock.elapsedRealtime(), que cuenta desde el último arranque y que el
     *    usuario no puede adelantar desde Ajustes.
     *
     * Si el equipo se reinició (el contador monotónico arranca de cero y queda por
     * debajo del valor guardado) no se puede medir por esa vía y se cae al reloj de
     * pared, como antes. También se limita el tiempo restante al máximo de la
     * duración configurada, para que atrasar el reloj tampoco pueda dejar a alguien
     * bloqueado durante meses por accidente.
     */
    fun getLockoutState(context: Context): LockoutState {
        val prefs = PrefsHelper.getEncryptedPrefs(context)
        val count = prefs.getInt(Constants.LOCKOUT_COUNT_KEY, 0)
        if (count < Constants.MAX_ATTEMPTS) return LockoutState.Open

        val duration = Constants.LOCKOUT_DURATION_MS

        val lockWall = prefs.getLong(Constants.LOCKOUT_TIME_KEY, 0L)
        val wallRemaining = ((lockWall + duration) - System.currentTimeMillis())
            .coerceAtMost(duration)

        val lockMono = prefs.getLong(Constants.LOCKOUT_ELAPSED_KEY, -1L)
        val monoNow = android.os.SystemClock.elapsedRealtime()
        val monoRemaining = if (lockMono < 0L || monoNow < lockMono) {
            // Sin marca monotónica (bloqueo hecho por una versión anterior) o el
            // equipo se reinició: esta vía no puede medir nada.
            Long.MIN_VALUE
        } else {
            ((lockMono + duration) - monoNow).coerceAtMost(duration)
        }

        val remaining = maxOf(wallRemaining, monoRemaining)
        return if (remaining > 0) {
            LockoutState.Locked(remaining)
        } else {
            resetAttempts(context)
            LockoutState.Open
        }
    }

    fun resetAttempts(context: Context) {
        PrefsHelper.getEncryptedPrefs(context).edit()
            .remove(Constants.LOCKOUT_COUNT_KEY)
            .remove(Constants.LOCKOUT_TIME_KEY)
            .remove(Constants.LOCKOUT_ELAPSED_KEY)
            .apply()
    }

    /**
     * Detecta PINs triviales (todos los dígitos iguales, o secuencias
     * ascendentes/descendentes de dígitos consecutivos como "1234" o "9876").
     *
     * Antes esta función solo existía como `private fun` dentro de
     * SetupPinActivity.kt y solo se llamaba desde el botón táctil "OK" del
     * teclado en pantalla — ni el flujo de tecla física (Key.Enter) del propio
     * SetupPinActivity, ni el diálogo "Cambiar PIN de Administrador" del
     * Dashboard, la usaban. Resultado: un admin podía evitar la validación de
     * PIN débil con un teclado físico durante el setup inicial, y sobre todo
     * podía cambiar más tarde a un PIN trivial como "1234" sin ninguna
     * advertencia — debilitando en la práctica justo la protección que el
     * setup inicial intentaba exigir. Se centraliza acá para que ambos lugares
     * (y cualquier futuro) validen exactamente igual.
     */
    fun isTrivialPin(pin: String): Boolean {
        if (pin.isEmpty()) return true
        if (pin.all { it == pin[0] }) return true

        var ascending = true
        for (i in 0 until pin.length - 1) {
            if (pin[i + 1].code - pin[i].code != 1) {
                ascending = false
                break
            }
        }
        if (ascending) return true

        var descending = true
        for (i in 0 until pin.length - 1) {
            if (pin[i].code - pin[i + 1].code != 1) {
                descending = false
                break
            }
        }
        if (descending) return true

        return false
    }
}

sealed class LockoutStatus {
    object LockedOut : LockoutStatus()
    data class Warning(val remainingAttempts: Int) : LockoutStatus()
}

sealed class LockoutState {
    object Open : LockoutState()
    data class Locked(val remainingMs: Long) : LockoutState()
}
