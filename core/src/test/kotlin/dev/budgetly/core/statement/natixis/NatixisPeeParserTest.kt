package dev.budgetly.core.statement.natixis

import dev.budgetly.core.pdf.PdfTextExtractor
import dev.budgetly.core.statement.AccountType
import dev.budgetly.core.statement.easybourse.EasyBourseFixtures
import dev.budgetly.core.statement.easybourse.EasyBoursePeaParser
import dev.budgetly.core.statement.easybourse.EasyVieParser
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

class NatixisPeeParserTest {
    private val parser = NatixisPeeParser()
    private val doc = PdfTextExtractor.extract(NatixisFixtures.notice())

    @Test
    fun `ne reconnait que l'avis Natixis`() {
        assertTrue(parser.supports(doc))
        assertFalse(parser.supports(PdfTextExtractor.extract(EasyBourseFixtures.pea())))
        assertFalse(parser.supports(PdfTextExtractor.extract(LbpStatementFixture.build(LbpStatementFixture.standard()))))
        assertFalse(EasyBoursePeaParser().supports(doc))
        assertFalse(EasyVieParser().supports(doc))
        assertFalse(LbpAccountStatementParser().supports(doc))
    }

    @Test
    fun `situation des avoirs - placements, parts, total et disponibilite`() {
        val p = parser.parse(doc)
        assertEquals(AccountType.PEE, p.account.type)
        assertEquals("PEE-${NatixisFixtures.COMPANY}", p.account.iban)
        assertEquals(LocalDate.of(2026, 10, 7), p.valuationDate)
        assertEquals(NatixisFixtures.rows.size, p.positions.size)
        assertEquals(NatixisFixtures.total, p.printedTotalCents)
        assertTrue(p.balanced)
        assertNull(p.contributionsCents)

        val first = p.positions.first()
        assertEquals("FONDS EXEMPLE ACTIONS MONDE I", first.name)
        assertNull(first.isin)
        assertEquals(SecurityKind.FUND, first.kind)
        assertEquals(BigDecimal("35.12345"), first.unitPrice)
        assertEquals(BigDecimal("4.0000"), first.quantity)
        assertEquals(LocalDate.of(2026, 10, 6), first.priceDate)
        assertEquals("FONDS EXEMPLE MONETAIRE", p.positions.last().name)
        assertEquals(NatixisFixtures.rows.map { it.valueCents }, p.positions.map { it.valueCents })

        assertEquals(
            listOf(LocalDate.of(2025, 6, 1), LocalDate.of(2029, 6, 1), LocalDate.of(2030, 6, 1), LocalDate.of(2031, 6, 1)),
            p.availability.map { it.availableOn },
        )
        assertEquals(NatixisFixtures.total, p.availability.sumOf { it.valueCents })
    }

    @Test
    fun `un total qui ne boucle pas est detecte`() {
        assertFalse(parser.parse(PdfTextExtractor.extract(NatixisFixtures.notice(totalAdjustmentCents = 100))).balanced)
    }
}
