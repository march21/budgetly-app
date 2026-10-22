package dev.budgetly.core.ledger

import org.junit.jupiter.api.Test
import java.time.LocalDate
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class RecurringDetectorTest {
    private var id = 0L
    private fun op(date: String, cents: Long, label: String) = RecurringCandidate(++id, LocalDate.parse(date), cents, label, null, null)

    private val horizon = LocalDate.of(2026, 10, 9)

    private fun detect(vararg ops: RecurringCandidate) = RecurringDetector.detect(ops.toList(), horizon)

    @Test
    fun `abonnement mensuel a montant fixe, puis hausse de prix`() {
        val series = detect(
            op("2026-06-12", -1349, "PRELEVEMENT DE STREAMING EXEMPLE"),
            op("2026-07-12", -1349, "PRELEVEMENT DE STREAMING EXEMPLE"),
            op("2026-08-12", -1349, "PRELEVEMENT DE STREAMING EXEMPLE"),
            op("2026-09-12", -1599, "PRELEVEMENT DE STREAMING EXEMPLE"),
        ).single()
        assertEquals("STREAMING EXEMPLE", series.pattern)
        assertEquals(SeriesStatus.ACTIVE, series.status)
        assertEquals(4, series.occurrences)
        assertEquals(LocalDate.of(2026, 10, 12), series.nextDate)
        assertTrue(series.priceChanged)
        assertEquals(-1349, series.previousAmountCents)
        assertFalse(series.isNew)
    }

    @Test
    fun `montant variable, serie reconnue sans alerte de hausse`() {
        val series = detect(
            op("2026-07-05", -6200, "PRELEVEMENT DE FOURNISSEUR ENERGIE"),
            op("2026-08-05", -8900, "PRELEVEMENT DE FOURNISSEUR ENERGIE"),
            op("2026-09-04", -7400, "PRELEVEMENT DE FOURNISSEUR ENERGIE"),
        ).single()
        assertTrue(series.variable)
        assertFalse(series.priceChanged)
        assertEquals(-7500, series.typicalAmountCents)
    }

    @Test
    fun `achats frequents chez un commercant, pas une charge fixe`() {
        val bakery = (1..12).map { op("2026-09-%02d".format(it * 2), -250, "ACHAT CB BOULANGERIE DU COIN %02d.09.26".format(it * 2)) }
        assertTrue(RecurringDetector.detect(bakery, horizon).isEmpty())
    }

    @Test
    fun `deux occurrences, seulement a montant identique, et signalee comme nouvelle`() {
        // L'historique commence en juin : l'abonnement apparu en août est nouveau.
        val fresh = detect(
            op("2026-06-03", -4500, "ACHAT CB LIBRAIRIE 02.06.26"),
            op("2026-08-20", -999, "PRELEVEMENT DE APPLI EXEMPLE"),
            op("2026-09-20", -999, "PRELEVEMENT DE APPLI EXEMPLE"),
        ).single()
        assertTrue(fresh.isNew)
        assertTrue(detect(op("2026-08-20", -999, "ACHAT CB X"), op("2026-09-20", -1500, "ACHAT CB X")).isEmpty())
    }

    @Test
    fun `serie arretee, salaire et deux abonnements chez le meme fournisseur`() {
        val all = detect(
            op("2026-03-02", -1500, "PRELEVEMENT DE SALLE DE SPORT"),
            op("2026-04-02", -1500, "PRELEVEMENT DE SALLE DE SPORT"),
            op("2026-05-02", -1500, "PRELEVEMENT DE SALLE DE SPORT"),
            op("2026-07-28", 250_000, "VIREMENT DE SOCIETE EXEMPLE"),
            op("2026-08-28", 251_300, "VIREMENT DE SOCIETE EXEMPLE"),
            op("2026-09-28", 250_800, "VIREMENT DE SOCIETE EXEMPLE"),
            op("2026-07-10", -2999, "PRELEVEMENT DE OPERATEUR"),
            op("2026-07-15", -999, "PRELEVEMENT DE OPERATEUR"),
            op("2026-08-10", -2999, "PRELEVEMENT DE OPERATEUR"),
            op("2026-08-15", -999, "PRELEVEMENT DE OPERATEUR"),
            op("2026-09-10", -2999, "PRELEVEMENT DE OPERATEUR"),
            op("2026-09-15", -999, "PRELEVEMENT DE OPERATEUR"),
        )
        assertEquals(SeriesStatus.STOPPED, all.single { it.pattern == "SALLE DE SPORT" }.status)
        assertTrue(all.single { it.pattern == "VIREMENT DE SOCIETE EXEMPLE" }.credit)
        assertEquals(listOf(-2999L, -999L), all.filter { it.pattern == "OPERATEUR" }.map { it.lastAmountCents })
        assertEquals(all.map { it.key }.distinct().size, all.size, "clés distinctes")
    }
}
