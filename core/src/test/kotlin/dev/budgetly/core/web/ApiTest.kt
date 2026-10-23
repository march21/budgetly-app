package dev.budgetly.core.web

import dev.budgetly.core.TestSupport
import dev.budgetly.core.pdf.StatementPdfFixture
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

@SpringBootTest(properties = [TestSupport.DATA_DIR, TestSupport.FAST_KDF_MEMORY, TestSupport.FAST_KDF_ITERATIONS])
@AutoConfigureMockMvc
class ApiTest(@Autowired private val mvc: MockMvc, @Autowired private val vault: VaultService) {

    @BeforeEach
    fun unlock() = TestSupport.unlocked(vault)

    @Test
    fun `la sante indique un coffre ouvert et migre`() {
        mvc.get("/api/health").andExpect {
            status { isOk() }
            jsonPath("$.status") { value("UP") }
            jsonPath("$.vault") { value("UNLOCKED") }
            jsonPath("$.schemaVersion") { value("9") }
        }
    }

    @Test
    fun `l'outil d'inspection renvoie le texte positionne`() {
        val file = MockMultipartFile("file", "releve.pdf", "application/pdf", StatementPdfFixture.build())
        mvc.multipart("/api/dev/pdf-text") { file(file) }.andExpect {
            status { isOk() }
            jsonPath("$.pages") { value(1) }
            jsonPath("$.lines[1].words[5].text") { value("12,50") }
        }
    }
}
