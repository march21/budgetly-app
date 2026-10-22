package dev.budgetly.core.statement.lbp

import dev.budgetly.core.pdf.PdfDocumentText
import dev.budgetly.core.pdf.PdfLine
import dev.budgetly.core.pdf.PdfTextExtractor
import dev.budgetly.core.pdf.PdfWord
import dev.budgetly.core.pdf.StatementPdfFixture
import dev.budgetly.core.statement.AccountType
import dev.budgetly.core.statement.StatementParseException
import dev.budgetly.core.statement.StatementValidator
import dev.budgetly.core.statement.TransactionKind
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import java.time.LocalDate
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class LbpAccountStatementParserTest {

    private val parser = LbpAccountStatementParser()

    private fun parse(spec: LbpStatementFixture.Spec) =
        parser.parse(PdfTextExtractor.extract(LbpStatementFixture.build(spec)))

    @Test
    fun `reconnait un releve Banque Postale et rien d'autre`() {
        assertTrue(parser.supports(PdfTextExtractor.extract(LbpStatementFixture.build(LbpStatementFixture.standard()))))
        assertFalse(parser.supports(PdfTextExtractor.extract(StatementPdfFixture.build())))
    }

    @Test
    fun `lit les trois comptes du releve avec leur IBAN`() {
        val statement = parse(LbpStatementFixture.standard())

        assertEquals(listOf(AccountType.CHECKING, AccountType.LDDS, AccountType.LIVRET_A), statement.accounts.map { it.account.type })
        assertEquals(
            listOf(LbpStatementFixture.CHECKING_IBAN, LbpStatementFixture.LDDS_IBAN, LbpStatementFixture.LIVRET_A_IBAN),
            statement.accounts.map { it.account.iban },
        )
        assertEquals(LocalDate.of(2026, 9, 9), statement.periodStart)
        assertEquals(LocalDate.of(2026, 10, 9), statement.periodEnd)
    }

    @Test
    fun `chaque compte boucle sur son solde et ses totaux imprimes`() {
        val statement = parse(LbpStatementFixture.standard())
        for (account in statement.accounts) {
            val check = StatementValidator.check(account)
            assertTrue(check.ok, "${account.account.type} : $check")
        }
        val checking = statement.accounts.first()
        assertEquals(125_000, checking.opening.cents)
        assertEquals(LbpStatementFixture.standard().checking.closingCents, checking.closing.cents)
        assertEquals(8, checking.transactions.size)
        assertEquals(0, statement.accounts[1].transactions.size)
    }

    @Test
    fun `montants avec separateur de milliers, au debit comme au credit`() {
        val ops = parse(LbpStatementFixture.standard()).accounts.first().transactions
        assertEquals(418_702, ops.single { it.label == "VIREMENT DE SOCIETE EXEMPLE" }.amountCents)
        assertEquals(-105_748, ops.single { it.label.startsWith("ACHAT CB GRAND MAGASIN") }.amountCents)
        assertEquals(30_000, ops.first().amountCents)
    }

    @Test
    fun `date d'achat CB, type d'operation et lignes de detail`() {
        val ops = parse(LbpStatementFixture.standard()).accounts.first().transactions

        val purchase = ops.single { it.label.startsWith("ACHAT CB GRAND MAGASIN") }
        assertEquals(LocalDate.of(2026, 9, 30), purchase.bookingDate)
        assertEquals(LocalDate.of(2026, 9, 29), purchase.operationDate)
        assertEquals(TransactionKind.CARD, purchase.kind)

        val debit = ops.single { it.kind == TransactionKind.DIRECT_DEBIT }
        assertEquals(2, debit.details.lines().size)
        assertTrue(debit.details.lines()[1].startsWith("IDENT"))

        val standing = ops.single { it.label == "VIREMENT PERMANENT POUR" }
        assertTrue(standing.details.contains(LbpStatementFixture.LIVRET_A_IBAN))

        assertEquals(TransactionKind.FEE, ops.single { it.label.startsWith("COTISATION") }.kind)
        assertEquals(TransactionKind.DEPOSIT, ops.single { it.label.startsWith("VERSEMENT") }.kind)
    }

    @Test
    fun `deduit l'annee au passage decembre - janvier`() {
        val ops = parse(LbpStatementFixture.yearBoundary()).accounts.single().transactions
        assertEquals(
            listOf(LocalDate.of(2026, 12, 15), LocalDate.of(2026, 12, 31), LocalDate.of(2027, 1, 5)),
            ops.map { it.bookingDate },
        )
        assertEquals(LocalDate.of(2026, 12, 14), ops.first().operationDate)
    }

    @Test
    fun `un solde final faux est detecte par la validation`() {
        val statement = parse(LbpStatementFixture.standard(closingAdjustmentCents = 100))
        assertFalse(StatementValidator.check(statement.accounts.first()).ok)
    }

    @Test
    fun `une erreur de lecture ne revele ni montant ni libelle`() {
        fun line(page: Int, y: Float, vararg words: Pair<String, Float>) =
            PdfLine(page, y, words.map { (text, x) -> PdfWord(text, x, x + text.length * 4f) })

        val document = PdfDocumentText(
            pages = 1,
            lines = listOf(
                line(1, 90f, "www.labanquepostale.fr" to 98f),
                line(1, 100f, "Compte" to 54f, "Courant" to 100f),
                line(1, 110f, "IBAN" to 54f, ":" to 72f, LbpStatementFixture.CHECKING_IBAN to 76f),
                line(1, 120f, "Ancien" to 272f, "solde" to 303f, "au" to 328f, "09/09/2026" to 341f, "123,45" to 512f),
                line(1, 130f, "10/09" to 54f, "ACHAT" to 86f, "SECRET" to 120f),
            ),
        )
        val error = assertThrows<StatementParseException> { parser.parse(document) }
        assertFalse(error.message!!.contains("123,45"))
        assertFalse(error.message!!.contains("SECRET"))
    }
}
