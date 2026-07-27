package com.ejemplo.locksuite.dns

// Normalización centralizada
fun normalizeDomain(domain: String): String =
    domain.trim().trimEnd('.').lowercase()

fun domainLabelsReversed(domain: String): List<String> =
    normalizeDomain(domain).split(".").filter { it.isNotEmpty() }.asReversed()

enum class RuleType { BLOCK, ALLOW }

private class TrieNode {
    val children = HashMap<String, TrieNode>()
    var rule: RuleType? = null
}

class DomainRuleTrie private constructor(private val root: TrieNode) {
    companion object {
        fun build(rules: Map<String, RuleType>): DomainRuleTrie {
            val root = TrieNode()
            for ((domain, rule) in rules) insert(root, domain, rule)
            return DomainRuleTrie(root)
        }

        private fun insert(root: TrieNode, domain: String, rule: RuleType) {
            val labels = domainLabelsReversed(domain)
            if (labels.isEmpty()) return
            var node = root
            for (label in labels) node = node.children.getOrPut(label) { TrieNode() }
            node.rule = rule
        }
    }

    /**
     * Camina desde el TLD hacia las subetiquetas.
     * Si encuentra múltiples reglas en el camino, la más específica (más profunda) gana.
     * Retorna null si no hay regla que aplique.
     */
    fun resolve(domain: String): RuleType? {
        val labels = domainLabelsReversed(domain)
        if (labels.isEmpty()) return null
        var node = root
        var lastMatch: RuleType? = null
        for (label in labels) {
            node = node.children[label] ?: break
            node.rule?.let { lastMatch = it }
        }
        return lastMatch
    }
}
