package dev.budgetly.core.web

import dev.budgetly.core.imports.ImportPreview
import dev.budgetly.core.imports.tabular.TabularAnalysis
import dev.budgetly.core.imports.tabular.TabularImportService
import dev.budgetly.core.imports.tabular.TabularPreviewRequest
import org.springframework.http.MediaType
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController
import org.springframework.web.multipart.MultipartFile

/** Exports CSV / OFX : analyse du fichier, puis aperçu une fois colonnes, compte et soldes choisis. La validation passe par /api/imports/{id}/commit. */
@RestController
@RequestMapping("/api/imports/tabular")
class TabularImportController(private val tabular: TabularImportService) {

    @PostMapping("/analyze", consumes = [MediaType.MULTIPART_FORM_DATA_VALUE])
    fun analyze(@RequestParam("file") file: MultipartFile): TabularAnalysis = tabular.analyze(file.bytes)

    @PostMapping("/{token}/preview")
    fun preview(@PathVariable token: String, @RequestBody request: TabularPreviewRequest): ImportPreview =
        tabular.preview(token, request)
}
