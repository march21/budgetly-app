package dev.budgetly.core.statement

import org.apache.pdfbox.pdmodel.PDDocument
import org.apache.pdfbox.pdmodel.PDPage
import org.apache.pdfbox.pdmodel.PDPageContentStream
import org.apache.pdfbox.pdmodel.common.PDRectangle
import org.apache.pdfbox.pdmodel.font.PDType1Font
import org.apache.pdfbox.pdmodel.font.Standard14Fonts
import java.io.ByteArrayOutputStream

/** Petit outil pour écrire des relevés fictifs à des coordonnées mesurées (y depuis le haut de la page). */
class PdfFixtureWriter {
    private val document = PDDocument()
    private val regular = PDType1Font(Standard14Fonts.FontName.HELVETICA)
    private val bold = PDType1Font(Standard14Fonts.FontName.HELVETICA_BOLD)
    private var stream: PDPageContentStream? = null
    private val height = PDRectangle.A4.height

    fun page(): PdfFixtureWriter {
        stream?.close()
        val page = PDPage(PDRectangle.A4)
        document.addPage(page)
        stream = PDPageContentStream(document, page)
        return this
    }

    fun text(x: Float, y: Float, size: Float, value: String, isBold: Boolean = false) {
        val cs = stream!!
        cs.beginText()
        cs.setFont(if (isBold) bold else regular, size)
        cs.newLineAtOffset(x, height - y)
        cs.showText(value)
        cs.endText()
    }

    /** Texte aligné à droite sur `xEnd`. */
    fun right(xEnd: Float, y: Float, size: Float, value: String, isBold: Boolean = false) {
        val font = if (isBold) bold else regular
        text(xEnd - font.getStringWidth(value) / 1000f * size, y, size, value, isBold)
    }

    fun build(): ByteArray {
        stream?.close()
        return ByteArrayOutputStream().also { document.save(it); document.close() }.toByteArray()
    }

    companion object {
        /** 143068 → « 1 430,68 » */
        fun amount(cents: Long): String {
            val sign = if (cents < 0) "-" else ""
            val absolute = kotlin.math.abs(cents)
            val units = (absolute / 100).toString().reversed().chunked(3).joinToString(" ").reversed()
            return "$sign$units,${(absolute % 100).toString().padStart(2, '0')}"
        }
    }
}
