package com.acme.onboarding.config

import jakarta.servlet.FilterChain
import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletResponse
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.http.HttpStatus
import org.springframework.http.MediaType
import org.springframework.security.config.annotation.web.builders.HttpSecurity
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity
import org.springframework.security.config.http.SessionCreationPolicy
import org.springframework.security.web.SecurityFilterChain

import org.springframework.security.web.csrf.CsrfFilter
import org.springframework.security.web.csrf.CsrfToken
import org.springframework.security.web.csrf.CsrfTokenRequestAttributeHandler
import org.springframework.web.filter.OncePerRequestFilter

/**
 * The HTTP security posture.
 *
 * Two decisions carry most of it, and they are linked:
 *
 *  - **Sessions are ours, not the servlet container's.** Spring's own session
 *    handling is switched off entirely; [SessionAuthenticationFilter] resolves
 *    the caller from a row in Postgres. That is what makes revocation immediate.
 *
 *  - **CSRF protection is mandatory because the credential is a cookie.**
 *    Browsers attach cookies to cross-origin requests automatically, so without
 *    it any page on the internet could make an authenticated call on a signed-in
 *    ops user's behalf. `SameSite=Lax` on the session cookie is the second
 *    layer, not the only one.
 */
@Configuration
@EnableWebSecurity
class SecurityConfig {

    @Bean
    fun securityFilterChain(
        http: HttpSecurity,
        sessionAuthenticationFilter: SessionAuthenticationFilter,
    ): SecurityFilterChain {
        http
            .csrf { csrf ->
                csrf
                    // Readable by the SPA, which echoes it in a header. The
                    // session cookie stays HttpOnly; this one is not a credential.
                    .csrfTokenRepository(StableCookieCsrfTokenRepository())
                    // The plain handler keeps the cookie value and the expected
                    // header value identical. The XOR-masking default protects
                    // against BREACH but requires the client to unmask, which is
                    // a subtlety worth avoiding for the protection it buys here.
                    .csrfTokenRequestHandler(CsrfTokenRequestAttributeHandler())
                    // A scheduler has no cookie jar, so there is no cross-site
                    // request to forge; the shared secret is the control there.
                    .ignoringRequestMatchers("/internal/jobs/**")
            }
            .addFilterAfter(CsrfCookieFilter(), CsrfFilter::class.java)
            .sessionManagement { it.sessionCreationPolicy(SessionCreationPolicy.STATELESS) }
            .addFilterBefore(sessionAuthenticationFilter, CsrfFilter::class.java)
            .authorizeHttpRequests { authorize ->
                authorize
                    .requestMatchers(
                        "/api/auth/login",
                        "/api/auth/password-reset",
                        "/api/auth/password-reset/*",
                        "/api/invitations/*",
                        "/api/invitations/*/accept",
                        // Demo sign-ins, so an evaluator is never stuck at the
                        // door. The endpoint answers 404 outside demo mode.
                        "/api/demo/accounts",
                    ).permitAll()
                    .requestMatchers("/api/openapi.json", "/api/docs/**", "/swagger-ui/**").permitAll()
                    // Scheduled jobs carry a shared secret the controller checks
                    // rather than a session; see InternalJobsController.
                    .requestMatchers("/internal/jobs/**").permitAll()
                    .requestMatchers("/actuator/health", "/actuator/health/**", "/actuator/info").permitAll()
                    .anyRequest().authenticated()
            }
            .exceptionHandling { handling ->
                handling
                    .authenticationEntryPoint { _, response, _ ->
                        response.writeProblem(HttpStatus.UNAUTHORIZED, "Sign in to continue.")
                    }
                    .accessDeniedHandler { _, response, _ ->
                        response.writeProblem(
                            HttpStatus.FORBIDDEN,
                            "You do not have access to that.",
                        )
                    }
            }
            .httpBasic { it.disable() }
            .formLogin { it.disable() }
            .logout { it.disable() }
            .requestCache { it.disable() }

        return http.build()
    }

    /**
     * Forces the deferred CSRF token to materialise so the cookie is actually
     * written. Without this the token is created lazily — meaning a client that
     * has never made a mutating request never receives one, and its first
     * mutating request fails.
     */
    private class CsrfCookieFilter : OncePerRequestFilter() {
        override fun doFilterInternal(
            request: HttpServletRequest,
            response: HttpServletResponse,
            filterChain: FilterChain,
        ) {
            (request.getAttribute(CsrfToken::class.java.name) as? CsrfToken)?.token
            filterChain.doFilter(request, response)
        }
    }
}

/**
 * Errors from the security layer are shaped like every other error the API
 * returns, so the SPA has one failure path rather than two.
 */
private fun HttpServletResponse.writeProblem(status: HttpStatus, message: String) {
    this.status = status.value()
    contentType = MediaType.APPLICATION_JSON_VALUE
    characterEncoding = Charsets.UTF_8.name()
    writer.write("""{"message":${quote(message)},"status":${status.value()}}""")
}

private fun quote(value: String): String =
    "\"" + value.replace("\\", "\\\\").replace("\"", "\\\"") + "\""
