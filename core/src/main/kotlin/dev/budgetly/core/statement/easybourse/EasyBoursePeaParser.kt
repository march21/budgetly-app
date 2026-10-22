package dev.budgetly.core.statement.easybourse

import dev.budgetly.core.pdf.PdfDocumentText
import dev.budgetly.core.pdf.PdfLine
import dev.budgetly.core.statement.AccountRef
import dev.budgetly.core.statement.AccountType
import dev.budgetly.core.statement.StatementParseException
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
import kotlin.math.abs

/**
 * « Évaluation de portefeuille » d'un PEA EasyBourse : une ligne par valeur (ISIN, quantité, cours,
 * valorisation, prix de revient, plus-value latente), noms parfois répartis sur plusieurs lignes,
 * liquidités et total du portefeuille. Colonnes repérées par leur bord droit, mesuré sur un relevé réel.
 */
class EasyBoursePeaParser : PortfolioParser {
    override val id = "easybourse-pea-evaluation"
    override val institution = "EasyBourse"

    private val columns = mapOf(
        "quantity" to 248f,
        "price" to 296f,
        "value" to 415f,
        "weight" to 449f,
        "costBasis" to 501f,
        "unrealized" to 564f,
    )

    override fun supports(document: PdfDocumentText): Boolean {
        val texts = document.lines.map { normalize(it.text) }
        return texts.any { "evaluation de portefeuille" in it } && texts.any { "compte pea" in it }
    }

    override fun parse(document: PdfDocumentText): ParsedPortfolio {
        val lines = document.lines
        val texts = lines.map { normalize(it.text) }

        val accountLine = lines.indices.firstOrNull { "votre compte pea" in texts[it] }
            ?: throw StatementParseException("Relevé PEA EasyBourse : numéro de compte introuvable")
        val number = lines[accountLine].words.last().text.filter { it.isLetterOrDigit() }
        if (number.length < 5) throw StatementParseException("Relevé PEA EasyBourse : numéro de compte illisible")

        val valuationDate = lines.firstNotNullOfOrNull { line ->
            if (normalize(line.words.firstOrNull()?.text ?: "") == "au") PortfolioText.frenchDate(line.words.map { it.text }) else null
        } ?: throw StatementParseException("Relevé PEA EasyBourse : date d'évaluation introuvable")

        val contributions = lines.indices.firstOrNull { texts[it].startsWith("cumul de vos versements") }
            ?.let { numberGroups(lines[it].words, fromX = 250f).lastOrNull()?.let { g -> centsOf(g.text) } }

        val positions = mutableListOf<ParsedPosition>()
        var section = ""
        var cash = 0L
        var total: Map<String, String>? = null
        for ((index, line) in lines.withIndex()) {
            val text = texts[index]
            val isinWord = line.words.firstOrNull { PortfolioText.isinPattern.matches(it.text) }
            when {
                isinWord != null -> positions += position(line, isinWord.text, lines, section)
                text.startsWith("total portefeuille") -> total = byColumn(numberGroups(line.words, 205f), columns)
                text.startsWith("especes") ->
                    byColumn(numberGroups(line.words, 205f), columns)["value"]?.let { cash += centsOf(it) }
                isSectionTitle(line, text) -> section = text
            }
        }

        val printed = total ?: throw StatementParseException("Relevé PEA EasyBourse : total du portefeuille introuvable")
        return ParsedPortfolio(
            parserId = id,
            institution = institution,
            account = AccountRef(AccountType.PEA, "PEA EasyBourse", number),
            valuationDate = valuationDate,
            positions = positions,
            cashCents = cash,
            printedTotalCents = printed["value"]?.let(::centsOf)
                ?: throw StatementParseException("Relevé PEA EasyBourse : montant total illisible"),
            contributionsCents = contributions,
            printedUnrealizedCents = printed["unrealized"]?.let(::centsOf),
        )
    }

    private fun position(line: PdfLine, isin: String, lines: List<PdfLine>, section: String): ParsedPosition {
        val values = byColumn(numberGroups(line.words, 205f), columns)
        val nameLines = lines.filter { it.page == line.page && abs(it.y - line.y) <= 6f }.sortedBy { it.y }
        val name = PortfolioText.textOf(nameLines, maxX = 140f)
        val priceDate = line.words.firstNotNullOfOrNull { shortDate.matchEntire(it.text) }
            ?.let { LocalDate.parse(it.value, shortFormat) }
        val value = values["value"] ?: throw StatementParseException("Relevé PEA EasyBourse : valorisation illisible pour une ligne")
        return ParsedPosition(
            isin = isin,
            name = name.ifBlank { isin },
            kind = kindOf(section, name),
            quantity = values["quantity"]?.let(::decimalOf),
            unitPrice = values["price"]?.let(::decimalOf),
            priceDate = priceDate,
            valueCents = centsOf(value),
            costBasisUnit = values["costBasis"]?.let(::decimalOf),
            unrealizedCents = values["unrealized"]?.let(::centsOf),
        )
    }

    private fun isSectionTitle(line: PdfLine, text: String) =
        line.words.size <= 4 && sectionWords.any { it in text } && numberGroups(line.words).isEmpty()

    private fun kindOf(section: String, name: String): SecurityKind = when {
        "ETF" in name.uppercase() -> SecurityKind.ETF
        "actions" in section -> SecurityKind.STOCK
        "opcvm" in section -> SecurityKind.FUND
        else -> SecurityKind.OTHER
    }

    private companion object {
        val shortDate = Regex("""\d{2}/\d{2}/\d{2}""")
        val shortFormat: DateTimeFormatter = DateTimeFormatter.ofPattern("dd/MM/yy")
        val sectionWords = listOf("actions", "opcvm", "autres", "obligations", "liquidites")
    }
}
