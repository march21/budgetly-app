package dev.budgetly.core.config

import dev.budgetly.core.statement.StatementParser
import dev.budgetly.core.statement.easybourse.EasyBoursePeaParser
import dev.budgetly.core.statement.easybourse.EasyVieParser
import dev.budgetly.core.statement.natixis.NatixisPeeParser
import dev.budgetly.core.statement.lbp.LbpAccountStatementParser
import dev.budgetly.core.statement.portfolio.PortfolioParser
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import java.time.Clock

@Configuration(proxyBeanMethods = false)
class AppConfig(props: BudgetlyProperties) {

    init {
        // PDFBox garde la liste des polices du système dans un cache (.pdfbox.cache) : dans le dossier de données
        // plutôt que dans le dossier personnel, pour que rien ne soit écrit ailleurs.
        if (System.getProperty("pdfbox.fontcache") == null) System.setProperty("pdfbox.fontcache", props.dataDir.toAbsolutePath().toString())
    }

    @Bean
    fun clock(): Clock = Clock.systemUTC()

    /** Parsers disponibles : un par établissement × type de document. */
    @Bean
    fun lbpAccountStatementParser(): StatementParser = LbpAccountStatementParser()

    @Bean
    fun easyBoursePeaParser(): PortfolioParser = EasyBoursePeaParser()

    @Bean
    fun easyVieParser(): PortfolioParser = EasyVieParser()

    @Bean
    fun natixisPeeParser(): PortfolioParser = NatixisPeeParser()
}
