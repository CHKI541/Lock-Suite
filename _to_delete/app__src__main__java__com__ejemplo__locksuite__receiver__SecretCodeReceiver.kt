package com.ejemplo.locksuite.receiver

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.ejemplo.locksuite.ui.auth.LoginActivity
import com.ejemplo.locksuite.ui.emergency.EmergencyActivity

class SecretCodeReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        val action = intent.action ?: return
        if (action == "android.provider.Telephony.SECRET_CODE") {
            val host = intent.data?.host ?: return
            when (host) {
                "1234" -> {
                    val appIntent = Intent(context, LoginActivity::class.java).apply {
                        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
                    }
                    context.startActivity(appIntent)
                }
                "9999" -> {
                    val emergencyIntent = Intent(context, EmergencyActivity::class.java).apply {
                        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
                    }
                    context.startActivity(emergencyIntent)
                }
            }
        }
    }
}
