package com.acme.onboarding.application.auth

import com.acme.onboarding.adapter.persistence.InvitationRepository
import com.acme.onboarding.adapter.persistence.SupplierRepository
import com.acme.onboarding.adapter.persistence.UserRepository
import com.acme.onboarding.application.audit.ActivityRecorder
import com.acme.onboarding.application.audit.AuditAction
import com.acme.onboarding.application.notification.Notifier
import com.acme.onboarding.application.support.ConflictException
import com.acme.onboarding.application.support.InvalidRequestException
import com.acme.onboarding.application.support.NotFoundException
import com.acme.onboarding.application.support.Tokens
import com.acme.onboarding.application.support.hash
import com.acme.onboarding.config.AcmeProperties
import com.acme.onboarding.domain.onboarding.OnboardingStage
import com.acme.onboarding.domain.onboarding.OnboardingTransitions
import com.acme.onboarding.domain.user.Actor
import com.acme.onboarding.domain.user.PasswordPolicy
import com.acme.onboarding.domain.user.Role
import com.acme.onboarding.domain.user.UserStatus
import org.springframework.security.crypto.password.PasswordEncoder
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.Clock
import java.time.Instant
import java.util.UUID

/** What the invitation-acceptance screen shows before anyone types a password. */
data class InvitationPreview(
    val email: String,
    val role: Role,
    val fullName: String,
    /** The supplier's legal name for an external invitation, "Acme Inc." for staff. */
    val organizationName: String,
    val usable: Boolean,
    /** Why it is not usable, written for the person holding the dead link. */
    val unusableReason: String?,
)

/**
 * Invitations are the only way an account comes into existence.
 *
 * The user row is created up front in `INVITED` status rather than at
 * acceptance, so an admin who invites someone sees them in the access report
 * immediately — an account that exists but has never been used is exactly the
 * thing a periodic access review is supposed to surface.
 */
@Service
class InvitationService(
    private val users: UserRepository,
    private val invitations: InvitationRepository,
    private val suppliers: SupplierRepository,
    private val passwordEncoder: PasswordEncoder,
    private val notifier: Notifier,
    private val recorder: ActivityRecorder,
    private val authentication: AuthenticationService,
    private val properties: AcmeProperties,
    private val clock: Clock,
) {

    @Transactional
    fun inviteStaff(
        actor: Actor,
        email: String,
        fullName: String,
        role: Role,
        programIds: List<UUID> = emptyList(),
    ): UUID {
        actor.requireAdmin()
        if (role == Role.SUPPLIER_USER) {
            throw InvalidRequestException(
                "Supplier users are invited from the supplier's own record, not from staff administration.",
            )
        }

        val userId = createInvitedUser(email, fullName, role, supplierId = null)
        if (role == Role.PROGRAM_MANAGER && programIds.isNotEmpty()) {
            users.replaceProgramScope(userId, programIds)
        }

        val token = issueToken(email, role, supplierId = null, invitedBy = actor.userId)
        notifier.staffInvited(
            recipientEmail = email,
            recipientName = fullName,
            roleLabel = roleLabel(role),
            invitedByName = actor.fullName,
            token = token,
        )
        recorder.record(
            action = AuditAction.USER_INVITED,
            subjectType = "USER",
            subjectId = userId,
            actor = actor,
            after = mapOf("email" to email, "role" to role.name, "programs" to programIds.map(UUID::toString)),
        )
        return userId
    }

    /**
     * Invites someone at a supplier company. Ops-operated and scoped to that one
     * supplier — deliberately a different entry point from staff administration,
     * so nobody grants Acme-internal access while working a supplier's file.
     */
    @Transactional
    fun inviteSupplierUser(
        actor: Actor,
        supplierId: UUID,
        email: String,
        fullName: String,
    ): UUID {
        actor.requireOps()
        val supplier = suppliers.findById(supplierId)
            ?: throw NotFoundException("That supplier no longer exists.")

        val userId = createInvitedUser(email, fullName, Role.SUPPLIER_USER, supplierId)
        val token = issueToken(email, Role.SUPPLIER_USER, supplierId, invitedBy = actor.userId)

        notifier.supplierInvited(
            recipientEmail = email,
            recipientName = fullName,
            supplierId = supplierId,
            companyName = supplier.legalName,
            invitedByName = actor.fullName,
            token = token,
        )
        recorder.record(
            action = AuditAction.USER_INVITED,
            subjectType = "USER",
            subjectId = userId,
            actor = actor,
            supplierId = supplierId,
            after = mapOf("email" to email, "role" to Role.SUPPLIER_USER.name),
        )
        return userId
    }

    /**
     * Reads an invitation without consuming it, so the acceptance screen can say
     * who it is for and which company — and can explain a dead link instead of
     * showing an empty form that will fail on submit.
     */
    @Transactional(readOnly = true)
    fun preview(rawToken: String): InvitationPreview {
        val invitation = invitations.findByTokenHash(Tokens.hash(rawToken))
            ?: throw NotFoundException(
                "That invitation link is not valid. Ask your Acme contact to send a new one.",
            )

        val user = users.findByEmail(invitation.email)
        val organization = invitation.supplierId
            ?.let { suppliers.findById(it)?.legalName }
            ?: "Acme Inc."

        val reason = when {
            invitation.acceptedAt != null ->
                "This invitation has already been used. Sign in with your email and password instead."

            invitation.expiresAt.isBefore(Instant.now(clock)) ->
                "This invitation expired on ${invitation.expiresAt}. Ask your Acme contact for a new one."

            user == null -> "This invitation is no longer attached to an account."

            else -> null
        }

        return InvitationPreview(
            email = invitation.email,
            role = invitation.role,
            fullName = user?.fullName ?: "",
            organizationName = organization,
            usable = reason == null,
            unusableReason = reason,
        )
    }

    /**
     * Consumes the invitation, sets the password, and signs the user in.
     *
     * For a supplier's first user this is also the `INVITED → REGISTERED`
     * transition — the moment onboarding stops being something Acme did *to* the
     * supplier and becomes something they are doing.
     */
    @Transactional
    fun accept(rawToken: String, password: String): IssuedSession {
        val invitation = invitations.findByTokenHash(Tokens.hash(rawToken))
            ?: throw NotFoundException(
                "That invitation link is not valid. Ask your Acme contact to send a new one.",
            )

        if (invitation.acceptedAt != null) {
            throw InvalidRequestException(
                "This invitation has already been used. Sign in with your email and password instead.",
            )
        }
        if (invitation.expiresAt.isBefore(Instant.now(clock))) {
            throw InvalidRequestException(
                "This invitation has expired. Ask your Acme contact to send a new one.",
            )
        }

        when (val verdict = PasswordPolicy.check(password)) {
            is PasswordPolicy.Result.Rejected -> throw InvalidRequestException(verdict.message)
            PasswordPolicy.Result.Accepted -> Unit
        }

        val user = users.findByEmail(invitation.email)
            ?: throw NotFoundException("This invitation is no longer attached to an account.")

        users.setPassword(user.id, passwordEncoder.hash(password))
        invitations.markAccepted(invitation.id, Instant.now(clock))

        val actor = user.toActor()
        recorder.record(
            action = AuditAction.INVITATION_ACCEPTED,
            subjectType = "USER",
            subjectId = user.id,
            actor = actor,
            supplierId = user.supplierId,
        )

        user.supplierId?.let { advanceToRegistered(it, actor) }

        return authentication.issueSession(actor)
    }

    private fun advanceToRegistered(supplierId: UUID, actor: Actor) {
        val supplier = suppliers.findById(supplierId) ?: return
        // A second user at the same company accepting later must not drag the
        // supplier's stage backwards, so this only fires from INVITED.
        if (supplier.stage != OnboardingStage.INVITED) return

        OnboardingTransitions.require(supplier.stage, OnboardingStage.REGISTERED)
        suppliers.updateStage(supplierId, OnboardingStage.REGISTERED)
        recorder.record(
            action = AuditAction.SUPPLIER_STAGE_CHANGED,
            subjectType = "SUPPLIER",
            subjectId = supplierId,
            actor = actor,
            supplierId = supplierId,
            before = mapOf("stage" to supplier.stage.name),
            after = mapOf("stage" to OnboardingStage.REGISTERED.name),
        )
    }

    private fun createInvitedUser(email: String, fullName: String, role: Role, supplierId: UUID?): UUID {
        val existing = users.findByEmail(email)
        if (existing != null) {
            throw ConflictException(
                "${existing.email} already has an account. Use the access report to change their " +
                    "role or send them a password reset.",
            )
        }
        if (fullName.isBlank()) {
            throw InvalidRequestException("A name is required so the access report is readable.")
        }
        return users.insert(
            email = email,
            fullName = fullName,
            role = role,
            supplierId = supplierId,
            status = UserStatus.INVITED,
            passwordHash = null,
        )
    }

    private fun issueToken(email: String, role: Role, supplierId: UUID?, invitedBy: UUID?): String {
        val token = Tokens.generate()
        invitations.create(
            tokenHash = Tokens.hash(token),
            email = email,
            role = role,
            supplierId = supplierId,
            invitedBy = invitedBy,
            expiresAt = Instant.now(clock).plus(properties.invitation.ttl),
        )
        return token
    }

    private fun roleLabel(role: Role): String = when (role) {
        Role.ADMIN -> "administrator"
        Role.OPS -> "supplier operations"
        Role.PROGRAM_MANAGER -> "program manager (read-only)"
        Role.SUPPLIER_USER -> "supplier"
    }
}
