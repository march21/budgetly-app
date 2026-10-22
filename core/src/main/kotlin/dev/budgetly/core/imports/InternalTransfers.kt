package dev.budgetly.core.imports

import dev.budgetly.core.statement.ParsedStatement
import kotlin.math.abs

/** Position d'une opération dans un relevé : (index du compte, index de l'opération). */
data class TxnRef(val account: Int, val index: Int)

data class TransferDetection(
    /** Opérations entre comptes suivis, exclues des dépenses et revenus. */
    val internal: Set<TxnRef>,
    /** Paires débit ↔ crédit trouvées dans le même relevé. */
    val pairs: List<Pair<TxnRef, TxnRef>>,
    /** Pour une opération interne sans contrepartie dans le relevé : IBAN du compte de contrepartie. */
    val counterpartIban: Map<TxnRef, String>,
)

/**
 * Un virement est interne quand son libellé ou ses détails citent l'IBAN d'un compte suivi.
 * Sa contrepartie (même montant opposé, à 3 jours près) dans l'autre compte l'est aussi, même si elle ne cite rien.
 */
object InternalTransfers {
    private const val MAX_DAYS_APART = 3L

    fun detect(statement: ParsedStatement, knownIbans: Set<String>): TransferDetection {
        val internal = mutableSetOf<TxnRef>()
        val pairs = mutableListOf<Pair<TxnRef, TxnRef>>()
        val counterpart = mutableMapOf<TxnRef, String>()
        val paired = mutableSetOf<TxnRef>()
        val accountIndexByIban = statement.accounts.withIndex().associate { (i, a) -> a.account.iban to i }

        statement.accounts.forEachIndexed { accountIndex, account ->
            account.transactions.forEachIndexed { index, transaction ->
                val text = (transaction.label + " " + transaction.details).uppercase().replace(" ", "")
                val target = knownIbans.firstOrNull { it != account.account.iban && it in text } ?: return@forEachIndexed
                val ref = TxnRef(accountIndex, index)
                internal += ref
                if (ref in paired) return@forEachIndexed
                val targetIndex = accountIndexByIban[target]
                if (targetIndex == null) {
                    counterpart[ref] = target
                    return@forEachIndexed
                }
                val match = statement.accounts[targetIndex].transactions.withIndex().firstOrNull { (i, other) ->
                    val otherRef = TxnRef(targetIndex, i)
                    otherRef !in paired &&
                        other.amountCents == -transaction.amountCents &&
                        abs(other.bookingDate.toEpochDay() - transaction.bookingDate.toEpochDay()) <= MAX_DAYS_APART
                }
                if (match == null) {
                    counterpart[ref] = target
                } else {
                    val otherRef = TxnRef(targetIndex, match.index)
                    internal += otherRef
                    paired += ref
                    paired += otherRef
                    pairs += ref to otherRef
                }
            }
        }
        return TransferDetection(internal, pairs, counterpart)
    }
}
