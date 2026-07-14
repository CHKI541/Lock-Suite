package com.ejemplo.locksuite.receiver

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import com.ejemplo.locksuite.util.FirebaseDeviceSync

class PackageReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val action = intent.action
        Log.i("PackageReceiver", "Acción de paquete recibida: $action")
        if (action == Intent.ACTION_PACKAGE_ADDED ||
            action == Intent.ACTION_PACKAGE_REMOVED ||
            action == Intent.ACTION_PACKAGE_REPLACED) {
            
            val isReplacing = intent.getBooleanExtra(Intent.EXTRA_REPLACING, false)
            // ACTION_PACKAGE_REMOVED se dispara con EXTRA_REPLACING = true cuando se está actualizando la app.
            // Para evitar doble sincronización durante una actualización (quitar + añadir),
            // ignoramos el REMOVED si es parte de un reemplazo.
            if (action == Intent.ACTION_PACKAGE_REMOVED && isReplacing) {
                return
            }

            try {
                Log.i("PackageReceiver", "Sincronizando información de apps tras cambio en los paquetes.")
                FirebaseDeviceSync.syncDeviceInfo(context)
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }
    }
}
