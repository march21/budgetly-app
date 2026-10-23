package dev.budgetly.core.web

import dev.budgetly.core.milestones.MilestoneReport
import dev.budgetly.core.milestones.MilestoneService
import dev.budgetly.core.milestones.OpeningDate
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
class MilestoneController(private val milestones: MilestoneService) {

    @GetMapping("/milestones")
    fun report(): MilestoneReport = milestones.report()

    @PutMapping("/accounts/{accountId}/opened-on")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    fun setOpeningDate(@PathVariable accountId: Long, @RequestBody request: OpeningDate) = milestones.setOpeningDate(accountId, request)
}
