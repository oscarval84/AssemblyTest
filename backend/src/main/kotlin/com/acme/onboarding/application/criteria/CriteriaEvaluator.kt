package com.acme.onboarding.application.criteria

import java.util.UUID

/** One authored criterion, as the model is asked about it. */
data class CriterionPrompt(
    val criterionId: UUID,
    val ordinal: Int,
    val text: String,
)

/** One document, with everything the model needs to judge it in context. */
data class EvaluationRequest(
    val documentTypeName: String,
    val programName: String?,
    val companyLegalName: String,
    val contentType: String,
    val bytes: ByteArray,
    val criteria: List<CriterionPrompt>,
)

/**
 * What the model suggests for one criterion. Advisory in the strict sense: a
 * `FAIL` here never rejects a document and a `PASS` never approves one.
 */
data class ModelVerdict(
    val criterionId: UUID,
    val verdict: String,
    /** The span of the document the verdict rests on, quoted for a reviewer to check. */
    val evidence: String?,
    val confidence: Double?,
)

/**
 * The model that prefills the criteria checklist.
 *
 * A port, because this is the one part of criteria review that depends on a
 * third party. With no implementation configured the product still works —
 * a reviewer ticks each criterion by hand, which is the fallback the design
 * requires anyway: the model only ever saves reading time, so its absence
 * costs time rather than correctness.
 */
interface CriteriaEvaluator {

    /** False when no API key is configured; the UI then never offers the button. */
    val available: Boolean

    /** Recorded on every verdict and every disclosure event, so "which model said this" survives. */
    val model: String

    fun evaluate(request: EvaluationRequest): List<ModelVerdict>
}

/**
 * The default: no model, and it says so.
 *
 * Deliberately not a stub that invents verdicts. A checklist filled in by
 * something that did not read the document is worse than an empty one — it
 * would be advisory input a reviewer trusts, sourced from nothing.
 */
object DisabledCriteriaEvaluator : CriteriaEvaluator {
    override val available = false
    override val model = "none"

    override fun evaluate(request: EvaluationRequest): List<ModelVerdict> =
        throw UnsupportedOperationException(
            "No model is configured (acme.ai.api-key is unset), so criteria are checked by a person.",
        )
}
