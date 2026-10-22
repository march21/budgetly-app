package dev.budgetly.core

import dev.budgetly.core.pdf.PdfBoxRuntimeHints
import org.springframework.boot.autoconfigure.SpringBootApplication
import org.springframework.boot.context.properties.ConfigurationPropertiesScan
import org.springframework.boot.runApplication
import org.springframework.context.annotation.ImportRuntimeHints
import org.springframework.scheduling.annotation.EnableScheduling

@SpringBootApplication
@ConfigurationPropertiesScan
@ImportRuntimeHints(PdfBoxRuntimeHints::class)
@EnableScheduling
class BudgetlyApplication

fun main(args: Array<String>) {
    runApplication<BudgetlyApplication>(*args)
}
