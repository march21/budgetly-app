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
import java.time.YearMonth
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
        // Année en cours : jusqu'au dernier mois complet ; année passée : décembre.
        val now = YearMonth.now()
        val through = when {
            now.year > 2026 -> YearMonth.of(2026, 12)
            now.monthValue == 1 -> now
            else -> now.minusMonths(1)
        }
        assertEquals(through.toString(), review["through"].asText())
        val months = (1..through.monthValue).map { YearMonth.of(2026, it) }
        assertEquals(months.sumOf { get("/api/summary?month=$it")["expenseCents"].asLong() }, review["current"]["expenseCents"].asLong())
        assertEquals(months.sumOf { get("/api/summary?month=${it.minusYears(1)}")["expenseCents"].asLong() }, review["previous"]["expenseCents"].asLong())
        assertEquals(review["current"]["expenseCents"].asLong(), review["expenses"].sumOf { it["cents"].asLong() })
        assertTrue(review["increases"].all { it["deltaCents"].asLong() > 0 })
        assertEquals(review["months"].count { it["expenseCents"].asLong() != 0L || it["incomeCents"].asLong() != 0L }, review["monthsWithData"].asInt())
        mvc.get("/api/summary/year?year=1999").andExpect { status { isBadRequest() } }

        val pdf = mvc.get("/api/summary/year/report.pdf?year=2026").andExpect {
            status { isOk() }
            header { string("Content-Type", "application/pdf") }
            header { string("Cache-Control", "no-store") }
        }.andReturn().response.contentAsByteArray
        val text = Loader.loadPDF(pdf).use { PDFTextStripper().getText(it) }
        assertTrue(text.contains("Bilan 2026"), text.take(200))
        assertTrue(text.contains("Dépenses par catégorie"))
        assertTrue(text.contains(AnnualReportPdf.euros(review["current"]["expenseCents"].asLong()).replace(" ", " ")) ||
            text.contains(AnnualReportPdf.euros(review["current"]["expenseCents"].asLong())))
    }

    @Test
    fun `PDF, caractere hors encodage remplace sans erreur`() {
        val line = AnnualLine(1, "Loisirs 🎉 Ж", 12_345, 10_000)
        val totals = AnnualTotals(500_000, 300_000, 50_000, 0.4)
        val review = AnnualReview(
            2025, YearMonth.of(2025, 12), totals, totals, listOf(line), emptyList(), listOf(line), emptyList(),
            emptyList(), 12,
        )
        val text = Loader.loadPDF(AnnualReportPdf.write(review, java.time.Instant.now(), java.time.ZoneId.of("Europe/Paris")))
            .use { PDFTextStripper().getText(it) }
        assertTrue(text.contains("Loisirs ? ?") || text.contains("Loisirs ??"), text)
        assertEquals("1\u00A0234,56\u00A0€", AnnualReportPdf.euros(123_456))
        assertEquals("-0,05\u00A0€", AnnualReportPdf.euros(-5))
        assertEquals("+12,00\u00A0€", AnnualReportPdf.euros(1_200, signed = true))
    }
}
