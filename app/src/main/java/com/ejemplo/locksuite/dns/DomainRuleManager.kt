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

        try {
            com.ejemplo.locksuite.receiver.BootReceiver.ensureVpnRunning(context)
        } catch (e: Exception) {
            e.printStackTrace()
        }
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
    }

    fun getAllRules(): Map<String, RuleType> {
        val map = mutableMapOf<String, RuleType>()
        for ((type, key) in KEY_BY_TYPE) {
            for (d in readSet(key)) map[normalizeDomain(d)] = type
        }
        return map
    }
}
