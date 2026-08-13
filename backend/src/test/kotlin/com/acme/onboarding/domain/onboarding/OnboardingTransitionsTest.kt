package com.acme.onboarding.domain.onboarding

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

class OnboardingTransitionsTest {

    @Test
    fun `the happy path is a single unbroken route`() {
        assertEquals(
            listOf(
                OnboardingStage.REGISTERED,
                OnboardingStage.PROFILE_SUBMITTED,
                OnboardingStage.DOCUMENTS_IN_PROGRESS,
                OnboardingStage.IN_REVIEW,
                OnboardingStage.APPROVED,
            ),
            OnboardingTransitions.path(OnboardingStage.INVITED, OnboardingStage.APPROVED),
        )
    }

    @Test
    fun `a supplier who finishes their documents in one sitting still records every step`() {
        // The event log is what Dana hands an auditor. A jump from
        // PROFILE_SUBMITTED straight to IN_REVIEW would be a history with a hole
        // in it, and the transition table forbids it anyway.
        assertEquals(
            listOf(OnboardingStage.DOCUMENTS_IN_PROGRESS, OnboardingStage.IN_REVIEW),
            OnboardingTransitions.path(OnboardingStage.PROFILE_SUBMITTED, OnboardingStage.IN_REVIEW),
        )
    }

    @Test
    fun `reopening an approved supplier is legal, because certificates expire`() {
        assertEquals(
            listOf(OnboardingStage.DOCUMENTS_IN_PROGRESS),
            OnboardingTransitions.path(OnboardingStage.APPROVED, OnboardingStage.DOCUMENTS_IN_PROGRESS),
        )
    }

    @Test
    fun `there is no route backwards to registration`() {
        assertNull(OnboardingTransitions.path(OnboardingStage.IN_REVIEW, OnboardingStage.REGISTERED))
        assertNull(OnboardingTransitions.path(OnboardingStage.APPROVED, OnboardingStage.INVITED))
    }

    @Test
    fun `a route to the current stage is empty rather than absent`() {
        assertEquals(emptyList(), OnboardingTransitions.path(OnboardingStage.IN_REVIEW, OnboardingStage.IN_REVIEW))
    }

    @Test
    fun `an illegal move fails loudly`() {
        val failure = assertFailsWith<IllegalStageTransition> {
            OnboardingTransitions.require(OnboardingStage.INVITED, OnboardingStage.APPROVED)
        }
        assertEquals(OnboardingStage.INVITED, failure.from)
        assertEquals(OnboardingStage.APPROVED, failure.to)
    }

    @Test
    fun `only the stages where the supplier owes us something are supplier-side`() {
        assertTrue(OnboardingStage.CHANGES_REQUESTED.awaitingSupplier)
        assertTrue(!OnboardingStage.IN_REVIEW.awaitingSupplier)
        assertTrue(!OnboardingStage.APPROVED.awaitingSupplier)
    }
}
