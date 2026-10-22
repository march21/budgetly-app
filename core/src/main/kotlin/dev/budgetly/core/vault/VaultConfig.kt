package dev.budgetly.core.vault

import org.springframework.boot.autoconfigure.flyway.FlywayMigrationStrategy
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.jdbc.datasource.AbstractDataSource
import java.sql.Connection
import javax.sql.DataSource

/** DataSource unique de l'application : délègue au pool du coffre, refuse tant qu'il est verrouillé. */
class VaultDataSource(private val vault: VaultService) : AbstractDataSource() {
    override fun getConnection(): Connection = vault.dataSource().connection

    override fun getConnection(username: String?, password: String?): Connection = getConnection()
}

@Configuration(proxyBeanMethods = false)
class VaultConfig {

    @Bean
    fun dataSource(vault: VaultService): DataSource = VaultDataSource(vault)

    /** Pas de migration au démarrage (base verrouillée) : le coffre migre à chaque déverrouillage. */
    @Bean
    fun flywayMigrationStrategy() = FlywayMigrationStrategy { }
}
