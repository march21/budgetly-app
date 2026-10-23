package dev.budgetly.core.loans

import org.junit.jupiter.api.Test
import java.math.BigDecimal
import java.time.LocalDate
import kotlin.test.assertEquals

class AmortizationTest {
    private val first = LocalDate.of(2025, 1, 5)

    @Test
    fun `200 000 euros a 3 % sur 20 ans`() {
        assertEquals(110_920L, Amortization.payment(20_000_000, BigDecimal("3"), 240))
        val schedule = Amortization.schedule(20_000_000, BigDecimal("3"), 240, first)
        assertEquals(240, schedule.size)
        assertEquals(50_000L, schedule.first().interestCents, "500 € d'intérêts le premier mois")
        assertEquals(60_920L, schedule.first().principalCents)
        assertEquals(0L, schedule.last().remainingCents)
        assertEquals(20_000_000L, schedule.sumOf { it.principalCents })
        assertEquals(6_620_643L, schedule.sumOf { it.interestCents })
        assertEquals(110_763L, schedule.last().paymentCents, "la dernière échéance solde l'écart d'arrondi")
        assertEquals(LocalDate.of(2044, 12, 5), schedule.last().date)
    }

    @Test
    fun `taux nul et petit pret`() {
        val schedule = Amortization.schedule(1_200_000, BigDecimal.ZERO, 12, first)
        assertEquals(List(12) { 100_000L }, schedule.map { it.paymentCents })
        assertEquals(0L, schedule.sumOf { it.interestCents })
        val odd = Amortization.schedule(1_000, BigDecimal.ZERO, 3, first)
        assertEquals(1_000L, odd.sumOf { it.principalCents })
        assertEquals(0L, odd.last().remainingCents)
    }
}
