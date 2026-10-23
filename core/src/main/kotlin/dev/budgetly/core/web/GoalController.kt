package dev.budgetly.core.web

import dev.budgetly.core.goals.GoalRequest
import dev.budgetly.core.goals.GoalService
import dev.budgetly.core.goals.GoalView
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

data class CreatedGoal(val id: Long)

@RestController
@RequestMapping("/api/goals")
class GoalController(private val goals: GoalService) {

    @GetMapping
    fun list(): List<GoalView> = goals.list()

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    fun create(@RequestBody request: GoalRequest) = CreatedGoal(goals.create(request))

    @PutMapping("/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    fun update(@PathVariable id: Long, @RequestBody request: GoalRequest) = goals.update(id, request)

    @DeleteMapping("/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    fun delete(@PathVariable id: Long) = goals.delete(id)
}
