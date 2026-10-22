package dev.budgetly.core.imports

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import dev.budgetly.core.TestSupport
import dev.budgetly.core.pdf.StatementPdfFixture
import dev.budgetly.core.statement.lbp.LbpStatementFixture
import dev.budgetly.core.statement.lbp.LbpStatementFixture.Op
import dev.budgetly.core.vault.VaultService
import org.junit.jupiter.api.BeforeEach
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

@SpringBootTest(
    properties = [
        TestSupport.DATA_DIR, TestSupport.FAST_KDF_MEMORY, TestSupport.FAST_KDF_ITERATIONS, "test.context=imports",
    ],
)
@AutoConfigureMockMvc
class ImportFlowTest(
    @Autowired private val mvc: MockMvc,
    @Autowired private val vault: VaultService,
    @Autowired private val json: ObjectMapper,
) {
    @BeforeEach
    fun unlock() = TestSupport.unlocked(vault)

    private fun preview(pdf: ByteArray): JsonNode {
        val body = mvc.multipart("/api/imports/preview") {
            file(MockMultipartFile("file", "releve.pdf", "application/pdf", pdf))
        }.andExpect { status { isOk() } }.andReturn().response.contentAsString
        return json.readTree(body)
    }

    private fun get(path: String): JsonNode =
        json.readTree(mvc.get(path).andExpect { status { isOk() } }.andReturn().response.contentAsString)

    @Test
    fun `apercu, import, doublons, virements internes et chevauchement`() {
        val pdf = LbpStatementFixture.build(LbpStatementFixture.standard())

        val first = preview(pdf)
        assertEquals("READY", first["status"].asText())
        assertEquals(3, first["accounts"].size())
        assertTrue(first["accounts"].all { it["newAccount"].asBoolean() && it["balanceOk"].asBoolean() })
        assertEquals(10, first["transactions"].size())
        // CCP 10/09 ↔ Livret A 10/09, CCP 06/10 (permanent) ↔ Livret A 06/10
        assertEquals(4, first["transactions"].count { it["internalTransfer"].asBoolean() })

        val record = json.readTree(
            mvc.post("/api/imports/${first["previewId"].asText()}/commit")
                .andExpect { status { isOk() } }.andReturn().response.contentAsString,
        )
        assertEquals(10, record["impact"]["transactionsCreated"].asInt())
        assertEquals(3, record["impact"]["accountsCreated"].asInt())
        assertEquals(4, record["impact"]["internalTransfers"].asInt())

        // Le même fichier est reconnu, rien n'est réimporté.
        assertEquals("ALREADY_IMPORTED", preview(pdf)["status"].asText())

        val accounts = get("/api/accounts")
        assertEquals(listOf("CHECKING", "LDDS", "LIVRET_A"), accounts.map { it["type"].asText() })
        val checking = accounts.first()
        assertEquals(LbpStatementFixture.standard().checking.closingCents, checking["balanceCents"].asLong())
        assertTrue(checking["ibanMasked"].asText().endsWith("2012"))

        val linked = get("/api/transactions?accountId=${checking["id"].asLong()}&q=PERMANENT")
        assertEquals(1, linked.size())
        assertTrue(linked.first()["internalTransfer"].asBoolean())

        // Relevé qui chevauche le précédent : seules les nouvelles opérations sont importées.
        val base = LbpStatementFixture.standard()
        val overlapping = base.copy(
            checking = base.checking.copy(
                ops = base.checking.ops + Op("08/10", "ACHAT CB LIBRAIRIE 07.10.26", -1_500, listOf("CARTE NUMERO 999")),
            ),
        )
        val second = preview(LbpStatementFixture.build(overlapping))
        assertEquals("READY", second["status"].asText())
        assertEquals(10, second["transactions"].count { it["duplicate"].asBoolean() })
        val secondRecord = json.readTree(
            mvc.post("/api/imports/${second["previewId"].asText()}/commit")
                .andExpect { status { isOk() } }.andReturn().response.contentAsString,
        )
        assertEquals(1, secondRecord["impact"]["transactionsCreated"].asInt())
        assertEquals(10, secondRecord["impact"]["duplicatesSkipped"].asInt())

        assertEquals(2, get("/api/imports").size())
        assertEquals(11, get("/api/transactions").size())
    }

    @Test
    fun `un releve dont le solde ne boucle pas est refuse`() {
        val rejected = preview(LbpStatementFixture.build(LbpStatementFixture.standard(closingAdjustmentCents = 100)))
        assertEquals("REJECTED", rejected["status"].asText())
        assertTrue(rejected["issues"].size() > 0)
        mvc.post("/api/imports/${rejected["previewId"].asText()}/commit").andExpect { status { isConflict() } }
    }

    @Test
    fun `un document inconnu est refuse sans rien ecrire`() {
        mvc.multipart("/api/imports/preview") {
            file(MockMultipartFile("file", "autre.pdf", "application/pdf", StatementPdfFixture.build()))
        }.andExpect {
            status { isUnprocessableEntity() }
            jsonPath("$.error") { value("UNSUPPORTED_DOCUMENT") }
        }
    }
}
