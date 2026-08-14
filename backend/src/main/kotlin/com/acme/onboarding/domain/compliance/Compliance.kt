package com.acme.onboarding.domain.compliance

import java.time.Clock
import java.time.LocalDate
import java.time.ZoneId
import java.util.UUID

/**
 * Whether a document requirement is satisfied at a point in time.
 */
enum class ComplianceStatus {
    /** Every required document is approved and unexpired. */
    COMPLIANT,

    /** Compliant today, but something expires inside the warning band. */
    EXPIRING_SOON,

    /** At least one requirement is missing, rejected, or expired. */
    NON_COMPLIANT,
    ;

    /** Ordering used to fold per-document findings into one enrollment status. */
    val severity: Int
        get() = when (this) {
            COMPLIANT -> 0
            EXPIRING_SOON -> 1
            NON_COMPLIANT -> 2
        }
}

enum class DocumentScope { SUPPLIER, PROGRAM }

enum class SubmissionStatus { PENDING, APPROVED, REJECTED }

/** Why a particular requirement is not cleanly satisfied. */
enum class Issue {
    /** Nothing has been submitted for this requirement. */
    MISSING,

    /** Submitted and waiting on Acme. The supplier has done their part. */
    PENDING_REVIEW,

    /** Ops rejected it; the supplier must resubmit. */
    REJECTED,

    /** Approved, but the expiry date has passed. */
    EXPIRED,

    /** Approved and valid, but expiring inside the warning band. */
    EXPIRING_SOON,
}

/**
 * A document this enrollment demands.
 *
 * [constraints] is intentionally loose: the shape differs per document type — a
 * coverage minimum on a certificate has no analogue on a W-9 — and v1 should not
 * invent columns for constraints the client has not confirmed.
 *
 * **Nothing below reads it.** It is carried so the supplier's checklist can
 * state the bar in each program's own terms and so review can check against it;
 * the evaluator judges presence, status and expiry only. See
 * architecture.md § Requirement resolution for why enforcing it here would need
 * a per-enrollment decision on a shared document first.
 */
data class RequiredDocument(
    val documentTypeCode: String,
    val scope: DocumentScope,
    val expiring: Boolean,
    val constraints: Map<String, Any?> = emptyMap(),
)

/**
 * A document the supplier actually holds.
 *
 * [enrollmentId] is null for supplier-scope documents, which are shared across
 * every enrollment — that sharing is the point, and it is why a supplier joining
 * a second program does not re-upload their W-9.
 */
data class HeldDocument(
    val documentTypeCode: String,
    val status: SubmissionStatus,
    val expiresOn: LocalDate?,
    val enrollmentId: UUID?,
)

data class Finding(
    val documentTypeCode: String,
    val issue: Issue,
    val expiresOn: LocalDate? = null,
) {
    val status: ComplianceStatus
        get() = when (issue) {
            Issue.EXPIRING_SOON -> ComplianceStatus.EXPIRING_SOON
            else -> ComplianceStatus.NON_COMPLIANT
        }
}

data class EnrollmentCompliance(
    val enrollmentId: UUID,
    val status: ComplianceStatus,
    val findings: List<Finding>,
) {
    /** The soonest expiry that put this enrollment in the warning band, if any. */
    val nextExpiry: LocalDate?
        get() = findings.mapNotNull { it.expiresOn }.minOrNull()
}

/**
 * Computes compliance for a program enrollment.
 *
 * Two rules make this correct, and both are easy to get wrong:
 *
 *  1. **Status is computed, never stored.** A stored `COMPLIANT` flag becomes a
 *     lie at midnight with nothing having written to the row. The scheduled
 *     sweep exists to send notifications and record transitions in the audit
 *     log, not to keep a status column truthful.
 *
 *  2. **"Today" is resolved in Acme's business time zone**, not the server's and
 *     not UTC. A job firing at 02:00 UTC must not expire a document a day early
 *     for an ops team in New York.
 */
class ComplianceEvaluator(
    private val businessZone: ZoneId,
    private val warningDays: Long,
    private val clock: Clock = Clock.systemUTC(),
) {

    fun today(): LocalDate = LocalDate.now(clock.withZone(businessZone))

    /**
     * Evaluate one enrollment.
     *
     * [held] should contain the supplier's current submissions: supplier-scope
     * documents (shared) plus any program-scope documents belonging to this
     * enrollment. Documents belonging to a *different* enrollment are ignored
     * rather than rejected, so callers may pass the supplier's full set.
     */
    fun evaluate(
        enrollmentId: UUID,
        required: List<RequiredDocument>,
        held: List<HeldDocument>,
    ): EnrollmentCompliance {
        val today = today()
        val relevant = held.filter { it.enrollmentId == null || it.enrollmentId == enrollmentId }
        val byType = relevant.associateBy { it.documentTypeCode }

        val findings = required.mapNotNull { requirement ->
            evaluateOne(requirement, byType[requirement.documentTypeCode], today)
        }

        val status = findings
            .map { it.status }
            .maxByOrNull { it.severity }
            ?: ComplianceStatus.COMPLIANT

        return EnrollmentCompliance(enrollmentId, status, findings)
    }

    private fun evaluateOne(
        requirement: RequiredDocument,
        document: HeldDocument?,
        today: LocalDate,
    ): Finding? {
        if (document == null) {
            return Finding(requirement.documentTypeCode, Issue.MISSING)
        }

        when (document.status) {
            SubmissionStatus.PENDING ->
                return Finding(requirement.documentTypeCode, Issue.PENDING_REVIEW)

            SubmissionStatus.REJECTED ->
                return Finding(requirement.documentTypeCode, Issue.REJECTED)

            SubmissionStatus.APPROVED -> Unit
        }

        // Approved. Only expiring document types can degrade from here.
        val expiresOn = document.expiresOn
        if (!requirement.expiring || expiresOn == null) return null

        // A document is valid *through* the end of its expiry date, so it is
        // expired only once today has moved past it. Off-by-one here means
        // flagging a supplier whose certificate is still good.
        return when {
            today.isAfter(expiresOn) ->
                Finding(requirement.documentTypeCode, Issue.EXPIRED, expiresOn)

            !today.plusDays(warningDays).isBefore(expiresOn) ->
                Finding(requirement.documentTypeCode, Issue.EXPIRING_SOON, expiresOn)

            else -> null
        }
    }
}
