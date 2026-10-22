package dev.budgetly.core.pdf

import org.junit.jupiter.api.Test
import kotlin.math.abs
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class PdfTextExtractorTest {

    private val document = PdfTextExtractor.extract(StatementPdfFixture.build())

    @Test
    fun `lit les lignes de haut en bas avec les accents`() {
        assertEquals(1, document.pages)
        assertEquals(3, document.lines.size)
        assertEquals(listOf("Date", "Opération", "Débit", "Crédit"), document.lines[0].words.map { it.text })
        assertTrue(document.lines[0].y < document.lines[1].y)
    }

    @Test
    fun `place chaque montant dans sa colonne grace aux positions`() {
        val achat = document.lines[1]
        val salaire = document.lines[2]

        val debit = achat.words.last()
        assertEquals("12,50", debit.text)
        assertTrue(abs(debit.x - StatementPdfFixture.DEBIT_X) < 1f, "x=${debit.x}")

        val credit = salaire.words.first { it.x >= StatementPdfFixture.CREDIT_X - 1f }
        assertEquals("2", credit.text)
        assertTrue(salaire.text.endsWith("2 100,00"), salaire.text)
    }

    @Test
    fun `garde la date d'achat CB presente dans le libelle`() {
        assertTrue(document.lines[1].text.contains("09.09.26"))
    }
}
