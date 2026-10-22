package dev.budgetly.core.imports.tabular

import java.nio.ByteBuffer
import java.nio.charset.CharacterCodingException
import java.nio.charset.Charset
import java.nio.charset.CodingErrorAction

/** Format d'un export bancaire tabulaire. */
enum class TabularFormat { CSV, OFX }

/** Texte décodé d'un export : UTF-8 (avec ou sans BOM) sinon Windows-1252, l'encodage courant des banques. */
data class DecodedText(val text: String, val encoding: String)

object TabularText {
    private val windows1252: Charset = Charset.forName("windows-1252")

    fun decode(bytes: ByteArray): DecodedText {
        if (bytes.size >= 3 && bytes[0] == 0xEF.toByte() && bytes[1] == 0xBB.toByte() && bytes[2] == 0xBF.toByte()) {
            return DecodedText(String(bytes, 3, bytes.size - 3, Charsets.UTF_8), "UTF-8")
        }
        return try {
            val decoder = Charsets.UTF_8.newDecoder()
                .onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT)
            DecodedText(decoder.decode(ByteBuffer.wrap(bytes)).toString(), "UTF-8")
        } catch (e: CharacterCodingException) {
            DecodedText(String(bytes, windows1252), "Windows-1252")
        }
    }

    fun formatOf(text: String): TabularFormat {
        val head = text.take(4_000).uppercase()
        return if (head.contains("OFXHEADER") || head.contains("<OFX>")) TabularFormat.OFX else TabularFormat.CSV
    }

    /** Séparateur le plus régulier sur les premières lignes non vides : « ; », « , », tabulation ou « | ». */
    fun detectDelimiter(text: String): Char {
        val lines = text.lineSequence().map { it.trim() }.filter { it.isNotEmpty() }.take(30).toList()
        val candidates = listOf(';', ',', '\t', '|')
        return candidates.maxByOrNull { delimiter ->
            val counts = lines.map { line -> split(line, delimiter).size }.filter { it > 1 }
            if (counts.isEmpty()) {
                0.0
            } else {
                val mode = counts.groupingBy { it }.eachCount().maxBy { it.value }
                // Nombre de lignes qui partagent le nombre de colonnes le plus fréquent, pondéré par ce nombre.
                mode.value * 10.0 + mode.key
            }
        } ?: ';'
    }

    /** Lignes du fichier découpées en cellules (guillemets à la RFC 4180 : « "" » = guillemet littéral). */
    fun rows(text: String, delimiter: Char): List<List<String>> {
        val rows = mutableListOf<List<String>>()
        val cell = StringBuilder()
        var row = mutableListOf<String>()
        var quoted = false
        var i = 0
        while (i < text.length) {
            val c = text[i]
            when {
                quoted && c == '"' && i + 1 < text.length && text[i + 1] == '"' -> {
                    cell.append('"')
                    i++
                }
                c == '"' && (quoted || cell.isBlank()) -> {
                    if (!quoted) cell.setLength(0)
                    quoted = !quoted
                }
                !quoted && c == delimiter -> {
                    row.add(cell.toString().trim())
                    cell.setLength(0)
                }
                !quoted && (c == '\n' || c == '\r') -> {
                    if (c == '\r' && i + 1 < text.length && text[i + 1] == '\n') i++
                    row.add(cell.toString().trim())
                    cell.setLength(0)
                    if (row.any { it.isNotEmpty() }) rows.add(row)
                    row = mutableListOf()
                }
                else -> cell.append(c)
            }
            i++
        }
        row.add(cell.toString().trim())
        if (row.any { it.isNotEmpty() }) rows.add(row)
        return rows
    }

    private fun split(line: String, delimiter: Char): List<String> = rows(line, delimiter).firstOrNull() ?: emptyList()
}
