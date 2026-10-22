package dev.budgetly.core.web

import dev.budgetly.core.imports.ImportPreview
import dev.budgetly.core.imports.ImportRecord
import dev.budgetly.core.imports.ImportJournal
import dev.budgetly.core.imports.ImportService
import dev.budgetly.core.ledger.TransactionCsv
import dev.budgetly.core.ledger.AccountView
import dev.budgetly.core.ledger.Ledger
import dev.budgetly.core.ledger.TransactionFilter
import dev.budgetly.core.ledger.TransactionView
import org.springframework.format.annotation.DateTimeFormat
import org.springframework.http.MediaType
import org.springframework.http.HttpHeaders
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.DeleteMapping
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.ResponseStatus
import org.springframework.web.bind.annotation.RestController
import org.springframework.web.multipart.MultipartFile
import java.time.LocalDate

@RestController
@RequestMapping("/api")
class ImportController(
    private val imports: ImportService,
    private val journal: ImportJournal,
    private val ledger: Ledger,
    private val clock: java.time.Clock,
) {

    /** Analyse un relevé sans rien écrire. Le fichier n'est ni stocké ni journalisé. */
    @PostMapping("/imports/preview", consumes = [MediaType.MULTIPART_FORM_DATA_VALUE])
    fun preview(@RequestParam("file") file: MultipartFile): ImportPreview = imports.preview(file.bytes)

    @PostMapping("/imports/{previewId}/commit")
    fun commit(@PathVariable previewId: String): ImportRecord = imports.commit(previewId)

    @GetMapping("/imports")
    fun history(): List<ImportRecord> = journal.history()

    /** Annule le dernier import des comptes concernés : opérations, soldes et positions qu'il a créés disparaissent. */
    @DeleteMapping("/imports/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    fun cancel(@PathVariable id: Long) = journal.cancel(id)

    @GetMapping("/accounts")
    fun accounts(): List<AccountView> = ledger.accounts()

    @GetMapping("/transactions")
    fun transactions(
        @RequestParam(required = false) accountId: Long?,
        @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) from: LocalDate?,
        @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) to: LocalDate?,
        @RequestParam(required = false) q: String?,
        @RequestParam(required = false) categoryId: Long?,
        @RequestParam(required = false, defaultValue = "false") uncategorized: Boolean,
        @RequestParam(required = false, defaultValue = "500") limit: Int,
    ): List<TransactionView> =
        ledger.transactions(TransactionFilter(accountId, from, to, q, categoryId, uncategorized, limit.coerceIn(1, 2_000)))

    /** Export CSV pour un tableur (séparateur « ; », virgule décimale, UTF-8 avec BOM pour Excel). */
    @GetMapping("/transactions/export.csv")
    fun exportCsv(
        @RequestParam(required = false) accountId: Long?,
        @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) from: LocalDate?,
        @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) to: LocalDate?,
    ): ResponseEntity<ByteArray> {
        val rows = ledger.transactions(TransactionFilter(accountId, from, to, limit = 1_000_000))
        val name = "budgetly-operations-${LocalDate.now(clock)}.csv"
        return ResponseEntity.ok()
            .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"$name\"")
            .contentType(MediaType("text", "csv", Charsets.UTF_8))
            .body(TransactionCsv.write(rows))
    }
}
