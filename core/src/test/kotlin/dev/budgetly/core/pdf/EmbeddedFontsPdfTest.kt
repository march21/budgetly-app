package dev.budgetly.core.pdf

import org.apache.pdfbox.cos.COSArray
import org.apache.pdfbox.cos.COSName
import org.apache.pdfbox.pdmodel.PDDocument
import org.apache.pdfbox.pdmodel.PDPage
import org.apache.pdfbox.pdmodel.PDPageContentStream
import org.apache.pdfbox.pdmodel.common.PDRectangle
import org.apache.pdfbox.pdmodel.common.PDStream
import org.apache.pdfbox.pdmodel.font.PDType0Font
import org.apache.pdfbox.pdmodel.font.PDTrueTypeFont
import org.apache.pdfbox.pdmodel.font.encoding.WinAnsiEncoding
import org.apache.pdfbox.pdmodel.graphics.color.PDColor
import org.apache.pdfbox.pdmodel.graphics.color.PDICCBased
import org.junit.jupiter.api.Test
import java.awt.color.ColorSpace
import java.awt.color.ICC_Profile
import java.io.ByteArrayOutputStream
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Chemins de PDFBox que les relevés générés par Helvetica ne parcourent pas, mais qu'un vrai relevé peut
 * emprunter : police TrueType intégrée (en sous-ensemble Type0/CID et en TrueType simple) et espace couleur
 * ICC. Le test tourne aussi en natif : l'agent GraalVM trace ces chemins en JVM, l'image doit les connaître.
 */
class EmbeddedFontsPdfTest {

    private fun font() = requireNotNull(javaClass.classLoader.getResourceAsStream(FONT)) { "police absente : $FONT" }

    /** Profil ICC sRGB du JDK (aucune ressource externe) : PDFBox le relit via java.awt.color, comme pour un vrai relevé. */
    private fun iccProfile(): ByteArray = ICC_Profile.getInstance(ColorSpace.CS_sRGB).data

    private fun build(): ByteArray = PDDocument().use { document ->
        val page = PDPage(PDRectangle.A4)
        document.addPage(page)
        val cid = font().use { PDType0Font.load(document, it) }
        val trueType = font().use { PDTrueTypeFont.load(document, it, WinAnsiEncoding.INSTANCE) }

        val icc = PDStream(document, iccProfile().inputStream())
        icc.cosObject.setInt(COSName.N, 3)
        val array = COSArray().apply {
            add(COSName.ICCBASED)
            add(icc)
        }
        val colorSpace = PDICCBased.create(array, page.resources)

        PDPageContentStream(document, page).use { cs ->
            cs.setNonStrokingColor(PDColor(floatArrayOf(0.1f, 0.3f, 0.6f), colorSpace))
            cs.beginText()
            cs.setFont(cid, 11f)
            cs.newLineAtOffset(50f, 760f)
            cs.showText("Relevé fictif : 1 234,56 € — opération créditée")
            cs.endText()
            cs.beginText()
            cs.setFont(trueType, 11f)
            cs.newLineAtOffset(50f, 740f)
            cs.showText("Solde au 31/10 : 987,65 €")
            cs.endText()
        }
        ByteArrayOutputStream().also { document.save(it) }.toByteArray()
    }

    @Test
    fun `texte en police TrueType integree et couleur ICC`() {
        val text = PdfTextExtractor.extract(build())
        assertEquals(1, text.pages)
        val lines = text.lines.map { it.text }
        assertTrue(lines.any { it.contains("Relevé fictif : 1 234,56 €") }, lines.toString())
        assertTrue(lines.any { it.contains("opération créditée") }, lines.toString())
        assertTrue(lines.any { it.contains("Solde au 31/10 : 987,65 €") }, lines.toString())
    }

    private companion object {
        const val FONT = "org/apache/pdfbox/resources/ttf/LiberationSans-Regular.ttf"
    }
}
