package dev.budgetly.core.web

import dev.budgetly.core.vault.AutoLock
import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletResponse
import org.springframework.context.annotation.Configuration
import org.springframework.web.servlet.HandlerInterceptor
import org.springframework.web.servlet.config.annotation.InterceptorRegistry
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer

/** Toute requête de données compte comme activité ; l'état du coffre et la santé, interrogés en tâche de fond, non. */
@Configuration(proxyBeanMethods = false)
class ActivityConfig(private val autoLock: AutoLock) : WebMvcConfigurer {
    override fun addInterceptors(registry: InterceptorRegistry) {
        registry.addInterceptor(object : HandlerInterceptor {
            override fun preHandle(request: HttpServletRequest, response: HttpServletResponse, handler: Any): Boolean {
                autoLock.touch()
                return true
            }
        }).addPathPatterns("/api/**").excludePathPatterns("/api/vault", "/api/vault/**", "/api/health")
    }
}
