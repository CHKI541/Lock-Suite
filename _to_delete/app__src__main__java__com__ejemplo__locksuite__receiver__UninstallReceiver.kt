package com.ejemplo.locksuite.receiver

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import android.widget.Toast

class UninstallReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val status = intent.getIntExtra(PackageInstaller.EXTRA_STATUS, PackageInstaller.STATUS_FAILURE)
        if (status == PackageInstaller.STATUS_SUCCESS) {
            Toast.makeText(context, "Aplicación desinstalada con éxito", Toast.LENGTH_SHORT).show()
            // Enviar broadcast local para refrescar el Dashboard de inmediato (H17)
            val refreshIntent = Intent("com.ejemplo.locksuite.ACTION_APP_UNINSTALLED")
            context.sendBroadcast(refreshIntent)
        } else {
            val message = intent.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE) ?: "Fallo al desinstalar la aplicación"
            Toast.makeText(context, message, Toast.LENGTH_SHORT).show()
        }
    }
}
