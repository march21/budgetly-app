package dev.budgetly.core.pdf

import org.apache.pdfbox.pdmodel.PDDocument
import org.apache.pdfbox.pdmodel.PDPage
import org.apache.pdfbox.pdmodel.PDPageContentStream
import org.apache.pdfbox.pdmodel.common.PDRectangle
import org.apache.pdfbox.pdmodel.font.PDType1Font
import org.apache.pdfbox.pdmodel.font.Standard14Fonts
import java.io.ByteArrayOutputStream

/**
 * Relevé synthétique, sans aucune donnée réelle : colonnes Débit / Crédit,
 * accents, séparateur de milliers, aplats et texte en couleur comme sur un vrai relevé.
 */
object StatementPdfFixture {
    const val DEBIT_X = 400f
    const val CREDIT_X = 480f

    fun build(): ByteArray = PDDocument().use { document ->
        val page = PDPage(PDRectangle.A4)
        document.addPage(page)
        val font = PDType1Font(Standard14Fonts.FontName.HELVETICA)
        val bold = PDType1Font(Standard14Fonts.FontName.HELVETICA_BOLD)
        val top = PDRectangle.A4.height

        PDPageContentStream(document, page).use { cs ->
            fun text(x: Float, yFromTop: Float, value: String, f: PDType1Font = font) {
                cs.beginText()
                cs.setFont(f, 10f)
                cs.newLineAtOffset(x, top - yFromTop)
                cs.showText(value)
                cs.endText()
            }

            cs.setNonStrokingColor(0.9f, 0.92f, 0.95f)
            cs.addRect(40f, top - 98f, 520f, 16f)
            cs.fill()

            cs.setNonStrokingColor(0.1f, 0.2f, 0.5f)
            text(50f, 92f, "Date", bold)
            text(100f, 92f, "Opération", bold)
            text(DEBIT_X, 92f, "Débit", bold)
            text(CREDIT_X, 92f, "Crédit", bold)

            cs.setNonStrokingColor(0f, 0f, 0f)
            text(50f, 112f, "10/09")
            text(100f, 112f, "ACHAT CB BOULANGERIE 09.09.26")
            text(DEBIT_X, 112f, "12,50")

            text(50f, 132f, "11/09")
            text(100f, 132f, "VIREMENT SALAIRE")
            text(CREDIT_X, 132f, "2 100,00")
        }

        ByteArrayOutputStream().also { document.save(it) }.toByteArray()
    }
}
