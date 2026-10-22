package dev.budgetly.core.imports

import com.fasterxml.jackson.databind.ObjectMapper
import org.springframework.http.HttpStatus
import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.Clock
import java.time.Instant
import java.time.LocalDate

/**
 * Journal des imports et annulation.
 *
 * Seul l'import le plus récent d'un compte peut être annulé : un import plus récent a pu écarter
 * comme doublons des opérations portées par celui-ci, les supprimer le laisserait incomplet.
 */
@Service
class ImportJournal(
    private val jdbc: JdbcClient,
    private val json: ObjectMapper,
    private val clock: Clock,
) {
    fun history(): List<ImportRecord> {
        val cancellable = cancellableIds()
        return jdbc.sql(
            "select i.id, i.imported_at, o.name, i.parser_id, i.period_start, i.period_end, i.impact_json, i.cancelled_at " +
                "from statement_import i join owner o on o.id = i.owner_id order by i.imported_at desc, i.id desc",
        ).query { rs, _ ->
            val id = rs.getLong(1)
            ImportRecord(
                id = id,
                importedAt = Instant.parse(rs.getString(2)),
                importedBy = rs.getString(3),
                parserId = rs.getString(4),
                periodStart = LocalDate.parse(rs.getString(5)),
                periodEnd = LocalDate.parse(rs.getString(6)),
                impact = json.readValue(rs.getString(7), ImportImpact::class.java),
                cancelledAt = rs.getString(8)?.let(Instant::parse),
                cancellable = id in cancellable,
            )
        }.list()
    }

    /** Supprime tout ce que l'import a produit ; le journal garde la ligne, marquée annulée. */
    @Transactional
    fun cancel(importId: Long) {
        val cancelledAt = jdbc.sql("select cancelled_at from statement_import where id = ?").param(importId)
            .query { rs, _ -> rs.getString(1) ?: "" }.optional()
            .orElseThrow { ApiException(HttpStatus.NOT_FOUND, "IMPORT_NOT_FOUND", "Import introuvable.") }
        if (cancelledAt.isNotEmpty()) {
            throw ApiException(HttpStatus.CONFLICT, "IMPORT_ALREADY_CANCELLED", "Cet import est déjà annulé.")
        }
        if (importId !in cancellableIds()) {
            throw ApiException(
                HttpStatus.CONFLICT,
                "IMPORT_NOT_LATEST",
                "Un relevé importé ensuite porte sur les mêmes comptes : annulez d'abord celui-là.",
            )
        }

        val accounts = touchedAccounts(importId)
        // Les virements internes appariés avec une opération supprimée redeviennent orphelins.
        jdbc.sql("update txn set transfer_txn_id = null where transfer_txn_id in (select id from txn where import_id = ?)")
            .param(importId).update()
        jdbc.sql("delete from txn_split where txn_id in (select id from txn where import_id = ?)").param(importId).update()
        for (table in listOf("txn", "statement_balance", "position_snapshot", "availability_snapshot", "valuation")) {
            jdbc.sql("delete from $table where import_id = ?").param(importId).update()
        }
        // Un compte créé par cet import et resté vide disparaît (une valeur saisie à la main le garde).
        for (accountId in accounts) {
            val remaining = jdbc.sql(
                "select (select count(*) from txn where account_id = :id) + " +
                    "(select count(*) from statement_balance where account_id = :id) + " +
                    "(select count(*) from valuation where account_id = :id) + " +
                    "(select count(*) from position_snapshot where account_id = :id)",
            ).param("id", accountId).query(Long::class.java).single()
            if (remaining == 0L) jdbc.sql("delete from account where id = ?").param(accountId).update()
        }
        jdbc.sql("update statement_import set cancelled_at = ?, file_sha256 = file_sha256 || ':annule:' || id where id = ?")
            .params(clock.instant().toString(), importId).update()
    }

    private fun touchedAccounts(importId: Long): List<Long> =
        jdbc.sql("select account_id from ($TOUCHED) where import_id = ?").param(importId).query(Long::class.java).list()

    private fun cancellableIds(): Set<Long> =
        jdbc.sql(
            """
            select i.id from statement_import i
            where i.cancelled_at is null and not exists (
              select 1 from ($TOUCHED) mine
              join ($TOUCHED) later on later.account_id = mine.account_id and later.import_id > mine.import_id
              where mine.import_id = i.id
            )
            """.trimIndent(),
        ).query(Long::class.java).list().toSet()

    private companion object {
        /** Couples (import, compte) : tout ce qu'un import a écrit. Les imports annulés n'y figurent plus. */
        const val TOUCHED =
            "select import_id, account_id from statement_balance " +
                "union select import_id, account_id from txn " +
                "union select import_id, account_id from valuation where import_id is not null " +
                "union select import_id, account_id from position_snapshot where import_id is not null"
    }
}
