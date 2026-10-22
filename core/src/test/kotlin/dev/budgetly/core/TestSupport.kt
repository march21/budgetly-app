package dev.budgetly.core

import dev.budgetly.core.vault.VaultService
import dev.budgetly.core.vault.VaultState

/** Propriétés communes : dossier de données jetable, Argon2 allégé pour des tests rapides. */
object TestSupport {
    const val PASSWORD = "mot-de-passe-de-test"

    const val DATA_DIR = "budgetly.data-dir=\${java.io.tmpdir}/budgetly-test-\${random.uuid}"
    const val FAST_KDF_MEMORY = "budgetly.vault.argon2-memory-kib=1024"
    const val FAST_KDF_ITERATIONS = "budgetly.vault.argon2-iterations=1"

    fun unlocked(vault: VaultService) {
        when (vault.state) {
            VaultState.UNINITIALIZED -> vault.setup(PASSWORD)
            VaultState.LOCKED -> vault.unlock(PASSWORD)
            VaultState.UNLOCKED -> Unit
        }
    }
}
