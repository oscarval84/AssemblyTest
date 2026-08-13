package com.acme.onboarding.application.auth

import com.acme.onboarding.adapter.persistence.SessionRepository
import com.acme.onboarding.adapter.persistence.SupplierRepository
import com.acme.onboarding.adapter.persistence.UserRepository
import com.acme.onboarding.application.audit.ActivityRecorder
import com.acme.onboarding.application.audit.AuditAction
import com.acme.onboarding.application.audit.RequestContext
import com.acme.onboarding.application.support.AuthenticationException
import com.acme.onboarding.application.support.Tokens
import com.acme.onboarding.application.support.hash
import com.acme.onboarding.config.AcmeProperties
import com.acme.onboarding.domain.user.Actor
import com.acme.onboarding.domain.user.Role
import com.acme.onboarding.domain.user.UserStatus
import org.springframework.security.crypto.password.PasswordEncoder
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.util.UUID

/** A signed-in session: who it belongs to, the token to hand back, and its ceiling. */
data class IssuedSession(val actor: Actor, val token: String, val expiresAt: Instant)

/**
 * What the app needs about the signed-in user on every page load: their identity,
 * what they may do, and — for a supplier user — which company they are acting for.
 */
data class SessionDescription(
    val userId: UUID,
    val email: String,
    val fullName: String,
    val role: Role,
    val supplierId: UUID?,
    val supplierName: String?,
    val programIds: List<UUID>,
)

@Service
class AuthenticationService(
    private val users: UserRepository,
    private val sessions: SessionRepository,
    private val suppliers: SupplierRepository,
    private val passwordEncoder: PasswordEncoder,
    private val properties: AcmeProperties,
    private val recorder: ActivityRecorder,
    private val requestContext: RequestContext,
    private val clock: Clock,
) {

    /**
     * A real hash, verified when the email is unknown.
     *
     * Without it, "no such user" returns in microseconds while a wrong password
     * costs a full BCrypt verification — a timing difference large enough to
     * enumerate which suppliers Acme works with, from the outside, unauthenticated.
     */
    private val timingEqualizer: String = passwordEncoder.hash(Tokens.generate())

    @Transactional
    fun login(email: String, password: String): IssuedSession {
        val user = users.findByEmail(email)
        val storedHash = user?.passwordHash

        val credentialsMatch = if (storedHash == null) {
            passwordEncoder.matches(password, timingEqualizer)
            false
        } else {
            passwordEncoder.matches(password, storedHash)
        }

        if (user == null || !credentialsMatch) {
            throw AuthenticationException("That email and password combination is not recognised.")
        }

        // Only reached with correct credentials, so naming the real reason here
        // tells the account holder something useful without leaking anything to
        // someone who does not already have their password.
        when (user.status) {
            UserStatus.DEACTIVATED -> throw AuthenticationException(
                "This account has been deactivated. Contact your Acme supplier operations " +
                    "contact if you think that is a mistake.",
            )

            UserStatus.INVITED -> throw AuthenticationException(
                "This account has not been set up yet. Use the invitation link we emailed you, " +
                    "or ask for a new one.",
            )

            UserStatus.ACTIVE -> Unit
        }

        return issueSession(user.toActor())
    }

    /**
     * Starts a session for an already-proven identity.
     *
     * Accepting an invitation lands here: the single-use token in the email was
     * itself proof of control of the address, so making someone type the
     * password they just chose adds a step and no security.
     */
    @Transactional
    fun issueSession(actor: Actor): IssuedSession {
        val now = Instant.now(clock)
        val token = Tokens.generate()
        val expiresAt = now.plus(properties.session.ttl)

        sessions.create(
            userId = actor.userId,
            tokenHash = Tokens.hash(token),
            expiresAt = expiresAt,
            ip = requestContext.ip(),
            userAgent = requestContext.userAgent(),
        )
        users.recordLogin(actor.userId, now)

        recorder.record(
            action = AuditAction.USER_SIGNED_IN,
            subjectType = "USER",
            subjectId = actor.userId,
            actor = actor,
        )
        return IssuedSession(actor, token, expiresAt)
    }

    /**
     * Resolves the caller for one request, or null if the token is unusable.
     *
     * The status check is re-read here rather than trusted from sign-in time,
     * which is the entire reason sessions are server-side: an admin who
     * deactivates someone at 14:00 expects them locked out at 14:00, not
     * whenever a token would have expired on its own.
     */
    @Transactional
    fun resolve(rawToken: String): Actor? {
        val now = Instant.now(clock)
        val session = sessions.findActive(Tokens.hash(rawToken), now) ?: return null
        val user = users.findById(session.userId) ?: return null
        if (user.status != UserStatus.ACTIVE) return null

        // One write per request would double this endpoint's cost for a field
        // nothing reads in real time.
        if (Duration.between(session.lastSeenAt, now) > TOUCH_INTERVAL) {
            sessions.touch(session.id, now)
        }
        return user.toActor()
    }

    @Transactional
    fun logout(rawToken: String, actor: Actor?) {
        sessions.revoke(Tokens.hash(rawToken))
        if (actor != null) {
            recorder.record(
                action = AuditAction.USER_SIGNED_OUT,
                subjectType = "USER",
                subjectId = actor.userId,
                actor = actor,
            )
        }
    }

    /** Used by deactivation and by every password change. */
    @Transactional
    fun revokeAllSessions(userId: UUID): Int = sessions.revokeAllForUser(userId)

    @Transactional(readOnly = true)
    fun describe(actor: Actor): SessionDescription = SessionDescription(
        userId = actor.userId,
        email = actor.email,
        fullName = actor.fullName,
        role = actor.role,
        supplierId = actor.supplierId,
        supplierName = actor.supplierId?.let { suppliers.findById(it)?.legalName },
        programIds = users.programScope(actor.userId),
    )

    private companion object {
        val TOUCH_INTERVAL: Duration = Duration.ofMinutes(5)
    }
}
