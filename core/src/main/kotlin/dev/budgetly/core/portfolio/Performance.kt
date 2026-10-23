package dev.budgetly.core.portfolio

import java.time.LocalDate
import java.time.temporal.ChronoUnit
import kotlin.math.abs
import kotlin.math.pow

/**
 * Performance d'une enveloppe entre son premier relevé exploitable et sa valeur actuelle, versements et retraits
 * déduits : taux de rendement interne (TRI), comme le calculent les assureurs et les courtiers.
 * Les montants restent en centimes ; seuls les taux sont des nombres à virgule.
 */
data class EnvelopePerformance(
    val sinceDate: LocalDate,
    val untilDate: LocalDate,
    val startValueCents: Long,
    /** Versements moins retraits sur la période (évolution du cumul des versements imprimé sur les relevés). */
    val netContributionsCents: Long,
    val endValueCents: Long,
    /** Gain sur la période : valeur finale - valeur initiale - versements nets. */
    val gainCents: Long,
    /** Rendement sur la période, composé (non annualisé). */
    val periodRate: Double,
    /** Rendement annualisé, seulement sur une période d'un an au moins (sinon trompeur). */
    val annualizedRate: Double?,
)

object Performance {

    /** Point de relevé : date, valeur de l'enveloppe, cumul des versements nets imprimé (null s'il manque). */
    data class Statement(val date: LocalDate, val valueCents: Long, val contributionsCents: Long?)

    /**
     * Les versements d'une période ne sont pas datés sur les relevés : ils sont placés au milieu de l'intervalle
     * entre deux relevés (hypothèse de versements réguliers). Null si moins de deux points exploitables ou une
     * période de moins de 30 jours.
     */
    fun of(statements: List<Statement>, endDate: LocalDate, endValueCents: Long): EnvelopePerformance? {
        val known = statements.filter { it.contributionsCents != null }.sortedBy { it.date }
        val start = known.firstOrNull() ?: return null
        val days = ChronoUnit.DAYS.between(start.date, endDate)
        if (days < 30) return null

        val flows = mutableListOf(start.date to -start.valueCents)
        var netContributions = 0L
        known.zipWithNext().forEach { (previous, next) ->
            if (next.date > endDate) return@forEach
            val delta = next.contributionsCents!! - previous.contributionsCents!!
            if (delta != 0L) {
                val middle = previous.date.plusDays(ChronoUnit.DAYS.between(previous.date, next.date) / 2)
                flows += middle to -delta
                netContributions += delta
            }
        }
        flows += endDate to endValueCents
        val rate = irr(flows) ?: return null
        val years = days / 365.0
        return EnvelopePerformance(
            sinceDate = start.date,
            untilDate = endDate,
            startValueCents = start.valueCents,
            netContributionsCents = netContributions,
            endValueCents = endValueCents,
            gainCents = endValueCents - start.valueCents - netContributions,
            periodRate = (1 + rate).pow(years) - 1,
            annualizedRate = if (days >= 365) rate else null,
        )
    }

    /**
     * Taux annuel r tel que la somme des flux actualisés soit nulle (négatif = argent investi, positif = valeur
     * récupérée). Bissection : robuste, une solution unique pour ce profil de flux. Les centimes passent en Double
     * seulement pour l'actualisation ; aucun montant n'est recalculé à partir de ce taux.
     */
    fun irr(flows: List<Pair<LocalDate, Long>>): Double? {
        if (flows.none { it.second < 0 } || flows.none { it.second > 0 }) return null
        val origin = flows.minOf { it.first }
        fun npv(rate: Double) = flows.sumOf { (date, cents) ->
            cents / (1 + rate).pow(ChronoUnit.DAYS.between(origin, date) / 365.0)
        }
        var low = -0.9999
        var high = 10.0
        var fLow = npv(low)
        if (fLow * npv(high) > 0) return null
        repeat(200) {
            val mid = (low + high) / 2
            val fMid = npv(mid)
            if (abs(fMid) < 1e-6 || high - low < 1e-12) return mid
            if (fLow * fMid < 0) {
                high = mid
            } else {
                low = mid
                fLow = fMid
            }
        }
        return (low + high) / 2
    }
}
