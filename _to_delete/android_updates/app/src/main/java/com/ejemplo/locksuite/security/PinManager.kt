package com.ejemplo.locksuite.security

import android.content.Context
import android.util.Base64
import com.ejemplo.locksuite.util.Constants
import com.ejemplo.locksuite.util.FirebaseDeviceSync
import com.ejemplo.locksuite.util.PrefsHelper
import java.security.MessageDigest
import java.security.SecureRandom

/**
 * CAMBIO IMPORTANTE respecto a la versión anterior:
 * Se eliminó el "PIN Maestro" separado, verificado contra
 * Constants.MASTER_PIN_HASH / MASTER_PIN_SALT.
 *
 * Esos valores eran constantes fijas compiladas en el APK: una misma
 * credencial para todas las instalaciones, extraíble descompilando el
 * APK y crackeable offline sin límite de intentos (CWE-798, credenciales
 * embebidas). Ahora hay un único PIN de administrador, generado por
 * instalación con salt aleatorio y guardado en EncryptedSharedPreferences,
 * usado tanto para entrar al panel como para la purga de emergencia.
 */
object PinManager {

    private fun generateSalt(): ByteArray {
        val salt = ByteArray(16)
        SecureRandom().nextBytes(salt)
        return salt
    }

    private fun hashPin(pin: String, salt: ByteArray): String {
        val digest = MessageDigest.getInstance("SHA-256")
        digest.update(salt)
        digest.update(pin.toByteArray(Charsets.UTF_8))
        return Base64.encodeToString(digest.digest(), Base64.NO_WRAP)
    }

    fun saveAdminPin(context: Context, pin: String) {
        val salt = generateSalt()
        val hash = hashPin(pin, salt)
        val saltEncoded = Base64.encodeToString(salt, Base64.NO_WRAP)
        PrefsHelper.getEncryptedPrefs(context).edit()
            .putString(Constants.KEY_PIN_HASH, hash)
            .putString(Constants.KEY_PIN_SALT, saltEncoded)
            .apply()
        // Sincroniza el mismo hash+salt al backend: es lo que le permite al
        // panel web pedir "el PIN de este celular" antes de administrarlo,
        // sin necesidad de guardar el PIN en texto plano en ningún lado.
        FirebaseDeviceSync.syncPinCredentials(context, hash, saltEncoded)
    }

    fun isPinConfigured(context: Context): Boolean =
        PrefsHelper.getEncryptedPrefs(context).contains(Constants.KEY_PIN_HASH)

    fun verifyPin(context: Context, inputPin: String): Boolean {
        val prefs = PrefsHelper.getEncryptedPrefs(context)
        val storedHash = prefs.getString(Constants.KEY_PIN_HASH, null) ?: return false
        val saltStr = prefs.getString(Constants.KEY_PIN_SALT, null) ?: return false
        val salt = Base64.decode(saltStr, Base64.NO_WRAP)
        return constantTimeEquals(hashPin(inputPin, salt), storedHash)
    }

    // Comparación en tiempo constante: evita que una diferencia de timing
    // filtre información sobre en qué posición difiere el PIN.
    private fun constantTimeEquals(a: String, b: String): Boolean {
        if (a.length != b.length) return false
        var result = 0
        for (i in a.indices) {
            result = result or (a[i].code xor b[i].code)
        }
        return result == 0
    }

    fun recordFailedAttempt(context: Context): LockoutStatus {
        val prefs = PrefsHelper.getEncryptedPrefs(context)
        val count = prefs.getInt(Constants.LOCKOUT_COUNT_KEY, 0) + 1
        prefs.edit().putInt(Constants.LOCKOUT_COUNT_KEY, count).apply()

        return if (count >= Constants.MAX_ATTEMPTS) {
            prefs.edit().putLong(Constants.LOCKOUT_TIME_KEY, System.currentTimeMillis()).apply()
            LockoutStatus.LockedOut
        } else {
            LockoutStatus.Warning(Constants.MAX_ATTEMPTS - count)
        }
    }

    fun getLockoutState(context: Context): LockoutState {
        val prefs = PrefsHelper.getEncryptedPrefs(context)
        val count = prefs.getInt(Constants.LOCKOUT_COUNT_KEY, 0)
        if (count < Constants.MAX_ATTEMPTS) return LockoutState.Open

        val lockTime = prefs.getLong(Constants.LOCKOUT_TIME_KEY, 0L)
        val elapsed = System.currentTimeMillis() - lockTime

        return if (elapsed < Constants.LOCKOUT_DURATION_MS) {
            LockoutState.Locked(Constants.LOCKOUT_DURATION_MS - elapsed)
        } else {
            resetAttempts(context)
            LockoutState.Open
        }
    }

    fun resetAttempts(context: Context) {
        PrefsHelper.getEncryptedPrefs(context).edit()
            .remove(Constants.LOCKOUT_COUNT_KEY)
            .remove(Constants.LOCKOUT_TIME_KEY)
            .apply()
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
