package dev.budgetly.core.pdf

import org.springframework.aot.hint.RuntimeHints
import org.springframework.aot.hint.RuntimeHintsRegistrar

/**
 * PDFBox charge à l'exécution des ressources (métriques AFM des polices standard,
 * glyphlist, CMaps). Elles doivent être embarquées explicitement dans l'image native.
 */
class PdfBoxRuntimeHints : RuntimeHintsRegistrar {
    override fun registerHints(hints: RuntimeHints, classLoader: ClassLoader?) {
        hints.resources()
            .registerPattern("org/apache/pdfbox/resources/**")
            .registerPattern("org/apache/fontbox/**")
    }
}
