package com.acme.onboarding.domain.pipeline

import com.acme.onboarding.domain.compliance.Finding
import com.acme.onboarding.domain.compliance.Issue
import com.acme.onboarding.domain.onboarding.OnboardingStage
import org.junit.jupiter.api.Test
import java.time.LocalDate
import kotlin.test.assertEquals

class BlockerDerivationTest {

    @Test
    fun `pre-document stages report their own blocker regardless of findings`() {
        assertEquals(
            WaitingOn.SUPPLIER,
            BlockerDerivation.derive(OnboardingStage.INVITED, emptyList()).waitingOn,
        )
        assertEquals(
            "Company profile not started",
            BlockerDerivation.derive(OnboardingStage.REGISTERED, emptyList()).summary,
        )
    }

    @Test
    fun `work sitting in Acme's queue outranks work sitting with the supplier`() {
        // A supplier who has submitted one document and still owes another is
        // Acme's problem first: that is the part Acme controls and is measured
        // on, and burying it under "1 document not yet submitted" is how review
        // queues quietly grow.
        val blocker = BlockerDerivation.derive(
            OnboardingStage.DOCUMENTS_IN_PROGRESS,
            listOf(
                Finding("W9", Issue.MISSING),
                Finding("CERTIFICATE_OF_INSURANCE", Issue.PENDING_REVIEW),
            ),
        )

        assertEquals(WaitingOn.ACME, blocker.waitingOn)
        assertEquals("1 document awaiting review", blocker.summary)
        assertEquals(listOf("CERTIFICATE_OF_INSURANCE"), blocker.documentTypeCodes)
    }

    @Test
    fun `an expired document outranks a merely missing one`() {
        val blocker = BlockerDerivation.derive(
            OnboardingStage.APPROVED,
            listOf(
                Finding("PROGRAM_ADDENDUM", Issue.MISSING),
                Finding("CERTIFICATE_OF_INSURANCE", Issue.EXPIRED, LocalDate.of(2026, 1, 1)),
            ),
        )

        assertEquals(WaitingOn.SUPPLIER, blocker.waitingOn)
        assertEquals("1 document expired", blocker.summary)
    }

    @Test
    fun `an expiring document names the renewal date`() {
        val due = LocalDate.of(2026, 9, 30)
        val blocker = BlockerDerivation.derive(
            OnboardingStage.APPROVED,
            listOf(Finding("CERTIFICATE_OF_INSURANCE", Issue.EXPIRING_SOON, due)),
        )

        assertEquals("Renewal due by $due", blocker.summary)
    }

    @Test
    fun `an approved supplier with nothing outstanding blocks on nobody`() {
        val blocker = BlockerDerivation.derive(OnboardingStage.APPROVED, emptyList())

        assertEquals(WaitingOn.NOBODY, blocker.waitingOn)
        assertEquals("Approved and compliant", blocker.summary)
    }

    @Test
    fun `counts are pluralised for the pipeline line`() {
        val blocker = BlockerDerivation.derive(
            OnboardingStage.DOCUMENTS_IN_PROGRESS,
            listOf(Finding("W9", Issue.MISSING), Finding("BANKING_FORM", Issue.MISSING)),
        )

        assertEquals("2 documents not yet submitted", blocker.summary)
    }
}
