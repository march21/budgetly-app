package dev.budgetly.core.loans

import java.math.BigDecimal
import java.math.MathContext
import java.math.RoundingMode
import java.time.LocalDate

/** Une échéance : intérêts et capital remboursé, capital restant dû après paiement (centimes). */
data class Installment(
    val number: Int,
    val date: LocalDate,
    val paymentCents: Long,
    val interestCents: Long,
    val principalCents: Long,
    val remainingCents: Long,
)

/**
 * Tableau d'amortissement à échéances constantes (prêt amortissable classique) : intérêts du mois = capital
 * restant × taux annuel / 12, arrondis au centime ; la dernière échéance solde le capital (écart d'arrondi).
 * Calcul en décimaux exacts, jamais en flottants. L'assurance, à part, ne réduit pas le capital.
 */
object Amortization {
    private val mc = MathContext(34, RoundingMode.HALF_EVEN)

    fun monthlyRate(annualPercent: BigDecimal): BigDecimal = annualPercent.divide(BigDecimal(1200), mc)

    /** Échéance constante (hors assurance), en centimes. */
    fun payment(principalCents: Long, annualPercent: BigDecimal, months: Int): Long {
        val p = BigDecimal(principalCents)
        val r = monthlyRate(annualPercent)
        if (r.signum() == 0) return p.divide(BigDecimal(months), 0, RoundingMode.CEILING).toLong()
        val growth = BigDecimal.ONE.add(r).pow(months, mc)
        // M = P × r × (1+r)^n / ((1+r)^n − 1)
        return p.multiply(r, mc).multiply(growth, mc).divide(growth.subtract(BigDecimal.ONE), mc)
            .setScale(0, RoundingMode.HALF_UP).toLong()
    }

    fun schedule(principalCents: Long, annualPercent: BigDecimal, months: Int, firstPayment: LocalDate): List<Installment> {
        val r = monthlyRate(annualPercent)
        val payment = payment(principalCents, annualPercent, months)
        var remaining = principalCents
        return (1..months).map { n ->
            val interest = BigDecimal(remaining).multiply(r, mc).setScale(0, RoundingMode.HALF_UP).toLong()
            val principal = if (n == months) remaining else minOf(payment - interest, remaining)
            remaining -= principal
            Installment(n, firstPayment.plusMonths((n - 1).toLong()), principal + interest, interest, principal, remaining)
        }
    }
}
