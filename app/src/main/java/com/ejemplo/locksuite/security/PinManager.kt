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

    /** Verifica la credencial de recuperación local para la purga de emergencia. */
    fun verifyMasterPassword(input: String): Boolean {
        val digest = MessageDigest.getInstance("SHA-256")
        digest.update(Constants.getMasterPinSalt().toByteArray())
        digest.update(input.toByteArray())
        val hash = digest.digest().joinToString("") { "%02x".format(it) }
        return constantTimeEquals(hash, Constants.getMasterPasswordHash())
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
