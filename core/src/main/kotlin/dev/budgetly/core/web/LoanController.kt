package dev.budgetly.core.web

import dev.budgetly.core.loans.Installment
import dev.budgetly.core.loans.LoanRequest
import dev.budgetly.core.loans.LoanService
import dev.budgetly.core.loans.LoanView
import org.springframework.http.HttpStatus
import org.springframework.web.bind.annotation.DeleteMapping
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.PutMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.ResponseStatus
import org.springframework.web.bind.annotation.RestController

data class CreatedLoan(val id: Long)

@RestController
@RequestMapping("/api/loans")
class LoanController(private val loans: LoanService) {

    @GetMapping
    fun list(): List<LoanView> = loans.list()

    @GetMapping("/{id}/schedule")
    fun schedule(@PathVariable id: Long): List<Installment> = loans.schedule(id)

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    fun create(@RequestBody request: LoanRequest) = CreatedLoan(loans.create(request))

    @PutMapping("/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    fun update(@PathVariable id: Long, @RequestBody request: LoanRequest) = loans.update(id, request)

    @DeleteMapping("/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    fun delete(@PathVariable id: Long) = loans.delete(id)
}
