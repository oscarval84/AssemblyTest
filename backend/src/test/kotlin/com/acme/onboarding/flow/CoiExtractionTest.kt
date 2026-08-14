package com.acme.onboarding.flow

import com.acme.onboarding.adapter.persistence.CatalogRepository
import com.acme.onboarding.adapter.persistence.SubmissionRepository
import com.acme.onboarding.adapter.persistence.UserRepository
import com.acme.onboarding.application.auth.InvitationService
import com.acme.onboarding.application.compliance.ComplianceSweepService
import com.acme.onboarding.application.document.DocumentReviewService
import com.acme.onboarding.application.document.DocumentService
import com.acme.onboarding.application.document.UploadRequest
import com.acme.onboarding.application.extraction.CoiExtractionService
import com.acme.onboarding.application.extraction.CoiExtractor
import com.acme.onboarding.application.extraction.CoiFields
import com.acme.onboarding.application.extraction.ExtractionOutcome
import com.acme.onboarding.application.extraction.ExtractionRequest
import com.acme.onboarding.application.supplier.NewSupplierRequest
import com.acme.onboarding.application.supplier.ProfileUpdateRequest
import com.acme.onboarding.application.supplier.SupplierService
import com.acme.onboarding.application.support.InvalidRequestException
import com.acme.onboarding.application.support.hash
import com.acme.onboarding.domain.compliance.ComplianceEvaluator
import com.acme.onboarding.domain.extraction.CertificateFlag
import com.acme.onboarding.domain.user.AccessDeniedException
import com.acme.onboarding.domain.user.Actor
import com.acme.onboarding.domain.user.Role
import com.acme.onboarding.domain.user.UserStatus
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.context.TestConfiguration
import org.springframework.boot.testcontainers.service.connection.ServiceConnection
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Import
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
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Reading a certificate, and disagreeing with the person who uploaded it.
 *
 * The stretch goal's whole value is in the disagreement. A supplier types the
 * expiry date at upload and the compliance engine runs on it; nobody checked it
 * against the document, and an expiry date wrong by two months is the shape of
 * the failure that let a supplier work on a lapsed certificate twice.
 *
 * So these tests are about three things: that a Restricted document never
 * reaches the model, that a disagreement is surfaced rather than silently
 * resolved, and that correcting it is an act by a person which the compliance
 * engine then acts on.
 */
@Testcontainers
@SpringBootTest(
    properties = [
        "acme.demo.seed-on-startup=false",
        "spring.main.allow-bean-definition-overriding=true",
    ],
)
@Import(CoiExtractionTest.StandInModel::class)
class CoiExtractionTest {

    /** A model that never sees a network, and remembers what it was shown. */
    class RecordingExtractor : CoiExtractor {
        val seen = mutableListOf<ExtractionRequest>()
        var fields = CoiFields()
        var confidence: Double? = 0.88

        override val available = true
        override val model = "stand-in-model"

        override fun extract(request: ExtractionRequest): ExtractionOutcome {
            seen += request
            return ExtractionOutcome(fields, confidence)
        }
    }

    @TestConfiguration
    class StandInModel {
        @Bean
        fun coiExtractor(): CoiExtractor = RecordingExtractor()
    }

    companion object {
        @Container
        @ServiceConnection
        @JvmStatic
        val postgres: PostgreSQLContainer = PostgreSQLContainer("postgres:17-alpine")

        private const val PASSWORD = "Onboarding2026!"
        private val PDF = "%PDF-1.4\nread by a test\n%%EOF".toByteArray(Charsets.US_ASCII)
    }

    @Autowired private lateinit var extraction: CoiExtractionService
    @Autowired private lateinit var extractor: CoiExtractor
    @Autowired private lateinit var suppliers: SupplierService
    @Autowired private lateinit var documents: DocumentService
    @Autowired private lateinit var submissions: SubmissionRepository
    @Autowired private lateinit var review: DocumentReviewService
    @Autowired private lateinit var sweep: ComplianceSweepService
    @Autowired private lateinit var evaluator: ComplianceEvaluator
    @Autowired private lateinit var invitations: InvitationService
    @Autowired private lateinit var users: UserRepository
    @Autowired private lateinit var catalog: CatalogRepository
    @Autowired private lateinit var passwordEncoder: PasswordEncoder
    @Autowired private lateinit var jdbc: JdbcTemplate

    private val model get() = extractor as RecordingExtractor

    @Test
    fun `a W-9 is never sent to the model, and the button is never offered for one`() {
        val ops = staffActor(Role.OPS)
        val program = programRequiring("W9", "CERTIFICATE_OF_INSURANCE")
        val world = supplierWith(ops, program, "W9")
        val before = model.seen.size

        val refusal = assertFailsWith<AccessDeniedException> { extraction.extract(ops, world.submissionId) }

        assertTrue(refusal.message!!.contains("Restricted"), refusal.message!!)
        assertEquals(before, model.seen.size, "a Restricted document must not reach the model at all")
        assertFalse(extraction.current(ops, world.submissionId).available)
    }

    @Test
    fun `extraction disagrees with the date the supplier typed rather than overwriting it`() {
        val ops = staffActor(Role.OPS)
        val program = programRequiring("CERTIFICATE_OF_INSURANCE")
        val typed = evaluator.today().plusMonths(8)
        val onTheDocument = typed.minusMonths(2)

        val world = supplierWith(ops, program, "CERTIFICATE_OF_INSURANCE", expiresOn = typed)
        model.fields = certificate(expiresOn = onTheDocument)

        val view = extraction.extract(ops, world.submissionId)

        assertTrue(view.expiryDisagrees)
        assertEquals(onTheDocument, view.fields?.expiresOn)
        assertEquals(typed, view.recordedExpiry)

        // Read, not written. The compliance engine is still running on the
        // supplier's date until a person says otherwise.
        assertEquals(typed, submissions.findById(world.submissionId)!!.expiresOn)

        val disagreement = view.findings.single { it.flag == CertificateFlag.EXPIRY_MISMATCH }
        assertTrue(disagreement.detail.contains(onTheDocument.toString()), disagreement.detail)
        assertTrue(disagreement.detail.contains(typed.toString()), disagreement.detail)

        // Transmission to a third party is recorded before the call, and what
        // was read is recorded after it.
        val actions = suppliers.activity(ops, world.supplierId).map { it.action }
        assertTrue(actions.contains("DOCUMENT_DISCLOSED"), actions.toString())
        assertTrue(actions.contains("DOCUMENT_FIELDS_EXTRACTED"), actions.toString())
    }

    @Test
    fun `a reviewer applies the certificate's date, and the compliance engine acts on it`() {
        val ops = staffActor(Role.OPS)
        val program = programRequiring("CERTIFICATE_OF_INSURANCE")

        // Typed as comfortably in the future; the certificate says it lapsed
        // yesterday. This is the pair that cost the client twice.
        val typed = evaluator.today().plusMonths(8)
        val actual = evaluator.today().minusDays(1)

        val world = supplierWith(ops, program, "CERTIFICATE_OF_INSURANCE", expiresOn = typed)
        model.fields = certificate(expiresOn = actual)
        extraction.extract(ops, world.submissionId)

        val corrected = extraction.applyExtractedExpiry(ops, world.submissionId)

        assertEquals(actual, corrected.recordedExpiry)
        assertFalse(corrected.expiryDisagrees)
        assertEquals(actual, submissions.findById(world.submissionId)!!.expiresOn)

        // Both values, because "why did this supplier's expiry move" must have
        // an answer that is not "the model".
        val event = suppliers.activity(ops, world.supplierId).first { it.action == "DOCUMENT_EXPIRY_CORRECTED" }
        assertEquals(ops.label, event.actorLabel)
        assertTrue(event.beforeState!!.contains(typed.toString()), event.beforeState!!)
        assertTrue(event.afterState!!.contains(actual.toString()), event.afterState!!)

        // And the correction reaches the engine. The sweep tracks approved
        // documents, because an unreviewed one is not yet an obligation — so
        // approve it as the reviewer would, and the certificate that was going
        // to be ignored for eight months is now in front of the ops team.
        review.approve(ops, world.submissionId)

        assertTrue(
            sweep.upcomingExpirations(ops, withinDays = 60).any { it.submissionId == world.submissionId },
            "a corrected expiry has to show up in the expirations the ops team works from",
        )
    }

    @Test
    fun `applying a date the certificate does not carry is refused`() {
        val ops = staffActor(Role.OPS)
        val program = programRequiring("CERTIFICATE_OF_INSURANCE")
        val world = supplierWith(ops, program, "CERTIFICATE_OF_INSURANCE")

        // A cropped scan: the model reports what it could not read as null
        // rather than guessing, and there is then nothing to apply.
        model.fields = certificate(expiresOn = null)
        model.confidence = 0.2
        val view = extraction.extract(ops, world.submissionId)

        assertNull(view.fields?.expiresOn)
        assertFalse(view.expiryDisagrees)
        assertFailsWith<InvalidRequestException> { extraction.applyExtractedExpiry(ops, world.submissionId) }
    }

    @Test
    fun `extraction is for certificates, and other documents are read against their criteria`() {
        val ops = staffActor(Role.OPS)
        val program = programRequiring("SUPPLIER_AGREEMENT", "CERTIFICATE_OF_INSURANCE")
        val world = supplierWith(ops, program, "SUPPLIER_AGREEMENT")

        val refusal = assertFailsWith<InvalidRequestException> { extraction.extract(ops, world.submissionId) }
        assertTrue(refusal.message!!.contains("acceptance criteria"), refusal.message!!)
    }

    // -- helpers --------------------------------------------------------------

    private data class World(val supplierId: UUID, val supplierUser: Actor, val submissionId: UUID)

    private fun unique() = UUID.randomUUID().toString().take(8)

    private fun certificate(expiresOn: LocalDate?) = CoiFields(
        insurer = "Continental Casualty",
        policyNumber = "GL-4471902",
        namedInsured = "Extraction Co",
        certificateHolder = "Acme Inc., 400 Market Street, Boston MA",
        generalLiabilityEachOccurrence = 1_000_000,
        generalLiabilityAggregate = 2_000_000,
        effectiveOn = evaluator.today().minusMonths(4),
        expiresOn = expiresOn,
        workersCompensationPresent = true,
        signed = true,
    )

    private fun staffActor(role: Role): Actor {
        val email = "${role.name.lowercase()}-${unique()}@acme-msp.example"
        val id = users.insert(email, "Test ${role.name}", role, null, UserStatus.ACTIVE, passwordEncoder.hash(PASSWORD))
        return Actor(id, email, "Test ${role.name}", role, null)
    }

    private fun programRequiring(vararg documentTypeCodes: String): UUID {
        val id = catalog.insertProgram("EXTRACT_${unique()}", "Extraction Program", null)
        val types = catalog.documentTypes().associateBy { it.code }
        documentTypeCodes.forEach { catalog.addRequirement(id, types.getValue(it).id, "{}") }
        return id
    }

    private fun supplierWith(
        ops: Actor,
        programId: UUID,
        documentTypeCode: String,
        expiresOn: LocalDate? = null,
    ): World {
        val contactEmail = "owner-${unique()}@example.test"
        val supplier = suppliers.createAndInvite(
            ops,
            NewSupplierRequest("Extraction Co", "Robin Fell", contactEmail, listOf(programId)),
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
                documentTypeCode = documentTypeCode,
                enrollmentId = null,
                originalFilename = "${documentTypeCode.lowercase()}.pdf",
                declaredContentType = "application/pdf",
                bytes = PDF,
                issuedOn = evaluator.today().minusMonths(4),
                expiresOn = expiresOn ?: if (documentTypeCode == "CERTIFICATE_OF_INSURANCE") {
                    evaluator.today().plusMonths(8)
                } else {
                    null
                },
            ),
        )
        return World(supplier.profile.id, supplierUser, submissionId)
    }
}
