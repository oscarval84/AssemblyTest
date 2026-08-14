package com.acme.onboarding.application.supplier

import com.acme.onboarding.adapter.persistence.ActivityEventRepository
import com.acme.onboarding.adapter.persistence.ActivityRow
import com.acme.onboarding.adapter.persistence.CatalogRepository
import com.acme.onboarding.adapter.persistence.EnrollmentRepository
import com.acme.onboarding.adapter.persistence.SupplierProfileUpdate
import com.acme.onboarding.adapter.persistence.SupplierRecord
import com.acme.onboarding.adapter.persistence.SupplierRepository
import com.acme.onboarding.adapter.persistence.UserRecord
import com.acme.onboarding.adapter.persistence.UserRepository
import com.acme.onboarding.application.audit.ActivityRecorder
import com.acme.onboarding.application.audit.AuditAction
import com.acme.onboarding.application.auth.AuthenticationService
import com.acme.onboarding.application.auth.InvitationService
import com.acme.onboarding.application.onboarding.StageProgression
import com.acme.onboarding.application.support.InvalidRequestException
import com.acme.onboarding.application.support.NotFoundException
import com.acme.onboarding.domain.onboarding.OnboardingStage
import com.acme.onboarding.domain.user.AccessDeniedException
import com.acme.onboarding.domain.user.Actor
import com.acme.onboarding.domain.user.Role
import com.acme.onboarding.domain.user.UserStatus
import org.springframework.security.crypto.encrypt.BytesEncryptor
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.util.UUID

/** Everything ops supplies to start onboarding a company. */
data class NewSupplierRequest(
    val legalName: String,
    val contactName: String,
    val contactEmail: String,
    val programIds: List<UUID>,
)

/** The editable profile, as the supplier's own form submits it. */
data class ProfileUpdateRequest(
    val legalName: String,
    val dbaName: String?,
    val entityType: String?,
    /** Nine digits, sent once and never returned. Null leaves the stored value alone. */
    val taxId: String?,
    val addressLine1: String?,
    val addressLine2: String?,
    val city: String?,
    val state: String?,
    val postalCode: String?,
    val primaryContactName: String?,
    val primaryContactEmail: String?,
    val primaryContactPhone: String?,
)

@Service
class SupplierService(
    private val suppliers: SupplierRepository,
    private val enrollments: EnrollmentRepository,
    private val catalog: CatalogRepository,
    private val users: UserRepository,
    private val events: ActivityEventRepository,
    private val assembler: SupplierAssembler,
    private val invitations: InvitationService,
    private val authentication: AuthenticationService,
    private val progression: StageProgression,
    private val recorder: ActivityRecorder,
    private val fieldEncryptor: BytesEncryptor,
) {

    @Transactional(readOnly = true)
    fun list(actor: Actor): List<SupplierSummary> {
        if (actor.role == Role.SUPPLIER_USER) {
            throw AccessDeniedException("Supplier users see their own company only.")
        }
        val scope = programScope(actor)
        return suppliers.list()
            .map { assembler.snapshot(it) }
            .filter { snapshot -> scope == null || snapshot.enrollments.any { it.programId in scope } }
            .map(assembler::summary)
            .sortedWith(compareBy({ it.stage.ordinal }, { it.legalName }))
    }

    @Transactional(readOnly = true)
    fun detail(actor: Actor, supplierId: UUID): SupplierDetail =
        assembler.detail(visibleSnapshot(actor, supplierId))

    @Transactional(readOnly = true)
    fun checklist(actor: Actor, supplierId: UUID): ChecklistView =
        assembler.checklist(visibleSnapshot(actor, supplierId))

    @Transactional(readOnly = true)
    fun profile(actor: Actor, supplierId: UUID): SupplierProfileView =
        assembler.profile(visibleSnapshot(actor, supplierId).supplier)

    @Transactional(readOnly = true)
    fun activity(actor: Actor, supplierId: UUID): List<ActivityRow> {
        visibleSnapshot(actor, supplierId)
        return events.timeline(supplierId.toString())
    }

    @Transactional(readOnly = true)
    fun supplierUsers(actor: Actor, supplierId: UUID): List<SupplierUserView> {
        visibleSnapshot(actor, supplierId)
        return users.listForSupplier(supplierId).map {
            SupplierUserView(
                id = it.id,
                email = it.email,
                fullName = it.fullName,
                status = it.status.name,
                lastLoginAt = it.lastLoginAt,
            )
        }
    }

    /**
     * Supplier intake: the company record, its programs and the first user's
     * invitation, in one transaction.
     *
     * All of it or none of it — a supplier row with no invited user is a company
     * that exists in the pipeline and has never heard from Acme, which is the
     * failure mode the spreadsheet has today.
     */
    @Transactional
    fun createAndInvite(actor: Actor, request: NewSupplierRequest): SupplierDetail {
        actor.requireOps()

        if (request.legalName.isBlank()) {
            throw InvalidRequestException("Enter the company's legal name.")
        }
        if (request.programIds.isEmpty()) {
            throw InvalidRequestException(
                "Choose at least one program. The program decides which documents we ask for.",
            )
        }

        val supplierId = suppliers.insert(
            legalName = request.legalName,
            contactName = request.contactName,
            contactEmail = request.contactEmail,
        )

        recorder.record(
            action = AuditAction.SUPPLIER_INVITED,
            subjectType = "SUPPLIER",
            subjectId = supplierId,
            actor = actor,
            supplierId = supplierId,
            after = mapOf("legalName" to request.legalName, "contactEmail" to request.contactEmail),
        )

        request.programIds.forEach { programId -> enroll(actor, supplierId, programId) }
        invitations.inviteSupplierUser(actor, supplierId, request.contactEmail, request.contactName)

        return assembler.detail(assembler.snapshot(suppliers.findById(supplierId)!!))
    }

    @Transactional
    fun enroll(actor: Actor, supplierId: UUID, programId: UUID) {
        actor.requireOps()
        val program = catalog.programs().firstOrNull { it.id == programId }
            ?: throw NotFoundException("That program no longer exists.")

        val existing = enrollments.listForSupplier(supplierId)
        if (existing.any { it.programId == programId }) return

        val enrollmentId = enrollments.insert(supplierId, programId)
        recorder.record(
            action = AuditAction.SUPPLIER_ENROLLED,
            subjectType = "ENROLLMENT",
            subjectId = enrollmentId,
            actor = actor,
            supplierId = supplierId,
            after = mapOf("program" to program.name, "programCode" to program.code),
        )
    }

    @Transactional
    fun inviteUser(actor: Actor, supplierId: UUID, email: String, fullName: String): UUID =
        invitations.inviteSupplierUser(actor, supplierId, email, fullName)

    /**
     * Ends one supplier user's access, from inside that supplier's record.
     *
     * The binding check below is the whole control. Without it this endpoint
     * would be a second, unguarded route to deactivating an Acme staff account —
     * reachable by ops rather than by an admin — which is exactly what keeping
     * the two administration surfaces apart is meant to prevent.
     */
    @Transactional
    fun deactivateUser(actor: Actor, supplierId: UUID, userId: UUID) {
        val user = supplierUser(actor, supplierId, userId)

        users.updateStatus(userId, UserStatus.DEACTIVATED)
        val revoked = authentication.revokeAllSessions(userId)

        recorder.record(
            action = AuditAction.USER_DEACTIVATED,
            subjectType = "USER",
            subjectId = userId,
            actor = actor,
            supplierId = supplierId,
            before = mapOf("status" to user.status.name),
            after = mapOf("status" to UserStatus.DEACTIVATED.name, "sessionsRevoked" to revoked),
        )
    }

    @Transactional
    fun reactivateUser(actor: Actor, supplierId: UUID, userId: UUID) {
        val user = supplierUser(actor, supplierId, userId)

        // Someone who never set a password goes back to INVITED rather than
        // ACTIVE, or reactivation leaves an account nobody can sign in to and
        // nothing flags as incomplete.
        val restored = if (user.passwordHash == null) UserStatus.INVITED else UserStatus.ACTIVE
        users.updateStatus(userId, restored)

        recorder.record(
            action = AuditAction.USER_REACTIVATED,
            subjectType = "USER",
            subjectId = userId,
            actor = actor,
            supplierId = supplierId,
            before = mapOf("status" to user.status.name),
            after = mapOf("status" to restored.name),
        )
    }

    private fun supplierUser(actor: Actor, supplierId: UUID, userId: UUID): UserRecord {
        actor.requireOps()
        visibleSnapshot(actor, supplierId)

        val user = users.findById(userId)
            ?: throw NotFoundException("That user no longer exists.")

        if (user.role != Role.SUPPLIER_USER || user.supplierId != supplierId) {
            throw AccessDeniedException(
                "That account does not belong to this supplier. Acme staff are managed from " +
                    "staff administration.",
            )
        }
        return user
    }

    /**
     * Saves the company profile.
     *
     * The tax ID is the one field that never comes back out: it is encrypted on
     * the way in, only its last four digits are stored in the clear, and the
     * audit event records that it was set rather than what it was set to. An
     * audit log that quotes a taxpayer identification number is a second copy of
     * the secret in a table designed to be impossible to delete from.
     */
    @Transactional
    fun updateProfile(actor: Actor, supplierId: UUID, request: ProfileUpdateRequest): SupplierProfileView {
        actor.requireCanEditSupplier(supplierId)
        val supplier = suppliers.findById(supplierId)
            ?: throw NotFoundException("That supplier no longer exists.")

        if (request.legalName.isBlank()) {
            throw InvalidRequestException("Enter your company's legal name, exactly as it appears on your W-9.")
        }

        suppliers.updateProfile(
            supplierId,
            SupplierProfileUpdate(
                legalName = request.legalName,
                dbaName = request.dbaName,
                entityType = request.entityType,
                addressLine1 = request.addressLine1,
                addressLine2 = request.addressLine2,
                city = request.city,
                state = request.state,
                postalCode = request.postalCode,
                primaryContactName = request.primaryContactName,
                primaryContactEmail = request.primaryContactEmail,
                primaryContactPhone = request.primaryContactPhone,
            ),
        )

        request.taxId?.let { storeTaxId(supplierId, it) }

        recorder.record(
            action = AuditAction.SUPPLIER_PROFILE_UPDATED,
            subjectType = "SUPPLIER",
            subjectId = supplierId,
            actor = actor,
            supplierId = supplierId,
            before = mapOf(
                "legalName" to supplier.legalName,
                "entityType" to supplier.entityType,
                "city" to supplier.city,
                "state" to supplier.state,
            ),
            after = mapOf(
                "legalName" to request.legalName,
                "entityType" to request.entityType,
                "city" to request.city,
                "state" to request.state,
                "taxIdProvided" to (request.taxId != null),
            ),
        )

        val saved = suppliers.findById(supplierId)!!
        if (saved.stage == OnboardingStage.REGISTERED && saved.isProfileComplete()) {
            progression.moveTo(saved, OnboardingStage.PROFILE_SUBMITTED, actor)
        }

        return assembler.profile(suppliers.findById(supplierId)!!)
    }

    private fun storeTaxId(supplierId: UUID, rawTaxId: String) {
        val digits = rawTaxId.filter(Char::isDigit)
        if (digits.length != TAX_ID_DIGITS) {
            throw InvalidRequestException(
                "A US tax ID has nine digits — an EIN like 12-3456789, or a Social Security number " +
                    "for a sole proprietor.",
            )
        }
        suppliers.updateTaxId(
            id = supplierId,
            encrypted = fieldEncryptor.encrypt(digits.toByteArray(Charsets.UTF_8)),
            last4 = digits.takeLast(4),
        )
    }

    /**
     * Loads a supplier and proves the caller may see it.
     *
     * Program managers are scoped to their programs, and that scope lives in a
     * table rather than in the session, which is why this check is a query and
     * not a claim inspection.
     */
    internal fun visibleSnapshot(actor: Actor, supplierId: UUID): SupplierAssembler.Snapshot {
        actor.requireAccessTo(supplierId)
        val supplier: SupplierRecord = suppliers.findById(supplierId)
            ?: throw NotFoundException("That supplier no longer exists.")

        val snapshot = assembler.snapshot(supplier)
        val scope = programScope(actor)
        if (scope != null && snapshot.enrollments.none { it.programId in scope }) {
            throw AccessDeniedException("That supplier is not in one of your programs.")
        }
        return snapshot
    }

    /** Null means "no program restriction"; an empty set means "sees nothing". */
    private fun programScope(actor: Actor): Set<UUID>? =
        if (actor.role == Role.PROGRAM_MANAGER) users.programScope(actor.userId).toSet() else null

    private companion object {
        const val TAX_ID_DIGITS = 9
    }
}
