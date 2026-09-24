package com.ejemplo.locksuite.service

import com.google.firebase.messaging.FirebaseMessagingService
import com.google.firebase.messaging.RemoteMessage

/**
 * Punto de entrada de FCM. Desde el 22/9/2026 solo delega: la ejecución de comandos vive
 * en [CommandProcessor], que comparte con el buzón de la base de datos
 * (`util/CommandMailbox.kt`). Ver el encabezado de CommandProcessor para el porqué.
 */
class LockSuiteFirebaseService : FirebaseMessagingService() {

    override fun onMessageReceived(message: RemoteMessage) {
        CommandProcessor(this).process(message.data, CommandProcessor.Source.FCM)
    }

    /**
     * FCM descartó mensajes pendientes para este equipo (más de 100 en cola, o vencidos
     * mientras estaba sin red). Antes esto no estaba implementado y esos comandos se
     * perdían sin rastro. Ahora se drena el buzón (ahí está cada comando firmado) y se
     * relee la configuración de apps, que es estado y no comando.
     */
    override fun onDeletedMessages() {
        try {
            com.ejemplo.locksuite.util.CommandMailbox.drainAsync(applicationContext, "FCM descartó mensajes")
            com.ejemplo.locksuite.util.FirebaseDeviceSync.pullWhitelistConfigAsync(applicationContext)
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    override fun onNewToken(token: String) {
        try {
            com.ejemplo.locksuite.util.FirebaseDeviceSync.syncToken(this, token)
            com.ejemplo.locksuite.util.FirebaseDeviceSync.syncDeviceInfo(this)
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }
}
