package dev.budgetly.core.imports

import com.fasterxml.jackson.databind.ObjectMapper
import dev.budgetly.core.pdf.PdfDocumentText
import dev.budgetly.core.statement.portfolio.ParsedPortfolio
import dev.budgetly.core.statement.portfolio.PortfolioParser
import org.springframework.http.HttpStatus
import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.jdbc.support.GeneratedKeyHolder
import dev.budgetly.core.vault.VaultLockedEvent
import org.springframework.context.event.EventListener
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/** Import d'un relevé de portefeuille (PEA, assurance vie) : même principe aperçu → validation que les relevés de compte. */
@Service
class PortfolioImportService(
    private val parsers: List<PortfolioParser>,
    private val jdbc: JdbcClient,
    private val json: ObjectMapper,
    private val clock: Clock,
) {
    private class Pending(val createdAt: Instant, val fileHash: String, val portfolio: ParsedPortfolio, val ready: Boolean)

    private val pending = ConcurrentHashMap<String, Pending>()

    /** Aperçu, ou null si aucun parser de portefeuille ne reconnaît le document. */
    fun preview(fileHash: String, document: PdfDocumentText): ImportPreview? {
        val parser = parsers.firstOrNull { it.supports(document) } ?: return null
        val portfolio = parser.parse(document)
        val existingId = accountId(portfolio.account.iban)
        val issues = mutableListOf<String>()
        if (!portfolio.balanced) {
            issues += "La somme des lignes ne correspond pas au total imprimé sur le relevé. Import refusé."
        }
        if (existingId != null && valuationExists(existingId, portfolio)) {
            issues += "Une valorisation de ce compte à cette date a déjà été importée."
        }
        val ready = issues.isEmpty()
        val previewId = UUID.randomUUID().toString()
        purgeExpired()
        pending[previewId] = Pending(clock.instant(), fileHash, portfolio, ready)

        return ImportPreview(
            previewId = previewId,
            status = if (ready) PreviewStatus.READY else PreviewStatus.REJECTED,
            parserId = portfolio.parserId,
            institution = portfolio.institution,
            periodStart = portfolio.valuationDate,
            periodEnd = portfolio.valuationDate,
            accounts = emptyList(),
            transactions = emptyList(),
            issues = issues,
            documentType = DocumentType.PORTFOLIO,
            portfolio = PortfolioPreview(
                type = portfolio.account.type,
                name = portfolio.account.name,
                numberMasked = "•••• ${portfolio.account.iban.takeLast(4)}",
                newAccount = existingId == null,
                valuationDate = portfolio.valuationDate,
                printedTotalCents = portfolio.printedTotalCents,
                computedTotalCents = portfolio.computedTotalCents,
                balanced = portfolio.balanced,
                cashCents = portfolio.cashCents,
                contributionsCents = portfolio.contributionsCents,
                availability = portfolio.availability.map { AvailabilityView(it.availableOn, it.valueCents) },
                positions = portfolio.positions.map {
                    PortfolioPositionPreview(
                        isin = it.isin,
                        name = it.name,
                        kind = it.kind,
                        quantity = it.quantity?.toPlainString(),
                        unitPrice = it.unitPrice?.toPlainString(),
                        valueCents = it.valueCents,
                        unrealizedCents = it.unrealizedCents,
                    )
                },
            ),
        )
    }

    @Transactional
    fun commit(previewId: String): ImportRecord {
        val p = pending.remove(previewId)
            ?: throw ApiException(HttpStatus.NOT_FOUND, "PREVIEW_EXPIRED", "Aperçu expiré : déposez de nouveau le relevé.")
        if (!p.ready) {
            throw ApiException(HttpStatus.CONFLICT, "PREVIEW_REJECTED", "Ce relevé n'a pas passé les contrôles : import impossible.")
        }
        val portfolio = p.portfolio
        val now = clock.instant()
        val alreadyImported = jdbc.sql("select count(*) from statement_import where file_sha256 = ?")
            .param(p.fileHash).query(Long::class.java).single() > 0
        if (alreadyImported) throw ApiException(HttpStatus.CONFLICT, "ALREADY_IMPORTED", "Ce relevé a déjà été importé.")

        var created = 0
        val accountId = accountId(portfolio.account.iban) ?: run {
            created = 1
            insert(
                "insert into account (owner_id, institution, type, name, iban, created_at) values (1, ?, ?, ?, ?, ?)",
                portfolio.institution, portfolio.account.type.name, portfolio.account.name, portfolio.account.iban, now.toString(),
            )
        }
        if (valuationExists(accountId, portfolio)) {
            throw ApiException(HttpStatus.CONFLICT, "ALREADY_IMPORTED", "Une valorisation de ce compte à cette date existe déjà.")
        }
        val date = portfolio.valuationDate.toString()
        val importId = insert(
            "insert into statement_import (owner_id, file_sha256, parser_id, imported_at, period_start, period_end, impact_json) " +
                "values (1, ?, ?, ?, ?, ?, '{}')",
            p.fileHash, portfolio.parserId, now.toString(), date, date,
        )

        // Un même support peut apparaître dans deux modes de gestion : on cumule parts et valorisation.
        val merged = portfolio.positions.groupBy { it.isin ?: "name:${it.name}" }.values.map { same ->
            same.reduce { a, b ->
                a.copy(
                    quantity = if (a.quantity != null && b.quantity != null) a.quantity + b.quantity else null,
                    valueCents = a.valueCents + b.valueCents,
                    unrealizedCents = if (a.unrealizedCents != null && b.unrealizedCents != null) a.unrealizedCents + b.unrealizedCents else null,
                )
            }
        }
        for (position in merged) {
            val securityId = securityId(position.isin, position.name, position.kind.name)
            jdbc.sql(
                "insert into position_snapshot (account_id, import_id, valuation_date, security_id, quantity, unit_price, price_date, " +
                    "value_cents, cost_basis_unit, unrealized_cents) values (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)",
            ).params(
                accountId, importId, date, securityId, position.quantity?.toPlainString(), position.unitPrice?.toPlainString(),
                position.priceDate?.toString(), position.valueCents, position.costBasisUnit?.toPlainString(), position.unrealizedCents,
            ).update()
            // Le cours imprimé sur le relevé est aussi un point de cours, daté.
            if (position.unitPrice != null && position.priceDate != null) {
                jdbc.sql("insert into price (security_id, price_date, price, source) values (?, ?, ?, 'STATEMENT') on conflict do nothing")
                    .params(securityId, position.priceDate.toString(), position.unitPrice.toPlainString()).update()
            }
        }
        for (part in portfolio.availability) {
            jdbc.sql("insert into availability_snapshot (account_id, import_id, valuation_date, available_on, value_cents) values (?, ?, ?, ?, ?)")
                .params(accountId, importId, date, part.availableOn.toString(), part.valueCents).update()
        }
        jdbc.sql(
            "insert into valuation (account_id, valuation_date, value_cents, cash_cents, contributions_cents, source, import_id) " +
                "values (?, ?, ?, ?, ?, 'STATEMENT', ?)",
        ).params(accountId, date, portfolio.printedTotalCents, portfolio.cashCents, portfolio.contributionsCents, importId).update()

        val impact = ImportImpact(
            accountsTouched = 1,
            accountsCreated = created,
            transactionsCreated = 0,
            duplicatesSkipped = 0,
            internalTransfers = 0,
            positionsRecorded = portfolio.positions.size,
        )
        jdbc.sql("update statement_import set impact_json = ? where id = ?").params(json.writeValueAsString(impact), importId).update()
        return ImportRecord(importId, now, "Moi", portfolio.parserId, portfolio.valuationDate, portfolio.valuationDate, impact)
    }

    private fun accountId(number: String): Long? =
        jdbc.sql("select id from account where iban = ?").param(number).query(Long::class.java).optional().orElse(null)

    private fun valuationExists(accountId: Long, portfolio: ParsedPortfolio) =
        jdbc.sql("select count(*) from valuation where account_id = ? and valuation_date = ? and source = 'STATEMENT'")
            .params(accountId, portfolio.valuationDate.toString()).query(Long::class.java).single() > 0

    private fun securityId(isin: String?, name: String, kind: String): Long {
        val existing = if (isin != null) {
            jdbc.sql("select id from security where isin = ?").param(isin)
        } else {
            jdbc.sql("select id from security where isin is null and name = ? and kind = ?").params(name, kind)
        }.query(Long::class.java).optional().orElse(null)
        if (existing != null) {
            jdbc.sql("update security set name = ? where id = ?").params(name, existing).update()
            return existing
        }
        return insert("insert into security (isin, name, kind) values (?, ?, ?)", isin, name, kind)
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
