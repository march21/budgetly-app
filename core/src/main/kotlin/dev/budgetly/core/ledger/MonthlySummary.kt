package dev.budgetly.core.ledger

import dev.budgetly.core.categories.CategoryKind
import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.stereotype.Service
import java.time.YearMonth

data class CategoryLine(
    val categoryId: Long?,
    val name: String,
    /** Montant net en centimes, positif (dépense nette pour une catégorie de dépenses, revenu net sinon). */
    val cents: Long,
    val budgetCents: Long?,
    val transactionCount: Int,
    val children: List<CategoryLine>,
)

data class MonthSummary(
    val month: YearMonth,
    val incomeCents: Long,
    val expenseCents: Long,
    /** Versements vers les enveloppes d'épargne (AV, PEE…), qui ne sont pas des dépenses. */
    val savingsCents: Long,
    /** Revenus − dépenses − versements d'épargne : ce qui reste (ou manque) sur les comptes. */
    val remainingCents: Long,
    /** (Revenus − dépenses) / revenus : part des revenus non dépensée, versements d'épargne compris. */
    val savingsRate: Double?,
    val expenses: List<CategoryLine>,
    val income: List<CategoryLine>,
    val uncategorizedCount: Int,
    val internalTransferCount: Int,
)

data class MonthPoint(val month: YearMonth, val incomeCents: Long, val expenseCents: Long, val savingsCents: Long)

data class SummaryHistory(
    val months: List<MonthPoint>,
    val averageIncomeCents: Long,
    val averageExpenseCents: Long,
    val averageSavingsCents: Long,
    /** (Revenus − dépenses) / revenus sur les mois renseignés ; null sans revenus. */
    val averageSavingsRate: Double? = null,
)

/**
 * Bilan d'un mois, par date d'opération. Une opération ventilée compte pour chacune de ses parts.
 * Les virements entre mes comptes sont exclus ;
 * une opération non classée compte comme dépense (débit) ou revenu (crédit) jusqu'à ce qu'on la classe.
 */
@Service
class MonthlySummary(private val jdbc: JdbcClient) {

    private data class Row(
        val amountCents: Long,
        val categoryId: Long?,
        val categoryName: String?,
        val categoryKind: CategoryKind?,
        val parentId: Long?,
        val parentName: String?,
        val internalTransfer: Boolean,
    ) {
        val effectiveKind: CategoryKind?
            get() = categoryKind ?: if (internalTransfer) CategoryKind.TRANSFER else null
    }

    fun month(month: YearMonth): MonthSummary {
        val rows = rows(month.atDay(1).toString(), month.atEndOfMonth().toString())
        val budgets = budgets()

        val counted = rows.filter { it.effectiveKind != CategoryKind.TRANSFER }
        val expenseRows = counted.filter { it.effectiveKind == CategoryKind.EXPENSE || (it.effectiveKind == null && it.amountCents < 0) }
        val incomeRows = counted.filter { it.effectiveKind == CategoryKind.INCOME || (it.effectiveKind == null && it.amountCents >= 0) }
        val savingsRows = counted.filter { it.effectiveKind == CategoryKind.SAVINGS }

        val income = incomeRows.sumOf { it.amountCents }
        val expenses = -expenseRows.sumOf { it.amountCents }
        val savings = -savingsRows.sumOf { it.amountCents }

        return MonthSummary(
            month = month,
            incomeCents = income,
            expenseCents = expenses,
            savingsCents = savings,
            remainingCents = income - expenses - savings,
            savingsRate = if (income > 0) (income - expenses).toDouble() / income else null,
            expenses = lines(expenseRows, budgets, sign = -1),
            income = lines(incomeRows, budgets, sign = 1),
            uncategorizedCount = counted.count { it.categoryId == null },
            internalTransferCount = rows.size - counted.size,
        )
    }

    fun history(last: YearMonth, count: Int): SummaryHistory {
        val months = (count - 1 downTo 0).map { last.minusMonths(it.toLong()) }
        val points = months.map { m ->
            val s = month(m)
            MonthPoint(m, s.incomeCents, s.expenseCents, s.savingsCents)
        }
        val withData = points.filter { it.incomeCents != 0L || it.expenseCents != 0L || it.savingsCents != 0L }
        fun avg(selector: (MonthPoint) -> Long) = if (withData.isEmpty()) 0L else withData.sumOf(selector) / withData.size
        val income = withData.sumOf { it.incomeCents }
        val rate = if (income > 0) (income - withData.sumOf { it.expenseCents }).toDouble() / income else null
        return SummaryHistory(points, avg { it.incomeCents }, avg { it.expenseCents }, avg { it.savingsCents }, rate)
    }

    /** Regroupe par catégorie principale, avec le détail des sous-catégories ; budget = somme des budgets du groupe. */
    private fun lines(rows: List<Row>, budgets: Map<Long, Long>, sign: Int): List<CategoryLine> =
        rows.groupBy { it.parentId ?: it.categoryId }
            .map { (topId, group) ->
                val first = group.first()
                val topName = first.parentName ?: first.categoryName ?: "À classer"
                val children = if (topId == null) {
                    emptyList()
                } else {
                    group.filter { it.parentId != null }.groupBy { it.categoryId }.map { (childId, items) ->
                        CategoryLine(childId, items.first().categoryName!!, sign * items.sumOf { it.amountCents }, budgets[childId], items.size, emptyList())
                    }.sortedByDescending { it.cents }
                }
                val ownBudget = topId?.let { budgets[it] }
                val childBudgets = children.mapNotNull { it.budgetCents }
                val budget = if (ownBudget == null && childBudgets.isEmpty()) null else (ownBudget ?: 0) + childBudgets.sum()
                CategoryLine(topId, topName, sign * group.sumOf { it.amountCents }, budget, group.size, children)
            }
            .sortedWith(compareBy<CategoryLine> { it.categoryId == null }.thenByDescending { it.cents })

    private fun rows(from: String, to: String): List<Row> =
        jdbc.sql(
            """
            select t.amount_cents, c.id, c.name, c.kind, p.id, p.name, t.internal_transfer
            from txn t
            left join category c on c.id = t.category_id
            left join category p on p.id = c.parent_id
            where t.operation_date between ? and ? and not exists (select 1 from txn_split s where s.txn_id = t.id)
            union all
            select s.amount_cents, c.id, c.name, c.kind, p.id, p.name, t.internal_transfer
            from txn_split s
            join txn t on t.id = s.txn_id
            join category c on c.id = s.category_id
            left join category p on p.id = c.parent_id
            where t.operation_date between ? and ?
            """.trimIndent(),
        ).params(from, to, from, to).query { rs, _ ->
            Row(
                amountCents = rs.getLong(1),
                categoryId = rs.getLong(2).takeUnless { rs.wasNull() },
                categoryName = rs.getString(3),
                categoryKind = rs.getString(4)?.let(CategoryKind::valueOf),
                parentId = rs.getLong(5).takeUnless { rs.wasNull() },
                parentName = rs.getString(6),
                internalTransfer = rs.getInt(7) == 1,
            )
        }.list()

    private fun budgets(): Map<Long, Long> =
        jdbc.sql("select category_id, monthly_cents from budget").query { rs, _ -> rs.getLong(1) to rs.getLong(2) }.list().toMap()
}
