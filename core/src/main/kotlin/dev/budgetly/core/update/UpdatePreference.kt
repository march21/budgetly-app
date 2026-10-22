package dev.budgetly.core.update

import com.fasterxml.jackson.databind.ObjectMapper
import dev.budgetly.core.config.BudgetlyProperties
import org.springframework.stereotype.Component
import java.nio.file.Files
import java.nio.file.StandardCopyOption

/**
 * Vérification des mises à jour au démarrage (activée par défaut, désactivable).
 *
 * Le réglage vit hors de la base chiffrée, dans `updates.json` à côté d'elle : c'est la coque desktop qui
 * vérifie, au lancement, avant que le coffre soit ouvert. Le fichier ne contient que ce booléen.
 * Absent = activée ; présent mais illisible = désactivée, comme le lit la coque.
 */
@Component
class UpdatePreference(props: BudgetlyProperties, private val json: ObjectMapper) {
    private val file = props.dataDir.resolve(FILE)

    fun checkAtStartup(): Boolean {
        if (!Files.exists(file)) return true
        return runCatching { json.readTree(file.toFile()).get("checkAtStartup")?.takeIf { it.isBoolean }?.asBoolean() }
            .getOrNull() ?: false
    }

    fun setCheckAtStartup(enabled: Boolean) {
        Files.createDirectories(file.parent)
        val next = file.resolveSibling("$FILE.tmp")
        Files.writeString(next, "{\"checkAtStartup\": $enabled}\n")
        Files.move(next, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
    }

    companion object {
        const val FILE = "updates.json"
    }
}
