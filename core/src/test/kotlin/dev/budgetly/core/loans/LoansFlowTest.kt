package dev.budgetly.core.loans

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import dev.budgetly.core.TestSupport
import dev.budgetly.core.statement.lbp.LbpStatementFixture
import dev.budgetly.core.vault.VaultService
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.http.MediaType
import org.springframework.mock.web.MockMultipartFile
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.delete
import org.springframework.test.web.servlet.get
import org.springframework.test.web.servlet.multipart
import org.springframework.test.web.servlet.post
import org.springframework.test.web.servlet.put
import java.time.LocalDate
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** Crédits : capital restant dû, prochaine échéance, déduction du patrimoine, échéances retrouvées. */
@SpringBootTest(
    properties = [
        TestSupport.DATA_DIR, TestSupport.FAST_KDF_MEMORY, TestSupport.FAST_KDF_ITERATIONS,
        "budgetly.prices.yahoo.enabled=false", "test.context=loans",
    ],
)
@AutoConfigureMockMvc
class LoansFlowTest(
    @Autowired private val mvc: MockMvc,
    @Autowired private val vault: VaultService,
    @Autowired private val json: ObjectMapper,
) {
    private fun get(path: String): JsonNode = json.readTree(mvc.get(path).andExpect { status { isOk() } }.andReturn().response.contentAsString)

    private fun body(value: Any) = json.writeValueAsString(value)

    @Test
    fun `credit immobilier et pret a taux zero`() {
        TestSupport.unlocked(vault)
        val preview = json.readTree(
            mvc.multipart("/api/imports/preview") {
                file(MockMultipartFile("file", "r.pdf", "application/pdf", LbpStatementFixture.build(LbpStatementFixture.standard())))
            }.andReturn().response.contentAsString,
        )
        mvc.post("/api/imports/${preview["previewId"].asText()}/commit").andExpect { status { isOk() } }

        val today = LocalDate.now()
        val first = today.minusMonths(2)
        mvc.post("/api/loans") {
            contentType = MediaType.APPLICATION_JSON
            content = body(mapOf("name" to "Résidence principale", "lender" to "Banque Exemple", "principalCents" to 20_000_000, "annualRate" to "3,00", "firstPaymentDate" to first.toString(), "termMonths" to 240, "insuranceMonthlyCents" to 2_500))
        }.andExpect { status { isCreated() } }
        // Prêt à taux zéro dont l'échéance (89 €) correspond au prélèvement du relevé fictif.
        mvc.post("/api/loans") {
            contentType = MediaType.APPLICATION_JSON
            content = body(mapOf("name" to "Prêt travaux", "principalCents" to 89_000, "annualRate" to "0", "firstPaymentDate" to "2026-08-11", "termMonths" to 10, "paymentLabel" to "fournisseur energie"))
        }.andExpect { status { isCreated() } }
        for (invalid in listOf(
            mapOf("name" to "X", "principalCents" to 100, "annualRate" to "abc", "firstPaymentDate" to "2026-01-01", "termMonths" to 12),
            mapOf("name" to "X", "principalCents" to 100, "annualRate" to "25", "firstPaymentDate" to "2026-01-01", "termMonths" to 12),
            mapOf("name" to "X", "principalCents" to 100, "annualRate" to "1", "firstPaymentDate" to "2026-01-01", "termMonths" to 0),
            mapOf("name" to " ", "principalCents" to 100, "annualRate" to "1", "firstPaymentDate" to "2026-01-01", "termMonths" to 12),
        )) {
            mvc.post("/api/loans") { contentType = MediaType.APPLICATION_JSON; content = body(invalid) }.andExpect { status { isBadRequest() } }
        }

        val loans = get("/api/loans")
        val home = loans.single { it["name"].asText() == "Résidence principale" }
        assertEquals(110_920, home["paymentCents"].asLong())
        assertEquals("3", home["annualRate"].asText())
        assertEquals(3, home["paidInstallments"].asInt())
        val schedule = get("/api/loans/${home["id"].asLong()}/schedule")
        assertEquals(240, schedule.size())
        assertEquals(schedule[2]["remainingCents"].asLong(), home["remainingCents"].asLong())
        assertEquals(schedule[3]["date"].asText(), home["next"]["date"].asText())
        assertEquals(schedule.take(3).sumOf { it["interestCents"].asLong() }, home["interestPaidCents"].asLong())

        val works = loans.single { it["name"].asText() == "Prêt travaux" }
        assertEquals(8_900, works["paymentCents"].asLong())
        assertEquals(1, works["matched"]["count"].asInt())
        assertEquals("2026-09-11", works["matched"]["lastDate"].asText())

        // Patrimoine net : avoirs moins capital restant dû, aussi en fin de mois.
        val worth = get("/api/portfolio")
        val debt = loans.sumOf { it["remainingCents"].asLong() }
        assertEquals(debt, worth["debtCents"].asLong())
        assertEquals(worth["totalCents"].asLong() - debt, worth["netCents"].asLong())
        assertEquals(debt, worth["monthly"].last()["debtCents"].asLong())
        assertTrue(worth["monthly"].first()["month"].asText() <= first.minusMonths(1).toString().take(7))

        // Modification et suppression.
        mvc.put("/api/loans/${works["id"].asLong()}") {
            contentType = MediaType.APPLICATION_JSON
            content = body(mapOf("name" to "Prêt travaux", "principalCents" to 89_000, "annualRate" to "0", "firstPaymentDate" to "2026-08-11", "termMonths" to 5))
        }.andExpect { status { isNoContent() } }
        assertEquals(17_800, get("/api/loans").single { it["name"].asText() == "Prêt travaux" }["paymentCents"].asLong())
        mvc.delete("/api/loans/${works["id"].asLong()}").andExpect { status { isNoContent() } }
        mvc.get("/api/loans/${works["id"].asLong()}/schedule").andExpect { status { isNotFound() } }
        assertEquals(1, get("/api/loans").size())
    }
}
