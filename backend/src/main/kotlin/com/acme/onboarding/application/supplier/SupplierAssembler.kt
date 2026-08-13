package com.acme.onboarding.application.supplier

import com.acme.onboarding.adapter.persistence.CatalogRepository
import com.acme.onboarding.adapter.persistence.EnrollmentRecord
import com.acme.onboarding.adapter.persistence.EnrollmentRepository
import com.acme.onboarding.adapter.persistence.ProgramRequirementRecord
import com.acme.onboarding.adapter.persistence.SignatureRepository
import com.acme.onboarding.adapter.persistence.SubmissionRecord
import com.acme.onboarding.adapter.persistence.SubmissionRepository
import com.acme.onboarding.adapter.persistence.SupplierRecord
import com.acme.onboarding.domain.compliance.ComplianceEvaluator
import com.acme.onboarding.domain.compliance.ComplianceStatus
import com.acme.onboarding.domain.compliance.DocumentScope
import com.acme.onboarding.domain.compliance.EnrollmentCompliance
import com.acme.onboarding.domain.compliance.Finding
import com.acme.onboarding.domain.compliance.HeldDocument
import com.acme.onboarding.domain.compliance.Issue
import com.acme.onboarding.domain.pipeline.BlockerDerivation
import com.acme.onboarding.domain.requirement.ProgramRequirementSpec
import com.acme.onboarding.domain.requirement.RequirementResolver
import org.springframework.stereotype.Component
import java.util.UUID

/**
 * Turns rows into the views ops and suppliers read.
 *
 * It exists so that "what is this supplier's status" is computed in exactly one
 * place. The pipeline, the supplier record and the supplier's own checklist all
 * read from here, which is what makes the product's promise — that both sides
 * see the same truth — a property of the code rather than a habit.
 */
@Component
class SupplierAssembler(
    private val enrollments: EnrollmentRepository,
    private val catalog: CatalogRepository,
    private val submissions: SubmissionRepository,
    private val signatures: SignatureRepository,
    private val resolver: RequirementResolver,
    private val evaluator: ComplianceEvaluator,
) {

    /** Everything one supplier's views need, read once. */
    data class Snapshot(
        val supplier: SupplierRecord,
        val enrollments: List<EnrollmentRecord>,
        val requirements: List<ProgramRequirementRecord>,
        val current: List<SubmissionRecord>,
        val compliance: Map<UUID, EnrollmentCompliance>,
    ) {
        val allFindings: List<Finding> get() = compliance.values.flatMap { it.findings }

        val overallStatus: ComplianceStatus
            get() = compliance.values
                .map { it.status }
                .maxByOrNull { it.severity }
                ?: ComplianceStatus.COMPLIANT
    }

    fun snapshot(supplier: SupplierRecord): Snapshot {
        val supplierEnrollments = enrollments.listForSupplier(supplier.id)
        val requirements = catalog.requirementsForPrograms(supplierEnrollments.map { it.programId }.toSet())
        val current = submissions.listCurrentForSupplier(supplier.id)
        val held = current.map { it.toHeldDocument() }
        val specs = requirements.map { it.toSpec() }

        val compliance = supplierEnrollments.associate { enrollment ->
            enrollment.id to evaluator.evaluate(
                enrollmentId = enrollment.id,
                required = resolver.forEnrollment(enrollment.programId, specs),
                held = held,
            )
        }

        return Snapshot(supplier, supplierEnrollments, requirements, current, compliance)
    }

    fun blocker(snapshot: Snapshot): BlockerView {
        val derived = BlockerDerivation.derive(snapshot.supplier.stage, snapshot.allFindings)
        return BlockerView(derived.waitingOn, derived.summary, derived.documentTypeCodes)
    }

    fun summary(snapshot: Snapshot): SupplierSummary = SupplierSummary(
        id = snapshot.supplier.id,
        legalName = snapshot.supplier.legalName,
        dbaName = snapshot.supplier.dbaName,
        stage = snapshot.supplier.stage,
        blocker = blocker(snapshot),
        complianceStatus = snapshot.overallStatus,
        programNames = snapshot.enrollments.map { it.programName },
        primaryContactEmail = snapshot.supplier.primaryContactEmail,
        nextExpiry = snapshot.compliance.values.mapNotNull { it.nextExpiry }.minOrNull(),
        updatedAt = snapshot.supplier.updatedAt,
    )

    fun detail(snapshot: Snapshot): SupplierDetail = SupplierDetail(
        profile = profile(snapshot.supplier),
        blocker = blocker(snapshot),
        complianceStatus = snapshot.overallStatus,
        enrollments = snapshot.enrollments.map { enrollment ->
            val compliance = snapshot.compliance.getValue(enrollment.id)
            EnrollmentView(
                enrollmentId = enrollment.id,
                programId = enrollment.programId,
                programCode = enrollment.programCode,
                programName = enrollment.programName,
                status = enrollment.status,
                complianceStatus = compliance.status,
                nextExpiry = compliance.nextExpiry,
                findings = compliance.findings.map { it.toView(snapshot) },
            )
        },
    )

    fun checklist(snapshot: Snapshot): ChecklistView {
        val programs = snapshot.enrollments.map { enrollment ->
            val compliance = snapshot.compliance.getValue(enrollment.id)
            val findingsByType = compliance.findings.associateBy { it.documentTypeCode }
            val entries = snapshot.requirements
                .filter { it.programId == enrollment.programId }
                .map { requirement -> entry(requirement, enrollment.id, snapshot, findingsByType[requirement.documentType.code]) }

            ProgramChecklist(
                enrollmentId = enrollment.id,
                programId = enrollment.programId,
                programCode = enrollment.programCode,
                programName = enrollment.programName,
                programDescription = enrollment.programDescription,
                enrollmentStatus = enrollment.status,
                complianceStatus = compliance.status,
                nextExpiry = compliance.nextExpiry,
                // Shown, not hidden: a supplier who opens an empty checklist
                // assumes the system lost their paperwork. Seeing "W-9 —
                // approved, no action needed" is how they learn it remembers them.
                alreadyOnFile = entries.filter { !it.state.needsSupplierAction && it.state != RequirementState.IN_REVIEW },
                neededForThisProgram = entries.filter { it.state.needsSupplierAction || it.state == RequirementState.IN_REVIEW },
            )
        }

        return ChecklistView(
            supplierId = snapshot.supplier.id,
            legalName = snapshot.supplier.legalName,
            stage = snapshot.supplier.stage,
            profileComplete = snapshot.supplier.isProfileComplete(),
            blocker = blocker(snapshot),
            programs = programs,
        )
    }

    fun profile(supplier: SupplierRecord): SupplierProfileView = SupplierProfileView(
        id = supplier.id,
        legalName = supplier.legalName,
        dbaName = supplier.dbaName,
        entityType = supplier.entityType,
        taxIdLast4 = supplier.taxIdLast4,
        addressLine1 = supplier.addressLine1,
        addressLine2 = supplier.addressLine2,
        city = supplier.city,
        state = supplier.state,
        postalCode = supplier.postalCode,
        country = supplier.country,
        primaryContactName = supplier.primaryContactName,
        primaryContactEmail = supplier.primaryContactEmail,
        primaryContactPhone = supplier.primaryContactPhone,
        stage = supplier.stage,
        complete = supplier.isProfileComplete(),
        updatedAt = supplier.updatedAt,
    )

    /**
     * The requirement's state, and the submission behind it.
     *
     * A supplier-scope document is looked up without an enrollment because one
     * copy satisfies every program — the reuse the client asked for is expressed
     * here, in which row is found, rather than by copying files between programs.
     */
    private fun entry(
        requirement: ProgramRequirementRecord,
        enrollmentId: UUID,
        snapshot: Snapshot,
        finding: Finding?,
    ): ChecklistEntry {
        val type = requirement.documentType
        val shared = type.scope == DocumentScope.SUPPLIER
        val submission = snapshot.current.firstOrNull {
            it.documentTypeCode == type.code &&
                if (shared) it.enrollmentId == null else it.enrollmentId == enrollmentId
        }

        return ChecklistEntry(
            documentTypeCode = type.code,
            documentTypeName = type.name,
            description = type.description,
            scope = type.scope,
            expiring = type.expiring,
            requiresSignature = type.requiresSignature,
            classification = type.classification,
            constraints = requirement.constraints,
            shared = shared,
            state = finding.toState(),
            enrollmentId = if (shared) null else enrollmentId,
            submission = submission?.toView(),
        )
    }

    private fun SubmissionRecord.toView(): SubmissionView {
        val signature = if (requiresSignatureLookup()) signatures.findBySubmission(id) else null
        return SubmissionView(
            id = id,
            version = version,
            originalFilename = originalFilename,
            contentType = contentType,
            sizeBytes = sizeBytes,
            issuedOn = issuedOn,
            expiresOn = expiresOn,
            uploadedAt = uploadedAt,
            uploadedByName = uploadedByName,
            reviewedAt = reviewedAt,
            reviewedByName = reviewedByName,
            rejectionReasonLabel = rejectionReasonLabel,
            rejectionNote = rejectionNote,
            signedAt = signature?.signedAt,
            signedBy = signature?.typedName,
        )
    }

    /** Signature lookups are one query each, so they are only made where one can exist. */
    private fun SubmissionRecord.requiresSignatureLookup(): Boolean =
        documentTypeCode == "SUPPLIER_AGREEMENT" || documentTypeCode == "PROGRAM_ADDENDUM"

    private fun Finding?.toState(): RequirementState = when (this?.issue) {
        null -> RequirementState.APPROVED
        Issue.MISSING -> RequirementState.NOT_STARTED
        Issue.PENDING_REVIEW -> RequirementState.IN_REVIEW
        Issue.REJECTED -> RequirementState.CHANGES_REQUESTED
        Issue.EXPIRED -> RequirementState.EXPIRED
        Issue.EXPIRING_SOON -> RequirementState.EXPIRING_SOON
    }

    private fun Finding.toView(snapshot: Snapshot): FindingView = FindingView(
        documentTypeCode = documentTypeCode,
        documentTypeName = snapshot.requirements
            .firstOrNull { it.documentType.code == documentTypeCode }
            ?.documentType?.name
            ?: documentTypeCode,
        issue = issue,
        expiresOn = expiresOn,
    )

    private fun SubmissionRecord.toHeldDocument() = HeldDocument(
        documentTypeCode = documentTypeCode,
        status = status,
        expiresOn = expiresOn,
        enrollmentId = enrollmentId,
    )

    private fun ProgramRequirementRecord.toSpec() = ProgramRequirementSpec(
        programId = programId,
        documentTypeCode = documentType.code,
        scope = documentType.scope,
        expiring = documentType.expiring,
        constraints = constraints,
    )
}

/**
 * Enough of a profile to review. Deliberately not "every field is non-null":
 * `dba_name` and a second address line are optional facts, and demanding them
 * would block onboarding on information some suppliers do not have.
 */
internal fun SupplierRecord.isProfileComplete(): Boolean =
    legalName.isNotBlank() &&
        !entityType.isNullOrBlank() &&
        !addressLine1.isNullOrBlank() &&
        !city.isNullOrBlank() &&
        !state.isNullOrBlank() &&
        !postalCode.isNullOrBlank() &&
        !primaryContactName.isNullOrBlank() &&
        !primaryContactEmail.isNullOrBlank() &&
        !taxIdLast4.isNullOrBlank()
