package dev.budgetly.core.web

import dev.budgetly.core.milestones.MilestoneReport
import dev.budgetly.core.milestones.MilestoneService
import dev.budgetly.core.milestones.OpeningDate
import dev.budgetly.core.milestones.TaxAidReport
import dev.budgetly.core.milestones.TaxAidService
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.http.HttpStatus
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PutMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.ResponseStatus
import org.springframework.web.bind.annotation.RestController

@RestController
@RequestMapping("/api")
class MilestoneController(private val milestones: MilestoneService, private val taxAid: TaxAidService, private val clock: java.time.Clock) {

    @GetMapping("/milestones")
    fun report(): MilestoneReport = milestones.report()

    /** Aide à la déclaration des revenus d'une année (par défaut l'année précédente, celle que l'on déclare). */
    @GetMapping("/summary/tax")
    fun taxAid(@RequestParam(required = false) year: Int?): TaxAidReport = taxAid.report(year ?: (java.time.LocalDate.now(clock).year - 1))

    @PutMapping("/accounts/{accountId}/opened-on")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    fun setOpeningDate(@PathVariable accountId: Long, @RequestBody request: OpeningDate) = milestones.setOpeningDate(accountId, request)
}
