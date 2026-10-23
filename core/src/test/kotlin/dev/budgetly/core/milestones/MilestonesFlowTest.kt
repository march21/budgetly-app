package dev.budgetly.core.milestones

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import dev.budgetly.core.TestSupport
import dev.budgetly.core.statement.easybourse.EasyBourseFixtures
import dev.budgetly.core.statement.lbp.LbpStatementFixture
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

/** Repères : échéances PEA et assurance vie d'après la date d'ouverture saisie, plafonds PEA et livrets. */
@SpringBootTest(
    properties = [
        TestSupport.DATA_DIR, TestSupport.FAST_KDF_MEMORY, TestSupport.FAST_KDF_ITERATIONS,
        "budgetly.prices.yahoo.enabled=false", "test.context=milestones",
    ],
)
@AutoConfigureMockMvc
class MilestonesFlowTest(
    @Autowired private val mvc: MockMvc,
    @Autowired private val vault: VaultService,
    @Autowired private val json: ObjectMapper,
) {
    private fun get(path: String): JsonNode = json.readTree(mvc.get(path).andExpect { status { isOk() } }.andReturn().response.contentAsString)

    private fun import(pdf: ByteArray) {
        val preview = json.readTree(
            mvc.multipart("/api/imports/preview") { file(MockMultipartFile("file", "r.pdf", "application/pdf", pdf)) }
                .andExpect { status { isOk() } }.andReturn().response.contentAsString,
        )
        mvc.post("/api/imports/${preview["previewId"].asText()}/commit").andExpect { status { isOk() } }
    }

    private fun openedOn(accountId: Long, date: String) =
        mvc.put("/api/accounts/$accountId/opened-on") {
            contentType = MediaType.APPLICATION_JSON
            content = """{"date":"$date"}"""
        }

    @Test
    fun `echeances et plafonds`() {
        TestSupport.unlocked(vault)
        import(LbpStatementFixture.build(LbpStatementFixture.standard()))
        import(EasyBourseFixtures.pea())
        import(EasyBourseFixtures.lifeInsurance())

        var report = get("/api/milestones")
        assertEquals("2026-10-23", report["rules"]["verifiedOn"].asText())
        assertEquals(15_000_000, report["rules"]["peaCeilingCents"].asLong())
        var byKind = report["milestones"].groupBy { it["kind"].asText() }
        assertEquals("NEEDS_OPENING_DATE", byKind.getValue("PEA_MATURITY").single()["status"].asText())
        assertEquals("NEEDS_OPENING_DATE", byKind.getValue("LIFE_INSURANCE_MATURITY").single()["status"].asText())
        val peaCeiling = byKind.getValue("PEA_CEILING").single()
        assertEquals(EasyBourseFixtures.PEA_CONTRIBUTIONS, peaCeiling["amountCents"].asLong())
        assertEquals("UPCOMING", peaCeiling["status"].asText())
        assertEquals(2, byKind.getValue("SAVINGS_CEILING").size, "Livret A et LDDS")
        assertTrue(byKind.getValue("SAVINGS_CEILING").all { it["limitCents"].asLong() in setOf(2_295_000L, 1_200_000L) })

        val pea = byKind.getValue("PEA_MATURITY").single()["accountId"].asLong()
        val life = byKind.getValue("LIFE_INSURANCE_MATURITY").single()["accountId"].asLong()
        val today = LocalDate.now()
        val peaOpened = today.minusYears(5).plusMonths(3)
        openedOn(pea, peaOpened.toString()).andExpect { status { isNoContent() } }
        openedOn(life, today.minusYears(9).toString()).andExpect { status { isNoContent() } }
        openedOn(life, today.plusDays(1).toString()).andExpect { status { isBadRequest() } }
        val checking = get("/api/accounts").first { it["type"].asText() == "CHECKING" }["id"].asLong()
        openedOn(checking, "2020-01-01").andExpect { status { isBadRequest() } }

        report = get("/api/milestones")
        byKind = report["milestones"].groupBy { it["kind"].asText() }
        val peaMaturity = byKind.getValue("PEA_MATURITY").single()
        assertEquals("SOON", peaMaturity["status"].asText())
        assertEquals(peaOpened.plusYears(5).toString(), peaMaturity["date"].asText())
        assertEquals("REACHED", byKind.getValue("LIFE_INSURANCE_MATURITY").single()["status"].asText())
        // Les repères proches viennent en tête.
        assertEquals("SOON", report["milestones"].first()["status"].asText())
    }
}
