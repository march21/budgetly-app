package dev.budgetly.core.portfolio

import java.time.LocalDate
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class PerformanceTest {
    private val start = LocalDate.of(2025, 1, 1)

    @Test
    fun `TRI d'un placement sans versement, un an`() {
        val rate = assertNotNull(Performance.irr(listOf(start to -10_000L, start.plusDays(365) to 11_000L)))
        assertEquals(0.10, rate, 1e-6)

        val p = assertNotNull(Performance.of(listOf(Performance.Statement(start, 1_000_000, 900_000)), start.plusDays(365), 1_100_000))
        assertEquals(0.10, assertNotNull(p.annualizedRate), 1e-6)
        assertEquals(0.10, p.periodRate, 1e-6)
        assertEquals(100_000L, p.gainCents)
        assertEquals(0L, p.netContributionsCents)
    }

    @Test
    fun `versements deduits, places au milieu de la periode`() {
        // 10 000 € au départ, 1 000 € versés en cours d'année, 11 500 € à la fin : 500 € de gain, pas 1 500.
        val statements = listOf(
            Performance.Statement(start, 1_000_000, 1_000_000),
            Performance.Statement(start.plusDays(365), 1_150_000, 1_100_000),
        )
        val p = assertNotNull(Performance.of(statements, start.plusDays(365), 1_150_000))
        assertEquals(100_000L, p.netContributionsCents)
        assertEquals(50_000L, p.gainCents)
        val rate = assertNotNull(p.annualizedRate)
        // Entre 500 / 11 000 (versement au début) et 500 / 10 000 (versement à la fin).
        assertTrue(rate > 500.0 / 11_000 && rate < 500.0 / 10_000, "taux $rate")
    }

    @Test
    fun `retrait et periode courte`() {
        val statements = listOf(
            Performance.Statement(start, 1_000_000, 1_000_000),
            Performance.Statement(start.plusDays(120), 820_000, 800_000),
        )
        val p = assertNotNull(Performance.of(statements, start.plusDays(180), 830_000))
        assertEquals(-200_000L, p.netContributionsCents)
        assertEquals(30_000L, p.gainCents)
        assertNull(p.annualizedRate, "moins d'un an : pas de taux annualisé")
        assertTrue(p.periodRate > 0.03 && p.periodRate < 0.04, "taux ${p.periodRate}")
    }

    @Test
    fun `incalculable sans cumul des versements ou sur moins de 30 jours`() {
        assertNull(Performance.of(listOf(Performance.Statement(start, 1_000_000, null)), start.plusDays(400), 1_100_000))
        assertNull(Performance.of(listOf(Performance.Statement(start, 1_000_000, 0)), start.plusDays(10), 1_010_000))
        assertNull(Performance.irr(listOf(start to 10_000L, start.plusDays(30) to 11_000L)))
    }
}
