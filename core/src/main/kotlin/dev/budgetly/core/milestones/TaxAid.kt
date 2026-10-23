package dev.budgetly.core.milestones

import dev.budgetly.core.imports.ApiException
import org.springframework.http.HttpStatus
import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.stereotype.Service
import java.math.BigDecimal
import java.math.RoundingMode
import java.time.Clock
import java.time.LocalDate

/** Un poste de la déclaration : montant de l'année (centimes positifs) et avantage estimé quand il se calcule. */
data class TaxItem(
    val code: String,
    val cents: Long,
    val transactionCount: Int,
    /** Réduction ou crédit d'impôt estimé (centimes), avant plafonnements propres au foyer. */
    val estimatedBenefitCents: Long? = null,
)

data class TaxRules(
    val verifiedOn: LocalDate,
    val donationRatePercent: Int,
    val donationHelpRatePercent: Int,
    val donationHelpCeilingCents: Long,
    val homeServicesRatePercent: Int,
    val homeServicesCeilingCents: Long,
    val childcareRatePercent: Int,
    val childcareCeilingPerChildCents: Long,
)

/** Aide à la déclaration des revenus d'une année, d'après les opérations catégorisées. */
data class TaxAidReport(
    val year: Int,
    /** Intérêts des livrets réglementés (Livret A, LDDS) : exonérés, rien à déclarer. */
    val regulatedInterest: TaxItem,
    /** Intérêts des autres comptes : imposables (en général déjà prélevés à la source par la banque). */
    val taxableInterest: TaxItem,
    val donations: TaxItem,
    val donationsHelp: TaxItem,
    val homeServices: TaxItem,
    val childcare: TaxItem,
    val retirementSavings: TaxItem,
    val taxesPaid: TaxItem,
    val rules: TaxRules,
)

/**
 * Totaux annuels utiles à la déclaration : intérêts selon le compte, dons, emploi à domicile, garde d'enfants,
 * versements sur un PER, impôts payés. Estimations au taux légal, sans les plafonds qui dépendent du foyer
 * (20 % du revenu imposable pour les dons, nombre d'enfants, plafond PER de l'avis d'imposition).
 */
@Service
class TaxAidService(private val jdbc: JdbcClient, private val clock: Clock) {

    fun report(year: Int): TaxAidReport {
        if (year < 2000 || year > LocalDate.now(clock).year) throw ApiException(HttpStatus.BAD_REQUEST, "INVALID_YEAR", "Année inconnue.")
        val from = LocalDate.of(year, 1, 1).toString()
        val to = LocalDate.of(year, 12, 31).toString()
        val rows = rows(from, to)
        fun item(code: String, codes: Set<String>, accountTypes: Set<String>? = null, sign: Int = -1): TaxItem {
            val matching = rows.filter { it.code in codes && (accountTypes == null || it.accountType in accountTypes) }
            return TaxItem(code, matching.sumOf { it.cents * sign }.coerceAtLeast(0), matching.size)
        }
        val regulated = setOf("LIVRET_A", "LDDS")
        val interest = rows.filter { it.code == "interest" }
        val donations = item("donations", setOf("donations"))
        val help = item("donations-help", setOf("donations-help"))
        val home = item("home-services", setOf("home-services"))
        val childcare = item("childcare", setOf("childcare"))
        return TaxAidReport(
            year = year,
            regulatedInterest = TaxItem("regulated-interest", interest.filter { it.accountType in regulated }.sumOf { it.cents }.coerceAtLeast(0), interest.count { it.accountType in regulated }),
            taxableInterest = TaxItem("taxable-interest", interest.filter { it.accountType !in regulated }.sumOf { it.cents }.coerceAtLeast(0), interest.count { it.accountType !in regulated }),
            donations = donations.copy(estimatedBenefitCents = percent(donations.cents + (help.cents - minOf(help.cents, FiscalRules.DONATION_HELP_CEILING_CENTS)), FiscalRules.DONATION_RATE_PERCENT)),
            donationsHelp = help.copy(estimatedBenefitCents = percent(minOf(help.cents, FiscalRules.DONATION_HELP_CEILING_CENTS), FiscalRules.DONATION_HELP_RATE_PERCENT)),
            homeServices = home.copy(estimatedBenefitCents = percent(minOf(home.cents, FiscalRules.HOME_SERVICES_CEILING_CENTS), FiscalRules.HOME_SERVICES_RATE_PERCENT)),
            childcare = childcare.copy(estimatedBenefitCents = null),
            retirementSavings = item("retirement", setOf("retirement")),
            taxesPaid = item("taxes", setOf("taxes")),
            rules = TaxRules(
                FiscalRules.VERIFIED_ON, FiscalRules.DONATION_RATE_PERCENT, FiscalRules.DONATION_HELP_RATE_PERCENT, FiscalRules.DONATION_HELP_CEILING_CENTS,
                FiscalRules.HOME_SERVICES_RATE_PERCENT, FiscalRules.HOME_SERVICES_CEILING_CENTS, FiscalRules.CHILDCARE_RATE_PERCENT,
                FiscalRules.CHILDCARE_CEILING_PER_CHILD_CENTS,
            ),
        )
    }

    /** Pourcentage entier d'un montant, arrondi à l'euro inférieur comme l'administration. */
    private fun percent(cents: Long, rate: Int): Long =
        BigDecimal(cents).multiply(BigDecimal(rate)).divide(BigDecimal(10_000), 0, RoundingMode.DOWN).multiply(BigDecimal(100)).toLong()

    private data class Row(val cents: Long, val code: String?, val accountType: String)

    /** Opérations de l'année avec le code de leur catégorie (ou de sa catégorie parente), ventilations comprises. */
    private fun rows(from: String, to: String): List<Row> =
        jdbc.sql(
            """
            select t.amount_cents, coalesce(c.code, ''), coalesce(p.code, ''), a.type
            from txn t join account a on a.id = t.account_id
            left join category c on c.id = t.category_id
            left join category p on p.id = c.parent_id
            where t.operation_date between ? and ? and t.internal_transfer = 0
              and not exists (select 1 from txn_split s where s.txn_id = t.id)
            union all
            select s.amount_cents, coalesce(c.code, ''), coalesce(p.code, ''), a.type
            from txn_split s join txn t on t.id = s.txn_id join account a on a.id = t.account_id
            join category c on c.id = s.category_id
            left join category p on p.id = c.parent_id
            where t.operation_date between ? and ?
            """.trimIndent(),
        ).params(from, to, from, to).query { rs, _ ->
            val own = rs.getString(2)
            val parent = rs.getString(3)
            // Une sous-catégorie créée par l'utilisateur sous « Dons » compte comme des dons, etc.
            val code = when {
                own in KNOWN -> own
                parent in KNOWN -> parent
                else -> null
            }
            Row(rs.getLong(1), code, rs.getString(4))
        }.list()

    companion object {
        private val KNOWN = setOf("interest", "donations", "donations-help", "home-services", "childcare", "retirement", "taxes")
    }
}
