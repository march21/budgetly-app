package dev.budgetly.core.web

import dev.budgetly.core.categories.CategoryService
import dev.budgetly.core.categories.CategoryView
import dev.budgetly.core.categories.NewCategory
import dev.budgetly.core.categories.NewRule
import dev.budgetly.core.categories.RuleSuggestion
import dev.budgetly.core.categories.RuleView
import dev.budgetly.core.categories.SplitPart
import dev.budgetly.core.imports.ApiException
import dev.budgetly.core.ledger.MonthSummary
import dev.budgetly.core.ledger.MonthlySummary
import dev.budgetly.core.ledger.SummaryHistory
import org.springframework.http.HttpStatus
import org.springframework.web.bind.annotation.DeleteMapping
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PatchMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.PutMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.ResponseStatus
import org.springframework.web.bind.annotation.RestController
import java.time.Clock
import java.time.YearMonth
import java.time.format.DateTimeParseException

data class CreatedId(val id: Long)

data class RenameRequest(val name: String)

data class BudgetRequest(val monthlyCents: Long?)

data class CategorizeRequest(val categoryId: Long?, val rulePattern: String? = null)

data class BulkCategorizeRequest(val transactionIds: List<Long>, val categoryId: Long?)

data class BulkCategorizeResult(val updated: Int)

data class SplitRequest(val parts: List<SplitPart>)

data class NoteRequest(val note: String?)

@RestController
@RequestMapping("/api")
class CategoryController(
    private val categories: CategoryService,
    private val summary: MonthlySummary,
    private val clock: Clock,
) {
    @GetMapping("/categories")
    fun categories(): List<CategoryView> = categories.categories()

    @PostMapping("/categories")
    fun createCategory(@RequestBody request: NewCategory) = CreatedId(categories.createCategory(request))

    @PatchMapping("/categories/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    fun renameCategory(@PathVariable id: Long, @RequestBody request: RenameRequest) = categories.renameCategory(id, request.name)

    @DeleteMapping("/categories/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    fun deleteCategory(@PathVariable id: Long) = categories.deleteCategory(id)

    @PutMapping("/categories/{id}/budget")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    fun setBudget(@PathVariable id: Long, @RequestBody request: BudgetRequest) = categories.setBudget(id, request.monthlyCents)

    @GetMapping("/rules")
    fun rules(): List<RuleView> = categories.rules()

    @PostMapping("/rules")
    fun createRule(@RequestBody request: NewRule) = CreatedId(categories.createRule(request))

    @DeleteMapping("/rules/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    fun deleteRule(@PathVariable id: Long) = categories.deleteRule(id)

    @PutMapping("/transactions/{id}/category")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    fun categorize(@PathVariable id: Long, @RequestBody request: CategorizeRequest) =
        categories.categorizeTransaction(id, request.categoryId, request.rulePattern)

    @PutMapping("/transactions/{id}/splits")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    fun split(@PathVariable id: Long, @RequestBody request: SplitRequest) = categories.splitTransaction(id, request.parts)

    @PutMapping("/transactions/{id}/note")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    fun note(@PathVariable id: Long, @RequestBody request: NoteRequest) = categories.setNote(id, request.note)

    @PutMapping("/transactions/category")
    fun categorizeMany(@RequestBody request: BulkCategorizeRequest) =
        BulkCategorizeResult(categories.categorizeTransactions(request.transactionIds, request.categoryId))

    @GetMapping("/rules/suggestions")
    fun suggestions(@RequestParam(required = false, defaultValue = "15") limit: Int): List<RuleSuggestion> =
        categories.ruleSuggestions(limit)

    @GetMapping("/summary")
    fun month(@RequestParam(required = false) month: String?): MonthSummary = summary.month(parse(month))

    @GetMapping("/summary/history")
    fun history(
        @RequestParam(required = false) month: String?,
        @RequestParam(required = false, defaultValue = "12") months: Int,
    ): SummaryHistory = summary.history(parse(month), months.coerceIn(1, 36))

    private fun parse(month: String?): YearMonth = try {
        month?.let(YearMonth::parse) ?: YearMonth.now(clock)
    } catch (e: DateTimeParseException) {
        throw ApiException(HttpStatus.BAD_REQUEST, "INVALID_MONTH", "Mois attendu au format AAAA-MM.")
    }
}
