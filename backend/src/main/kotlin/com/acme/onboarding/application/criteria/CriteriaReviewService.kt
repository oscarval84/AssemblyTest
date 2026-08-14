package com.acme.onboarding.application.criteria

import com.acme.onboarding.adapter.persistence.AcceptanceCriterionRecord
import com.acme.onboarding.adapter.persistence.CatalogRepository
import com.acme.onboarding.adapter.persistence.CriteriaEvaluationRecord
import com.acme.onboarding.adapter.persistence.CriteriaRepository
import com.acme.onboarding.adapter.persistence.EnrollmentRepository
import com.acme.onboarding.adapter.persistence.SubmissionRepository
import com.acme.onboarding.application.audit.ActivityRecorder
import com.acme.onboarding.application.audit.AuditAction
import com.acme.onboarding.application.support.InvalidRequestException
import com.acme.onboarding.application.support.NotFoundException
import com.acme.onboarding.domain.user.Actor
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.util.UUID

/** One criterion and where it stands for this submission. */
data class CriterionVerdict(
    val criterionId: UUID,
    val ordinal: Int,
    val text: String,
    val criteriaVersion: Int,
    /** Null until somebody — or something — has judged it. */
    val verdict: String?,
    val evidence: String?,
    val confidence: Double?,
    val source: String?,
    val decidedByName: String?,
)

data class CriteriaChecklist(
    val submissionId: UUID,
    val documentTypeName: String,
    val programName: String?,
    val criteriaVersion: Int,
    val criteria: List<CriterionVerdict>,
    /**
     * Whether this document can be prefilled by the model: a model is configured
     * *and* the document's classification permits sending it to one. False for a
     * W-9 in every environment, and the screen says which of the two it is.
     */
    val modelAvailable: Boolean = false,
    /** Null when no model is configured; named so a reviewer knows what suggested what. */
    val model: String? = null,
) {
    val failed: List<CriterionVerdict> get() = criteria.filter { it.verdict == "FAIL" }

    /** True when a requirement carries no criteria — normal, not broken. */
    val empty: Boolean get() = criteria.isEmpty()
}

/**
 * Review against criteria Acme wrote, rather than against a reason catalog we
 * guessed at.
 *
 * The client was asked which three or four reasons his team rejects documents
 * for, so they could become one-click buttons, and answered a better question:
 * let Acme input the acceptance criteria, and check submissions against those.
 * A seeded catalog encodes what we guessed on the day we guessed it; authored
 * criteria encode what Acme actually requires, maintained by the people who own
 * the requirement, with no deploy.
 *
 * Everything here is advisory in the strict sense: a `FAIL` never rejects on its
 * own and a `PASS` never approves. What the feature buys is that when a person
 * does click reject, the supplier is told *"the general liability aggregate
 * shows USD 1,000,000; this program requires USD 2,000,000"* instead of
 * *"rejected — incorrect information"*. That is the difference between one
 * resubmission and three.
 */
@Service
class CriteriaReviewService(
    private val criteria: CriteriaRepository,
    private val submissions: SubmissionRepository,
    private val enrollments: EnrollmentRepository,
    private val catalog: CatalogRepository,
    private val recorder: ActivityRecorder,
    private val evaluator: CriteriaEvaluator,
) {

    // -- authoring ------------------------------------------------------------

    @Transactional(readOnly = true)
    fun forRequirement(actor: Actor, programRequirementId: UUID): List<AcceptanceCriterionRecord> {
        actor.requireOps()
        return criteria.current(programRequirementId)
    }

    /**
     * Replaces a requirement's criteria, producing a new version.
     *
     * Ops owns this, not engineering: Marcus adds a criterion the first time a
     * supplier gets something wrong, and every submission after that is checked
     * against it.
     */
    @Transactional
    fun author(actor: Actor, programRequirementId: UUID, texts: List<String>): Int {
        actor.requireOps()

        val cleaned = texts.map { it.trim() }.filter { it.isNotEmpty() }
        if (cleaned.any { it.length > MAX_CRITERION_LENGTH }) {
            throw InvalidRequestException(
                "Keep each criterion to one checkable statement — a supplier reads it verbatim in a " +
                    "rejection, and a paragraph is not something a reviewer can tick.",
            )
        }

        val version = criteria.replace(programRequirementId, cleaned, actor.userId)
        recorder.record(
            action = AuditAction.CRITERIA_UPDATED,
            subjectType = "REQUIREMENT",
            subjectId = programRequirementId,
            actor = actor,
            after = mapOf("version" to version, "criteria" to cleaned),
        )
        return version
    }

    // -- review ---------------------------------------------------------------

    /**
     * The checklist a reviewer works through, beside the document.
     *
     * Four green, one red, one unclear — each pointing at what it relied on —
     * rather than reading a certificate line by line against a program's
     * requirements the reviewer has to remember.
     */
    @Transactional(readOnly = true)
    fun checklist(actor: Actor, submissionId: UUID): CriteriaChecklist {
        actor.requireOps()
        val submission = submissions.findById(submissionId)
            ?: throw NotFoundException("That document is no longer available.")

        val enrollment = resolveEnrollment(submission.supplierId, submission.enrollmentId)
        val requirementId = enrollment?.let {
            criteria.requirementFor(it.programId, submission.documentTypeCode)
        }

        val current = requirementId?.let(criteria::current).orEmpty()
        val verdicts = criteria.verdictsFor(submissionId).associateBy { it.criterionId }

        return CriteriaChecklist(
            submissionId = submissionId,
            documentTypeName = submission.documentTypeName,
            programName = enrollment?.programName,
            criteriaVersion = current.firstOrNull()?.version ?: 0,
            // Restricted documents are never sent to an external processor, so
            // the button is not offered for a W-9 even where a key is set.
            modelAvailable = evaluator.available && submission.classification != "RESTRICTED",
            model = evaluator.model.takeIf { evaluator.available },
            criteria = current.map { criterion ->
                val verdict = verdicts[criterion.id]
                CriterionVerdict(
                    criterionId = criterion.id,
                    ordinal = criterion.ordinal,
                    text = criterion.text,
                    criteriaVersion = criterion.version,
                    verdict = verdict?.verdict,
                    evidence = verdict?.evidence,
                    confidence = verdict?.confidence,
                    source = verdict?.source,
                    decidedByName = verdict?.decidedByName,
                )
            },
        )
    }

    /**
     * A reviewer's judgement on one criterion.
     *
     * Recorded with `source = REVIEWER` so the audit trail keeps what the model
     * said and what the human decided as two separate facts.
     */
    @Transactional
    fun judge(actor: Actor, submissionId: UUID, criterionId: UUID, verdict: String, evidence: String?) {
        actor.requireOps()
        if (verdict !in VERDICTS) {
            throw InvalidRequestException("A criterion passes, fails, or is unclear.")
        }

        val submission = submissions.findById(submissionId)
            ?: throw NotFoundException("That document is no longer available.")
        val criterion = criteriaById(submission.supplierId, submission.enrollmentId, criterionId)

        criteria.recordVerdict(
            submissionId = submissionId,
            criterion = criterion,
            verdict = verdict,
            evidence = evidence?.trim()?.takeIf { it.isNotEmpty() },
            confidence = null,
            source = "REVIEWER",
            model = null,
            decidedBy = actor.userId,
        )

        recorder.record(
            action = AuditAction.CRITERION_JUDGED,
            subjectType = "DOCUMENT",
            subjectId = submissionId,
            actor = actor,
            supplierId = submission.supplierId,
            after = mapOf(
                "criterion" to criterion.text,
                "criteriaVersion" to criterion.version,
                "verdict" to verdict,
            ),
        )
    }

    /**
     * The rejection text a failed criterion produces.
     *
     * Acme's own words plus the evidence, which is the whole point: the supplier
     * is told what is wrong with the document in front of them rather than that
     * something was.
     */
    @Transactional(readOnly = true)
    fun rejectionNoteFor(actor: Actor, submissionId: UUID, criterionId: UUID): String {
        actor.requireOps()
        val verdict = criteria.verdictsFor(submissionId).firstOrNull { it.criterionId == criterionId }
            ?: throw NotFoundException("That criterion has not been judged on this document.")

        return buildString {
            append(verdict.criterionText)
            verdict.evidence?.let { append("\n\nWhat the document shows: ").append(it) }
        }
    }

    private fun resolveEnrollment(supplierId: UUID, enrollmentId: UUID?) =
        enrollments.listForSupplier(supplierId).let { all ->
            // A supplier-scope document has no enrollment because it satisfies
            // every program at once; its criteria are the first program's, and
            // the checklist names which one so nobody is guessing.
            enrollmentId?.let { id -> all.firstOrNull { it.id == id } } ?: all.firstOrNull()
        }

    private fun criteriaById(supplierId: UUID, enrollmentId: UUID?, criterionId: UUID): AcceptanceCriterionRecord {
        val enrollment = resolveEnrollment(supplierId, enrollmentId)
            ?: throw NotFoundException("That supplier is not enrolled in a program.")
        val programIds = listOf(enrollment.programId)

        return criteria.currentForPrograms(programIds).firstOrNull { it.id == criterionId }
            ?: throw NotFoundException("That criterion is not part of the current version.")
    }

    private companion object {
        val VERDICTS = setOf("PASS", "FAIL", "UNCLEAR")
        const val MAX_CRITERION_LENGTH = 300
    }
}
