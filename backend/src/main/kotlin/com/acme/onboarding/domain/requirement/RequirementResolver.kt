package com.acme.onboarding.domain.requirement

import com.acme.onboarding.domain.compliance.DocumentScope
import com.acme.onboarding.domain.compliance.RequiredDocument
import java.util.UUID

/**
 * One document type a program demands, with that program's own constraints.
 */
data class ProgramRequirementSpec(
    val programId: UUID,
    val documentTypeCode: String,
    val scope: DocumentScope,
    val expiring: Boolean,
    val constraints: Map<String, Any?> = emptyMap(),
)

/** A supplier's enrollment into one program. */
data class EnrollmentRef(
    val enrollmentId: UUID,
    val programId: UUID,
)

/**
 * An entry on the supplier's upload checklist.
 *
 * [enrollmentId] is null for supplier-scope documents — uploaded once, shared
 * everywhere. [demandedByPrograms] exists so the supplier portal can explain
 * *why* a document is being asked for, which matters when a two-person agency is
 * looking at a list of obligations and deciding whether it is worth their time.
 */
data class ChecklistItem(
    val documentTypeCode: String,
    val scope: DocumentScope,
    val expiring: Boolean,
    val enrollmentId: UUID?,
    val demandedByPrograms: Set<UUID>,
)

/**
 * Turns program requirements into what a supplier must actually provide.
 *
 * The rule, and the reason it is shaped this way:
 *
 *  - **Supplier-scope requirements are satisfied once, globally.** A supplier
 *    onboarding into a second program does not re-upload their W-9. Asking twice
 *    is precisely the friction that has suppliers describing the current process
 *    as faxing paperwork into a void.
 *
 *  - **Program-scope requirements are satisfied per enrollment.**
 *
 *  - **When two programs demand the same supplier-scope document under different
 *    constraints, the document is shared and each enrollment evaluates its own
 *    constraint against it.** A supplier holding a single USD 1M certificate can
 *    therefore be compliant for one program and non-compliant for another,
 *    simultaneously. That is the correct answer, and it is only expressible
 *    because compliance lives on the enrollment rather than on the supplier.
 */
class RequirementResolver {

    /**
     * What one enrollment demands, carrying that program's constraints.
     *
     * Includes supplier-scope requirements: they are shared documents, but each
     * enrollment still evaluates them under its own program's terms.
     */
    fun forEnrollment(
        programId: UUID,
        specs: List<ProgramRequirementSpec>,
    ): List<RequiredDocument> =
        specs.filter { it.programId == programId }
            .map {
                RequiredDocument(
                    documentTypeCode = it.documentTypeCode,
                    scope = it.scope,
                    expiring = it.expiring,
                    constraints = it.constraints,
                )
            }

    /**
     * The supplier's upload checklist across every program they are enrolled in.
     *
     * Supplier-scope entries are collapsed to one item naming every program that
     * demands it. Program-scope entries stay one per enrollment.
     */
    fun supplierChecklist(
        enrollments: List<EnrollmentRef>,
        specs: List<ProgramRequirementSpec>,
    ): List<ChecklistItem> {
        val enrolledProgramIds = enrollments.map { it.programId }.toSet()
        val relevant = specs.filter { it.programId in enrolledProgramIds }

        val supplierScope = relevant
            .filter { it.scope == DocumentScope.SUPPLIER }
            .groupBy { it.documentTypeCode }
            .map { (code, group) ->
                ChecklistItem(
                    documentTypeCode = code,
                    scope = DocumentScope.SUPPLIER,
                    // If any program treats it as expiring, expiry is collected.
                    // Collecting a date nobody needs is harmless; failing to
                    // collect one a program relies on is an audit finding.
                    expiring = group.any { it.expiring },
                    enrollmentId = null,
                    demandedByPrograms = group.map { it.programId }.toSet(),
                )
            }

        val programScope = enrollments.flatMap { enrollment ->
            relevant
                .filter { it.programId == enrollment.programId && it.scope == DocumentScope.PROGRAM }
                .map {
                    ChecklistItem(
                        documentTypeCode = it.documentTypeCode,
                        scope = DocumentScope.PROGRAM,
                        expiring = it.expiring,
                        enrollmentId = enrollment.enrollmentId,
                        demandedByPrograms = setOf(it.programId),
                    )
                }
        }

        return supplierScope + programScope
    }
}
