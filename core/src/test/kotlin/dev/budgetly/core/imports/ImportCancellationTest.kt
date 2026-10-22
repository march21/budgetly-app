package dev.budgetly.core.imports

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import dev.budgetly.core.TestSupport
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
import org.springframework.test.web.servlet.delete
import org.springframework.test.web.servlet.get
import org.springframework.test.web.servlet.multipart
import org.springframework.test.web.servlet.post
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

@SpringBootTest(
    properties = [
        TestSupport.DATA_DIR, TestSupport.FAST_KDF_MEMORY, TestSupport.FAST_KDF_ITERATIONS, "test.context=cancellation",
    ],
)
@AutoConfigureMockMvc
class ImportCancellationTest(
    @Autowired private val mvc: MockMvc,
    @Autowired private val vault: VaultService,
    @Autowired private val json: ObjectMapper,
) {
    @BeforeEach
    fun unlock() = TestSupport.unlocked(vault)

    private fun get(path: String): JsonNode =
        json.readTree(mvc.get(path).andExpect { status { isOk() } }.andReturn().response.contentAsString)

    private fun import(pdf: ByteArray): JsonNode {
        val preview = json.readTree(
            mvc.multipart("/api/imports/preview") {
                file(MockMultipartFile("file", "releve.pdf", "application/pdf", pdf))
            }.andExpect { status { isOk() } }.andReturn().response.contentAsString,
        )
        assertEquals("READY", preview["status"].asText())
        return json.readTree(
            mvc.post("/api/imports/${preview["previewId"].asText()}/commit")
                .andExpect { status { isOk() } }.andReturn().response.contentAsString,
        )
    }

    @Test
    fun `seul le dernier import d'un compte s'annule, le releve se reimporte, export CSV`() {
        val base = LbpStatementFixture.standard()
        val firstPdf = LbpStatementFixture.build(base)
        val first = import(firstPdf)
        val second = import(
            LbpStatementFixture.build(
                base.copy(checking = base.checking.copy(ops = base.checking.ops + Op("08/10", "ACHAT CB LIBRAIRIE 07.10.26", -1_500))),
            ),
        )
        assertEquals(11, get("/api/transactions").size())

        val history = get("/api/imports")
        val byId = history.associateBy { it["id"].asLong() }
        assertFalse(byId.getValue(first["id"].asLong())["cancellable"].asBoolean(), "un import plus récent touche les mêmes comptes")
        assertTrue(byId.getValue(second["id"].asLong())["cancellable"].asBoolean())

        mvc.delete("/api/imports/${first["id"].asLong()}").andExpect {
            status { isConflict() }
            jsonPath("$.error") { value("IMPORT_NOT_LATEST") }
        }

        // Le second n'a créé qu'une opération : l'annuler ne retire qu'elle, les comptes restent.
        mvc.delete("/api/imports/${second["id"].asLong()}").andExpect { status { isNoContent() } }
        assertEquals(10, get("/api/transactions").size())
        assertEquals(3, get("/api/accounts").size())
        mvc.delete("/api/imports/${second["id"].asLong()}").andExpect { status { isConflict() } }

        // Le premier redevient annulable ; l'annuler vide tout, comptes compris.
        assertTrue(get("/api/imports").first { it["id"].asLong() == first["id"].asLong() }["cancellable"].asBoolean())
        mvc.delete("/api/imports/${first["id"].asLong()}").andExpect { status { isNoContent() } }
        assertEquals(0, get("/api/transactions").size())
        assertEquals(0, get("/api/accounts").size())

        val journal = get("/api/imports")
        assertEquals(2, journal.size(), "le journal garde la trace des imports annulés")
        assertTrue(journal.all { !it["cancelledAt"].isNull && !it["cancellable"].asBoolean() })

        // Le même fichier se réimporte après annulation.
        assertEquals(10, import(firstPdf)["impact"]["transactionsCreated"].asInt())
        assertEquals(3, get("/api/accounts").size())

        mvc.delete("/api/imports/999999").andExpect { status { isNotFound() } }

        // Export CSV des opérations réimportées.
        val response = mvc.get("/api/transactions/export.csv").andExpect {
            status { isOk() }
            header { string("Content-Disposition", org.hamcrest.Matchers.startsWith("attachment; filename=\"budgetly-operations-")) }
        }.andReturn().response
        val bytes = response.contentAsByteArray
        val text = String(bytes, Charsets.UTF_8)
        assertTrue(text.startsWith("\uFEFFDate d'opération;"), "BOM UTF-8 et en-tête attendus")
        val lines = text.trimEnd().split("\r\n")
        assertEquals(get("/api/transactions").size() + 1, lines.size)
        assertTrue(lines.drop(1).all { it.split(";").size >= 8 })
        assertTrue(lines.drop(1).all { Regex(";-?\\d+,\\d{2};").containsMatchIn(it) }, "montants à virgule décimale")
    }
}
