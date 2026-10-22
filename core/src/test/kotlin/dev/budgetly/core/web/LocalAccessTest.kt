package dev.budgetly.core.web

import dev.budgetly.core.TestSupport
import dev.budgetly.core.security.LocalAccessFilter
import jakarta.servlet.http.Cookie
import org.hamcrest.Matchers.containsString
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.http.HttpHeaders
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.get

@SpringBootTest(properties = [TestSupport.DATA_DIR, "budgetly.launch-token=jeton-de-lancement"])
@AutoConfigureMockMvc
class LocalAccessTest(@Autowired private val mvc: MockMvc) {

    @Test
    fun `refuse une requete sans cookie de session`() {
        mvc.get("/api/health").andExpect { status { isUnauthorized() } }
    }

    @Test
    fun `le jeton de lancement pose un cookie strict puis redirige`() {
        mvc.get("/?launch=jeton-de-lancement").andExpect {
            status { isFound() }
            header {
                string(HttpHeaders.LOCATION, "/")
                string(HttpHeaders.SET_COOKIE, containsString("${LocalAccessFilter.SESSION_COOKIE}=jeton-de-lancement"))
                string(HttpHeaders.SET_COOKIE, containsString("HttpOnly"))
                string(HttpHeaders.SET_COOKIE, containsString("SameSite=Strict"))
            }
        }
    }

    @Test
    fun `refuse un mauvais jeton`() {
        mvc.get("/?launch=faux").andExpect { status { isUnauthorized() } }
    }

    @Test
    fun `accepte une requete avec le cookie`() {
        mvc.get("/api/health") {
            cookie(Cookie(LocalAccessFilter.SESSION_COOKIE, "jeton-de-lancement"))
        }.andExpect { status { isOk() } }
    }

    @Test
    fun `refuse un Host etranger meme avec le cookie`() {
        mvc.get("/api/health") {
            header(HttpHeaders.HOST, "attaquant.example")
            cookie(Cookie(LocalAccessFilter.SESSION_COOKIE, "jeton-de-lancement"))
        }.andExpect { status { isForbidden() } }
    }
}
