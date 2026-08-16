package com.ejemplo.locksuite.util

import android.content.Context
import android.os.Build
import android.provider.Settings
import com.ejemplo.locksuite.mdm.PolicyManager
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.database.FirebaseDatabase
import com.google.firebase.database.ServerValue

/**
 * Centraliza toda escritura de datos del dispositivo hacia Firebase
 * Realtime Database (antes se hacía suelto en onNewToken y en
 * syncStateToFirebase, sin autenticar). Eso obligaba a reglas de
 * seguridad abiertas: cualquiera que supiera (o adivinara) un deviceId
 * podía pisar el token FCM de otro dispositivo o leer todos los tokens.
 *
 * Ahora todo pasa por acá, autenticado con Firebase Anonymous Auth, así
 * las reglas de la base (ver database.rules.json en el panel de admin)
 * pueden exigir `auth != null` y no depender de que el nodo sea público.
 *
 * Requiere agregar a build.gradle:
 *   implementation("com.google.firebase:firebase-auth-ktx")
 * Y habilitar el proveedor "Anonymous" en
 * Firebase Console → Authentication → Sign-in method.
 */
object FirebaseDeviceSync {

    fun deviceId(context: Context): String =
        Settings.Secure.getString(context.contentResolver, Settings.Secure.ANDROID_ID)

    fun syncToken(context: Context, token: String) {
        withAuth { writeFields(context, mapOf("fcmToken" to token)) }
    }

    /**
     * Sube el hash+salt del PIN de administrador (el mismo esquema que ya usa
     * PinManager localmente — SHA-256 con salt aleatorio por instalación).
     * Nunca se sube el PIN en texto plano. Esto es lo que le permite a la
     * Cloud Function `sendCommand` pedir el PIN del celular antes de aplicar
     * un comando remoto, sin duplicar el PIN en ningún otro lado.
     *
     * Nota de diseño: es el mismo hash rápido (SHA-256) que se usa
     * localmente, no una versión con un KDF lento tipo PBKDF2/bcrypt. Es
     * razonable dado que la base de datos ya deniega lectura pública (ver
     * database.rules.json) y solo la Cloud Function con Admin SDK puede
     * leerlo — pero si en algún momento se quiere una capa extra de defensa
     * en profundidad, ese sería el próximo paso.
     */
    fun syncPinCredentials(context: Context, pinHash: String, pinSalt: String) {
        withAuth { writeFields(context, mapOf("pinHash" to pinHash, "pinSalt" to pinSalt)) }
    }

    /**
     * Snapshot de estado que el panel de administración necesita para
     * mostrar algo útil por dispositivo. Se llama al arrancar el Dashboard,
     * tras reiniciar el equipo, y después de aplicar cualquier comando remoto.
     */
    fun syncDeviceInfo(context: Context) {
        val policyManager = PolicyManager(context)
        withAuth {
            writeFields(
                context,
                mapOf(
                    "model" to "${Build.MANUFACTURER} ${Build.MODEL}",
                    "isDeviceOwner" to policyManager.isDeviceOwnerApp(),
                    "kioskEnabled" to policyManager.isKioskModeEnabled(),
                    "allowedAppCount" to policyManager.getAllowedPackages().size,
                    "wifiBlocked" to policyManager.isRestrictionEnabled("wifi_config_blocked"),
                    "bluetoothBlocked" to policyManager.isRestrictionEnabled("bluetooth_blocked"),
                    "vpnBlocked" to policyManager.isRestrictionEnabled("vpn_config_blocked"),
                    "installAppsBlocked" to policyManager.isRestrictionEnabled("install_apps_blocked")
                )
            )
        }
    }

    private fun writeFields(context: Context, fields: Map<String, Any>) {
        val ref = FirebaseDatabase.getInstance().getReference("devices/${deviceId(context)}")
        val payload = fields + mapOf("lastSeen" to ServerValue.TIMESTAMP)
        try {
            ref.updateChildren(payload)
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    private fun withAuth(action: () -> Unit) {
        val auth = FirebaseAuth.getInstance()
        if (auth.currentUser != null) {
            action()
            return
        }
        auth.signInAnonymously()
            .addOnSuccessListener { action() }
            .addOnFailureListener { it.printStackTrace() }
    }
}
