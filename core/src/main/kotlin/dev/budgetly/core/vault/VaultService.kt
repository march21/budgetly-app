package dev.budgetly.core.vault

import com.fasterxml.jackson.databind.ObjectMapper
import com.zaxxer.hikari.HikariConfig
import com.zaxxer.hikari.HikariDataSource
import dev.budgetly.core.config.BudgetlyProperties
import dev.budgetly.core.config.EncryptedSqlite
import org.flywaydb.core.Flyway
import jakarta.annotation.PreDestroy
import org.slf4j.LoggerFactory
import org.springframework.aot.hint.annotation.RegisterReflectionForBinding
import org.springframework.beans.factory.ObjectProvider
import org.springframework.context.ApplicationEventPublisher
import org.springframework.stereotype.Service
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.security.MessageDigest
import java.sql.Connection
import java.sql.DriverManager
import java.sql.SQLException
import java.util.Base64
import java.util.HexFormat

enum class VaultState { UNINITIALIZED, LOCKED, UNLOCKED }

/** Contenu de vault.json : paramètres de dérivation, aucun secret. */
data class VaultFile(
    val version: Int = 1,
    val kdf: String = "argon2id",
    val memoryKib: Int,
    val iterations: Int,
    val parallelism: Int,
    val salt: String,
)

/** Publié après chaque déverrouillage réussi (la sauvegarde automatique s'y abonne). */
class VaultUnlockedEvent

/** Publié quand le coffre se verrouille : les aperçus d'import gardés en mémoire sont oubliés. */
class VaultLockedEvent

/**
 * Coffre : la base chiffrée n'est ouverte qu'après saisie du mot de passe maître.
 * La clé dérivée ne vit qu'en mémoire, dans la configuration du pool de connexions.
 */
@Service
@RegisterReflectionForBinding(VaultFile::class)
class VaultService(
    private val props: BudgetlyProperties,
    private val json: ObjectMapper,
    private val flyway: ObjectProvider<Flyway>,
    private val events: ApplicationEventPublisher,
) {
    private val log = LoggerFactory.getLogger(javaClass)

    val databaseFile: Path = props.dataDir.resolve(DATABASE_FILE)
    val vaultFile: Path = props.dataDir.resolve(VAULT_FILE)

    /** Paramètres du nouveau mot de passe, écrits avant le rechiffrement et promus après (reprise sur panne). */
    private val nextVaultFile: Path = props.dataDir.resolve("vault.next.json")

    @Volatile
    private var pool: HikariDataSource? = null

    val state: VaultState
        get() = when {
            pool != null -> VaultState.UNLOCKED
            Files.exists(vaultFile) -> VaultState.LOCKED
            else -> VaultState.UNINITIALIZED
        }

    /** Pool ouvert, ou exception si le coffre est verrouillé. */
    fun dataSource(): HikariDataSource = pool ?: throw VaultLockedException()

    @Synchronized
    fun setup(password: String) {
        if (Files.exists(vaultFile)) throw VaultAlreadyInitializedException()
        if (Files.exists(databaseFile)) throw OrphanDatabaseException()
        checkStrength(password)

        Files.createDirectories(props.dataDir)
        val file = newVaultFile()
        open(deriveHexKey(password, file))
        json.writeValue(vaultFile.toFile(), file)
        events.publishEvent(VaultUnlockedEvent())
    }

    @Synchronized
    fun unlock(password: String) {
        if (pool != null) return
        if (!Files.exists(vaultFile)) throw VaultNotInitializedException()
        try {
            open(deriveHexKey(password, readVaultFile(vaultFile)))
            Files.deleteIfExists(nextVaultFile)
        } catch (e: InvalidPasswordException) {
            // Changement de mot de passe interrompu après le rechiffrement : la base attend le nouveau.
            if (!Files.exists(nextVaultFile)) throw e
            open(deriveHexKey(password, readVaultFile(nextVaultFile)))
            Files.move(nextVaultFile, vaultFile, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
            log.info("Changement de mot de passe interrompu : repris au déverrouillage")
        }
        events.publishEvent(VaultUnlockedEvent())
    }

    @Synchronized
    fun lock() {
        val wasOpen = pool != null
        closePool()
        if (wasOpen) events.publishEvent(VaultLockedEvent())
    }

    /** Arrêt de l'application : ferme la base sans publier d'événement (contexte en cours de fermeture). */
    @PreDestroy
    @Synchronized
    fun closePool() {
        pool?.close()
        pool = null
    }

    /**
     * Rechiffre la base avec une clé dérivée du nouveau mot de passe (nouveau sel).
     * Ordre choisi pour qu'une panne à n'importe quelle étape laisse un coffre ouvrable :
     * paramètres écrits à part → rechiffrement → promotion des paramètres.
     */
    @Synchronized
    fun changePassword(current: String, next: String) {
        val open = checkPassword(current)
        checkStrength(next)

        val file = newVaultFile()
        val nextKey = deriveHexKey(next, file)
        json.writeValue(nextVaultFile.toFile(), file)
        open.connection.use { connection ->
            connection.createStatement().use { it.execute("PRAGMA rekey = '$nextKey'") }
        }
        lock()
        open(nextKey)
        Files.move(nextVaultFile, vaultFile, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
    }

    /** Vérifie le mot de passe du coffre ouvert (comparaison en temps constant avec la clé du pool). */
    fun checkPassword(password: String): HikariDataSource {
        val open = pool ?: throw VaultLockedException()
        val expectedUrl = EncryptedSqlite.jdbcUrl(databaseFile, deriveHexKey(password, readVaultFile(vaultFile))).toByteArray()
        if (!MessageDigest.isEqual(expectedUrl, open.jdbcUrl.toByteArray())) throw InvalidPasswordException()
        return open
    }

    /**
     * Donne l'unique connexion du pool au bloc : personne d'autre ne lit ni n'écrit pendant ce temps,
     * le fichier de la base est donc cohérent sur disque (journal en mode DELETE, sans transaction ouverte).
     */
    fun <T> withExclusiveAccess(block: (Connection) -> T): T = dataSource().connection.use(block)

    /** Vérifie qu'une base et ses paramètres s'ouvrent avec ce mot de passe, sans toucher au coffre courant. */
    fun verify(database: Path, parameters: Path, password: String) {
        val url = EncryptedSqlite.jdbcUrl(database, deriveHexKey(password, readVaultFile(parameters)))
        try {
            DriverManager.getConnection(url).use { connection ->
                connection.createStatement().use { it.executeQuery("select count(*) from sqlite_master").close() }
            }
        } catch (e: SQLException) {
            throw InvalidPasswordException()
        }
    }

    fun checkStrength(password: String) {
        if (password.length < props.vault.minPasswordLength) throw WeakPasswordException(props.vault.minPasswordLength)
    }

    private fun newVaultFile() = VaultFile(
        memoryKib = props.vault.argon2MemoryKib,
        iterations = props.vault.argon2Iterations,
        parallelism = props.vault.argon2Parallelism,
        salt = Base64.getEncoder().encodeToString(KeyDerivation.newSalt()),
    )

    private fun readVaultFile(path: Path): VaultFile = json.readValue(path.toFile(), VaultFile::class.java)

    private fun deriveHexKey(password: String, file: VaultFile): String {
        val chars = password.toCharArray()
        val key = try {
            KeyDerivation.derive(
                chars,
                KdfParams(file.memoryKib, file.iterations, file.parallelism, Base64.getDecoder().decode(file.salt)),
            )
        } finally {
            chars.fill('\u0000')
        }
        return HexFormat.of().formatHex(key).also { key.fill(0) }
    }

    private fun open(hexKey: String) {
        val candidate = HikariDataSource(
            HikariConfig().apply {
                jdbcUrl = EncryptedSqlite.jdbcUrl(databaseFile, hexKey)
                // SQLite : un seul écrivain à la fois.
                maximumPoolSize = 1
                poolName = "budgetly"
                // Le pool n'ouvre rien tant qu'on ne l'a pas vérifié.
                initializationFailTimeout = -1
            },
        )
        try {
            candidate.connection.use { connection ->
                connection.createStatement().use { it.executeQuery("select count(*) from sqlite_master").close() }
            }
        } catch (e: SQLException) {
            candidate.close()
            throw InvalidPasswordException()
        }
        pool = candidate
        try {
            flyway.getObject().migrate()
        } catch (e: RuntimeException) {
            lock()
            throw e
        }
    }

    companion object {
        const val DATABASE_FILE = "budgetly.db"
        const val VAULT_FILE = "vault.json"
    }
}
