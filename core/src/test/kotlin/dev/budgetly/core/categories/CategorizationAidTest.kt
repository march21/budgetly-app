package dev.budgetly.core.categories

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import dev.budgetly.core.TestSupport
import dev.budgetly.core.statement.lbp.LbpStatementFixture
import dev.budgetly.core.vault.AutoLock
import dev.budgetly.core.vault.VaultService
import dev.budgetly.core.vault.VaultState
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
import java.time.Duration
import java.time.Instant
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** Suggestions de règles, reclassement en masse, puis verrouillage automatique (scénario ordonné). */
@SpringBootTest(
    properties = [
        TestSupport.DATA_DIR, TestSupport.FAST_KDF_MEMORY, TestSupport.FAST_KDF_ITERATIONS, "test.context=categorization-aid",
    ],
)
@AutoConfigureMockMvc
class CategorizationAidTest(
    @Autowired private val mvc: MockMvc,
    @Autowired private val vault: VaultService,
    @Autowired private val autoLock: AutoLock,
    @Autowired private val json: ObjectMapper,
) {
    private fun get(path: String): JsonNode =
        json.readTree(mvc.get(path).andExpect { status { isOk() } }.andReturn().response.contentAsString)

    @Test
    fun `suggestions de regles, reclassement en masse et verrouillage automatique`() {
        TestSupport.unlocked(vault)
        val preview = json.readTree(
            mvc.multipart("/api/imports/preview") {
                file(MockMultipartFile("file", "releve.pdf", "application/pdf", LbpStatementFixture.build(LbpStatementFixture.standard())))
            }.andReturn().response.contentAsString,
        )
        mvc.post("/api/imports/${preview["previewId"].asText()}/commit").andExpect { status { isOk() } }

        val uncategorized = get("/api/transactions?uncategorized=true")
        val suggestions = get("/api/rules/suggestions")
        assertTrue(suggestions.size() > 0, "des libellés non classés doivent être proposés")
        assertTrue(suggestions.sumOf { it["count"].asInt() } <= uncategorized.size())
        val counts = suggestions.map { it["count"].asInt() }
        assertEquals(counts.sortedDescending(), counts, "les motifs les plus fréquents d'abord")
        assertTrue(suggestions.none { it["pattern"].asText().startsWith("ACHAT CB ") }, "préfixe technique retiré du motif")

        // Règle créée depuis la première suggestion : le motif disparaît des suggestions.
        val first = suggestions.first()
        val category = get("/api/categories").first { it["kind"].asText() == "EXPENSE" && !it["system"].asBoolean() }
        mvc.post("/api/rules") {
            contentType = MediaType.APPLICATION_JSON
            content = """{"pattern":${json.writeValueAsString(first["pattern"].asText())},"categoryId":${category["id"].asLong()}}"""
        }.andExpect { status { isOk() } }
        assertTrue(get("/api/rules/suggestions").none { it["pattern"].asText() == first["pattern"].asText() })

        // Reclassement en masse : toutes les opérations encore à classer, d'un coup.
        val remaining = get("/api/transactions?uncategorized=true").map { it["id"].asLong() }
        assertTrue(remaining.isNotEmpty())
        val result = json.readTree(
            mvc.put("/api/transactions/category") {
                contentType = MediaType.APPLICATION_JSON
                content = """{"transactionIds":$remaining,"categoryId":${category["id"].asLong()}}"""
            }.andExpect { status { isOk() } }.andReturn().response.contentAsString,
        )
        assertEquals(remaining.size, result["updated"].asInt())
        assertEquals(0, get("/api/transactions?uncategorized=true").size())
        assertEquals(0, get("/api/rules/suggestions").size())
        val moved = get("/api/transactions").filter { it["id"].asLong() in remaining }
        assertTrue(moved.all { it["categorySource"].asText() == "MANUAL" })

        // Verrouillage automatique : réglage validé, puis coffre fermé passé le délai.
        assertEquals(15, get("/api/vault/auto-lock")["minutes"].asInt())
        mvc.put("/api/vault/auto-lock") {
            contentType = MediaType.APPLICATION_JSON
            content = """{"minutes":7}"""
        }.andExpect { status { isBadRequest() } }
        mvc.put("/api/vault/auto-lock") {
            contentType = MediaType.APPLICATION_JSON
            content = """{"minutes":5}"""
        }.andExpect { status { isOk() } }

        val now = Instant.now()
        assertFalse(autoLock.lockIfIdle(now.plus(Duration.ofMinutes(4))), "pas encore")
        mvc.post("/api/vault/activity").andExpect { status { isNoContent() } }
        assertTrue(autoLock.lockIfIdle(Instant.now().plus(Duration.ofMinutes(6))))
        assertEquals(VaultState.LOCKED, vault.state)

        // Le réglage survit au verrouillage ; 0 désactive.
        TestSupport.unlocked(vault)
        assertEquals(5, get("/api/vault/auto-lock")["minutes"].asInt())
        mvc.put("/api/vault/auto-lock") {
            contentType = MediaType.APPLICATION_JSON
            content = """{"minutes":0}"""
        }.andExpect { status { isOk() } }
        assertFalse(autoLock.lockIfIdle(Instant.now().plus(Duration.ofDays(1))))
        assertEquals(VaultState.UNLOCKED, vault.state)
    }
}
