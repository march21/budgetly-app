package dev.budgetly.core.ledger

import dev.budgetly.core.categories.RuleEngine
import java.time.LocalDate
import java.time.temporal.ChronoUnit
import kotlin.math.abs

enum class SeriesStatus { ACTIVE, STOPPED }

/** Une opération candidate : débit ou crédit qui n'est pas un virement entre mes comptes. */
data class RecurringCandidate(
    val id: Long,
    val date: LocalDate,
    val amountCents: Long,
    val label: String,
    val categoryName: String?,
    val categoryKind: String?,
)

/**
 * Série récurrente détectée : même motif de libellé, montant comparable, environ une fois par mois.
 * La clé est stable d'une analyse à l'autre (motif + sens + ordre de grandeur), pour pouvoir l'écarter.
 */
data class RecurringSeries(
    val key: String,
    val pattern: String,
    val label: String,
    val categoryName: String?,
    val categoryKind: String?,
    val credit: Boolean,
    val occurrences: Int,
    val firstDate: LocalDate,
    val lastDate: LocalDate,
    val intervalDays: Int,
    /** Rythme mensuel : l'occurrence suivante tombe le même jour du mois suivant. */
    val monthly: Boolean,
    val nextDate: LocalDate,
    /** Dernier montant, en centimes, signé. */
    val lastAmountCents: Long,
    val previousAmountCents: Long?,
    /** Montant moyen des trois dernières occurrences, signé : sert au total des charges fixes. */
    val typicalAmountCents: Long,
    /** Montant qui varie d'une fois sur l'autre (énergie, téléphone) : pas d'alerte de hausse. */
    val variable: Boolean,
    val status: SeriesStatus,
    /** Apparue récemment (première occurrence dans les deux derniers mois connus). */
    val isNew: Boolean,
    /** Hausse ou baisse du montant à la dernière occurrence (série à montant fixe). */
    val priceChanged: Boolean,
    val transactionIds: List<Long>,
)

/**
 * Détection des charges et revenus récurrents mensuels. Volontairement prudente : on préfère rater une série
 * qu'en inventer une. `horizon` = date de la dernière opération connue (les relevés arrivent avec du retard,
 * la date du jour ne dit pas si une série s'est arrêtée).
 */
object RecurringDetector {
    private const val MIN_INTERVAL = 20
    private const val MAX_INTERVAL = 40

    fun detect(candidates: List<RecurringCandidate>, horizon: LocalDate): List<RecurringSeries> {
        val dataStart = candidates.minOfOrNull { it.date } ?: return emptyList()
        return candidates.groupBy { RuleEngine.suggestPattern(it.label) to (it.amountCents > 0) }
            .filterKeys { (pattern, _) -> pattern.length >= 3 }
            .flatMap { (key, items) ->
                // Plusieurs passages le même mois chez ce commerçant : seules des séries à montant exact (box et mobile
                // du même opérateur) peuvent être des abonnements ; des courses ne le sont pas.
                val crowded = items.groupBy { it.date.withDayOfMonth(1) }.any { it.value.size > 1 }
                clusters(items).mapNotNull { series(key.first, key.second, it, horizon, dataStart, crowded) }
            }
            .sortedWith(compareBy<RecurringSeries> { it.credit }.thenBy { it.typicalAmountCents })
    }

    /** Un même commerçant peut porter deux abonnements (box et mobile) : regroupement par montant voisin (±35 %, une facture d'énergie varie). */
    private fun clusters(items: List<RecurringCandidate>): List<List<RecurringCandidate>> {
        val groups = mutableListOf<MutableList<RecurringCandidate>>()
        for (item in items.sortedBy { abs(it.amountCents) }) {
            val last = groups.lastOrNull()
            val reference = last?.let { g -> g.map { abs(it.amountCents) }.sorted()[g.size / 2] }
            if (reference != null && abs(abs(item.amountCents) - reference) <= reference * 0.35 + 100) last += item else groups += mutableListOf(item)
        }
        return groups.map { g -> g.sortedBy { it.date } }
    }

    private fun series(
        pattern: String,
        credit: Boolean,
        items: List<RecurringCandidate>,
        horizon: LocalDate,
        dataStart: LocalDate,
        crowded: Boolean,
    ): RecurringSeries? {
        // Une occurrence par mois au plus : deux passages le même mois chez le même commerçant ne font pas un abonnement.
        if (items.size < 2 || items.groupBy { it.date.withDayOfMonth(1) }.any { it.value.size > 1 }) return null
        val intervals = items.zipWithNext { a, b -> ChronoUnit.DAYS.between(a.date, b.date).toInt() }
        if (intervals.any { it !in MIN_INTERVAL..MAX_INTERVAL }) return null
        val amounts = items.map { it.amountCents }
        val fixedAmount = amounts.distinct().size == 1
        // Deux occurrences seulement : il faut le même montant (abonnement) pour y croire.
        if (items.size == 2 && !fixedAmount) return null
        if (crowded && !fixedAmount) return null

        // Variabilité jugée sur l'historique avant la dernière occurrence : une hausse de prix n'est pas une variation.
        val history = amounts.dropLast(1).takeLast(3)
        val historyMean = history.sum() / history.size
        val variable = history.size >= 2 && history.any { abs(it - historyMean) > abs(historyMean) * 0.10 }
        val recent = amounts.takeLast(3)
        val typical = if (variable) recent.sum() / recent.size else amounts.last()
        val interval = intervals.sorted()[intervals.size / 2]
        val last = items.last()
        val previous = items.getOrNull(items.size - 2)
        val stopped = ChronoUnit.DAYS.between(last.date, horizon) > interval * 1.6
        val changed = !variable && previous != null && previous.amountCents != last.amountCents &&
            abs(last.amountCents - previous.amountCents) >= maxOf(50L, abs(previous.amountCents) / 50)
        val bucket = Math.round(Math.log10(abs(typical).coerceAtLeast(1).toDouble()) * 4)
        return RecurringSeries(
            key = "$pattern|${if (credit) "C" else "D"}|$bucket",
            pattern = pattern,
            label = last.label,
            categoryName = last.categoryName,
            categoryKind = last.categoryKind,
            credit = credit,
            occurrences = items.size,
            firstDate = items.first().date,
            lastDate = last.date,
            intervalDays = interval,
            monthly = interval in 26..35,
            nextDate = if (interval in 26..35) last.date.plusMonths(1) else last.date.plusDays(interval.toLong()),
            lastAmountCents = last.amountCents,
            previousAmountCents = previous?.amountCents,
            typicalAmountCents = typical,
            variable = variable,
            status = if (stopped) SeriesStatus.STOPPED else SeriesStatus.ACTIVE,
            // Nouvelle : absente du premier mois de données au moins, et apparue dans les deux derniers mois.
            isNew = !stopped && ChronoUnit.DAYS.between(dataStart, items.first().date) > 35 &&
                ChronoUnit.DAYS.between(items.first().date, horizon) <= 62,
            priceChanged = changed && !stopped,
            transactionIds = items.map { it.id },
        )
    }
}
