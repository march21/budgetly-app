package dev.budgetly.core.ledger

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import dev.budgetly.core.TestSupport
import dev.budgetly.core.statement.lbp.LbpStatementFixture
import dev.budgetly.core.vault.VaultService
import org.apache.pdfbox.Loader
import org.apache.pdfbox.text.PDFTextStripper
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.mock.web.MockMultipartFile
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.get
import org.springframework.test.web.servlet.multipart
import org.springframework.test.web.servlet.post
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** Bilan annuel : totaux, comparaison à la même période de l'année précédente, PDF généré localement. */
@SpringBootTest(properties = [TestSupport.DATA_DIR, TestSupport.FAST_KDF_MEMORY, TestSupport.FAST_KDF_ITERATIONS, "test.context=annual"])
@AutoConfigureMockMvc
class AnnualReviewFlowTest(
    @Autowired private val mvc: MockMvc,
    @Autowired private val vault: VaultService,
    @Autowired private val json: ObjectMapper,
) {
    private fun get(path: String): JsonNode = json.readTree(mvc.get(path).andExpect { status { isOk() } }.andReturn().response.contentAsString)

    @Test
    fun `bilan 2026 et son PDF`() {
        TestSupport.unlocked(vault)
        val preview = json.readTree(
            mvc.multipart("/api/imports/preview") {
                file(MockMultipartFile("file", "r.pdf", "application/pdf", LbpStatementFixture.build(LbpStatementFixture.standard())))
            }.andReturn().response.contentAsString,
        )
        mvc.post("/api/imports/${preview["previewId"].asText()}/commit").andExpect { status { isOk() } }

        val review = get("/api/summary/year?year=2026")
        assertEquals(2026, review["year"].asInt())
        val october = get("/api/summary?month=2026-10")
        val september = get("/api/summary?month=2026-09")
        assertEquals(october["expenseCents"].asLong() + september["expenseCents"].asLong(), review["current"]["expenseCents"].asLong())
        assertEquals(0, review["previous"]["expenseCents"].asLong())
        assertEquals(review["current"]["expenseCents"].asLong(), review["expenses"].sumOf { it["cents"].asLong() })
        assertTrue(review["increases"].size() in 1..5)
        assertTrue(review["increases"].all { it["deltaCents"].asLong() > 0 })
        assertEquals(2, review["monthsWithData"].asInt())
        mvc.get("/api/summary/year?year=1999").andExpect { status { isBadRequest() } }

        val pdf = mvc.get("/api/summary/year/report.pdf?year=2026").andExpect {
            status { isOk() }
            header { string("Content-Type", "application/pdf") }
        }.andReturn().response.contentAsByteArray
        val text = Loader.loadPDF(pdf).use { PDFTextStripper().getText(it) }
        assertTrue(text.contains("Bilan 2026"), text.take(200))
        assertTrue(text.contains("Dépenses par catégorie"))
        assertTrue(text.contains(AnnualReportPdf.euros(review["current"]["expenseCents"].asLong()).replace(" ", " ")) ||
            text.contains(AnnualReportPdf.euros(review["current"]["expenseCents"].asLong())))
    }
}
