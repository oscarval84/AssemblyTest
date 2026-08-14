package com.acme.onboarding.adapter.web

import com.acme.onboarding.application.compliance.ComplianceSweepService
import com.acme.onboarding.application.compliance.SweepResult
import com.acme.onboarding.application.notification.DrainResult
import com.acme.onboarding.application.notification.OutboxDrainService
import com.acme.onboarding.application.support.AuthenticationException
import com.acme.onboarding.config.AcmeProperties
import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.tags.Tag
import jakarta.servlet.http.HttpServletRequest
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController
import java.security.MessageDigest

/**
 * The scheduled work, as endpoints rather than in-process timers.
 *
 * Cloud Run scales to zero, so an `@Scheduled` job would run only when something
 * else happened to wake the container — which for a compliance sweep means "not
 * on the day it mattered". Cloud Scheduler calls these instead, and Cloud Run
 * verifies its OIDC token before the request arrives. The shared secret checked
 * here is the second layer and the local equivalent.
 */
@RestController
@RequestMapping("/internal/jobs")
@Tag(name = "Scheduled jobs")
class InternalJobsController(
    private val sweep: ComplianceSweepService,
    private val drain: OutboxDrainService,
    private val properties: AcmeProperties,
) {

    @PostMapping("/compliance-sweep")
    @Operation(summary = "Notify about expiring and expired documents, and record the transitions")
    fun complianceSweep(request: HttpServletRequest): SweepResult {
        authorize(request)
        return sweep.sweep()
    }

    @PostMapping("/outbox-drain")
    @Operation(summary = "Deliver queued notifications through the configured transport")
    fun outboxDrain(request: HttpServletRequest): DrainResult {
        authorize(request)
        return drain.drain()
    }

    /**
     * Constant-time comparison, because a timing-sensitive equality check on a
     * shared secret is recoverable byte by byte by anyone who can call the
     * endpoint — and these endpoints are reachable without a session by design.
     */
    private fun authorize(request: HttpServletRequest) {
        val presented = request.getHeader(TOKEN_HEADER).orEmpty()
        val expected = properties.jobs.token

        val matches = MessageDigest.isEqual(
            presented.toByteArray(Charsets.UTF_8),
            expected.toByteArray(Charsets.UTF_8),
        )
        if (!matches) throw AuthenticationException("This endpoint is for scheduled jobs.")
    }

    private companion object {
        const val TOKEN_HEADER = "X-Job-Token"
    }
}
