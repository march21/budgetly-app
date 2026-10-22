package dev.budgetly.core.web

import dev.budgetly.core.update.UpdatePreference
import dev.budgetly.core.vault.VaultService
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PutMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController

data class UpdateSetting(val checkAtStartup: Boolean)

@RestController
@RequestMapping("/api/settings")
class SettingsController(
    private val vault: VaultService,
    private val updates: UpdatePreference,
) {
    @GetMapping("/updates")
    fun updateSetting(): UpdateSetting {
        vault.dataSource()
        return UpdateSetting(updates.checkAtStartup())
    }

    @PutMapping("/updates")
    fun setUpdateSetting(@RequestBody request: UpdateSetting): UpdateSetting {
        vault.dataSource()
        updates.setCheckAtStartup(request.checkAtStartup)
        return UpdateSetting(updates.checkAtStartup())
    }
}
