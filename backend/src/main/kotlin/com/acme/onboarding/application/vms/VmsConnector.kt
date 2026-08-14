package com.acme.onboarding.application.vms

import com.fasterxml.jackson.annotation.JsonIgnore
import java.time.Instant
import java.time.LocalDate

/**
 * One supplier–program assignment, as the VMS describes it.
 *
 * Deliberately small. Everything here is owned by the VMS — who the supplier is
 * and which program they are engaged on — and nothing here is onboarding
 * evidence, which this system owns. Naming that split is what stops two systems
 * each believing they are authoritative about the same field.
 */
data class VmsAssignment(
    val externalAssignmentId: String,
    val externalSupplierId: String,
    val supplierLegalName: String,
    val contactName: String,
    val contactEmail: String,
    /** Matched against `program.code`; an unknown program is reported, not invented. */
    val programCode: String,
    val startsOn: LocalDate?,
    val raisedAt: Instant,
)

/** What goes back to the VMS when something here changes. */
sealed interface OnboardingUpdate {
    val externalSupplierId: String

    /** Derived from the class, and carried on the message row rather than in the payload. */
    @get:JsonIgnore
    val type: String

    /** Onboarding finished. The VMS is waiting on exactly this. */
    data class SupplierActivated(
        override val externalSupplierId: String,
        val legalName: String,
        val activatedOn: LocalDate,
        val satisfiedRequirements: List<String>,
    ) : OnboardingUpdate {
        @get:JsonIgnore override val type = "SUPPLIER_ACTIVATED"
    }

    /** Compliance moved. If the VMS gates placements, this is what it gates on. */
    data class ComplianceChanged(
        override val externalSupplierId: String,
        val externalAssignmentId: String?,
        val status: String,
        val effectiveOn: LocalDate,
        val detail: String,
    ) : OnboardingUpdate {
        @get:JsonIgnore override val type = "COMPLIANCE_CHANGED"
    }

    /** An executed agreement exists. The artifact itself only if the VMS takes documents. */
    data class AgreementExecuted(
        override val externalSupplierId: String,
        val signedOn: LocalDate,
        val templateVersion: String,
        val artifactReference: String,
    ) : OnboardingUpdate {
        @get:JsonIgnore override val type = "AGREEMENT_EXECUTED"
    }
}

/**
 * The boundary between this product and whichever VMS Acme runs.
 *
 * No vendor types reach the domain, and no domain types cross this port. v1
 * ships one implementation against a simulated VMS; Fieldglass, Beeline, VNDLY
 * and Bullhorn differ in API shape, auth and whether they can push at all, and
 * that entire difference lives in the adapter.
 *
 * This is a working integration against a simulated VMS, not a proven
 * integration with a named vendor. What it demonstrates is the contract, the
 * automation and the reliability machinery.
 */
interface VmsConnector {

    /** Which system this speaks to, recorded on every message for the log. */
    val systemName: String

    /**
     * Assignments that have reached a state meaning "needs onboarding".
     *
     * [since] lets an adapter ask for a window, but callers must not depend on
     * it for correctness: a connector is free to return everything, and the sync
     * is idempotent precisely so that replaying yesterday is harmless.
     */
    fun fetchPendingAssignments(since: Instant): List<VmsAssignment>

    /** Throwing marks the message failed and schedules a retry with backoff. */
    fun publishOnboardingUpdate(update: OnboardingUpdate)
}
