package com.ejemplo.locksuite.receiver

import android.app.admin.DeviceAdminReceiver
import android.content.Context
import android.content.Intent
import android.widget.Toast

class DeviceAdminReceiver : DeviceAdminReceiver() {

    override fun onEnabled(context: Context, intent: Intent) {
        super.onEnabled(context, intent)
        Toast.makeText(context, "LockSuite MDM: Administrador Habilitado", Toast.LENGTH_SHORT).show()
    }

    override fun onDisableRequested(context: Context, intent: Intent): CharSequence {
        return "No se puede desactivar la administración de LockSuite. Contacte al departamento de TI."
    }

    override fun onDisabled(context: Context, intent: Intent) {
        super.onDisabled(context, intent)
        Toast.makeText(context, "LockSuite MDM: Administrador Deshabilitado", Toast.LENGTH_SHORT).show()
    }
}
