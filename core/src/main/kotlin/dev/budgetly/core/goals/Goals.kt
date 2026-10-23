package dev.budgetly.core.goals

import dev.budgetly.core.imports.ApiException
import dev.budgetly.core.portfolio.PortfolioService
import org.springframework.aot.hint.MemberCategory
import org.springframework.aot.hint.RuntimeHints
import org.springframework.aot.hint.RuntimeHintsRegistrar
import org.springframework.context.annotation.ImportRuntimeHints
import org.springframework.http.HttpStatus
import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.jdbc.support.GeneratedKeyHolder
import org.springframework.stereotype.Service
import java.time.Clock
import java.time.LocalDate
import java.time.YearMonth
import java.time.temporal.ChronoUnit

/** Saisie d'un objectif. Suivi par le solde d'un compte ([accountId]) ou par un montant saisi ([savedCents]). */
data class GoalRequest(
    val name: String,
    val targetCents: Long,
    val targetDate: LocalDate? = null,
    val accountId: Long? = null,
    val savedCents: Long? = null,
    /** Effort d'épargne prévu par mois : sert à estimer la date d'atteinte. */
    val monthlyCents: Long? = null,
)

enum class GoalSource { ACCOUNT, MANUAL }

data class GoalView(
    val id: Long,
    val name: String,
    val targetCents: Long,
    val targetDate: LocalDate?,
    val accountId: Long?,
    val accountName: String?,
    val source: GoalSource,
    val currentCents: Long,
    val monthlyCents: Long?,
    val reached: Boolean,
    /** Mois entiers restants jusqu'à la date cible (0 si elle est passée ou ce mois-ci). */
    val monthsLeft: Int?,
    /** Effort mensuel nécessaire pour atteindre la cible à la date voulue (arrondi au centime supérieur). */
    val requiredMonthlyCents: Long?,
    /** Mois d'atteinte au rythme de [monthlyCents]. */
    val projectedMonth: YearMonth?,
)

/** Tableau d'éléments créé par réflexion à la sérialisation (même précaution que pour les autres listes renvoyées). */
class GoalRuntimeHints : RuntimeHintsRegistrar {
    override fun registerHints(hints: RuntimeHints, classLoader: ClassLoader?) {
        hints.reflection().registerType(java.lang.reflect.Array.newInstance(GoalView::class.java, 0).javaClass, MemberCategory.UNSAFE_ALLOCATED)
        hints.reflection().registerType(GoalView::class.java, MemberCategory.INVOKE_PUBLIC_METHODS, MemberCategory.INVOKE_PUBLIC_CONSTRUCTORS, MemberCategory.DECLARED_FIELDS)
    }
}

@Service
@ImportRuntimeHints(GoalRuntimeHints::class)
class GoalService(private val jdbc: JdbcClient, private val portfolio: PortfolioService, private val clock: Clock) {

    private data class Row(
        val id: Long, val name: String, val targetCents: Long, val targetDate: LocalDate?, val accountId: Long?,
        val savedCents: Long?, val monthlyCents: Long?,
    )

    fun list(): List<GoalView> {
        val today = LocalDate.now(clock)
        val worth = portfolio.netWorth()
        val values = worth.cashAccounts.associate { it.accountId to it.balanceCents } + worth.envelopes.associate { it.accountId to it.valueCents }
        val names = jdbc.sql("select id, name from account").query { rs, _ -> rs.getLong(1) to rs.getString(2) }.list().toMap()
        return rows().map { r ->
            val linked = r.accountId?.takeIf { it in names }
            val current = if (linked != null) values[linked] ?: 0L else r.savedCents ?: 0L
            val remaining = (r.targetCents - current).coerceAtLeast(0)
            val monthsLeft = r.targetDate?.let { ChronoUnit.MONTHS.between(YearMonth.from(today), YearMonth.from(it)).toInt().coerceAtLeast(0) }
            GoalView(
                id = r.id,
                name = r.name,
                targetCents = r.targetCents,
                targetDate = r.targetDate,
                accountId = linked,
                accountName = linked?.let(names::get),
                source = if (linked != null) GoalSource.ACCOUNT else GoalSource.MANUAL,
                currentCents = current,
                monthlyCents = r.monthlyCents,
                reached = remaining == 0L,
                monthsLeft = monthsLeft,
                requiredMonthlyCents = monthsLeft?.takeIf { remaining > 0 }?.let { m -> if (m == 0) remaining else ceilDiv(remaining, m.toLong()) },
                projectedMonth = r.monthlyCents?.takeIf { it > 0 && remaining > 0 }?.let { YearMonth.from(today).plusMonths(ceilDiv(remaining, it)) },
            )
        }
    }

    fun create(request: GoalRequest): Long {
        validate(request)
        val keys = GeneratedKeyHolder()
        jdbc.sql(
            "insert into savings_goal (name, target_cents, target_date, account_id, saved_cents, monthly_cents, created_at) values (?, ?, ?, ?, ?, ?, ?)",
        ).params(*values(request), clock.instant().toString()).update(keys)
        return keys.key!!.toLong()
    }

    fun update(id: Long, request: GoalRequest) {
        validate(request)
        val updated = jdbc.sql(
            "update savings_goal set name = ?, target_cents = ?, target_date = ?, account_id = ?, saved_cents = ?, monthly_cents = ? where id = ?",
        ).params(*values(request), id).update()
        if (updated == 0) throw ApiException(HttpStatus.NOT_FOUND, "NOT_FOUND", "Objectif introuvable.")
    }

    fun delete(id: Long) {
        if (jdbc.sql("delete from savings_goal where id = ?").param(id).update() == 0) {
            throw ApiException(HttpStatus.NOT_FOUND, "NOT_FOUND", "Objectif introuvable.")
        }
    }

    private fun values(r: GoalRequest): Array<Any?> = arrayOf(
        r.name.trim(), r.targetCents, r.targetDate?.toString(), r.accountId, if (r.accountId == null) r.savedCents ?: 0L else null, r.monthlyCents,
    )

    private fun validate(r: GoalRequest) {
        fun invalid(message: String): Nothing = throw ApiException(HttpStatus.BAD_REQUEST, "INVALID_REQUEST", message)
        if (r.name.isBlank() || r.name.trim().length > 80) invalid("Donnez un nom à l'objectif (80 caractères au plus).")
        if (r.targetCents <= 0) invalid("Le montant visé doit être positif.")
        if ((r.savedCents ?: 0) < 0 || (r.monthlyCents ?: 0) < 0) invalid("Les montants ne peuvent pas être négatifs.")
        if (r.targetDate != null && r.targetDate.isBefore(LocalDate.now(clock))) invalid("La date visée est déjà passée.")
        if (r.accountId != null) {
            val exists = jdbc.sql("select count(*) from account where id = ?").param(r.accountId).query(Long::class.java).single() > 0
            if (!exists) invalid("Compte introuvable.")
        }
    }

    private fun rows(): List<Row> =
        jdbc.sql("select id, name, target_cents, target_date, account_id, saved_cents, monthly_cents from savings_goal order by coalesce(target_date, '9999'), id")
            .query { rs, _ ->
                Row(
                    rs.getLong(1), rs.getString(2), rs.getLong(3), rs.getString(4)?.let(LocalDate::parse),
                    rs.getLong(5).takeUnless { rs.wasNull() }, rs.getLong(6).takeUnless { rs.wasNull() }, rs.getLong(7).takeUnless { rs.wasNull() },
                )
            }.list()

    private fun ceilDiv(a: Long, b: Long) = Math.floorDiv(a + b - 1, b)
}
