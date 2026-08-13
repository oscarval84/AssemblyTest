package com.acme.onboarding.application.onboarding

import com.acme.onboarding.adapter.persistence.SupplierRecord
import com.acme.onboarding.adapter.persistence.SupplierRepository
import com.acme.onboarding.application.audit.ActivityRecorder
import com.acme.onboarding.application.audit.AuditAction
import com.acme.onboarding.application.supplier.SupplierAssembler
import com.acme.onboarding.domain.compliance.Issue
import com.acme.onboarding.domain.onboarding.OnboardingStage
import com.acme.onboarding.domain.onboarding.OnboardingTransitions
import com.acme.onboarding.domain.user.Actor
import org.springframework.stereotype.Component
import org.springframework.transaction.annotation.Propagation
import org.springframework.transaction.annotation.Transactional

/**
 * Moves suppliers through the onboarding state machine, and writes an event for
 * every step.
 *
 * The stage is stored — unlike compliance, which is computed — because it
 * records a sequence of decisions rather than a fact about today. What is *not*
 * stored is the conclusion drawn from it: "blocked on" is derived on every read.
 */
@Component
class StageProgression(
    private val suppliers: SupplierRepository,
    private val recorder: ActivityRecorder,
) {

    @Transactional(propagation = Propagation.MANDATORY)
    fun moveTo(supplier: SupplierRecord, target: OnboardingStage, actor: Actor?): OnboardingStage {
        if (supplier.stage == target) return target

        val route = OnboardingTransitions.path(supplier.stage, target)
            ?: throw IllegalStateException("No legal route from ${supplier.stage} to $target")

        var current = supplier.stage
        for (next in route) {
            OnboardingTransitions.require(current, next)
            suppliers.updateStage(supplier.id, next)
            recorder.record(
                action = AuditAction.SUPPLIER_STAGE_CHANGED,
                subjectType = "SUPPLIER",
                subjectId = supplier.id,
                actor = actor,
                supplierId = supplier.id,
                before = mapOf("stage" to current.name),
                after = mapOf("stage" to next.name),
            )
            current = next
        }
        return current
    }

    /**
     * Where a supplier belongs after their documents changed.
     *
     * Approval is deliberately absent: reaching `IN_REVIEW` is something the
     * supplier's own actions cause, but leaving it is Acme's decision. A system
     * that approved suppliers automatically would be answering the question the
     * ops team is paid to answer.
     */
    @Transactional(propagation = Propagation.MANDATORY)
    fun afterDocumentChange(snapshot: SupplierAssembler.Snapshot, actor: Actor?): OnboardingStage {
        val supplier = snapshot.supplier

        // Before the profile is in, document uploads do not move the stage:
        // there is nothing to review until Acme knows who the company is.
        if (supplier.stage == OnboardingStage.INVITED || supplier.stage == OnboardingStage.REGISTERED) {
            return supplier.stage
        }

        val outstanding = snapshot.allFindings.any {
            it.issue == Issue.MISSING || it.issue == Issue.REJECTED || it.issue == Issue.EXPIRED
        }

        val target = when {
            outstanding -> OnboardingStage.DOCUMENTS_IN_PROGRESS
            supplier.stage == OnboardingStage.APPROVED -> OnboardingStage.APPROVED
            else -> OnboardingStage.IN_REVIEW
        }

        return moveTo(supplier, target, actor)
    }

    /**
     * Where a supplier belongs after ops has ruled on one of their documents.
     *
     * A rejection is not the same event as a document going missing, and the
     * stage has to say so. `CHANGES_REQUESTED` means "we looked, and we are
     * handing this back" — the supplier is owed an explanation and knows exactly
     * what to fix. It is only reachable from `IN_REVIEW`, which is correct: a
     * supplier still assembling their paperwork stays in
     * `DOCUMENTS_IN_PROGRESS`, because nothing has been handed back to them that
     * they were not already working on.
     */
    @Transactional(propagation = Propagation.MANDATORY)
    fun afterReview(
        snapshot: SupplierAssembler.Snapshot,
        actor: Actor?,
        rejected: Boolean,
    ): OnboardingStage {
        val supplier = snapshot.supplier

        if (rejected) {
            return if (supplier.stage == OnboardingStage.IN_REVIEW) {
                moveTo(supplier, OnboardingStage.CHANGES_REQUESTED, actor)
            } else {
                supplier.stage
            }
        }

        val outstanding = snapshot.allFindings.any {
            it.issue == Issue.MISSING || it.issue == Issue.REJECTED || it.issue == Issue.EXPIRED
        }
        val awaitingReview = snapshot.allFindings.any { it.issue == Issue.PENDING_REVIEW }

        // Approving the last outstanding document is what finishes onboarding.
        // Nothing else in the system decides this, and no scheduled job does it
        // later: the supplier is approved the moment the last review lands.
        return if (!outstanding && !awaitingReview && supplier.stage == OnboardingStage.IN_REVIEW) {
            moveTo(supplier, OnboardingStage.APPROVED, actor)
        } else {
            supplier.stage
        }
    }
}
