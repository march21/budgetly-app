package dev.budgetly.core.web

import dev.budgetly.core.portfolio.PriceProvider
import dev.budgetly.core.pdf.PdfDocumentText
import dev.budgetly.core.pdf.PdfTextExtractor
import org.springframework.http.MediaType
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController
import org.springframework.web.multipart.MultipartFile

/**
 * Outil de développement : renvoie le texte positionné d'un PDF pour concevoir les parsers.
 * Rien n'est écrit sur disque ni journalisé.
 */
@RestController
@RequestMapping("/api/dev")
class PdfInspectController(private val providers: List<PriceProvider>) {

    /** Vérifie la source de cours pour un ISIN donné (à utiliser avec un ISIN public, pas un support détenu). */
    @GetMapping("/price-check")
    fun priceCheck(@RequestParam isin: String): Map<String, Any?> =
        providers.firstOrNull()?.diagnose(isin.trim().uppercase()) ?: mapOf("provider" to null, "error" to "NO_PROVIDER")


    @PostMapping("/pdf-text", consumes = [MediaType.MULTIPART_FORM_DATA_VALUE])
    fun inspect(@RequestParam("file") file: MultipartFile): PdfDocumentText =
        PdfTextExtractor.extract(file.bytes)
}
