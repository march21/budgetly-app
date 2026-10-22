package dev.budgetly.core.update

import com.fasterxml.jackson.databind.ObjectMapper
import dev.budgetly.core.config.BudgetlyProperties
import org.junit.jupiter.api.Test
import java.nio.file.Files
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class UpdatePreferenceTest {
    private val dir = Files.createTempDirectory("budgetly-updates")
    private val preference = UpdatePreference(BudgetlyProperties(dataDir = dir), ObjectMapper())

    @Test
    fun `absent, la verification est activee, le choix est relu tel quel`() {
        assertTrue(preference.checkAtStartup())
        preference.setCheckAtStartup(false)
        assertFalse(preference.checkAtStartup())
        Files.writeString(dir.resolve(UpdatePreference.FILE), "{\n\t\"checkAtStartup\" :\ttrue\n}")
        assertTrue(preference.checkAtStartup())
    }

    @Test
    fun `fichier illisible, la verification est desactivee`() {
        Files.writeString(dir.resolve(UpdatePreference.FILE), "{checkAtStartup")
        assertFalse(preference.checkAtStartup())
        Files.writeString(dir.resolve(UpdatePreference.FILE), "{\"checkAtStartup\": \"oui\"}")
        assertFalse(preference.checkAtStartup())
    }
}
