package dev.budgetly.core.ledger

import dev.budgetly.core.imports.ApiException
import org.springframework.aot.hint.MemberCategory
import org.springframework.aot.hint.RuntimeHints
import org.springframework.aot.hint.RuntimeHintsRegistrar
import org.springframework.context.annotation.ImportRuntimeHints
import org.springframework.http.HttpStatus
import org.springframework.stereotype.Service
import java.time.Clock
import java.time.YearMonth

/** Une catégorie principale sur l'année et sur la même période de l'année précédente (centimes positifs). */
data class AnnualLine(val categoryId: Long?, val name: String, val cents: Long, val previousCents: Long) {
    val deltaCents: Long get() = cents - previousCents
}

data class AnnualMonth(val month: YearMonth, val incomeCents: Long, val expenseCents: Long, val previousIncomeCents: Long, val previousExpenseCents: Long)

data class AnnualTotals(val incomeCents: Long, val expenseCents: Long, val savingsCents: Long, val savingsRate: Double?)

/**
 * Bilan d'une année, comparé à la même période de l'année précédente : pour l'année en cours, de janvier au
 * dernier mois complet, pour que la comparaison porte sur autant de mois entiers.
 */
data class AnnualReview(
    val year: Int,
    val through: YearMonth,
    val current: AnnualTotals,
    val previous: AnnualTotals,
    val expenses: List<AnnualLine>,
    val income: List<AnnualLine>,
    /** Catégories de dépenses en plus forte hausse (5 au plus). */
    val increases: List<AnnualLine>,
    /** Catégories de dépenses en plus forte baisse (5 au plus). */
    val decreases: List<AnnualLine>,
    val months: List<AnnualMonth>,
    val monthsWithData: Int,
)

/** Tableaux d'éléments créés par réflexion à la sérialisation (même précaution que pour les autres listes). */
class AnnualReviewRuntimeHints : RuntimeHintsRegistrar {
    override fun registerHints(hints: RuntimeHints, classLoader: ClassLoader?) {
        listOf(AnnualLine::class.java, AnnualMonth::class.java).forEach { type ->
            hints.reflection().registerType(java.lang.reflect.Array.newInstance(type, 0).javaClass, MemberCategory.UNSAFE_ALLOCATED)
            hints.reflection().registerType(type, MemberCategory.INVOKE_PUBLIC_METHODS, MemberCategory.INVOKE_PUBLIC_CONSTRUCTORS, MemberCategory.DECLARED_FIELDS)
        }
    }
}

@Service
@ImportRuntimeHints(AnnualReviewRuntimeHints::class)
class AnnualReviewService(private val summary: MonthlySummary, private val clock: Clock) {

    fun review(year: Int): AnnualReview {
        val now = YearMonth.now(clock)
        if (year < 2000 || year > now.year) throw ApiException(HttpStatus.BAD_REQUEST, "INVALID_YEAR", "Année inconnue.")
        // Année en cours : jusqu'au dernier mois complet (le mois courant n'est pas fini, souvent pas encore importé).
        // En janvier, faute de mois complet, janvier en cours.
        val through = when {
            year < now.year -> YearMonth.of(year, 12)
            now.monthValue == 1 -> now
            else -> now.minusMonths(1)
        }
        val months = (1..through.monthValue).map { YearMonth.of(year, it) }
        val current = months.map(summary::month)
        val previous = months.map { summary.month(it.minusYears(1)) }

        val expenses = lines(current.flatMap { it.expenses }, previous.flatMap { it.expenses })
        return AnnualReview(
            year = year,
            through = through,
            current = totals(current),
            previous = totals(previous),
            expenses = expenses,
            income = lines(current.flatMap { it.income }, previous.flatMap { it.income }),
            increases = expenses.filter { it.deltaCents > 0 }
                .sortedByDescending { it.deltaCents }.take(5),
            decreases = expenses.filter { it.deltaCents < 0 }
                .sortedBy { it.deltaCents }.take(5),
            months = months.indices.map { i ->
                AnnualMonth(months[i], current[i].incomeCents, current[i].expenseCents, previous[i].incomeCents, previous[i].expenseCents)
            },
            monthsWithData = current.count { it.incomeCents != 0L || it.expenseCents != 0L || it.savingsCents != 0L },
        )
    }

    fun years(): List<Int> = (YearMonth.now(clock).year downTo 2000).take(15)

    private fun totals(months: List<MonthSummary>): AnnualTotals {
        val income = months.sumOf { it.incomeCents }
        val expense = months.sumOf { it.expenseCents }
        return AnnualTotals(income, expense, months.sumOf { it.savingsCents }, if (income > 0) (income - expense).toDouble() / income else null)
    }

    /** Lignes par catégorie principale, triées par montant de l'année. */
    private fun lines(current: List<CategoryLine>, previous: List<CategoryLine>): List<AnnualLine> {
        fun key(l: CategoryLine) = l.categoryId?.toString() ?: "none"
        val names = (previous + current).associate { key(it) to it.name }
        val now = current.groupBy(::key).mapValues { (_, l) -> l.sumOf { it.cents } }
        val before = previous.groupBy(::key).mapValues { (_, l) -> l.sumOf { it.cents } }
        val ids = (previous + current).associate { key(it) to it.categoryId }
        return (now.keys + before.keys).map { k -> AnnualLine(ids[k], names.getValue(k), now[k] ?: 0L, before[k] ?: 0L) }
            .sortedWith(compareByDescending<AnnualLine> { it.cents }.thenByDescending { it.previousCents })
    }
}
