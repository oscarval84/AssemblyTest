package com.acme.onboarding.domain.onboarding

/**
 * Where a supplier sits in the onboarding process.
 *
 * This is the supplier's own progress toward being approved by Acme. Whether
 * they are *active* is a property of each program enrollment, not of the
 * supplier, and is deliberately absent here.
 */
enum class OnboardingStage {
    /** Invited but has not yet accepted. No user account is usable yet. */
    INVITED,

    /** Accepted the invitation and set a password. Profile still empty. */
    REGISTERED,

    /** Company profile submitted. Document collection can begin. */
    PROFILE_SUBMITTED,

    /** At least one requirement is still unmet or unsubmitted. */
    DOCUMENTS_IN_PROGRESS,

    /** Everything is submitted and waiting on Acme. The ball is on our side. */
    IN_REVIEW,

    /** Ops rejected something. The ball is back with the supplier. */
    CHANGES_REQUESTED,

    /** Acme has approved the supplier. Enrollments may now be activated. */
    APPROVED,
    ;

    /**
     * Whether the supplier is the party currently expected to act.
     *
     * Drives the pipeline's "who are we waiting on" grouping, which is the
     * question Marcus's team answers by hand today.
     */
    val awaitingSupplier: Boolean
        get() = this in setOf(INVITED, REGISTERED, PROFILE_SUBMITTED, DOCUMENTS_IN_PROGRESS, CHANGES_REQUESTED)
}

/**
 * The legal transitions of the onboarding state machine.
 *
 * Kept as data rather than scattered `if` statements so that the set of legal
 * moves can be asserted in a single test, and so an illegal transition fails
 * loudly at the boundary instead of silently corrupting a supplier's history.
 */
object OnboardingTransitions {

    private val allowed: Map<OnboardingStage, Set<OnboardingStage>> = mapOf(
        OnboardingStage.INVITED to setOf(OnboardingStage.REGISTERED),
        OnboardingStage.REGISTERED to setOf(OnboardingStage.PROFILE_SUBMITTED),
        OnboardingStage.PROFILE_SUBMITTED to setOf(OnboardingStage.DOCUMENTS_IN_PROGRESS),

        // Documents can go straight to review once every requirement is met.
        OnboardingStage.DOCUMENTS_IN_PROGRESS to setOf(OnboardingStage.IN_REVIEW),

        // Review either approves, or bounces back with a rejection reason.
        OnboardingStage.IN_REVIEW to setOf(
            OnboardingStage.APPROVED,
            OnboardingStage.CHANGES_REQUESTED,
        ),

        // A supplier fixing a rejected document re-enters document collection.
        OnboardingStage.CHANGES_REQUESTED to setOf(OnboardingStage.DOCUMENTS_IN_PROGRESS),

        // Approval is not terminal: an expiring document can reopen collection,
        // which is exactly the lapse that went unnoticed in Acme's past audits.
        OnboardingStage.APPROVED to setOf(OnboardingStage.DOCUMENTS_IN_PROGRESS),
    )

    fun isAllowed(from: OnboardingStage, to: OnboardingStage): Boolean =
        to in allowed.getOrDefault(from, emptySet())

    fun nextStages(from: OnboardingStage): Set<OnboardingStage> =
        allowed.getOrDefault(from, emptySet())

    /**
     * The shortest legal route from one stage to another, excluding [from].
     *
     * Some events imply more than one move — a supplier who fills in their
     * profile and immediately uploads their last document has gone from
     * `PROFILE_SUBMITTED` to `IN_REVIEW`, through a stage nobody saw. Walking the
     * route records each step, so the timeline shows what happened instead of a
     * jump that the transition table does not permit.
     *
     * Returns null when no route exists, which callers must treat as a bug
     * rather than as "close enough".
     */
    fun path(from: OnboardingStage, to: OnboardingStage): List<OnboardingStage>? {
        if (from == to) return emptyList()

        val previous = mutableMapOf<OnboardingStage, OnboardingStage>()
        val queue = ArrayDeque(listOf(from))
        val seen = mutableSetOf(from)

        while (queue.isNotEmpty()) {
            val stage = queue.removeFirst()
            for (next in nextStages(stage)) {
                if (!seen.add(next)) continue
                previous[next] = stage
                if (next == to) {
                    val route = mutableListOf(to)
                    var cursor = to
                    while (cursor != from) {
                        cursor = previous.getValue(cursor)
                        if (cursor != from) route.add(cursor)
                    }
                    return route.reversed()
                }
                queue.addLast(next)
            }
        }
        return null
    }

    /**
     * @throws IllegalStageTransition if the move is not legal. Callers are
     * expected to let this propagate: a rejected transition is a bug or a
     * concurrent edit, never something to paper over.
     */
    fun require(from: OnboardingStage, to: OnboardingStage) {
        if (!isAllowed(from, to)) throw IllegalStageTransition(from, to)
    }
}

class IllegalStageTransition(
    val from: OnboardingStage,
    val to: OnboardingStage,
) : IllegalStateException("Cannot move supplier from $from to $to")
