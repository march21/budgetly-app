package dev.budgetly.core.statement

import dev.budgetly.core.pdf.PdfDocumentText

/** Un parser = un établissement × un type de document. */
interface StatementParser {
    val id: String
    val institution: String

    fun supports(document: PdfDocumentText): Boolean

    fun parse(document: PdfDocumentText): ParsedStatement
}
