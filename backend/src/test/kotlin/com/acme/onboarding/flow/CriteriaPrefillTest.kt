package com.acme.onboarding.flow

import com.acme.onboarding.adapter.persistence.CatalogRepository
import com.acme.onboarding.adapter.persistence.CriteriaRepository
import com.acme.onboarding.adapter.persistence.UserRepository
import com.acme.onboarding.application.auth.InvitationService
import com.acme.onboarding.application.criteria.CriteriaEvaluator
import com.acme.onboarding.application.criteria.CriteriaPrefillService
import com.acme.onboarding.application.criteria.CriteriaReviewService
import com.acme.onboarding.application.criteria.EvaluationRequest
import com.acme.onboarding.application.criteria.ModelVerdict
import com.acme.onboarding.application.document.DocumentService
import com.acme.onboarding.application.document.UploadRequest
import com.acme.onboarding.application.supplier.NewSupplierRequest
import com.acme.onboarding.application.supplier.ProfileUpdateRequest
import com.acme.onboarding.application.supplier.SupplierService
import com.acme.onboarding.application.support.hash
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
import kotlin.test.assertTrue

/**
 * The gate between a supplier's documents and a third-party model.
 *
 * This is the test that has to exist. Criteria prefill sends a document to
 * Anthropic, and the rule that a Restricted document — a W-9, with a taxpayer
 * identification number on it — is never one of them is enforced in code rather
 * than in configuration. A test that only proved the happy path would prove the
 * feature works while leaving the part Acme's compliance function actually cares
 * about unchecked.
 *
 * The evaluator is a stand-in that records what it was asked to look at, which
 * is the only way to assert the negative: not merely that the refusal happened,
 * but that nothing was transmitted before it.
 */
@Testcontainers
@SpringBootTest(
    properties = [
        "acme.demo.seed-on-startup=false",
        // The real bean is chosen by whether an API key is configured; this
        // replaces it with one that answers without a network call.
        "spring.main.allow-bean-definition-overriding=true",
    ],
)
@Import(CriteriaPrefillTest.StandInModel::class)
class CriteriaPrefillTest {

    /**
     * A model that never sees a network, and remembers everything handed to it.
     */
    class RecordingEvaluator : CriteriaEvaluator {
        val seen = mutableListOf<EvaluationRequest>()

        override val available = true
        override val model = "stand-in-model"

        override fun evaluate(request: EvaluationRequest): List<ModelVerdict> {
            seen += request
            return request.criteria.map {
                ModelVerdict(
                    criterionId = it.criterionId,
                    verdict = "FAIL",
                    evidence = "General liability aggregate: USD 1,000,000",
                    confidence = 0.91,
                )
            }
        }
    }

    @TestConfiguration
    class StandInModel {
        @Bean
        fun criteriaEvaluator(): CriteriaEvaluator = RecordingEvaluator()
    }

    companion object {
        @Container
        @ServiceConnection
        @JvmStatic
        val postgres: PostgreSQLContainer = PostgreSQLContainer("postgres:17-alpine")

        private const val PASSWORD = "Onboarding2026!"
        private val PDF = "%PDF-1.4\nprefilled by a test\n%%EOF".toByteArray(Charsets.US_ASCII)
    }

    @Autowired private lateinit var prefill: CriteriaPrefillService
    @Autowired private lateinit var criteriaService: CriteriaReviewService
    @Autowired private lateinit var criteria: CriteriaRepository
    @Autowired private lateinit var suppliers: SupplierService
    @Autowired private lateinit var documents: DocumentService
    @Autowired private lateinit var invitations: InvitationService
    @Autowired private lateinit var users: UserRepository
    @Autowired private lateinit var catalog: CatalogRepository
    @Autowired private lateinit var passwordEncoder: PasswordEncoder
    @Autowired private lateinit var evaluator: CriteriaEvaluator
    @Autowired private lateinit var jdbc: JdbcTemplate

    private val recorder get() = evaluator as RecordingEvaluator

    @Test
    fun `a Restricted document is refused, and nothing is transmitted`() {
        val ops = staffActor(Role.OPS)
        val program = programRequiring("W9", "CERTIFICATE_OF_INSURANCE")
        criteriaService.author(
            ops,
            criteria.requirementFor(program, "W9")!!,
            listOf("The name on the form matches the company's legal name."),
        )

        val world = supplierWith(ops, program, "W9")
        val before = recorder.seen.size

        val refusal = assertFailsWith<AccessDeniedException> { prefill.prefill(ops, world.submissionId) }

        assertTrue(refusal.message!!.contains("Restricted"), refusal.message!!)
        assertEquals(before, recorder.seen.size, "a Restricted document must not reach the model at all")

        // The screen is told the same thing, so the button is never offered for
        // a W-9 in the first place — the refusal is the second line of defence,
        // not the only one.
        assertFalse(criteriaService.checklist(ops, world.submissionId).modelAvailable)

        // And nothing was recorded as disclosed, because nothing was.
        assertEquals(0, disclosures(world.supplierId))
    }

    @Test
    fun `a Confidential document is prefilled, recorded as disclosed, and still decided by a person`() {
        val ops = staffActor(Role.OPS)
        val program = programRequiring("CERTIFICATE_OF_INSURANCE")
        criteriaService.author(
            ops,
            criteria.requirementFor(program, "CERTIFICATE_OF_INSURANCE")!!,
            listOf("The general liability aggregate is at least USD 2,000,000."),
        )

        val world = supplierWith(ops, program, "CERTIFICATE_OF_INSURANCE")
        val checklist = prefill.prefill(ops, world.submissionId)

        val judged = checklist.criteria.single()
        assertEquals("FAIL", judged.verdict)
        assertEquals("MODEL", judged.source)
        assertEquals(0.91, judged.confidence)

        // The model gets the document and the criteria, and the criteria carry
        // their identifiers so a verdict cannot be about something Acme did not
        // ask.
        val sent = recorder.seen.last()
        assertEquals(1, sent.criteria.size)
        assertEquals(judged.criterionId, sent.criteria.single().criterionId)
        assertTrue(sent.bytes.isNotEmpty())

        // A FAIL is a suggestion, not a decision: the document is still pending.
        assertEquals("PENDING", status(world.submissionId))

        // Transmission to a third party is an event in the supplier's own chain,
        // naming the processor and the model.
        assertEquals(1, disclosures(world.supplierId))
        val disclosed = suppliers.activity(ops, world.supplierId).first { it.action == "DOCUMENT_DISCLOSED" }
        assertTrue(disclosed.afterState!!.contains("stand-in-model"), disclosed.afterState!!)
        assertTrue(disclosed.afterState!!.contains("Anthropic"), disclosed.afterState!!)
    }

    // -- helpers --------------------------------------------------------------

    private data class World(val supplierId: UUID, val supplierUser: Actor, val submissionId: UUID)

    private fun unique() = UUID.randomUUID().toString().take(8)

    private fun staffActor(role: Role): Actor {
        val email = "${role.name.lowercase()}-${unique()}@acme-msp.example"
        val id = users.insert(email, "Test ${role.name}", role, null, UserStatus.ACTIVE, passwordEncoder.hash(PASSWORD))
        return Actor(id, email, "Test ${role.name}", role, null)
    }

    private fun programRequiring(vararg documentTypeCodes: String): UUID {
        val id = catalog.insertProgram("PREFILL_${unique()}", "Prefill Program", null)
        val types = catalog.documentTypes().associateBy { it.code }
        documentTypeCodes.forEach { catalog.addRequirement(id, types.getValue(it).id, "{}") }
        return id
    }

    private fun disclosures(supplierId: UUID): Int =
        jdbc.queryForObject(
            "SELECT count(*) FROM activity_event WHERE chain_key = ? AND action = 'DOCUMENT_DISCLOSED'",
            Int::class.java,
            supplierId.toString(),
        )!!

    private fun status(submissionId: UUID): String =
        jdbc.queryForObject(
            "SELECT status FROM document_submission WHERE id = ?",
            String::class.java,
            submissionId,
        )!!

    private fun supplierWith(ops: Actor, programId: UUID, documentTypeCode: String): World {
        val contactEmail = "owner-${unique()}@example.test"
        val supplier = suppliers.createAndInvite(
            ops,
            NewSupplierRequest("Prefill Co ${unique()}", "Robin Fell", contactEmail, listOf(programId)),
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
                issuedOn = LocalDate.now().minusMonths(1),
                expiresOn = if (documentTypeCode == "CERTIFICATE_OF_INSURANCE") LocalDate.now().plusMonths(8) else null,
            ),
        )
        return World(supplier.profile.id, supplierUser, submissionId)
    }
}
