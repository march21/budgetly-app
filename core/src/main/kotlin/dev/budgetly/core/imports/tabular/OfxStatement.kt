package dev.budgetly.core.imports.tabular

import dev.budgetly.core.statement.Balance
import dev.budgetly.core.statement.ParsedTransaction
import java.time.LocalDate

/** Relevé OFX (1.x SGML ou 2.x XML) : identifiant du compte, opérations, solde comptable de fin si présent. */
data class OfxRead(
    val accountId: String?,
    val transactions: List<ParsedTransaction>,
    val closing: Balance?,
    val problems: List<String>,
)

object OfxStatement {
    private val transactionBlock = Regex("<STMTTRN>(.*?)(</STMTTRN>|(?=<STMTTRN>)|(?=</BANKTRANLIST>))", setOf(RegexOption.DOT_MATCHES_ALL, RegexOption.IGNORE_CASE))
    private val ledgerBlock = Regex("<LEDGERBAL>(.*?)(</LEDGERBAL>|(?=<AVAILBAL>)|(?=</STMTRS>))", setOf(RegexOption.DOT_MATCHES_ALL, RegexOption.IGNORE_CASE))

    /** Valeur d'un élément, qu'il soit fermé (XML) ou non (SGML : la valeur court jusqu'au prochain « < »). */
    private fun field(block: String, name: String): String? =
        Regex("<$name>([^<\\r\\n]*)", RegexOption.IGNORE_CASE).find(block)?.groupValues?.get(1)?.trim()?.takeIf { it.isNotEmpty() }
            ?.let(::unescape)

    private fun unescape(text: String) =
        text.replace("&lt;", "<").replace("&gt;", ">").replace("&quot;", "\"").replace("&apos;", "'").replace("&amp;", "&")

    /** « 20261015 », « 20261015120000 », « 20261015120000.000[+1:CET] » → date. */
    private fun date(text: String?): LocalDate? {
        val digits = text?.takeWhile(Char::isDigit) ?: return null
        if (digits.length < 8) return null
        return TabularValues.parseDate(digits.take(8), "yyyyMMdd")
    }

    private fun cents(text: String?): Long? {
        val value = text ?: return null
        // Le point est le séparateur décimal de la norme ; certaines banques françaises écrivent une virgule.
        return TabularValues.parseCents(value, decimalComma = value.contains(',') && !value.contains('.'))
    }

    fun read(text: String): OfxRead {
        val problems = mutableListOf<String>()
        val bank = listOfNotNull(field(text, "BANKID"), field(text, "BRANCHID"), field(text, "ACCTID")).joinToString("")
        val accountId = bank.ifEmpty { null }?.uppercase()?.replace(" ", "")

        val transactions = transactionBlock.findAll(text).mapIndexedNotNull { index, match ->
            val block = match.groupValues[1]
            val booked = date(field(block, "DTPOSTED"))
            val amount = cents(field(block, "TRNAMT"))
            if (booked == null || amount == null) {
                problems += "Opération n° ${index + 1} : date ou montant illisible."
                return@mapIndexedNotNull null
            }
            val name = field(block, "NAME").orEmpty()
            val memo = field(block, "MEMO").orEmpty()
            val label = name.ifEmpty { memo }.replace(Regex("\\s+"), " ").ifEmpty { "(sans libellé)" }
            ParsedTransaction(
                bookingDate = booked,
                operationDate = date(field(block, "DTUSER")) ?: booked,
                amountCents = amount,
                label = label,
                details = if (name.isNotEmpty() && memo.isNotEmpty() && memo != name) memo else "",
                kind = TabularValues.kindOf(label, amount),
            )
        }.toList().sortedBy { it.bookingDate }

        val ledger = ledgerBlock.find(text)?.groupValues?.get(1)
        val closing = ledger?.let { block ->
            val cents = cents(field(block, "BALAMT"))
            val asOf = date(field(block, "DTASOF"))
            if (cents != null && asOf != null) Balance(asOf, cents) else null
        }
        if (transactions.isEmpty() && problems.isEmpty()) problems += "Aucune opération dans ce fichier OFX."
        return OfxRead(accountId, transactions, closing, problems)
    }
}
