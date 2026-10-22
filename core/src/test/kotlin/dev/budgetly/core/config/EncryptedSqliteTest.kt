package dev.budgetly.core.config

import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.junit.jupiter.api.io.TempDir
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import java.sql.DriverManager
import java.sql.SQLException
import kotlin.test.assertEquals
import kotlin.test.assertFalse

class EncryptedSqliteTest {

    @TempDir
    lateinit var dir: Path

    @Test
    fun `le fichier de base est illisible sans la bonne cle`() {
        val file = dir.resolve("test.db")
        val url = EncryptedSqlite.jdbcUrl(file, "bonne-cle")

        DriverManager.getConnection(url).use { connection ->
            connection.createStatement().use {
                it.execute("create table secret (value text)")
                it.execute("insert into secret values ('marqueur-confidentiel')")
            }
        }

        val raw = String(Files.readAllBytes(file), StandardCharsets.ISO_8859_1)
        assertFalse(raw.startsWith("SQLite format 3"), "en-tête SQLite en clair : base non chiffrée")
        assertFalse(raw.contains("marqueur-confidentiel"), "donnée lisible en clair dans le fichier")

        assertThrows<SQLException> {
            DriverManager.getConnection(EncryptedSqlite.jdbcUrl(file, "mauvaise-cle")).use { connection ->
                connection.createStatement().use { it.executeQuery("select count(*) from secret").next() }
            }
        }

        DriverManager.getConnection(url).use { connection ->
            connection.createStatement().use { statement ->
                statement.executeQuery("select value from secret").use { rs ->
                    rs.next()
                    assertEquals("marqueur-confidentiel", rs.getString(1))
                }
            }
        }
    }
}
