package com.acme.onboarding.flow

import com.acme.onboarding.adapter.persistence.CatalogRepository
import com.acme.onboarding.adapter.persistence.UserRepository
import com.acme.onboarding.application.admin.StaffAdministrationService
import com.acme.onboarding.application.audit.ActivityRecorder
import com.acme.onboarding.application.auth.AuthenticationService
import com.acme.onboarding.application.auth.InvitationService
import com.acme.onboarding.application.document.DocumentService
import com.acme.onboarding.application.document.SignatureService
import com.acme.onboarding.application.document.UploadRequest
import com.acme.onboarding.application.supplier.NewSupplierRequest
import com.acme.onboarding.application.supplier.ProfileUpdateRequest
import com.acme.onboarding.application.supplier.RequirementState
import com.acme.onboarding.application.supplier.SupplierService
import com.acme.onboarding.application.support.InvalidRequestException
import com.acme.onboarding.application.support.hash
import com.acme.onboarding.domain.audit.ChainVerification
import com.acme.onboarding.domain.onboarding.OnboardingStage
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
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The onboarding flow end to end, against a real PostgreSQL.
 *
 * These run through the application services rather than over HTTP, because what
 * is worth testing here is the part a controller cannot get right on its own:
 * the transitions, the reuse of supplier-scope documents, the audit chain, and
 * the authorization rules that decide which records a caller can resolve.
 *
 * Demo seeding is off. A test that starts from a curated world proves the world
 * was curated, not that the code works.
 */
@Testcontainers
@SpringBootTest(properties = ["acme.demo.seed-on-startup=false"])
class SupplierOnboardingFlowTest {

    companion object {
        @Container
        @ServiceConnection
        @JvmStatic
        val postgres: PostgreSQLContainer = PostgreSQLContainer("postgres:17-alpine")

        private const val PASSWORD = "Onboarding2026!"
        private val PDF = "%PDF-1.4\nseeded by a test\n%%EOF".toByteArray(Charsets.US_ASCII)
    }

    @Autowired private lateinit var suppliers: SupplierService
    @Autowired private lateinit var documents: DocumentService
    @Autowired private lateinit var signatures: SignatureService
    @Autowired private lateinit var invitations: InvitationService
    @Autowired private lateinit var authentication: AuthenticationService
    @Autowired private lateinit var staff: StaffAdministrationService
    @Autowired private lateinit var recorder: ActivityRecorder
    @Autowired private lateinit var users: UserRepository
    @Autowired private lateinit var catalog: CatalogRepository
    @Autowired private lateinit var passwordEncoder: PasswordEncoder
    @Autowired private lateinit var jdbc: JdbcTemplate

    @Test
    fun `a supplier goes from invitation to review, and the second program is mostly pre-filled`() {
        val ops = staffActor(Role.OPS)
        val programOne = program("PROGRAM_ONE_${unique()}", listOf("W9", "CERTIFICATE_OF_INSURANCE"))
        val programTwo = program("PROGRAM_TWO_${unique()}", listOf("W9", "CERTIFICATE_OF_INSURANCE", "BACKGROUND_CHECK_ATTESTATION"))

        val contactEmail = "owner-${unique()}@example.test"
        val supplier = suppliers.createAndInvite(
            ops,
            NewSupplierRequest("Test Staffing ${unique()}", "Robin Fell", contactEmail, listOf(programOne, programTwo)),
        )
        val supplierId = supplier.profile.id
        assertEquals(OnboardingStage.INVITED, supplier.profile.stage)

        // Accepting the invitation is the INVITED -> REGISTERED transition.
        val token = outstandingInvitationToken(contactEmail)
        val session = invitations.accept(token, PASSWORD)
        val supplierUser = session.actor
        assertEquals(OnboardingStage.REGISTERED, suppliers.detail(ops, supplierId).profile.stage)

        // A complete profile moves them on; an incomplete one would not.
        suppliers.updateProfile(supplierUser, supplierId, profile("Test Staffing"))
        assertEquals(OnboardingStage.PROFILE_SUBMITTED, suppliers.detail(ops, supplierId).profile.stage)

        upload(supplierUser, supplierId, "W9", null, null)
        assertEquals(OnboardingStage.DOCUMENTS_IN_PROGRESS, suppliers.detail(ops, supplierId).profile.stage)

        upload(supplierUser, supplierId, "CERTIFICATE_OF_INSURANCE", null, LocalDate.now().plusMonths(8))
        val enrollmentTwo = suppliers.detail(ops, supplierId).enrollments.first { it.programId == programTwo }
        upload(supplierUser, supplierId, "BACKGROUND_CHECK_ATTESTATION", enrollmentTwo.enrollmentId, LocalDate.now().plusMonths(11))

        // Everything submitted and nothing outstanding: the ball is with Acme.
        assertEquals(OnboardingStage.IN_REVIEW, suppliers.detail(ops, supplierId).profile.stage)

        val checklist = suppliers.checklist(supplierUser, supplierId)
        val second = checklist.programs.first { it.programId == programTwo }
        val w9 = second.neededForThisProgram.first { it.documentTypeCode == "W9" }

        // One W-9 satisfies both programs: the reuse is expressed by the
        // document being found, not by a copy being made.
        assertTrue(w9.shared)
        assertEquals(RequirementState.IN_REVIEW, w9.state)
        assertEquals(
            1,
            jdbc.queryForObject(
                """
                SELECT count(*) FROM document_submission s
                  JOIN document_type t ON t.id = s.document_type_id
                 WHERE s.supplier_id = ? AND t.code = 'W9' AND s.is_current
                """.trimIndent(),
                Int::class.java,
                supplierId,
            ),
        )
    }

    @Test
    fun `signing produces an executed document, not a checkbox`() {
        val ops = staffActor(Role.OPS)
        val programId = program("SIGN_${unique()}", listOf("SUPPLIER_AGREEMENT"))
        val contactEmail = "signer-${unique()}@example.test"
        val supplier = suppliers.createAndInvite(
            ops,
            NewSupplierRequest("Signing Co ${unique()}", "Alex Reed", contactEmail, listOf(programId)),
        )
        val supplierUser = invitations.accept(outstandingInvitationToken(contactEmail), PASSWORD).actor

        val submissionId = signatures.sign(
            supplierUser,
            SignatureService.SignRequest(supplier.profile.id, "SUPPLIER_AGREEMENT", null, "Alex Reed"),
        )

        val stored = jdbc.queryForMap(
            """
            SELECT r.typed_name, r.template_version, r.template_sha256, r.executed_sha256,
                   s.content_type, s.size_bytes, s.status
              FROM signature_record r
              JOIN document_submission s ON s.id = r.document_submission_id
             WHERE r.document_submission_id = ?
            """.trimIndent(),
            submissionId,
        )
        assertEquals("Alex Reed", stored["typed_name"])
        assertEquals("application/pdf", stored["content_type"])
        assertTrue((stored["size_bytes"] as Number).toLong() > 0)
        // The template hash is what answers "which text did they agree to" after
        // the template has moved on.
        assertEquals(64, (stored["template_sha256"] as String).length)
        assertEquals(64, (stored["executed_sha256"] as String).length)
    }

    @Test
    fun `expiring document types refuse an upload with no expiry date`() {
        val ops = staffActor(Role.OPS)
        val programId = program("EXPIRY_${unique()}", listOf("CERTIFICATE_OF_INSURANCE"))
        val contactEmail = "expiry-${unique()}@example.test"
        val supplier = suppliers.createAndInvite(
            ops,
            NewSupplierRequest("Expiry Co ${unique()}", "Sam Vale", contactEmail, listOf(programId)),
        )
        val supplierUser = invitations.accept(outstandingInvitationToken(contactEmail), PASSWORD).actor

        // Without this date the compliance engine has nothing to work with, and
        // an expired certificate goes unnoticed — which is what happened twice.
        val failure = assertFailsWith<InvalidRequestException> {
            upload(supplierUser, supplier.profile.id, "CERTIFICATE_OF_INSURANCE", null, null)
        }
        assertTrue(failure.message!!.contains("expiry date"), failure.message!!)
    }

    @Test
    fun `deactivating a user ends their session on the very next request`() {
        val admin = staffActor(Role.ADMIN)
        val email = "leaver-${unique()}@acme-msp.example"
        val userId = users.insert(email, "Jamie Leaver", Role.OPS, null, UserStatus.ACTIVE, passwordEncoder.hash(PASSWORD))

        val session = authentication.login(email, PASSWORD)
        assertNotNull(authentication.resolve(session.token))

        staff.deactivate(admin, userId)

        // Not "expires eventually" — gone now. This is the property server-side
        // sessions were chosen for.
        assertNull(authentication.resolve(session.token))
    }

    @Test
    fun `a supplier user cannot reach another supplier`() {
        val ops = staffActor(Role.OPS)
        val programId = program("SCOPE_${unique()}", listOf("W9"))

        val mineEmail = "mine-${unique()}@example.test"
        val mine = suppliers.createAndInvite(
            ops,
            NewSupplierRequest("Mine ${unique()}", "Mine Owner", mineEmail, listOf(programId)),
        )
        val theirs = suppliers.createAndInvite(
            ops,
            NewSupplierRequest("Theirs ${unique()}", "Their Owner", "theirs-${unique()}@example.test", listOf(programId)),
        )
        val supplierUser = invitations.accept(outstandingInvitationToken(mineEmail), PASSWORD).actor

        suppliers.checklist(supplierUser, mine.profile.id)
        assertFailsWith<AccessDeniedException> { suppliers.checklist(supplierUser, theirs.profile.id) }
        assertFailsWith<AccessDeniedException> { suppliers.list(supplierUser) }
    }

    @Test
    fun `the audit chain is intact after a full onboarding, and records document access`() {
        val ops = staffActor(Role.OPS)
        val programId = program("AUDIT_${unique()}", listOf("W9"))
        val contactEmail = "audit-${unique()}@example.test"
        val supplier = suppliers.createAndInvite(
            ops,
            NewSupplierRequest("Audited Co ${unique()}", "Pat Kerr", contactEmail, listOf(programId)),
        )
        val supplierId = supplier.profile.id
        val supplierUser = invitations.accept(outstandingInvitationToken(contactEmail), PASSWORD).actor

        suppliers.updateProfile(supplierUser, supplierId, profile("Audited Co"))
        val submissionId = upload(supplierUser, supplierId, "W9", null, null)
        documents.download(ops, submissionId)

        assertEquals(ChainVerification.Intact, recorder.verify(supplierId.toString()))

        val actions = suppliers.activity(ops, supplierId).map { it.action }
        assertTrue(actions.contains("DOCUMENT_ACCESSED"), actions.toString())
        assertTrue(actions.contains("SUPPLIER_PROFILE_UPDATED"), actions.toString())

        // Reading a document is a recorded fact, and the record names the reader.
        val access = suppliers.activity(ops, supplierId).first { it.action == "DOCUMENT_ACCESSED" }
        assertTrue(access.actorLabel.contains(ops.email), access.actorLabel)
    }

    @Test
    fun `the audit log refuses to be rewritten`() {
        val ops = staffActor(Role.OPS)
        val programId = program("TAMPER_${unique()}", listOf("W9"))
        val supplier = suppliers.createAndInvite(
            ops,
            NewSupplierRequest("Tamper Co ${unique()}", "Lee Ash", "tamper-${unique()}@example.test", listOf(programId)),
        )

        assertFailsWith<Exception> {
            jdbc.update(
                "UPDATE activity_event SET action = 'NOTHING_HAPPENED' WHERE chain_key = ?",
                supplier.profile.id.toString(),
            )
        }
    }

    // -- helpers --------------------------------------------------------------

    private fun unique() = UUID.randomUUID().toString().take(8)

    private fun staffActor(role: Role): Actor {
        val email = "${role.name.lowercase()}-${unique()}@acme-msp.example"
        val id = users.insert(email, "Test ${role.name}", role, null, UserStatus.ACTIVE, passwordEncoder.hash(PASSWORD))
        return Actor(id, email, "Test ${role.name}", role, null)
    }

    private fun program(code: String, documentTypeCodes: List<String>): UUID {
        val id = catalog.insertProgram(code, "Program $code", "Created by a test.")
        val types = catalog.documentTypes().associateBy { it.code }
        documentTypeCodes.forEach { catalog.addRequirement(id, types.getValue(it).id, "{}") }
        return id
    }

    /**
     * Invitation tokens are only ever stored hashed, so a test cannot read one
     * back out of the database. It re-derives the hash the same way the service
     * does, over candidate tokens — which is impossible for a 256-bit random
     * value, so instead the outbox is read: the link is in the email, exactly
     * where the supplier finds it.
     */
    private fun outstandingInvitationToken(email: String): String {
        val body = jdbc.queryForObject(
            """
            SELECT body_text FROM email_message
             WHERE lower(recipient_email) = lower(?)
             ORDER BY created_at DESC LIMIT 1
            """.trimIndent(),
            String::class.java,
            email,
        )!!
        return body.substringAfter("/invitation/").substringBefore('\n').trim()
    }

    private fun profile(legalName: String) = ProfileUpdateRequest(
        legalName = legalName,
        dbaName = null,
        entityType = "LLC",
        taxId = "12-3456789",
        addressLine1 = "1 Test Street",
        addressLine2 = null,
        city = "Boston",
        state = "MA",
        postalCode = "02115",
        primaryContactName = "Robin Fell",
        primaryContactEmail = "robin@example.test",
        primaryContactPhone = "617-555-0100",
    )

    private fun upload(
        actor: Actor,
        supplierId: UUID,
        typeCode: String,
        enrollmentId: UUID?,
        expiresOn: LocalDate?,
    ): UUID = documents.upload(
        actor,
        UploadRequest(
            supplierId = supplierId,
            documentTypeCode = typeCode,
            enrollmentId = enrollmentId,
            originalFilename = "$typeCode.pdf",
            declaredContentType = "application/pdf",
            bytes = PDF,
            issuedOn = LocalDate.now().minusMonths(1),
            expiresOn = expiresOn,
        ),
    )
}
