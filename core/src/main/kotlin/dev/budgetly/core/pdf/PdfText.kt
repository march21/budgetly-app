package dev.budgetly.core.pdf

/** Un mot et sa position horizontale (en points PDF, origine à gauche). */
data class PdfWord(val text: String, val x: Float, val xEnd: Float)

/** Une ligne visuelle : mots triés de gauche à droite, y mesuré depuis le haut de la page. */
data class PdfLine(val page: Int, val y: Float, val words: List<PdfWord>) {
    val text: String get() = words.joinToString(" ") { it.text }
}

data class PdfDocumentText(val pages: Int, val lines: List<PdfLine>)
