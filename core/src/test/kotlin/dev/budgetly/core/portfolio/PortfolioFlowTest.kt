package dev.budgetly.core.portfolio

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import dev.budgetly.core.TestSupport
import dev.budgetly.core.statement.easybourse.EasyBourseFixtures
import dev.budgetly.core.vault.VaultService
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.context.TestConfiguration
import org.springframework.context.annotation.Bean
import org.springframework.http.MediaType
import org.springframework.mock.web.MockMultipartFile
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.get
import org.springframework.test.web.servlet.multipart
import org.springframework.test.web.servlet.post
import java.math.BigDecimal
import java.time.LocalDate
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** Source de cours de test : aucune requête réseau. */
class FakePriceProvider : PriceProvider {
    override val id = "fake"
    val requested = mutableListOf<String>()

    override fun resolve(isin: String): String? {
        requested += isin
        return mapOf("FR0000000011" to "EX.PA", "FR0000000029" to "WRONGCLASS.F")[isin]
    }

    override fun history(symbol: String, from: LocalDate): Quote = when (symbol) {
        // Cours cohérent avec le relevé (8,50 au 30/09) : accepté.
        "EX.PA" -> Quote(symbol, "EUR", listOf(PricePoint(LocalDate.of(2026, 9, 30), BigDecimal("8.52")), PricePoint(LocalDate.of(2026, 10, 15), BigDecimal("9.00"))))
        // Mauvaise part : VL sans rapport avec celle du relevé (68,28) → écarté.
        else -> Quote(symbol, "EUR", listOf(PricePoint(LocalDate.of(2026, 9, 30), BigDecimal("20.00"))))
    }
}

@SpringBootTest(
    properties = [
        TestSupport.DATA_DIR, TestSupport.FAST_KDF_MEMORY, TestSupport.FAST_KDF_ITERATIONS,
        "budgetly.prices.yahoo.enabled=false", "test.context=portfolio",
    ],
)
@AutoConfigureMockMvc
class PortfolioFlowTest(
    @Autowired private val mvc: MockMvc,
    @Autowired private val vault: VaultService,
    @Autowired private val json: ObjectMapper,
    @Autowired private val prices: FakePriceProvider,
) {
    @TestConfiguration
    class Prices {
        @Bean
        fun fakePriceProvider() = FakePriceProvider()
    }

    @BeforeEach
    fun unlock() = TestSupport.unlocked(vault)

    private fun read(body: String): JsonNode = json.readTree(body)

    private fun get(path: String) = read(mvc.get(path).andExpect { status { isOk() } }.andReturn().response.contentAsString)

    private fun preview(pdf: ByteArray) = read(
        mvc.multipart("/api/imports/preview") { file(MockMultipartFile("file", "r.pdf", "application/pdf", pdf)) }
            .andExpect { status { isOk() } }.andReturn().response.contentAsString,
    )

    private fun commit(preview: JsonNode) = read(
        mvc.post("/api/imports/${preview["previewId"].asText()}/commit").andExpect { status { isOk() } }.andReturn().response.contentAsString,
    )

    @Test
    fun `import PEA et assurance vie, cours par ISIN, estimation, saisie manuelle, patrimoine`() {
        val pea = EasyBourseFixtures.pea()
        val peaPreview = preview(pea)
        assertEquals("PORTFOLIO", peaPreview["documentType"].asText())
        assertEquals("READY", peaPreview["status"].asText())
        assertTrue(peaPreview["portfolio"]["balanced"].asBoolean())
        assertEquals(3, peaPreview["portfolio"]["positions"].size())
        assertEquals(3, commit(peaPreview)["impact"]["positionsRecorded"].asInt())
        assertEquals("ALREADY_IMPORTED", preview(pea)["status"].asText())

        var worth = get("/api/portfolio")
        var envelope = worth["envelopes"].single()
        assertEquals("STATEMENT", envelope["valueSource"].asText())
        assertEquals(EasyBourseFixtures.peaTotal, envelope["valueCents"].asLong())
        assertEquals(EasyBourseFixtures.peaTotal - EasyBourseFixtures.PEA_CONTRIBUTIONS, envelope["gainCents"].asLong())

        // Cours : seuls les ISIN partent vers la source ; une mauvaise part est écartée.
        val report = read(mvc.post("/api/portfolio/prices/refresh").andExpect { status { isOk() } }.andReturn().response.contentAsString)
        assertEquals(3, report["checked"].asInt())
        assertEquals(1, report["found"].asInt())
        val statuses = report["results"].associate { it["isin"].asText() to it["status"].asText() }
        assertEquals("FOUND", statuses["FR0000000011"])
        assertEquals("MISMATCH", statuses["FR0000000029"])
        assertEquals("NOT_FOUND", statuses["LU0000000035"])
        assertTrue(prices.requested.all { it.matches(Regex("^[A-Z]{2}[A-Z0-9]{9}\\d$")) })

        worth = get("/api/portfolio")
        envelope = worth["envelopes"].single()
        assertEquals("ESTIMATED", envelope["valueSource"].asText())
        assertEquals("2026-10-15", envelope["valueDate"].asText())
        val expected = EasyBourseFixtures.peaTotal - 102_000 + 120 * 900
        assertEquals(expected, envelope["valueCents"].asLong())

        // Saisie manuelle plus récente que tout : elle fait foi.
        mvc.post("/api/portfolio/${envelope["accountId"].asLong()}/valuations") {
            contentType = MediaType.APPLICATION_JSON
            content = """{"date":"2026-10-20","valueCents":300000}"""
        }.andExpect { status { isNoContent() } }
        envelope = get("/api/portfolio")["envelopes"].single()
        assertEquals("MANUAL", envelope["valueSource"].asText())
        assertEquals(300_000, envelope["valueCents"].asLong())

        // Assurance vie : fonds euros + UC, cumul des versements.
        val lifePreview = preview(EasyBourseFixtures.lifeInsurance())
        assertEquals("READY", lifePreview["status"].asText())
        commit(lifePreview)
        worth = get("/api/portfolio")
        assertEquals(2, worth["envelopes"].size())
        val life = worth["envelopes"].single { it["type"].asText() == "LIFE_INSURANCE" }
        assertEquals(EasyBourseFixtures.avTotal, life["valueCents"].asLong())
        assertEquals(194_500, life["contributionsCents"].asLong())
        // Un seul relevé, valeur à sa date : rendement incalculable, champ présent (sérialisé, natif compris).
        assertTrue(life.has("performance") && life["performance"].isNull)
        assertEquals(300_000 + EasyBourseFixtures.avTotal, worth["investedCents"].asLong())
        assertTrue(worth["history"].size() >= 2)

        // Répartition : fonds en euros de l'assurance vie à part, total égal au patrimoine.
        val allocation = worth["allocation"].associate { it["assetClass"].asText() to it["valueCents"].asLong() }
        assertTrue((allocation["EURO_FUNDS"] ?: 0) > 0, allocation.toString())
        assertTrue((allocation["EQUITY_AND_FUNDS"] ?: 0) > 0, allocation.toString())
        assertEquals(worth["totalCents"].asLong(), allocation.values.sum())

        // Fin de mois : le mois en cours vaut le patrimoine actuel, comptes et investissements séparés.
        val monthly = worth["monthly"]
        assertTrue(monthly.size() >= 1)
        assertEquals(worth["totalCents"].asLong(), monthly.last()["totalCents"].asLong())
        assertEquals(worth["investedCents"].asLong(), monthly.last()["investedCents"].asLong())
        assertTrue(monthly.zipWithNext().all { (a, b) -> a["month"].asText() < b["month"].asText() })

        // Les enveloppes apparaissent avec leur valeur dans la liste des comptes.
        val accounts = get("/api/accounts")
        assertTrue(accounts.any { it["type"].asText() == "PEA" && it["balanceCents"].asLong() > 0 })
    }
}
