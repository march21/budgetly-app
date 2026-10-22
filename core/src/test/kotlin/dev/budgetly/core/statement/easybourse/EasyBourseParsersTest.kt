package dev.budgetly.core.statement.easybourse

import dev.budgetly.core.pdf.PdfTextExtractor
import dev.budgetly.core.statement.AccountType
import dev.budgetly.core.statement.lbp.LbpAccountStatementParser
import dev.budgetly.core.statement.lbp.LbpStatementFixture
import dev.budgetly.core.statement.portfolio.SecurityKind
import org.junit.jupiter.api.Test
import java.math.BigDecimal
import java.time.LocalDate
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class EasyBourseParsersTest {

    private val pea = EasyBoursePeaParser()
    private val life = EasyVieParser()
    private val peaDoc = PdfTextExtractor.extract(EasyBourseFixtures.pea())
    private val lifeDoc = PdfTextExtractor.extract(EasyBourseFixtures.lifeInsurance())

    @Test
    fun `chaque parser ne reconnait que son document`() {
        val lbpDoc = PdfTextExtractor.extract(LbpStatementFixture.build(LbpStatementFixture.standard()))
        assertTrue(pea.supports(peaDoc))
        assertFalse(pea.supports(lifeDoc))
        assertFalse(pea.supports(lbpDoc))
        assertTrue(life.supports(lifeDoc))
        assertFalse(life.supports(peaDoc))
        assertFalse(life.supports(lbpDoc))
        assertFalse(LbpAccountStatementParser().supports(peaDoc))
        assertFalse(LbpAccountStatementParser().supports(lifeDoc))
    }

    @Test
    fun `PEA - lignes, liquidites, total et plus-values boucles`() {
        val portfolio = pea.parse(peaDoc)
        assertEquals(AccountType.PEA, portfolio.account.type)
        assertEquals(EasyBourseFixtures.PEA_NUMBER, portfolio.account.iban)
        assertEquals(LocalDate.of(2026, 9, 30), portfolio.valuationDate)
        assertEquals(3, portfolio.positions.size)
        assertEquals(EasyBourseFixtures.PEA_CASH, portfolio.cashCents)
        assertEquals(EasyBourseFixtures.peaTotal, portfolio.printedTotalCents)
        assertEquals(EasyBourseFixtures.PEA_CONTRIBUTIONS, portfolio.contributionsCents)
        assertTrue(portfolio.balanced)

        val fund = portfolio.positions.single { it.isin == "FR0000000029" }
        assertEquals("FONDS EXEMPLE ACTIONS EURO", fund.name)
        assertEquals(BigDecimal("5.79845"), fund.quantity)
        assertEquals(BigDecimal("68.28"), fund.unitPrice)
        assertEquals(LocalDate.of(2026, 9, 30), fund.priceDate)
        assertEquals(SecurityKind.FUND, fund.kind)
        assertEquals(SecurityKind.STOCK, portfolio.positions.single { it.isin == "FR0000000011" }.kind)
        assertEquals(SecurityKind.ETF, portfolio.positions.single { it.isin == "LU0000000035" }.kind)
    }

    @Test
    fun `PEA - un total qui ne boucle pas est detecte`() {
        assertFalse(pea.parse(PdfTextExtractor.extract(EasyBourseFixtures.pea(totalAdjustmentCents = 100))).balanced)
    }

    @Test
    fun `assurance vie - fonds euros, unites de compte sur deux pages, annexes ignorees`() {
        val portfolio = life.parse(lifeDoc)
        assertEquals(AccountType.LIFE_INSURANCE, portfolio.account.type)
        assertEquals("B0E00000001", portfolio.account.iban)
        assertEquals(LocalDate.of(2025, 12, 31), portfolio.valuationDate)
        assertEquals(1 + EasyBourseFixtures.avUnits.size, portfolio.positions.size)
        assertTrue(portfolio.balanced, "total ${portfolio.computedTotalCents} / ${portfolio.printedTotalCents}")

        val euro = portfolio.positions.single { it.kind == SecurityKind.EURO_FUND }
        assertNull(euro.isin)
        assertEquals("EASYVIE Euros", euro.name)
        assertEquals(EasyBourseFixtures.AV_EURO_CENTS, euro.valueCents)

        val money = portfolio.positions.single { it.isin == "FR0000000060" }
        assertEquals(BigDecimal("44710.55"), money.unitPrice)
        assertEquals(BigDecimal("0.00107"), money.quantity)
        assertEquals(SecurityKind.ETF, portfolio.positions.single { it.isin == "IE0000000041" }.kind)
    }

    @Test
    fun `assurance vie - cumul des versements reconstitue`() {
        // 0 € avant 2017 + 1 345 € après, au 31/12/2024, + 600 € versés en 2025.
        assertEquals(194_500, life.parse(lifeDoc).contributionsCents)
    }
}
