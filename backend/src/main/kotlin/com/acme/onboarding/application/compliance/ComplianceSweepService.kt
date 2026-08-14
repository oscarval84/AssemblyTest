package com.acme.onboarding.application.compliance

import com.acme.onboarding.adapter.persistence.ExpiringDocument
import com.acme.onboarding.adapter.persistence.ExpiryRepository
import com.acme.onboarding.adapter.persistence.SupplierRepository
import com.acme.onboarding.adapter.persistence.UserRepository
import com.acme.onboarding.application.audit.ActivityRecorder
import com.acme.onboarding.application.audit.AuditAction
import com.acme.onboarding.application.notification.Notifier
import com.acme.onboarding.application.onboarding.StageProgression
import com.acme.onboarding.application.supplier.SupplierAssembler
import com.acme.onboarding.config.AcmeProperties
import com.acme.onboarding.domain.compliance.ComplianceEvaluator
import com.acme.onboarding.domain.user.Actor
import com.acme.onboarding.domain.user.UserStatus
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.LocalDate
import java.time.temporal.ChronoUnit
import java.util.UUID

/** What one sweep did, for the job's response and the ops-visible log. */
data class SweepResult(
    val today: LocalDate,
    val documentsExamined: Int,
    val remindersSent: Int,
    val suppliersReopened: Int,
)

/**
 * The scheduled sweep behind the client's most expensive failure: twice, a
 * supplier worked on an expired certificate and nobody noticed.
 *
 * What this job does **not** do is keep a status column truthful. Compliance is
 * computed from the current date on every read, so it is already correct at
 * midnight without anyone running anything. The sweep exists for the two things
 * a computed value cannot do on its own: tell somebody, and record that the
 * transition happened.
 */
@Service
class ComplianceSweepService(
    private val expiry: ExpiryRepository,
    private val suppliers: SupplierRepository,
    private val users: UserRepository,
    private val assembler: SupplierAssembler,
    private val progression: StageProgression,
    private val notifier: Notifier,
    private val recorder: ActivityRecorder,
    private val evaluator: ComplianceEvaluator,
    private val properties: AcmeProperties,
) {

    private val log = LoggerFactory.getLogger(javaClass)

    /**
     * Reminder points, in days before expiry.
     *
     * Three, not thirty: one while renewal is still routine, one when it needs
     * to happen this week, and one the morning after it lapses. Each is claimed
     * once per document version, so a certificate sitting in the warning band
     * produces three messages over a month rather than one a day.
     */
    private val thresholds = listOf(30, 7, 0, -1)

    @Transactional
    fun sweep(): SweepResult {
        val today = evaluator.today()
        val horizon = today.plusDays(properties.compliance.expiryWarningDays)
        val documents = expiry.listExpiringThrough(horizon)

        var reminders = 0
        val reopened = mutableSetOf<UUID>()

        documents.forEach { document ->
            val daysRemaining = ChronoUnit.DAYS.between(today, document.expiresOn)
            val threshold = thresholdFor(daysRemaining) ?: return@forEach

            if (!expiry.claimReminder(document.submissionId, threshold, document.expiresOn)) return@forEach

            reminders += notify(document, daysRemaining)
            recordTransition(document, daysRemaining)

            // An expired document reopens document collection, which is exactly
            // the lapse that went unnoticed before. It flags rather than blocks:
            // whether an expired certificate stops a placement is the VMS's call,
            // and Acme has not answered that question yet.
            if (daysRemaining < 0 && reopened.add(document.supplierId)) {
                reopenIfNeeded(document.supplierId)
            }
        }

        val result = SweepResult(today, documents.size, reminders, reopened.size)
        log.info(
            "Compliance sweep for {}: {} documents examined, {} reminders sent, {} suppliers reopened",
            result.today,
            result.documentsExamined,
            result.remindersSent,
            result.suppliersReopened,
        )
        return result
    }

    /**
     * The reminder point this document has reached, or null when it sits between
     * two of them and has already been reminded at the last one it passed.
     */
    private fun thresholdFor(daysRemaining: Long): Int? =
        // The *last* match, not the first: a document 5 days from expiry has
        // passed both the 30-day and the 7-day mark, and the message it is owed
        // is the urgent one. Claiming the nearer threshold also leaves the
        // earlier one unclaimed, which is harmless — it will never be reached
        // again by this version of the document.
        thresholds.lastOrNull { daysRemaining <= it }

    private fun notify(document: ExpiringDocument, daysRemaining: Long): Int {
        val recipients = users.listForSupplier(document.supplierId)
            .filter { it.status == UserStatus.ACTIVE }
            .map { it.email to it.fullName }
            .ifEmpty {
                document.contactEmail?.let { listOf(it to document.contactName) } ?: emptyList()
            }

        recipients.forEach { (email, name) ->
            notifier.expiryReminder(
                recipientEmail = email,
                recipientName = name,
                supplierId = document.supplierId,
                companyName = document.supplierLegalName,
                documentName = document.documentTypeName,
                expiresOn = document.expiresOn,
                daysRemaining = daysRemaining,
            )
        }
        return recipients.size
    }

    /**
     * The transition, in the supplier's own chain.
     *
     * This is what makes "the certificate lapsed on the 14th and we told them on
     * the 14th" answerable from the audit log rather than from a mail server
     * nobody kept.
     */
    private fun recordTransition(document: ExpiringDocument, daysRemaining: Long) {
        recorder.record(
            action = if (daysRemaining < 0) AuditAction.DOCUMENT_EXPIRED else AuditAction.DOCUMENT_EXPIRING_SOON,
            subjectType = "DOCUMENT",
            subjectId = document.submissionId,
            actor = null,
            supplierId = document.supplierId,
            systemActorLabel = "compliance sweep",
            after = mapOf(
                "documentType" to document.documentTypeCode,
                "expiresOn" to document.expiresOn.toString(),
                "daysRemaining" to daysRemaining,
                "programs" to document.programNames,
            ),
        )
    }

    private fun reopenIfNeeded(supplierId: UUID) {
        val supplier = suppliers.findById(supplierId) ?: return
        progression.afterDocumentChange(assembler.snapshot(supplier), actor = null)
    }

    /** The ops-facing read: what is about to lapse, and what already has. */
    @Transactional(readOnly = true)
    fun upcomingExpirations(actor: Actor, withinDays: Long = 60): List<ExpiringDocument> {
        actor.requireOps()
        return expiry.listExpiringThrough(evaluator.today().plusDays(withinDays))
    }
}
