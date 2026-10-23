package dev.budgetly.core.backup

import com.fasterxml.jackson.databind.ObjectMapper
import dev.budgetly.core.config.BudgetlyProperties
import dev.budgetly.core.imports.ApiException
import dev.budgetly.core.vault.VaultService
import dev.budgetly.core.vault.VaultState
import dev.budgetly.core.vault.VaultUnlockedEvent
import org.slf4j.LoggerFactory
import org.springframework.aot.hint.annotation.RegisterReflectionForBinding
import org.springframework.context.event.EventListener
import org.springframework.http.HttpStatus
import org.springframework.stereotype.Service
import java.io.InputStream
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream
import kotlin.io.path.deleteIfExists
import kotlin.io.path.fileSize
import kotlin.io.path.getLastModifiedTime
import kotlin.io.path.isRegularFile
import kotlin.io.path.listDirectoryEntries
import kotlin.io.path.name

enum class BackupKind { MANUAL, AUTOMATIC, SAFETY }

data class BackupInfo(val name: String, val kind: BackupKind, val createdAt: Instant, val sizeBytes: Long)

data class BackupList(val directory: String, val backups: List<BackupInfo>)

/** En-tête d'une sauvegarde : rien de personnel, seulement de quoi la reconnaître. */
data class BackupManifest(val format: Int, val createdAt: Instant, val kind: BackupKind, val schemaVersion: String?)

/**
 * Sauvegardes de la base. Le fichier .budgetly est une archive qui contient la base telle qu'elle est
 * sur disque (donc chiffrée) et ses paramètres de dérivation : sans le mot de passe maître, elle est illisible.
 */
@Service
@RegisterReflectionForBinding(BackupManifest::class)
class BackupService(
    private val props: BudgetlyProperties,
    private val vault: VaultService,
    private val json: ObjectMapper,
    private val clock: Clock,
) {
    private val log = LoggerFactory.getLogger(javaClass)
    private val directory: Path = props.dataDir.resolve("sauvegardes")
    private val stamp = DateTimeFormatter.ofPattern("yyyy-MM-dd-HHmmss").withZone(ZoneId.systemDefault())

    fun list(): BackupList {
        val backups = if (Files.isDirectory(directory)) {
            directory.listDirectoryEntries("*$EXTENSION").filter { it.isRegularFile() }.mapNotNull { file ->
                val kind = BackupKind.entries.firstOrNull { file.name.startsWith(prefix(it)) } ?: return@mapNotNull null
                BackupInfo(file.name, kind, file.getLastModifiedTime().toInstant(), file.fileSize())
            }.sortedByDescending { it.createdAt }
        } else {
            emptyList()
        }
        return BackupList(directory.toAbsolutePath().toString(), backups)
    }

    @Synchronized
    fun create(kind: BackupKind): BackupInfo {
        if (vault.state != VaultState.UNLOCKED) throw ApiException(HttpStatus.LOCKED, "VAULT_LOCKED", "Le coffre est verrouillé")
        Files.createDirectories(directory)
        val now = clock.instant()
        var target = directory.resolve("${prefix(kind)}${stamp.format(now)}$EXTENSION")
        var n = 2
        while (Files.exists(target)) target = directory.resolve("${prefix(kind)}${stamp.format(now)}-${n++}$EXTENSION")
        val partial = target.resolveSibling(target.name + ".partiel")

        vault.withExclusiveAccess { connection ->
            val schema = connection.createStatement().use { st ->
                st.executeQuery("select version from flyway_schema_history where success = 1 and version is not null order by cast(version as integer) desc limit 1").use { rs ->
                    if (rs.next()) rs.getString(1) else null
                }
            }
            ZipOutputStream(Files.newOutputStream(partial)).use { zip ->
                zip.putNextEntry(ZipEntry(MANIFEST))
                zip.write(json.writeValueAsBytes(BackupManifest(FORMAT, now, kind, schema)))
                zip.closeEntry()
                zip.putNextEntry(ZipEntry(VaultService.VAULT_FILE))
                Files.copy(vault.vaultFile, zip)
                zip.closeEntry()
                zip.putNextEntry(ZipEntry(VaultService.DATABASE_FILE))
                Files.copy(vault.databaseFile, zip)
                zip.closeEntry()
            }
        }
        Files.move(partial, target, StandardCopyOption.ATOMIC_MOVE)
        if (kind == BackupKind.AUTOMATIC) prune()
        log.info("Sauvegarde {} créée", kind)
        return BackupInfo(target.name, kind, now, target.fileSize())
    }

    fun file(name: String): Path {
        if (!NAME.matches(name)) throw notFound()
        val file = directory.resolve(name)
        if (!file.isRegularFile()) throw notFound()
        return file
    }

    fun delete(name: String) {
        file(name).deleteIfExists()
    }

    /**
     * Remplace les données par une sauvegarde, coffre verrouillé ou pas encore créé.
     * La sauvegarde est d'abord vérifiée avec le mot de passe ; les données en place sont mises de côté,
     * et remises si l'ouverture échoue.
     */
    @Synchronized
    fun restore(archive: InputStream, password: String) {
        if (vault.state == VaultState.UNLOCKED) {
            throw ApiException(HttpStatus.CONFLICT, "VAULT_UNLOCKED", "Verrouillez le coffre avant de restaurer une sauvegarde.")
        }
        Files.createDirectories(props.dataDir)
        val staging = Files.createTempDirectory(props.dataDir, "restauration-")
        try {
            unpack(archive, staging)
            vault.verify(staging.resolve(VaultService.DATABASE_FILE), staging.resolve(VaultService.VAULT_FILE), password)

            val aside = props.dataDir.resolve("avant-restauration-${stamp.format(clock.instant())}")
            val current = listOf(vault.databaseFile, vault.vaultFile).filter { Files.exists(it) }
            if (current.isNotEmpty()) {
                Files.createDirectories(aside)
                current.forEach { Files.move(it, aside.resolve(it.name)) }
            }
            Files.move(staging.resolve(VaultService.DATABASE_FILE), vault.databaseFile)
            Files.move(staging.resolve(VaultService.VAULT_FILE), vault.vaultFile)
            try {
                vault.unlock(password)
            } catch (e: RuntimeException) {
                vault.lock()
                Files.deleteIfExists(vault.databaseFile)
                Files.deleteIfExists(vault.vaultFile)
                current.forEach { Files.move(aside.resolve(it.name), it) }
                Files.deleteIfExists(aside)
                throw ApiException(
                    HttpStatus.UNPROCESSABLE_ENTITY,
                    "BACKUP_INCOMPATIBLE",
                    "Cette sauvegarde n'a pas pu être ouverte par cette version de Budgetly. Vos données n'ont pas changé.",
                )
            }
            log.info("Sauvegarde restaurée")
        } finally {
            staging.toFile().deleteRecursively()
        }
    }

    @EventListener
    fun onUnlocked(event: VaultUnlockedEvent) {
        val latest = list().backups.filter { it.kind == BackupKind.AUTOMATIC }.maxOfOrNull { it.createdAt }
        if (latest != null && Duration.between(latest, clock.instant()) < Duration.ofHours(20)) return
        try {
            create(BackupKind.AUTOMATIC)
        } catch (e: Exception) {
            // Une sauvegarde automatique ratée ne doit pas empêcher d'ouvrir le coffre.
            log.warn("Sauvegarde automatique impossible : {}", e.javaClass.simpleName)
        }
    }

    private fun unpack(archive: InputStream, staging: Path) {
        val expected = setOf(MANIFEST, VaultService.VAULT_FILE, VaultService.DATABASE_FILE)
        val seen = mutableSetOf<String>()
        try {
            ZipInputStream(archive).use { zip ->
                while (true) {
                    val entry = zip.nextEntry ?: break
                    // Seuls les trois noms attendus sont extraits : aucun chemin n'est construit depuis l'archive.
                    if (entry.name in expected && seen.add(entry.name)) Files.copy(zip, staging.resolve(entry.name))
                }
            }
            val manifest = json.readValue(staging.resolve(MANIFEST).toFile(), BackupManifest::class.java)
            if (manifest.format > FORMAT) throw invalid()
        } catch (e: ApiException) {
            throw e
        } catch (e: Exception) {
            throw invalid()
        }
        if (seen != expected) throw invalid()
    }

    private fun prune() {
        list().backups.filter { it.kind == BackupKind.AUTOMATIC }.drop(KEEP_AUTOMATIC).forEach { delete(it.name) }
    }

    private fun prefix(kind: BackupKind) = when (kind) {
        BackupKind.MANUAL -> "budgetly-"
        BackupKind.AUTOMATIC -> "auto-budgetly-"
        BackupKind.SAFETY -> "securite-budgetly-"
    }

    private fun notFound() = ApiException(HttpStatus.NOT_FOUND, "BACKUP_NOT_FOUND", "Sauvegarde introuvable.")

    private fun invalid() = ApiException(HttpStatus.UNPROCESSABLE_ENTITY, "BACKUP_INVALID", "Ce fichier n'est pas une sauvegarde Budgetly.")

    companion object {
        const val EXTENSION = ".budgetly"
        private const val MANIFEST = "sauvegarde.json"
        private const val FORMAT = 1
        private const val KEEP_AUTOMATIC = 7
        private val NAME = Regex("[a-z]+(-[a-z]+)*-\\d{4}-\\d{2}-\\d{2}-\\d{6}(-\\d+)?\\.budgetly")
    }
}
