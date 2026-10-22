package dev.budgetly.core.web

import dev.budgetly.core.imports.ApiException
import dev.budgetly.core.ledger.MonthProjection
import dev.budgetly.core.ledger.Outlook
import dev.budgetly.core.ledger.RecurringOverview
import org.springframework.http.HttpStatus
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PutMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.ResponseStatus
import org.springframework.web.bind.annotation.RestController
import java.time.Clock
import java.time.YearMonth
import java.time.format.DateTimeParseException

data class IgnoreRequest(val key: String, val ignored: Boolean)

@RestController
@RequestMapping("/api")
class OutlookController(private val outlook: Outlook, private val clock: Clock) {

    /** Charges fixes, abonnements et revenus récurrents détectés. */
    @GetMapping("/recurring")
    fun recurring(): RecurringOverview = outlook.recurring()

    /** Écarter (ou rétablir) une série : « ce n'est pas une charge fixe ». */
    @PutMapping("/recurring/ignored")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    fun ignore(@RequestBody request: IgnoreRequest) = outlook.ignore(request.key, request.ignored)

    /** Projection de fin de mois et alertes de budget. */
    @GetMapping("/projection")
    fun projection(@RequestParam(required = false) month: String?): MonthProjection {
        val parsed = try {
            month?.let(YearMonth::parse) ?: YearMonth.now(clock)
        } catch (e: DateTimeParseException) {
            throw ApiException(HttpStatus.BAD_REQUEST, "INVALID_MONTH", "Mois attendu au format AAAA-MM.")
        }
        return outlook.projection(parsed)
    }
}
