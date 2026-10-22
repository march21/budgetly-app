package dev.budgetly.core.portfolio

import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.math.BigDecimal
import java.math.MathContext
import java.time.Clock
import java.time.Instant
import java.time.LocalDate
import java.time.temporal.ChronoUnit

enum class PriceStatus { FOUND, NOT_FOUND, MISMATCH, ERROR, UNAVAILABLE }

data class PriceResult(
    val isin: String,
    val name: String,
    val status: PriceStatus,
    val symbol: String?,
    val lastPrice: String?,
    val lastDate: LocalDate?,
)

data class PriceReport(val checked: Int, val found: Int, val results: List<PriceResult>)

/**
 * Récupère les cours des supports cotés détenus, par ISIN. Garde-fous : cours en euros uniquement,
 * et cohérent (±25 %) avec le dernier cours imprimé sur un relevé — sinon le symbole est écarté
 * (mauvaise part ou mauvais instrument).
 */
@Service
class PriceService(
    private val providers: List<PriceProvider>,
    private val jdbc: JdbcClient,
    private val clock: Clock,
) {
    private data class Held(val securityId: Long, val isin: String, val name: String)

    private data class Reference(val date: LocalDate, val price: BigDecimal)

    @Transactional
    fun refresh(): PriceReport {
        val held = jdbc.sql(
            """
            select distinct s.id, s.isin, s.name from security s
            join position_snapshot p on p.security_id = s.id
            where s.isin is not null and s.kind <> 'EURO_FUND'
              and p.valuation_date = (select max(p2.valuation_date) from position_snapshot p2 where p2.account_id = p.account_id)
            order by s.name
            """.trimIndent(),
        ).query { rs, _ -> Held(rs.getLong(1), rs.getString(2), rs.getString(3)) }.list()

        val provider = providers.firstOrNull()
        val results = held.map { security ->
            if (provider == null) PriceResult(security.isin, security.name, PriceStatus.UNAVAILABLE, null, null, null)
            else refreshOne(provider, security)
        }
        return PriceReport(results.size, results.count { it.status == PriceStatus.FOUND }, results)
    }

    private fun refreshOne(provider: PriceProvider, security: Held): PriceResult {
        val now = clock.instant()
        val cached = jdbc.sql("select symbol, status, checked_at from price_symbol where security_id = ?")
            .param(security.securityId)
            .query { rs, _ -> Triple(rs.getString(1), rs.getString(2), Instant.parse(rs.getString(3))) }
            .optional().orElse(null)
        val recentlyMissing = cached != null && cached.second != PriceStatus.FOUND.name &&
            cached.third.isAfter(now.minus(30, ChronoUnit.DAYS))
        if (recentlyMissing) {
            return result(security, PriceStatus.valueOf(cached!!.second), cached.first)
        }

        val symbol = if (cached?.second == PriceStatus.FOUND.name) cached.first else provider.resolve(security.isin)
        if (symbol == null) {
            remember(security, provider, null, PriceStatus.NOT_FOUND, now)
            return result(security, PriceStatus.NOT_FOUND, null)
        }
        val reference = reference(security.securityId)
        val from = (reference?.date ?: LocalDate.now(clock).minusDays(30)).minusDays(5)
        val quote = provider.history(symbol, from)
        if (quote == null || quote.points.isEmpty()) {
            return result(security, PriceStatus.ERROR, symbol)
        }
        if (!quote.currency.equals("EUR", ignoreCase = true) || !consistent(quote, reference)) {
            remember(security, provider, symbol, PriceStatus.MISMATCH, now)
            return result(security, PriceStatus.MISMATCH, symbol)
        }
        for (point in quote.points) {
            // Le cours imprimé sur un relevé reste la référence pour sa date.
            jdbc.sql(
                "insert into price (security_id, price_date, price, source) values (?, ?, ?, ?) " +
                    "on conflict(security_id, price_date) do update set price = excluded.price, source = excluded.source " +
                    "where price.source <> 'STATEMENT'",
            ).params(security.securityId, point.date.toString(), point.price.toPlainString(), provider.id).update()
        }
        remember(security, provider, symbol, PriceStatus.FOUND, now)
        return result(security, PriceStatus.FOUND, symbol)
    }

    /** Le cours de la source autour de la date du relevé doit rester à ±25 % du cours imprimé. */
    private fun consistent(quote: Quote, reference: Reference?): Boolean {
        if (reference == null) return true
        val near = quote.points.minByOrNull { kotlin.math.abs(ChronoUnit.DAYS.between(it.date, reference.date)) } ?: return false
        if (kotlin.math.abs(ChronoUnit.DAYS.between(near.date, reference.date)) > 10) return true
        if (reference.price.signum() == 0) return false
        val ratio = near.price.divide(reference.price, MathContext.DECIMAL64).toDouble()
        return ratio in 0.8..1.25
    }

    private fun reference(securityId: Long): Reference? =
        jdbc.sql("select price_date, price from price where security_id = ? and source = 'STATEMENT' order by price_date desc limit 1")
            .param(securityId)
            .query { rs, _ -> Reference(LocalDate.parse(rs.getString(1)), BigDecimal(rs.getString(2))) }
            .optional().orElse(null)

    private fun remember(security: Held, provider: PriceProvider, symbol: String?, status: PriceStatus, now: Instant) {
        jdbc.sql(
            "insert into price_symbol (security_id, provider, symbol, status, checked_at) values (?, ?, ?, ?, ?) " +
                "on conflict(security_id) do update set provider = excluded.provider, symbol = excluded.symbol, " +
                "status = excluded.status, checked_at = excluded.checked_at",
        ).params(security.securityId, provider.id, symbol, status.name, now.toString()).update()
    }

    private fun result(security: Held, status: PriceStatus, symbol: String?): PriceResult {
        val last = jdbc.sql("select price, price_date from price where security_id = ? order by price_date desc limit 1")
            .param(security.securityId)
            .query { rs, _ -> rs.getString(1) to LocalDate.parse(rs.getString(2)) }
            .optional().orElse(null)
        return PriceResult(security.isin, security.name, status, symbol, last?.first, last?.second)
    }
}
