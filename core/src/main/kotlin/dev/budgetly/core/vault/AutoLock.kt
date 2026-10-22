package dev.budgetly.core.vault

import dev.budgetly.core.imports.ApiException
import org.slf4j.LoggerFactory
import org.springframework.context.event.EventListener
import org.springframework.http.HttpStatus
import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Component
import java.time.Clock
import java.time.Duration
import java.time.Instant

/**
 * Verrouille le coffre après une période sans activité. L'activité, c'est toute requête de données
 * et les signaux que l'interface envoie quand l'utilisateur tape ou clique (au plus un par minute).
 * Le délai est un réglage stocké dans la base ; 0 = jamais.
 */
@Component
class AutoLock(
    private val vault: VaultService,
    private val jdbc: JdbcClient,
    private val clock: Clock,
) {
    private val log = LoggerFactory.getLogger(javaClass)

    @Volatile
    private var lastActivity: Instant = Instant.EPOCH

    @Volatile
    private var minutes: Int = DEFAULT_MINUTES

    /** Délai courant en minutes (0 = jamais). */
    val timeoutMinutes: Int get() = minutes

    fun touch() {
        lastActivity = clock.instant()
    }

    @EventListener
    fun onUnlocked(event: VaultUnlockedEvent) {
        touch()
        minutes = jdbc.sql("select value from app_setting where key = ?").param(KEY)
            .query(String::class.java).optional().map { it.toIntOrNull() ?: DEFAULT_MINUTES }.orElse(DEFAULT_MINUTES)
    }

    fun setTimeout(minutes: Int) {
        if (minutes !in ALLOWED) {
            throw ApiException(HttpStatus.BAD_REQUEST, "INVALID_REQUEST", "Délai possible : ${ALLOWED.joinToString(", ")} minutes (0 = jamais).")
        }
        jdbc.sql("insert into app_setting (key, value) values (?, ?) on conflict(key) do update set value = excluded.value")
            .params(KEY, minutes.toString()).update()
        this.minutes = minutes
        touch()
    }

    @Scheduled(fixedDelay = 15_000, initialDelay = 15_000)
    fun check() {
        lockIfIdle(clock.instant())
    }

    /** Vrai si le coffre vient d'être verrouillé. */
    fun lockIfIdle(now: Instant): Boolean {
        val minutes = this.minutes
        if (minutes <= 0 || vault.state != VaultState.UNLOCKED) return false
        if (Duration.between(lastActivity, now) < Duration.ofMinutes(minutes.toLong())) return false
        vault.lock()
        log.info("Coffre verrouillé après {} minutes d'inactivité", minutes)
        return true
    }

    companion object {
        const val DEFAULT_MINUTES = 15
        private const val KEY = "auto-lock-minutes"
        val ALLOWED = listOf(0, 5, 15, 30, 60)
    }
}
