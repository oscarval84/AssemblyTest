package com.acme.onboarding.config

import com.acme.onboarding.application.auth.AuthenticationService
import jakarta.servlet.FilterChain
import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletResponse
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken
import org.springframework.security.core.authority.SimpleGrantedAuthority
import org.springframework.security.core.context.SecurityContextHolder
import org.springframework.stereotype.Component
import org.springframework.web.filter.OncePerRequestFilter

/**
 * Turns the opaque session cookie into an authenticated caller, once per request.
 *
 * The lookup is a real database read rather than a signature check, and that is
 * the deliberate trade behind server-side sessions: one indexed query per
 * request buys instant revocation. An admin who removes someone's access at
 * 14:00 has removed it at 14:00 — not whenever a self-contained token would have
 * expired on its own.
 */
@Component
class SessionAuthenticationFilter(
    private val authentication: AuthenticationService,
    private val properties: AcmeProperties,
) : OncePerRequestFilter() {

    override fun doFilterInternal(
        request: HttpServletRequest,
        response: HttpServletResponse,
        filterChain: FilterChain,
    ) {
        if (SecurityContextHolder.getContext().authentication == null) {
            sessionToken(request)?.let { token ->
                authentication.resolve(token)?.let { actor ->
                    val authenticated = UsernamePasswordAuthenticationToken(
                        actor,
                        null,
                        listOf(SimpleGrantedAuthority("ROLE_${actor.role.name}")),
                    )
                    val context = SecurityContextHolder.createEmptyContext()
                    context.authentication = authenticated
                    SecurityContextHolder.setContext(context)
                }
            }
        }

        filterChain.doFilter(request, response)
    }

    private fun sessionToken(request: HttpServletRequest): String? =
        request.cookies
            ?.firstOrNull { it.name == properties.session.cookieName }
            ?.value
            ?.takeIf { it.isNotBlank() }
}
