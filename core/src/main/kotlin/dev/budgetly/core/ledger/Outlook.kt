package dev.budgetly.core.ledger

import dev.budgetly.core.categories.CategoryKind
import dev.budgetly.core.imports.ApiException
import org.springframework.aot.hint.MemberCategory
import org.springframework.aot.hint.RuntimeHints
import org.springframework.aot.hint.RuntimeHintsRegistrar
import org.springframework.context.annotation.ImportRuntimeHints
import org.springframework.http.HttpStatus
import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.stereotype.Service
import java.time.Clock
import java.time.LocalDate
import java.time.YearMonth
import java.time.temporal.ChronoUnit

data class RecurringOverview(
    /** Date de la dernière opération connue : tout est calculé par rapport à elle. */
    val horizon: LocalDate?,
    /** Charges fixes actives, par mois (positif). */
    val monthlyFixedCents: Long,
    /** Revenus récurrents actifs, par mois. */
    val monthlyIncomeCents: Long,
    val series: List<RecurringSeries>,
    val ignored: List<RecurringSeries>,
)

data class ExpectedItem(val label: String, val date: LocalDate, val amountCents: Long, val savings: Boolean)

enum class BudgetStatus { WATCH, AT_RISK, OVER }

data class BudgetAlert(val categoryId: Long, val name: String, val spentCents: Long, val budgetCents: Long, val projectedCents: Long, val status: BudgetStatus)

data class MonthProjection(
    val month: YearMonth,
    /** Dernière opération connue dans le mois (null si le mois n'a encore aucune opération importée). */
    val asOf: LocalDate?,
    val daysRemaining: Int,
    val actualIncomeCents: Long,
    val actualExpenseCents: Long,
    val actualSavingsCents: Long,
    /** Occurrences attendues des séries récurrentes d'ici la fin du mois. */
    val expected: List<ExpectedItem>,
    /** Dépenses courantes (hors récurrentes) estimées pour les jours restants, d'après les trois derniers mois. */
    val variableEstimateCents: Long,
    val projectedIncomeCents: Long,
    val projectedExpenseCents: Long,
    val projectedSavingsCents: Long,
    /** Solde des comptes courants au dernier relevé. */
    val checkingBalanceCents: Long?,
    /** Solde attendu des comptes courants en fin de mois. */
    val projectedCheckingCents: Long?,
    val budgetAlerts: List<BudgetAlert>,
)

/**
 * Charges fixes et projection de fin de mois. Tout repose sur les relevés importés : sans relevé récent,
 * la projection part de la dernière date connue et l'indique.
 */
/** Tableaux d'éléments créés par réflexion à la sérialisation (manque constaté en natif sur ExpectedItem[]). */
class OutlookRuntimeHints : RuntimeHintsRegistrar {
    override fun registerHints(hints: RuntimeHints, classLoader: ClassLoader?) {
        listOf(ExpectedItem::class.java, BudgetAlert::class.java, RecurringSeries::class.java, SplitView::class.java).forEach { type ->
            hints.reflection().registerType(java.lang.reflect.Array.newInstance(type, 0).javaClass, MemberCategory.UNSAFE_ALLOCATED)
            hints.reflection().registerType(type, MemberCategory.INVOKE_PUBLIC_METHODS, MemberCategory.INVOKE_PUBLIC_CONSTRUCTORS, MemberCategory.DECLARED_FIELDS)
        }
    }
}

@Service
@ImportRuntimeHints(OutlookRuntimeHints::class)
class Outlook(private val jdbc: JdbcClient, private val summary: MonthlySummary, private val clock: Clock) {

    fun recurring(): RecurringOverview {
        val horizon = horizon() ?: return RecurringOverview(null, 0, 0, emptyList(), emptyList())
        val all = RecurringDetector.detect(candidates(horizon.minusDays(400)), horizon)
        val ignored = ignoredKeys()
        val kept = all.filter { it.key !in ignored }
        val active = kept.filter { it.status == SeriesStatus.ACTIVE && it.categoryKind != CategoryKind.SAVINGS.name }
        return RecurringOverview(
            horizon = horizon,
            monthlyFixedCents = -active.filter { !it.credit }.sumOf { it.typicalAmountCents },
            monthlyIncomeCents = active.filter { it.credit }.sumOf { it.typicalAmountCents },
            series = kept,
            ignored = all.filter { it.key in ignored },
        )
    }

    fun ignore(key: String, ignored: Boolean) {
        if (key.isBlank() || key.length > 300) throw ApiException(HttpStatus.BAD_REQUEST, "INVALID_REQUEST", "Série inconnue.")
        if (ignored) {
            jdbc.sql("insert into recurring_ignored (series_key, created_at) values (?, ?) on conflict do nothing")
                .params(key, clock.instant().toString()).update()
        } else {
            jdbc.sql("delete from recurring_ignored where series_key = ?").param(key).update()
        }
    }

    fun projection(month: YearMonth): MonthProjection {
        val start = month.atDay(1)
        val end = month.atEndOfMonth()
        val horizon = horizon()
        val asOf = horizon?.takeIf { !it.isBefore(start) }?.let { minOf(it, end) }
        val from = asOf ?: start.minusDays(1)
        val daysRemaining = ChronoUnit.DAYS.between(from, end).toInt().coerceAtLeast(0)

        val actual = summary.month(month)
        val overview = recurring()
        val active = overview.series.filter { it.status == SeriesStatus.ACTIVE }

        // Prochaines occurrences de chaque série entre la dernière date connue et la fin du mois.
        val expected = active.flatMap { s ->
            generateSequence(s.nextDate) { if (s.monthly) it.plusMonths(1) else it.plusDays(s.intervalDays.toLong()) }
                .takeWhile { !it.isAfter(end) }
                .filter { it.isAfter(from) && !it.isBefore(start) }
                .map { ExpectedItem(s.label, it, s.typicalAmountCents, s.categoryKind == CategoryKind.SAVINGS.name) }
                .toList()
        }.sortedBy { it.date }

        val recurringIds = overview.series.flatMap { it.transactionIds }.toSet()
        val variableDaily = variableDailySpending(month, recurringIds)
        val variableEstimate = variableDaily * daysRemaining

        val expectedIncome = expected.filter { it.amountCents > 0 }.sumOf { it.amountCents }
        val expectedSavings = -expected.filter { it.savings && it.amountCents < 0 }.sumOf { it.amountCents }
        val expectedExpense = -expected.filter { !it.savings && it.amountCents < 0 }.sumOf { it.amountCents }
        val projectedExpense = actual.expenseCents + expectedExpense + variableEstimate

        val checking = checkingBalance()
        val projectedChecking = checking?.let { it + expectedIncome - expectedExpense - expectedSavings - variableEstimate }

        return MonthProjection(
            month = month,
            asOf = asOf,
            daysRemaining = daysRemaining,
            actualIncomeCents = actual.incomeCents,
            actualExpenseCents = actual.expenseCents,
            actualSavingsCents = actual.savingsCents,
            expected = expected,
            variableEstimateCents = variableEstimate,
            projectedIncomeCents = actual.incomeCents + expectedIncome,
            projectedExpenseCents = projectedExpense,
            projectedSavingsCents = actual.savingsCents + expectedSavings,
            checkingBalanceCents = checking,
            projectedCheckingCents = projectedChecking,
            budgetAlerts = budgetAlerts(actual, start, end, asOf),
        )
    }

    /**
     * Alertes de budget : dépassé, en passe de l'être au rythme actuel, ou consommé à 80 %.
     * Le rythme n'est extrapolé qu'après le premier cinquième du mois (trop instable avant).
     */
    private fun budgetAlerts(actual: MonthSummary, start: LocalDate, end: LocalDate, asOf: LocalDate?): List<BudgetAlert> {
        val elapsed = if (asOf == null) 0.0 else (ChronoUnit.DAYS.between(start, asOf) + 1).toDouble() / (ChronoUnit.DAYS.between(start, end) + 1)
        val lines = actual.expenses.flatMap { listOf(it) + it.children }
        return lines.mapNotNull { line ->
            val budget = line.budgetCents ?: return@mapNotNull null
            val id = line.categoryId ?: return@mapNotNull null
            if (budget <= 0) return@mapNotNull null
            val projected = if (elapsed >= 0.2) Math.round(line.cents / elapsed) else line.cents
            val status = when {
                line.cents > budget -> BudgetStatus.OVER
                projected > budget -> BudgetStatus.AT_RISK
                line.cents >= budget * 0.8 -> BudgetStatus.WATCH
                else -> return@mapNotNull null
            }
            BudgetAlert(id, line.name, line.cents, budget, projected, status)
        }.distinctBy { it.categoryId }.sortedByDescending { it.spentCents.toDouble() / it.budgetCents }
    }

    /** Dépense courante par jour (hors séries récurrentes et virements) sur les trois mois complets précédents qui ont des données. */
    private fun variableDailySpending(month: YearMonth, recurringIds: Set<Long>): Long {
        val months = (1L..3L).map { month.minusMonths(it) }
        val rows = jdbc.sql(
            """
            select t.id, t.amount_cents, t.operation_date from txn t left join category c on c.id = t.category_id
            where t.operation_date between ? and ? and t.amount_cents < 0 and t.internal_transfer = 0
              and (c.kind is null or c.kind = 'EXPENSE')
            """.trimIndent(),
        ).params(months.last().atDay(1).toString(), months.first().atEndOfMonth().toString())
            .query { rs, _ -> Triple(rs.getLong(1), rs.getLong(2), YearMonth.from(LocalDate.parse(rs.getString(3)))) }
            .list()
            .filter { it.first !in recurringIds }
        val withData = rows.map { it.third }.distinct()
        if (withData.isEmpty()) return 0
        val days = withData.sumOf { it.lengthOfMonth() }
        return -rows.sumOf { it.second } / days
    }

    private fun horizon(): LocalDate? =
        jdbc.sql("select max(operation_date) from txn").query(String::class.java).optional().orElse(null)?.let(LocalDate::parse)

    private fun checkingBalance(): Long? =
        jdbc.sql(
            """
            select sum(b.closing_cents), count(*) from account a join statement_balance b on b.account_id = a.id
            where a.type = 'CHECKING' and b.closing_date = (select max(b2.closing_date) from statement_balance b2 where b2.account_id = a.id)
            """.trimIndent(),
        ).query { rs, _ -> if (rs.getInt(2) == 0) null else rs.getLong(1) }.optional().orElse(null)

    private fun ignoredKeys(): Set<String> = jdbc.sql("select series_key from recurring_ignored").query(String::class.java).list().toSet()

    private fun candidates(since: LocalDate): List<RecurringCandidate> =
        jdbc.sql(
            """
            select t.id, t.operation_date, t.amount_cents, t.label, c.name, c.kind from txn t
            left join category c on c.id = t.category_id
            where t.operation_date >= ? and t.internal_transfer = 0 and (c.kind is null or c.kind <> 'TRANSFER')
            """.trimIndent(),
        ).param(since.toString()).query { rs, _ ->
            RecurringCandidate(rs.getLong(1), LocalDate.parse(rs.getString(2)), rs.getLong(3), rs.getString(4), rs.getString(5), rs.getString(6))
        }.list()
}
