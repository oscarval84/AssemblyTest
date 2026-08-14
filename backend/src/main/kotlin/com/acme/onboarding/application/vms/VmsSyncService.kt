package com.acme.onboarding.application.vms

import com.acme.onboarding.adapter.persistence.CatalogRepository
import com.acme.onboarding.adapter.persistence.EnrollmentRepository
import com.acme.onboarding.adapter.persistence.IntegrationMessageRecord
import com.acme.onboarding.adapter.persistence.IntegrationMessageRepository
import com.acme.onboarding.adapter.persistence.SupplierRepository
import com.acme.onboarding.adapter.persistence.UserRepository
import com.acme.onboarding.adapter.persistence.VmsConflictRepository
import com.acme.onboarding.adapter.persistence.VmsLinkRepository
import com.acme.onboarding.application.audit.ActivityRecorder
import com.acme.onboarding.application.audit.AuditAction
import com.acme.onboarding.application.auth.InvitationService
import com.acme.onboarding.application.support.NotFoundException
import com.acme.onboarding.domain.user.Actor
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Propagation
import org.springframework.transaction.annotation.Transactional
import org.springframework.transaction.support.TransactionTemplate
import tools.jackson.databind.ObjectMapper
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.util.UUID

data class VmsSyncResult(
    val system: String,
    val assignmentsSeen: Int,
    val suppliersCreated: Int,
    val enrollmentsCreated: Int,
    val conflictsRaised: Int,
    val unknownPrograms: Int,
)

data class VmsDrainResult(
    val system: String,
    val attempted: Int,
    val sent: Int,
    val failed: Int,
    val deadLettered: Int,
)

/**
 * The integration, in both directions.
 *
 * Inbound, a scheduled pull turns VMS assignments into onboarding that has
 * already started by the time anyone looks: Marcus finds the supplier in his
 * pipeline, at the right stage, with the right checklist, because the VMS said
 * so. Outbound, the result goes back to the system that owns the relationship.
 *
 * **Idempotency is the whole game.** Every operation keys on the external
 * identifier, so running yesterday's sync again changes nothing — and
 * re-invitation in particular must never happen twice, because a supplier who
 * receives the same invitation every morning is how a demo becomes a support
 * ticket.
 */
@Service
class VmsSyncService(
    private val connector: VmsConnector,
    private val links: VmsLinkRepository,
    private val messages: IntegrationMessageRepository,
    private val conflicts: VmsConflictRepository,
    private val suppliers: SupplierRepository,
    private val enrollments: EnrollmentRepository,
    private val catalog: CatalogRepository,
    private val users: UserRepository,
    private val invitations: InvitationService,
    private val recorder: ActivityRecorder,
    private val objectMapper: ObjectMapper,
    /**
     * Each message commits on its own, so one bad recipient cannot roll back
     * everything queued behind it. A template rather than `@Transactional`
     * because the boundary is *inside* this loop, and a self-invoked annotated
     * method would not open one at all — the proxy is only consulted for calls
     * arriving from outside the bean.
     */
    private val transactions: TransactionTemplate,
    private val clock: Clock,
) {

    private val log = LoggerFactory.getLogger(javaClass)

    // -- inbound --------------------------------------------------------------

    /**
     * Two methods rather than one with a default, and the reason is a trap worth
     * naming: a Kotlin default argument that reads a field compiles to a static
     * bridge which reads that field from the *proxy*, and this bean is proxied
     * for `@Transactional`. A CGLIB proxy's own fields are never initialised, so
     * the default would evaluate `clock` as null on every call.
     */
    @Transactional
    fun sync(): VmsSyncResult = sync(Instant.now(clock).minus(Duration.ofDays(7)))

    @Transactional
    fun sync(since: Instant): VmsSyncResult {
        val assignments = connector.fetchPendingAssignments(since)
        var suppliersCreated = 0
        var enrollmentsCreated = 0
        var conflictsRaised = 0
        var unknownPrograms = 0

        assignments.forEach { assignment ->
            val program = catalog.programByCode(assignment.programCode)
            if (program == null) {
                // Never invent a program to match one in another system: the
                // mapping is wrong, and only a human can say which way.
                unknownPrograms++
                recordInbound(
                    assignment = assignment,
                    supplierId = null,
                    outcome = "UNKNOWN_PROGRAM",
                    status = "FAILED",
                    error = "No local program with code ${assignment.programCode}",
                )
                return@forEach
            }

            val existing = links.findLocalId(connector.systemName, SUPPLIER, assignment.externalSupplierId)
            val supplierId = existing ?: createSupplier(assignment).also { suppliersCreated++ }

            if (existing != null && flagDivergence(supplierId, assignment)) conflictsRaised++

            if (enrol(supplierId, program.id, assignment)) enrollmentsCreated++
        }

        val result = VmsSyncResult(
            system = connector.systemName,
            assignmentsSeen = assignments.size,
            suppliersCreated = suppliersCreated,
            enrollmentsCreated = enrollmentsCreated,
            conflictsRaised = conflictsRaised,
            unknownPrograms = unknownPrograms,
        )

        // A line even when nothing changed. "We checked and there was nothing
        // new" is what an operator needs when they are wondering whether the
        // integration is alive at all.
        messages.enqueue(
            direction = "INBOUND",
            targetSystem = connector.systemName,
            messageType = "ASSIGNMENT_SYNC",
            externalRef = null,
            supplierId = null,
            payloadJson = objectMapper.writeValueAsString(result),
            status = "RECEIVED",
        )

        log.info("VMS sync: {}", result)
        return result
    }

    private fun createSupplier(assignment: VmsAssignment): UUID {
        val supplierId = suppliers.insert(
            legalName = assignment.supplierLegalName,
            contactName = assignment.contactName,
            contactEmail = assignment.contactEmail,
        )
        links.link(connector.systemName, SUPPLIER, supplierId, assignment.externalSupplierId)

        recorder.record(
            action = AuditAction.SUPPLIER_INVITED,
            subjectType = "SUPPLIER",
            subjectId = supplierId,
            actor = systemActor(),
            supplierId = supplierId,
            after = mapOf(
                "legalName" to assignment.supplierLegalName,
                "source" to connector.systemName,
                "externalSupplierId" to assignment.externalSupplierId,
            ),
        )

        // The invitation is the only thing this path sends, and it is sent once:
        // the supplier link created above is what makes the next sync skip it.
        invitations.inviteSupplierUser(
            actor = systemActor(),
            supplierId = supplierId,
            email = assignment.contactEmail,
            fullName = assignment.contactName,
        )

        recordInbound(assignment, supplierId, outcome = "SUPPLIER_CREATED", status = "RECEIVED", error = null)
        return supplierId
    }

    /**
     * Creates the enrollment, unless this assignment already produced one.
     *
     * A known supplier joining a second program is the common case, and the one
     * where everything already on file is reused: no new company, no second
     * invitation, and a checklist that opens mostly satisfied.
     */
    private fun enrol(supplierId: UUID, programId: UUID, assignment: VmsAssignment): Boolean {
        val linked = links.findLocalId(connector.systemName, ENROLLMENT, assignment.externalAssignmentId)
        if (linked != null) return false

        val existing = enrollments.listForSupplier(supplierId).firstOrNull { it.programId == programId }
        val enrollmentId = existing?.id ?: enrollments.insert(supplierId, programId)
        links.link(connector.systemName, ENROLLMENT, enrollmentId, assignment.externalAssignmentId)

        if (existing != null) return false

        recorder.record(
            action = AuditAction.SUPPLIER_ENROLLED,
            subjectType = "ENROLLMENT",
            subjectId = enrollmentId,
            actor = systemActor(),
            supplierId = supplierId,
            after = mapOf(
                "programCode" to assignment.programCode,
                "source" to connector.systemName,
                "externalAssignmentId" to assignment.externalAssignmentId,
                "startsOn" to assignment.startsOn?.toString(),
            ),
        )
        recordInbound(assignment, supplierId, outcome = "ENROLLED", status = "RECEIVED", error = null)
        return true
    }

    /**
     * Raises a flag when the two systems disagree, and changes neither.
     *
     * The VMS says "Northwind Staffing LLC"; the approved W-9 says "Northwind
     * Staffing Partners". One of them is wrong, a human decides which, and
     * silent resolution in either direction is how two systems diverge in a way
     * nobody can reconstruct later.
     */
    private fun flagDivergence(supplierId: UUID, assignment: VmsAssignment): Boolean {
        val supplier = suppliers.findById(supplierId) ?: return false
        if (supplier.legalName.equals(assignment.supplierLegalName, ignoreCase = true)) return false

        conflicts.record(
            supplierId = supplierId,
            field = "legalName",
            localValue = supplier.legalName,
            remoteValue = assignment.supplierLegalName,
        )
        recorder.record(
            action = AuditAction.VMS_CONFLICT_RAISED,
            subjectType = "SUPPLIER",
            subjectId = supplierId,
            actor = systemActor(),
            supplierId = supplierId,
            before = mapOf("legalName" to supplier.legalName),
            after = mapOf("legalName" to assignment.supplierLegalName, "source" to connector.systemName),
        )
        return true
    }

    // -- outbound -------------------------------------------------------------

    /**
     * Queues a writeback in the caller's transaction.
     *
     * `MANDATORY` for the reason the whole outbox exists: the VMS must not be
     * told about an activation that rolled back.
     */
    @Transactional(propagation = Propagation.MANDATORY)
    fun enqueue(supplierId: UUID, update: OnboardingUpdate) {
        messages.enqueue(
            direction = "OUTBOUND",
            targetSystem = connector.systemName,
            messageType = update.type,
            externalRef = update.externalSupplierId,
            supplierId = supplierId,
            payloadJson = objectMapper.writeValueAsString(update),
        )
    }

    /**
     * The external id for a supplier, or null when this supplier never came from
     * the VMS.
     *
     * A locally-created supplier is never pushed as a new VMS entity — this tool
     * does not invent records in someone else's system of record. It shows up in
     * the integration log as unlinked instead.
     */
    @Transactional(propagation = Propagation.MANDATORY, readOnly = true)
    fun externalSupplierId(supplierId: UUID): String? =
        links.findExternalId(connector.systemName, SUPPLIER, supplierId)

    fun drain(batchSize: Int = 25): VmsDrainResult {
        val due = messages.claimDue(batchSize)
        var sent = 0
        var failed = 0

        due.forEach { message ->
            try {
                transactions.executeWithoutResult {
                    connector.publishOnboardingUpdate(rehydrate(message))
                    messages.markSent(message.id, Instant.now(clock))
                    recordDisclosure(message)
                }
                sent++
            } catch (error: Exception) {
                log.warn("VMS push failed for {} ({})", message.messageType, message.id, error)
                messages.markFailed(
                    id = message.id,
                    error = error.message ?: error.javaClass.simpleName,
                    // Exponential, from the attempt count already recorded.
                    backoff = Duration.ofMinutes(2L shl minOf(message.attempts, 5)),
                    maxAttempts = MAX_ATTEMPTS,
                )
                failed++
            }
        }

        return VmsDrainResult(
            system = connector.systemName,
            attempted = due.size,
            sent = sent,
            failed = failed,
            deadLettered = messages.countDeadLettered(),
        )
    }

    /**
     * Records that data left the system.
     *
     * Every transmission is an audit event regardless of where it goes — the
     * same treatment an external AI processor gets. "When was the VMS told, and
     * what did we send" is part of Dana's audit story.
     */
    private fun recordDisclosure(message: IntegrationMessageRecord) {
        recorder.record(
            action = AuditAction.VMS_UPDATE_SENT,
            subjectType = "INTEGRATION",
            subjectId = message.id,
            actor = systemActor(),
            supplierId = message.supplierId,
            after = mapOf(
                "type" to message.messageType,
                "system" to message.targetSystem,
                "externalRef" to message.externalRef,
            ),
        )
    }

    /**
     * Reads a queued payload back into the update it was written from.
     *
     * Symmetric with how it was serialised, rather than walking the JSON by
     * hand: the message type on the row says which shape to expect, and an
     * unknown one is a bug worth failing on rather than a message to drop.
     */
    private fun rehydrate(message: IntegrationMessageRecord): OnboardingUpdate {
        val type = when (message.messageType) {
            "SUPPLIER_ACTIVATED" -> OnboardingUpdate.SupplierActivated::class.java
            "COMPLIANCE_CHANGED" -> OnboardingUpdate.ComplianceChanged::class.java
            "AGREEMENT_EXECUTED" -> OnboardingUpdate.AgreementExecuted::class.java
            else -> throw IllegalStateException("Unknown outbound message type ${message.messageType}")
        }
        return objectMapper.readValue(message.payload, type)
    }

    // -- ops-facing -----------------------------------------------------------

    @Transactional(readOnly = true)
    fun messages(actor: Actor, limit: Int = 200): List<IntegrationMessageRecord> {
        actor.requireOps()
        return messages.listRecent(limit)
    }

    @Transactional
    fun retry(actor: Actor, messageId: UUID) {
        actor.requireOps()
        if (!messages.requeue(messageId)) {
            throw NotFoundException("That message is not waiting to be retried.")
        }
    }

    private fun recordInbound(
        assignment: VmsAssignment,
        supplierId: UUID?,
        outcome: String,
        status: String,
        error: String?,
    ) {
        messages.enqueue(
            direction = "INBOUND",
            targetSystem = connector.systemName,
            messageType = outcome,
            externalRef = assignment.externalAssignmentId,
            supplierId = supplierId,
            payloadJson = objectMapper.writeValueAsString(
                mapOf(
                    "externalSupplierId" to assignment.externalSupplierId,
                    "supplierLegalName" to assignment.supplierLegalName,
                    "programCode" to assignment.programCode,
                    "startsOn" to assignment.startsOn?.toString(),
                    "error" to error,
                ),
            ),
            status = status,
        )
    }

    /**
     * The integration's own account.
     *
     * Acting as a named principal rather than as "system" means every supplier
     * it creates and every event it writes attributes to something the access
     * report lists. The account is deactivated and has no password, so it can
     * never sign in — status gates authentication only.
     */
    private fun systemActor(): Actor =
        users.findByEmail(SERVICE_ACCOUNT_EMAIL)?.toActor()
            ?: error("The VMS integration account is missing; V6 creates it")

    private companion object {
        const val SUPPLIER = "SUPPLIER"
        const val ENROLLMENT = "ENROLLMENT"
        const val SERVICE_ACCOUNT_EMAIL = "vms-sync@acme-msp.example"
        const val MAX_ATTEMPTS = 6
    }
}
