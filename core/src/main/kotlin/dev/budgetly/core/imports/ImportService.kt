package dev.budgetly.core.imports

import com.fasterxml.jackson.databind.ObjectMapper
import dev.budgetly.core.categories.CategoryService
import dev.budgetly.core.pdf.PdfTextExtractor
import dev.budgetly.core.statement.ParsedStatement
import dev.budgetly.core.statement.StatementParser
import dev.budgetly.core.statement.StatementValidator
import org.springframework.aot.hint.annotation.RegisterReflectionForBinding
import org.springframework.http.HttpStatus
import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.jdbc.support.GeneratedKeyHolder
import dev.budgetly.core.vault.VaultLockedEvent
import org.springframework.context.event.EventListener
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.io.IOException
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import kotlin.math.abs

/**
 * Import d'un relevé en deux temps : aperçu (rien n'est écrit), puis validation par l'utilisateur.
 * Le PDF n'est jamais conservé : seul l'aperçu analysé reste en mémoire, 30 minutes au plus.
 */
@Service
@RegisterReflectionForBinding(ImportImpact::class)
class ImportService(
    private val parsers: List<StatementParser>,
    private val jdbc: JdbcClient,
    private val json: ObjectMapper,
    private val clock: Clock,
    private val categories: CategoryService,
    private val portfolios: PortfolioImportService,
) {
    private class Pending(
        val createdAt: Instant,
        val fileHash: String,
        val statement: ParsedStatement,
        val fingerprints: List<List<String>>,
        val duplicates: Set<TxnRef>,
        val transfers: TransferDetection,
        val ready: Boolean,
    )

    private val pending = ConcurrentHashMap<String, Pending>()

    fun preview(pdf: ByteArray): ImportPreview {
        purgeExpired()
        val hash = Fingerprints.sha256(pdf)
        val alreadyImported = jdbc.sql("select count(*) from statement_import where file_sha256 = ?")
            .param(hash).query(Long::class.java).single() > 0
        if (alreadyImported) {
            return ImportPreview(
                previewId = null, status = PreviewStatus.ALREADY_IMPORTED, parserId = null, institution = null,
                periodStart = null, periodEnd = null, accounts = emptyList(), transactions = emptyList(),
                issues = listOf("Ce relevé a déjà été importé."),
            )
        }

        val document = try {
            PdfTextExtractor.extract(pdf)
        } catch (e: IOException) {
            throw ApiException(HttpStatus.UNPROCESSABLE_ENTITY, "PDF_UNREADABLE", "Ce fichier n'est pas un PDF lisible.")
        }
        val parser = parsers.firstOrNull { it.supports(document) }
            ?: return portfolios.preview(hash, document)
            ?: throw ApiException(
                HttpStatus.UNPROCESSABLE_ENTITY, "UNSUPPORTED_DOCUMENT",
                "Document non reconnu : sont pris en charge les relevés de comptes La Banque Postale, " +
                    "l'évaluation de PEA EasyBourse, le bulletin annuel de l'assurance vie EasyVie et l'avis d'opération " +
                    "Natixis (PEE). Pour une autre banque, importez l'export CSV ou OFX de vos opérations.",
            )
        return previewStatement(hash, parser.parse(document))
    }

    /**
     * Aperçu d'un relevé déjà lu (PDF ou export CSV/OFX). [looseDuplicates] : un export ne reprend pas les libellés
     * des relevés PDF ; une opération du même compte, du même jour et du même montant y compte comme doublon.
     */
    fun previewStatement(hash: String, statement: ParsedStatement, looseDuplicates: Boolean = false): ImportPreview {
        val ibans = statement.accounts.map { it.account.iban }
        val existingAccounts = knownAccounts()
        val fingerprints = statement.accounts.map { Fingerprints.of(it.account.iban, it.transactions) }
        val existingFingerprints = existingFingerprints(fingerprints.flatten())
        val duplicates = fingerprints.flatMapIndexed { a, list ->
            val sameDayAmount = if (looseDuplicates) existingDayAmounts(existingAccounts[statement.accounts[a].account.iban]) else mutableMapOf()
            list.mapIndexedNotNull { i, fp ->
                val t = statement.accounts[a].transactions[i]
                val key = "${t.bookingDate}|${t.amountCents}"
                val left = sameDayAmount[key] ?: 0
                if (left > 0) sameDayAmount[key] = left - 1
                if (fp in existingFingerprints || left > 0) TxnRef(a, i) else null
            }
        }.toSet()
        val transfers = InternalTransfers.detect(statement, existingAccounts.keys + ibans)

        val issues = mutableListOf<String>()
        val accounts = statement.accounts.mapIndexed { index, account ->
            val check = StatementValidator.check(account)
            if (!check.ok) {
                issues += "${account.account.name} : le solde final ne correspond pas aux opérations lues. Import refusé."
            }
            val dup = account.transactions.indices.count { TxnRef(index, it) in duplicates }
            AccountPreview(
                key = index,
                type = account.account.type,
                name = account.account.name,
                ibanMasked = maskIban(account.account.iban),
                newAccount = account.account.iban !in existingAccounts,
                openingDate = account.opening.date,
                openingCents = account.opening.cents,
                closingDate = account.closing.date,
                closingCents = account.closing.cents,
                expectedClosingCents = check.expectedClosingCents,
                balanceOk = check.ok,
                transactionCount = account.transactions.size,
                newTransactionCount = account.transactions.size - dup,
                duplicateCount = dup,
            )
        }
        val ready = issues.isEmpty()
        val previewId = UUID.randomUUID().toString()
        pending[previewId] = Pending(clock.instant(), hash, statement, fingerprints, duplicates, transfers, ready)

        val transactions = statement.accounts.flatMapIndexed { a, account ->
            account.transactions.mapIndexed { i, t ->
                PreviewTransaction(
                    accountKey = a,
                    bookingDate = t.bookingDate,
                    operationDate = t.operationDate,
                    amountCents = t.amountCents,
                    label = t.label,
                    kind = t.kind,
                    duplicate = TxnRef(a, i) in duplicates,
                    internalTransfer = TxnRef(a, i) in transfers.internal,
                )
            }
        }
        return ImportPreview(
            previewId = previewId,
            status = if (ready) PreviewStatus.READY else PreviewStatus.REJECTED,
            parserId = statement.parserId,
            institution = statement.institution,
            periodStart = statement.periodStart,
            periodEnd = statement.periodEnd,
            accounts = accounts,
            transactions = transactions,
            issues = issues,
        )
    }

    /** Écrit tout ou rien : comptes, journal, soldes, opérations nouvelles, liens de virements internes. */
    @Transactional
    fun commit(previewId: String): ImportRecord {
        val p = pending.remove(previewId) ?: return portfolios.commit(previewId)
        if (!p.ready) {
            throw ApiException(HttpStatus.CONFLICT, "PREVIEW_REJECTED", "Ce relevé n'a pas passé les contrôles : import impossible.")
        }
        val statement = p.statement
        val now = clock.instant()
        val existing = knownAccounts()

        var created = 0
        val accountIds = statement.accounts.map { account ->
            existing[account.account.iban] ?: run {
                created++
                insert(
                    "insert into account (owner_id, institution, type, name, iban, created_at) values (1, ?, ?, ?, ?, ?)",
                    statement.institution, account.account.type.name, account.account.name, account.account.iban, now.toString(),
                )
            }
        }

        val alreadyImported = jdbc.sql("select count(*) from statement_import where file_sha256 = ?")
            .param(p.fileHash).query(Long::class.java).single() > 0
        if (alreadyImported) throw ApiException(HttpStatus.CONFLICT, "ALREADY_IMPORTED", "Ce relevé a déjà été importé.")
        val importId = insert(
            "insert into statement_import (owner_id, file_sha256, parser_id, imported_at, period_start, period_end, impact_json) " +
                "values (1, ?, ?, ?, ?, ?, '{}')",
            p.fileHash, statement.parserId, now.toString(), statement.periodStart.toString(), statement.periodEnd.toString(),
        )

        val txnIds = mutableMapOf<TxnRef, Long>()
        var skipped = 0
        statement.accounts.forEachIndexed { a, account ->
            jdbc.sql(
                "insert into statement_balance (import_id, account_id, opening_date, opening_cents, closing_date, closing_cents) " +
                    "values (?, ?, ?, ?, ?, ?)",
            ).params(importId, accountIds[a], account.opening.date.toString(), account.opening.cents,
                account.closing.date.toString(), account.closing.cents).update()

            account.transactions.forEachIndexed { i, t ->
                val ref = TxnRef(a, i)
                val fingerprint = p.fingerprints[a][i]
                val exists = ref in p.duplicates || jdbc.sql("select count(*) from txn where fingerprint = ?").param(fingerprint)
                    .query(Long::class.java).single() > 0
                if (exists) {
                    skipped++
                } else {
                    txnIds[ref] = insert(
                        "insert into txn (account_id, import_id, booking_date, operation_date, amount_cents, label, details, kind, " +
                            "fingerprint, internal_transfer) values (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
                        accountIds[a], importId, t.bookingDate.toString(), t.operationDate.toString(), t.amountCents,
                        t.label, t.details, t.kind.name, fingerprint, if (ref in p.transfers.internal) 1 else 0,
                    )
                }
            }
        }

        for ((left, right) in p.transfers.pairs) {
            val leftId = txnIds[left] ?: continue
            val rightId = txnIds[right] ?: continue
            link(leftId, rightId)
        }
        for ((ref, iban) in p.transfers.counterpartIban) {
            val id = txnIds[ref] ?: continue
            val t = statement.accounts[ref.account].transactions[ref.index]
            findCounterpart(iban, t.amountCents, t.bookingDate)?.let { link(id, it) }
        }

        val impact = ImportImpact(
            accountsTouched = statement.accounts.size,
            accountsCreated = created,
            transactionsCreated = txnIds.size,
            duplicatesSkipped = skipped,
            internalTransfers = txnIds.keys.count { it in p.transfers.internal },
        )
        jdbc.sql("update statement_import set impact_json = ? where id = ?")
            .params(json.writeValueAsString(impact), importId).update()
        categories.recategorize()

        return ImportRecord(importId, now, "Moi", statement.parserId, statement.periodStart!!, statement.periodEnd!!, impact)
    }

    private fun knownAccounts(): Map<String, Long> =
        jdbc.sql("select iban, id from account").query { rs, _ -> rs.getString(1) to rs.getLong(2) }.list().toMap()

    /** Opérations déjà en base sur ce compte, comptées par « date|montant ». */
    private fun existingDayAmounts(accountId: Long?): MutableMap<String, Int> {
        if (accountId == null) return mutableMapOf()
        return jdbc.sql("select booking_date, amount_cents from txn where account_id = ?").param(accountId)
            .query { rs, _ -> "${rs.getString(1)}|${rs.getLong(2)}" }.list()
            .groupingBy { it }.eachCount().toMutableMap()
    }

    private fun existingFingerprints(candidates: List<String>): Set<String> =
        candidates.chunked(500).flatMap { chunk ->
            jdbc.sql("select fingerprint from txn where fingerprint in (${chunk.joinToString(",") { "?" }})")
                .params(chunk).query(String::class.java).list()
        }.toSet()

    /** Contrepartie déjà en base (import précédent) : compte cible, montant opposé, à 3 jours près, non liée. */
    private fun findCounterpart(iban: String, amountCents: Long, date: LocalDate): Long? =
        jdbc.sql(
            "select t.id, t.booking_date from txn t join account a on a.id = t.account_id " +
                "where a.iban = ? and t.amount_cents = ? and t.transfer_txn_id is null",
        ).params(iban, -amountCents)
            .query { rs, _ -> rs.getLong(1) to LocalDate.parse(rs.getString(2)) }
            .list()
            .filter { (_, d) -> abs(d.toEpochDay() - date.toEpochDay()) <= 3 }
            .minByOrNull { (_, d) -> abs(d.toEpochDay() - date.toEpochDay()) }
            ?.first

    private fun link(a: Long, b: Long) {
        jdbc.sql("update txn set transfer_txn_id = ?, internal_transfer = 1 where id = ?").params(b, a).update()
        jdbc.sql("update txn set transfer_txn_id = ?, internal_transfer = 1 where id = ?").params(a, b).update()
    }

    private fun insert(sql: String, vararg params: Any?): Long {
        val keys = GeneratedKeyHolder()
        jdbc.sql(sql).params(*params).update(keys)
        return keys.key!!.toLong()
    }

    /** Coffre verrouillé : les relevés analysés mais non validés sont oubliés. */
    @EventListener
    fun onLocked(event: VaultLockedEvent) = pending.clear()

    @org.springframework.scheduling.annotation.Scheduled(fixedDelay = 60_000, initialDelay = 60_000)
    fun purgeExpired() {
        val limit = clock.instant().minus(Duration.ofMinutes(30))
        pending.entries.removeIf { it.value.createdAt.isBefore(limit) }
    }
}
