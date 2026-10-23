package dev.budgetly.core.ledger

import org.apache.pdfbox.pdmodel.PDDocument
import org.apache.pdfbox.pdmodel.PDPage
import org.apache.pdfbox.pdmodel.PDPageContentStream
import org.apache.pdfbox.pdmodel.common.PDRectangle
import org.apache.pdfbox.pdmodel.font.PDType1Font
import org.apache.pdfbox.pdmodel.font.Standard14Fonts
import java.io.ByteArrayOutputStream
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.TextStyle
import java.util.Calendar
import java.util.Locale

/**
 * Bilan annuel en PDF, généré sur la machine (PDFBox, polices standard). Rien n'est écrit sur disque : le fichier
 * part directement dans la réponse.
 */
object AnnualReportPdf {
    private val regular = PDType1Font(Standard14Fonts.FontName.HELVETICA)
    private val bold = PDType1Font(Standard14Fonts.FontName.HELVETICA_BOLD)
    private const val MARGIN = 50f
    private val french = Locale.FRANCE

    /** « 1 234,56 € » avec une espace insécable (présente dans l'encodage des polices standard, contrairement à l'espace fine). */
    fun euros(cents: Long, signed: Boolean = false): String {
        val abs = kotlin.math.abs(cents)
        val units = (abs / 100).toString().reversed().chunked(3).joinToString(" ").reversed()
        val sign = if (cents < 0) "-" else if (signed && cents > 0) "+" else ""
        return "$sign$units,${(abs % 100).toString().padStart(2, '0')} €"
    }

    private fun percent(ratio: Double?) = ratio?.let { String.format(french, "%.0f %%", it * 100) } ?: "—"

    private fun month(review: AnnualReview) = review.through.month.getDisplayName(TextStyle.FULL, french)

    fun write(review: AnnualReview, generatedAt: Instant, zone: ZoneId): ByteArray = PDDocument().use { document ->
        document.documentInformation.title = "Bilan ${review.year}"
        document.documentInformation.creator = "Budgetly"
        document.documentInformation.creationDate = Calendar.getInstance().apply { timeInMillis = generatedAt.toEpochMilli() }
        val writer = Writer(document)
        val period = if (review.through.monthValue == 12) "année complète" else "de janvier à ${month(review)}"
        writer.line("Bilan ${review.year}", bold, 18f, gapAfter = 6f)
        writer.line("$period, comparé à la même période de ${review.year - 1}", regular, 10f, gapAfter = 18f)

        writer.line("Synthèse", bold, 12f, gapAfter = 6f)
        writer.row(listOf("", review.year.toString(), (review.year - 1).toString(), "Écart"), bold)
        writer.row(listOf("Revenus", euros(review.current.incomeCents), euros(review.previous.incomeCents), euros(review.current.incomeCents - review.previous.incomeCents, true)))
        writer.row(listOf("Dépenses", euros(review.current.expenseCents), euros(review.previous.expenseCents), euros(review.current.expenseCents - review.previous.expenseCents, true)))
        writer.row(listOf("Épargne versée", euros(review.current.savingsCents), euros(review.previous.savingsCents), euros(review.current.savingsCents - review.previous.savingsCents, true)))
        writer.row(listOf("Taux d'épargne", percent(review.current.savingsRate), percent(review.previous.savingsRate), ""))
        writer.space(14f)

        if (review.increases.isNotEmpty()) {
            writer.line("Plus fortes hausses de dépenses", bold, 12f, gapAfter = 6f)
            review.increases.forEach { writer.row(listOf(it.name, euros(it.cents), euros(it.previousCents), euros(it.deltaCents, true))) }
            writer.space(14f)
        }
        if (review.decreases.isNotEmpty()) {
            writer.line("Plus fortes baisses de dépenses", bold, 12f, gapAfter = 6f)
            review.decreases.forEach { writer.row(listOf(it.name, euros(it.cents), euros(it.previousCents), euros(it.deltaCents, true))) }
            writer.space(14f)
        }
        writer.line("Dépenses par catégorie", bold, 12f, gapAfter = 6f)
        writer.row(listOf("Catégorie", review.year.toString(), (review.year - 1).toString(), "Écart"), bold)
        review.expenses.forEach { writer.row(listOf(it.name, euros(it.cents), euros(it.previousCents), euros(it.deltaCents, true))) }
        writer.space(14f)
        if (review.income.isNotEmpty()) {
            writer.line("Revenus par catégorie", bold, 12f, gapAfter = 6f)
            writer.row(listOf("Catégorie", review.year.toString(), (review.year - 1).toString(), "Écart"), bold)
            review.income.forEach { writer.row(listOf(it.name, euros(it.cents), euros(it.previousCents), euros(it.deltaCents, true))) }
        }
        writer.space(18f)
        val stamp = DateTimeFormatter.ofPattern("d MMMM yyyy 'à' HH:mm", french).withZone(zone).format(generatedAt)
        writer.line("Établi par Budgetly le $stamp, d'après les relevés importés.", regular, 8f)
        writer.close()
        ByteArrayOutputStream().also(document::save).toByteArray()
    }

    private class Writer(private val document: PDDocument) {
        private var page = PDPage(PDRectangle.A4)
        private var stream: PDPageContentStream
        private var y: Float
        private val columns = listOf(MARGIN, 300f, 395f, 490f)
        private val widths = listOf(240f, 90f, 90f, 0f)

        init {
            document.addPage(page)
            stream = PDPageContentStream(document, page)
            y = page.mediaBox.height - MARGIN
        }

        private fun ensure(height: Float) {
            if (y - height >= MARGIN) return
            stream.close()
            page = PDPage(PDRectangle.A4)
            document.addPage(page)
            stream = PDPageContentStream(document, page)
            y = page.mediaBox.height - MARGIN
        }

        fun space(height: Float) {
            y -= height
        }

        fun line(text: String, font: PDType1Font, size: Float, gapAfter: Float = 4f) {
            ensure(size + gapAfter)
            y -= size
            text(MARGIN, safe(text, font), font, size)
            y -= gapAfter
        }

        /** Première colonne à gauche (tronquée), montants alignés à droite. */
        fun row(cells: List<String>, font: PDType1Font = regular, size: Float = 9.5f) {
            ensure(size + 5f)
            y -= size
            // Remplacement des caractères hors encodage avant toute mesure (getStringWidth échoue sinon).
            text(columns[0], truncate(safe(cells[0], font), font, size, widths[0]), font, size)
            for (i in 1 until cells.size) {
                val right = if (i < columns.size - 1) columns[i + 1] - 10f else page.mediaBox.width - MARGIN
                val width = font.getStringWidth(cells[i]) / 1000 * size
                text(right - width, cells[i], font, size)
            }
            y -= 5f
        }

        private fun truncate(text: String, font: PDType1Font, size: Float, max: Float): String {
            if (font.getStringWidth(text) / 1000 * size <= max) return text
            var cut = text
            while (cut.isNotEmpty() && font.getStringWidth("$cut…") / 1000 * size > max) cut = cut.dropLast(1)
            return "$cut…"
        }

        private fun text(x: Float, value: String, font: PDType1Font, size: Float) {
            stream.beginText()
            stream.setFont(font, size)
            stream.newLineAtOffset(x, y)
            stream.showText(safe(value, font))
            stream.endText()
        }

        /** Caractères absents de l'encodage des polices standard (catégories saisies librement) : remplacés. */
        fun safe(value: String, font: PDType1Font): String = buildString {
            for (c in value) {
                val ok = try {
                    font.encode(c.toString()); true
                } catch (e: IllegalArgumentException) {
                    false
                }
                append(if (ok) c else '?')
            }
        }

        fun close() = stream.close()
    }
}
