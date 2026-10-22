package dev.budgetly.core.imports.tabular

import dev.budgetly.core.statement.TransactionKind
import java.math.BigDecimal
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.time.format.DateTimeParseException
import java.time.format.ResolverStyle

/** Montants et dates tels que les écrivent les exports bancaires. */
object TabularValues {

    /** Formats de date reconnus, du plus courant au moins courant dans les exports français. */
    val DATE_FORMATS = listOf("dd/MM/yyyy", "dd/MM/yy", "yyyy-MM-dd", "dd-MM-yyyy", "dd.MM.yyyy", "yyyyMMdd")

    private val formatters = DATE_FORMATS.associateWith {
        DateTimeFormatter.ofPattern(it.replace("yyyy", "uuuu").replace("yy", "uu")).withResolverStyle(ResolverStyle.STRICT)
    }

    fun parseDate(text: String, format: String): LocalDate? {
        val value = text.trim().take(10).let { if (format == "yyyyMMdd") it.take(8) else it }
        if (value.isEmpty()) return null
        val formatter = formatters[format] ?: return null
        return try {
            LocalDate.parse(value, formatter)
        } catch (e: DateTimeParseException) {
            null
        }
    }

    /** Premier format qui lit toutes les valeurs non vides ; null si aucun. */
    fun detectDateFormat(values: List<String>): String? {
        val filled = values.map { it.trim() }.filter { it.isNotEmpty() }
        if (filled.isEmpty()) return null
        return DATE_FORMATS.firstOrNull { format -> filled.all { parseDate(it, format) != null } }
    }

    /**
     * « -1 234,56 », « 1.234,56 € », « +12.30 », « (45,00) » → centimes signés. Null si ce n'est pas un montant
     * ou s'il porte plus de deux décimales. [decimalComma] : la virgule sépare les décimales (format français).
     */
    fun parseCents(text: String, decimalComma: Boolean): Long? {
        var value = text.trim()
            .replace(" ", "").replace(" ", "").replace(" ", "")
            .replace("€", "").replace("EUR", "", ignoreCase = true)
        if (value.isEmpty()) return null
        var negative = false
        if (value.startsWith("(") && value.endsWith(")")) {
            negative = true
            value = value.substring(1, value.length - 1)
        }
        when {
            value.startsWith("-") -> {
                negative = !negative
                value = value.substring(1)
            }
            value.startsWith("+") -> value = value.substring(1)
            value.endsWith("-") -> {
                negative = !negative
                value = value.dropLast(1)
            }
        }
        value = if (decimalComma) value.replace(".", "").replace(',', '.') else value.replace(",", "")
        if (!Regex("""^\d+(\.\d{1,2})?$""").matches(value)) return null
        val cents = BigDecimal(value).movePointRight(2).longValueExact()
        return if (negative) -cents else cents
    }

    /** La virgule est le séparateur décimal si un montant se termine par une virgule suivie d'un ou deux chiffres. */
    fun detectDecimalComma(values: List<String>): Boolean {
        val commaDecimal = Regex(""",\d{1,2}\s*(€|EUR)?\s*$""", RegexOption.IGNORE_CASE)
        val dotDecimal = Regex("""\.\d{1,2}\s*(€|EUR)?\s*$""", RegexOption.IGNORE_CASE)
        val comma = values.count { commaDecimal.containsMatchIn(it.trim()) }
        val dot = values.count { dotDecimal.containsMatchIn(it.trim()) }
        return comma >= dot
    }

    /** Nature d'une opération d'après les préfixes usuels des libellés bancaires. */
    fun kindOf(label: String, amountCents: Long): TransactionKind {
        val upper = label.uppercase().trim()
        return when {
            upper.startsWith("CB ") || upper.startsWith("CARTE") || upper.startsWith("ACHAT CB") || upper.startsWith("PAIEMENT CB") ->
                TransactionKind.CARD
            upper.startsWith("PRLV") || upper.startsWith("PRELEVEMENT") || upper.startsWith("PRÉLÈVEMENT") -> TransactionKind.DIRECT_DEBIT
            upper.startsWith("VIR") -> TransactionKind.TRANSFER
            upper.startsWith("RETRAIT") || upper.startsWith("DAB") -> TransactionKind.WITHDRAWAL
            upper.startsWith("VERSEMENT") || upper.startsWith("REMISE") -> TransactionKind.DEPOSIT
            listOf("FRAIS", "COTISATION", "COMMISSION", "AGIOS").any { upper.startsWith(it) } -> TransactionKind.FEE
            upper.isEmpty() && amountCents > 0 -> TransactionKind.DEPOSIT
            else -> TransactionKind.OTHER
        }
    }
}
