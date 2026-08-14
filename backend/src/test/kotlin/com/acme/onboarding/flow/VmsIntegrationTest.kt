package com.acme.onboarding.flow

import com.acme.onboarding.adapter.persistence.CatalogRepository
import com.acme.onboarding.adapter.persistence.DemoRepository
import com.acme.onboarding.adapter.persistence.EnrollmentRepository
import com.acme.onboarding.adapter.persistence.SupplierRepository
import com.acme.onboarding.adapter.persistence.UserRepository
import com.acme.onboarding.adapter.persistence.VmsLinkRepository
import com.acme.onboarding.adapter.vms.SimulatedVmsConnector
import com.acme.onboarding.application.document.DocumentReviewService
import com.acme.onboarding.application.document.DocumentService
import com.acme.onboarding.application.document.UploadRequest
import com.acme.onboarding.application.auth.InvitationService
import com.acme.onboarding.application.supplier.ProfileUpdateRequest
import com.acme.onboarding.application.supplier.SupplierService
import com.acme.onboarding.application.support.hash
import com.acme.onboarding.application.vms.OnboardingUpdate
import com.acme.onboarding.application.vms.VmsSyncService
import com.acme.onboarding.domain.onboarding.OnboardingStage
import com.acme.onboarding.domain.user.Actor
import com.acme.onboarding.domain.user.Role
import com.acme.onboarding.domain.user.UserStatus
import org.junit.jupiter.api.BeforeEach
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.testcontainers.service.connection.ServiceConnection
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.security.crypto.password.PasswordEncoder
import org.testcontainers.junit.jupiter.Container
import org.testcontainers.junit.jupiter.Testcontainers
import org.testcontainers.postgresql.PostgreSQLContainer
import java.time.LocalDate
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The integration, both directions, against the simulated VMS.
 *
 * The seeded connector returns two assignments: one for a supplier Acme already
 * knows and one for a company nobody has heard of. Between them they cover every
 * inbound path, and the tests below are mostly about the failure modes that only
 * show up on the *second* run — duplicate suppliers, duplicate invitations, and
 * an integration that says it is fine while sending nothing.
 */
@Testcontainers
@SpringBootTest(properties = ["acme.demo.seed-on-startup=false"])
class VmsIntegrationTest {

    companion object {
        @Container
        @ServiceConnection
        @JvmStatic
        val postgres: PostgreSQLContainer = PostgreSQLContainer("postgres:17-alpine")

        private const val PASSWORD = "Onboarding2026!"
        private val PDF = "%PDF-1.4\npushed by a test\n%%EOF".toByteArray(Charsets.US_ASCII)
    }

    @Autowired private lateinit var vms: VmsSyncService
    @Autowired private lateinit var connector: SimulatedVmsConnector
    @Autowired private lateinit var links: VmsLinkRepository
    @Autowired private lateinit var suppliers: SupplierService
    @Autowired private lateinit var supplierRepository: SupplierRepository
    @Autowired private lateinit var enrollments: EnrollmentRepository
    @Autowired private lateinit var documents: DocumentService
    @Autowired private lateinit var review: DocumentReviewService
    @Autowired private lateinit var invitations: InvitationService
    @Autowired private lateinit var users: UserRepository
    @Autowired private lateinit var catalog: CatalogRepository
    @Autowired private lateinit var passwordEncoder: PasswordEncoder
    @Autowired private lateinit var demo: DemoRepository
    @Autowired private lateinit var jdbc: JdbcTemplate

    /**
     * A clean world per test.
     *
     * The simulated VMS reports the same two assignments every time, which is
     * exactly what a real one does — the records are stable and the sync is
     * idempotent. That makes these tests order-dependent unless each starts from
     * an empty database, so each one does.
     */
    @BeforeEach
    fun resetWorld() {
        demo.truncateOperationalData()

        // The connector names real program codes; the sync refuses to invent a
        // program to match one in another system, so they have to exist.
        listOf("ATLAS_LOGISTICS", "NORTHSTAR_HEALTH").forEach { code ->
            val id = catalog.insertProgram(code, code, "Created by a test.")
            val w9 = catalog.documentTypes().first { it.code == "W9" }
            catalog.addRequirement(id, w9.id, "{}")
        }
        connector.inbox.clear()
        connector.failNextPushes = 0
    }

    @Test
    fun `an unknown assignment becomes a supplier, an enrollment and one invitation`() {
        val result = vms.sync()

        assertTrue(result.suppliersCreated >= 1)
        val supplierId = links.findLocalId("SIMULATED_VMS", "SUPPLIER", "VMS-SUP-8802")
        assertNotNull(supplierId)

        val supplier = supplierRepository.findById(supplierId)!!
        assertEquals("Vantage Field Solutions", supplier.legalName)

        // No ops action anywhere: the supplier is in the pipeline, at the right
        // stage, with an invitation already sent, because the VMS said so.
        assertEquals(OnboardingStage.INVITED, supplier.stage)
        assertEquals(1, enrollments.listForSupplier(supplierId).size)
        assertEquals(1, invitationsSentTo("priya.nadar@vantagefield.example"))
    }

    @Test
    fun `running the same sync again changes nothing and re-invites nobody`() {
        vms.sync()
        val suppliersAfterFirst = countSuppliers()

        val second = vms.sync()

        assertEquals(0, second.suppliersCreated)
        assertEquals(0, second.enrollmentsCreated)
        assertEquals(suppliersAfterFirst, countSuppliers())

        // A supplier who receives the same invitation every morning is how a
        // demo becomes a support ticket.
        assertEquals(1, invitationsSentTo("priya.nadar@vantagefield.example"))
    }

    @Test
    fun `a sync that changed nothing still writes a line to the log`() {
        vms.sync()
        val before = jdbc.queryForObject(
            "SELECT count(*) FROM integration_message WHERE message_type = 'ASSIGNMENT_SYNC'",
            Int::class.java,
        )!!

        vms.sync()

        // "We checked and there was nothing new" is what an operator needs when
        // they are wondering whether the integration is alive at all.
        assertEquals(
            before + 1,
            jdbc.queryForObject(
                "SELECT count(*) FROM integration_message WHERE message_type = 'ASSIGNMENT_SYNC'",
                Int::class.java,
            ),
        )
    }

    @Test
    fun `a known supplier gets the enrollment only, and a flag when the names disagree`() {
        val ops = staffActor(Role.OPS)
        // A supplier that already exists locally, linked to the VMS record the
        // connector will report — the second-program path.
        val existing = suppliers.createAndInvite(
            ops,
            com.acme.onboarding.application.supplier.NewSupplierRequest(
                legalName = "Northwind Staffing Partners",
                contactName = "Erin Walsh",
                contactEmail = "erin.walsh@northwind-staffing.example",
                programIds = listOf(catalog.programByCode("NORTHSTAR_HEALTH")!!.id),
            ),
        )
        links.link("SIMULATED_VMS", "SUPPLIER", existing.profile.id, "VMS-SUP-4471")
        val before = countSuppliers()

        val result = vms.sync()

        assertEquals(before + 1, countSuppliers()) // only the unknown one
        assertEquals(1, result.conflictsRaised)

        // Neither name is overwritten. One of them is wrong and a human decides
        // which; silent resolution is how two systems diverge unrecoverably.
        assertEquals("Northwind Staffing Partners", supplierRepository.findById(existing.profile.id)!!.legalName)
        val conflict = jdbc.queryForMap(
            "SELECT local_value, remote_value FROM vms_field_conflict WHERE supplier_id = ?",
            existing.profile.id,
        )
        assertEquals("Northwind Staffing Partners", conflict["local_value"])
        assertEquals("Northwind Staffing LLC", conflict["remote_value"])

        // The enrollment is new; the company, its W-9 and its agreement are not.
        assertEquals(2, enrollments.listForSupplier(existing.profile.id).size)
        assertEquals(1, invitationsSentTo("erin.walsh@northwind-staffing.example"))
    }

    @Test
    fun `approving the last document pushes the outcome back to the VMS`() {
        val ops = staffActor(Role.OPS)
        val reviewer = staffActor(Role.OPS)
        vms.sync()

        val supplierId = links.findLocalId("SIMULATED_VMS", "SUPPLIER", "VMS-SUP-8802")!!
        val supplierUser = acceptInvitation("priya.nadar@vantagefield.example")
        completeProfile(supplierUser, supplierId)

        val submissionId = documents.upload(
            supplierUser,
            UploadRequest(
                supplierId = supplierId,
                documentTypeCode = "W9",
                enrollmentId = null,
                originalFilename = "w9.pdf",
                declaredContentType = "application/pdf",
                bytes = PDF,
                issuedOn = LocalDate.now(),
                expiresOn = null,
            ),
        )
        review.approve(reviewer, submissionId)

        // Queued in the same transaction as the activation, and not yet sent.
        assertEquals(
            "PENDING",
            jdbc.queryForObject(
                "SELECT status FROM integration_message WHERE supplier_id = ? AND direction = 'OUTBOUND'",
                String::class.java,
                supplierId,
            ),
        )

        val drained = vms.drain()
        assertEquals(1, drained.sent, "drain reported $drained")

        val update = connector.inbox.filterIsInstance<OnboardingUpdate.SupplierActivated>().single()
        assertEquals("VMS-SUP-8802", update.externalSupplierId)
        assertTrue(update.satisfiedRequirements.contains("W9"))

        // Data leaving the system is an audit event regardless of where it goes.
        assertTrue(suppliers.activity(ops, supplierId).any { it.action == "VMS_UPDATE_SENT" })
    }

    @Test
    fun `a push that keeps failing dead-letters instead of retrying forever`() {
        val reviewer = staffActor(Role.OPS)
        vms.sync()
        val supplierId = links.findLocalId("SIMULATED_VMS", "SUPPLIER", "VMS-SUP-8802")!!
        val supplierUser = acceptInvitation("priya.nadar@vantagefield.example")
        completeProfile(supplierUser, supplierId)

        val submissionId = documents.upload(
            supplierUser,
            UploadRequest(
                supplierId = supplierId,
                documentTypeCode = "W9",
                enrollmentId = null,
                originalFilename = "w9.pdf",
                declaredContentType = "application/pdf",
                bytes = PDF,
                issuedOn = LocalDate.now(),
                expiresOn = null,
            ),
        )
        review.approve(reviewer, submissionId)

        connector.failNextPushes = 99
        repeat(6) {
            // Clear the backoff so the test does not have to wait for it.
            jdbc.update("UPDATE integration_message SET next_attempt_at = now() WHERE supplier_id = ?", supplierId)
            vms.drain()
        }

        val row = jdbc.queryForMap(
            "SELECT status, attempts, last_error FROM integration_message WHERE supplier_id = ? AND direction = 'OUTBOUND'",
            supplierId,
        )
        // Terminal and visible, rather than an infinite quiet loop: a silently
        // failing integration is worse than no integration, because everyone
        // downstream believes the VMS is current.
        assertEquals("DEAD_LETTER", row["status"])
        assertTrue((row["attempts"] as Number).toInt() >= 6)
        assertTrue((row["last_error"] as String).contains("timeout"))
    }

    @Test
    fun `a supplier created locally is never pushed as a new VMS record`() {
        val ops = staffActor(Role.OPS)
        val local = suppliers.createAndInvite(
            ops,
            com.acme.onboarding.application.supplier.NewSupplierRequest(
                legalName = "Locally Invited Co ${unique()}",
                contactName = "Sam Local",
                contactEmail = "sam-${unique()}@example.test",
                programIds = listOf(catalog.programByCode("ATLAS_LOGISTICS")!!.id),
            ),
        )

        // This tool does not invent records in someone else's system of record.
        assertNull(links.findExternalId("SIMULATED_VMS", "SUPPLIER", local.profile.id))
        assertEquals(
            0,
            jdbc.queryForObject(
                "SELECT count(*) FROM integration_message WHERE supplier_id = ? AND direction = 'OUTBOUND'",
                Int::class.java,
                local.profile.id,
            ),
        )
    }

    @Test
    fun `restricted fields never reach the VMS`() {
        val reviewer = staffActor(Role.OPS)
        vms.sync()
        val supplierId = links.findLocalId("SIMULATED_VMS", "SUPPLIER", "VMS-SUP-8802")!!
        val supplierUser = acceptInvitation("priya.nadar@vantagefield.example")
        completeProfile(supplierUser, supplierId)

        val submissionId = documents.upload(
            supplierUser,
            UploadRequest(
                supplierId = supplierId,
                documentTypeCode = "W9",
                enrollmentId = null,
                originalFilename = "w9.pdf",
                declaredContentType = "application/pdf",
                bytes = PDF,
                issuedOn = LocalDate.now(),
                expiresOn = null,
            ),
        )
        review.approve(reviewer, submissionId)

        val payloads = jdbc.queryForList(
            "SELECT payload::text FROM integration_message WHERE supplier_id = ?",
            String::class.java,
            supplierId,
        )

        // Tax ID and bank account are Restricted, and Restricted data does not
        // leave this system without explicit sign-off — the same gate that keys
        // W-9 extraction off.
        payloads.forEach { payload ->
            assertTrue(!payload!!.contains("taxId"), payload)
            assertTrue(!payload.contains("123456789"), payload)
            assertTrue(!payload.contains("bankAccount"), payload)
        }
    }

    // -- helpers --------------------------------------------------------------

    private fun unique() = UUID.randomUUID().toString().take(8)

    private fun countSuppliers(): Int =
        jdbc.queryForObject("SELECT count(*) FROM supplier", Int::class.java)!!

    private fun invitationsSentTo(email: String): Int =
        jdbc.queryForObject(
            "SELECT count(*) FROM email_message WHERE lower(recipient_email) = lower(?) AND template = 'SUPPLIER_INVITED'",
            Int::class.java,
            email,
        )!!

    private fun staffActor(role: Role): Actor {
        val email = "${role.name.lowercase()}-${unique()}@acme-msp.example"
        val id = users.insert(email, "Test ${role.name}", role, null, UserStatus.ACTIVE, passwordEncoder.hash(PASSWORD))
        return Actor(id, email, "Test ${role.name}", role, null)
    }

    private fun acceptInvitation(email: String): Actor {
        val body = jdbc.queryForObject(
            "SELECT body_text FROM email_message WHERE lower(recipient_email) = lower(?) ORDER BY created_at DESC LIMIT 1",
            String::class.java,
            email,
        )!!
        return invitations.accept(body.substringAfter("/invitation/").substringBefore('\n').trim(), PASSWORD).actor
    }

    private fun completeProfile(actor: Actor, supplierId: UUID) {
        suppliers.updateProfile(
            actor,
            supplierId,
            ProfileUpdateRequest(
                legalName = supplierRepository.findById(supplierId)!!.legalName,
                dbaName = null,
                entityType = "LLC",
                taxId = "12-3456789",
                addressLine1 = "1 Test Street",
                addressLine2 = null,
                city = "Boston",
                state = "MA",
                postalCode = "02115",
                primaryContactName = "Test Contact",
                primaryContactEmail = "contact-${unique()}@example.test",
                primaryContactPhone = "617-555-0100",
            ),
        )
    }
}
