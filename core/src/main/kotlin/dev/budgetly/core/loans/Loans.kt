package dev.budgetly.core.loans

import dev.budgetly.core.imports.ApiException
import org.springframework.aot.hint.MemberCategory
import org.springframework.aot.hint.RuntimeHints
import org.springframework.aot.hint.RuntimeHintsRegistrar
import org.springframework.context.annotation.ImportRuntimeHints
import org.springframework.http.HttpStatus
import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.jdbc.support.GeneratedKeyHolder
import org.springframework.stereotype.Service
import java.math.BigDecimal
import java.text.Normalizer
import java.time.Clock
import java.time.LocalDate
import kotlin.math.abs

/** Saisie d'un crédit. [annualRate] : taux nominal annuel en pourcentage (« 3,25 » ou « 3.25 »). */
data class LoanRequest(
    val name: String,
    val lender: String? = null,
    val principalCents: Long,
    val annualRate: String,
    val firstPaymentDate: LocalDate,
    val termMonths: Int,
    val insuranceMonthlyCents: Long? = null,
    val paymentLabel: String? = null,
)

/** Échéances retrouvées parmi les opérations importées (libellé et montant). */
data class MatchedPayments(val count: Int, val lastDate: LocalDate?)

data class LoanView(
    val id: Long,
    val name: String,
    val lender: String?,
    val principalCents: Long,
    val annualRate: String,
    val firstPaymentDate: LocalDate,
    val termMonths: Int,
    val endDate: LocalDate,
    val insuranceMonthlyCents: Long?,
    val paymentLabel: String?,
    /** Échéance hors assurance. */
    val paymentCents: Long,
    val totalInterestCents: Long,
    val paidInstallments: Int,
    /** Capital restant dû après les échéances passées (au jour près). */
    val remainingCents: Long,
    val interestPaidCents: Long,
    /** Prochaine échéance (null une fois le crédit remboursé). */
    val next: Installment?,
    val matched: MatchedPayments?,
)

class LoanRuntimeHints : RuntimeHintsRegistrar {
    override fun registerHints(hints: RuntimeHints, classLoader: ClassLoader?) {
        listOf(LoanView::class.java, Installment::class.java).forEach { type ->
            hints.reflection().registerType(java.lang.reflect.Array.newInstance(type, 0).javaClass, MemberCategory.UNSAFE_ALLOCATED)
            hints.reflection().registerType(type, MemberCategory.INVOKE_PUBLIC_METHODS, MemberCategory.INVOKE_PUBLIC_CONSTRUCTORS, MemberCategory.DECLARED_FIELDS)
        }
    }
}

/** Crédits saisis à la main ; leur capital restant dû vient en déduction du patrimoine. */
@Service
@ImportRuntimeHints(LoanRuntimeHints::class)
class LoanService(private val jdbc: JdbcClient, private val clock: Clock) {

    private data class Row(
        val id: Long, val name: String, val lender: String?, val principalCents: Long, val rate: BigDecimal, val first: LocalDate,
        val term: Int, val insurance: Long?, val label: String?,
    )

    fun list(): List<LoanView> = rows().map(::view)

    fun schedule(id: Long): List<Installment> {
        val r = row(id)
        return Amortization.schedule(r.principalCents, r.rate, r.term, r.first)
    }

    /** Capital restant dû total à une date (patrimoine net, fins de mois). */
    fun outstandingAt(date: LocalDate): Long = rows().sumOf { r ->
        Amortization.schedule(r.principalCents, r.rate, r.term, r.first).lastOrNull { !it.date.isAfter(date) }?.remainingCents
            ?: if (r.first.isAfter(date)) r.principalCents else 0L
    }

    /** Date de début des crédits (un crédit compte à partir du mois précédant sa première échéance). */
    fun earliestStart(): LocalDate? = rows().minOfOrNull { it.first.minusMonths(1) }

    fun create(request: LoanRequest): Long {
        val rate = validate(request)
        val keys = GeneratedKeyHolder()
        jdbc.sql(
            "insert into loan (name, lender, principal_cents, annual_rate, first_payment_date, term_months, insurance_monthly_cents, payment_label, created_at) " +
                "values (?, ?, ?, ?, ?, ?, ?, ?, ?)",
        ).params(*values(request, rate), clock.instant().toString()).update(keys)
        return keys.key!!.toLong()
    }

    fun update(id: Long, request: LoanRequest) {
        val rate = validate(request)
        val n = jdbc.sql(
            "update loan set name = ?, lender = ?, principal_cents = ?, annual_rate = ?, first_payment_date = ?, term_months = ?, " +
                "insurance_monthly_cents = ?, payment_label = ? where id = ?",
        ).params(*values(request, rate), id).update()
        if (n == 0) throw ApiException(HttpStatus.NOT_FOUND, "NOT_FOUND", "Crédit introuvable.")
    }

    fun delete(id: Long) {
        if (jdbc.sql("delete from loan where id = ?").param(id).update() == 0) throw ApiException(HttpStatus.NOT_FOUND, "NOT_FOUND", "Crédit introuvable.")
    }

    private fun view(r: Row): LoanView {
        val today = LocalDate.now(clock)
        val schedule = Amortization.schedule(r.principalCents, r.rate, r.term, r.first)
        val paid = schedule.filter { !it.date.isAfter(today) }
        return LoanView(
            id = r.id,
            name = r.name,
            lender = r.lender,
            principalCents = r.principalCents,
            annualRate = r.rate.stripTrailingZeros().toPlainString(),
            firstPaymentDate = r.first,
            termMonths = r.term,
            endDate = schedule.last().date,
            insuranceMonthlyCents = r.insurance,
            paymentLabel = r.label,
            paymentCents = Amortization.payment(r.principalCents, r.rate, r.term),
            totalInterestCents = schedule.sumOf { it.interestCents },
            paidInstallments = paid.size,
            remainingCents = paid.lastOrNull()?.remainingCents ?: r.principalCents,
            interestPaidCents = paid.sumOf { it.interestCents },
            next = schedule.firstOrNull { it.date.isAfter(today) },
            matched = r.label?.let { matched(it, schedule.first().paymentCents + (r.insurance ?: 0)) },
        )
    }

    /** Opérations de débit dont le libellé contient le texte donné et dont le montant est l'échéance à 1 € près. */
    private fun matched(label: String, monthlyCents: Long): MatchedPayments {
        val needle = normalize(label)
        val dates = jdbc.sql("select booking_date, amount_cents, label from txn where amount_cents < 0")
            .query { rs, _ -> Triple(LocalDate.parse(rs.getString(1)), rs.getLong(2), rs.getString(3)) }.list()
            .filter { (_, cents, text) -> abs(-cents - monthlyCents) <= 100 && normalize(text).contains(needle) }
            .map { it.first }
        return MatchedPayments(dates.size, dates.maxOrNull())
    }

    private fun normalize(text: String) =
        Normalizer.normalize(text, Normalizer.Form.NFD).replace(Regex("\\p{M}"), "").uppercase().replace(Regex("\\s+"), " ").trim()

    private fun values(r: LoanRequest, rate: BigDecimal): Array<Any?> = arrayOf(
        r.name.trim(), r.lender?.trim()?.ifEmpty { null }, r.principalCents, rate.toPlainString(), r.firstPaymentDate.toString(), r.termMonths,
        r.insuranceMonthlyCents, r.paymentLabel?.trim()?.ifEmpty { null },
    )

    private fun validate(r: LoanRequest): BigDecimal {
        fun invalid(message: String): Nothing = throw ApiException(HttpStatus.BAD_REQUEST, "INVALID_REQUEST", message)
        if (r.name.isBlank() || r.name.trim().length > 80) invalid("Donnez un nom au crédit (80 caractères au plus).")
        if (r.principalCents <= 0 || r.principalCents > 100_000_000_000L) invalid("Le montant emprunté doit être positif (1 milliard d'euros au plus).")
        if (r.termMonths !in 1..480) invalid("La durée doit être comprise entre 1 et 480 mois.")
        if ((r.insuranceMonthlyCents ?: 0) !in 0..100_000_000L) invalid("Montant d'assurance invalide.")
        if ((r.paymentLabel?.length ?: 0) > 80 || (r.lender?.length ?: 0) > 80) invalid("Texte trop long (80 caractères au plus).")
        val rate = r.annualRate.trim().replace(',', '.').toBigDecimalOrNull() ?: invalid("Taux illisible : par exemple 3,25.")
        if (rate.signum() < 0 || rate > BigDecimal(20) || rate.scale() > 4) invalid("Taux annuel entre 0 et 20 %, quatre décimales au plus.")
        return rate
    }

    private fun row(id: Long): Row = rows(id).singleOrNull() ?: throw ApiException(HttpStatus.NOT_FOUND, "NOT_FOUND", "Crédit introuvable.")

    private fun rows(id: Long? = null): List<Row> =
        jdbc.sql(
            "select id, name, lender, principal_cents, annual_rate, first_payment_date, term_months, insurance_monthly_cents, payment_label from loan" +
                (if (id != null) " where id = ?" else " order by first_payment_date, id"),
        ).let { if (id != null) it.param(id) else it }.query { rs, _ ->
            Row(
                rs.getLong(1), rs.getString(2), rs.getString(3), rs.getLong(4), BigDecimal(rs.getString(5)), LocalDate.parse(rs.getString(6)),
                rs.getInt(7), rs.getLong(8).takeUnless { rs.wasNull() }, rs.getString(9),
            )
        }.list()
}
