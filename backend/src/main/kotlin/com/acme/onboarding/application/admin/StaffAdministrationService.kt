package com.acme.onboarding.application.admin

import com.acme.onboarding.adapter.persistence.ActivityEventRepository
import com.acme.onboarding.adapter.persistence.ActivityRow
import com.acme.onboarding.adapter.persistence.UserRepository
import com.acme.onboarding.application.audit.ActivityRecorder
import com.acme.onboarding.application.audit.AuditAction
import com.acme.onboarding.application.auth.AuthenticationService
import com.acme.onboarding.application.support.NotFoundException
import com.acme.onboarding.domain.user.Actor
import com.acme.onboarding.domain.user.AdminSafeguards
import com.acme.onboarding.domain.user.Role
import com.acme.onboarding.domain.user.UserStatus
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.Instant
import java.util.UUID

/** One row of the access report: who has access to what, and when they last used it. */
data class StaffUserView(
    val id: UUID,
    val email: String,
    val fullName: String,
    val role: Role,
    val status: UserStatus,
    val programIds: List<UUID>,
    val lastLoginAt: Instant?,
    val createdAt: Instant,
)

/**
 * Acme's own people. Deliberately not the same surface as supplier user
 * management: keeping them apart is what stops an ops user from granting
 * Acme-internal access while working a supplier's file.
 */
@Service
class StaffAdministrationService(
    private val users: UserRepository,
    private val events: ActivityEventRepository,
    private val authentication: AuthenticationService,
    private val recorder: ActivityRecorder,
) {

    @Transactional(readOnly = true)
    fun list(actor: Actor): List<StaffUserView> {
        actor.requireAdmin()
        return users.listStaff().map {
            StaffUserView(
                id = it.id,
                email = it.email,
                fullName = it.fullName,
                role = it.role,
                status = it.status,
                programIds = if (it.role == Role.PROGRAM_MANAGER) users.programScope(it.id) else emptyList(),
                lastLoginAt = it.lastLoginAt,
                createdAt = it.createdAt,
            )
        }
    }

    @Transactional
    fun changeRole(actor: Actor, userId: UUID, newRole: Role) {
        actor.requireAdmin()
        val user = users.findById(userId) ?: throw NotFoundException("That user no longer exists.")

        AdminSafeguards.checkRoleChange(
            actor = actor,
            targetUserId = userId,
            currentRole = user.role,
            newRole = newRole,
            activeAdmins = users.countActiveAdmins(),
        )

        users.updateRole(userId, newRole)
        if (newRole != Role.PROGRAM_MANAGER) users.replaceProgramScope(userId, emptyList())

        recorder.record(
            action = AuditAction.USER_ROLE_CHANGED,
            subjectType = "USER",
            subjectId = userId,
            actor = actor,
            before = mapOf("role" to user.role.name),
            after = mapOf("role" to newRole.name),
        )
    }

    @Transactional
    fun setProgramScope(actor: Actor, userId: UUID, programIds: List<UUID>) {
        actor.requireAdmin()
        val user = users.findById(userId) ?: throw NotFoundException("That user no longer exists.")
        val before = users.programScope(userId)

        users.replaceProgramScope(userId, programIds)
        recorder.record(
            action = AuditAction.USER_SCOPE_CHANGED,
            subjectType = "USER",
            subjectId = user.id,
            actor = actor,
            before = mapOf("programs" to before.map(UUID::toString)),
            after = mapOf("programs" to programIds.map(UUID::toString)),
        )
    }

    /**
     * Deactivation ends access now, not at the next token expiry — the sessions
     * are deleted in the same transaction as the status change.
     */
    @Transactional
    fun deactivate(actor: Actor, userId: UUID) {
        actor.requireAdmin()
        val user = users.findById(userId) ?: throw NotFoundException("That user no longer exists.")

        AdminSafeguards.checkDeactivation(
            actor = actor,
            targetUserId = userId,
            targetRole = user.role,
            activeAdmins = users.countActiveAdmins(),
        )

        users.updateStatus(userId, UserStatus.DEACTIVATED)
        val revoked = authentication.revokeAllSessions(userId)

        recorder.record(
            action = AuditAction.USER_DEACTIVATED,
            subjectType = "USER",
            subjectId = userId,
            actor = actor,
            supplierId = user.supplierId,
            before = mapOf("status" to user.status.name),
            after = mapOf("status" to UserStatus.DEACTIVATED.name, "sessionsRevoked" to revoked),
        )
    }

    @Transactional
    fun reactivate(actor: Actor, userId: UUID) {
        actor.requireAdmin()
        val user = users.findById(userId) ?: throw NotFoundException("That user no longer exists.")

        // Someone who never set a password goes back to INVITED, not ACTIVE, or
        // reactivation would leave an account nobody can sign in to and nothing
        // flags as incomplete.
        val restored = if (user.passwordHash == null) UserStatus.INVITED else UserStatus.ACTIVE
        users.updateStatus(userId, restored)

        recorder.record(
            action = AuditAction.USER_REACTIVATED,
            subjectType = "USER",
            subjectId = userId,
            actor = actor,
            supplierId = user.supplierId,
            before = mapOf("status" to user.status.name),
            after = mapOf("status" to restored.name),
        )
    }

    /**
     * Every access change for one user. Access changes are the first thing an
     * auditor samples, so this is a screen rather than a query someone writes.
     */
    @Transactional(readOnly = true)
    fun accessHistory(actor: Actor, userId: UUID): List<ActivityRow> {
        actor.requireAdmin()
        return events.timeline(ActivityRecorder.SYSTEM_CHAIN, limit = 500)
            .filter { it.subjectType == "USER" && it.subjectId == userId }
    }
}
