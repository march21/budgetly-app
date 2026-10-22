package dev.budgetly.core.categories

import dev.budgetly.core.statement.TransactionKind
import java.text.Normalizer

enum class CategoryKind { EXPENSE, INCOME, SAVINGS, TRANSFER }

enum class MatchType { CONTAINS, REGEX }

enum class RuleScope { LABEL, LABEL_AND_DETAILS }

/** Origine de la catégorie d'une opération, par ordre de priorité décroissante. */
enum class CategorySource { MANUAL, INTERNAL, RULE, KIND, SPLIT }

data class Rule(
    val id: Long,
    val pattern: String,
    val matchType: MatchType,
    val scope: RuleScope,
    val categoryId: Long,
    val priority: Int,
)

data class Assignment(val categoryId: Long, val source: CategorySource)

/** Ce que le moteur sait d'une opération pour la classer. */
data class Categorizable(
    val label: String,
    val details: String,
    val kind: TransactionKind,
    val internalTransfer: Boolean,
)

/**
 * Moteur de classement, sans accès à la base :
 * virement entre mes comptes > règle (priorité croissante, puis motif le plus long) > type d'opération.
 * Le choix manuel de l'utilisateur est traité en amont : il n'est jamais remis en cause.
 */
class RuleEngine(rules: List<Rule>, private val internalCategoryId: Long, private val kindDefaults: Map<TransactionKind, Long>) {

    private class Compiled(val rule: Rule, val normalized: String, val regex: Regex?)

    private val ordered = rules
        .sortedWith(compareBy<Rule> { it.priority }.thenByDescending { it.pattern.length }.thenBy { it.id })
        .map { rule ->
            Compiled(
                rule = rule,
                normalized = normalize(rule.pattern),
                regex = if (rule.matchType == MatchType.REGEX) Regex(rule.pattern, RegexOption.IGNORE_CASE) else null,
            )
        }

    fun assign(t: Categorizable): Assignment? {
        if (t.internalTransfer) return Assignment(internalCategoryId, CategorySource.INTERNAL)
        val label = normalize(t.label)
        val both = normalize(t.label + " " + t.details)
        for (c in ordered) {
            val text = if (c.rule.scope == RuleScope.LABEL) label else both
            val matches = c.regex?.containsMatchIn(text) ?: (c.normalized.isNotEmpty() && c.normalized in text)
            if (matches) return Assignment(c.rule.categoryId, CategorySource.RULE)
        }
        return kindDefaults[t.kind]?.let { Assignment(it, CategorySource.KIND) }
    }

    companion object {
        fun normalize(text: String): String =
            Normalizer.normalize(text, Normalizer.Form.NFD).replace(Regex("\\p{M}"), "")
                .uppercase().replace(Regex("\\s+"), " ").trim()

        /**
         * Motif proposé quand l'utilisateur reclasse une opération : le commerçant ou l'émetteur,
         * sans le préfixe du type d'opération ni la date d'achat.
         */
        fun suggestPattern(label: String): String {
            val normalized = normalize(label)
            val withoutPrefix = prefixes.firstOrNull { normalized.startsWith(it) }
                ?.let { normalized.removePrefix(it) } ?: normalized
            return withoutPrefix.replace(Regex("""\s*\b\d{2}[./]\d{2}[./]\d{2}\b.*$"""), "").trim().ifEmpty { normalized }
        }

        private val prefixes = listOf("ACHAT CB ", "PRELEVEMENT DE ", "VIREMENT INSTANTANE DE ", "VIREMENT INSTANTANE A ")
    }
}
