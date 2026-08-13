package com.acme.onboarding.domain.compliance

import org.junit.jupiter.api.Test
import java.time.Clock
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZoneOffset
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Compliance is the feature Acme got audited on twice, so the boundaries matter
 * more than the happy path. These tests pin the two failure modes that produce a
 * wrong answer nobody notices: the expiry off-by-one, and resolving "today" in
 * the wrong time zone.
 */
class ComplianceEvaluatorTest {

    private val newYork: ZoneId = ZoneId.of("America/New_York")
    private val enrollment: UUID = UUID.randomUUID()
    private val otherEnrollment: UUID = UUID.randomUUID()

    private val coi = RequiredDocument(
        documentTypeCode = "CERTIFICATE_OF_INSURANCE",
        scope = DocumentScope.SUPPLIER,
        expiring = true,
    )
    private val w9 = RequiredDocument(
        documentTypeCode = "W9",
        scope = DocumentScope.SUPPLIER,
        expiring = false,
    )

    /** Fixes "now" at midday New York time on the given date. */
    private fun evaluatorOn(date: LocalDate, warningDays: Long = 30): ComplianceEvaluator {
        val instant = date.atTime(12, 0).atZone(newYork).toInstant()
        return ComplianceEvaluator(newYork, warningDays, Clock.fixed(instant, newYork))
    }

    private fun approvedCoi(expiresOn: LocalDate?) = HeldDocument(
        documentTypeCode = "CERTIFICATE_OF_INSURANCE",
        status = SubmissionStatus.APPROVED,
        expiresOn = expiresOn,
        enrollmentId = null,
    )

    private fun approvedW9() = HeldDocument(
        documentTypeCode = "W9",
        status = SubmissionStatus.APPROVED,
        expiresOn = null,
        enrollmentId = null,
    )

    @Test
    fun `everything approved and well clear of expiry is compliant`() {
        val result = evaluatorOn(LocalDate.of(2026, 6, 1)).evaluate(
            enrollmentId = enrollment,
            required = listOf(coi, w9),
            held = listOf(approvedCoi(LocalDate.of(2026, 12, 31)), approvedW9()),
        )

        assertEquals(ComplianceStatus.COMPLIANT, result.status)
        assertTrue(result.findings.isEmpty())
    }

    @Test
    fun `a document is not yet expired on its expiry date`() {
        // The boundary that matters: a certificate reading "expires 2026-09-15"
        // is good through the end of the 15th. Flagging it EXPIRED that morning
        // means telling a supplier who is still covered that they are not.
        //
        // It is legitimately EXPIRING_SOON — zero days of margin is inside any
        // warning band — but that is a nudge, not a compliance failure.
        val expiry = LocalDate.of(2026, 9, 15)

        val result = evaluatorOn(expiry, warningDays = 0).evaluate(
            enrollmentId = enrollment,
            required = listOf(coi),
            held = listOf(approvedCoi(expiry)),
        )

        assertEquals(Issue.EXPIRING_SOON, result.findings.single().issue)
        assertEquals(ComplianceStatus.EXPIRING_SOON, result.status)
    }

    @Test
    fun `a document is expired the day after its expiry date`() {
        val expiry = LocalDate.of(2026, 9, 15)

        val result = evaluatorOn(expiry.plusDays(1), warningDays = 0).evaluate(
            enrollmentId = enrollment,
            required = listOf(coi),
            held = listOf(approvedCoi(expiry)),
        )

        assertEquals(ComplianceStatus.NON_COMPLIANT, result.status)
        assertEquals(Issue.EXPIRED, result.findings.single().issue)
    }

    @Test
    fun `today is resolved in Acme's business zone, not UTC`() {
        // 02:00 UTC on 16 September is still 22:00 on the 15th in New York. A
        // certificate expiring on the 15th is valid for Acme's ops team, and a
        // sweep that resolved the date in UTC would expire it a day early —
        // exactly the kind of false finding an auditor would catch.
        val expiry = LocalDate.of(2026, 9, 15)
        val instant = Instant.parse("2026-09-16T02:00:00Z")

        val inBusinessZone = ComplianceEvaluator(newYork, 0, Clock.fixed(instant, ZoneOffset.UTC))
        assertEquals(expiry, inBusinessZone.today())

        // In New York the certificate is still in force — a nudge, not a lapse.
        val inBusinessZoneResult = inBusinessZone.evaluate(
            enrollmentId = enrollment,
            required = listOf(coi),
            held = listOf(approvedCoi(expiry)),
        )
        assertEquals(Issue.EXPIRING_SOON, inBusinessZoneResult.findings.single().issue)

        // The same instant read in UTC has already rolled over, and would report
        // the supplier as lapsed a day early.
        val inUtc = ComplianceEvaluator(ZoneOffset.UTC, 0, Clock.fixed(instant, ZoneOffset.UTC))
        assertEquals(expiry.plusDays(1), inUtc.today())

        val inUtcResult = inUtc.evaluate(
            enrollmentId = enrollment,
            required = listOf(coi),
            held = listOf(approvedCoi(expiry)),
        )
        assertEquals(Issue.EXPIRED, inUtcResult.findings.single().issue)
    }

    @Test
    fun `a document expiring exactly at the warning threshold is expiring soon`() {
        val today = LocalDate.of(2026, 6, 1)

        val result = evaluatorOn(today, warningDays = 30).evaluate(
            enrollmentId = enrollment,
            required = listOf(coi),
            held = listOf(approvedCoi(today.plusDays(30))),
        )

        assertEquals(ComplianceStatus.EXPIRING_SOON, result.status)
        assertEquals(today.plusDays(30), result.nextExpiry)
    }

    @Test
    fun `a document expiring one day past the warning threshold is still clean`() {
        val today = LocalDate.of(2026, 6, 1)

        val result = evaluatorOn(today, warningDays = 30).evaluate(
            enrollmentId = enrollment,
            required = listOf(coi),
            held = listOf(approvedCoi(today.plusDays(31))),
        )

        assertEquals(ComplianceStatus.COMPLIANT, result.status)
    }

    @Test
    fun `missing, pending and rejected all block compliance for different reasons`() {
        val today = LocalDate.of(2026, 6, 1)
        val evaluator = evaluatorOn(today)

        val missing = evaluator.evaluate(enrollment, listOf(w9), emptyList())
        assertEquals(Issue.MISSING, missing.findings.single().issue)

        val pending = evaluator.evaluate(
            enrollment, listOf(w9),
            listOf(HeldDocument("W9", SubmissionStatus.PENDING, null, null)),
        )
        assertEquals(Issue.PENDING_REVIEW, pending.findings.single().issue)

        val rejected = evaluator.evaluate(
            enrollment, listOf(w9),
            listOf(HeldDocument("W9", SubmissionStatus.REJECTED, null, null)),
        )
        assertEquals(Issue.REJECTED, rejected.findings.single().issue)

        listOf(missing, pending, rejected).forEach {
            assertEquals(ComplianceStatus.NON_COMPLIANT, it.status)
        }
    }

    @Test
    fun `the worst finding determines the enrollment status`() {
        val today = LocalDate.of(2026, 6, 1)

        val result = evaluatorOn(today).evaluate(
            enrollmentId = enrollment,
            required = listOf(coi, w9),
            held = listOf(approvedCoi(today.plusDays(10))), // expiring soon, W-9 missing
        )

        // Expiring soon must not mask a missing document.
        assertEquals(ComplianceStatus.NON_COMPLIANT, result.status)
        assertEquals(2, result.findings.size)
    }

    @Test
    fun `a supplier-scope document satisfies every enrollment`() {
        val today = LocalDate.of(2026, 6, 1)
        val evaluator = evaluatorOn(today)
        val shared = listOf(approvedCoi(today.plusDays(200)), approvedW9())

        // The same held documents clear both enrollments without re-upload.
        listOf(enrollment, otherEnrollment).forEach {
            assertEquals(ComplianceStatus.COMPLIANT, evaluator.evaluate(it, listOf(coi, w9), shared).status)
        }
    }

    @Test
    fun `a program-scope document belonging to another enrollment does not count`() {
        val today = LocalDate.of(2026, 6, 1)
        val addendum = RequiredDocument("PROGRAM_ADDENDUM", DocumentScope.PROGRAM, expiring = false)

        val result = evaluatorOn(today).evaluate(
            enrollmentId = enrollment,
            required = listOf(addendum),
            held = listOf(
                HeldDocument("PROGRAM_ADDENDUM", SubmissionStatus.APPROVED, null, otherEnrollment),
            ),
        )

        assertEquals(ComplianceStatus.NON_COMPLIANT, result.status)
        assertEquals(Issue.MISSING, result.findings.single().issue)
    }
}
