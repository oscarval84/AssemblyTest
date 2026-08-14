package com.acme.onboarding.flow

import com.acme.onboarding.adapter.persistence.CatalogRepository
import com.acme.onboarding.adapter.persistence.ExtractionRepository
import com.acme.onboarding.adapter.persistence.UserRepository
import com.acme.onboarding.application.auth.InvitationService
import com.acme.onboarding.application.document.DocumentService
import com.acme.onboarding.application.document.UploadRequest
import com.acme.onboarding.application.extraction.DocumentExtractionService
import com.acme.onboarding.application.extraction.DocumentExtractor
import com.acme.onboarding.application.extraction.ExtractionOutcome
import com.acme.onboarding.application.extraction.ExtractionRequest
import com.acme.onboarding.application.extraction.W9Fields
import com.acme.onboarding.application.supplier.NewSupplierRequest
import com.acme.onboarding.application.supplier.ProfileUpdateRequest
import com.acme.onboarding.application.supplier.SupplierService
import com.acme.onboarding.application.support.InvalidRequestException
import com.acme.onboarding.application.support.hash
import com.acme.onboarding.domain.compliance.ComplianceEvaluator
import com.acme.onboarding.domain.extraction.ExtractionFlag
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
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The same system as [DocumentExtractionTest], with Acme's decision made.
 *
 * One property is the difference — `acme.ai.w9-extraction-enabled=true` — and
 * that is the point being tested: enabling this is a setting Acme's own people
 * can turn, not a deploy they have to ask us for.
 *
 * The tests that matter here are the two halves of that bargain. Acme decides
 * whether the *document* is transmitted. We decide what may be *kept*, and the
 * answer is that a taxpayer identification number never is — no field, no
 * column, no JSON key, whatever this flag says.
 */
@Testcontainers
@SpringBootTest(
    properties = [
        "acme.demo.seed-on-startup=false",
        "spring.main.allow-bean-definition-overriding=true",
        "acme.ai.w9-extraction-enabled=true",
    ],
)
@Import(W9ExtractionTest.StandInModel::class)
class W9ExtractionTest {

    /**
     * A model that never sees a network.
     *
     * It answers with whatever [taxForm] holds — and note that there is no way
     * for this stand-in to return a taxpayer identification number even if it
     * wanted to, because [W9Fields] has nowhere to put one. That is the same
     * constraint the real adapter is under.
     */
    class RecordingExtractor : DocumentExtractor {
        val seen = mutableListOf<ExtractionRequest>()
        var taxForm = W9Fields()

        override val available = true
        override val model = "stand-in-model"

        override fun extract(request: ExtractionRequest): ExtractionOutcome {
            seen += request
            return ExtractionOutcome(w9 = taxForm, confidence = 0.9)
        }
    }

    @TestConfiguration
    class StandInModel {
        @Bean
        fun documentExtractor(): DocumentExtractor = RecordingExtractor()
    }

    companion object {
        @Container
        @ServiceConnection
        @JvmStatic
        val postgres: PostgreSQLContainer = PostgreSQLContainer("postgres:17-alpine")

        private const val PASSWORD = "Onboarding2026!"
        private const val TAX_ID = "12-3456789"
        private val PDF = "%PDF-1.4\nread by a test\n%%EOF".toByteArray(Charsets.US_ASCII)
    }

    @Autowired private lateinit var extraction: DocumentExtractionService
    @Autowired private lateinit var extractor: DocumentExtractor
    @Autowired private lateinit var extractions: ExtractionRepository
    @Autowired private lateinit var suppliers: SupplierService
    @Autowired private lateinit var documents: DocumentService
    @Autowired private lateinit var evaluator: ComplianceEvaluator
    @Autowired private lateinit var invitations: InvitationService
    @Autowired private lateinit var users: UserRepository
    @Autowired private lateinit var catalog: CatalogRepository
    @Autowired private lateinit var passwordEncoder: PasswordEncoder
    @Autowired private lateinit var jdbc: JdbcTemplate

    private val model get() = extractor as RecordingExtractor

    @Test
    fun `with the switch on the W-9 is read, and no taxpayer ID is stored anywhere`() {
        val ops = staffActor(Role.OPS)
        val world = supplierWith(ops)
        model.taxForm = W9Fields(
            legalName = "Extraction Co",
            businessName = null,
            taxClassification = "Limited liability company",
            address = "1 Test Street, Boston MA 02115",
            signed = true,
        )

        val view = extraction.extract(ops, world.submissionId)

        assertTrue(view.available)
        assertEquals("Limited liability company", view.w9?.taxClassification)
        assertEquals("1 Test Street, Boston MA 02115", view.w9?.address)
        // The form agrees with the profile on both name and entity type, so a
        // reviewer is shown a clean read rather than noise.
        assertTrue(view.findings.isEmpty(), view.findings.toString())

        // The row that is kept must not contain the number, in any form. The
        // supplier typed a real tax ID into their profile, so this would catch a
        // field that quietly carried it through.
        val stored = extractions.latestFor(world.submissionId)!!
        assertFalse(stored.extractedJson.contains(TAX_ID), stored.extractedJson)
        assertFalse(stored.extractedJson.contains("3456789"), stored.extractedJson)
        assertFalse(
            stored.extractedJson.lowercase().contains("taxid") ||
                stored.extractedJson.lowercase().contains("\"tin\""),
            stored.extractedJson,
        )
    }

    @Test
    fun `a W-9 filed under a different company is the finding worth having`() {
        val ops = staffActor(Role.OPS)
        val world = supplierWith(ops)

        // The owner's other company. Acme files this with the IRS under the
        // wrong entity, and nobody notices until a 1099 goes to the wrong place.
        model.taxForm = W9Fields(
            legalName = "Fell Holdings",
            taxClassification = "S Corporation",
            address = "1 Test Street, Boston MA 02115",
            signed = true,
        )

        val findings = extraction.extract(ops, world.submissionId).findings

        val name = findings.single { it.flag == ExtractionFlag.NAME_MISMATCH }
        assertTrue(name.detail.contains("Fell Holdings"), name.detail)
        assertTrue(name.detail.contains("Extraction Co"), name.detail)

        // And the entity type contradicts the profile's LLC, which is a second,
        // independent signal that this is somebody else's form.
        assertTrue(findings.any { it.flag == ExtractionFlag.ENTITY_TYPE_MISMATCH }, findings.toString())
    }

    @Test
    fun `transmitting a Restricted document names the switch that allowed it`() {
        val ops = staffActor(Role.OPS)
        val world = supplierWith(ops)
        model.taxForm = W9Fields(legalName = "Extraction Co", signed = true)

        extraction.extract(ops, world.submissionId)

        // An auditor sampling the log sees that a Restricted document left the
        // building, who sent it, and under which decision — without having to
        // correlate the date against a deploy.
        val disclosure = suppliers.activity(ops, world.supplierId).first { it.action == "DOCUMENT_DISCLOSED" }
        val recorded = disclosure.afterState!!
        assertEquals(ops.label, disclosure.actorLabel)
        assertTrue(recorded.contains("RESTRICTED"), recorded)
        assertTrue(recorded.contains("w9-extraction-enabled"), recorded)
    }

    @Test
    fun `a W-9 has no expiry date to apply`() {
        val ops = staffActor(Role.OPS)
        val world = supplierWith(ops)
        model.taxForm = W9Fields(legalName = "Extraction Co", signed = true)
        extraction.extract(ops, world.submissionId)

        val refusal = assertFailsWith<InvalidRequestException> {
            extraction.applyExtractedExpiry(ops, world.submissionId)
        }
        assertTrue(refusal.message!!.contains("no expiry date"), refusal.message!!)
    }

    // -- helpers --------------------------------------------------------------

    private data class World(val supplierId: UUID, val submissionId: UUID)

    private fun unique() = UUID.randomUUID().toString().take(8)

    private fun staffActor(role: Role): Actor {
        val email = "${role.name.lowercase()}-${unique()}@acme-msp.example"
        val id = users.insert(email, "Test ${role.name}", role, null, UserStatus.ACTIVE, passwordEncoder.hash(PASSWORD))
        return Actor(id, email, "Test ${role.name}", role, null)
    }

    /** A supplier who has filled in their profile and uploaded a W-9. */
    private fun supplierWith(ops: Actor): World {
        val programId = catalog.insertProgram("W9_${unique()}", "Tax Form Program", null)
        val types = catalog.documentTypes().associateBy { it.code }
        listOf("W9", "CERTIFICATE_OF_INSURANCE").forEach {
            catalog.addRequirement(programId, types.getValue(it).id, "{}")
        }

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
                taxId = TAX_ID,
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
                documentTypeCode = "W9",
                enrollmentId = null,
                originalFilename = "w9.pdf",
                declaredContentType = "application/pdf",
                bytes = PDF,
                issuedOn = evaluator.today().minusMonths(4),
                expiresOn = null,
            ),
        )
        return World(supplier.profile.id, submissionId)
    }
}
