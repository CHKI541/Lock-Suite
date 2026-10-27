package com.ejemplo.locksuite.receiver

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import android.util.Log
import android.widget.Toast

class PackageInstallStatusReceiver : BroadcastReceiver() {

    companion object {
        /**
         * El paquete que se está instalando, puesto por SelfUpdater en el PendingIntent
         * (26/10/2026, B.97). `EXTRA_PACKAGE_NAME` no viene siempre en las fallas.
         */
        const val EXTRA_LS_PACKAGE = "com.ejemplo.locksuite.extra.INSTALL_PACKAGE"
    }

    override fun onReceive(context: Context, intent: Intent) {
        val status = intent.getIntExtra(PackageInstaller.EXTRA_STATUS, PackageInstaller.STATUS_FAILURE)
        val message = intent.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE)
        val packageName = intent.getStringExtra(PackageInstaller.EXTRA_PACKAGE_NAME)
            ?: intent.getStringExtra(EXTRA_LS_PACKAGE)

        // Restaurar restricciones de instalación del MDM tras completar o fallar el proceso
        // (solo si no hay un flujo de Play Store en curso, para no cortarle permisos a mitad)
        try {
            if (!com.ejemplo.locksuite.util.UpdateFlowManager.isRunning(context)) {
                val policyManager = com.ejemplo.locksuite.mdm.PolicyManager(context)
                policyManager.restoreInstallRestrictions()
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }

        // Cancelar alarma de timeout de seguridad
        try {
            val safetyIntent = Intent(context, PackageReceiver::class.java).apply {
                action = "INSTALL_SAFETY_TIMEOUT"
            }
            val flags = if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.S) {
                android.app.PendingIntent.FLAG_UPDATE_CURRENT or android.app.PendingIntent.FLAG_IMMUTABLE
            } else {
                android.app.PendingIntent.FLAG_UPDATE_CURRENT
            }
            val pendingIntent = android.app.PendingIntent.getBroadcast(context, 9922, safetyIntent, flags)
            val alarmManager = context.getSystemService(Context.ALARM_SERVICE) as android.app.AlarmManager
            alarmManager.cancel(pendingIntent)
        } catch (e: Exception) {
            e.printStackTrace()
        }

        // B.97 — ¿es la instalación silenciosa de una actualización en curso (ruta de la
        // Tienda)? Entonces el resultado es del flujo: lo cierra, o sigue por Google Play si
        // el sistema rechazó la copia, y avisa con su propia notificación y su registro.
        val delFlujo = try {
            com.ejemplo.locksuite.util.UpdateFlowManager.onHostedInstallStatus(context, packageName, status, message)
        } catch (e: Exception) {
            Log.w("PackageInstallStatus", "No se pudo pasar el resultado al flujo de actualización: ${e.message}")
            false
        }
        if (delFlujo) {
            Log.i("PackageInstallStatus", "Resultado de $packageName (status=$status) entregado al flujo de actualización")
            return
        }

        if (status == PackageInstaller.STATUS_SUCCESS) {
            Log.i("PackageInstallStatus", "Instalación exitosa de $packageName")
            Toast.makeText(context, "Instalación completada con éxito", Toast.LENGTH_LONG).show()
        } else {
            Log.e("PackageInstallStatus", "Error al instalar $packageName: $message (status=$status)")
            Toast.makeText(context, "Fallo al instalar: ${message ?: "Restricciones de usuario o paquete inválido"}", Toast.LENGTH_LONG).show()
        }
    }
}
