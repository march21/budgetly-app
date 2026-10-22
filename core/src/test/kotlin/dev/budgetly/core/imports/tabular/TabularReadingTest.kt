package dev.budgetly.core.imports.tabular

import dev.budgetly.core.statement.TransactionKind
import org.junit.jupiter.api.Test
import java.time.LocalDate
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class TabularReadingTest {

    @Test
    fun `encodage, separateur et guillemets`() {
        val decoded = TabularText.decode(TabularFixtures.debitCredit)
        assertEquals("Windows-1252", decoded.encoding)
        assertTrue(decoded.text.contains("Libellé"))
        assertEquals(';', TabularText.detectDelimiter(decoded.text))
        assertEquals("UTF-8", TabularText.decode(byteArrayOf(0xEF.toByte(), 0xBB.toByte(), 0xBF.toByte()) + "a;b".toByteArray()).encoding)

        val rows = TabularText.rows(decoded.text, ';')
        assertEquals(7, rows.size)
        assertEquals("CB EPICERIE \"CHEZ EXEMPLE\"", rows.last()[1])
        assertEquals(listOf("a", "b;c", ""), TabularText.rows("a;\"b;c\";\n\n", ';').single())
        assertEquals(',', TabularText.detectDelimiter(String(TabularFixtures.signedWithBalance)))
    }

    @Test
    fun `montants et dates des exports`() {
        assertEquals(-123_456L, TabularValues.parseCents("-1 234,56", decimalComma = true))
        assertEquals(123_456L, TabularValues.parseCents("1.234,56 €", decimalComma = true))
        assertEquals(1_230L, TabularValues.parseCents("+12.30", decimalComma = false))
        assertEquals(-4_500L, TabularValues.parseCents("(45,00)", decimalComma = true))
        assertEquals(-700L, TabularValues.parseCents("7,00-", decimalComma = true))
        assertEquals(1_200L, TabularValues.parseCents("12", decimalComma = true))
        assertNull(TabularValues.parseCents("12,345", decimalComma = true))
        assertNull(TabularValues.parseCents("abc", decimalComma = true))

        assertEquals("dd/MM/yyyy", TabularValues.detectDateFormat(listOf("28/10/2026", "", "01/10/2026")))
        assertEquals("yyyy-MM-dd", TabularValues.detectDateFormat(listOf("2026-09-02")))
        assertEquals(LocalDate.of(2026, 2, 3), TabularValues.parseDate("03/02/26", "dd/MM/yy"))
        assertNull(TabularValues.parseDate("31/02/2026", "dd/MM/yyyy"))
        assertTrue(TabularValues.detectDecimalComma(listOf("700,00", "1 500,00")))
        assertEquals(TransactionKind.DIRECT_DEBIT, TabularValues.kindOf("PRLV LOYER EXEMPLE", -70_000))
    }

    @Test
    fun `CSV debit credit, correspondance proposee puis lecture chronologique`() {
        val rows = TabularText.rows(TabularText.decode(TabularFixtures.debitCredit).text, ';')
        val mapping = assertNotNull(CsvStatement.suggest(rows))
        assertEquals(2, mapping.headerRow)
        assertEquals(3, mapping.firstDataRow)
        assertEquals(0, mapping.dateColumn)
        assertEquals(1, mapping.labelColumn)
        assertNull(mapping.amountColumn)
        assertEquals(2, mapping.debitColumn)
        assertEquals(3, mapping.creditColumn)
        assertNull(mapping.balanceColumn)
        assertEquals("dd/MM/yyyy", mapping.dateFormat)
        assertTrue(mapping.decimalComma)

        val read = CsvStatement.read(rows, mapping)
        assertEquals(emptyList(), read.problems)
        assertNull(read.opening)
        assertEquals(listOf(-5_430L, -1_200L, 150_000L, -70_000L), read.transactions.map { it.amountCents })
        assertEquals(LocalDate.of(2026, 10, 1), read.transactions.first().bookingDate)
        assertEquals(TabularFixtures.debitCreditClosing - TabularFixtures.debitCreditOpening, read.transactions.sumOf { it.amountCents })
        assertEquals(TransactionKind.TRANSFER, read.transactions[2].kind)
    }

    @Test
    fun `CSV avec solde, controle jour par jour`() {
        val rows = TabularText.rows(String(TabularFixtures.signedWithBalance), ',')
        val mapping = assertNotNull(CsvStatement.suggest(rows))
        assertEquals(3, mapping.amountColumn)
        assertEquals(4, mapping.balanceColumn)
        assertEquals(2, mapping.labelColumn)
        assertEquals(false, mapping.decimalComma)

        val read = CsvStatement.read(rows, mapping)
        assertEquals(emptyList(), read.problems)
        assertEquals(TabularFixtures.signedOpening, read.opening?.cents)
        assertEquals(TabularFixtures.signedClosing, read.closing?.cents)
        assertEquals(LocalDate.of(2026, 9, 9), read.closing?.date)

        val wrong = CsvStatement.read(TabularText.rows(String(TabularFixtures.signedWithWrongBalance), ','), mapping)
        assertEquals(listOf("Opérations du 07/09/2026 : le solde de la journée ne correspond pas au solde précédent plus ses opérations."), wrong.problems)
        assertTrue(wrong.transactions.isEmpty())
    }

    @Test
    fun `CSV, lignes illisibles signalees sans leur contenu`() {
        val rows = TabularText.rows("Date;Libelle;Montant\n32/13/2026;X;1,00\n01/01/2026;Y;abc\n", ';')
        val mapping = CsvMapping(headerRow = 0, firstDataRow = 1, dateColumn = 0, labelColumn = 1, amountColumn = 2, dateFormat = "dd/MM/yyyy", decimalComma = true)
        assertEquals(listOf("Ligne 2 : date illisible au format dd/MM/yyyy.", "Ligne 3 : montant illisible."), CsvStatement.read(rows, mapping).problems)
    }

    @Test
    fun `OFX SGML et XML`() {
        val sgml = OfxStatement.read(TabularText.decode(TabularFixtures.ofxSgml).text)
        assertEquals(TabularFormat.OFX, TabularText.formatOf(TabularText.decode(TabularFixtures.ofxSgml).text))
        assertEquals(emptyList(), sgml.problems)
        assertEquals(TabularFixtures.ofxAccountId, sgml.accountId)
        assertEquals(listOf(-4_290L, 30_000L), sgml.transactions.map { it.amountCents })
        assertEquals("CB SUPERMARCHE EXEMPLE", sgml.transactions.first().label)
        assertEquals("CARTE 0000 02/08", sgml.transactions.first().details)
        assertEquals(TabularFixtures.ofxClosing, sgml.closing?.cents)
        assertEquals(LocalDate.of(2026, 8, 31), sgml.closing?.date)

        val xml = OfxStatement.read(String(TabularFixtures.ofxXml))
        assertEquals("99999LIVRET42", xml.accountId)
        assertEquals("INTERETS 2026 & PRIMES", xml.transactions.single().label)
        assertEquals(LocalDate.of(2026, 12, 31), xml.transactions.single().bookingDate)
        assertEquals(101_234L, xml.closing?.cents)
        assertEquals(TabularFormat.CSV, TabularText.formatOf(String(TabularFixtures.signedWithBalance)))
    }
}
