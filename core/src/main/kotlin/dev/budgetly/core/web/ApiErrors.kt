package dev.budgetly.core.web

import dev.budgetly.core.imports.ApiException
import dev.budgetly.core.statement.StatementParseException
import dev.budgetly.core.vault.InvalidPasswordException
import dev.budgetly.core.vault.OrphanDatabaseException
import dev.budgetly.core.vault.VaultAlreadyInitializedException
import dev.budgetly.core.vault.VaultLockedException
import dev.budgetly.core.vault.VaultNotInitializedException
import dev.budgetly.core.vault.WeakPasswordException
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.ExceptionHandler
import org.springframework.web.bind.annotation.RestControllerAdvice

/** Erreur renvoyée à l'UI. Les messages sont rédigés pour l'utilisateur et ne contiennent aucune donnée. */
data class ApiError(val error: String, val message: String)

@RestControllerAdvice
class ApiErrors {

    @ExceptionHandler(VaultLockedException::class)
    fun locked(e: VaultLockedException) = error(HttpStatus.LOCKED, "VAULT_LOCKED", e)

    @ExceptionHandler(InvalidPasswordException::class)
    fun invalidPassword(e: InvalidPasswordException) = error(HttpStatus.UNAUTHORIZED, "INVALID_PASSWORD", e)

    @ExceptionHandler(VaultAlreadyInitializedException::class, OrphanDatabaseException::class)
    fun conflict(e: RuntimeException) = error(HttpStatus.CONFLICT, "VAULT_CONFLICT", e)

    @ExceptionHandler(VaultNotInitializedException::class)
    fun notInitialized(e: VaultNotInitializedException) = error(HttpStatus.CONFLICT, "VAULT_NOT_INITIALIZED", e)

    @ExceptionHandler(WeakPasswordException::class)
    fun weak(e: WeakPasswordException) = error(HttpStatus.BAD_REQUEST, "WEAK_PASSWORD", e)

    @ExceptionHandler(StatementParseException::class)
    fun unreadable(e: StatementParseException) = error(HttpStatus.UNPROCESSABLE_ENTITY, "STATEMENT_UNREADABLE", e)

    @ExceptionHandler(ApiException::class)
    fun import(e: ApiException) = error(e.status, e.code, e)

    private fun error(status: HttpStatus, code: String, e: RuntimeException) =
        ResponseEntity.status(status).body(ApiError(code, e.message ?: code))
}
