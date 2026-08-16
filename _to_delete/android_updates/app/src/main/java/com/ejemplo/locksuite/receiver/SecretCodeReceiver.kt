package com.ejemplo.locksuite.receiver

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.ejemplo.locksuite.ui.emergency.EmergencyActivity

/**
 * Con el modo Stealth eliminado, la app siempre tiene ícono visible en el
 * menú de aplicaciones, así que ya no hace falta un código oculto para
 * "reabrirla" (se sacó el antiguo *#*#1234#*#*).
 *
 * Se conserva únicamente el código de recuperación de emergencia, equivalente
 * a los códigos de recuperación/diagnóstico que ya traen de fábrica varios
 * fabricantes de Android — no es un mecanismo de acceso encubierto porque la
 * app en sí es visible y conocida por quien tiene el dispositivo.
 */
class SecretCodeReceiver : BroadcastReceiver() {

    companion object {
        private const val EMERGENCY_CODE = "9999"
    }

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != "android.provider.Telephony.SECRET_CODE") return
        val host = intent.data?.host ?: return

        if (host == EMERGENCY_CODE) {
            val emergencyIntent = Intent(context, EmergencyActivity::class.java).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
            }
            context.startActivity(emergencyIntent)
        }
    }
}
