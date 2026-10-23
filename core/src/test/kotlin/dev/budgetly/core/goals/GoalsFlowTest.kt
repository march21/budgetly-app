package dev.budgetly.core.goals

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
import java.time.YearMonth
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** Objectifs d'épargne : suivi par un compte ou par un montant saisi, effort mensuel nécessaire, date estimée. */
@SpringBootTest(properties = [TestSupport.DATA_DIR, TestSupport.FAST_KDF_MEMORY, TestSupport.FAST_KDF_ITERATIONS, "test.context=goals"])
@AutoConfigureMockMvc
class GoalsFlowTest(
    @Autowired private val mvc: MockMvc,
    @Autowired private val vault: VaultService,
    @Autowired private val json: ObjectMapper,
) {
    private fun get(path: String): JsonNode = json.readTree(mvc.get(path).andExpect { status { isOk() } }.andReturn().response.contentAsString)

    private fun body(value: Any) = json.writeValueAsString(value)

    @Test
    fun `objectifs suivis par un compte ou a la main`() {
        TestSupport.unlocked(vault)
        val preview = json.readTree(
            mvc.multipart("/api/imports/preview") {
                file(MockMultipartFile("file", "r.pdf", "application/pdf", LbpStatementFixture.build(LbpStatementFixture.standard())))
            }.andReturn().response.contentAsString,
        )
        val record = json.readTree(mvc.post("/api/imports/${preview["previewId"].asText()}/commit").andReturn().response.contentAsString)
        val livret = get("/api/accounts").first { it["type"].asText() == "LIVRET_A" }
        val balance = livret["balanceCents"].asLong()

        val today = LocalDate.now()
        val target = today.plusMonths(12)
        mvc.post("/api/goals") {
            contentType = MediaType.APPLICATION_JSON
            content = body(mapOf("name" to "Épargne de précaution", "targetCents" to balance + 120_000, "targetDate" to target.toString(), "accountId" to livret["id"].asLong(), "monthlyCents" to 5_000))
        }.andExpect { status { isCreated() } }
        mvc.post("/api/goals") {
            contentType = MediaType.APPLICATION_JSON
            content = body(mapOf("name" to "Vélo", "targetCents" to 50_000, "savedCents" to 50_000))
        }.andExpect { status { isCreated() } }
        for (invalid in listOf(
            mapOf("name" to " ", "targetCents" to 1),
            mapOf("name" to "X", "targetCents" to 0),
            mapOf("name" to "X", "targetCents" to 100, "targetDate" to today.minusDays(1).toString()),
            mapOf("name" to "X", "targetCents" to 100, "accountId" to 9_999),
            mapOf("name" to "X", "targetCents" to 100_000_000_001L),
            mapOf("name" to "X", "targetCents" to 100, "monthlyCents" to -1),
        )) {
            mvc.post("/api/goals") { contentType = MediaType.APPLICATION_JSON; content = body(invalid) }.andExpect { status { isBadRequest() } }
        }

        var goals = get("/api/goals")
        assertEquals(listOf("Épargne de précaution", "Vélo"), goals.map { it["name"].asText() })
        val precaution = goals[0]
        assertEquals("ACCOUNT", precaution["source"].asText())
        assertEquals(balance, precaution["currentCents"].asLong())
        assertEquals(12, precaution["monthsLeft"].asInt())
        assertEquals(10_000, precaution["requiredMonthlyCents"].asLong())
        assertEquals(YearMonth.from(today).plusMonths(24).toString(), precaution["projectedMonth"].asText())
        assertFalse(precaution["reached"].asBoolean())
        assertTrue(goals[1]["reached"].asBoolean())
        assertTrue(goals[1]["requiredMonthlyCents"].isNull)

        // Modification puis suppression.
        mvc.put("/api/goals/${goals[1]["id"].asLong()}") {
            contentType = MediaType.APPLICATION_JSON
            content = body(mapOf("name" to "Vélo", "targetCents" to 80_000, "savedCents" to 50_000, "monthlyCents" to 10_000))
        }.andExpect { status { isNoContent() } }
        goals = get("/api/goals")
        assertEquals(YearMonth.from(today).plusMonths(3).toString(), goals[1]["projectedMonth"].asText())
        // Effort dérisoire : pas de mois d'atteinte au-delà d'un siècle.
        mvc.put("/api/goals/${goals[1]["id"].asLong()}") {
            contentType = MediaType.APPLICATION_JSON
            content = body(mapOf("name" to "Vélo", "targetCents" to 100_000_000_000L, "savedCents" to 0, "monthlyCents" to 1))
        }.andExpect { status { isNoContent() } }
        assertTrue(get("/api/goals")[1]["projectedMonth"].isNull)
        mvc.delete("/api/goals/${goals[1]["id"].asLong()}").andExpect { status { isNoContent() } }
        mvc.delete("/api/goals/${goals[1]["id"].asLong()}").andExpect { status { isNotFound() } }

        // Le compte disparaît avec l'annulation de son import : l'objectif reste, sans compte.
        mvc.delete("/api/imports/${record["id"].asLong()}").andExpect { status { isNoContent() } }
        val orphan = get("/api/goals").single()
        assertEquals("MANUAL", orphan["source"].asText())
        assertEquals(0, orphan["currentCents"].asLong())

        // Taux d'épargne moyen sur les mois renseignés.
        assertTrue(get("/api/summary/history?month=${YearMonth.from(today)}&months=12").has("averageSavingsRate"))
    }
}
