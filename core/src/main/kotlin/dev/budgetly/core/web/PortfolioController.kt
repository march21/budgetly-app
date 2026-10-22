package dev.budgetly.core.web

import dev.budgetly.core.portfolio.ManualValuation
import dev.budgetly.core.portfolio.NetWorth
import dev.budgetly.core.portfolio.PortfolioService
import dev.budgetly.core.portfolio.PriceReport
import dev.budgetly.core.portfolio.PriceService
import org.springframework.http.HttpStatus
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.ResponseStatus
import org.springframework.web.bind.annotation.RestController

@RestController
@RequestMapping("/api/portfolio")
class PortfolioController(private val portfolio: PortfolioService, private val prices: PriceService) {

    @GetMapping
    fun netWorth(): NetWorth = portfolio.netWorth()

    /** Seule sortie réseau de l'application : cours des supports détenus, demandés par ISIN. */
    @PostMapping("/prices/refresh")
    fun refreshPrices(): PriceReport = prices.refresh()

    @PostMapping("/{accountId}/valuations")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    fun addValuation(@PathVariable accountId: Long, @RequestBody valuation: ManualValuation) =
        portfolio.addManualValuation(accountId, valuation)
}
