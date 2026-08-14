package com.acme.onboarding.application.audit

import com.acme.onboarding.adapter.persistence.ActivityEventRepository
import com.acme.onboarding.domain.audit.AuditEventPayload
import com.acme.onboarding.domain.audit.ChainVerification
import com.acme.onboarding.domain.audit.ChainVerifier
import com.acme.onboarding.domain.audit.EventHasher
import com.acme.onboarding.domain.user.Actor
import org.springframework.stereotype.Component
import org.springframework.transaction.annotation.Propagation
import org.springframework.transaction.annotation.Transactional
import tools.jackson.databind.ObjectMapper
import java.time.Clock
import java.time.Instant
import java.util.UUID

/**
 * Where the caller came from, as recorded on every event.
 *
 * An interface in the application layer with a servlet implementation in the web
 * adapter, so a use case invoked by a scheduled job — which has no request —
 * records that fact instead of pretending to have one.
 */
interface RequestContext {
    fun origin(): String?
    fun ip(): String?
    fun userAgent(): String?
}

/**
 * Appends to the hash-chained audit log.
 *
 * `@Transactional(MANDATORY)` is deliberate: this must run inside the caller's
 * transaction, not alongside it. Two reasons, and both are the whole point of
 * the design — the event must not survive a rolled-back state change, and the
 * per-chain advisory lock is transaction-scoped, so a self-created transaction
 * would release the lock before the caller finished writing.
 */
@Component
class ActivityRecorder(
    private val events: ActivityEventRepository,
    private val objectMapper: ObjectMapper,
    private val requestContext: RequestContext,
    private val clock: Clock,
) {

    @Transactional(propagation = Propagation.MANDATORY)
    fun record(
        action: String,
        subjectType: String,
        subjectId: UUID?,
        actor: Actor?,
        supplierId: UUID? = null,
        before: Any? = null,
        after: Any? = null,
        systemActorLabel: String = "system",
    ): UUID {
        // One chain per supplier, plus a system chain for everything with no
        // supplier — user administration, scheduled jobs, sign-in activity.
        val chainKey = supplierId?.toString() ?: SYSTEM_CHAIN

        events.lockChain(chainKey)
        val tail = events.tail(chainKey)
        val prevHash = tail?.second ?: EventHasher.GENESIS_HASH
        val sequence = (tail?.first ?: 0L) + 1

        val payload = AuditEventPayload(
            chainKey = chainKey,
            sequence = sequence,
            actorLabel = actor?.label ?: systemActorLabel,
            actorUserId = actor?.userId,
            action = action,
            subjectType = subjectType,
            subjectId = subjectId,
            beforeState = before?.let { objectMapper.writeValueAsString(it) },
            afterState = after?.let { objectMapper.writeValueAsString(it) },
            requestOrigin = requestContext.origin(),
            // Truncated to milliseconds because that is the precision the column
            // round-trips at; hashing microseconds the database will not return
            // would make every event fail its own verification.
            occurredAt = Instant.now(clock).toEpochMilli().let(Instant::ofEpochMilli),
        )

        return events.append(payload, prevHash, EventHasher.hash(prevHash, payload))
    }

    /** Walks a supplier's chain — or the system chain — and reports the first break. */
    fun verify(chainKey: String): ChainVerification =
        ChainVerifier.verify(chainKey, events.chain(chainKey))

    companion object {
        const val SYSTEM_CHAIN = "SYSTEM"
    }
}

/**
 * The vocabulary of the audit log, in one place.
 *
 * Actions are strings in the database so a new one is not a migration, but they
 * are constants here so the timeline UI, the auditor export and the tests all
 * agree on their spelling.
 */
object AuditAction {
    const val USER_INVITED = "USER_INVITED"
    const val INVITATION_ACCEPTED = "INVITATION_ACCEPTED"
    const val USER_SIGNED_IN = "USER_SIGNED_IN"
    const val USER_SIGNED_OUT = "USER_SIGNED_OUT"
    const val USER_ROLE_CHANGED = "USER_ROLE_CHANGED"
    const val USER_SCOPE_CHANGED = "USER_SCOPE_CHANGED"
    const val USER_DEACTIVATED = "USER_DEACTIVATED"
    const val USER_REACTIVATED = "USER_REACTIVATED"
    const val PASSWORD_RESET_REQUESTED = "PASSWORD_RESET_REQUESTED"
    const val PASSWORD_RESET_COMPLETED = "PASSWORD_RESET_COMPLETED"

    const val SUPPLIER_INVITED = "SUPPLIER_INVITED"
    const val SUPPLIER_PROFILE_UPDATED = "SUPPLIER_PROFILE_UPDATED"
    const val SUPPLIER_STAGE_CHANGED = "SUPPLIER_STAGE_CHANGED"
    const val SUPPLIER_ENROLLED = "SUPPLIER_ENROLLED"

    const val DOCUMENT_UPLOADED = "DOCUMENT_UPLOADED"
    const val DOCUMENT_APPROVED = "DOCUMENT_APPROVED"
    const val DOCUMENT_REJECTED = "DOCUMENT_REJECTED"
    const val DOCUMENT_SIGNED = "DOCUMENT_SIGNED"

    /** Access is audited, not only change: "who read this W-9, and when". */
    const val DOCUMENT_ACCESSED = "DOCUMENT_ACCESSED"

    /**
     * Compliance status is computed, so these record the *transition* rather
     * than the state — the moment a certificate crossed a line, and the fact
     * that the supplier was told about it.
     */
    const val DOCUMENT_EXPIRING_SOON = "DOCUMENT_EXPIRING_SOON"
    const val DOCUMENT_EXPIRED = "DOCUMENT_EXPIRED"

    /**
     * Data leaving the system is an audit event regardless of where it goes —
     * the same treatment an external AI processor gets.
     */
    const val VMS_UPDATE_SENT = "VMS_UPDATE_SENT"
    const val VMS_CONFLICT_RAISED = "VMS_CONFLICT_RAISED"

    const val DEMO_DATA_RESET = "DEMO_DATA_RESET"
}
