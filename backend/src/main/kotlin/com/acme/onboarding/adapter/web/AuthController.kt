package com.acme.onboarding.adapter.web

import com.acme.onboarding.application.auth.AuthenticationService
import com.acme.onboarding.application.auth.IssuedSession
import com.acme.onboarding.application.auth.PasswordResetService
import com.acme.onboarding.application.auth.SessionDescription
import com.acme.onboarding.config.AcmeProperties
import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.tags.Tag
import jakarta.servlet.http.HttpServletRequest
import jakarta.validation.Valid
import jakarta.validation.constraints.Email
import jakarta.validation.constraints.NotBlank
import org.springframework.http.HttpHeaders
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseCookie
import org.springframework.http.ResponseEntity
import org.springframework.stereotype.Component
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController
import java.time.Duration
import java.time.Instant

/**
 * Builds the session cookie.
 *
 * `HttpOnly` is the load-bearing attribute: an injected script cannot read the
 * credential, which is not true of the common pattern of keeping a token in
 * `localStorage`. `SameSite=Lax` blocks the cross-site form post; the CSRF token
 * (see SecurityConfig) covers what Lax does not.
 */
@Component
class SessionCookies(private val properties: AcmeProperties) {

    fun issue(token: String, expiresAt: Instant): ResponseCookie =
        base(token)
            .maxAge(Duration.between(Instant.now(), expiresAt).coerceAtLeast(Duration.ZERO))
            .build()

    fun clear(): ResponseCookie = base("").maxAge(Duration.ZERO).build()

    fun read(request: HttpServletRequest): String? =
        request.cookies
            ?.firstOrNull { it.name == properties.session.cookieName }
            ?.value
            ?.takeIf { it.isNotBlank() }

    private fun base(value: String) = ResponseCookie.from(properties.session.cookieName, value)
        .httpOnly(true)
        .secure(properties.session.cookieSecure)
        .sameSite("Lax")
        .path("/")
}

@RestController
@RequestMapping("/api/auth")
@Tag(name = "Authentication")
class AuthController(
    private val authentication: AuthenticationService,
    private val passwordReset: PasswordResetService,
    private val cookies: SessionCookies,
) {

    data class LoginRequest(
        @field:Email(message = "Enter a valid email address.")
        @field:NotBlank(message = "Enter your email address.")
        val email: String,
        @field:NotBlank(message = "Enter your password.")
        val password: String,
    )

    data class PasswordResetRequest(
        @field:Email(message = "Enter a valid email address.")
        @field:NotBlank(message = "Enter your email address.")
        val email: String,
    )

    data class NewPasswordRequest(
        @field:NotBlank(message = "Choose a password.")
        val password: String,
    )

    @PostMapping("/login")
    @Operation(summary = "Sign in and receive a session cookie")
    fun login(@Valid @RequestBody request: LoginRequest): ResponseEntity<SessionDescription> =
        withSessionCookie(authentication.login(request.email, request.password))

    @PostMapping("/logout")
    @Operation(summary = "Revoke the current session")
    fun logout(request: HttpServletRequest): ResponseEntity<Void> {
        val actor = CurrentActor.optional()
        cookies.read(request)?.let { authentication.logout(it, actor) }

        return ResponseEntity.noContent()
            .header(HttpHeaders.SET_COOKIE, cookies.clear().toString())
            .build()
    }

    @GetMapping("/session")
    @Operation(summary = "Describe the signed-in user")
    fun session(): SessionDescription = authentication.describe(CurrentActor.require())

    /**
     * Always accepted, whether or not the address is known. Anything else turns
     * this into a way to ask which companies Acme works with.
     */
    @PostMapping("/password-reset")
    @Operation(summary = "Request a password reset link")
    fun requestReset(@Valid @RequestBody request: PasswordResetRequest): ResponseEntity<Void> {
        passwordReset.request(request.email)
        return ResponseEntity.status(HttpStatus.ACCEPTED).build()
    }

    @PostMapping("/password-reset/{token}")
    @Operation(summary = "Set a new password with a reset token")
    fun completeReset(
        @PathVariable token: String,
        @Valid @RequestBody request: NewPasswordRequest,
    ): ResponseEntity<Void> {
        passwordReset.consume(token, request.password)
        // Consuming the token revoked every session, including this browser's.
        return ResponseEntity.noContent()
            .header(HttpHeaders.SET_COOKIE, cookies.clear().toString())
            .build()
    }

    private fun withSessionCookie(session: IssuedSession): ResponseEntity<SessionDescription> =
        ResponseEntity.ok()
            .header(HttpHeaders.SET_COOKIE, cookies.issue(session.token, session.expiresAt).toString())
            .body(authentication.describe(session.actor))
}
