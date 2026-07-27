package com.ejemplo.locksuite.dns

import java.util.concurrent.atomic.AtomicReference

class DomainRuleEngine {
    private val trieRef = AtomicReference(DomainRuleTrie.build(emptyMap()))
    private val rulesRef = AtomicReference<Map<String, RuleType>>(emptyMap())

    /** Llamar desde el hilo de procesamiento de paquetes DNS. NO usa locks. */
    fun effectiveRule(domain: String): RuleType? =
        trieRef.get().resolve(domain)

    /** Regla explícita puesta a mano SOLO para este dominio exacto (sin heredar). */
    fun explicitRule(domain: String): RuleType? =
        rulesRef.get()[normalizeDomain(domain)]

    /** Reconstruye el Trie atómicamente al cambiar reglas. */
    fun updateRules(rules: Map<String, RuleType>) {
        val normalized = rules.mapKeys { normalizeDomain(it.key) }
        rulesRef.set(normalized)
        trieRef.set(DomainRuleTrie.build(normalized))
    }
}
