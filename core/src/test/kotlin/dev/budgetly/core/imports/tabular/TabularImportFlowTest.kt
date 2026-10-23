package dev.budgetly.core.imports.tabular

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.databind.node.ObjectNode
import dev.budgetly.core.TestSupport
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
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** Exports CSV et OFX par l'API : analyse, soldes de contrôle, aperçu, validation, doublons, annulation. */
@SpringBootTest(
    properties = [TestSupport.DATA_DIR, TestSupport.FAST_KDF_MEMORY, TestSupport.FAST_KDF_ITERATIONS, "test.context=tabular"],
)
@AutoConfigureMockMvc
class TabularImportFlowTest(
    @Autowired private val mvc: MockMvc,
    @Autowired private val vault: VaultService,
    @Autowired private val json: ObjectMapper,
) {
    private fun read(body: String): JsonNode = json.readTree(body)

    private fun get(path: String): JsonNode = read(mvc.get(path).andExpect { status { isOk() } }.andReturn().response.contentAsString)

    private fun analyze(bytes: ByteArray, name: String): JsonNode = read(
        mvc.multipart("/api/imports/tabular/analyze") { file(MockMultipartFile("file", name, "text/csv", bytes)) }
            .andExpect { status { isOk() } }.andReturn().response.contentAsString,
    )

    private fun preview(token: String, body: Any, expectOk: Boolean = true): JsonNode {
        val result = mvc.post("/api/imports/tabular/$token/preview") {
            contentType = MediaType.APPLICATION_JSON
            content = json.writeValueAsString(body)
        }.andReturn().response
        if (expectOk) assertEquals(200, result.status, result.contentAsString)
        return read(result.contentAsString).also { (it as ObjectNode).put("httpStatus", result.status) }
    }

    private fun commit(previewId: String): JsonNode =
        read(mvc.post("/api/imports/$previewId/commit").andExpect { status { isOk() } }.andReturn().response.contentAsString)

    @Test
    fun `exports CSV et OFX, du fichier au compte`() {
        TestSupport.unlocked(vault)

        // 1. CSV débit / crédit sans solde : les deux soldes de contrôle sont exigés, et vérifiés.
        val first = analyze(TabularFixtures.debitCredit, "export.csv")
        assertEquals("CSV", first["format"].asText())
        assertEquals("Windows-1252", first["encoding"].asText())
        assertFalse(first["mappingRemembered"].asBoolean())
        val token = first["token"].asText()
        val mapping = first["suggestedMapping"]
        val newAccount = mapOf("name" to "Compte joint", "type" to "CHECKING", "institution" to "Banque Exemple")

        val missing = preview(token, mapOf("mapping" to mapping, "account" to newAccount), expectOk = false)
        assertEquals(400, missing["httpStatus"].asInt())
        assertEquals("BALANCES_REQUIRED", missing["error"].asText())

        val wrong = preview(token, mapOf("mapping" to mapping, "account" to newAccount, "openingCents" to 100_000, "closingCents" to 100_000))
        assertEquals("REJECTED", wrong["status"].asText())
        assertTrue(wrong["issues"].single().asText().contains("le solde final ne correspond pas"))

        val ready = preview(
            token,
            mapOf(
                "mapping" to mapping, "account" to newAccount,
                "openingCents" to TabularFixtures.debitCreditOpening, "closingCents" to TabularFixtures.debitCreditClosing,
            ),
        )
        assertEquals("READY", ready["status"].asText())
        assertEquals(4, ready["accounts"][0]["newTransactionCount"].asInt())
        val record = commit(ready["previewId"].asText())
        assertEquals(4, record["impact"]["transactionsCreated"].asInt())
        val account = get("/api/accounts").single { it["name"].asText() == "Compte joint" }
        assertEquals(TabularFixtures.debitCreditClosing, account["balanceCents"].asLong())
        assertEquals("Banque Exemple", account["institution"].asText())

        // Le même fichier ne se réimporte pas ; la correspondance est retenue pour les mêmes en-têtes.
        val again = analyze(TabularFixtures.debitCredit, "export.csv")
        assertTrue(again["alreadyImported"].asBoolean())
        assertTrue(again["mappingRemembered"].asBoolean())

        // 2. CSV avec solde vers ce compte existant : soldes lus dans le fichier, opérations du même jour et du même
        //    montant déjà connues comptées comme doublons même si le libellé diffère.
        val overlap = String(TabularFixtures.signedWithBalance) +
            "\n\"2026-10-01\",\"2026-10-01\",\"PAIEMENT EPICERIE EXEMPLE\",\"-54.30\",\"182.20\"\n"
        val second = analyze(overlap.toByteArray(), "autre.csv")
        val secondPreview = preview(
            second["token"].asText(),
            mapOf("mapping" to second["suggestedMapping"], "account" to mapOf("existingAccountId" to account["id"].asLong())),
        )
        assertEquals("READY", secondPreview["status"].asText(), secondPreview.toString())
        assertEquals(1, secondPreview["accounts"][0]["duplicateCount"].asInt())
        assertEquals(5, secondPreview["accounts"][0]["newTransactionCount"].asInt())
        val secondRecord = commit(secondPreview["previewId"].asText())
        assertEquals(1, secondRecord["impact"]["duplicatesSkipped"].asInt())
        assertEquals(9, get("/api/transactions?accountId=${account["id"].asLong()}").size())

        // 3. OFX : nouveau compte avec l'identifiant du fichier, solde de fin lu, solde de début saisi.
        val ofx = analyze(TabularFixtures.ofxSgml, "releve.ofx")
        assertEquals("OFX", ofx["format"].asText())
        assertEquals(TabularFixtures.ofxClosing, ofx["ofx"]["closingCents"].asLong())
        assertTrue(ofx["suggestedAccountId"].isNull)
        val ofxPreview = preview(
            ofx["token"].asText(),
            mapOf("account" to mapOf("name" to "Livret Exemple", "type" to "LIVRET_A"), "openingCents" to TabularFixtures.ofxOpening),
        )
        assertEquals("READY", ofxPreview["status"].asText(), ofxPreview.toString())
        val ofxRecord = commit(ofxPreview["previewId"].asText())

        // Un autre export du même compte le retrouve par son identifiant.
        val ofxAgain = analyze(String(TabularFixtures.ofxSgml).replace("<FITID>A2", "<FITID>A3").toByteArray(), "releve2.ofx")
        val livret = get("/api/accounts").single { it["name"].asText() == "Livret Exemple" }
        assertEquals(livret["id"].asLong(), ofxAgain["suggestedAccountId"].asLong())

        // Annulation : l'import OFX est le dernier de son compte.
        mvc.delete("/api/imports/${ofxRecord["id"].asLong()}").andExpect { status { isNoContent() } }
        assertTrue(get("/api/accounts").none { it["name"].asText() == "Livret Exemple" })
    }
}
