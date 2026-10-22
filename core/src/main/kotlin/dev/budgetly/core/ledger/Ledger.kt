package dev.budgetly.core.ledger

import dev.budgetly.core.categories.CategoryKind
import dev.budgetly.core.categories.CategorySource
import dev.budgetly.core.categories.RuleEngine
import dev.budgetly.core.imports.maskIban
import dev.budgetly.core.statement.AccountType
import dev.budgetly.core.statement.TransactionKind
import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.stereotype.Service
import java.time.LocalDate

data class AccountView(
    val id: Long,
    val institution: String,
    val type: AccountType,
    val name: String,
    val ibanMasked: String,
    val balanceCents: Long?,
    val balanceDate: LocalDate?,
    val transactionCount: Int,
)

data class TransactionView(
    val id: Long,
    val accountId: Long,
    val accountName: String,
    val bookingDate: LocalDate,
    val operationDate: LocalDate,
    val amountCents: Long,
    val label: String,
    val details: String,
    val kind: TransactionKind,
    val internalTransfer: Boolean,
    val categoryId: Long?,
    val categoryName: String?,
    val categoryKind: CategoryKind?,
    val categorySource: CategorySource?,
    /** Motif proposé pour une règle si l'utilisateur reclasse l'opération. */
    val suggestedPattern: String,
    val note: String? = null,
    /** Ventilation sur plusieurs catégories (vide si l'opération n'est pas ventilée). */
    val splits: List<SplitView> = emptyList(),
)

data class SplitView(val categoryId: Long, val categoryName: String, val categoryKind: CategoryKind, val amountCents: Long)

data class TransactionFilter(
    val accountId: Long? = null,
    val from: LocalDate? = null,
    val to: LocalDate? = null,
    val query: String? = null,
    val categoryId: Long? = null,
    val uncategorizedOnly: Boolean = false,
    val limit: Int = 500,
)

@Service
class Ledger(private val jdbc: JdbcClient) {

    /** Comptes avec le solde du dernier relevé importé. */
    fun accounts(): List<AccountView> =
        jdbc.sql(
            """
            select a.id, a.institution, a.type, a.name, a.iban,
                   coalesce(
                     (select b.closing_cents from statement_balance b where b.account_id = a.id order by b.closing_date desc limit 1),
                     (select v.value_cents from valuation v where v.account_id = a.id order by v.valuation_date desc, v.source desc limit 1)),
                   coalesce(
                     (select b.closing_date from statement_balance b where b.account_id = a.id order by b.closing_date desc limit 1),
                     (select v.valuation_date from valuation v where v.account_id = a.id order by v.valuation_date desc, v.source desc limit 1)),
                   (select count(*) from txn t where t.account_id = a.id)
            from account a
            order by case a.type when 'CHECKING' then 0 when 'PEA' then 2 when 'LIFE_INSURANCE' then 3 when 'PEE' then 4 else 1 end, a.name
            """.trimIndent(),
        ).query { rs, _ ->
            AccountView(
                id = rs.getLong(1),
                institution = rs.getString(2),
                type = AccountType.valueOf(rs.getString(3)),
                name = rs.getString(4),
                ibanMasked = AccountType.valueOf(rs.getString(3)).let { type ->
                    if (type in setOf(AccountType.PEA, AccountType.LIFE_INSURANCE, AccountType.PEE)) "•••• ${rs.getString(5).takeLast(4)}" else maskIban(rs.getString(5))
                },
                balanceCents = rs.getLong(6).takeUnless { rs.wasNull() },
                balanceDate = rs.getString(7)?.let(LocalDate::parse),
                transactionCount = rs.getInt(8),
            )
        }.list()

    /** Opérations, de la plus récente à la plus ancienne, filtrées par compte, période (date d'opération) et texte. */
    fun transactions(filter: TransactionFilter): List<TransactionView> {
        val where = mutableListOf<String>()
        val params = mutableListOf<Any>()
        filter.accountId?.let { where += "t.account_id = ?"; params += it }
        filter.from?.let { where += "t.operation_date >= ?"; params += it.toString() }
        filter.to?.let { where += "t.operation_date <= ?"; params += it.toString() }
        filter.query?.trim()?.takeIf { it.isNotEmpty() }?.let {
            where += "(t.label like ? or t.details like ? or t.note like ?)"
            repeat(3) { _ -> params += "%$it%" }
        }
        filter.categoryId?.let {
            where += "(t.category_id = ? or t.category_id in (select id from category where parent_id = ?) or exists (" +
                "select 1 from txn_split s where s.txn_id = t.id and (s.category_id = ? or s.category_id in (select id from category where parent_id = ?))))"
            repeat(4) { _ -> params += it }
        }
        if (filter.uncategorizedOnly) where += "t.category_id is null and not exists (select 1 from txn_split s where s.txn_id = t.id)"
        val clause = if (where.isEmpty()) "" else "where " + where.joinToString(" and ")
        params += filter.limit.coerceIn(1, 1_000_000)
        val rows = jdbc.sql(
            """
            select t.id, t.account_id, a.name, t.booking_date, t.operation_date, t.amount_cents,
                   t.label, t.details, t.kind, t.internal_transfer,
                   t.category_id, c.name, c.kind, t.category_source, t.note
            from txn t join account a on a.id = t.account_id
            left join category c on c.id = t.category_id
            $clause
            order by t.operation_date desc, t.id desc
            limit ?
            """.trimIndent(),
        ).params(params).query { rs, _ ->
            TransactionView(
                id = rs.getLong(1),
                accountId = rs.getLong(2),
                accountName = rs.getString(3),
                bookingDate = LocalDate.parse(rs.getString(4)),
                operationDate = LocalDate.parse(rs.getString(5)),
                amountCents = rs.getLong(6),
                label = rs.getString(7),
                details = rs.getString(8),
                kind = TransactionKind.valueOf(rs.getString(9)),
                internalTransfer = rs.getInt(10) == 1,
                categoryId = rs.getLong(11).takeUnless { rs.wasNull() },
                categoryName = rs.getString(12),
                categoryKind = rs.getString(13)?.let(CategoryKind::valueOf),
                categorySource = rs.getString(14)?.let(CategorySource::valueOf),
                suggestedPattern = RuleEngine.suggestPattern(rs.getString(7)),
                note = rs.getString(15),
            )
        }.list()
        return withSplits(rows)
    }

    private fun withSplits(rows: List<TransactionView>): List<TransactionView> {
        val split = rows.filter { it.categorySource == CategorySource.SPLIT }.map { it.id }
        if (split.isEmpty()) return rows
        val parts = split.chunked(500).flatMap { chunk ->
            jdbc.sql(
                "select s.txn_id, s.category_id, c.name, c.kind, s.amount_cents from txn_split s join category c on c.id = s.category_id " +
                    "where s.txn_id in (${chunk.joinToString(",") { "?" }}) order by s.txn_id, s.position",
            ).params(chunk).query { rs, _ ->
                rs.getLong(1) to SplitView(rs.getLong(2), rs.getString(3), CategoryKind.valueOf(rs.getString(4)), rs.getLong(5))
            }.list()
        }.groupBy({ it.first }, { it.second })
        return rows.map { t -> parts[t.id]?.let { t.copy(splits = it) } ?: t }
    }
}
