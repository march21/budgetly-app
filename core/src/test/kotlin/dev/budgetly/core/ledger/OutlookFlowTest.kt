package dev.budgetly.core.ledger

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import dev.budgetly.core.TestSupport
import dev.budgetly.core.statement.lbp.LbpStatementFixture
import dev.budgetly.core.statement.lbp.LbpStatementFixture.Op
import dev.budgetly.core.vault.VaultService
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.http.MediaType
import org.springframework.mock.web.MockMultipartFile
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.get
import org.springframework.test.web.servlet.multipart
import org.springframework.test.web.servlet.post
import org.springframework.test.web.servlet.put
import java.time.LocalDate
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** Trois relevés mensuels fictifs : charges fixes, projection de fin de mois, alertes de budget, ventilation et notes. */
@SpringBootTest(
    properties = [
        TestSupport.DATA_DIR, TestSupport.FAST_KDF_MEMORY, TestSupport.FAST_KDF_ITERATIONS, "test.context=outlook",
    ],
)
@AutoConfigureMockMvc
class OutlookFlowTest(
    @Autowired private val mvc: MockMvc,
    @Autowired private val vault: VaultService,
    @Autowired private val json: ObjectMapper,
) {
    private fun get(path: String): JsonNode =
        json.readTree(mvc.get(path).andExpect { status { isOk() } }.andReturn().response.contentAsString)

    private fun statement(month: Int, opening: Long, ops: List<Op>) = LbpStatementFixture.Spec(
        openingDate = LocalDate.of(2026, month, 9),
        closingDate = LocalDate.of(2026, month + 1, 9),
        checking = LbpStatementFixture.Account("Compte Courant Postal n° 12 345 67A 020", LbpStatementFixture.CHECKING_IBAN, opening, ops),
        savings = emptyList(),
    )

    // Deux passages par mois au marché : des courses, pas une charge fixe.
    private fun monthOps(month: Int, groceries: Long) = listOf(
        Op("10/%02d".format(month), "ACHAT CB MARCHE DU QUARTIER %02d.%02d.26".format(9, month), -groceries / 3),
        Op("15/%02d".format(month), "VIREMENT DE SOCIETE EXEMPLE", 250_000),
        Op("20/%02d".format(month), "PRELEVEMENT DE STREAMING EXEMPLE", -1_349),
        Op("25/%02d".format(month), "ACHAT CB MARCHE DU QUARTIER %02d.%02d.26".format(24, month), -groceries),
        Op("02/%02d".format(month + 1), "PRELEVEMENT DE LOYER EXEMPLE", -80_000),
    )

    private fun import(spec: LbpStatementFixture.Spec) {
        val preview = json.readTree(
            mvc.multipart("/api/imports/preview") {
                file(MockMultipartFile("file", "releve.pdf", "application/pdf", LbpStatementFixture.build(spec)))
            }.andExpect { status { isOk() } }.andReturn().response.contentAsString,
        )
        assertEquals("READY", preview["status"].asText(), preview["issues"].toString())
        mvc.post("/api/imports/${preview["previewId"].asText()}/commit").andExpect { status { isOk() } }
    }

    @Test
    fun `charges fixes, projection, alertes de budget, ventilation et notes`() {
        TestSupport.unlocked(vault)
        var balance = 100_000L
        for ((month, groceries) in listOf(7 to 12_000L, 8 to 15_000L, 9 to 9_000L)) {
            val ops = monthOps(month, groceries)
            import(statement(month, balance, ops))
            balance += ops.sumOf { it.cents }
        }

        val recurring = get("/api/recurring")
        assertEquals("2026-10-02", recurring["horizon"].asText())
        val patterns = recurring["series"].map { it["pattern"].asText() }.toSet()
        assertTrue(patterns.containsAll(listOf("STREAMING EXEMPLE", "LOYER EXEMPLE", "VIREMENT DE SOCIETE EXEMPLE")), patterns.toString())
        assertTrue(patterns.none { it.startsWith("MARCHE") }, "achats variables écartés")
        assertEquals(81_349, recurring["monthlyFixedCents"].asLong())
        assertEquals(250_000, recurring["monthlyIncomeCents"].asLong())

        // Écarter une série la retire du total ; la rétablir la remet.
        val streaming = recurring["series"].first { it["pattern"].asText() == "STREAMING EXEMPLE" }["key"].asText()
        fun ignore(value: Boolean) = mvc.put("/api/recurring/ignored") {
            contentType = MediaType.APPLICATION_JSON
            content = json.writeValueAsString(mapOf("key" to streaming, "ignored" to value))
        }.andExpect { status { isNoContent() } }
        ignore(true)
        assertEquals(80_000, get("/api/recurring")["monthlyFixedCents"].asLong())
        assertEquals(1, get("/api/recurring")["ignored"].size())
        ignore(false)

        // Octobre : loyer déjà passé le 2 ; restent salaire (15), streaming (20) et les courses courantes.
        val projection = get("/api/projection?month=2026-10")
        assertEquals("2026-10-02", projection["asOf"].asText())
        assertEquals(29, projection["daysRemaining"].asInt())
        assertEquals(listOf("2026-10-15", "2026-10-20"), projection["expected"].map { it["date"].asText() })
        assertEquals(80_000, projection["actualExpenseCents"].asLong())
        assertTrue(projection["variableEstimateCents"].asLong() > 0)
        assertEquals(balance, projection["checkingBalanceCents"].asLong())
        val variable = projection["variableEstimateCents"].asLong()
        assertEquals(balance + 250_000 - 1_349 - variable, projection["projectedCheckingCents"].asLong())

        // Budget de 500 € sur le logement, dépassé par le loyer.
        val housing = get("/api/categories").first { it["name"].asText() == "Logement" }["id"].asLong()
        val rent = get("/api/transactions?q=LOYER").map { it["id"].asLong() }
        mvc.put("/api/transactions/category") {
            contentType = MediaType.APPLICATION_JSON
            content = """{"transactionIds":$rent,"categoryId":$housing}"""
        }.andExpect { status { isOk() } }
        mvc.put("/api/categories/$housing/budget") {
            contentType = MediaType.APPLICATION_JSON
            content = """{"monthlyCents":50000}"""
        }.andExpect { status { isNoContent() } }
        val alert = get("/api/projection?month=2026-10")["budgetAlerts"].single()
        assertEquals("OVER", alert["status"].asText())
        assertEquals(80_000, alert["spentCents"].asLong())

        // Ventilation des courses du 24 septembre (90 €) : 60 € alimentation, 30 € shopping.
        val categories = get("/api/categories")
        val food = categories.first { it["name"].asText() == "Alimentation" }["id"].asLong()
        val shopping = categories.first { it["name"].asText() == "Shopping" }["id"].asLong()
        val market = get("/api/transactions?q=MARCHE&from=2026-09-20&to=2026-09-30").single()["id"].asLong()
        fun split(body: String) = mvc.put("/api/transactions/$market/splits") {
            contentType = MediaType.APPLICATION_JSON
            content = body
        }
        split("""{"parts":[{"categoryId":$food,"amountCents":-6000},{"categoryId":$shopping,"amountCents":-2000}]}""")
            .andExpect { status { isBadRequest() } }
        split("""{"parts":[{"categoryId":$food,"amountCents":-9000}]}""").andExpect { status { isBadRequest() } }
        split("""{"parts":[{"categoryId":$food,"amountCents":-6000},{"categoryId":$shopping,"amountCents":-3000}]}""")
            .andExpect { status { isNoContent() } }

        val split = get("/api/transactions?q=MARCHE&from=2026-09-20&to=2026-09-30").single()
        assertEquals("SPLIT", split["categorySource"].asText())
        assertEquals(listOf(-6000L, -3000L), split["splits"].map { it["amountCents"].asLong() })
        val september = get("/api/summary?month=2026-09")["expenses"]
        assertEquals(6_000, september.first { it["categoryId"].asLong() == food }["cents"].asLong())
        assertEquals(3_000, september.first { it["categoryId"].asLong() == shopping }["cents"].asLong())
        assertTrue(get("/api/transactions?uncategorized=true").none { it["id"].asLong() == market })
        assertTrue(get("/api/transactions?categoryId=$shopping").any { it["id"].asLong() == market })
        // Une nouvelle règle ne défait pas une ventilation.
        mvc.post("/api/rules") {
            contentType = MediaType.APPLICATION_JSON
            content = """{"pattern":"MARCHE DU QUARTIER","categoryId":$food}"""
        }.andExpect { status { isOk() } }
        assertEquals("SPLIT", get("/api/transactions?q=MARCHE&from=2026-09-20&to=2026-09-30").single()["categorySource"].asText())

        // Note libre, retrouvée par la recherche ; ventilation défaite par une liste vide.
        mvc.put("/api/transactions/$market/note") {
            contentType = MediaType.APPLICATION_JSON
            content = """{"note":"Cadeau d'anniversaire inclus"}"""
        }.andExpect { status { isNoContent() } }
        assertEquals(market, get("/api/transactions?q=anniversaire").single()["id"].asLong())
        split("""{"parts":[]}""").andExpect { status { isNoContent() } }
        assertEquals("RULE", get("/api/transactions?q=anniversaire").single()["categorySource"].asText())
    }
}
