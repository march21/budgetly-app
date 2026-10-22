package dev.budgetly.core.ledger

import dev.budgetly.core.statement.TransactionKind
import org.junit.jupiter.api.Test
import java.time.LocalDate
import kotlin.test.assertEquals

class TransactionCsvTest {
    private fun txn(label: String, cents: Long) = TransactionView(
        id = 1, accountId = 1, accountName = "Compte", bookingDate = LocalDate.of(2026, 10, 2),
        operationDate = LocalDate.of(2026, 10, 1), amountCents = cents, label = label, details = "",
        kind = TransactionKind.values().first(), internalTransfer = false, categoryId = null, categoryName = null,
        categoryKind = null, categorySource = null, suggestedPattern = "",
    )

    @Test
    fun `montants, guillemets et formules`() {
        assertEquals("-1234,56", TransactionCsv.euros(-123_456))
        assertEquals("0,05", TransactionCsv.euros(5))
        val line = String(TransactionCsv.write(listOf(txn("=CMD; \"X\"", -100))), Charsets.UTF_8).split("\r\n")[1]
        assertEquals("2026-10-01;2026-10-02;Compte;\"'=CMD; \"\"X\"\"\";;-1,00;;non;", line)
    }
}
