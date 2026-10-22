package dev.budgetly.core.statement

/** Résultat du contrôle d'un compte. Les montants servent à l'affichage dans l'aperçu, jamais aux logs. */
data class BalanceCheck(
    val expectedClosingCents: Long,
    val printedClosingCents: Long,
    val sumDebitCents: Long,
    val sumCreditCents: Long,
    val printedTotalsMatch: Boolean,
) {
    val ok: Boolean get() = expectedClosingCents == printedClosingCents && printedTotalsMatch
}

object StatementValidator {

    /** Solde initial + mouvements = solde final, et sommes = totaux imprimés quand ils existent. */
    fun check(account: ParsedAccountStatement): BalanceCheck {
        val debit = account.transactions.filter { it.amountCents < 0 }.sumOf { -it.amountCents }
        val credit = account.transactions.filter { it.amountCents > 0 }.sumOf { it.amountCents }
        val totalsMatch =
            (account.printedTotalDebitCents == null || account.printedTotalDebitCents == debit) &&
                (account.printedTotalCreditCents == null || account.printedTotalCreditCents == credit)
        return BalanceCheck(
            expectedClosingCents = account.opening.cents + credit - debit,
            printedClosingCents = account.closing.cents,
            sumDebitCents = debit,
            sumCreditCents = credit,
            printedTotalsMatch = totalsMatch,
        )
    }
}
