package dev.budgetly.core.backup

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import dev.budgetly.core.TestSupport
import dev.budgetly.core.statement.lbp.LbpStatementFixture
import dev.budgetly.core.vault.VaultService
import dev.budgetly.core.vault.VaultState
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
import java.io.ByteArrayInputStream
import java.util.zip.ZipInputStream
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** Sauvegarde, changement de mot de passe et restauration, dans un seul scénario ordonné. */
@SpringBootTest(
    properties = [
        TestSupport.DATA_DIR, TestSupport.FAST_KDF_MEMORY, TestSupport.FAST_KDF_ITERATIONS, "test.context=backup",
    ],
)
@AutoConfigureMockMvc
class BackupTest(
    @Autowired private val mvc: MockMvc,
    @Autowired private val vault: VaultService,
    @Autowired private val json: ObjectMapper,
) {
    private val newPassword = "nouveau-mot-de-passe-de-test"

    private fun get(path: String): JsonNode =
        json.readTree(mvc.get(path).andExpect { status { isOk() } }.andReturn().response.contentAsString)

    private fun password(value: String) = """{"password":"$value"}"""

    private fun unlock(value: String) = mvc.post("/api/vault/unlock") {
        contentType = MediaType.APPLICATION_JSON
        content = password(value)
    }

    private fun restore(bytes: ByteArray, value: String) = mvc.multipart("/api/vault/restore") {
        file(MockMultipartFile("file", "sauvegarde.budgetly", "application/octet-stream", bytes))
        param("password", value)
    }

    private fun importStandardStatement() {
        val preview = json.readTree(
            mvc.multipart("/api/imports/preview") {
                file(MockMultipartFile("file", "releve.pdf", "application/pdf", LbpStatementFixture.build(LbpStatementFixture.standard())))
            }.andReturn().response.contentAsString,
        )
        mvc.post("/api/imports/${preview["previewId"].asText()}/commit").andExpect { status { isOk() } }
    }

    @Test
    fun `sauvegarde chiffree, changement de mot de passe et restauration`() {
        TestSupport.unlocked(vault)
        // La création du coffre déclenche la première sauvegarde automatique.
        assertEquals(listOf("AUTOMATIC"), get("/api/backups")["backups"].map { it["kind"].asText() })
        importStandardStatement()

        val created = json.readTree(mvc.post("/api/backups").andExpect { status { isOk() } }.andReturn().response.contentAsString)
        assertEquals("MANUAL", created["kind"].asText())
        val name = created["name"].asText()
        assertTrue(name.endsWith(".budgetly"))

        val archive = mvc.get("/api/backups/$name").andExpect { status { isOk() } }.andReturn().response.contentAsByteArray
        val entries = mutableMapOf<String, ByteArray>()
        ZipInputStream(ByteArrayInputStream(archive)).use { zip ->
            while (true) {
                val entry = zip.nextEntry ?: break
                entries[entry.name] = zip.readBytes()
            }
        }
        assertEquals(setOf("sauvegarde.json", "vault.json", "budgetly.db"), entries.keys)
        val database = String(entries.getValue("budgetly.db"), Charsets.ISO_8859_1)
        assertFalse(database.startsWith("SQLite format 3"), "la base sauvegardée doit rester chiffrée")
        assertFalse(database.contains("CREATE TABLE"), "schéma lisible en clair dans la sauvegarde")
        assertFalse(String(entries.getValue("sauvegarde.json")).contains(TestSupport.PASSWORD))
        mvc.get("/api/backups/vault.json").andExpect { status { isNotFound() } }

        // Changement de mot de passe : refusé si l'actuel est faux ou le nouveau trop court.
        fun change(current: String, next: String) = mvc.post("/api/vault/password") {
            contentType = MediaType.APPLICATION_JSON
            content = """{"currentPassword":"$current","newPassword":"$next"}"""
        }
        change("mauvais-mot-de-passe", newPassword).andExpect { status { isUnauthorized() } }
        change(TestSupport.PASSWORD, "court").andExpect { status { isBadRequest() } }
        change(TestSupport.PASSWORD, newPassword).andExpect { status { isNoContent() } }
        assertEquals(VaultState.UNLOCKED, vault.state)
        assertEquals(3, get("/api/accounts").size())
        assertTrue(get("/api/backups")["backups"].any { it["kind"].asText() == "SAFETY" }, "sauvegarde de sécurité avant rechiffrement")

        mvc.post("/api/vault/lock")
        unlock(TestSupport.PASSWORD).andExpect { status { isUnauthorized() } }
        unlock(newPassword).andExpect { status { isNoContent() } }
        assertEquals(3, get("/api/accounts").size())

        // Restauration : interdite coffre ouvert, refusée avec un mauvais mot de passe ou un fichier quelconque.
        restore(archive, TestSupport.PASSWORD).andExpect { status { isConflict() } }
        mvc.post("/api/vault/lock")
        restore(archive, newPassword).andExpect { status { isUnauthorized() } }
        restore("pas une sauvegarde".toByteArray(), TestSupport.PASSWORD).andExpect {
            status { isUnprocessableEntity() }
            jsonPath("$.error") { value("BACKUP_INVALID") }
        }
        assertEquals(VaultState.LOCKED, vault.state)
        unlock(newPassword).andExpect { status { isNoContent() } }
        mvc.post("/api/vault/lock")

        // La sauvegarde manuelle date d'avant le changement : elle s'ouvre avec l'ancien mot de passe.
        restore(archive, TestSupport.PASSWORD).andExpect { status { isNoContent() } }
        assertEquals(VaultState.UNLOCKED, vault.state)
        assertEquals(3, get("/api/accounts").size())
        assertEquals(10, get("/api/transactions").size())
        mvc.post("/api/vault/lock")
        unlock(newPassword).andExpect { status { isUnauthorized() } }
        unlock(TestSupport.PASSWORD).andExpect { status { isNoContent() } }

        mvc.delete("/api/backups/$name").andExpect { status { isNoContent() } }
        mvc.get("/api/backups/$name").andExpect { status { isNotFound() } }
    }
}
