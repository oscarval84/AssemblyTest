package com.acme.onboarding.flow

import com.acme.onboarding.adapter.persistence.CatalogRepository
import com.acme.onboarding.adapter.persistence.UserRepository
import com.acme.onboarding.application.admin.StaffAdministrationService
import com.acme.onboarding.application.auth.AuthenticationService
import com.acme.onboarding.application.auth.InvitationService
import com.acme.onboarding.application.supplier.NewSupplierRequest
import com.acme.onboarding.application.supplier.SupplierService
import com.acme.onboarding.application.support.hash
import com.acme.onboarding.domain.user.AccessDeniedException
import com.acme.onboarding.domain.user.Actor
import com.acme.onboarding.domain.user.Role
import com.acme.onboarding.domain.user.UserStatus
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.testcontainers.service.connection.ServiceConnection
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.security.crypto.password.PasswordEncoder
import org.testcontainers.junit.jupiter.Container
import org.testcontainers.junit.jupiter.Testcontainers
import org.testcontainers.postgresql.PostgreSQLContainer
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The administration module, which exists because Marcus said it directly: they
 * cannot file an IT ticket every time someone needs access.
 *
 * The tests below are about the boundary between the two surfaces rather than
 * about the forms. Acme staff and supplier users are administered from different
 * screens by different roles, and the reason is a specific incident: an ops user
 * granting Acme-internal access while working a supplier's file.
 */
@Testcontainers
@SpringBootTest(properties = ["acme.demo.seed-on-startup=false"])
class AdministrationTest {

    companion object {
        @Container
        @ServiceConnection
        @JvmStatic
        val postgres: PostgreSQLContainer = PostgreSQLContainer("postgres:17-alpine")

        private const val PASSWORD = "Onboarding2026!"
    }

    @Autowired private lateinit var staff: StaffAdministrationService
    @Autowired private lateinit var suppliers: SupplierService
    @Autowired private lateinit var invitations: InvitationService
    @Autowired private lateinit var authentication: AuthenticationService
    @Autowired private lateinit var users: UserRepository
    @Autowired private lateinit var catalog: CatalogRepository
    @Autowired private lateinit var passwordEncoder: PasswordEncoder
    @Autowired private lateinit var jdbc: JdbcTemplate

    @Test
    fun `ops removes a supplier user's access and their session ends with it`() {
        val ops = staffActor(Role.OPS)
        val supplierId = supplierWithUser(ops)
        val supplierUser = users.listForSupplier(supplierId).single()

        val session = authentication.login(supplierUser.email, PASSWORD)
        assertNotNull(authentication.resolve(session.token))

        suppliers.deactivateUser(ops, supplierId, supplierUser.id)

        assertNull(authentication.resolve(session.token))
        assertEquals(
            "DEACTIVATED",
            suppliers.supplierUsers(ops, supplierId).single().status,
        )
    }

    @Test
    fun `the supplier surface cannot reach an Acme staff account`() {
        val ops = staffActor(Role.OPS)
        val admin = staffActor(Role.ADMIN)
        val supplierId = supplierWithUser(ops)

        // The attack this guards against is not exotic: the endpoint takes a
        // supplier and a user id, and without the binding check any user id
        // would do.
        val failure = assertFailsWith<AccessDeniedException> {
            suppliers.deactivateUser(ops, supplierId, admin.userId)
        }
        assertTrue(failure.message!!.contains("staff administration"), failure.message!!)

        assertEquals(
            "ACTIVE",
            jdbc.queryForObject("SELECT status FROM app_user WHERE id = ?", String::class.java, admin.userId),
        )
    }

    @Test
    fun `reactivating someone who never set a password returns them to invited`() {
        val ops = staffActor(Role.OPS)
        val supplierId = supplierWithUser(ops)
        val invitedId = suppliers.inviteUser(ops, supplierId, "second-${unique()}@example.test", "Sam Reed")

        suppliers.deactivateUser(ops, supplierId, invitedId)
        suppliers.reactivateUser(ops, supplierId, invitedId)

        // ACTIVE would be an account nobody can sign in to and nothing flags as
        // incomplete — the dormant row an access review is supposed to surface.
        assertEquals(UserStatus.INVITED, users.findById(invitedId)!!.status)
    }

    @Test
    fun `the access report lists every internal user with their scope`() {
        val admin = staffActor(Role.ADMIN)
        val programId = catalog.insertProgram("SCOPE_${unique()}", "Scoped Program", null)
        val managerId = invitations.inviteStaff(
            actor = admin,
            email = "pm-${unique()}@acme-msp.example",
            fullName = "Pat Moss",
            role = Role.PROGRAM_MANAGER,
            programIds = listOf(programId),
        )

        val report = staff.list(admin)
        val manager = report.single { it.id == managerId }

        assertEquals(Role.PROGRAM_MANAGER, manager.role)
        assertEquals(UserStatus.INVITED, manager.status)
        assertEquals(listOf(programId), manager.programIds)
        assertNull(manager.lastLoginAt)

        // Supplier users are administered from the supplier's own record and
        // must not appear on the staff screen at all.
        assertTrue(report.none { it.role == Role.SUPPLIER_USER })
    }

    @Test
    fun `changing a role takes effect on the next request, not at token expiry`() {
        val admin = staffActor(Role.ADMIN)
        val email = "demoted-${unique()}@acme-msp.example"
        val userId = users.insert(email, "Robin Chase", Role.OPS, null, UserStatus.ACTIVE, passwordEncoder.hash(PASSWORD))

        val session = authentication.login(email, PASSWORD)
        assertEquals(Role.OPS, authentication.resolve(session.token)!!.role)

        staff.changeRole(admin, userId, Role.PROGRAM_MANAGER)

        // Server-side sessions are what buy this: the same token now resolves to
        // the narrower role rather than carrying the old one until it lapses.
        assertEquals(Role.PROGRAM_MANAGER, authentication.resolve(session.token)!!.role)
    }

    @Test
    fun `demoting a program manager clears the program scope that no longer applies`() {
        val admin = staffActor(Role.ADMIN)
        val programId = catalog.insertProgram("CLEARED_${unique()}", "Cleared Program", null)
        val userId = invitations.inviteStaff(
            admin,
            "pm2-${unique()}@acme-msp.example",
            "Lee Vance",
            Role.PROGRAM_MANAGER,
            listOf(programId),
        )

        staff.changeRole(admin, userId, Role.OPS)

        // Left behind, the scope would silently reapply if they were ever made a
        // program manager again — an access grant nobody made.
        assertTrue(users.programScope(userId).isEmpty())
    }

    @Test
    fun `every access change is recorded against the user it affected`() {
        val admin = staffActor(Role.ADMIN)
        val userId = users.insert(
            "audited-${unique()}@acme-msp.example",
            "Ada Quinn",
            Role.OPS,
            null,
            UserStatus.ACTIVE,
            passwordEncoder.hash(PASSWORD),
        )

        staff.changeRole(admin, userId, Role.PROGRAM_MANAGER)
        staff.deactivate(admin, userId)
        staff.reactivate(admin, userId)

        val history = staff.accessHistory(admin, userId).map { it.action }
        assertEquals(
            listOf("USER_REACTIVATED", "USER_DEACTIVATED", "USER_ROLE_CHANGED"),
            history.take(3),
        )
        // Access changes are the first thing an auditor samples, so the actor is
        // on every one of them.
        assertTrue(staff.accessHistory(admin, userId).all { it.actorLabel.contains(admin.email) })
    }

    @Test
    fun `only an admin operates staff administration`() {
        val ops = staffActor(Role.OPS)
        val admin = staffActor(Role.ADMIN)

        assertFailsWith<AccessDeniedException> { staff.list(ops) }
        assertFailsWith<AccessDeniedException> { staff.changeRole(ops, admin.userId, Role.OPS) }
        assertFailsWith<AccessDeniedException> {
            invitations.inviteStaff(ops, "nope-${unique()}@acme-msp.example", "No Body", Role.OPS)
        }
    }

    // -- helpers --------------------------------------------------------------

    private fun unique() = UUID.randomUUID().toString().take(8)

    private fun staffActor(role: Role): Actor {
        val email = "${role.name.lowercase()}-${unique()}@acme-msp.example"
        val id = users.insert(email, "Test ${role.name}", role, null, UserStatus.ACTIVE, passwordEncoder.hash(PASSWORD))
        return Actor(id, email, "Test ${role.name}", role, null)
    }

    /** A supplier whose first user has accepted their invitation. */
    private fun supplierWithUser(ops: Actor): UUID {
        val programId = catalog.insertProgram("ADMIN_${unique()}", "Admin Program", null)
        val contactEmail = "owner-${unique()}@example.test"
        val supplier = suppliers.createAndInvite(
            ops,
            NewSupplierRequest("Admin Co ${unique()}", "Robin Fell", contactEmail, listOf(programId)),
        )

        val body = jdbc.queryForObject(
            "SELECT body_text FROM email_message WHERE lower(recipient_email) = lower(?) ORDER BY created_at DESC LIMIT 1",
            String::class.java,
            contactEmail,
        )!!
        invitations.accept(body.substringAfter("/invitation/").substringBefore('\n').trim(), PASSWORD)

        return supplier.profile.id
    }
}
