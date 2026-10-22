package dev.budgetly.core.statement.portfolio

import dev.budgetly.core.pdf.PdfDocumentText
import dev.budgetly.core.statement.AccountRef
import java.math.BigDecimal
import java.time.LocalDate

enum class SecurityKind { STOCK, FUND, ETF, EURO_FUND, OTHER }

/**
 * Une ligne de portefeuille à la date du relevé. La valorisation est en centimes ;
 * quantité et prix unitaires restent des décimaux exacts (jusqu'à 5 décimales sur les relevés).
 */
data class ParsedPosition(
    /** Absent pour un fonds en euros, qui n'est pas coté. */
    val isin: String?,
    val name: String,
    val kind: SecurityKind,
    val quantity: BigDecimal?,
    val unitPrice: BigDecimal?,
    val priceDate: LocalDate?,
    val valueCents: Long,
    /** Prix de revient unitaire, quand le relevé le donne (PEA). */
    val costBasisUnit: BigDecimal? = null,
    /** Plus ou moins-value latente imprimée, en centimes. */
    val unrealizedCents: Long? = null,
)

/** Part des avoirs disponible à une date (épargne salariale : parts bloquées jusqu'à leur date de disponibilité). */
data class Availability(val availableOn: LocalDate, val valueCents: Long)

/** Relevé d'une enveloppe d'investissement (PEA, assurance vie) : une photo des positions à une date. */
data class ParsedPortfolio(
    val parserId: String,
    val institution: String,
    val account: AccountRef,
    val valuationDate: LocalDate,
    val positions: List<ParsedPosition>,
    val cashCents: Long,
    /** Total imprimé sur le relevé, qui sert de contrôle. */
    val printedTotalCents: Long,
    /** Cumul des versements nets depuis l'ouverture, quand le relevé permet de le connaître. */
    val contributionsCents: Long? = null,
    /** Total imprimé des plus-values latentes, s'il existe (contrôle supplémentaire). */
    val printedUnrealizedCents: Long? = null,
    /** Répartition par date de disponibilité, quand le relevé la donne (PEE). */
    val availability: List<Availability> = emptyList(),
) {
    val computedTotalCents: Long get() = positions.sumOf { it.valueCents } + cashCents

    /** Somme des lignes + liquidités = total imprimé ; somme des plus-values = total imprimé, s'il existe. */
    val balanced: Boolean
        get() = computedTotalCents == printedTotalCents &&
            (printedUnrealizedCents == null || positions.sumOf { it.unrealizedCents ?: 0 } == printedUnrealizedCents) &&
            (availability.isEmpty() || availability.sumOf { it.valueCents } == printedTotalCents)
}

/** Un parser de relevé de portefeuille : un établissement × un type de document. */
interface PortfolioParser {
    val id: String
    val institution: String

    fun supports(document: PdfDocumentText): Boolean

    fun parse(document: PdfDocumentText): ParsedPortfolio
}
