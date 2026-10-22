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

/**
 * Bulletin de situation annuel de l'assurance vie EasyVie (CNP, distribuée par EasyBourse).
 * Lu : capital total, support(s) en euros, unités de compte (ISIN, parts, VL, capital) jusqu'au TOTAL,
 * cumul des versements (page fiscale). Les annexes jointes (offres, DDA) sont ignorées.
 */
class EasyVieParser : PortfolioParser {
    override val id = "easyvie-bulletin-annuel"
    override val institution = "EasyBourse (CNP Assurances)"

    private val unitColumns = mapOf("quantity" to 300f, "price" to 380f, "value" to 462f, "performance" to 534f)

    override fun supports(document: PdfDocumentText): Boolean {
        val texts = document.lines.map { normalize(it.text) }
        return texts.any { "easyvie" in it } && texts.any { "bulletin de situation" in it }
    }

    override fun parse(document: PdfDocumentText): ParsedPortfolio {
        val lines = document.lines
        val texts = lines.map { normalize(it.text) }

        val number = contractNumber(lines, texts)
        val capitalIndex = texts.indexOfFirst { it.startsWith("votre capital au") }
        if (capitalIndex < 0) throw StatementParseException("Bulletin EasyVie : capital total introuvable")
        val capitalLine = lines[capitalIndex]
        val valuationDate = capitalLine.words.firstNotNullOfOrNull { fullDate.matchEntire(it.text) }
            ?.let { LocalDate.parse(it.value, fullFormat) }
            ?: throw StatementParseException("Bulletin EasyVie : date du bulletin illisible")
        val printedTotal = numberGroups(capitalLine.words, fromX = 400f).lastOrNull()?.let { centsOf(it.text) }
            ?: throw StatementParseException("Bulletin EasyVie : montant du capital illisible")

        val euroStart = texts.indexOfFirst { it.trim() == "support(s) en euros" }
        val unitStart = texts.indexOfFirst { "en unites de compte" in it && it.startsWith("support") }
        if (euroStart < 0 || unitStart < euroStart) throw StatementParseException("Bulletin EasyVie : répartition du capital introuvable")
        val unitEnd = (unitStart until lines.size).firstOrNull { texts[it].startsWith("total") }
            ?: throw StatementParseException("Bulletin EasyVie : total des unités de compte introuvable")

        val positions = euroFunds(lines, euroStart, unitStart, valuationDate) +
            unitLinked(lines, texts, unitStart, unitEnd, valuationDate)

        return ParsedPortfolio(
            parserId = id,
            institution = institution,
            account = AccountRef(AccountType.LIFE_INSURANCE, "Assurance vie EasyVie", number),
            valuationDate = valuationDate,
            positions = positions,
            cashCents = 0,
            printedTotalCents = printedTotal,
            contributionsCents = contributions(lines, texts, valuationDate.year),
        )
    }

    private fun contractNumber(lines: List<PdfLine>, texts: List<String>): String {
        val label = texts.indexOfFirst { it.startsWith("reference a rappeler") }
        if (label < 0) throw StatementParseException("Bulletin EasyVie : référence du contrat introuvable")
        val line = lines.drop(label + 1).firstOrNull { it.page == lines[label].page && (it.words.firstOrNull()?.x ?: 999f) < 120f }
            ?: throw StatementParseException("Bulletin EasyVie : référence du contrat illisible")
        return line.words.filter { it.x < 200f }.joinToString("") { it.text }
    }

    /** Support(s) en euros : ligne portant le capital (colonne de droite), intitulé juste en dessous. */
    private fun euroFunds(lines: List<PdfLine>, from: Int, to: Int, date: LocalDate): List<ParsedPosition> =
        (from until to).mapNotNull { i ->
            val line = lines[i]
            val capital = numberGroups(line.words, fromX = 400f).lastOrNull { it.xEnd in 480f..530f && "," in it.text } ?: return@mapNotNull null
            val name = lines.subList(i + 1, to)
                .firstOrNull { it.page == line.page && it.y - line.y in 0f..10f && it.words.first().x < 200f }
                ?.let { PortfolioText.textOf(listOf(it), maxX = 200f) }
            ParsedPosition(
                isin = null,
                name = name ?: "Fonds en euros",
                kind = SecurityKind.EURO_FUND,
                quantity = null,
                unitPrice = null,
                priceDate = date,
                valueCents = centsOf(capital.text),
            )
        }

    /** Unités de compte : une ligne ISIN, puis parts / VL / capital / performance et l'intitulé dans les ~20 pt suivants. */
    private fun unitLinked(lines: List<PdfLine>, texts: List<String>, from: Int, to: Int, date: LocalDate): List<ParsedPosition> {
        val anchors = (from until to).filter { i ->
            val first = lines[i].words.firstOrNull()
            first != null && first.x < 120f && PortfolioText.isinPattern.matches(first.text)
        }
        var section = ""
        val sectionTitles = (from until to).filter { texts[it].startsWith("fonds ") && numberGroups(lines[it].words).isEmpty() }
        return anchors.mapIndexed { n, i ->
            val anchor = lines[i]
            section = sectionTitles.lastOrNull { it < i }?.let { texts[it] } ?: section
            val next = anchors.getOrNull(n + 1)?.let { lines[it] }
            val window = lines.subList(i, to).filter { line ->
                line.page == anchor.page && line.y >= anchor.y - 1f && line.y < anchor.y + 22f &&
                    (next == null || next.page != line.page || line.y < next.y)
            }
            val values = window.fold(mapOf<String, String>()) { acc, line -> acc + byColumn(numberGroups(line.words, 200f), unitColumns) }
            val isin = anchor.words.first().text
            val name = window.drop(1).let { PortfolioText.textOf(it, maxX = 260f) }
            val value = values["value"] ?: throw StatementParseException("Bulletin EasyVie : capital illisible pour une unité de compte")
            ParsedPosition(
                isin = isin,
                name = name.ifBlank { isin },
                kind = if ("ETF" in name.uppercase()) SecurityKind.ETF else if ("fonds" in section) SecurityKind.FUND else SecurityKind.OTHER,
                quantity = values["quantity"]?.let(::decimalOf),
                unitPrice = values["price"]?.let(::decimalOf),
                priceDate = date,
                valueCents = centsOf(value),
            )
        }
    }

    /**
     * Cumul des versements : versements bruts avant et après le 27/09/2017 au 31/12 de l'année précédente
     * (page fiscale), plus les versements de l'année du bulletin. Null si l'un des deux manque.
     */
    private fun contributions(lines: List<PdfLine>, texts: List<String>, year: Int): Long? {
        val header = texts.indexOfFirst { it.startsWith("montant des versements bruts") }
        val previous = if (header < 0) {
            null
        } else {
            lines.drop(header + 1).take(4).firstNotNullOfOrNull { line ->
                numberGroups(line.words).takeIf { it.size == 2 }?.sumOf { centsOf(it.text) }
            }
        }
        val ofYear = texts.indexOfFirst { it.startsWith("vos versements de cotisations") && "$year" in it }
            .takeIf { it >= 0 }
            ?.let { numberGroups(lines[it].words, fromX = 470f).lastOrNull()?.let { g -> centsOf(g.text) } }
        return if (previous != null && ofYear != null) previous + ofYear else null
    }

    private companion object {
        val fullDate = Regex("""\d{2}/\d{2}/\d{4}""")
        val fullFormat: DateTimeFormatter = DateTimeFormatter.ofPattern("dd/MM/yyyy")
    }
}
