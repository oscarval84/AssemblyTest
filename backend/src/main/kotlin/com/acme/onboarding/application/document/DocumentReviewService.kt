package com.acme.onboarding.application.document

import com.acme.onboarding.adapter.persistence.CatalogRepository
import com.acme.onboarding.adapter.persistence.EnrollmentRepository
import com.acme.onboarding.adapter.persistence.RejectionReasonRecord
import com.acme.onboarding.adapter.persistence.SubmissionRecord
import com.acme.onboarding.adapter.persistence.SubmissionRepository
import com.acme.onboarding.adapter.persistence.SupplierRepository
import com.acme.onboarding.adapter.persistence.UserRepository
import com.acme.onboarding.application.audit.ActivityRecorder
import com.acme.onboarding.application.audit.AuditAction
import com.acme.onboarding.application.notification.Notifier
import com.acme.onboarding.application.onboarding.StageProgression
import com.acme.onboarding.application.supplier.SupplierAssembler
import com.acme.onboarding.application.support.InvalidRequestException
import com.acme.onboarding.application.support.NotFoundException
import com.acme.onboarding.domain.compliance.SubmissionStatus
import com.acme.onboarding.domain.onboarding.OnboardingStage
import com.acme.onboarding.domain.user.Actor
import com.acme.onboarding.domain.user.UserStatus
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.Clock
import java.time.Instant
import java.time.LocalDate
import java.util.UUID

/** One document waiting on Acme, with enough context to decide without leaving the queue. */
data class ReviewQueueItem(
    val submissionId: UUID,
    val supplierId: UUID,
    val supplierLegalName: String,
    val documentTypeCode: String,
    val documentTypeName: String,
    val classification: String,
    val programNames: List<String>,
    val version: Int,
    val originalFilename: String,
    val sizeBytes: Long,
    val issuedOn: LocalDate?,
    val expiresOn: LocalDate?,
    val uploadedAt: Instant,
    val uploadedByName: String?,
    /** Whole days this has been sitting with Acme. The number the team is measured on. */
    val waitingDays: Long,
    /** False when the caller uploaded it themselves and must hand it to a colleague. */
    val reviewableByCaller: Boolean,
)

/**
 * Ops' decision on a submitted document.
 *
 * Two rules shape everything here, and both come from what the client said went
 * wrong before. A rejection always carries a reason, because "rejected, no
 * reason given" is the experience suppliers described as faxing paperwork into a
 * void — the schema refuses to record one without a reason, and so does this.
 * And the approver is never the uploader: ops uploads on behalf of two-person
 * agencies, and that path must still force a second pair of eyes.
 */
@Service
class DocumentReviewService(
    private val submissions: SubmissionRepository,
    private val suppliers: SupplierRepository,
    private val enrollments: EnrollmentRepository,
    private val catalog: CatalogRepository,
    private val users: UserRepository,
    private val assembler: SupplierAssembler,
    private val progression: StageProgression,
    private val notifier: Notifier,
    private val recorder: ActivityRecorder,
    private val clock: Clock,
) {

    @Transactional(readOnly = true)
    fun queue(actor: Actor): List<ReviewQueueItem> {
        actor.requireOps()
        val now = Instant.now(clock)

        return submissions.listPendingReview().map { submission ->
            val supplier = suppliers.findById(submission.supplierId)
            ReviewQueueItem(
                submissionId = submission.id,
                supplierId = submission.supplierId,
                supplierLegalName = supplier?.legalName ?: "Unknown supplier",
                documentTypeCode = submission.documentTypeCode,
                documentTypeName = submission.documentTypeName,
                classification = submission.classification,
                programNames = programNamesFor(submission),
                version = submission.version,
                originalFilename = submission.originalFilename,
                sizeBytes = submission.sizeBytes,
                issuedOn = submission.issuedOn,
                expiresOn = submission.expiresOn,
                uploadedAt = submission.uploadedAt,
                uploadedByName = submission.uploadedByName,
                waitingDays = java.time.Duration.between(submission.uploadedAt, now).toDays(),
                reviewableByCaller = submission.uploadedBy != actor.userId,
            )
        }
    }

    @Transactional(readOnly = true)
    fun rejectionReasons(actor: Actor): List<RejectionReasonRecord> {
        actor.requireOps()
        return catalog.rejectionReasons()
    }

    @Transactional
    fun approve(actor: Actor, submissionId: UUID) {
        val submission = reviewable(actor, submissionId)

        submissions.recordReview(
            id = submission.id,
            status = SubmissionStatus.APPROVED,
            reviewerId = actor.userId,
            rejectionReasonCode = null,
            rejectionNote = null,
            at = Instant.now(clock),
        )

        recorder.record(
            action = AuditAction.DOCUMENT_APPROVED,
            subjectType = "DOCUMENT",
            subjectId = submission.id,
            actor = actor,
            supplierId = submission.supplierId,
            before = mapOf("status" to SubmissionStatus.PENDING.name),
            after = mapOf(
                "status" to SubmissionStatus.APPROVED.name,
                "documentType" to submission.documentTypeCode,
                "version" to submission.version,
            ),
        )

        settle(actor, submission.supplierId, rejected = false)
    }

    @Transactional
    fun reject(actor: Actor, submissionId: UUID, reasonCode: String, note: String?) {
        val submission = reviewable(actor, submissionId)

        val reason = catalog.rejectionReasons().firstOrNull { it.code == reasonCode }
            ?: throw InvalidRequestException("Choose a reason the supplier will understand.")

        submissions.recordReview(
            id = submission.id,
            status = SubmissionStatus.REJECTED,
            reviewerId = actor.userId,
            rejectionReasonCode = reason.code,
            rejectionNote = note?.trim()?.takeIf { it.isNotEmpty() },
            at = Instant.now(clock),
        )

        recorder.record(
            action = AuditAction.DOCUMENT_REJECTED,
            subjectType = "DOCUMENT",
            subjectId = submission.id,
            actor = actor,
            supplierId = submission.supplierId,
            before = mapOf("status" to SubmissionStatus.PENDING.name),
            after = mapOf(
                "status" to SubmissionStatus.REJECTED.name,
                "documentType" to submission.documentTypeCode,
                "reason" to reason.code,
                "note" to note,
            ),
        )

        notifySupplier(submission) { email, name ->
            notifier.documentRejected(
                recipientEmail = email,
                recipientName = name,
                supplierId = submission.supplierId,
                documentName = submission.documentTypeName,
                reasonLabel = reason.label,
                note = note?.trim()?.takeIf { it.isNotEmpty() },
            )
        }

        settle(actor, submission.supplierId, rejected = true)
    }

    /**
     * Moves the supplier on, and closes onboarding when this was the last thing
     * anyone was waiting for.
     */
    private fun settle(actor: Actor, supplierId: UUID, rejected: Boolean) {
        val snapshot = assembler.snapshot(suppliers.findById(supplierId)!!)
        val stage = progression.afterReview(snapshot, actor, rejected)

        if (stage != OnboardingStage.APPROVED) return

        // Approval is what the VMS is waiting to hear (§5); until that connector
        // exists, activation is local and the supplier is told directly.
        enrollments.listForSupplier(supplierId)
            .filter { it.status != "ACTIVE" }
            .forEach { enrollments.activate(it.id) }

        val supplier = suppliers.findById(supplierId)!!
        recipients(supplierId, supplier.primaryContactEmail, supplier.primaryContactName)
            .forEach { (email, name) ->
                notifier.onboardingCompleted(
                    recipientEmail = email,
                    recipientName = name,
                    supplierId = supplierId,
                    companyName = supplier.legalName,
                )
            }
    }

    /**
     * Loads the submission and refuses the two reviews that must not happen: one
     * on a document already ruled on, and one by the person who uploaded it.
     */
    private fun reviewable(actor: Actor, submissionId: UUID): SubmissionRecord {
        actor.requireOps()

        val submission = submissions.findById(submissionId)
            ?: throw NotFoundException("That document is no longer available.")

        if (!submission.isCurrent) {
            throw InvalidRequestException(
                "The supplier has since replaced this document. Review the current version instead.",
            )
        }
        if (submission.status != SubmissionStatus.PENDING) {
            throw InvalidRequestException(
                "${submission.reviewedByName ?: "Someone"} already reviewed this document. " +
                    "Refresh to see where it stands.",
            )
        }
        if (submission.uploadedBy == actor.userId) {
            throw InvalidRequestException(
                "You uploaded this document, so someone else on the team has to review it.",
            )
        }
        return submission
    }

    private fun notifySupplier(submission: SubmissionRecord, send: (String, String?) -> Unit) {
        val supplier = suppliers.findById(submission.supplierId) ?: return
        recipients(submission.supplierId, supplier.primaryContactEmail, supplier.primaryContactName)
            .forEach { (email, name) -> send(email, name) }
    }

    /**
     * Everyone at the supplier who can act on this, falling back to the contact
     * on the record. A rejection that reaches nobody is the black box this
     * product replaces.
     */
    private fun recipients(
        supplierId: UUID,
        fallbackEmail: String?,
        fallbackName: String?,
    ): List<Pair<String, String?>> {
        val active = users.listForSupplier(supplierId)
            .filter { it.status == UserStatus.ACTIVE || it.status == UserStatus.INVITED }
            .map { it.email to it.fullName }

        return active.ifEmpty {
            fallbackEmail?.let { listOf(it to fallbackName) } ?: emptyList()
        }
    }

    private fun programNamesFor(submission: SubmissionRecord): List<String> {
        val supplierEnrollments = enrollments.listForSupplier(submission.supplierId)
        // A supplier-scope document has no enrollment because it satisfies every
        // program at once, and the queue should say so rather than show nothing.
        return supplierEnrollments
            .filter { submission.enrollmentId == null || it.id == submission.enrollmentId }
            .map { it.programName }
    }
}
