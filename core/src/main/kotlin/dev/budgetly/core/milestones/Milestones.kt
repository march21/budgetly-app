package dev.budgetly.core.milestones

import dev.budgetly.core.imports.ApiException
import dev.budgetly.core.portfolio.PortfolioService
import dev.budgetly.core.statement.AccountType
import org.springframework.http.HttpStatus
import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.stereotype.Service
import java.time.Clock
import java.time.LocalDate

enum class MilestoneKind {
    /** PEA : 5 ans depuis l'ouverture. */
    PEA_MATURITY,
    /** Assurance vie : 8 ans depuis l'ouverture. */
    LIFE_INSURANCE_MATURITY,
    /** PEE : prochaine date de déblocage d'avoirs. */
    PEE_RELEASE,
    /** Versements PEA face au plafond. */
    PEA_CEILING,
    /** Solde d'un Livret A ou d'un LDDS face au plafond des versements. */
    SAVINGS_CEILING,
}

enum class MilestoneStatus {
    /** Date d'ouverture inconnue : à saisir pour calculer l'échéance. */
    NEEDS_OPENING_DATE,
    UPCOMING,
    /** Échéance à moins de 6 mois, ou plafond à 90 % ou plus. */
    SOON,
    REACHED,
}

/** Un repère : une échéance datée ou un plafond. Les textes sont composés par l'interface. */
data class Milestone(
    val accountId: Long,
    val accountName: String,
    val accountType: AccountType,
    val kind: MilestoneKind,
    val status: MilestoneStatus,
    val date: LocalDate? = null,
    val openedOn: LocalDate? = null,
    val amountCents: Long? = null,
    val limitCents: Long? = null,
)

data class FiscalReference(
    val verifiedOn: LocalDate,
    val peaCeilingCents: Long,
    val livretACeilingCents: Long,
    val lddsCeilingCents: Long,
    val lifeInsuranceAllowanceSingleCents: Long,
    val lifeInsuranceAllowanceCoupleCents: Long,
    val socialLeviesGeneral: Double,
    val socialLeviesLifeInsurance: Double,
)

data class MilestoneReport(val milestones: List<Milestone>, val rules: FiscalReference)

data class OpeningDate(val date: LocalDate?)

/** Échéances fiscales des enveloppes et plafonds des livrets, d'après les comptes suivis et leurs relevés. */
@Service
class MilestoneService(private val jdbc: JdbcClient, private val portfolio: PortfolioService, private val clock: Clock) {

    private data class Account(val id: Long, val name: String, val type: AccountType, val openedOn: LocalDate?)

    fun report(): MilestoneReport {
        val today = LocalDate.now(clock)
        val worth = portfolio.netWorth()
        val accounts = jdbc.sql("select id, name, type, opened_on from account order by name")
            .query { rs, _ -> Account(rs.getLong(1), rs.getString(2), AccountType.valueOf(rs.getString(3)), rs.getString(4)?.let(LocalDate::parse)) }
            .list()
        val milestones = mutableListOf<Milestone>()
        for (a in accounts) {
            val envelope = worth.envelopes.firstOrNull { it.accountId == a.id }
            when (a.type) {
                AccountType.PEA -> {
                    milestones += maturity(a, MilestoneKind.PEA_MATURITY, FiscalRules.PEA_YEARS, today)
                    envelope?.contributionsCents?.let { paid ->
                        milestones += ceiling(a, MilestoneKind.PEA_CEILING, paid, FiscalRules.PEA_CEILING_CENTS)
                    }
                }
                AccountType.LIFE_INSURANCE -> milestones += maturity(a, MilestoneKind.LIFE_INSURANCE_MATURITY, FiscalRules.LIFE_INSURANCE_YEARS, today)
                AccountType.PEE -> envelope?.availability?.firstOrNull { it.availableOn > today }?.let { next ->
                    milestones += Milestone(
                        a.id, a.name, a.type, MilestoneKind.PEE_RELEASE, statusFor(next.availableOn, today),
                        date = next.availableOn, amountCents = next.valueCents,
                    )
                }
                AccountType.LIVRET_A, AccountType.LDDS -> worth.cashAccounts.firstOrNull { it.accountId == a.id }?.let { cash ->
                    val limit = if (a.type == AccountType.LIVRET_A) FiscalRules.LIVRET_A_CEILING_CENTS else FiscalRules.LDDS_CEILING_CENTS
                    milestones += ceiling(a, MilestoneKind.SAVINGS_CEILING, cash.balanceCents, limit)
                }
                else -> Unit
            }
        }
        val order = listOf(MilestoneStatus.SOON, MilestoneStatus.NEEDS_OPENING_DATE, MilestoneStatus.UPCOMING, MilestoneStatus.REACHED)
        return MilestoneReport(
            milestones.sortedWith(compareBy({ order.indexOf(it.status) }, { it.date ?: LocalDate.MAX })),
            FiscalReference(
                FiscalRules.VERIFIED_ON, FiscalRules.PEA_CEILING_CENTS, FiscalRules.LIVRET_A_CEILING_CENTS, FiscalRules.LDDS_CEILING_CENTS,
                FiscalRules.LIFE_INSURANCE_ALLOWANCE_SINGLE_CENTS, FiscalRules.LIFE_INSURANCE_ALLOWANCE_COUPLE_CENTS,
                FiscalRules.SOCIAL_LEVIES_GENERAL, FiscalRules.SOCIAL_LEVIES_LIFE_INSURANCE,
            ),
        )
    }

    fun setOpeningDate(accountId: Long, request: OpeningDate) {
        val type = jdbc.sql("select type from account where id = ?").param(accountId).query(String::class.java).optional()
            .orElseThrow { ApiException(HttpStatus.NOT_FOUND, "NOT_FOUND", "Compte introuvable.") }
        if (AccountType.valueOf(type) !in setOf(AccountType.PEA, AccountType.LIFE_INSURANCE, AccountType.PEE)) {
            throw ApiException(HttpStatus.BAD_REQUEST, "INVALID_REQUEST", "La date d'ouverture ne concerne que les enveloppes d'investissement.")
        }
        if (request.date != null && request.date.isAfter(LocalDate.now(clock))) {
            throw ApiException(HttpStatus.BAD_REQUEST, "INVALID_REQUEST", "La date d'ouverture ne peut pas être dans le futur.")
        }
        jdbc.sql("update account set opened_on = ? where id = ?").params(request.date?.toString(), accountId).update()
    }

    private fun maturity(a: Account, kind: MilestoneKind, years: Long, today: LocalDate): Milestone {
        val opened = a.openedOn ?: return Milestone(a.id, a.name, a.type, kind, MilestoneStatus.NEEDS_OPENING_DATE)
        val date = opened.plusYears(years)
        return Milestone(a.id, a.name, a.type, kind, statusFor(date, today), date = date, openedOn = opened)
    }

    private fun ceiling(a: Account, kind: MilestoneKind, amount: Long, limit: Long): Milestone {
        val status = when {
            amount >= limit -> MilestoneStatus.REACHED
            amount >= limit * FiscalRules.NEAR_CEILING_RATIO -> MilestoneStatus.SOON
            else -> MilestoneStatus.UPCOMING
        }
        return Milestone(a.id, a.name, a.type, kind, status, amountCents = amount, limitCents = limit)
    }

    private fun statusFor(date: LocalDate, today: LocalDate) = when {
        !date.isAfter(today) -> MilestoneStatus.REACHED
        !date.isAfter(today.plusMonths(FiscalRules.SOON_MONTHS)) -> MilestoneStatus.SOON
        else -> MilestoneStatus.UPCOMING
    }
}
