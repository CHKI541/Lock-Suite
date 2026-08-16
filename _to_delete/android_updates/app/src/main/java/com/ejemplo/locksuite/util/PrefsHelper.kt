package com.ejemplo.locksuite.util

import android.content.Context
import android.content.SharedPreferences
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey

/**
 * Requiere en build.gradle (module :app):
 *   implementation("androidx.security:security-crypto:1.1.0-alpha06")
 * (o la versión estable vigente al momento de compilar)
 */
object PrefsHelper {

    @Volatile
    private var encryptedPrefs: SharedPreferences? = null

    /**
     * Preferencias normales para estado de políticas MDM (no contienen secretos,
     * son un espejo local de configuración ya aplicada vía DevicePolicyManager).
     */
    fun getMdmPrefs(context: Context): SharedPreferences =
        context.getSharedPreferences(Constants.PREFS_MDM, Context.MODE_PRIVATE)

    /**
     * Preferencias cifradas (hash+salt del PIN, contadores de intentos fallidos).
     * La clave de cifrado vive en el Android Keystore respaldado por hardware,
     * nunca en texto plano ni en el APK.
     */
    fun getEncryptedPrefs(context: Context): SharedPreferences {
        return encryptedPrefs ?: synchronized(this) {
            encryptedPrefs ?: buildEncryptedPrefs(context.applicationContext).also {
                encryptedPrefs = it
            }
        }
    }

    private fun buildEncryptedPrefs(context: Context): SharedPreferences {
        val masterKey = MasterKey.Builder(context)
            .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
            .build()

        return EncryptedSharedPreferences.create(
            context,
            Constants.PREFS_SECURE,
            masterKey,
            EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
            EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
        )
    }
}
