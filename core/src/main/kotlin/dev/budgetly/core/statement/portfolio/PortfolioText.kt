package dev.budgetly.core.statement.portfolio

import dev.budgetly.core.pdf.PdfLine
import dev.budgetly.core.pdf.PdfWord
import java.math.BigDecimal
import java.text.Normalizer
import java.time.LocalDate
import kotlin.math.abs

/** Outils partagés par les parsers de relevés de portefeuille. */
object PortfolioText {
    val isinPattern = Regex("""^[A-Z]{2}[A-Z0-9]{9}\d$""")
    private val numberPart = Regex("""^[-+]?[\d.,]+$|^[-+]$""")
    private val decimal = Regex("""^[-+]?\d+(?:,\d+)?$""")
    private val thousandsHead = Regex("""^[-+]?\d{1,3}$|^[-+]?\d{1,3}(?:\d{3})+$""")
    private val signOnly = Regex("""^[-+]$""")
    private val thousandsTail = Regex("""^\d{3}(?:,\d+)?$|^\d+,\d+$""")

    data class NumberGroup(val text: String, val x: Float, val xEnd: Float)

    /** Mots numériques voisins fusionnés (« 1 » « 430,68 » → « 1430,68 », « - » « 4,68 » → « -4,68 »). */
    fun numberGroups(words: List<PdfWord>, fromX: Float = 0f): List<NumberGroup> {
        val groups = mutableListOf<MutableList<PdfWord>>()
        for (word in words.filter { it.x >= fromX && numberPart.matches(it.text) }) {
            val last = groups.lastOrNull()
            // On ne fusionne qu'un début de nombre (1 à 3 chiffres, ou un signe seul) avec la suite :
            // deux colonnes voisines peuvent n'être séparées que de 4 pt.
            val head = last?.joinToString("") { it.text }
            val continues = head != null && (thousandsHead.matches(head) || signOnly.matches(head)) &&
                word.x - last.last().xEnd < 6f && thousandsTail.matches(word.text)
            if (continues) last!! += word else groups += mutableListOf(word)
        }
        return groups.map { g -> NumberGroup(g.joinToString("") { it.text }, g.first().x, g.last().xEnd) }
            .filter { decimal.matches(it.text) }
    }

    /**
     * Range chaque nombre dans la colonne dont le bord droit est le plus proche (à `tolerance` près).
     * `columns` : nom de colonne → bord droit attendu.
     */
    fun byColumn(groups: List<NumberGroup>, columns: Map<String, Float>, tolerance: Float = 18f): Map<String, String> =
        groups.mapNotNull { group ->
            val (name, edge) = columns.minBy { abs(it.value - group.xEnd) }
            if (abs(edge - group.xEnd) <= tolerance) name to group.text else null
        }.toMap()

    fun decimalOf(text: String): BigDecimal = BigDecimal(text.replace(",", "."))

    /** « 1430,68 » → 143068 centimes (arrondi au centime). */
    fun centsOf(text: String): Long = decimalOf(text).movePointRight(2).setScale(0, java.math.RoundingMode.HALF_UP).toLong()

    fun normalize(text: String): String =
        Normalizer.normalize(text, Normalizer.Form.NFD).replace(Regex("\\p{M}"), "").lowercase()

    private val months = listOf(
        "janvier", "fevrier", "mars", "avril", "mai", "juin",
        "juillet", "aout", "septembre", "octobre", "novembre", "decembre",
    )

    /** « 30 septembre 2026 » dans une liste de mots → date. */
    fun frenchDate(words: List<String>): LocalDate? {
        for (i in 0 until words.size - 2) {
            val day = words[i].toIntOrNull() ?: continue
            val month = months.indexOf(normalize(words[i + 1])).takeIf { it >= 0 } ?: continue
            val year = words[i + 2].toIntOrNull() ?: continue
            return runCatching { LocalDate.of(year, month + 1, day) }.getOrNull()
        }
        return null
    }

    fun textOf(lines: List<PdfLine>, maxX: Float) =
        lines.flatMap { line -> line.words.filter { it.x < maxX }.map { it.text } }.joinToString(" ").trim()
}
