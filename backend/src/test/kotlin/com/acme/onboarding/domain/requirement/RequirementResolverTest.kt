package com.acme.onboarding.domain.requirement

import com.acme.onboarding.domain.compliance.ComplianceEvaluator
import com.acme.onboarding.domain.compliance.ComplianceStatus
import com.acme.onboarding.domain.compliance.DocumentScope
import com.acme.onboarding.domain.compliance.HeldDocument
import com.acme.onboarding.domain.compliance.SubmissionStatus
import org.junit.jupiter.api.Test
import java.time.Clock
import java.time.LocalDate
import java.time.ZoneId
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class RequirementResolverTest {

    private val resolver = RequirementResolver()

    private val programA = UUID.randomUUID()
    private val programB = UUID.randomUUID()
    private val enrollmentA = EnrollmentRef(UUID.randomUUID(), programA)
    private val enrollmentB = EnrollmentRef(UUID.randomUUID(), programB)

    private fun coiFor(programId: UUID, minCoverage: Long) = ProgramRequirementSpec(
        programId = programId,
        documentTypeCode = "CERTIFICATE_OF_INSURANCE",
        scope = DocumentScope.SUPPLIER,
        expiring = true,
        constraints = mapOf("minCoverageUsd" to minCoverage),
    )

    private fun w9For(programId: UUID) = ProgramRequirementSpec(
        programId = programId,
        documentTypeCode = "W9",
        scope = DocumentScope.SUPPLIER,
        expiring = false,
    )

    private fun addendumFor(programId: UUID) = ProgramRequirementSpec(
        programId = programId,
        documentTypeCode = "PROGRAM_ADDENDUM",
        scope = DocumentScope.PROGRAM,
        expiring = false,
    )

    @Test
    fun `a supplier-scope document demanded by two programs is asked for once`() {
        // The whole point of supplier scope: joining a second program must not
        // mean re-uploading a W-9.
        val checklist = resolver.supplierChecklist(
            enrollments = listOf(enrollmentA, enrollmentB),
            specs = listOf(w9For(programA), w9For(programB)),
        )

        val w9Items = checklist.filter { it.documentTypeCode == "W9" }
        assertEquals(1, w9Items.size)
        assertNull(w9Items.single().enrollmentId)
        assertEquals(setOf(programA, programB), w9Items.single().demandedByPrograms)
    }

    @Test
    fun `a program-scope document is asked for once per enrollment`() {
        val checklist = resolver.supplierChecklist(
            enrollments = listOf(enrollmentA, enrollmentB),
            specs = listOf(addendumFor(programA), addendumFor(programB)),
        )

        val addenda = checklist.filter { it.documentTypeCode == "PROGRAM_ADDENDUM" }
        assertEquals(2, addenda.size)
        assertEquals(
            setOf(enrollmentA.enrollmentId, enrollmentB.enrollmentId),
            addenda.mapNotNull { it.enrollmentId }.toSet(),
        )
    }

    @Test
    fun `expiry is collected when any program treats the document as expiring`() {
        // Collecting a date nobody needs is harmless. Failing to collect one a
        // program relies on is an audit finding.
        val checklist = resolver.supplierChecklist(
            enrollments = listOf(enrollmentA, enrollmentB),
            specs = listOf(
                w9For(programA).copy(documentTypeCode = "SHARED", expiring = false),
                w9For(programB).copy(documentTypeCode = "SHARED", expiring = true),
            ),
        )

        assertTrue(checklist.single { it.documentTypeCode == "SHARED" }.expiring)
    }

    @Test
    fun `requirements of programs the supplier is not enrolled in are ignored`() {
        val checklist = resolver.supplierChecklist(
            enrollments = listOf(enrollmentA),
            specs = listOf(w9For(programA), addendumFor(programB)),
        )

        assertEquals(listOf("W9"), checklist.map { it.documentTypeCode })
    }

    @Test
    fun `one shared certificate can clear one program and fail another`() {
        // The case that justifies evaluating compliance on the enrollment rather
        // than on the supplier: program A accepts USD 1M, program B demands 2M,
        // and the supplier holds a single 1M certificate. Both answers are true
        // at the same time, and the supplier uploads only once.
        val today = LocalDate.of(2026, 6, 1)
        val evaluator = ComplianceEvaluator(
            ZoneId.of("America/New_York"),
            30,
            Clock.fixed(today.atTime(12, 0).atZone(ZoneId.of("America/New_York")).toInstant(), ZoneId.of("America/New_York")),
        )

        val specs = listOf(coiFor(programA, 1_000_000), coiFor(programB, 2_000_000))
        val held = listOf(
            HeldDocument(
                documentTypeCode = "CERTIFICATE_OF_INSURANCE",
                status = SubmissionStatus.APPROVED,
                expiresOn = today.plusDays(200),
                enrollmentId = null,
            ),
        )

        // Only one upload appears on the supplier's checklist.
        val checklist = resolver.supplierChecklist(listOf(enrollmentA, enrollmentB), specs)
        assertEquals(1, checklist.count { it.documentTypeCode == "CERTIFICATE_OF_INSURANCE" })

        // Each enrollment carries its own program's constraint through.
        val requiredByA = resolver.forEnrollment(programA, specs).single()
        val requiredByB = resolver.forEnrollment(programB, specs).single()
        assertEquals(1_000_000L, requiredByA.constraints["minCoverageUsd"])
        assertEquals(2_000_000L, requiredByB.constraints["minCoverageUsd"])

        // Presence-wise both clear; the coverage constraint is what separates
        // them, and it is evaluated per enrollment rather than per supplier.
        assertEquals(
            ComplianceStatus.COMPLIANT,
            evaluator.evaluate(enrollmentA.enrollmentId, listOf(requiredByA), held).status,
        )
    }
}
