package dev.budgetly.core.statement.lbp

import dev.budgetly.core.pdf.PdfDocumentText
import dev.budgetly.core.pdf.PdfLine
import dev.budgetly.core.pdf.PdfWord
import dev.budgetly.core.statement.AccountRef
import dev.budgetly.core.statement.AccountType
import dev.budgetly.core.statement.Balance
import dev.budgetly.core.statement.FrenchAmounts
import dev.budgetly.core.statement.ParsedAccountStatement
import dev.budgetly.core.statement.ParsedStatement
import dev.budgetly.core.statement.ParsedTransaction
import dev.budgetly.core.statement.StatementParseException
import dev.budgetly.core.statement.StatementParser
import dev.budgetly.core.statement.TransactionKind
import java.text.Normalizer
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import kotlin.math.abs

/**
 * Relevé de comptes mensuel La Banque Postale (CCP + livrets dans un même PDF).
 *
 * Principes, établis sur un relevé réel :
 * - les polices ont un encodage propre (accents et « € » mal décodés) : on ne s'appuie que sur
 *   des mots ASCII (« Ancien solde », « IBAN », « Nouveau solde »), des formes (dates, montants)
 *   et des positions ;
 * - débit ou crédit se décide par la colonne : bord droit du montant le plus proche du bord droit
 *   de l'en-tête « Débit » ou « Crédit » ;
 * - les dates d'opération sont sans année : déduite de la date du nouveau solde.
 */
class LbpAccountStatementParser : StatementParser {
    override val id = "lbp-releve-comptes"
    override val institution = "La Banque Postale"

    override fun supports(document: PdfDocumentText): Boolean {
        val texts = document.lines.map { normalize(it.text) }
        val isLbp = texts.any { "labanquepostale" in it || ("banque" in it && "postale" in it) }
        val hasIban = document.lines.any { it.words.firstOrNull()?.text == "IBAN" }
        val hasOpening = texts.any { it.startsWith("ancien solde") }
        return isLbp && hasIban && hasOpening
    }

    override fun parse(document: PdfDocumentText): ParsedStatement {
        val accounts = mutableListOf<ParsedAccountStatement>()
        var columns = Columns.DEFAULT
        var current: AccountBuilder? = null
        var previousLine: PdfLine? = null
        var lastTransaction: RawTransaction? = null

        for (line in document.lines) {
            val words = line.words
            val first = words.firstOrNull() ?: continue
            val text = normalize(line.text)

            when {
                isHeader(words) -> {
                    columns = Columns.from(words)
                    lastTransaction = null
                }

                first.text == "IBAN" -> {
                    if (current != null) throw error(line, "nouveau compte avant la fin du précédent")
                    val title = previousLine ?: throw error(line, "intitulé de compte introuvable")
                    current = AccountBuilder(accountRef(title, iban(words)))
                    lastTransaction = null
                }

                current == null -> Unit

                text.startsWith("ancien solde") -> {
                    current.opening = balance(line, columns)
                    lastTransaction = null
                }

                text.startsWith("total des op") -> {
                    for ((cents, side) in amounts(words, columns)) {
                        if (side == Side.DEBIT) current.printedDebit = cents else current.printedCredit = cents
                    }
                    lastTransaction = null
                }

                text.startsWith("nouveau solde") -> {
                    current.closing = balance(line, columns)
                    accounts += current.build(line.page)
                    current = null
                    lastTransaction = null
                }

                isTransactionStart(first) -> {
                    if (current.opening == null) throw error(line, "opération avant l'ancien solde")
                    val found = amounts(words, columns)
                    if (found.size != 1) throw error(line, "montant d'opération illisible")
                    val (cents, side) = found.single()
                    val label = words.drop(1).filter { it.x < columns.amountZoneStart }.joinToString(" ") { it.text }
                    lastTransaction = RawTransaction(first.text, label, if (side == Side.DEBIT) -cents else cents)
                    current.transactions += lastTransaction
                }

                isDetail(first) && lastTransaction != null -> lastTransaction.details += line.text

                else -> Unit
            }
            previousLine = line
        }

        if (current != null) throw StatementParseException("Relevé tronqué : nouveau solde absent pour un compte")
        if (accounts.isEmpty()) throw StatementParseException("Aucun compte trouvé dans le relevé")
        return ParsedStatement(id, institution, accounts)
    }

    private enum class Side { DEBIT, CREDIT }

    /** Colonnes de montants, mesurées sur l'en-tête « Date | Opérations | Débit | Crédit ». */
    private data class Columns(val amountZoneStart: Float, val debitRight: Float, val creditRight: Float) {
        fun side(xEnd: Float) = if (abs(xEnd - debitRight) <= abs(xEnd - creditRight)) Side.DEBIT else Side.CREDIT

        companion object {
            val DEFAULT = Columns(amountZoneStart = 395f, debitRight = 464f, creditRight = 539f)

            fun from(header: List<PdfWord>): Columns {
                val credit = header.firstOrNull { it.x >= 480f } ?: return DEFAULT
                val debitWords = header.filter { it.x >= 380f && it.x < credit.x }
                if (debitWords.isEmpty()) return DEFAULT
                return Columns(
                    amountZoneStart = debitWords.first().x - 40f,
                    debitRight = debitWords.maxOf { it.xEnd },
                    creditRight = header.filter { it.x >= credit.x }.maxOf { it.xEnd },
                )
            }
        }
    }

    private class RawTransaction(val dayMonth: String, val label: String, val amountCents: Long) {
        val details = mutableListOf<String>()
    }

    private inner class AccountBuilder(val ref: AccountRef) {
        var opening: Balance? = null
        var closing: Balance? = null
        var printedDebit: Long? = null
        var printedCredit: Long? = null
        val transactions = mutableListOf<RawTransaction>()

        fun build(page: Int): ParsedAccountStatement {
            val open = opening ?: throw StatementParseException("Ancien solde absent (page $page)")
            val close = closing ?: throw StatementParseException("Nouveau solde absent (page $page)")
            return ParsedAccountStatement(
                account = ref,
                opening = open,
                closing = close,
                printedTotalDebitCents = printedDebit,
                printedTotalCreditCents = printedCredit,
                transactions = transactions.map { it.resolve(close.date) },
            )
        }
    }

    private fun RawTransaction.resolve(closingDate: LocalDate): ParsedTransaction {
        val booking = bookingDate(dayMonth, closingDate)
        return ParsedTransaction(
            bookingDate = booking,
            operationDate = operationDate(label, booking) ?: booking,
            amountCents = amountCents,
            label = label,
            details = details.joinToString("\n"),
            kind = kindOf(label),
        )
    }

    private fun isHeader(words: List<PdfWord>) =
        words.size >= 3 && words.first().text == "Date" && words.last().x >= 480f

    private fun isTransactionStart(first: PdfWord) = first.x < 80f && dayMonth.matches(first.text)

    private fun isDetail(first: PdfWord) = first.x >= 80f && first.x < 110f

    /** Montants de la zone de droite, regroupés par proximité (« 1 » + « 057,48 »), avec leur colonne. */
    private fun amounts(words: List<PdfWord>, columns: Columns): List<Pair<Long, Side>> {
        val zone = words.filter { it.x >= columns.amountZoneStart && it.text != "+" && it.text != "-" }
        val groups = mutableListOf<MutableList<PdfWord>>()
        for (word in zone) {
            val group = groups.lastOrNull()
            if (group != null && word.x - group.last().xEnd < 6f) group += word else groups += mutableListOf(word)
        }
        return groups.mapNotNull { group ->
            FrenchAmounts.parseCents(group.joinToString("") { it.text })?.let { it to columns.side(group.last().xEnd) }
        }
    }

    private fun balance(line: PdfLine, columns: Columns): Balance {
        val date = line.words.firstNotNullOfOrNull { fullDate.matchEntire(it.text) }
            ?.let { LocalDate.parse(it.value, slashDate) }
            ?: throw error(line, "date de solde illisible")
        val (cents, side) = amounts(line.words, columns).singleOrNull() ?: throw error(line, "montant de solde illisible")
        return Balance(date, if (side == Side.DEBIT) -cents else cents)
    }

    private fun iban(words: List<PdfWord>): String {
        val afterColon = words.dropWhile { it.text != ":" }.drop(1).takeWhile { it.text != "|" }
        val iban = afterColon.joinToString("") { it.text }.uppercase()
        if (!ibanPattern.matches(iban)) throw StatementParseException("Relevé Banque Postale : IBAN illisible")
        return iban
    }

    private fun accountRef(title: PdfLine, iban: String): AccountRef {
        val words = title.words.map { it.text }
        val text = normalize(title.text)
        val livretIndex = words.indexOf("Livret")
        return when {
            "courant" in text -> AccountRef(AccountType.CHECKING, "Compte Courant Postal", iban)
            livretIndex >= 0 && words.getOrNull(livretIndex + 1) == "A" -> AccountRef(AccountType.LIVRET_A, "Livret A", iban)
            "durable" in text || "solidaire" in text -> AccountRef(AccountType.LDDS, "LDDS", iban)
            else -> {
                // Intitulé avant « n° » (le symbole ° peut être mal décodé).
                val name = title.words.takeWhile { !(it.text.startsWith("n") && it.text.length <= 3) }
                    .joinToString(" ") { it.text }
                AccountRef(AccountType.SAVINGS, name.ifBlank { "Épargne" }, iban)
            }
        }
    }

    private fun bookingDate(dayMonth: String, closing: LocalDate): LocalDate {
        val (day, month) = dayMonth.split("/").map { it.toInt() }
        val sameYear = LocalDate.of(closing.year, month, day)
        return if (sameYear.isAfter(closing)) sameYear.minusYears(1) else sameYear
    }

    /** Date d'achat CB (« 09.09.26 ») ou de versement (« 06/10/26 ») présente dans le libellé. */
    private fun operationDate(label: String, booking: LocalDate): LocalDate? {
        val match = cardDate.findAll(label).lastOrNull() ?: return null
        val (day, month, year) = match.destructured
        val date = runCatching { LocalDate.of(2000 + year.toInt(), month.toInt(), day.toInt()) }.getOrNull() ?: return null
        return date.takeIf { !it.isAfter(booking) && it.isAfter(booking.minusDays(60)) }
    }

    private fun kindOf(label: String): TransactionKind {
        val upper = normalize(label).uppercase()
        return when {
            upper.startsWith("ACHAT CB") -> TransactionKind.CARD
            upper.startsWith("PRELEVEMENT") -> TransactionKind.DIRECT_DEBIT
            upper.startsWith("VIREMENT") -> TransactionKind.TRANSFER
            upper.startsWith("VERSEMENT") -> TransactionKind.DEPOSIT
            upper.startsWith("RETRAIT") -> TransactionKind.WITHDRAWAL
            feePrefixes.any { upper.startsWith(it) } -> TransactionKind.FEE
            else -> TransactionKind.OTHER
        }
    }

    private fun error(line: PdfLine, reason: String) =
        StatementParseException("Relevé Banque Postale illisible page ${line.page} : $reason")

    private companion object {
        val dayMonth = Regex("""^\d{2}/\d{2}$""")
        val fullDate = Regex("""\d{2}/\d{2}/\d{4}""")
        val cardDate = Regex("""\b(\d{2})[./](\d{2})[./](\d{2})\b""")
        val ibanPattern = Regex("""^[A-Z]{2}\d{2}[A-Z0-9]{10,30}$""")
        val slashDate: DateTimeFormatter = DateTimeFormatter.ofPattern("dd/MM/yyyy")
        val feePrefixes = listOf("COTISATION", "FRAIS", "COMMISSION", "MINIMUM FORFAITAIRE", "AVANTAGE", "INTERETS", "AGIOS")

        fun normalize(text: String): String =
            Normalizer.normalize(text, Normalizer.Form.NFD).replace(Regex("\\p{M}"), "").lowercase()
    }
}
