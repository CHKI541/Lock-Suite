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
    }

    private fun prefs() = PrefsHelper.getMdmPrefs(context)

    /** Carga las reglas desde SharedPreferences y actualiza el engine. */
    fun loadRules() {
        val blocked = prefs().getStringSet(KEY_BLOCKED, emptySet()) ?: emptySet()
        val allowed = prefs().getStringSet(KEY_ALLOWED, emptySet()) ?: emptySet()
        val map = mutableMapOf<String, RuleType>()
        for (d in blocked) map[normalizeDomain(d)] = RuleType.BLOCK
        for (d in allowed) map[normalizeDomain(d)] = RuleType.ALLOW
        engine.updateRules(map)
    }

    fun setRule(domain: String, rule: RuleType) {
        val normalized = normalizeDomain(domain)
        val editor = prefs().edit()
        val blocked = (prefs().getStringSet(KEY_BLOCKED, emptySet()) ?: emptySet()).toMutableSet()
        val allowed = (prefs().getStringSet(KEY_ALLOWED, emptySet()) ?: emptySet()).toMutableSet()
        when (rule) {
            RuleType.BLOCK -> { blocked.add(normalized); allowed.remove(normalized) }
            RuleType.ALLOW -> { allowed.add(normalized); blocked.remove(normalized) }
        }
        editor.putStringSet(KEY_BLOCKED, blocked)
        editor.putStringSet(KEY_ALLOWED, allowed)
        editor.apply()
        loadRules() // Recarga atómica del Trie
    }

    fun clearRule(domain: String) {
        val normalized = normalizeDomain(domain)
        val editor = prefs().edit()
        val blocked = (prefs().getStringSet(KEY_BLOCKED, emptySet()) ?: emptySet()).toMutableSet()
        val allowed = (prefs().getStringSet(KEY_ALLOWED, emptySet()) ?: emptySet()).toMutableSet()
        blocked.remove(normalized)
        allowed.remove(normalized)
        editor.putStringSet(KEY_BLOCKED, blocked)
        editor.putStringSet(KEY_ALLOWED, allowed)
        editor.apply()
        loadRules()
    }

    fun getAllRules(): Map<String, RuleType> {
        val blocked = prefs().getStringSet(KEY_BLOCKED, emptySet()) ?: emptySet()
        val allowed = prefs().getStringSet(KEY_ALLOWED, emptySet()) ?: emptySet()
        val map = mutableMapOf<String, RuleType>()
        for (d in blocked) map[normalizeDomain(d)] = RuleType.BLOCK
        for (d in allowed) map[normalizeDomain(d)] = RuleType.ALLOW
        return map
    }
}
