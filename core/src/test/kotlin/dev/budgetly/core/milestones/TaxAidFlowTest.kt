package dev.budgetly.core.milestones

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
import org.springframework.test.web.servlet.get
import org.springframework.test.web.servlet.multipart
import org.springframework.test.web.servlet.post
import org.springframework.test.web.servlet.put
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** Aide à la déclaration : totaux par poste d'après les catégories, avantages estimés au taux légal. */
@SpringBootTest(properties = [TestSupport.DATA_DIR, TestSupport.FAST_KDF_MEMORY, TestSupport.FAST_KDF_ITERATIONS, "test.context=tax-aid"])
@AutoConfigureMockMvc
class TaxAidFlowTest(
    @Autowired private val mvc: MockMvc,
    @Autowired private val vault: VaultService,
    @Autowired private val json: ObjectMapper,
) {
    private fun get(path: String): JsonNode = json.readTree(mvc.get(path).andExpect { status { isOk() } }.andReturn().response.contentAsString)

    @Test
    fun `dons, interets et impots de l'annee`() {
        TestSupport.unlocked(vault)
        val preview = json.readTree(
            mvc.multipart("/api/imports/preview") {
                file(MockMultipartFile("file", "r.pdf", "application/pdf", LbpStatementFixture.build(LbpStatementFixture.standard())))
            }.andReturn().response.contentAsString,
        )
        mvc.post("/api/imports/${preview["previewId"].asText()}/commit").andExpect { status { isOk() } }

        val categories = get("/api/categories")
        fun categoryId(name: String): Long {
            fun find(nodes: JsonNode): JsonNode? = nodes.firstNotNullOfOrNull { n -> if (n["name"].asText() == name) n else n["children"]?.let(::find) }
            return find(categories)!!["id"].asLong()
        }
        val txns = get("/api/transactions")
        fun categorize(label: String, category: String) {
            val id = txns.first { it["label"].asText().startsWith(label) }["id"].asLong()
            mvc.put("/api/transactions/$id/category") {
                contentType = MediaType.APPLICATION_JSON
                content = """{"categoryId":${categoryId(category)}}"""
            }.andExpect { status { isNoContent() } }
        }
        categorize("PRELEVEMENT DE FOURNISSEUR", "Dons aux personnes en difficulté")
        categorize("ACHAT CB BOULANGERIE", "Dons")
        categorize("VERSEMENT CARTE", "Intérêts")
        categorize("COTISATION TRIMESTRIELLE", "Impôts et taxes")

        val report = get("/api/summary/tax?year=2026")
        assertEquals(8_900, report["donationsHelp"]["cents"].asLong())
        assertEquals(6_600, report["donationsHelp"]["estimatedBenefitCents"].asLong(), "75 % de 89 €, à l'euro inférieur")
        assertEquals(2_150, report["donations"]["cents"].asLong())
        assertEquals(1_400, report["donations"]["estimatedBenefitCents"].asLong(), "66 % de 21,50 €")
        assertEquals(65_000, report["taxableInterest"]["cents"].asLong())
        assertEquals(0, report["regulatedInterest"]["cents"].asLong())
        assertEquals(4_995, report["taxesPaid"]["cents"].asLong())
        assertEquals(0, report["homeServices"]["cents"].asLong())
        assertEquals(200_000, report["rules"]["donationHelpCeilingCents"].asLong())
        assertTrue(report["childcare"]["estimatedBenefitCents"].isNull)
        mvc.get("/api/summary/tax?year=1999").andExpect { status { isBadRequest() } }
    }
}
