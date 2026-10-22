package dev.budgetly.core.portfolio

import com.fasterxml.jackson.databind.ObjectMapper
import org.slf4j.LoggerFactory
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.stereotype.Component
import java.math.BigDecimal
import java.net.URI
import java.net.URLEncoder
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.nio.charset.StandardCharsets
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset

data class PricePoint(val date: LocalDate, val price: BigDecimal)

data class Quote(val symbol: String, val currency: String, val points: List<PricePoint>)

/**
 * Source de cours. Règle de confidentialité : on n'envoie que l'ISIN (ou le symbole qui en découle),
 * jamais une quantité, un montant ou un identifiant de compte.
 */
interface PriceProvider {
    val id: String

    /** Symbole de l'instrument chez la source, ou null s'il est inconnu. */
    fun resolve(isin: String): String?

    /** Cours de clôture quotidiens depuis `from`, ou null en cas d'échec. */
    fun history(symbol: String, from: LocalDate): Quote?

    /** Diagnostic de bout en bout pour un ISIN public (outil de développement et smoke test de la CI). */
    fun diagnose(isin: String): Map<String, Any?> = try {
        val symbol = resolve(isin)
        val quote = symbol?.let { history(it, LocalDate.now().minusDays(10)) }
        mapOf("provider" to id, "symbol" to symbol, "currency" to quote?.currency, "points" to (quote?.points?.size ?: 0), "error" to null)
    } catch (e: Throwable) {
        mapOf("provider" to id, "symbol" to null, "currency" to null, "points" to 0, "error" to e.javaClass.name)
    }
}

/**
 * Yahoo Finance, via ses points d'accès publics non officiels (recherche et historique).
 * Gratuit mais sans garantie de stabilité : en cas d'échec, l'application garde le dernier cours connu.
 */
@Component
@ConditionalOnProperty(name = ["budgetly.prices.yahoo.enabled"], havingValue = "true", matchIfMissing = true)
class YahooPriceProvider(private val json: ObjectMapper) : PriceProvider {
    override val id = "yahoo"

    private val log = LoggerFactory.getLogger(javaClass)
    private val http: HttpClient = HttpClient.newBuilder()
        .connectTimeout(Duration.ofSeconds(8))
        .followRedirects(HttpClient.Redirect.NORMAL)
        .build()

    override fun resolve(isin: String): String? {
        val body = get("https://query2.finance.yahoo.com/v1/finance/search?q=${encode(isin)}&quotesCount=5&newsCount=0&listsCount=0")
            ?: return null
        val quotes = json.readTree(body).path("quotes")
        val usable = quotes.filter { it.path("quoteType").asText() in setOf("EQUITY", "ETF", "MUTUALFUND") }
        // À choisir, une cotation européenne en euros (Paris, Amsterdam, Francfort, Milan…).
        val preferred = usable.firstOrNull { q -> europeanSuffixes.any { q.path("symbol").asText().endsWith(it) } } ?: usable.firstOrNull()
        return preferred?.path("symbol")?.asText()?.takeIf { it.isNotBlank() }
    }

    override fun history(symbol: String, from: LocalDate): Quote? {
        val start = from.atStartOfDay().toEpochSecond(ZoneOffset.UTC)
        val end = Instant.now().epochSecond
        val body = get("https://query2.finance.yahoo.com/v8/finance/chart/${encode(symbol)}?period1=$start&period2=$end&interval=1d")
            ?: return null
        val result = json.readTree(body).path("chart").path("result").firstOrNull() ?: return null
        val meta = result.path("meta")
        val offset = meta.path("gmtoffset").asLong(0)
        val timestamps = result.path("timestamp")
        val closes = result.path("indicators").path("quote").firstOrNull()?.path("close") ?: return null
        val points = (0 until timestamps.size()).mapNotNull { i ->
            val close = closes.get(i)
            if (close == null || close.isNull) return@mapNotNull null
            val date = Instant.ofEpochSecond(timestamps.get(i).asLong() + offset).atZone(ZoneOffset.UTC).toLocalDate()
            PricePoint(date, BigDecimal(close.asText()).stripTrailingZeros())
        }.distinctBy { it.date }
        return Quote(symbol, meta.path("currency").asText(), points)
    }

    private fun get(url: String): String? = try {
        val request = HttpRequest.newBuilder(URI.create(url))
            .timeout(Duration.ofSeconds(10))
            .header("User-Agent", "Mozilla/5.0")
            .header("Accept", "application/json")
            .GET()
            .build()
        val response = http.send(request, HttpResponse.BodyHandlers.ofString())
        if (response.statusCode() == 200) response.body() else null.also { log.info("Source de cours : réponse {}", response.statusCode()) }
    } catch (e: Exception) {
        log.info("Source de cours injoignable : {}", e.javaClass.simpleName)
        lastError = e.javaClass.name + (e.cause?.let { " / " + it.javaClass.name } ?: "")
        null
    }

    @Volatile
    private var lastError: String? = null

    override fun diagnose(isin: String): Map<String, Any?> {
        lastError = null
        val result = super.diagnose(isin)
        return if (result["error"] == null && lastError != null) result + ("error" to lastError) else result
    }

    private fun encode(value: String) = URLEncoder.encode(value, StandardCharsets.UTF_8)

    private companion object {
        val europeanSuffixes = listOf(".PA", ".AS", ".F", ".DE", ".MI", ".BR", ".AMS")
    }
}
