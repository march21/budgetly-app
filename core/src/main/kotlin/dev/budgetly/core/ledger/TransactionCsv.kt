package dev.budgetly.core.ledger

/** Opérations au format CSV « à la française », lisible directement par Excel et LibreOffice. */
object TransactionCsv {
    private val header = listOf(
        "Date d'opération", "Date de comptabilisation", "Compte", "Libellé", "Détails", "Montant", "Catégorie", "Entre mes comptes", "Note",
    )

    fun write(rows: List<TransactionView>): ByteArray {
        val out = StringBuilder("\uFEFF")
        line(out, header)
        for (t in rows.sortedWith(compareBy<TransactionView> { it.operationDate }.thenBy { it.id })) {
            line(
                out,
                listOf(
                    t.operationDate.toString(),
                    t.bookingDate.toString(),
                    t.accountName,
                    t.label,
                    t.details,
                    euros(t.amountCents),
                    t.categoryName ?: t.splits.joinToString(" / ") { "${it.categoryName} ${euros(it.amountCents)}" },
                    if (t.internalTransfer) "oui" else "non",
                    t.note ?: "",
                ),
            )
        }
        return out.toString().toByteArray(Charsets.UTF_8)
    }

    /** Centimes → « -1234,56 » : pas de séparateur de milliers, pour que le tableur reconnaisse un nombre. */
    fun euros(cents: Long): String {
        val sign = if (cents < 0) "-" else ""
        val abs = Math.abs(cents)
        return "$sign${abs / 100},${(abs % 100).toString().padStart(2, '0')}"
    }

    private fun line(out: StringBuilder, cells: List<String>) {
        cells.joinTo(out, ";") { cell(it) }
        out.append("\r\n")
    }

    private fun cell(value: String): String {
        // Une cellule commençant par = + @ serait interprétée comme formule par le tableur.
        val safe = if (value.isNotEmpty() && value[0] in "=+@\t\r") "'$value" else value
        return if (safe.any { it == ';' || it == '"' || it == '\n' || it == '\r' }) "\"" + safe.replace("\"", "\"\"") + "\"" else safe
    }
}
