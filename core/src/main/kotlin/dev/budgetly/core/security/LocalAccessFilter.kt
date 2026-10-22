package dev.budgetly.core.security

import dev.budgetly.core.config.BudgetlyProperties
import jakarta.servlet.FilterChain
import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletResponse
import org.springframework.http.HttpHeaders
import org.springframework.http.ResponseCookie
import org.springframework.stereotype.Component
import org.springframework.web.filter.OncePerRequestFilter
import java.security.MessageDigest

/**
 * Protège le cœur même s'il n'écoute que sur 127.0.0.1 :
 * - Host autorisé uniquement 127.0.0.1 / localhost (bloque le DNS rebinding) ;
 * - si un jeton de lancement est défini, chaque requête doit porter le cookie de session
 *   (bloque les requêtes qu'un site web ouvert dans le navigateur enverrait vers localhost).
 */
@Component
class LocalAccessFilter(private val props: BudgetlyProperties) : OncePerRequestFilter() {

    override fun doFilterInternal(request: HttpServletRequest, response: HttpServletResponse, chain: FilterChain) {
        // serverName est dérivé de l'en-tête Host par Tomcat (et posé par MockMvc en test).
        if (!isLocalHost(request.serverName)) {
            response.status = HttpServletResponse.SC_FORBIDDEN
            return
        }

        val token = props.launchToken
        if (token.isBlank()) {
            chain.doFilter(request, response)
            return
        }

        val presented = request.getParameter(LAUNCH_PARAM)
        if (presented != null) {
            if (!matches(presented, token)) {
                response.status = HttpServletResponse.SC_UNAUTHORIZED
                return
            }
            val cookie = ResponseCookie.from(SESSION_COOKIE, token)
                .httpOnly(true)
                .sameSite("Strict")
                .path("/")
                .build()
            response.addHeader(HttpHeaders.SET_COOKIE, cookie.toString())
            response.status = HttpServletResponse.SC_FOUND
            response.setHeader(HttpHeaders.LOCATION, request.requestURI)
            return
        }

        val cookie = request.cookies?.firstOrNull { it.name == SESSION_COOKIE }?.value
        if (cookie == null || !matches(cookie, token)) {
            response.status = HttpServletResponse.SC_UNAUTHORIZED
            return
        }
        chain.doFilter(request, response)
    }

    private fun isLocalHost(serverName: String?): Boolean =
        serverName == "127.0.0.1" || serverName == "localhost"

    private fun matches(a: String, b: String) =
        MessageDigest.isEqual(a.toByteArray(), b.toByteArray())

    companion object {
        const val LAUNCH_PARAM = "launch"
        const val SESSION_COOKIE = "budgetly_session"
    }
}
