package dev.budgetly.core.portfolio

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import dev.budgetly.core.TestSupport
import dev.budgetly.core.statement.natixis.NatixisFixtures
import dev.budgetly.core.vault.VaultService
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.mock.web.MockMultipartFile
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.delete
import org.springframework.test.web.servlet.get
import org.springframework.test.web.servlet.multipart
import org.springframework.test.web.servlet.post
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** PEE Natixis : import de l'avis, enveloppe au patrimoine avec sa disponibilité, annulation. */
@SpringBootTest(
    properties = [
        TestSupport.DATA_DIR, TestSupport.FAST_KDF_MEMORY, TestSupport.FAST_KDF_ITERATIONS,
        "budgetly.prices.yahoo.enabled=false", "test.context=pee",
    ],
)
@AutoConfigureMockMvc
class PeeFlowTest(
    @Autowired private val mvc: MockMvc,
    @Autowired private val vault: VaultService,
    @Autowired private val json: ObjectMapper,
) {
    private fun get(path: String): JsonNode =
        json.readTree(mvc.get(path).andExpect { status { isOk() } }.andReturn().response.contentAsString)

    @Test
    fun `avis Natixis importe, PEE au patrimoine avec sa disponibilite, puis annule`() {
        TestSupport.unlocked(vault)
        val preview = json.readTree(
            mvc.multipart("/api/imports/preview") {
                file(MockMultipartFile("file", "avis.pdf", "application/pdf", NatixisFixtures.notice()))
            }.andExpect { status { isOk() } }.andReturn().response.contentAsString,
        )
        assertEquals("READY", preview["status"].asText())
        assertEquals("PEE", preview["portfolio"]["type"].asText())
        assertEquals(4, preview["portfolio"]["availability"].size())
        val record = json.readTree(
            mvc.post("/api/imports/${preview["previewId"].asText()}/commit").andExpect { status { isOk() } }
                .andReturn().response.contentAsString,
        )

        val envelope = get("/api/portfolio")["envelopes"].single()
        assertEquals("PEE", envelope["type"].asText())
        assertEquals(NatixisFixtures.total, envelope["valueCents"].asLong())
        assertEquals("STATEMENT", envelope["valueSource"].asText())
        // Le même fonds, réparti sur trois dates de disponibilité, n'est qu'une ligne détenue.
        assertEquals(listOf("FONDS EXEMPLE ACTIONS MONDE I", "FONDS EXEMPLE MONETAIRE"), envelope["holdings"].map { it["name"].asText() }.sorted())
        val availability = envelope["availability"]
        assertEquals(listOf("2025-06-01", "2029-06-01", "2030-06-01", "2031-06-01"), availability.map { it["availableOn"].asText() })
        assertEquals(NatixisFixtures.total, availability.sumOf { it["valueCents"].asLong() })
        assertTrue(get("/api/accounts").any { it["type"].asText() == "PEE" && it["ibanMasked"].asText() == "•••• 7001" })

        // Pas d'ISIN : l'actualisation des cours ne le concerne pas.
        assertEquals(0, json.readTree(mvc.post("/api/portfolio/prices/refresh").andReturn().response.contentAsString)["checked"].asInt())

        mvc.delete("/api/imports/${record["id"].asLong()}").andExpect { status { isNoContent() } }
        assertEquals(0, get("/api/portfolio")["envelopes"].size())
    }
}
