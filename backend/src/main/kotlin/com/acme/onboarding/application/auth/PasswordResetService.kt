package com.acme.onboarding.application.auth

import com.acme.onboarding.adapter.persistence.PasswordResetRepository
import com.acme.onboarding.adapter.persistence.UserRepository
import com.acme.onboarding.application.audit.ActivityRecorder
import com.acme.onboarding.application.audit.AuditAction
import com.acme.onboarding.application.notification.Notifier
import com.acme.onboarding.application.support.InvalidRequestException
import com.acme.onboarding.application.support.NotFoundException
import com.acme.onboarding.application.support.Tokens
import com.acme.onboarding.application.support.hash
import com.acme.onboarding.config.AcmeProperties
import com.acme.onboarding.domain.user.Actor
import com.acme.onboarding.domain.user.PasswordPolicy
import com.acme.onboarding.domain.user.UserStatus
import org.springframework.security.crypto.password.PasswordEncoder
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.Clock
import java.time.Instant
import java.util.UUID

@Service
class PasswordResetService(
    private val users: UserRepository,
    private val resets: PasswordResetRepository,
    private val passwordEncoder: PasswordEncoder,
    private val notifier: Notifier,
    private val authentication: AuthenticationService,
    private val recorder: ActivityRecorder,
    private val properties: AcmeProperties,
    private val clock: Clock,
) {

    /**
     * Self-service, from the sign-in screen.
     *
     * Returns the same way for a known and an unknown address, and the endpoint
     * says so too. Anything else turns the reset form into a way to ask "does
     * this person work with Acme?" — a question a competitor would like answered
     * and one this system has no reason to answer.
     */
    @Transactional
    fun request(email: String) {
        val user = users.findByEmail(email) ?: return
        if (user.status != UserStatus.ACTIVE) return

        val token = issue(user.id, issuedBy = null)
        notifier.passwordReset(
            recipientEmail = user.email,
            recipientName = user.fullName,
            token = token,
            issuedByStaff = false,
        )
        recorder.record(
            action = AuditAction.PASSWORD_RESET_REQUESTED,
            subjectType = "USER",
            subjectId = user.id,
            actor = null,
            systemActorLabel = "self-service (${user.email})",
        )
    }

    /**
     * The phone call that starts with "I can't get in".
     *
     * Ops issues the same token the user would have requested; nobody at Acme
     * ever sees or sets the password itself.
     */
    @Transactional
    fun issueOnBehalf(actor: Actor, userId: UUID) {
        actor.requireOps()
        val user = users.findById(userId) ?: throw NotFoundException("That user no longer exists.")
        if (user.status == UserStatus.DEACTIVATED) {
            throw InvalidRequestException(
                "This account is deactivated. Reactivate it first, then send the reset.",
            )
        }

        val token = issue(user.id, issuedBy = actor.userId)
        notifier.passwordReset(
            recipientEmail = user.email,
            recipientName = user.fullName,
            token = token,
            issuedByStaff = true,
        )
        recorder.record(
            action = AuditAction.PASSWORD_RESET_REQUESTED,
            subjectType = "USER",
            subjectId = user.id,
            actor = actor,
            supplierId = user.supplierId,
        )
    }

    /**
     * Consuming a token sets the password and signs the user out everywhere.
     *
     * That second part is the reason the reset exists at all: someone resetting
     * because they suspect their account was reached needs the other sessions
     * gone, not merely a new password added alongside them.
     */
    @Transactional
    fun consume(rawToken: String, newPassword: String) {
        val token = resets.findByTokenHash(Tokens.hash(rawToken))
            ?: throw NotFoundException("That reset link is not valid. Request a new one from the sign-in page.")

        if (token.consumedAt != null) {
            throw InvalidRequestException("That reset link has already been used. Request a new one.")
        }
        if (token.expiresAt.isBefore(Instant.now(clock))) {
            throw InvalidRequestException("That reset link has expired. Request a new one.")
        }

        when (val verdict = PasswordPolicy.check(newPassword)) {
            is PasswordPolicy.Result.Rejected -> throw InvalidRequestException(verdict.message)
            PasswordPolicy.Result.Accepted -> Unit
        }

        val user = users.findById(token.userId)
            ?: throw NotFoundException("That account no longer exists.")

        users.setPassword(user.id, passwordEncoder.hash(newPassword))
        resets.markConsumed(token.id, Instant.now(clock))
        authentication.revokeAllSessions(user.id)

        recorder.record(
            action = AuditAction.PASSWORD_RESET_COMPLETED,
            subjectType = "USER",
            subjectId = user.id,
            actor = user.toActor(),
            supplierId = user.supplierId,
        )
    }

    private fun issue(userId: UUID, issuedBy: UUID?): String {
        val token = Tokens.generate()
        resets.create(
            tokenHash = Tokens.hash(token),
            userId = userId,
            issuedBy = issuedBy,
            expiresAt = Instant.now(clock).plus(properties.passwordReset.ttl),
        )
        return token
    }
}
