package com.acme.onboarding.domain.user

import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * Both safeguards exist to prevent the same outcome: an organisation that needs
 * a developer to get its access back. That is the exact thing the administration
 * module was built to avoid, so it is worth a test rather than a code review.
 */
class AdminSafeguardsTest {

    private val danaId = UUID.randomUUID()
    private val marcusId = UUID.randomUUID()

    private val dana = Actor(danaId, "dana@acme-msp.example", "Dana Whitfield", Role.ADMIN, null)

    @Test
    fun `an admin cannot remove their own admin role`() {
        val failure = assertFailsWith<AccessDeniedException> {
            AdminSafeguards.checkRoleChange(
                actor = dana,
                targetUserId = danaId,
                currentRole = Role.ADMIN,
                newRole = Role.OPS,
                activeAdmins = 4,
            )
        }
        assertTrue(failure.message!!.contains("another administrator"), failure.message!!)
    }

    @Test
    fun `the last admin cannot be demoted, even by a different admin`() {
        assertFailsWith<AccessDeniedException> {
            AdminSafeguards.checkRoleChange(
                actor = dana,
                targetUserId = marcusId,
                currentRole = Role.ADMIN,
                newRole = Role.OPS,
                activeAdmins = 1,
            )
        }
    }

    @Test
    fun `demoting one of several admins is allowed`() {
        AdminSafeguards.checkRoleChange(
            actor = dana,
            targetUserId = marcusId,
            currentRole = Role.ADMIN,
            newRole = Role.OPS,
            activeAdmins = 2,
        )
    }

    @Test
    fun `a role change that changes nothing is never blocked`() {
        // Saving a form without touching the role must not trip a safeguard, or
        // the admin screen becomes unusable for the last remaining admin.
        AdminSafeguards.checkRoleChange(
            actor = dana,
            targetUserId = danaId,
            currentRole = Role.ADMIN,
            newRole = Role.ADMIN,
            activeAdmins = 1,
        )
    }

    @Test
    fun `nobody deactivates themselves, and the last admin cannot be deactivated`() {
        assertFailsWith<AccessDeniedException> {
            AdminSafeguards.checkDeactivation(dana, danaId, Role.ADMIN, activeAdmins = 3)
        }
        assertFailsWith<AccessDeniedException> {
            AdminSafeguards.checkDeactivation(dana, marcusId, Role.ADMIN, activeAdmins = 1)
        }

        AdminSafeguards.checkDeactivation(dana, marcusId, Role.OPS, activeAdmins = 1)
    }
}

/**
 * Authorization is enforced in the application layer because it is about which
 * records a caller may resolve — a question no URL pattern can answer.
 */
class ActorAccessTest {

    private val northwind = UUID.randomUUID()
    private val cedarGrove = UUID.randomUUID()

    private val supplierUser = Actor(
        UUID.randomUUID(), "erin@northwind.example", "Erin Walsh", Role.SUPPLIER_USER, northwind,
    )
    private val programManager = Actor(
        UUID.randomUUID(), "priya@acme-msp.example", "Priya Raman", Role.PROGRAM_MANAGER, null,
    )

    @Test
    fun `a supplier user resolves their own supplier and no other`() {
        supplierUser.requireAccessTo(northwind)
        assertFailsWith<AccessDeniedException> { supplierUser.requireAccessTo(cedarGrove) }
    }

    @Test
    fun `a program manager is read-only`() {
        programManager.requireAccessTo(northwind)
        assertFailsWith<AccessDeniedException> { programManager.requireCanEditSupplier(northwind) }
        assertFailsWith<AccessDeniedException> { programManager.requireOps() }
        assertFailsWith<AccessDeniedException> { programManager.requireAdmin() }
    }

    @Test
    fun `ops can act on any supplier but is not an admin`() {
        val marcus = Actor(UUID.randomUUID(), "marcus@acme-msp.example", "Marcus Lee", Role.OPS, null)

        marcus.requireOps()
        marcus.requireCanEditSupplier(cedarGrove)
        assertFailsWith<AccessDeniedException> { marcus.requireAdmin() }
    }
}
