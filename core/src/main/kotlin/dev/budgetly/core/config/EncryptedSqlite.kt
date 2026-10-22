package dev.budgetly.core.config

import java.net.URLEncoder
import java.nio.charset.StandardCharsets
import java.nio.file.Path

/**
 * URL JDBC d'une base SQLite chiffrée via SQLite3MultipleCiphers, schéma SQLCipher v4.
 * Sans la bonne clé, le fichier est illisible (vérifié par EncryptedSqliteTest).
 */
object EncryptedSqlite {
    fun jdbcUrl(file: Path, key: String): String {
        require(key.isNotBlank()) { "La clé de chiffrement de la base est vide" }
        val path = file.toAbsolutePath().toString().replace('\\', '/')
        val encodedKey = URLEncoder.encode(key, StandardCharsets.UTF_8)
        return "jdbc:sqlite:file:$path?cipher=sqlcipher&legacy=4&key=$encodedKey"
    }
}
