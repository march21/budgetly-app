package dev.budgetly.core.web

import dev.budgetly.core.vault.VaultService
import dev.budgetly.core.vault.VaultState
import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController

data class HealthResponse(val status: String, val vault: VaultState, val schemaVersion: String?)

@RestController
@RequestMapping("/api")
class HealthController(private val jdbc: JdbcClient, private val vault: VaultService) {

    @GetMapping("/health")
    fun health(): HealthResponse {
        val state = vault.state
        val schema = if (state == VaultState.UNLOCKED) {
            jdbc.sql("select max(version) from flyway_schema_history where success = 1")
                .query(String::class.java)
                .optional()
                .orElse(null)
        } else {
            null
        }
        return HealthResponse(status = "UP", vault = state, schemaVersion = schema)
    }
}
