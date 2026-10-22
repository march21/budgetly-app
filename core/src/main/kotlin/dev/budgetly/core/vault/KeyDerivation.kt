package dev.budgetly.core.vault

import org.bouncycastle.crypto.generators.Argon2BytesGenerator
import org.bouncycastle.crypto.params.Argon2Parameters
import java.security.SecureRandom

data class KdfParams(val memoryKib: Int, val iterations: Int, val parallelism: Int, val salt: ByteArray) {
    override fun equals(other: Any?) =
        other is KdfParams && memoryKib == other.memoryKib && iterations == other.iterations &&
            parallelism == other.parallelism && salt.contentEquals(other.salt)

    override fun hashCode() = salt.contentHashCode()
}

/** Dérive la clé de la base (256 bits) depuis le mot de passe maître avec Argon2id. */
object KeyDerivation {
    private const val KEY_BYTES = 32
    private const val SALT_BYTES = 16

    fun newSalt(): ByteArray = ByteArray(SALT_BYTES).also { SecureRandom().nextBytes(it) }

    fun derive(password: CharArray, params: KdfParams): ByteArray {
        val generator = Argon2BytesGenerator()
        generator.init(
            Argon2Parameters.Builder(Argon2Parameters.ARGON2_id)
                .withVersion(Argon2Parameters.ARGON2_VERSION_13)
                .withMemoryAsKB(params.memoryKib)
                .withIterations(params.iterations)
                .withParallelism(params.parallelism)
                .withSalt(params.salt)
                .build(),
        )
        val key = ByteArray(KEY_BYTES)
        generator.generateBytes(password, key)
        return key
    }
}
