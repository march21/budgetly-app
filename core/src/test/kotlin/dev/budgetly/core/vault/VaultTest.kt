package dev.budgetly.core.vault

import dev.budgetly.core.TestSupport
import dev.budgetly.core.config.BudgetlyProperties
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.http.MediaType
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.get
import org.springframework.test.web.servlet.post
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import kotlin.test.assertEquals
import kotlin.test.assertFalse

// Contexte propre à cette classe (propriété distincte) : le coffre doit partir de zéro.
@SpringBootTest(
    properties = [
        TestSupport.DATA_DIR, TestSupport.FAST_KDF_MEMORY, TestSupport.FAST_KDF_ITERATIONS, "test.context=vault",
    ],
)
@AutoConfigureMockMvc
class VaultTest(
    @Autowired private val mvc: MockMvc,
    @Autowired private val vault: VaultService,
    @Autowired private val props: BudgetlyProperties,
) {
    private fun password(value: String) = """{"password":"$value"}"""

    @Test
    fun `cycle complet creation, verrouillage, mauvais puis bon mot de passe`() {
        mvc.get("/api/vault").andExpect { jsonPath("$.state") { value("UNINITIALIZED") } }
        mvc.get("/api/transactions").andExpect {
            status { isLocked() }
            jsonPath("$.error") { value("VAULT_LOCKED") }
        }

        mvc.post("/api/vault/setup") {
            contentType = MediaType.APPLICATION_JSON
            content = password("court")
        }.andExpect { status { isBadRequest() } }

        mvc.post("/api/vault/setup") {
            contentType = MediaType.APPLICATION_JSON
            content = password(TestSupport.PASSWORD)
        }.andExpect { status { isNoContent() } }
        assertEquals(VaultState.UNLOCKED, vault.state)
        mvc.get("/api/transactions").andExpect { status { isOk() } }

        mvc.post("/api/vault/setup") {
            contentType = MediaType.APPLICATION_JSON
            content = password(TestSupport.PASSWORD)
        }.andExpect { status { isConflict() } }

        mvc.post("/api/vault/lock").andExpect { status { isNoContent() } }
        mvc.get("/api/vault").andExpect { jsonPath("$.state") { value("LOCKED") } }
        mvc.get("/api/accounts").andExpect { status { isLocked() } }

        mvc.post("/api/vault/unlock") {
            contentType = MediaType.APPLICATION_JSON
            content = password("mauvais-mot-de-passe")
        }.andExpect {
            status { isUnauthorized() }
            jsonPath("$.error") { value("INVALID_PASSWORD") }
        }
        assertEquals(VaultState.LOCKED, vault.state)

        mvc.post("/api/vault/unlock") {
            contentType = MediaType.APPLICATION_JSON
            content = password(TestSupport.PASSWORD)
        }.andExpect { status { isNoContent() } }
        mvc.get("/api/health").andExpect { jsonPath("$.schemaVersion") { value("11") } }

        val vaultJson = Files.readString(props.dataDir.resolve("vault.json"))
        assertFalse(vaultJson.contains(TestSupport.PASSWORD), "le mot de passe ne doit pas être stocké")
        val database = String(Files.readAllBytes(props.dataDir.resolve("budgetly.db")), StandardCharsets.ISO_8859_1)
        assertFalse(database.startsWith("SQLite format 3"), "base non chiffrée")
        assertFalse(database.contains("CREATE TABLE"), "schéma lisible en clair")
    }
}
