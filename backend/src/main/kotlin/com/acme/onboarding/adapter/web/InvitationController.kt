package com.acme.onboarding.adapter.web

import com.acme.onboarding.application.auth.AuthenticationService
import com.acme.onboarding.application.auth.InvitationPreview
import com.acme.onboarding.application.auth.InvitationService
import com.acme.onboarding.application.auth.SessionDescription
import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.tags.Tag
import jakarta.validation.Valid
import jakarta.validation.constraints.NotBlank
import org.springframework.http.HttpHeaders
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController

/**
 * Both endpoints are reachable without a session — the token in the link is the
 * credential. The preview exists so a dead link explains itself instead of
 * showing a form that fails on submit.
 */
@RestController
@RequestMapping("/api/invitations")
@Tag(name = "Invitations")
class InvitationController(
    private val invitations: InvitationService,
    private val authentication: AuthenticationService,
    private val cookies: SessionCookies,
) {

    data class AcceptRequest(
        @field:NotBlank(message = "Choose a password.")
        val password: String,
    )

    @GetMapping("/{token}")
    @Operation(summary = "Describe an invitation without consuming it")
    fun preview(@PathVariable token: String): InvitationPreview = invitations.preview(token)

    @PostMapping("/{token}/accept")
    @Operation(summary = "Set a password, consume the invitation and sign in")
    fun accept(
        @PathVariable token: String,
        @Valid @RequestBody request: AcceptRequest,
    ): ResponseEntity<SessionDescription> {
        val session = invitations.accept(token, request.password)
        return ResponseEntity.ok()
            .header(HttpHeaders.SET_COOKIE, cookies.issue(session.token, session.expiresAt).toString())
            .body(authentication.describe(session.actor))
    }
}
