package dev.budgetly.core.statement

import java.time.LocalDate

enum class AccountType { CHECKING, LIVRET_A, LDDS, SAVINGS, PEA, LIFE_INSURANCE, PEE }

enum class TransactionKind { CARD, TRANSFER, DIRECT_DEBIT, DEPOSIT, WITHDRAWAL, FEE, OTHER }

/** Compte tel qu'identifié dans un relevé. L'IBAN est normalisé (sans espaces, majuscules). */
data class AccountRef(val type: AccountType, val name: String, val iban: String)

/** Solde à une date. Montants en centimes, signés (négatif = débiteur). */
data class Balance(val date: LocalDate, val cents: Long)

data class ParsedTransaction(
    /** Date de comptabilisation (colonne Date du relevé). */
    val bookingDate: LocalDate,
    /** Date réelle de l'opération : date d'achat CB si présente, sinon date de comptabilisation. */
    val operationDate: LocalDate,
    /** Centimes signés : négatif = débit, positif = crédit. */
    val amountCents: Long,
    val label: String,
    /** Lignes de détail sous l'opération (références, mandats, IBAN de contrepartie…). */
    val details: String,
    val kind: TransactionKind,
)

data class ParsedAccountStatement(
    val account: AccountRef,
    val opening: Balance,
    val closing: Balance,
    /** Totaux imprimés sur le relevé (« Total des opérations »), en centimes positifs, si présents. */
    val printedTotalDebitCents: Long?,
    val printedTotalCreditCents: Long?,
    val transactions: List<ParsedTransaction>,
)

data class ParsedStatement(
    val parserId: String,
    val institution: String,
    val accounts: List<ParsedAccountStatement>,
) {
    val periodStart: LocalDate? get() = accounts.minOfOrNull { it.opening.date }
    val periodEnd: LocalDate? get() = accounts.maxOfOrNull { it.closing.date }
}

/** Erreur de lecture d'un relevé. Le message ne contient jamais de montant ni de libellé. */
class StatementParseException(message: String) : RuntimeException(message)
