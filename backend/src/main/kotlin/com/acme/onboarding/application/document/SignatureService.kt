package com.acme.onboarding.application.document

import com.acme.onboarding.adapter.persistence.CatalogRepository
import com.acme.onboarding.adapter.persistence.EnrollmentRepository
import com.acme.onboarding.adapter.persistence.NewSubmission
import com.acme.onboarding.adapter.persistence.SignatureRepository
import com.acme.onboarding.adapter.persistence.SubmissionRepository
import com.acme.onboarding.adapter.persistence.SupplierRepository
import com.acme.onboarding.application.audit.ActivityRecorder
import com.acme.onboarding.application.audit.AuditAction
import com.acme.onboarding.application.audit.RequestContext
import com.acme.onboarding.application.onboarding.StageProgression
import com.acme.onboarding.application.supplier.SupplierAssembler
import com.acme.onboarding.application.support.InvalidRequestException
import com.acme.onboarding.application.support.NotFoundException
import com.acme.onboarding.config.AcmeProperties
import com.acme.onboarding.domain.compliance.DocumentScope
import com.acme.onboarding.domain.compliance.SubmissionStatus
import com.acme.onboarding.domain.user.Actor
import org.springframework.core.io.ClassPathResource
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.security.MessageDigest
import java.time.Clock
import java.time.Instant
import java.util.UUID

/**
 * Signing produces a document, not a checkbox.
 *
 * The supplier types their name; the system renders a new PDF containing the
 * agreement text they were shown, a signature block naming them, the timestamp,
 * the originating address, and the SHA-256 of the template. That artifact is
 * stored immutably and linked from the signature record.
 *
 * The resulting submission is recorded as approved with no reviewer, and that is
 * intentional rather than an oversight: the document is Acme's own template,
 * executed in Acme's own product. There is nothing for ops to verify that the
 * system does not already know, and queueing it for review would train the team
 * to click approve without reading — which is exactly the habit that makes the
 * reviews that *do* matter worthless.
 */
@Service
class SignatureService(
    private val suppliers: SupplierRepository,
    private val submissions: SubmissionRepository,
    private val signatures: SignatureRepository,
    private val enrollments: EnrollmentRepository,
    private val catalog: CatalogRepository,
    private val store: DocumentStore,
    private val renderer: ExecutedAgreementRenderer,
    private val assembler: SupplierAssembler,
    private val progression: StageProgression,
    private val recorder: ActivityRecorder,
    private val requestContext: RequestContext,
    private val properties: AcmeProperties,
    private val clock: Clock,
) {

    data class SignRequest(
        val supplierId: UUID,
        val documentTypeCode: String,
        val enrollmentId: UUID?,
        val typedName: String,
    )

    /** The text and its hash, as the supplier is shown it before signing. */
    data class AgreementPreview(
        val templateVersion: String,
        val body: String,
        val templateSha256: String,
    )

    @Transactional(readOnly = true)
    fun preview(actor: Actor, supplierId: UUID, documentTypeCode: String): AgreementPreview {
        actor.requireAccessTo(supplierId)
        val supplier = suppliers.findById(supplierId)
            ?: throw NotFoundException("That supplier no longer exists.")
        val template = loadTemplate()
        return AgreementPreview(
            templateVersion = properties.agreement.templateVersion,
            body = resolve(template, supplier.legalName),
            templateSha256 = sha256(template.toByteArray(Charsets.UTF_8)),
        )
    }

    @Transactional
    fun sign(actor: Actor, request: SignRequest): UUID {
        actor.requireCanEditSupplier(request.supplierId)
        val supplier = suppliers.findById(request.supplierId)
            ?: throw NotFoundException("That supplier no longer exists.")

        val type = catalog.documentTypeByCode(request.documentTypeCode)
            ?: throw NotFoundException("We do not collect a document of that type.")
        if (!type.requiresSignature) {
            throw InvalidRequestException("${type.name} is uploaded, not signed.")
        }

        val typedName = request.typedName.trim()
        if (typedName.length < MINIMUM_SIGNATURE_LENGTH) {
            throw InvalidRequestException("Type your full name as your signature.")
        }

        val enrollmentId = when (type.scope) {
            DocumentScope.SUPPLIER -> null
            DocumentScope.PROGRAM -> request.enrollmentId
                ?: throw InvalidRequestException("Tell us which program this addendum is for.")
        }
        val programName = enrollmentId?.let { id ->
            enrollments.listForSupplier(supplier.id).firstOrNull { it.id == id }?.programName
                ?: throw InvalidRequestException("This supplier is not enrolled in that program.")
        }

        val template = loadTemplate()
        val templateSha = sha256(template.toByteArray(Charsets.UTF_8))
        val signedAt = Instant.now(clock)

        val pdf = renderer.render(
            ExecutedAgreement(
                templateVersion = properties.agreement.templateVersion,
                body = resolve(template, supplier.legalName),
                companyLegalName = supplier.legalName,
                programName = programName,
                signerName = actor.fullName,
                signerEmail = actor.email,
                typedName = typedName,
                signedAt = signedAt,
                signerIp = requestContext.ip(),
                signerUserAgent = requestContext.userAgent(),
                templateSha256 = templateSha,
            ),
        )

        val storageKey = "suppliers/${supplier.id}/${type.code.lowercase()}/${UUID.randomUUID()}.pdf"
        store.put(storageKey, pdf, "application/pdf")

        val version = submissions.supersedeCurrent(supplier.id, type.id, enrollmentId) + 1
        val submissionId = submissions.insert(
            NewSubmission(
                supplierId = supplier.id,
                documentTypeId = type.id,
                enrollmentId = enrollmentId,
                version = version,
                storageKey = storageKey,
                originalFilename = "${type.code.lowercase()}-executed-v$version.pdf",
                contentType = "application/pdf",
                sizeBytes = pdf.size.toLong(),
                checksumSha256 = sha256(pdf),
                issuedOn = null,
                expiresOn = null,
                uploadedBy = actor.userId,
                status = SubmissionStatus.APPROVED,
            ),
        )

        signatures.insert(
            documentSubmissionId = submissionId,
            signerUserId = actor.userId,
            typedName = typedName,
            signerIp = requestContext.ip(),
            signerUserAgent = requestContext.userAgent(),
            templateVersion = properties.agreement.templateVersion,
            templateSha256 = templateSha,
            executedStorageKey = storageKey,
            executedSha256 = sha256(pdf),
        )

        recorder.record(
            action = AuditAction.DOCUMENT_SIGNED,
            subjectType = "DOCUMENT",
            subjectId = submissionId,
            actor = actor,
            supplierId = supplier.id,
            after = mapOf(
                "documentType" to type.code,
                "typedName" to typedName,
                "templateVersion" to properties.agreement.templateVersion,
                "templateSha256" to templateSha,
            ),
        )

        progression.afterDocumentChange(assembler.snapshot(suppliers.findById(supplier.id)!!), actor)
        return submissionId
    }

    private fun loadTemplate(): String {
        val path = "agreements/supplier-agreement-${properties.agreement.templateVersion}.txt"
        val resource = ClassPathResource(path)
        if (!resource.exists()) {
            throw IllegalStateException("Agreement template $path is missing from the build")
        }
        return resource.inputStream.use { it.readBytes().toString(Charsets.UTF_8) }
    }

    private fun resolve(template: String, companyLegalName: String): String = template
        .replace("{{templateVersion}}", properties.agreement.templateVersion)
        .replace("{{companyLegalName}}", companyLegalName)

    private fun sha256(bytes: ByteArray): String =
        MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }

    private companion object {
        const val MINIMUM_SIGNATURE_LENGTH = 3
    }
}
