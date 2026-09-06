package com.ejemplo.locksuite.dns

import android.content.Context
import com.ejemplo.locksuite.util.PrefsHelper

class DomainRuleManager(
    private val context: Context,
    private val engine: DomainRuleEngine
) {
    companion object {
        private const val KEY_BLOCKED = "dns_custom_blocked_domains"
        private const val KEY_ALLOWED = "dns_custom_allowed_domains"
        private const val KEY_FORCE_BLOCKED = "dns_custom_force_blocked_domains"
        private const val KEY_FORCE_ALLOWED = "dns_custom_force_allowed_domains"

        private val KEY_BY_TYPE = mapOf(
            RuleType.BLOCK to KEY_BLOCKED,
            RuleType.ALLOW to KEY_ALLOWED,
            RuleType.FORCE_BLOCK to KEY_FORCE_BLOCKED,
            RuleType.FORCE_ALLOW to KEY_FORCE_ALLOWED
        )
    }

    private fun prefs() = PrefsHelper.getMdmPrefs(context)

    private fun readSet(key: String): MutableSet<String> =
        (prefs().getStringSet(key, emptySet()) ?: emptySet()).toMutableSet()

    /** Carga las reglas desde SharedPreferences y actualiza el engine. */
    fun loadRules() {
        val map = mutableMapOf<String, RuleType>()
        for ((type, key) in KEY_BY_TYPE) {
            for (d in readSet(key)) map[normalizeDomain(d)] = type
        }
        engine.updateRules(map)
    }

    /**
     * Fija una regla para el dominio. Un dominio solo puede tener UNA regla
     * activa a la vez: al fijarla se lo saca de los otros 3 conjuntos (normal
     * bloqueado/permitido, forzado bloqueado/permitido) para que no queden
     * estados contradictorios guardados en simultaneo. Tambien se asegura de
     * que la VPN este corriendo: antes, si esta era la UNICA politica activa
     * (sin webview bloqueado, sin adblock, sin gifs), la regla quedaba
     * guardada pero nunca se aplicaba hasta el proximo reinicio del equipo.
     */
    fun setRule(domain: String, rule: RuleType) {
        val normalized = normalizeDomain(domain)
        val editor = prefs().edit()
        for ((type, key) in KEY_BY_TYPE) {
            val set = readSet(key)
            if (type == rule) set.add(normalized) else set.remove(normalized)
            editor.putStringSet(key, set)
        }
        editor.apply()
        loadRules() // Recarga atomica del Trie
        refreshVpnState()
    }

    fun clearRule(domain: String) {
        val normalized = normalizeDomain(domain)
        val editor = prefs().edit()
        for (key in KEY_BY_TYPE.values) {
            val set = readSet(key)
            set.remove(normalized)
            editor.putStringSet(key, set)
        }
        editor.apply()
        loadRules()
        refreshVpnState()
    }

    private fun refreshVpnState() {
        try {
            if (com.ejemplo.locksuite.receiver.BootReceiver.shouldVpnBeRunning(context)) {
                // 6/9/2026 (B.49): antes acá iba "RESTART_VPN", o sea que tocar UNA regla
                // DNS tiraba abajo el túnel entero. Rehacer el túnel para recargar un Trie
                // que se lee en memoria no aporta nada y sí cuesta: unos segundos sin que
                // ninguna app del equipo pueda resolver, más el riesgo de la carrera de
                // reestablecimiento que dejaba al equipo sin DNS (ver scheduleRestart()
                // en KosherVpnService). "RELOAD_RULES" recarga en caliente, y si la VPN
                // no estaba corriendo la arranca igual.
                val vpnIntent = android.content.Intent(context, com.ejemplo.locksuite.service.KosherVpnService::class.java).apply {
                    action = "RELOAD_RULES"
                }
                if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
                    context.startForegroundService(vpnIntent)
                } else {
                    context.startService(vpnIntent)
                }
            } else {
                val stopServiceIntent = android.content.Intent(context, com.ejemplo.locksuite.service.KosherVpnService::class.java).apply {
                    action = "STOP_VPN"
                }
                if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
                    context.startForegroundService(stopServiceIntent)
                } else {
                    context.startService(stopServiceIntent)
                }
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }

    fun getAllRules(): Map<String, RuleType> {
        val map = mutableMapOf<String, RuleType>()
        for ((type, key) in KEY_BY_TYPE) {
            for (d in readSet(key)) map[normalizeDomain(d)] = type
        }
        return map
    }
}
