package dev.budgetly.core.web

import dev.budgetly.core.backup.BackupKind
import dev.budgetly.core.backup.BackupService
import dev.budgetly.core.config.BudgetlyProperties
import dev.budgetly.core.vault.AutoLock
import dev.budgetly.core.vault.VaultService
import dev.budgetly.core.vault.VaultState
import org.springframework.http.HttpStatus
import org.springframework.http.MediaType
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.PutMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.ResponseStatus
import org.springframework.web.bind.annotation.RestController
import org.springframework.web.multipart.MultipartFile

data class VaultStatus(val state: VaultState, val minPasswordLength: Int)

data class PasswordRequest(val password: String)

data class PasswordChangeRequest(val currentPassword: String, val newPassword: String)

data class AutoLockSetting(val minutes: Int, val allowed: List<Int> = AutoLock.ALLOWED)

@RestController
@RequestMapping("/api/vault")
class VaultController(
    private val vault: VaultService,
    private val backups: BackupService,
    private val props: BudgetlyProperties,
    private val autoLock: AutoLock,
) {

    @GetMapping
    fun status() = VaultStatus(vault.state, props.vault.minPasswordLength)

    @PostMapping("/setup")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    fun setup(@RequestBody request: PasswordRequest) = vault.setup(request.password)

    @PostMapping("/unlock")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    fun unlock(@RequestBody request: PasswordRequest) = vault.unlock(request.password)

    @PostMapping("/lock")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    fun lock() = vault.lock()

    /** Signal d'activité envoyé par l'interface (frappe, clic) : repousse le verrouillage automatique. */
    @PostMapping("/activity")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    fun activity() {
        if (vault.state == VaultState.UNLOCKED) autoLock.touch()
    }

    @GetMapping("/auto-lock")
    fun autoLockSetting(): AutoLockSetting {
        vault.dataSource()
        return AutoLockSetting(autoLock.timeoutMinutes)
    }

    @PutMapping("/auto-lock")
    fun setAutoLock(@RequestBody request: AutoLockSetting): AutoLockSetting {
        vault.dataSource()
        autoLock.setTimeout(request.minutes)
        return AutoLockSetting(autoLock.timeoutMinutes)
    }

    /** Une sauvegarde de sécurité précède le rechiffrement : elle s'ouvre avec l'ancien mot de passe. */
    @PostMapping("/password")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    fun changePassword(@RequestBody request: PasswordChangeRequest) {
        vault.checkPassword(request.currentPassword)
        vault.checkStrength(request.newPassword)
        backups.create(BackupKind.SAFETY)
        vault.changePassword(request.currentPassword, request.newPassword)
    }

    /** Restaure une sauvegarde (coffre verrouillé ou non créé) puis ouvre le coffre avec son mot de passe. */
    @PostMapping("/restore", consumes = [MediaType.MULTIPART_FORM_DATA_VALUE])
    @ResponseStatus(HttpStatus.NO_CONTENT)
    fun restore(@RequestParam("file") file: MultipartFile, @RequestParam("password") password: String) =
        file.inputStream.use { backups.restore(it, password) }
}
