package com.acme.onboarding.flow

import com.acme.onboarding.adapter.persistence.CatalogRepository
import com.acme.onboarding.adapter.persistence.UserRepository
import com.acme.onboarding.application.auth.InvitationService
import com.acme.onboarding.application.document.DocumentReviewService
import com.acme.onboarding.application.document.DocumentService
import com.acme.onboarding.application.document.RejectionGrounds
import com.acme.onboarding.application.document.UploadRequest
import com.acme.onboarding.application.supplier.NewSupplierRequest
import com.acme.onboarding.application.supplier.ProfileUpdateRequest
import com.acme.onboarding.application.supplier.RequirementState
import com.acme.onboarding.application.supplier.SupplierService
import com.acme.onboarding.application.support.InvalidRequestException
import com.acme.onboarding.application.support.hash
import com.acme.onboarding.domain.compliance.ComplianceStatus
import com.acme.onboarding.domain.onboarding.OnboardingStage
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
import java.time.LocalDate
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Review: the half of document collection that Acme performs.
 *
 * These tests are about the consequences of a decision rather than the decision
 * itself — whether approving the last document actually finishes onboarding,
 * whether a rejection reaches the supplier with a reason attached, and whether
 * the segregation-of-duties rule holds on the path ops actually uses.
 */
@Testcontainers
@SpringBootTest(properties = ["acme.demo.seed-on-startup=false"])
class DocumentReviewTest {

    companion object {
        @Container
        @ServiceConnection
        @JvmStatic
        val postgres: PostgreSQLContainer = PostgreSQLContainer("postgres:17-alpine")

        private const val PASSWORD = "Onboarding2026!"
        private val PDF = "%PDF-1.4\nreviewed by a test\n%%EOF".toByteArray(Charsets.US_ASCII)
    }

    @Autowired private lateinit var suppliers: SupplierService
    @Autowired private lateinit var documents: DocumentService
    @Autowired private lateinit var review: DocumentReviewService
    @Autowired private lateinit var invitations: InvitationService
    @Autowired private lateinit var users: UserRepository
    @Autowired private lateinit var catalog: CatalogRepository
    @Autowired private lateinit var passwordEncoder: PasswordEncoder
    @Autowired private lateinit var jdbc: JdbcTemplate

    @Test
    fun `approving the last document completes onboarding and activates the enrollment`() {
        val ops = staffActor(Role.OPS)
        val world = onboardSupplier(ops, listOf("W9"))

        val submissionId = upload(world.supplierUser, world.supplierId, "W9", null)
        assertEquals(OnboardingStage.IN_REVIEW, suppliers.detail(ops, world.supplierId).profile.stage)

        review.approve(ops, submissionId)

        val detail = suppliers.detail(ops, world.supplierId)
        assertEquals(OnboardingStage.APPROVED, detail.profile.stage)
        assertEquals(ComplianceStatus.COMPLIANT, detail.complianceStatus)

        // Activation is the outcome the VMS is waiting to hear about (§5). Until
        // that connector exists it is local, but it still has to happen.
        assertEquals("ACTIVE", detail.enrollments.single().status)

        val subject = jdbc.queryForObject(
            """
            SELECT subject FROM email_message
             WHERE supplier_id = ? AND template = 'ONBOARDING_COMPLETED'
             ORDER BY created_at DESC LIMIT 1
            """.trimIndent(),
            String::class.java,
            world.supplierId,
        )
        assertNotNull(subject)
        assertTrue(subject.contains("approved"), subject)
    }

    @Test
    fun `a rejection hands the supplier back a reason they can act on`() {
        val ops = staffActor(Role.OPS)
        val world = onboardSupplier(ops, listOf("W9"))
        val submissionId = upload(world.supplierUser, world.supplierId, "W9", null)

        review.reject(
            actor = ops,
            submissionId = submissionId,
            grounds = RejectionGrounds.CatalogReason("ILLEGIBLE"),
            note = "The bottom third of the form is cut off.",
        )

        val detail = suppliers.detail(ops, world.supplierId)
        assertEquals(OnboardingStage.CHANGES_REQUESTED, detail.profile.stage)

        // The supplier's own view names the reason and the note, because a
        // rejection with no explanation is the black box this product replaces.
        val checklist = suppliers.checklist(world.supplierUser, world.supplierId)
        val entry = checklist.programs.single().neededForThisProgram.single { it.documentTypeCode == "W9" }
        assertEquals(RequirementState.CHANGES_REQUESTED, entry.state)
        assertEquals("Illegible or partially cut off", entry.submission?.rejectionReasonLabel)
        assertEquals("The bottom third of the form is cut off.", entry.submission?.rejectionNote)

        val email = jdbc.queryForMap(
            """
            SELECT subject, body_text FROM email_message
             WHERE supplier_id = ? AND template = 'DOCUMENT_REJECTED'
             ORDER BY created_at DESC LIMIT 1
            """.trimIndent(),
            world.supplierId,
        )
        assertTrue((email["body_text"] as String).contains("Illegible"), email.toString())
        assertTrue((email["body_text"] as String).contains("bottom third"), email.toString())
    }

    @Test
    fun `replacing a rejected document puts it back in front of ops`() {
        val ops = staffActor(Role.OPS)
        val world = onboardSupplier(ops, listOf("W9"))
        val first = upload(world.supplierUser, world.supplierId, "W9", null)
        review.reject(ops, first, RejectionGrounds.CatalogReason("ILLEGIBLE"), null)

        val second = upload(world.supplierUser, world.supplierId, "W9", null)

        assertEquals(OnboardingStage.IN_REVIEW, suppliers.detail(ops, world.supplierId).profile.stage)
        assertTrue(review.queue(ops).any { it.submissionId == second })

        // Nothing is overwritten: the rejected version stays, and stays rejected.
        assertEquals(
            2,
            jdbc.queryForObject(
                "SELECT count(*) FROM document_submission WHERE supplier_id = ?",
                Int::class.java,
                world.supplierId,
            ),
        )
        assertEquals(
            "REJECTED",
            jdbc.queryForObject("SELECT status FROM document_submission WHERE id = ?", String::class.java, first),
        )
    }

    @Test
    fun `the person who uploaded a document cannot be the one who approves it`() {
        val ops = staffActor(Role.OPS)
        val world = onboardSupplier(ops, listOf("W9"))

        // Ops uploading for a two-person agency is a real workflow, and it is
        // exactly the path that must still force a second pair of eyes.
        val submissionId = upload(ops, world.supplierId, "W9", null)

        val failure = assertFailsWith<InvalidRequestException> { review.approve(ops, submissionId) }
        assertTrue(failure.message!!.contains("someone else"), failure.message!!)

        val colleague = staffActor(Role.OPS)
        review.approve(colleague, submissionId)
        assertEquals(OnboardingStage.APPROVED, suppliers.detail(ops, world.supplierId).profile.stage)
    }

    @Test
    fun `a document cannot be reviewed twice`() {
        val ops = staffActor(Role.OPS)
        val colleague = staffActor(Role.OPS)
        val world = onboardSupplier(ops, listOf("W9"))
        val submissionId = upload(world.supplierUser, world.supplierId, "W9", null)

        review.approve(ops, submissionId)

        val failure = assertFailsWith<InvalidRequestException> { review.approve(colleague, submissionId) }
        assertTrue(failure.message!!.contains("already reviewed"), failure.message!!)
    }

    @Test
    fun `the queue tells a reviewer what they cannot act on, rather than hiding it`() {
        val ops = staffActor(Role.OPS)
        val world = onboardSupplier(ops, listOf("W9", "CERTIFICATE_OF_INSURANCE"))

        upload(ops, world.supplierId, "W9", null)
        upload(world.supplierUser, world.supplierId, "CERTIFICATE_OF_INSURANCE", LocalDate.now().plusMonths(6))

        val queue = review.queue(ops).filter { it.supplierId == world.supplierId }
        assertEquals(2, queue.size)

        // Hiding their own upload would leave the reviewer wondering where the
        // document went; the queue says why they cannot act on it instead.
        assertEquals(false, queue.single { it.documentTypeCode == "W9" }.reviewableByCaller)
        assertEquals(true, queue.single { it.documentTypeCode == "CERTIFICATE_OF_INSURANCE" }.reviewableByCaller)
        assertTrue(queue.all { it.supplierLegalName.isNotBlank() && it.programNames.isNotEmpty() })
    }

    @Test
    fun `only ops and admins review`() {
        val ops = staffActor(Role.OPS)
        val world = onboardSupplier(ops, listOf("W9"))
        val submissionId = upload(world.supplierUser, world.supplierId, "W9", null)

        assertFailsWith<com.acme.onboarding.domain.user.AccessDeniedException> {
            review.approve(world.supplierUser, submissionId)
        }
        assertFailsWith<com.acme.onboarding.domain.user.AccessDeniedException> {
            review.approve(staffActor(Role.PROGRAM_MANAGER), submissionId)
        }
    }

    // -- helpers --------------------------------------------------------------

    private data class World(val supplierId: UUID, val supplierUser: Actor)

    private fun unique() = UUID.randomUUID().toString().take(8)

    private fun staffActor(role: Role): Actor {
        val email = "${role.name.lowercase()}-${unique()}@acme-msp.example"
        val id = users.insert(email, "Test ${role.name}", role, null, UserStatus.ACTIVE, passwordEncoder.hash(PASSWORD))
        return Actor(id, email, "Test ${role.name}", role, null)
    }

    /** A supplier past registration and profile, so uploads land in review. */
    private fun onboardSupplier(ops: Actor, documentTypeCodes: List<String>): World {
        val programId = catalog.insertProgram("REVIEW_${unique()}", "Review Program", "Created by a test.")
        val types = catalog.documentTypes().associateBy { it.code }
        documentTypeCodes.forEach { catalog.addRequirement(programId, types.getValue(it).id, "{}") }

        val contactEmail = "owner-${unique()}@example.test"
        val supplier = suppliers.createAndInvite(
            ops,
            NewSupplierRequest("Review Co ${unique()}", "Robin Fell", contactEmail, listOf(programId)),
        )
        val token = invitationToken(contactEmail)
        val supplierUser = invitations.accept(token, PASSWORD).actor

        suppliers.updateProfile(
            supplierUser,
            supplier.profile.id,
            ProfileUpdateRequest(
                legalName = supplier.profile.legalName,
                dbaName = null,
                entityType = "LLC",
                taxId = "12-3456789",
                addressLine1 = "1 Test Street",
                addressLine2 = null,
                city = "Boston",
                state = "MA",
                postalCode = "02115",
                primaryContactName = "Robin Fell",
                primaryContactEmail = contactEmail,
                primaryContactPhone = "617-555-0100",
            ),
        )
        return World(supplier.profile.id, supplierUser)
    }

    private fun invitationToken(email: String): String {
        val body = jdbc.queryForObject(
            "SELECT body_text FROM email_message WHERE lower(recipient_email) = lower(?) ORDER BY created_at DESC LIMIT 1",
            String::class.java,
            email,
        )!!
        return body.substringAfter("/invitation/").substringBefore('\n').trim()
    }

    private fun upload(actor: Actor, supplierId: UUID, typeCode: String, expiresOn: LocalDate?): UUID =
        documents.upload(
            actor,
            UploadRequest(
                supplierId = supplierId,
                documentTypeCode = typeCode,
                enrollmentId = null,
                originalFilename = "$typeCode.pdf",
                declaredContentType = "application/pdf",
                bytes = PDF,
                issuedOn = LocalDate.now().minusMonths(1),
                expiresOn = expiresOn,
            ),
        )
}
