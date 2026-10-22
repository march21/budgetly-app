package dev.budgetly.core.web

import dev.budgetly.core.backup.BackupInfo
import dev.budgetly.core.backup.BackupKind
import dev.budgetly.core.backup.BackupList
import dev.budgetly.core.backup.BackupService
import org.springframework.core.io.FileSystemResource
import org.springframework.core.io.Resource
import org.springframework.http.HttpHeaders
import org.springframework.http.HttpStatus
import org.springframework.http.MediaType
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.DeleteMapping
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.ResponseStatus
import org.springframework.web.bind.annotation.RestController

@RestController
@RequestMapping("/api/backups")
class BackupController(private val backups: BackupService, private val vault: dev.budgetly.core.vault.VaultService) {

    @GetMapping
    fun list(): BackupList {
        vault.dataSource()
        return backups.list()
    }

    @PostMapping
    fun create(): BackupInfo = backups.create(BackupKind.MANUAL)

    /** Téléchargement d'une sauvegarde (chiffrée) pour la copier ailleurs : disque externe, autre machine. */
    @GetMapping("/{name}")
    fun download(@PathVariable name: String): ResponseEntity<Resource> {
        vault.dataSource()
        val file = backups.file(name)
        return ResponseEntity.ok()
            .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"${file.fileName}\"")
            .contentType(MediaType.APPLICATION_OCTET_STREAM)
            .body(FileSystemResource(file))
    }

    @DeleteMapping("/{name}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    fun delete(@PathVariable name: String) {
        vault.dataSource()
        backups.delete(name)
    }
}
