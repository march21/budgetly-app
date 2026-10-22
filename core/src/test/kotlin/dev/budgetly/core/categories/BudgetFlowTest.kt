package dev.budgetly.core.categories

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import dev.budgetly.core.TestSupport
import dev.budgetly.core.statement.lbp.LbpStatementFixture
import dev.budgetly.core.vault.VaultService
import org.junit.jupiter.api.BeforeEach
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
import kotlin.test.assertNull
import kotlin.test.assertTrue

@SpringBootTest(
    properties = [
        TestSupport.DATA_DIR, TestSupport.FAST_KDF_MEMORY, TestSupport.FAST_KDF_ITERATIONS, "test.context=budget",
    ],
)
@AutoConfigureMockMvc
class BudgetFlowTest(
    @Autowired private val mvc: MockMvc,
    @Autowired private val vault: VaultService,
    @Autowired private val json: ObjectMapper,
) {
    @BeforeEach
    fun unlock() = TestSupport.unlocked(vault)

    private fun get(path: String): JsonNode =
        json.readTree(mvc.get(path).andExpect { status { isOk() } }.andReturn().response.contentAsString)

    private fun categoryId(name: String) = get("/api/categories").first { it["name"].asText() == name }["id"].asLong()

    private fun importStandard() {
        val preview = json.readTree(
            mvc.multipart("/api/imports/preview") {
                file(MockMultipartFile("file", "r.pdf", "application/pdf", LbpStatementFixture.build(LbpStatementFixture.standard())))
            }.andReturn().response.contentAsString,
        )
        mvc.post("/api/imports/${preview["previewId"].asText()}/commit").andExpect { status { isOk() } }
    }

    @Test
    fun `classement automatique, reclassement avec regle, budget et bilan du mois`() {
        importStandard()

        // Classement automatique à l'import : virements internes et frais bancaires (règle par défaut).
        val all = get("/api/transactions")
        assertEquals(4, all.count { it["categorySource"].asText() == "INTERNAL" })
        val fee = all.single { it["label"].asText().startsWith("COTISATION") }
        assertEquals("Frais bancaires", fee["categoryName"].asText())

        // Septembre : virements internes exclus, opérations non classées comptées par leur signe.
        val september = get("/api/summary?month=2026-09")
        assertEquals(418_702, september["incomeCents"].asLong())
        assertEquals(2_150 + 8_900 + 105_748, september["expenseCents"].asLong())
        assertEquals(2, september["internalTransferCount"].asInt())
        assertEquals(4, september["uncategorizedCount"].asInt())

        // Reclassement du salaire avec création d'une règle sur le motif proposé.
        val salary = all.single { it["label"].asText() == "VIREMENT DE SOCIETE EXEMPLE" }
        mvc.put("/api/transactions/${salary["id"].asLong()}/category") {
            contentType = MediaType.APPLICATION_JSON
            content = """{"categoryId":${categoryId("Salaire")},"rulePattern":"${salary["suggestedPattern"].asText()}"}"""
        }.andExpect { status { isNoContent() } }
        assertTrue(get("/api/rules").any { it["pattern"].asText() == "VIREMENT DE SOCIETE EXEMPLE" && it["priority"].asInt() == 20 })

        // Courses sous Alimentation, avec un budget sur la catégorie principale.
        val bakery = all.single { it["label"].asText().startsWith("ACHAT CB BOULANGERIE") }
        mvc.put("/api/transactions/${bakery["id"].asLong()}/category") {
            contentType = MediaType.APPLICATION_JSON
            content = """{"categoryId":${categoryId("Courses")}}"""
        }.andExpect { status { isNoContent() } }
        mvc.put("/api/categories/${categoryId("Alimentation")}/budget") {
            contentType = MediaType.APPLICATION_JSON
            content = """{"monthlyCents":30000}"""
        }.andExpect { status { isNoContent() } }

        val updated = get("/api/summary?month=2026-09")
        val salaryLine = updated["income"].single { it["name"].asText() == "Salaire" }
        assertEquals(418_702, salaryLine["cents"].asLong())
        val food = updated["expenses"].single { it["name"].asText() == "Alimentation" }
        assertEquals(2_150, food["cents"].asLong())
        assertEquals(30_000, food["budgetCents"].asLong())
        assertEquals("Courses", food["children"][0]["name"].asText())
        assertEquals(2, updated["uncategorizedCount"].asInt())
        val rate = updated["savingsRate"].asDouble()
        assertTrue(rate > 0.72 && rate < 0.73, "taux d'épargne $rate")

        // Un choix manuel n'est jamais écrasé par une règle ; le retirer rend l'opération au classement automatique.
        mvc.post("/api/rules") {
            contentType = MediaType.APPLICATION_JSON
            content = """{"pattern":"BOULANGERIE","categoryId":${categoryId("Restaurants et cafés")}}"""
        }.andExpect { status { isOk() } }
        assertEquals("Courses", get("/api/transactions?q=BOULANGERIE").single()["categoryName"].asText())
        mvc.put("/api/transactions/${bakery["id"].asLong()}/category") {
            contentType = MediaType.APPLICATION_JSON
            content = """{"categoryId":null}"""
        }.andExpect { status { isNoContent() } }
        val reset = get("/api/transactions?q=BOULANGERIE").single()
        assertEquals("Restaurants et cafés", reset["categoryName"].asText())
        assertEquals("RULE", reset["categorySource"].asText())

        // Historique : octobre ne compte que le versement en revenu et la cotisation en dépense.
        val history = get("/api/summary/history?month=2026-10&months=3")
        assertEquals(3, history["months"].size())
        val october = history["months"].last()
        assertEquals(65_000, october["incomeCents"].asLong())
        assertEquals(4_995, october["expenseCents"].asLong())
        assertEquals(0, history["months"].first()["incomeCents"].asLong())
    }

    @Test
    fun `une sous-categorie herite de la nature de son parent`() {
        val created = json.readTree(
            mvc.post("/api/categories") {
                contentType = MediaType.APPLICATION_JSON
                content = """{"name":"Boulangerie","parentId":${categoryId("Alimentation")}}"""
            }.andExpect { status { isOk() } }.andReturn().response.contentAsString,
        )
        val category = get("/api/categories").single { it["id"].asLong() == created["id"].asLong() }
        assertEquals("EXPENSE", category["kind"].asText())
        assertNull(category["budgetCents"].takeUnless { it.isNull })
    }
}
