package com.acme.onboarding.domain.user

import java.util.UUID

enum class Role {
    ADMIN,
    OPS,

    /** Read-only, and only for the programs assigned to them. */
    PROGRAM_MANAGER,

    /** External. Can only ever resolve their own supplier. */
    SUPPLIER_USER,
    ;

    val isStaff: Boolean get() = this != SUPPLIER_USER
}

enum class UserStatus { INVITED, ACTIVE, DEACTIVATED }

/**
 * The authenticated caller, as every use case sees them.
 *
 * Authorization is enforced here and in the application layer rather than in
 * controllers, because the rules are about *which records* a caller may resolve
 * — a supplier user can only ever reach their own supplier — and that question
 * cannot be answered by a URL pattern.
 */
data class Actor(
    val userId: UUID,
    val email: String,
    val fullName: String,
    val role: Role,
    /** Set only for [Role.SUPPLIER_USER]; the binding that scopes them. */
    val supplierId: UUID?,
) {
    val label: String get() = "$fullName <$email>"

    fun requireAdmin() {
        if (role != Role.ADMIN) throw AccessDeniedException("This action is restricted to administrators.")
    }

    /** Ops and admins. A program manager is read-only and never passes this. */
    fun requireOps() {
        if (role != Role.ADMIN && role != Role.OPS) {
            throw AccessDeniedException("This action is restricted to supplier operations.")
        }
    }

    /**
     * A supplier user may reach exactly one supplier; staff may reach any.
     *
     * Program-manager scoping is narrower still and needs the enrollment set, so
     * it lives in the services that already load it.
     */
    fun requireAccessTo(target: UUID) {
        val permitted = when (role) {
            Role.SUPPLIER_USER -> supplierId == target
            else -> true
        }
        if (!permitted) throw AccessDeniedException("You do not have access to this supplier.")
    }

    /** Supplier users act on their own record; staff act on a named one. */
    fun requireCanEditSupplier(target: UUID) {
        requireAccessTo(target)
        if (role == Role.PROGRAM_MANAGER) {
            throw AccessDeniedException("Program managers have read-only access.")
        }
    }
}

class AccessDeniedException(message: String) : RuntimeException(message)

/**
 * The two lockouts that turn "manage access without developer help" back into a
 * support ticket.
 *
 * Both are checked against a *count* rather than a list, so the caller can take
 * whatever lock it needs around the read and the write without this logic
 * knowing about transactions.
 */
object AdminSafeguards {

    fun checkRoleChange(actor: Actor, targetUserId: UUID, currentRole: Role, newRole: Role, activeAdmins: Int) {
        if (currentRole == newRole) return

        if (currentRole == Role.ADMIN && actor.userId == targetUserId) {
            throw AccessDeniedException(
                "You cannot remove your own administrator role. Ask another administrator to do it.",
            )
        }
        if (currentRole == Role.ADMIN && activeAdmins <= 1) {
            throw AccessDeniedException(
                "This is the last administrator. Promote someone else before changing this role.",
            )
        }
    }

    fun checkDeactivation(actor: Actor, targetUserId: UUID, targetRole: Role, activeAdmins: Int) {
        if (actor.userId == targetUserId) {
            throw AccessDeniedException("You cannot deactivate your own account.")
        }
        if (targetRole == Role.ADMIN && activeAdmins <= 1) {
            throw AccessDeniedException(
                "This is the last administrator. Promote someone else before deactivating this account.",
            )
        }
    }
}
