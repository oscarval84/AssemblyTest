package com.acme.onboarding.application.document

import com.acme.onboarding.adapter.persistence.CatalogRepository
import com.acme.onboarding.adapter.persistence.DocumentTypeRecord
import com.acme.onboarding.adapter.persistence.EnrollmentRepository
import com.acme.onboarding.adapter.persistence.NewSubmission
import com.acme.onboarding.adapter.persistence.SubmissionRecord
import com.acme.onboarding.adapter.persistence.SubmissionRepository
import com.acme.onboarding.adapter.persistence.SupplierRepository
import com.acme.onboarding.application.audit.ActivityRecorder
import com.acme.onboarding.application.audit.AuditAction
import com.acme.onboarding.application.onboarding.StageProgression
import com.acme.onboarding.application.supplier.SupplierAssembler
import com.acme.onboarding.application.support.InvalidRequestException
import com.acme.onboarding.application.support.NotFoundException
import com.acme.onboarding.config.AcmeProperties
import com.acme.onboarding.domain.compliance.DocumentScope
import com.acme.onboarding.domain.compliance.SubmissionStatus
import com.acme.onboarding.domain.document.UploadValidation
import com.acme.onboarding.domain.user.Actor
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.net.URI
import java.security.MessageDigest
import java.time.LocalDate
import java.util.UUID

/** An upload as it arrives, before anything has been trusted about it. */
data class UploadRequest(
    val supplierId: UUID,
    val documentTypeCode: String,
    val enrollmentId: UUID?,
    val originalFilename: String,
    val declaredContentType: String?,
    val bytes: ByteArray,
    val issuedOn: LocalDate?,
    val expiresOn: LocalDate?,
)

/**
 * How the caller gets at the bytes.
 *
 * Cloud Storage answers with a short-lived signed URL; the local store has no
 * such thing and the response streams instead. Both paths run the same
 * authorization and write the same access event first — which is the part an
 * auditor asks about.
 */
sealed interface DownloadResult {
    data class Redirect(val uri: URI) : DownloadResult
    data class Streamed(val bytes: ByteArray, val contentType: String, val filename: String) : DownloadResult
}

@Service
class DocumentService(
    private val suppliers: SupplierRepository,
    private val submissions: SubmissionRepository,
    private val enrollments: EnrollmentRepository,
    private val catalog: CatalogRepository,
    private val store: DocumentStore,
    private val assembler: SupplierAssembler,
    private val progression: StageProgression,
    private val recorder: ActivityRecorder,
    private val properties: AcmeProperties,
) {

    @Transactional
    fun upload(actor: Actor, request: UploadRequest): UUID {
        actor.requireCanEditSupplier(request.supplierId)
        val supplier = suppliers.findById(request.supplierId)
            ?: throw NotFoundException("That supplier no longer exists.")

        val type = catalog.documentTypeByCode(request.documentTypeCode)
            ?: throw NotFoundException("We do not collect a document of that type.")

        val enrollmentId = resolveSlot(type, request)
        requireExpiryWhenNeeded(type, request.expiresOn)

        val accepted = when (val verdict = UploadValidation.validate(
            declaredContentType = request.declaredContentType,
            sizeBytes = request.bytes.size.toLong(),
            head = request.bytes.copyOfRange(0, minOf(request.bytes.size, HEAD_BYTES)),
        )) {
            is UploadValidation.Result.Rejected ->
                throw InvalidRequestException(verdict.message, verdict.code.name)

            is UploadValidation.Result.Accepted -> verdict
        }

        // Stored under a generated key, never the client's filename: a crafted
        // name must not be able to influence where the object lands.
        val storageKey = storageKey(request.supplierId, type.code, accepted.contentType)
        store.put(storageKey, request.bytes, accepted.contentType)

        val version = submissions.supersedeCurrent(request.supplierId, type.id, enrollmentId) + 1
        val submissionId = submissions.insert(
            NewSubmission(
                supplierId = request.supplierId,
                documentTypeId = type.id,
                enrollmentId = enrollmentId,
                version = version,
                storageKey = storageKey,
                originalFilename = request.originalFilename.take(255),
                contentType = accepted.contentType,
                sizeBytes = request.bytes.size.toLong(),
                checksumSha256 = sha256(request.bytes),
                issuedOn = request.issuedOn,
                expiresOn = request.expiresOn,
                uploadedBy = actor.userId,
            ),
        )

        recorder.record(
            action = AuditAction.DOCUMENT_UPLOADED,
            subjectType = "DOCUMENT",
            subjectId = submissionId,
            actor = actor,
            supplierId = request.supplierId,
            after = mapOf(
                "documentType" to type.code,
                "version" to version,
                "filename" to request.originalFilename,
                "sizeBytes" to request.bytes.size,
                "expiresOn" to request.expiresOn?.toString(),
            ),
        )

        progression.afterDocumentChange(assembler.snapshot(suppliers.findById(request.supplierId)!!), actor)
        return submissionId
    }

    /**
     * Authorises, records the access, and only then hands over the bytes.
     *
     * Auditing *reads* is the unusual part and the deliberate one. "Who looked at
     * this supplier's banking form, and when" is a question Acme's clients will
     * ask during an audit, and no bucket-level grant can answer it.
     */
    @Transactional
    fun download(actor: Actor, submissionId: UUID): DownloadResult {
        val submission = submissions.findById(submissionId)
            ?: throw NotFoundException("That document is no longer available.")
        actor.requireAccessTo(submission.supplierId)

        recorder.record(
            action = AuditAction.DOCUMENT_ACCESSED,
            subjectType = "DOCUMENT",
            subjectId = submission.id,
            actor = actor,
            supplierId = submission.supplierId,
            after = mapOf(
                "documentType" to submission.documentTypeCode,
                "version" to submission.version,
                "classification" to submission.classification,
            ),
        )

        val signed = store.signedUrl(submission.storageKey, properties.storage.signedUrlTtl)
        return if (signed != null) {
            DownloadResult.Redirect(signed)
        } else {
            DownloadResult.Streamed(
                bytes = store.read(submission.storageKey),
                contentType = submission.contentType,
                filename = submission.originalFilename,
            )
        }
    }

    @Transactional(readOnly = true)
    fun versions(actor: Actor, supplierId: UUID, documentTypeCode: String, enrollmentId: UUID?): List<SubmissionRecord> {
        actor.requireAccessTo(supplierId)
        val type = catalog.documentTypeByCode(documentTypeCode)
            ?: throw NotFoundException("We do not collect a document of that type.")
        return submissions.listVersions(supplierId, type.id, enrollmentId)
    }

    /**
     * Which requirement slot this upload fills.
     *
     * A supplier-scope document has no enrollment, and that is the mechanism
     * behind the reuse the client asked for: one W-9 satisfies every program at
     * once because it is stored against the company, not against a program.
     */
    private fun resolveSlot(type: DocumentTypeRecord, request: UploadRequest): UUID? =
        when (type.scope) {
            DocumentScope.SUPPLIER -> null

            DocumentScope.PROGRAM -> {
                val enrollmentId = request.enrollmentId
                    ?: throw InvalidRequestException(
                        "${type.name} is collected per program, so tell us which program this one is for.",
                    )
                val enrollment = enrollments.listForSupplier(request.supplierId)
                    .firstOrNull { it.id == enrollmentId }
                    ?: throw InvalidRequestException("This supplier is not enrolled in that program.")
                enrollment.id
            }
        }

    private fun requireExpiryWhenNeeded(type: DocumentTypeRecord, expiresOn: LocalDate?) {
        if (!type.expiring) return
        if (expiresOn == null) {
            throw InvalidRequestException(
                "Enter the expiry date printed on the ${type.name.lowercase()}. " +
                    "It is what tells us when to ask you for a new one.",
            )
        }
    }

    private fun storageKey(supplierId: UUID, typeCode: String, contentType: String): String {
        val extension = when (contentType) {
            "application/pdf" -> "pdf"
            "image/png" -> "png"
            else -> "jpg"
        }
        return "suppliers/$supplierId/${typeCode.lowercase()}/${UUID.randomUUID()}.$extension"
    }

    private fun sha256(bytes: ByteArray): String =
        MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }

    private companion object {
        /** Enough for every magic-byte signature we check. */
        const val HEAD_BYTES = 16
    }
}
