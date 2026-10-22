package dev.budgetly.core.imports.tabular

import dev.budgetly.core.statement.Balance
import dev.budgetly.core.statement.ParsedTransaction
import java.text.Normalizer

/**
 * Correspondance des colonnes d'un CSV (indices à partir de 0). Le montant vient soit d'une colonne signée,
 * soit d'une colonne débit et d'une colonne crédit. La colonne de solde, si elle existe, sert au contrôle.
 */
data class CsvMapping(
    /** Ligne d'en-tête (null : pas d'en-tête). Les données commencent à [firstDataRow]. */
    val headerRow: Int?,
    val firstDataRow: Int,
    val dateColumn: Int,
    val operationDateColumn: Int? = null,
    val labelColumn: Int,
    val detailColumns: List<Int> = emptyList(),
    val amountColumn: Int? = null,
    val debitColumn: Int? = null,
    val creditColumn: Int? = null,
    val balanceColumn: Int? = null,
    val dateFormat: String,
    val decimalComma: Boolean,
)

/** Résultat de la lecture : opérations en ordre chronologique, soldes lus dans le fichier, problèmes par ligne. */
data class TabularParse(
    val transactions: List<ParsedTransaction>,
    val opening: Balance?,
    val closing: Balance?,
    val problems: List<String>,
)

object CsvStatement {

    private fun normalize(text: String) =
        Normalizer.normalize(text, Normalizer.Form.NFD).replace(Regex("\\p{M}"), "").lowercase().trim()

    private val headerWords = listOf("date", "libelle", "label", "montant", "amount", "debit", "credit", "solde", "balance", "operation", "description")

    /** Correspondance proposée d'après les en-têtes, ou d'après le contenu s'il n'y en a pas. Null si rien d'exploitable. */
    fun suggest(rows: List<List<String>>): CsvMapping? {
        if (rows.isEmpty()) return null
        val headerRow = rows.take(20).indexOfFirst { row ->
            row.count { cell -> val n = normalize(cell); headerWords.any { n.contains(it) } } >= 2
        }.takeIf { it >= 0 }
        val firstDataRow = (headerRow ?: -1) + 1
        val data = rows.drop(firstDataRow).take(200)
        if (data.isEmpty()) return null
        val width = data.maxOf { it.size }
        fun values(column: Int) = data.map { it.getOrElse(column) { "" } }

        val dateColumns = (0 until width).filter { TabularValues.detectDateFormat(values(it)) != null && values(it).any(String::isNotBlank) }
        val names = headerRow?.let { rows[it].map(::normalize) } ?: emptyList()
        fun named(vararg words: String, exclude: Set<Int> = emptySet()) =
            names.indices.firstOrNull { i -> i !in exclude && words.any { names[i].contains(it) } }

        val dateColumn = (if (names.isNotEmpty()) dateColumns.firstOrNull { !names.getOrElse(it) { "" }.contains("valeur") } else null)
            ?: dateColumns.firstOrNull() ?: return null
        val operationDateColumn = if (names.isNotEmpty()) {
            dateColumns.firstOrNull { it != dateColumn && names.getOrElse(it) { "" }.contains("operation") }
        } else {
            null
        }
        val used = mutableSetOf(dateColumn).apply { operationDateColumn?.let(::add) }
        val debit = named("debit", exclude = used)?.also(used::add)
        val credit = named("credit", exclude = used)?.also(used::add)
        val balance = named("solde", "balance", exclude = used)?.also(used::add)
        var amount = if (debit == null || credit == null) named("montant", "amount", exclude = used)?.also(used::add) else null

        val probe = TabularValues.detectDecimalComma(listOfNotNull(amount, debit, credit, balance).flatMap(::values))
        fun numeric(column: Int) = values(column).filter(String::isNotBlank).let { v ->
            v.isNotEmpty() && v.all { TabularValues.parseCents(it, probe) != null || TabularValues.parseCents(it, !probe) != null }
        }
        if (amount == null && (debit == null || credit == null)) {
            amount = (0 until width).firstOrNull { it !in used && !dateColumns.contains(it) && numeric(it) }?.also(used::add)
                ?: return null
        }
        val label = named("libelle", "label", "description", "intitule", "nature", exclude = used + dateColumns)
            ?: (0 until width).filter { it !in used && it !in dateColumns }.maxByOrNull { c -> values(c).sumOf { it.length } }
            ?: return null
        used += label
        val details = if (names.isNotEmpty()) {
            names.indices.filter { i -> i !in used && i !in dateColumns && listOf("detail", "information", "complement", "reference").any { names[i].contains(it) } }
        } else {
            emptyList()
        }
        val decimalComma = TabularValues.detectDecimalComma(listOfNotNull(amount, debit, credit, balance).flatMap(::values))
        return CsvMapping(
            headerRow = headerRow,
            firstDataRow = firstDataRow,
            dateColumn = dateColumn,
            operationDateColumn = operationDateColumn,
            labelColumn = label,
            detailColumns = details,
            amountColumn = amount,
            debitColumn = if (amount == null) debit else null,
            creditColumn = if (amount == null) credit else null,
            balanceColumn = balance,
            dateFormat = TabularValues.detectDateFormat(values(dateColumn))!!,
            decimalComma = decimalComma,
        )
    }

    private class Row(val number: Int, val transaction: ParsedTransaction, val balanceCents: Long?)

    fun read(rows: List<List<String>>, mapping: CsvMapping): TabularParse {
        val problems = mutableListOf<String>()
        if (mapping.amountColumn == null && (mapping.debitColumn == null || mapping.creditColumn == null)) {
            return TabularParse(emptyList(), null, null, listOf("Choisissez une colonne de montant, ou une colonne débit et une colonne crédit."))
        }
        val parsed = mutableListOf<Row>()
        rows.drop(mapping.firstDataRow).forEachIndexed { index, row ->
            val number = mapping.firstDataRow + index + 1
            fun cell(column: Int?) = column?.let { row.getOrElse(it) { "" } } ?: ""
            val dateText = cell(mapping.dateColumn)
            val amountTexts = listOf(cell(mapping.amountColumn), cell(mapping.debitColumn), cell(mapping.creditColumn))
            if (dateText.isBlank() && amountTexts.all(String::isBlank)) return@forEachIndexed
            val date = TabularValues.parseDate(dateText, mapping.dateFormat)
            if (date == null) {
                problems += "Ligne $number : date illisible au format ${mapping.dateFormat}."
                return@forEachIndexed
            }
            val cents = amountOf(cell(mapping.amountColumn), cell(mapping.debitColumn), cell(mapping.creditColumn), mapping)
            if (cents == null) {
                problems += "Ligne $number : montant illisible."
                return@forEachIndexed
            }
            val operationDate = mapping.operationDateColumn?.let { TabularValues.parseDate(cell(it), mapping.dateFormat) } ?: date
            val label = cell(mapping.labelColumn).replace(Regex("\\s+"), " ").trim()
            val details = mapping.detailColumns.map(::cell).filter(String::isNotBlank).joinToString(" · ")
            val balanceText = cell(mapping.balanceColumn)
            val balance = if (mapping.balanceColumn != null && balanceText.isNotBlank()) {
                TabularValues.parseCents(balanceText, mapping.decimalComma) ?: run {
                    problems += "Ligne $number : solde illisible."
                    return@forEachIndexed
                }
            } else {
                null
            }
            parsed += Row(
                number,
                ParsedTransaction(
                    bookingDate = date,
                    operationDate = operationDate,
                    amountCents = cents,
                    label = label.ifEmpty { details.ifEmpty { "(sans libellé)" } },
                    details = if (label.isEmpty()) "" else details,
                    kind = TabularValues.kindOf(label, cents),
                ),
                balance,
            )
        }
        if (parsed.isEmpty() && problems.isEmpty()) problems += "Aucune opération trouvée avec cette correspondance de colonnes."
        if (problems.isNotEmpty()) return TabularParse(emptyList(), null, null, problems)

        // Fichiers du plus récent au plus ancien (fréquent) : remis dans l'ordre chronologique (tri stable).
        val descending = parsed.first().transaction.bookingDate > parsed.last().transaction.bookingDate
        val ascending = (if (descending) parsed.reversed() else parsed).sortedBy { it.transaction.bookingDate }
        if (mapping.balanceColumn == null) {
            return TabularParse(ascending.map { it.transaction }, null, null, emptyList())
        }
        if (ascending.any { it.balanceCents == null }) {
            return TabularParse(emptyList(), null, null, listOf("La colonne de solde est vide sur certaines lignes : retirez-la de la correspondance."))
        }
        val chain = BalanceChain.check(ascending.map { BalanceChain.Line(it.transaction.bookingDate, it.transaction.amountCents, it.balanceCents!!) })
        if (chain.failedOn != null) {
            return TabularParse(
                emptyList(), null, null,
                listOf("Opérations du ${chain.failedOn.format(java.time.format.DateTimeFormatter.ofPattern("dd/MM/yyyy"))} : " +
                    "le solde de la journée ne correspond pas au solde précédent plus ses opérations."),
            )
        }
        return TabularParse(
            transactions = ascending.map { it.transaction },
            opening = Balance(ascending.first().transaction.bookingDate, chain.openingCents!!),
            closing = Balance(ascending.last().transaction.bookingDate, chain.closingCents!!),
            problems = emptyList(),
        )
    }

    private fun amountOf(amount: String, debit: String, credit: String, mapping: CsvMapping): Long? {
        if (mapping.amountColumn != null) return TabularValues.parseCents(amount, mapping.decimalComma)
        val d = if (debit.isBlank()) 0L else TabularValues.parseCents(debit, mapping.decimalComma) ?: return null
        val c = if (credit.isBlank()) 0L else TabularValues.parseCents(credit, mapping.decimalComma) ?: return null
        if (debit.isBlank() && credit.isBlank()) return null
        // Les débits sont écrits tantôt positifs, tantôt négatifs : c'est la colonne qui fixe le signe.
        return kotlin.math.abs(c) - kotlin.math.abs(d)
    }
}

/**
 * Contrôle d'une colonne de solde, jour par jour : l'ordre des opérations d'une même journée varie selon les
 * banques, mais le solde de fin de journée doit être le solde de la veille plus les opérations du jour, et
 * figurer sur l'une des lignes de la journée.
 */
object BalanceChain {
    data class Line(val date: java.time.LocalDate, val amountCents: Long, val balanceCents: Long)

    data class Result(val openingCents: Long?, val closingCents: Long?, val failedOn: java.time.LocalDate?)

    fun check(lines: List<Line>): Result {
        val days = lines.groupBy { it.date }.toSortedMap().values.toList()
        val first = days.first()
        // Fin de la première journée : un de ses soldes ; on essaie d'abord celui de la dernière ligne.
        val candidates = (listOf(first.last().balanceCents) + first.map { it.balanceCents }).distinct()
        var failedOn: java.time.LocalDate? = null
        for (firstEnd in candidates) {
            var end = firstEnd
            val broken = days.drop(1).firstOrNull { day ->
                end += day.sumOf { it.amountCents }
                day.none { it.balanceCents == end }
            }
            if (broken == null) return Result(firstEnd - first.sumOf { it.amountCents }, end, null)
            if (failedOn == null) failedOn = broken.first().date
        }
        return Result(null, null, failedOn)
    }
}
