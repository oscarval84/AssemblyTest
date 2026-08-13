package com.acme.onboarding.application.supplier

import com.acme.onboarding.domain.compliance.ComplianceStatus
import com.acme.onboarding.domain.compliance.DocumentScope
import com.acme.onboarding.domain.compliance.Issue
import com.acme.onboarding.domain.onboarding.OnboardingStage
import com.acme.onboarding.domain.pipeline.WaitingOn
import java.time.Instant
import java.time.LocalDate
import java.util.UUID

/** What a supplier is blocked on. Derived on every read, never stored. */
data class BlockerView(
    val waitingOn: WaitingOn,
    val summary: String,
    val documentTypeCodes: List<String>,
)

data class SupplierProfileView(
    val id: UUID,
    val legalName: String,
    val dbaName: String?,
    val entityType: String?,
    /** Only ever the last four. The full value has no read path. */
    val taxIdLast4: String?,
    val addressLine1: String?,
    val addressLine2: String?,
    val city: String?,
    val state: String?,
    val postalCode: String?,
    val country: String,
    val primaryContactName: String?,
    val primaryContactEmail: String?,
    val primaryContactPhone: String?,
    val stage: OnboardingStage,
    val complete: Boolean,
    val updatedAt: Instant,
)

/** One row of the ops pipeline. */
data class SupplierSummary(
    val id: UUID,
    val legalName: String,
    val dbaName: String?,
    val stage: OnboardingStage,
    val blocker: BlockerView,
    val complianceStatus: ComplianceStatus,
    val programNames: List<String>,
    val primaryContactEmail: String?,
    val nextExpiry: LocalDate?,
    val updatedAt: Instant,
)

data class FindingView(
    val documentTypeCode: String,
    val documentTypeName: String,
    val issue: Issue,
    val expiresOn: LocalDate?,
)

data class EnrollmentView(
    val enrollmentId: UUID,
    val programId: UUID,
    val programCode: String,
    val programName: String,
    val status: String,
    val complianceStatus: ComplianceStatus,
    val nextExpiry: LocalDate?,
    val findings: List<FindingView>,
)

data class SupplierDetail(
    val profile: SupplierProfileView,
    val blocker: BlockerView,
    val enrollments: List<EnrollmentView>,
    val complianceStatus: ComplianceStatus,
)

/**
 * Where one requirement stands for one supplier.
 *
 * Named for what the supplier sees rather than for the database column: a
 * rejected document is `CHANGES_REQUESTED` here, because "rejected" describes
 * Acme's action and "changes requested" describes what the supplier has to do.
 */
enum class RequirementState {
    NOT_STARTED,
    IN_REVIEW,
    APPROVED,
    EXPIRING_SOON,
    EXPIRED,
    CHANGES_REQUESTED,
    ;

    val needsSupplierAction: Boolean
        get() = this == NOT_STARTED || this == CHANGES_REQUESTED || this == EXPIRED
}

data class SubmissionView(
    val id: UUID,
    val version: Int,
    val originalFilename: String,
    val contentType: String,
    val sizeBytes: Long,
    val issuedOn: LocalDate?,
    val expiresOn: LocalDate?,
    val uploadedAt: Instant,
    val uploadedByName: String?,
    val reviewedAt: Instant?,
    val reviewedByName: String?,
    val rejectionReasonLabel: String?,
    val rejectionNote: String?,
    val signedAt: Instant?,
    val signedBy: String?,
)

data class ChecklistEntry(
    val documentTypeCode: String,
    val documentTypeName: String,
    val description: String?,
    val scope: DocumentScope,
    val expiring: Boolean,
    val requiresSignature: Boolean,
    val classification: String,
    /** Program-specific terms, e.g. a coverage minimum. Rendered as written. */
    val constraints: Map<String, Any?>,
    /** True when one document satisfies every program at once. */
    val shared: Boolean,
    val state: RequirementState,
    val enrollmentId: UUID?,
    val submission: SubmissionView?,
)

/**
 * One program's checklist, split the way the client described the experience:
 * what is already on file and needs nothing, and what is net-new for this program.
 */
data class ProgramChecklist(
    val enrollmentId: UUID,
    val programId: UUID,
    val programCode: String,
    val programName: String,
    val programDescription: String?,
    val enrollmentStatus: String,
    val complianceStatus: ComplianceStatus,
    val nextExpiry: LocalDate?,
    val alreadyOnFile: List<ChecklistEntry>,
    val neededForThisProgram: List<ChecklistEntry>,
)

data class ChecklistView(
    val supplierId: UUID,
    val legalName: String,
    val stage: OnboardingStage,
    val profileComplete: Boolean,
    val blocker: BlockerView,
    val programs: List<ProgramChecklist>,
)

data class SupplierUserView(
    val id: UUID,
    val email: String,
    val fullName: String,
    val status: String,
    val lastLoginAt: Instant?,
)
