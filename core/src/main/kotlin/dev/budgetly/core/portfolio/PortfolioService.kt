package dev.budgetly.core.portfolio

import dev.budgetly.core.imports.ApiException
import dev.budgetly.core.statement.AccountType
import dev.budgetly.core.statement.portfolio.SecurityKind
import org.springframework.http.HttpStatus
import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.math.BigDecimal
import java.math.RoundingMode
import java.time.Clock
import java.time.LocalDate

enum class ValueSource { STATEMENT, ESTIMATED, MANUAL }

data class HoldingView(
    val isin: String?,
    val name: String,
    val kind: SecurityKind,
    val quantity: String?,
    val statementValueCents: Long,
    val unrealizedCents: Long?,
    val price: String?,
    val priceDate: LocalDate?,
    val currentValueCents: Long,
    /** Valorisé avec un cours plus récent que le relevé. */
    val repriced: Boolean,
)

data class EnvelopeView(
    val accountId: Long,
    val type: AccountType,
    val name: String,
    val institution: String,
    val numberMasked: String,
    val statementDate: LocalDate,
    val statementValueCents: Long,
    val cashCents: Long,
    val valueCents: Long,
    val valueSource: ValueSource,
    val valueDate: LocalDate,
    val contributionsCents: Long?,
    val gainCents: Long?,
    val gainRatio: Double?,
    val holdings: List<HoldingView>,
    /** Répartition du dernier relevé par date de disponibilité (épargne salariale), sinon vide. */
    val availability: List<dev.budgetly.core.imports.AvailabilityView> = emptyList(),
    /** Rendement depuis le premier relevé qui donne le cumul des versements ; null si incalculable. */
    val performance: EnvelopePerformance? = null,
)

data class CashAccountView(val accountId: Long, val type: AccountType, val name: String, val balanceCents: Long, val balanceDate: LocalDate)

data class NetWorthPoint(val date: LocalDate, val totalCents: Long, val estimated: Boolean)

/** Patrimoine à la fin d'un mois (ou aujourd'hui pour le mois en cours), comptes et investissements séparés. */
data class MonthlyNetWorth(
    val month: String,
    val date: LocalDate,
    val liquidCents: Long,
    val investedCents: Long,
    val estimated: Boolean,
    /** Capital restant dû des crédits saisis à cette date. */
    val debtCents: Long = 0,
) {
    val totalCents: Long get() = liquidCents + investedCents
    val netCents: Long get() = totalCents - debtCents
}

/** Classes d'actifs, du plus disponible au plus risqué. */
enum class AssetClass { CURRENT_ACCOUNTS, SAVINGS_ACCOUNTS, ENVELOPE_CASH, MONEY_MARKET, EURO_FUNDS, EQUITY_AND_FUNDS, UNALLOCATED }

data class AllocationSlice(val assetClass: AssetClass, val valueCents: Long)

data class NetWorth(
    val totalCents: Long,
    val liquidCents: Long,
    val investedCents: Long,
    val cashAccounts: List<CashAccountView>,
    val envelopes: List<EnvelopeView>,
    val history: List<NetWorthPoint>,
    val monthly: List<MonthlyNetWorth> = emptyList(),
    val allocation: List<AllocationSlice> = emptyList(),
    /** Capital restant dû des crédits saisis aujourd'hui ; [totalCents] reste le total des avoirs. */
    val debtCents: Long = 0,
) {
    val netCents: Long get() = totalCents - debtCents
}

data class ManualValuation(val date: LocalDate, val valueCents: Long)

/**
 * Patrimoine : comptes bancaires (dernier solde de relevé) + enveloppes d'investissement.
 * Une enveloppe vaut, par ordre de préférence : une saisie manuelle plus récente que tout, sinon
 * les parts du dernier relevé × le dernier cours connu (« estimée »), sinon la valeur du relevé.
 */
@Service
class PortfolioService(private val jdbc: JdbcClient, private val clock: Clock, private val loans: dev.budgetly.core.loans.LoanService) {

    private data class Envelope(val id: Long, val type: AccountType, val name: String, val institution: String, val number: String)

    private data class Valuation(val date: LocalDate, val valueCents: Long, val cashCents: Long, val contributionsCents: Long?, val source: String)

    fun netWorth(): NetWorth {
        val cash = cashAccounts()
        val envelopes = envelopes().mapNotNull(::envelopeView)
        val liquid = cash.sumOf { it.balanceCents }
        val invested = envelopes.sumOf { it.valueCents }
        return NetWorth(
            totalCents = liquid + invested,
            liquidCents = liquid,
            investedCents = invested,
            cashAccounts = cash,
            envelopes = envelopes,
            history = history(envelopes),
            monthly = monthly(envelopes),
            allocation = allocation(cash, envelopes),
            debtCents = loans.outstandingAt(LocalDate.now(clock)),
        )
    }

    @Transactional
    fun addManualValuation(accountId: Long, valuation: ManualValuation) {
        val envelope = envelopes().firstOrNull { it.id == accountId }
            ?: throw ApiException(HttpStatus.NOT_FOUND, "NOT_FOUND", "Enveloppe d'investissement introuvable.")
        if (valuation.valueCents < 0) throw ApiException(HttpStatus.BAD_REQUEST, "INVALID_REQUEST", "La valeur doit être positive.")
        if (valuation.date.isAfter(LocalDate.now(clock))) {
            throw ApiException(HttpStatus.BAD_REQUEST, "INVALID_REQUEST", "La date ne peut pas être dans le futur.")
        }
        val contributions = valuations(envelope.id).lastOrNull { it.contributionsCents != null }?.contributionsCents
        jdbc.sql(
            "insert into valuation (account_id, valuation_date, value_cents, cash_cents, contributions_cents, source) values (?, ?, ?, 0, ?, 'MANUAL') " +
                "on conflict(account_id, valuation_date, source) do update set value_cents = excluded.value_cents",
        ).params(envelope.id, valuation.date.toString(), valuation.valueCents, contributions).update()
    }

    private fun envelopeView(envelope: Envelope): EnvelopeView? {
        val all = valuations(envelope.id)
        val statement = all.lastOrNull { it.source == "STATEMENT" } ?: return null
        val manual = all.lastOrNull { it.source == "MANUAL" }
        val holdings = holdings(envelope.id, statement.date)

        val estimatedValue = holdings.sumOf { it.currentValueCents } + statement.cashCents
        val latestPriceDate = holdings.filter { it.repriced }.mapNotNull { it.priceDate }.maxOrNull()
        val (value, source, date) = when {
            manual != null && manual.date >= statement.date && (latestPriceDate == null || manual.date >= latestPriceDate) ->
                Triple(manual.valueCents, ValueSource.MANUAL, manual.date)
            latestPriceDate != null -> Triple(estimatedValue, ValueSource.ESTIMATED, latestPriceDate)
            else -> Triple(statement.valueCents, ValueSource.STATEMENT, statement.date)
        }
        val contributions = all.lastOrNull { it.contributionsCents != null }?.contributionsCents
        val gain = contributions?.let { value - it }
        return EnvelopeView(
            accountId = envelope.id,
            type = envelope.type,
            name = envelope.name,
            institution = envelope.institution,
            numberMasked = "•••• ${envelope.number.takeLast(4)}",
            statementDate = statement.date,
            statementValueCents = statement.valueCents,
            cashCents = statement.cashCents,
            valueCents = value,
            valueSource = source,
            valueDate = date,
            contributionsCents = contributions,
            gainCents = gain,
            gainRatio = if (gain != null && contributions != null && contributions > 0) gain.toDouble() / contributions else null,
            holdings = holdings,
            availability = availability(envelope.id, statement.date),
            performance = Performance.of(
                all.filter { it.source == "STATEMENT" }.map { Performance.Statement(it.date, it.valueCents, it.contributionsCents) },
                date,
                value,
            ),
        )
    }

    /**
     * Fin de chaque mois depuis la première valeur connue : chaque compte pour sa dernière valeur connue à cette
     * date (un compte n'apparaît qu'à partir de son premier relevé). Le mois en cours prend les valeurs actuelles.
     */
    private fun monthly(envelopes: List<EnvelopeView>): List<MonthlyNetWorth> {
        val today = LocalDate.now(clock)
        val types = jdbc.sql("select id, type from account").query { rs, _ -> rs.getLong(1) to AccountType.valueOf(rs.getString(2)) }
            .list().toMap()
        val series = accountSeries().mapValues { (_, points) -> points.map { Triple(it.first, it.second, false) } }.toMutableMap()
        for (e in envelopes.filter { it.valueSource == ValueSource.ESTIMATED }) {
            series[e.accountId] = (series[e.accountId].orEmpty() + Triple(e.valueDate, e.valueCents, true)).sortedBy { it.first }
        }
        val first = listOfNotNull(series.values.flatten().minOfOrNull { it.first }, loans.earliestStart()).minOrNull() ?: return emptyList()
        var month = java.time.YearMonth.from(first)
        val last = java.time.YearMonth.from(today)
        val points = mutableListOf<MonthlyNetWorth>()
        while (month <= last) {
            val date = minOf(month.atEndOfMonth(), today)
            var liquid = 0L
            var invested = 0L
            var estimated = false
            for ((id, s) in series) {
                val point = s.lastOrNull { it.first <= date } ?: continue
                if (types[id]?.let { it in INVESTMENT_TYPES } == true) invested += point.second else liquid += point.second
                estimated = estimated || point.third
            }
            points += MonthlyNetWorth(month.toString(), date, liquid, invested, estimated, loans.outstandingAt(date))
            month = month.plusMonths(1)
        }
        return points.takeLast(120)
    }

    /** Valeurs datées par compte : soldes de relevé des comptes, valorisations des enveloppes. */
    private fun accountSeries(): Map<Long, List<Pair<LocalDate, Long>>> {
        val series = mutableMapOf<Long, List<Pair<LocalDate, Long>>>()
        jdbc.sql("select account_id, closing_date, closing_cents from statement_balance order by closing_date")
            .query { rs, _ -> Triple(rs.getLong(1), LocalDate.parse(rs.getString(2)), rs.getLong(3)) }.list()
            .groupBy { it.first }.forEach { (id, rows) -> series[id] = rows.map { it.second to it.third } }
        jdbc.sql("select account_id, valuation_date, value_cents from valuation order by valuation_date, case source when 'STATEMENT' then 0 else 1 end")
            .query { rs, _ -> Triple(rs.getLong(1), LocalDate.parse(rs.getString(2)), rs.getLong(3)) }.list()
            .groupBy { it.first }.forEach { (id, rows) -> series[id] = rows.map { it.second to it.third } }
        return series
    }

    /**
     * Répartition par classe d'actifs. Une enveloppe se répartit comme les supports de son dernier relevé
     * (valorisés au dernier cours) ; une valeur saisie à la main garde ces proportions.
     */
    private fun allocation(cash: List<CashAccountView>, envelopes: List<EnvelopeView>): List<AllocationSlice> {
        val totals = mutableMapOf<AssetClass, Long>()
        for (account in cash) {
            val assetClass = if (account.type == AccountType.CHECKING) AssetClass.CURRENT_ACCOUNTS else AssetClass.SAVINGS_ACCOUNTS
            totals.merge(assetClass, account.balanceCents, Long::plus)
        }
        for (e in envelopes) {
            val parts = mutableMapOf<AssetClass, Long>()
            e.holdings.forEach { parts.merge(classOf(it), it.currentValueCents, Long::plus) }
            if (e.cashCents != 0L) parts.merge(AssetClass.ENVELOPE_CASH, e.cashCents, Long::plus)
            for ((assetClass, cents) in scaled(parts, e.valueCents)) totals.merge(assetClass, cents, Long::plus)
        }
        return AssetClass.entries.mapNotNull { c -> totals[c]?.takeIf { it != 0L }?.let { AllocationSlice(c, it) } }
    }

    /** Parts ramenées à [total] en centimes entiers ; l'arrondi va à la plus grosse part. */
    private fun scaled(parts: Map<AssetClass, Long>, total: Long): Map<AssetClass, Long> {
        val base = parts.values.sum()
        // Valeur sans supports connus (saisie sans relevé détaillé) : affichée comme non ventilée, pas devinée.
        if (base == 0L) return if (total == 0L) emptyMap() else mapOf(AssetClass.UNALLOCATED to total)
        if (base == total) return parts
        val result = parts.mapValues { (_, cents) ->
            BigDecimal(cents).multiply(BigDecimal(total)).divide(BigDecimal(base), 0, RoundingMode.HALF_UP).toLong()
        }.toMutableMap()
        val largest = result.maxBy { it.value }.key
        result[largest] = result.getValue(largest) + (total - result.values.sum())
        return result
    }

    private fun classOf(holding: HoldingView): AssetClass {
        val name = java.text.Normalizer.normalize(holding.name, java.text.Normalizer.Form.NFD).replace(Regex("\\p{M}"), "").uppercase()
        return when {
            holding.kind == SecurityKind.EURO_FUND -> AssetClass.EURO_FUNDS
            listOf("MONETAIRE", "TRESORERIE", "MONEY MARKET").any { name.contains(it) } -> AssetClass.MONEY_MARKET
            else -> AssetClass.EQUITY_AND_FUNDS
        }
    }

    private fun holdings(accountId: Long, date: LocalDate): List<HoldingView> =
        jdbc.sql(
            """
            select s.isin, s.name, s.kind, p.quantity, p.value_cents, p.unrealized_cents, s.id,
                   (select pr.price from price pr where pr.security_id = s.id order by pr.price_date desc limit 1),
                   (select pr.price_date from price pr where pr.security_id = s.id order by pr.price_date desc limit 1)
            from position_snapshot p join security s on s.id = p.security_id
            where p.account_id = ? and p.valuation_date = ?
            order by p.value_cents desc
            """.trimIndent(),
        ).params(accountId, date.toString()).query { rs, _ ->
            val quantity = rs.getString(4)?.let(::BigDecimal)
            val statementValue = rs.getLong(5)
            val price = rs.getString(8)?.let(::BigDecimal)
            val priceDate = rs.getString(9)?.let(LocalDate::parse)
            val repriced = quantity != null && price != null && priceDate != null && priceDate.isAfter(date)
            val current = if (repriced) {
                quantity!!.multiply(price).movePointRight(2).setScale(0, RoundingMode.HALF_UP).toLong()
            } else {
                statementValue
            }
            HoldingView(
                isin = rs.getString(1),
                name = rs.getString(2),
                kind = SecurityKind.valueOf(rs.getString(3)),
                quantity = quantity?.toPlainString(),
                statementValueCents = statementValue,
                unrealizedCents = rs.getLong(6).takeUnless { rs.wasNull() },
                price = price?.toPlainString(),
                priceDate = priceDate,
                currentValueCents = current,
                repriced = repriced,
            )
        }.list()

    /** Patrimoine aux dates connues : chaque compte compte pour sa dernière valeur connue à cette date. */
    private fun history(envelopes: List<EnvelopeView>): List<NetWorthPoint> {
        val series = accountSeries()

        val dates = series.values.flatten().map { it.first }.toSortedSet()
        val points = dates.map { date ->
            NetWorthPoint(date, series.values.sumOf { s -> s.lastOrNull { it.first <= date }?.second ?: 0L }, estimated = false)
        }.toMutableList()

        val estimated = envelopes.filter { it.valueSource == ValueSource.ESTIMATED }
        val estimateDate = estimated.maxOfOrNull { it.valueDate }
        if (estimateDate != null && (points.isEmpty() || estimateDate > points.last().date)) {
            val total = series.entries.sumOf { (id, s) ->
                envelopes.firstOrNull { it.accountId == id }?.valueCents ?: (s.lastOrNull()?.second ?: 0L)
            }
            points += NetWorthPoint(estimateDate, total, estimated = true)
        }
        return points
    }

    private fun cashAccounts(): List<CashAccountView> =
        jdbc.sql(
            """
            select a.id, a.type, a.name, b.closing_cents, b.closing_date
            from account a join statement_balance b on b.account_id = a.id
            where b.closing_date = (select max(b2.closing_date) from statement_balance b2 where b2.account_id = a.id)
            order by case a.type when 'CHECKING' then 0 else 1 end, a.name
            """.trimIndent(),
        ).query { rs, _ ->
            CashAccountView(rs.getLong(1), AccountType.valueOf(rs.getString(2)), rs.getString(3), rs.getLong(4), LocalDate.parse(rs.getString(5)))
        }.list().distinctBy { it.accountId }

    companion object {
        val INVESTMENT_TYPES = setOf(AccountType.PEA, AccountType.LIFE_INSURANCE, AccountType.PEE)
    }

    private fun envelopes(): List<Envelope> =
        jdbc.sql("select id, type, name, institution, iban from account where type in ('PEA', 'LIFE_INSURANCE', 'PEE') order by type, name")
            .query { rs, _ -> Envelope(rs.getLong(1), AccountType.valueOf(rs.getString(2)), rs.getString(3), rs.getString(4), rs.getString(5)) }
            .list()

    private fun availability(accountId: Long, date: LocalDate): List<dev.budgetly.core.imports.AvailabilityView> =
        jdbc.sql("select available_on, value_cents from availability_snapshot where account_id = ? and valuation_date = ? order by available_on")
            .params(accountId, date.toString())
            .query { rs, _ -> dev.budgetly.core.imports.AvailabilityView(LocalDate.parse(rs.getString(1)), rs.getLong(2)) }
            .list()

    private fun valuations(accountId: Long): List<Valuation> =
        jdbc.sql(
            "select valuation_date, value_cents, cash_cents, contributions_cents, source from valuation where account_id = ? " +
                "order by valuation_date, case source when 'STATEMENT' then 0 else 1 end",
        ).param(accountId).query { rs, _ ->
            Valuation(LocalDate.parse(rs.getString(1)), rs.getLong(2), rs.getLong(3), rs.getLong(4).takeUnless { rs.wasNull() }, rs.getString(5))
        }.list()
}
