package dev.budgetly.core.imports

import dev.budgetly.core.statement.AccountType
import dev.budgetly.core.statement.TransactionKind
import org.springframework.http.HttpStatus
import java.time.Instant
import java.time.LocalDate

/** Erreur métier renvoyée telle quelle à l'UI (message rédigé pour l'utilisateur, sans donnée personnelle). */
class ApiException(val status: HttpStatus, val code: String, message: String) : RuntimeException(message)

enum class PreviewStatus { READY, REJECTED, ALREADY_IMPORTED }

data class AccountPreview(
    val key: Int,
    val type: AccountType,
    val name: String,
    val ibanMasked: String,
    val newAccount: Boolean,
    val openingDate: LocalDate,
    val openingCents: Long,
    val closingDate: LocalDate,
    val closingCents: Long,
    val expectedClosingCents: Long,
    val balanceOk: Boolean,
    val transactionCount: Int,
    val newTransactionCount: Int,
    val duplicateCount: Int,
)

data class PreviewTransaction(
    val accountKey: Int,
    val bookingDate: LocalDate,
    val operationDate: LocalDate,
    val amountCents: Long,
    val label: String,
    val kind: TransactionKind,
    val duplicate: Boolean,
    val internalTransfer: Boolean,
)

enum class DocumentType { ACCOUNT_STATEMENT, PORTFOLIO }

data class PortfolioPositionPreview(
    val isin: String?,
    val name: String,
    val kind: dev.budgetly.core.statement.portfolio.SecurityKind,
    val quantity: String?,
    val unitPrice: String?,
    val valueCents: Long,
    val unrealizedCents: Long?,
)

/** Avoirs disponibles à une date (parts d'épargne salariale bloquées jusque-là). */
data class AvailabilityView(val availableOn: LocalDate, val valueCents: Long)

data class PortfolioPreview(
    val type: AccountType,
    val name: String,
    val numberMasked: String,
    val newAccount: Boolean,
    val valuationDate: LocalDate,
    val printedTotalCents: Long,
    val computedTotalCents: Long,
    val balanced: Boolean,
    val cashCents: Long,
    val contributionsCents: Long?,
    val availability: List<AvailabilityView> = emptyList(),
    val positions: List<PortfolioPositionPreview>,
)

data class ImportPreview(
    val previewId: String?,
    val status: PreviewStatus,
    val parserId: String?,
    val institution: String?,
    val periodStart: LocalDate?,
    val periodEnd: LocalDate?,
    val accounts: List<AccountPreview>,
    val transactions: List<PreviewTransaction>,
    val issues: List<String>,
    val documentType: DocumentType = DocumentType.ACCOUNT_STATEMENT,
    val portfolio: PortfolioPreview? = null,
)

/** Ce qu'un import a produit : c'est tout ce que le journal conserve (avec la date et l'empreinte du fichier). */
data class ImportImpact(
    val accountsTouched: Int,
    val accountsCreated: Int,
    val transactionsCreated: Int,
    val duplicatesSkipped: Int,
    val internalTransfers: Int,
    val positionsRecorded: Int = 0,
)

data class ImportRecord(
    val id: Long,
    val importedAt: Instant,
    val importedBy: String,
    val parserId: String,
    val periodStart: LocalDate,
    val periodEnd: LocalDate,
    val impact: ImportImpact,
    val cancelledAt: Instant? = null,
    /** Vrai si c'est l'import le plus récent de chacun des comptes qu'il touche. */
    val cancellable: Boolean = false,
)
