package dev.budgetly.core.statement.natixis

import dev.budgetly.core.pdf.PdfDocumentText
import dev.budgetly.core.pdf.PdfLine
import dev.budgetly.core.statement.AccountRef
import dev.budgetly.core.statement.AccountType
import dev.budgetly.core.statement.StatementParseException
import dev.budgetly.core.statement.portfolio.Availability
import dev.budgetly.core.statement.portfolio.ParsedPortfolio
import dev.budgetly.core.statement.portfolio.ParsedPosition
import dev.budgetly.core.statement.portfolio.PortfolioParser
import dev.budgetly.core.statement.portfolio.PortfolioText
import dev.budgetly.core.statement.portfolio.PortfolioText.byColumn
import dev.budgetly.core.statement.portfolio.PortfolioText.centsOf
import dev.budgetly.core.statement.portfolio.PortfolioText.decimalOf
import dev.budgetly.core.statement.portfolio.PortfolioText.normalize
import dev.budgetly.core.statement.portfolio.PortfolioText.numberGroups
import dev.budgetly.core.statement.portfolio.SecurityKind
import java.time.LocalDate
import java.time.format.DateTimeFormatter

/**
 * Avis d'opération d'épargne salariale Natixis Interépargne (PEE). Chaque avis, envoyé à chaque versement,
 * contient la situation complète des avoirs : par placement et par date de disponibilité, la valeur de part,
 * le nombre de parts et le montant, jusqu'au TOTAL. C'est cette situation qui est lue ; le versement lui-même
 * (page 1) n'apporte rien de plus. Les FCPE n'ont pas d'ISIN sur l'avis : un placement est identifié par son nom.
 */
class NatixisPeeParser : PortfolioParser {
    override val id = "natixis-pee-avis-operation"
    override val institution = "Natixis Interépargne"

    /** Bords droits mesurés sur un avis réel (page « Situation de vos avoirs »). */
    private val columns = mapOf("price" to 327f, "quantity" to 390f, "value" to 454f)

    override fun supports(document: PdfDocumentText): Boolean {
        val texts = document.lines.map { normalize(it.text) }
        return texts.any { "natixis interepargne" in it } && texts.any { it.startsWith("situation de vos avoirs au") }
    }

    override fun parse(document: PdfDocumentText): ParsedPortfolio {
        val lines = document.lines
        val texts = lines.map { normalize(it.text) }

        val titleIndex = texts.indexOfFirst { it.startsWith("situation de vos avoirs au") }
        val title = lines[titleIndex]
        val valuationDate = PortfolioText.frenchDate(title.words.map { it.text })
            ?: throw StatementParseException("Avis Natixis : date de la situation illisible")
        val page = title.page

        val totalIndex = (titleIndex until lines.size).firstOrNull { lines[it].page == page && texts[it].startsWith("total") }
            ?: throw StatementParseException("Avis Natixis : total de la situation introuvable")
        val total = numberGroups(lines[totalIndex].words).lastOrNull()?.let { centsOf(it.text) }
            ?: throw StatementParseException("Avis Natixis : total de la situation illisible")

        val rows = (titleIndex until totalIndex).filter { i -> availabilityDate(lines[i]) != null }
        if (rows.isEmpty()) throw StatementParseException("Avis Natixis : aucun placement dans la situation")

        val positions = rows.mapIndexed { n, i ->
            val row = lines[i]
            val values = byColumn(numberGroups(row.words, fromX = 280f), columns)
            // Intitulé sur deux lignes, l'une juste au-dessus, l'autre juste en dessous de la ligne de chiffres.
            val previous = rows.getOrNull(n - 1)?.let { lines[it].y } ?: Float.NEGATIVE_INFINITY
            val next = rows.getOrNull(n + 1)?.let { lines[it].y } ?: Float.POSITIVE_INFINITY
            val nameLines = lines.subList(titleIndex + 1, totalIndex).filter { line ->
                line.page == page && line.y in (row.y - 8f)..(row.y + 8f) && line.y > previous + 1f && line.y < next - 1f &&
                    line.words.isNotEmpty() && line.words.first().x < 215f
            }
            val name = PortfolioText.textOf(nameLines, maxX = 215f)
            val value = values["value"] ?: throw StatementParseException("Avis Natixis : montant illisible pour un placement")
            ParsedPosition(
                isin = null,
                name = name.ifBlank { "Placement d'épargne salariale" },
                kind = SecurityKind.FUND,
                quantity = values["quantity"]?.let(::decimalOf),
                unitPrice = values["price"]?.let(::decimalOf),
                priceDate = valuationDateOf(row) ?: valuationDate,
                valueCents = centsOf(value),
            )
        }
        val availability = rows.zip(positions)
            .groupBy({ (i, _) -> availabilityDate(lines[i])!! }, { (_, p) -> p.valueCents })
            .map { (date, values) -> Availability(date, values.sum()) }
            .sortedBy { it.availableOn }

        return ParsedPortfolio(
            parserId = id,
            institution = institution,
            account = AccountRef(AccountType.PEE, "PEE Natixis", accountNumber(texts)),
            valuationDate = valuationDate,
            positions = positions,
            cashCents = 0,
            printedTotalCents = total,
            availability = availability,
        )
    }

    /** Numéro d'entreprise : un PEE par employeur, c'est l'identifiant stable du plan. */
    private fun accountNumber(texts: List<String>): String {
        val company = texts.firstNotNullOfOrNull { companyNumber.find(it)?.groupValues?.get(1) }
            ?: throw StatementParseException("Avis Natixis : numéro d'entreprise introuvable")
        return "PEE-$company"
    }

    /** Date de disponibilité : la date en colonne gauche (avant la valeur de part). */
    private fun availabilityDate(line: PdfLine): LocalDate? =
        line.words.firstOrNull { it.x in 200f..280f && date.matches(it.text) }?.let { LocalDate.parse(it.text, format) }

    /** Date de valorisation : la date en colonne droite. */
    private fun valuationDateOf(line: PdfLine): LocalDate? =
        line.words.lastOrNull { it.x > 460f && date.matches(it.text) }?.let { LocalDate.parse(it.text, format) }

    private companion object {
        val date = Regex("""\d{2}/\d{2}/\d{4}""")
        val format: DateTimeFormatter = DateTimeFormatter.ofPattern("dd/MM/yyyy")
        val companyNumber = Regex("""n° d'entreprise\s*:\s*(\d+)""")
    }
}
