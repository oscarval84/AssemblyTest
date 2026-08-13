package com.acme.onboarding.domain.pipeline

import com.acme.onboarding.domain.compliance.Finding
import com.acme.onboarding.domain.compliance.Issue
import com.acme.onboarding.domain.onboarding.OnboardingStage

/** Who is expected to act next. Drives the pipeline's top-level grouping. */
enum class WaitingOn { SUPPLIER, ACME, NOBODY }

/**
 * What a supplier is blocked on, right now.
 *
 * Always **derived, never stored**. A stored blocker drifts the moment a
 * document is approved or expires, and the drift is silent — which is how the
 * current spreadsheet ends up disagreeing with reality. Marcus's team should
 * never have to ask whether the pipeline is stale.
 */
data class Blocker(
    val waitingOn: WaitingOn,
    val summary: String,
    val documentTypeCodes: List<String> = emptyList(),
)

object BlockerDerivation {

    /**
     * Derive the single most useful thing to say about a supplier's status.
     *
     * Ordering is deliberate: the pipeline shows one line per supplier, so when
     * several things are true at once this picks the one that decides what
     * happens next. Acme's own queue outranks supplier-side work, because that
     * is the part Acme controls and is measured on.
     */
    fun derive(stage: OnboardingStage, findings: List<Finding>): Blocker {
        // Pre-document stages have their own answer regardless of findings.
        when (stage) {
            OnboardingStage.INVITED ->
                return Blocker(WaitingOn.SUPPLIER, "Invitation not yet accepted")

            OnboardingStage.REGISTERED ->
                return Blocker(WaitingOn.SUPPLIER, "Company profile not started")

            OnboardingStage.PROFILE_SUBMITTED ->
                return Blocker(WaitingOn.SUPPLIER, "Documents not yet started")

            else -> Unit
        }

        val byIssue = findings.groupBy { it.issue }

        byIssue[Issue.PENDING_REVIEW]?.takeIf { it.isNotEmpty() }?.let { pending ->
            return Blocker(
                waitingOn = WaitingOn.ACME,
                summary = countPhrase(pending.size, "document") + " awaiting review",
                documentTypeCodes = pending.map { it.documentTypeCode },
            )
        }

        byIssue[Issue.EXPIRED]?.takeIf { it.isNotEmpty() }?.let { expired ->
            return Blocker(
                waitingOn = WaitingOn.SUPPLIER,
                summary = countPhrase(expired.size, "document") + " expired",
                documentTypeCodes = expired.map { it.documentTypeCode },
            )
        }

        byIssue[Issue.REJECTED]?.takeIf { it.isNotEmpty() }?.let { rejected ->
            return Blocker(
                waitingOn = WaitingOn.SUPPLIER,
                summary = countPhrase(rejected.size, "document") + " rejected, awaiting resubmission",
                documentTypeCodes = rejected.map { it.documentTypeCode },
            )
        }

        byIssue[Issue.MISSING]?.takeIf { it.isNotEmpty() }?.let { missing ->
            return Blocker(
                waitingOn = WaitingOn.SUPPLIER,
                summary = countPhrase(missing.size, "document") + " not yet submitted",
                documentTypeCodes = missing.map { it.documentTypeCode },
            )
        }

        byIssue[Issue.EXPIRING_SOON]?.takeIf { it.isNotEmpty() }?.let { expiring ->
            val soonest = expiring.mapNotNull { it.expiresOn }.minOrNull()
            return Blocker(
                waitingOn = WaitingOn.SUPPLIER,
                summary = soonest
                    ?.let { "Renewal due by $it" }
                    ?: (countPhrase(expiring.size, "document") + " expiring soon"),
                documentTypeCodes = expiring.map { it.documentTypeCode },
            )
        }

        return when (stage) {
            OnboardingStage.APPROVED -> Blocker(WaitingOn.NOBODY, "Approved and compliant")
            else -> Blocker(WaitingOn.ACME, "Ready for final review")
        }
    }

    private fun countPhrase(count: Int, noun: String): String =
        if (count == 1) "1 $noun" else "$count ${noun}s"
}
