package dev.budgetly.core.config

import org.springframework.boot.context.properties.ConfigurationProperties
import java.nio.file.Path

@ConfigurationProperties("budgetly")
data class BudgetlyProperties(
    /** Dossier des données locales (base chiffrée + paramètres du coffre). */
    val dataDir: Path,
    val vault: Vault = Vault(),
    /**
     * Jeton aléatoire généré par la coque desktop à chaque lancement.
     * Vide = contrôle désactivé (dev uniquement).
     */
    val launchToken: String = "",
) {
    /** Paramètres Argon2id appliqués à la création du coffre (puis lus depuis vault.json). */
    data class Vault(
        val argon2MemoryKib: Int = 65_536,
        val argon2Iterations: Int = 3,
        val argon2Parallelism: Int = 1,
        val minPasswordLength: Int = 12,
    )
}
