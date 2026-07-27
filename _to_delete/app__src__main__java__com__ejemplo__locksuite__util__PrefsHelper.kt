package com.ejemplo.locksuite.util

import android.content.Context
import android.content.SharedPreferences
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey

object PrefsHelper {

    fun getEncryptedPrefs(context: Context): SharedPreferences {
        return try {
            val masterKey = MasterKey.Builder(context)
                .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
                .build()
            EncryptedSharedPreferences.create(
                context,
                Constants.PREFS_NAME,
                masterKey,
                EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
                EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
            )
        } catch (e: Exception) {
            // Fallback en caso de que falle la inicialización del keystore del sistema
            context.getSharedPreferences(Constants.PREFS_NAME, Context.MODE_PRIVATE)
        }
    }

    fun getMdmPrefs(context: Context): SharedPreferences {
        return context.getSharedPreferences(Constants.MDM_STATE_PREFS, Context.MODE_PRIVATE)
    }
}
