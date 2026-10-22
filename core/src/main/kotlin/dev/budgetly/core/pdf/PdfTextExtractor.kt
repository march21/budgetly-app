package dev.budgetly.core.pdf

import org.apache.pdfbox.Loader
import org.apache.pdfbox.text.PDFTextStripper
import org.apache.pdfbox.text.TextPosition
import kotlin.math.abs
import kotlin.math.max

/**
 * Extraction positionnelle : chaque mot garde son abscisse, ce qui permet aux parsers
 * de savoir dans quelle colonne (débit, crédit…) tombe un montant.
 *
 * Note : un séparateur de milliers « 1 057,48 » produit deux mots ("1", "057,48") ;
 * le regroupement des montants est la responsabilité du parser.
 */
object PdfTextExtractor {

    fun extract(pdf: ByteArray): PdfDocumentText =
        Loader.loadPDF(pdf).use { document ->
            val collector = GlyphCollector()
            collector.sortByPosition = true
            collector.getText(document)
            PdfDocumentText(document.numberOfPages, collector.lines())
        }
}

private data class Glyph(
    val page: Int,
    val x: Float,
    val xEnd: Float,
    val y: Float,
    val text: String,
    val spaceWidth: Float,
)

private class GlyphCollector : PDFTextStripper() {
    private val glyphs = mutableListOf<Glyph>()

    override fun writeString(text: String, textPositions: MutableList<TextPosition>) {
        for (tp in textPositions) {
            glyphs += Glyph(
                page = currentPageNo,
                x = tp.xDirAdj,
                xEnd = tp.xDirAdj + tp.widthDirAdj,
                y = tp.yDirAdj,
                text = tp.unicode ?: "",
                spaceWidth = tp.widthOfSpace,
            )
        }
    }

    fun lines(): List<PdfLine> =
        glyphs.filter { it.text.isNotBlank() }
            .groupBy { it.page }
            .toSortedMap()
            .flatMap { (page, pageGlyphs) ->
                val rows = mutableListOf<MutableList<Glyph>>()
                for (glyph in pageGlyphs.sortedBy { it.y }) {
                    val row = rows.lastOrNull()
                    if (row != null && abs(row.first().y - glyph.y) <= LINE_TOLERANCE) row += glyph
                    else rows += mutableListOf(glyph)
                }
                rows.map { row -> PdfLine(page, row.first().y, words(row.sortedBy { it.x })) }
            }

    private fun words(row: List<Glyph>): List<PdfWord> {
        val words = mutableListOf<PdfWord>()
        val current = StringBuilder()
        var start = 0f
        var end = 0f
        for (glyph in row) {
            val space = if (glyph.spaceWidth.isFinite() && glyph.spaceWidth > 0f) glyph.spaceWidth else DEFAULT_SPACE
            if (current.isNotEmpty() && glyph.x - end > max(space * 0.5f, MIN_GAP)) {
                words += PdfWord(current.toString(), start, end)
                current.clear()
            }
            if (current.isEmpty()) start = glyph.x
            current.append(glyph.text)
            end = glyph.xEnd
        }
        if (current.isNotEmpty()) words += PdfWord(current.toString(), start, end)
        return words
    }

    companion object {
        const val LINE_TOLERANCE = 2.0f
        const val DEFAULT_SPACE = 2.5f
        const val MIN_GAP = 0.8f
    }
}
