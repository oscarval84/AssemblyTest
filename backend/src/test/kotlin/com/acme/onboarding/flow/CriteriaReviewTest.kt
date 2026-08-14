package com.acme.onboarding.flow

import com.acme.onboarding.adapter.persistence.CatalogRepository
import com.acme.onboarding.adapter.persistence.CriteriaRepository
import com.acme.onboarding.adapter.persistence.UserRepository
import com.acme.onboarding.application.auth.InvitationService
import com.acme.onboarding.application.criteria.CriteriaReviewService
import com.acme.onboarding.application.document.DocumentReviewService
import com.acme.onboarding.application.document.DocumentService
import com.acme.onboarding.application.document.RejectionGrounds
import com.acme.onboarding.application.document.UploadRequest
import com.acme.onboarding.application.supplier.NewSupplierRequest
import com.acme.onboarding.application.supplier.ProfileUpdateRequest
import com.acme.onboarding.application.supplier.SupplierService
import com.acme.onboarding.application.support.InvalidRequestException
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
import java.time.LocalDate
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Review against criteria Acme authored.
 *
 * The property that matters most here is the one an auditor asks about: after
 * the criteria change, a review recorded in March still says what it was judged
 * against in March. Everything else follows from criteria being versioned rather
 * than edited.
 */
@Testcontainers
@SpringBootTest(properties = ["acme.demo.seed-on-startup=false"])
class CriteriaReviewTest {

    companion object {
        @Container
        @ServiceConnection
        @JvmStatic
        val postgres: PostgreSQLContainer = PostgreSQLContainer("postgres:17-alpine")

        private const val PASSWORD = "Onboarding2026!"
        private val PDF = "%PDF-1.4\njudged by a test\n%%EOF".toByteArray(Charsets.US_ASCII)
    }

    @Autowired private lateinit var criteriaService: CriteriaReviewService
    @Autowired private lateinit var criteria: CriteriaRepository
    @Autowired private lateinit var suppliers: SupplierService
    @Autowired private lateinit var documents: DocumentService
    @Autowired private lateinit var review: DocumentReviewService
    @Autowired private lateinit var invitations: InvitationService
    @Autowired private lateinit var users: UserRepository
    @Autowired private lateinit var catalog: CatalogRepository
    @Autowired private lateinit var passwordEncoder: PasswordEncoder
    @Autowired private lateinit var jdbc: JdbcTemplate

    @Test
    fun `ops authors criteria without a deploy, and editing produces a new version`() {
        val ops = staffActor(Role.OPS)
        val requirementId = requirement()

        val first = criteriaService.author(
            ops,
            requirementId,
            listOf(
                "The certificate holder is Acme Inc.",
                "The general liability aggregate is at least USD 2,000,000.",
            ),
        )
        assertEquals(1, first)

        val second = criteriaService.author(
            ops,
            requirementId,
            listOf(
                "The certificate holder is Acme Inc.",
                "The general liability aggregate is at least USD 5,000,000.",
                "Workers' compensation coverage is present and unexpired.",
            ),
        )
        assertEquals(2, second)

        val current = criteria.current(requirementId)
        assertEquals(3, current.size)
        assertEquals(listOf(1, 2, 3), current.map { it.ordinal })
        assertTrue(current.all { it.version == 2 })

        // The old wording is retired, not deleted: an evaluation recorded
        // against version 1 still has text to point at.
        assertEquals(
            2,
            jdbc.queryForObject(
                "SELECT count(*) FROM acceptance_criterion WHERE program_requirement_id = ? AND retired_at IS NOT NULL",
                Int::class.java,
                requirementId,
            ),
        )
    }

    @Test
    fun `a verdict records the version it judged against, and survives the criteria changing`() {
        val ops = staffActor(Role.OPS)
        val requirementId = requirement()
        criteriaService.author(ops, requirementId, listOf("The general liability aggregate is at least USD 2,000,000."))

        val world = supplierWithSubmission(ops)
        val checklist = criteriaService.checklist(ops, world.submissionId)
        val criterion = checklist.criteria.single()

        criteriaService.judge(
            actor = ops,
            submissionId = world.submissionId,
            criterionId = criterion.criterionId,
            verdict = "FAIL",
            evidence = "General liability aggregate: USD 1,000,000",
        )

        // Acme changes what it requires.
        criteriaService.author(ops, requirementId, listOf("The general liability aggregate is at least USD 5,000,000."))

        val stored = jdbc.queryForMap(
            "SELECT criterion_text, criteria_version, verdict, source FROM criteria_evaluation WHERE document_submission_id = ?",
            world.submissionId,
        )
        assertEquals(1, stored["criteria_version"])
        assertEquals("FAIL", stored["verdict"])
        assertEquals("REVIEWER", stored["source"])
        // "What was this document held to in March" still has an answer.
        assertTrue((stored["criterion_text"] as String).contains("2,000,000"))
    }

    @Test
    fun `a failed criterion becomes the rejection, in Acme's own words`() {
        val ops = staffActor(Role.OPS)
        val reviewer = staffActor(Role.OPS)
        val requirementId = requirement()
        criteriaService.author(ops, requirementId, listOf("The general liability aggregate is at least USD 2,000,000."))

        val world = supplierWithSubmission(ops)
        val criterion = criteriaService.checklist(ops, world.submissionId).criteria.single()
        criteriaService.judge(
            ops,
            world.submissionId,
            criterion.criterionId,
            "FAIL",
            "General liability aggregate: USD 1,000,000",
        )

        val note = criteriaService.rejectionNoteFor(ops, world.submissionId, criterion.criterionId)

        // Not "rejected — incorrect information", which is the wording that
        // turns one resubmission into three.
        assertTrue(note.contains("at least USD 2,000,000"), note)
        assertTrue(note.contains("USD 1,000,000"), note)

        // Grounded in the criterion itself, not in a catalog code that happens
        // to be nearby. Before this was modelled properly the UI sent a fixed
        // code with every criterion-based rejection, so a supplier whose
        // certificate was unsigned was told their coverage was too low.
        review.reject(reviewer, world.submissionId, RejectionGrounds.Criterion(criterion.criterionId), note)

        val checklist = suppliers.checklist(world.supplierUser, world.supplierId)
        val entry = checklist.programs.single().neededForThisProgram
            .single { it.documentTypeCode == "CERTIFICATE_OF_INSURANCE" }

        // The supplier reads Acme's own wording as the reason, with the
        // evidence beneath it.
        assertEquals(criterion.text, entry.submission?.rejectionReasonLabel)
        assertTrue(entry.submission!!.rejectionNote!!.contains("at least USD 2,000,000"))
    }

    @Test
    fun `a requirement with no criteria is normal, not broken`() {
        val ops = staffActor(Role.OPS)
        requirement()
        val world = supplierWithSubmission(ops)

        val checklist = criteriaService.checklist(ops, world.submissionId)

        // Review falls back to a person reading the document, exactly as it
        // works today. Nothing here should suggest a misconfiguration.
        assertTrue(checklist.empty)
        assertEquals(0, checklist.criteriaVersion)
    }

    @Test
    fun `nothing is judged until somebody judges it`() {
        val ops = staffActor(Role.OPS)
        val requirementId = requirement()
        criteriaService.author(ops, requirementId, listOf("Workers' compensation coverage is present."))
        val world = supplierWithSubmission(ops)

        val criterion = criteriaService.checklist(ops, world.submissionId).criteria.single()

        // A FAIL never auto-rejects and a PASS never auto-approves; an unjudged
        // criterion is simply unjudged.
        assertNull(criterion.verdict)
        assertNull(criterion.source)
    }

    @Test
    fun `criteria are one checkable statement, not a paragraph`() {
        val ops = staffActor(Role.OPS)
        val requirementId = requirement()

        val failure = assertFailsWith<InvalidRequestException> {
            criteriaService.author(ops, requirementId, listOf("x".repeat(400)))
        }
        assertTrue(failure.message!!.contains("one checkable statement"), failure.message!!)
    }

    @Test
    fun `only ops authors and judges`() {
        val ops = staffActor(Role.OPS)
        val requirementId = requirement()
        criteriaService.author(ops, requirementId, listOf("Workers' compensation coverage is present."))
        val world = supplierWithSubmission(ops)

        assertFailsWith<AccessDeniedException> {
            criteriaService.forRequirement(staffActor(Role.PROGRAM_MANAGER), requirementId)
        }
        assertFailsWith<AccessDeniedException> {
            criteriaService.checklist(world.supplierUser, world.submissionId)
        }
    }

    // -- helpers --------------------------------------------------------------

    private data class World(val supplierId: UUID, val supplierUser: Actor, val submissionId: UUID)

    private fun unique() = UUID.randomUUID().toString().take(8)

    private fun staffActor(role: Role): Actor {
        val email = "${role.name.lowercase()}-${unique()}@acme-msp.example"
        val id = users.insert(email, "Test ${role.name}", role, null, UserStatus.ACTIVE, passwordEncoder.hash(PASSWORD))
        return Actor(id, email, "Test ${role.name}", role, null)
    }

    private var programId: UUID? = null

    /** One program requiring a certificate, reused across the assertions in a test. */
    private fun requirement(): UUID {
        val id = catalog.insertProgram("CRITERIA_${unique()}", "Criteria Program", null)
        programId = id
        val coi = catalog.documentTypes().first { it.code == "CERTIFICATE_OF_INSURANCE" }
        catalog.addRequirement(id, coi.id, """{"generalLiabilityMinimum": 2000000}""")
        return criteria.requirementFor(id, "CERTIFICATE_OF_INSURANCE")!!
    }

    private fun supplierWithSubmission(ops: Actor): World {
        val contactEmail = "owner-${unique()}@example.test"
        val supplier = suppliers.createAndInvite(
            ops,
            NewSupplierRequest("Criteria Co ${unique()}", "Robin Fell", contactEmail, listOf(programId!!)),
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
                issuedOn = LocalDate.now().minusMonths(1),
                expiresOn = LocalDate.now().plusMonths(8),
            ),
        )
        return World(supplier.profile.id, supplierUser, submissionId)
    }
}
