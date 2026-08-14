package com.acme.onboarding.application.criteria

import com.acme.onboarding.adapter.persistence.CriteriaRepository
import com.acme.onboarding.adapter.persistence.EnrollmentRepository
import com.acme.onboarding.adapter.persistence.SubmissionRepository
import com.acme.onboarding.adapter.persistence.SupplierRepository
import com.acme.onboarding.application.audit.ActivityRecorder
import com.acme.onboarding.application.audit.AuditAction
import com.acme.onboarding.application.document.DocumentStore
import com.acme.onboarding.application.support.InvalidRequestException
import com.acme.onboarding.application.support.NotFoundException
import com.acme.onboarding.domain.user.AccessDeniedException
import com.acme.onboarding.domain.user.Actor
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.util.UUID

/**
 * Asks the model to fill in a criteria checklist, and records that the document
 * left the building to do it.
 *
 * **The classification gate is the whole reason this is a separate service.**
 * Evaluation transmits a document to a third party, so it runs on Confidential
 * and Internal documents only. A W-9 is Restricted: it carries a taxpayer
 * identification number, and routing that to an external API is a data-governance
 * decision Acme's compliance function owns, not a default an engineer picks. The
 * refusal below is that decision expressed in code rather than in configuration,
 * so no environment variable can turn it on by accident.
 *
 * **Nothing here decides anything.** Verdicts land with `source = MODEL`, a
 * reviewer confirms or overrides each one, and both are kept. The model saves
 * reading time; it does not approve or reject.
 */
@Service
class CriteriaPrefillService(
    private val criteria: CriteriaRepository,
    private val submissions: SubmissionRepository,
    private val suppliers: SupplierRepository,
    private val enrollments: EnrollmentRepository,
    private val store: DocumentStore,
    private val evaluator: CriteriaEvaluator,
    private val journal: CriteriaJournal,
    private val review: CriteriaReviewService,
) {

    private val log = LoggerFactory.getLogger(javaClass)

    /**
     * Runs the model over one submission and returns the refreshed checklist.
     *
     * Deliberately **not** `@Transactional`: the disclosure event is committed
     * before the document is transmitted, so a call that times out still leaves
     * the record that the document was sent. One transaction spanning the whole
     * thing would roll that record back — an untraceable disclosure is the exact
     * finding an auditor writes up.
     */
    fun prefill(actor: Actor, submissionId: UUID): CriteriaChecklist {
        actor.requireOps()

        if (!evaluator.available) {
            throw InvalidRequestException(
                "No model is configured in this environment, so criteria are checked by hand. " +
                    "Nothing is missing from the review — this only saves reading time.",
            )
        }

        val submission = submissions.findById(submissionId)
            ?: throw NotFoundException("That document is no longer available.")

        if (submission.classification == RESTRICTED) {
            throw AccessDeniedException(
                "${submission.documentTypeName} is classified Restricted, so it is never sent to a " +
                    "third-party model. Check it against the criteria yourself.",
            )
        }

        val checklist = review.checklist(actor, submissionId)
        if (checklist.empty) {
            throw InvalidRequestException(
                "This requirement has no acceptance criteria yet. Author them first and every future " +
                    "submission is checkable against the same list.",
            )
        }

        val supplier = suppliers.findById(submission.supplierId)
            ?: throw NotFoundException("That supplier no longer exists.")
        val enrollment = enrollments.listForSupplier(submission.supplierId)
            .let { all -> submission.enrollmentId?.let { id -> all.firstOrNull { it.id == id } } ?: all.firstOrNull() }

        val prompts = checklist.criteria.map {
            CriterionPrompt(criterionId = it.criterionId, ordinal = it.ordinal, text = it.text)
        }

        journal.recordDisclosure(
            actor = actor,
            submissionId = submission.id,
            supplierId = submission.supplierId,
            documentTypeCode = submission.documentTypeCode,
            classification = submission.classification,
            model = evaluator.model,
            criteriaCount = prompts.size,
        )

        val verdicts = try {
            evaluator.evaluate(
                EvaluationRequest(
                    documentTypeName = submission.documentTypeName,
                    programName = enrollment?.programName,
                    companyLegalName = supplier.legalName,
                    contentType = submission.contentType,
                    bytes = store.read(submission.storageKey),
                    criteria = prompts,
                ),
            )
        } catch (error: Exception) {
            log.warn("Criteria prefill failed for submission {}", submission.id, error)
            throw InvalidRequestException(
                "The model could not be reached just now. The checklist is unchanged — tick it by hand, " +
                    "or try again.",
            )
        }

        journal.recordVerdicts(submission.supplierId, submission.id, verdicts, evaluator.model, prompts)
        return review.checklist(actor, submissionId)
    }

    private companion object {
        const val RESTRICTED = "RESTRICTED"
    }
}

/**
 * The two writes that bracket a model call, each in its own transaction.
 *
 * Separated from the orchestration above because the ordering is the point: the
 * disclosure commits first and survives a failed call, and the verdicts commit
 * only if there are verdicts. A single transaction around both would make the
 * record of the transmission conditional on the transmission succeeding.
 */
@Service
class CriteriaJournal(
    private val criteria: CriteriaRepository,
    private val recorder: ActivityRecorder,
) {

    @Transactional
    fun recordDisclosure(
        actor: Actor,
        submissionId: UUID,
        supplierId: UUID,
        documentTypeCode: String,
        classification: String,
        model: String,
        criteriaCount: Int,
    ) {
        recorder.record(
            action = AuditAction.DOCUMENT_DISCLOSED,
            subjectType = "DOCUMENT",
            subjectId = submissionId,
            actor = actor,
            supplierId = supplierId,
            after = mapOf(
                "processor" to "Anthropic",
                "model" to model,
                "documentType" to documentTypeCode,
                "classification" to classification,
                "criteriaCount" to criteriaCount,
                "purpose" to "CRITERIA_EVALUATION",
            ),
        )
    }

    @Transactional
    fun recordVerdicts(
        supplierId: UUID,
        submissionId: UUID,
        verdicts: List<ModelVerdict>,
        model: String,
        prompts: List<CriterionPrompt>,
    ) {
        if (verdicts.isEmpty()) return

        val current = criteria.currentByIds(prompts.map { it.criterionId })

        verdicts.forEach { verdict ->
            val criterion = current[verdict.criterionId] ?: return@forEach
            criteria.recordVerdict(
                submissionId = submissionId,
                criterion = criterion,
                verdict = verdict.verdict,
                evidence = verdict.evidence,
                confidence = verdict.confidence,
                source = "MODEL",
                model = model,
                // No user: attributing a model's verdict to the person who asked
                // for it would put their name on a judgement they have not made.
                decidedBy = null,
            )
        }

        recorder.record(
            action = AuditAction.CRITERION_JUDGED,
            subjectType = "DOCUMENT",
            subjectId = submissionId,
            actor = null,
            supplierId = supplierId,
            systemActorLabel = model,
            after = mapOf(
                "source" to "MODEL",
                "model" to model,
                "verdicts" to verdicts.associate { it.criterionId.toString() to it.verdict },
            ),
        )
    }
}
