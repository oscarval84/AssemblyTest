package com.acme.onboarding.flow

import com.acme.onboarding.adapter.persistence.CatalogRepository
import com.acme.onboarding.adapter.persistence.SubmissionRepository
import com.acme.onboarding.adapter.persistence.UserRepository
import com.acme.onboarding.application.auth.InvitationService
import com.acme.onboarding.application.compliance.ComplianceSweepService
import com.acme.onboarding.application.document.DocumentReviewService
import com.acme.onboarding.application.document.DocumentService
import com.acme.onboarding.application.document.UploadRequest
import com.acme.onboarding.application.supplier.NewSupplierRequest
import com.acme.onboarding.application.supplier.ProfileUpdateRequest
import com.acme.onboarding.application.supplier.SupplierService
import com.acme.onboarding.application.support.hash
import com.acme.onboarding.domain.compliance.ComplianceEvaluator
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
import kotlin.test.assertTrue

/**
 * The sweep that exists because a certificate lapsed twice and nobody noticed.
 *
 * Dates here are chosen relative to the evaluator's own "today" rather than
 * hardcoded, so the suite does not start failing on a particular calendar day —
 * the failure mode these tests are meant to catch is a supplier notified a day
 * late, and a test that is itself date-sensitive cannot be trusted to catch it.
 *
 * "Today" means [ComplianceEvaluator.today] — Acme's business time zone, the
 * same function the sweep itself resolves dates with. Asking the machine
 * instead made this file fail for six hours a day: run from a laptop west of
 * New York after 18:00, the application was already on the next business date
 * and every day count came out one short.
 */
@Testcontainers
@SpringBootTest(properties = ["acme.demo.seed-on-startup=false"])
class ComplianceSweepTest {

    companion object {
        @Container
        @ServiceConnection
        @JvmStatic
        val postgres: PostgreSQLContainer = PostgreSQLContainer("postgres:17-alpine")

        private const val PASSWORD = "Onboarding2026!"
        private val PDF = "%PDF-1.4\nswept by a test\n%%EOF".toByteArray(Charsets.US_ASCII)
    }

    @Autowired private lateinit var sweep: ComplianceSweepService
    @Autowired private lateinit var evaluator: ComplianceEvaluator
    @Autowired private lateinit var suppliers: SupplierService
    @Autowired private lateinit var documents: DocumentService
    @Autowired private lateinit var review: DocumentReviewService
    @Autowired private lateinit var invitations: InvitationService
    @Autowired private lateinit var submissions: SubmissionRepository
    @Autowired private lateinit var users: UserRepository
    @Autowired private lateinit var catalog: CatalogRepository
    @Autowired private lateinit var passwordEncoder: PasswordEncoder
    @Autowired private lateinit var jdbc: JdbcTemplate

    @Test
    fun `a certificate inside the warning band produces one reminder, not one a day`() {
        val ops = staffActor(Role.OPS)
        val supplierId = approvedSupplierWithCertificate(ops, expiresIn = 20)

        val first = sweep.sweep()
        assertEquals(1, first.remindersSent)

        // The job runs daily and the document sits in the band for weeks. This
        // is the difference between a useful reminder and a filtered sender.
        val second = sweep.sweep()
        assertEquals(0, second.remindersSent)

        assertEquals(1, remindersFor(supplierId))
    }

    @Test
    fun `crossing the seven-day mark earns a second, more urgent message`() {
        val ops = staffActor(Role.OPS)
        val supplierId = approvedSupplierWithCertificate(ops, expiresIn = 20)
        sweep.sweep()

        // Bring the expiry forward rather than moving the clock: the same
        // arithmetic, and it exercises the real query.
        moveExpiry(supplierId, today().plusDays(5))
        val urgent = sweep.sweep()

        assertEquals(1, urgent.remindersSent)
        assertEquals(2, remindersFor(supplierId))

        val subjects = jdbc.queryForList(
            "SELECT subject FROM email_message WHERE supplier_id = ? AND template = 'EXPIRY_REMINDER' ORDER BY created_at",
            String::class.java,
            supplierId,
        )
        assertTrue(subjects[0]!!.contains("Time to renew"), subjects.toString())
        assertTrue(subjects[1]!!.contains("expires in 5 days"), subjects.toString())
    }

    @Test
    fun `an expired certificate reopens document collection and says so in the timeline`() {
        val ops = staffActor(Role.OPS)
        val supplierId = approvedSupplierWithCertificate(ops, expiresIn = 20)
        assertEquals(OnboardingStage.APPROVED, suppliers.detail(ops, supplierId).profile.stage)

        moveExpiry(supplierId, today().minusDays(1))
        val result = sweep.sweep()

        assertEquals(1, result.suppliersReopened)
        // Flags rather than blocks: the supplier is back in document collection,
        // not suspended. Whether an expired certificate stops a placement is the
        // VMS's call, and Acme has not answered that question.
        assertEquals(
            OnboardingStage.DOCUMENTS_IN_PROGRESS,
            suppliers.detail(ops, supplierId).profile.stage,
        )

        val actions = suppliers.activity(ops, supplierId).map { it.action }
        assertTrue(actions.contains("DOCUMENT_EXPIRED"), actions.toString())

        val actor = suppliers.activity(ops, supplierId).first { it.action == "DOCUMENT_EXPIRED" }.actorLabel
        assertEquals("compliance sweep", actor)
    }

    @Test
    fun `documents beyond the warning band are left alone`() {
        val ops = staffActor(Role.OPS)
        val supplierId = approvedSupplierWithCertificate(ops, expiresIn = 200)

        sweep.sweep()

        // Scoped to this supplier rather than asserting on the sweep's totals:
        // the job is global by nature, and a test that assumes it is alone in
        // the database is asserting something the production job never has.
        assertEquals(0, remindersFor(supplierId))
        assertTrue(sweep.upcomingExpirations(ops, withinDays = 60).none { it.supplierId == supplierId })
    }

    @Test
    fun `a renewed certificate is a new document, and earns its own reminders`() {
        val ops = staffActor(Role.OPS)
        val reviewer = staffActor(Role.OPS)
        val supplierId = approvedSupplierWithCertificate(ops, expiresIn = 20)
        sweep.sweep()
        assertEquals(1, remindersFor(supplierId))

        // The supplier renews. The replacement is a new submission row, so the
        // reminders already claimed against the old one say nothing about it.
        val supplierUser = users.listForSupplier(supplierId).first { it.status == UserStatus.ACTIVE }
        val replacement = documents.upload(
            supplierUser.toActor(),
            UploadRequest(
                supplierId = supplierId,
                documentTypeCode = "CERTIFICATE_OF_INSURANCE",
                enrollmentId = null,
                originalFilename = "renewed.pdf",
                declaredContentType = "application/pdf",
                bytes = PDF,
                issuedOn = today(),
                expiresOn = today().plusDays(10),
            ),
        )
        review.approve(reviewer, replacement)

        val afterRenewal = sweep.sweep()
        assertEquals(1, afterRenewal.remindersSent)
        assertEquals(2, remindersFor(supplierId))
    }

    // -- helpers --------------------------------------------------------------

    private fun unique() = UUID.randomUUID().toString().take(8)

    /** Today as the application resolves it, not as this machine happens to. */
    private fun today(): LocalDate = evaluator.today()

    private fun staffActor(role: Role): Actor {
        val email = "${role.name.lowercase()}-${unique()}@acme-msp.example"
        val id = users.insert(email, "Test ${role.name}", role, null, UserStatus.ACTIVE, passwordEncoder.hash(PASSWORD))
        return Actor(id, email, "Test ${role.name}", role, null)
    }

    private fun remindersFor(supplierId: UUID): Int =
        jdbc.queryForObject(
            """
            SELECT count(*) FROM expiry_reminder r
              JOIN document_submission s ON s.id = r.document_submission_id
             WHERE s.supplier_id = ?
            """.trimIndent(),
            Int::class.java,
            supplierId,
        )!!

    private fun moveExpiry(supplierId: UUID, expiresOn: LocalDate) {
        jdbc.update(
            "UPDATE document_submission SET expires_on = ? WHERE supplier_id = ? AND is_current",
            java.sql.Date.valueOf(expiresOn),
            supplierId,
        )
    }

    /** An approved supplier whose only requirement is a certificate that expires. */
    private fun approvedSupplierWithCertificate(ops: Actor, expiresIn: Long): UUID {
        val reviewer = staffActor(Role.OPS)
        val programId = catalog.insertProgram("SWEEP_${unique()}", "Sweep Program", null)
        val types = catalog.documentTypes().associateBy { it.code }
        catalog.addRequirement(programId, types.getValue("CERTIFICATE_OF_INSURANCE").id, "{}")

        val contactEmail = "owner-${unique()}@example.test"
        val supplier = suppliers.createAndInvite(
            ops,
            NewSupplierRequest("Sweep Co ${unique()}", "Robin Fell", contactEmail, listOf(programId)),
        )
        val body = jdbc.queryForObject(
            "SELECT body_text FROM email_message WHERE lower(recipient_email) = lower(?) ORDER BY created_at DESC LIMIT 1",
            String::class.java,
            contactEmail,
        )!!
        val supplierUser = invitations
            .accept(body.substringAfter("/invitation/").substringBefore('\n').trim(), PASSWORD)
            .actor

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

        val submissionId = documents.upload(
            supplierUser,
            UploadRequest(
                supplierId = supplier.profile.id,
                documentTypeCode = "CERTIFICATE_OF_INSURANCE",
                enrollmentId = null,
                originalFilename = "coi.pdf",
                declaredContentType = "application/pdf",
                bytes = PDF,
                issuedOn = today().minusYears(1),
                expiresOn = today().plusDays(expiresIn),
            ),
        )
        review.approve(reviewer, submissionId)

        return supplier.profile.id
    }
}
