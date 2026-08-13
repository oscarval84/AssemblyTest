package com.acme.onboarding.domain.audit

import java.security.MessageDigest
import java.time.Instant
import java.util.UUID

/**
 * The content of one audit event, in the form its hash is computed over.
 *
 * Deliberately free of persistence concerns: the hash must be reproducible from
 * what an auditor can read back out of the database, not from an ORM's view of
 * it. If verifying a chain required the application, the chain would only prove
 * that the application agrees with itself.
 */
data class AuditEventPayload(
    val chainKey: String,
    val sequence: Long,
    val actorLabel: String,
    val actorUserId: UUID?,
    val action: String,
    val subjectType: String,
    val subjectId: UUID?,
    val beforeState: String?,
    val afterState: String?,
    val requestOrigin: String?,
    val occurredAt: Instant,
)

/**
 * Computes the per-chain event hash.
 *
 * **Why length-prefixed encoding and not a delimiter.** The obvious approach is
 * to join the fields with a separator. It is also forgeable: with a plain
 * separator, an actor named `a|APPROVE` and an action of `x` hash identically to
 * an actor `a` performing `APPROVE|x`. Someone who controls any free-text field
 * — a supplier's legal name, a rejection note — could craft two different
 * histories with the same hash, and the chain would vouch for both.
 *
 * Prefixing every field with its byte length removes the ambiguity: there is
 * exactly one way to parse the encoded form, so exactly one payload produces a
 * given digest.
 */
object EventHasher {

    /** The predecessor hash of the first event in any chain. */
    const val GENESIS_HASH: String = "0000000000000000000000000000000000000000000000000000000000000000"

    fun hash(prevHash: String, payload: AuditEventPayload): String {
        val digest = MessageDigest.getInstance("SHA-256")
        val canonical = canonicalize(prevHash, payload)
        return digest.digest(canonical).toHexString()
    }

    internal fun canonicalize(prevHash: String, payload: AuditEventPayload): ByteArray {
        val builder = StringBuilder()
        listOf(
            prevHash,
            payload.chainKey,
            payload.sequence.toString(),
            payload.actorLabel,
            payload.actorUserId?.toString(),
            payload.action,
            payload.subjectType,
            payload.subjectId?.toString(),
            payload.beforeState,
            payload.afterState,
            payload.requestOrigin,
            // Fixed precision: an Instant that round-trips through the database
            // must serialise identically, or verification fails on a value that
            // never actually changed.
            payload.occurredAt.toEpochMilli().toString(),
        ).forEach { field ->
            if (field == null) {
                // Distinguishable from an empty string, which is a different
                // fact: "no rejection note" is not "an empty rejection note".
                builder.append("-1:")
            } else {
                val bytes = field.toByteArray(Charsets.UTF_8)
                builder.append(bytes.size).append(':').append(field)
            }
        }
        return builder.toString().toByteArray(Charsets.UTF_8)
    }

    private fun ByteArray.toHexString(): String =
        joinToString("") { "%02x".format(it) }
}

/** An event as read back from storage, for verification. */
data class StoredEvent(
    val payload: AuditEventPayload,
    val prevHash: String,
    val eventHash: String,
)

sealed interface ChainVerification {
    data object Intact : ChainVerification

    /**
     * The chain is broken at [sequence].
     *
     * [reason] distinguishes the two ways tampering shows up: a rewritten event
     * whose content no longer matches its stored hash, and a deleted event that
     * leaves a gap between a hash and its successor's `prev_hash`.
     */
    data class Broken(
        val chainKey: String,
        val sequence: Long,
        val reason: Reason,
    ) : ChainVerification {
        enum class Reason { CONTENT_ALTERED, PREDECESSOR_MISMATCH, SEQUENCE_GAP }
    }
}

/**
 * Walks a chain and reports the first break.
 *
 * This is what makes the audit log defensible rather than merely restricted. The
 * database trigger and the role grants prevent tampering through ordinary paths;
 * neither stops a superuser. The chain does not prevent that either — it makes
 * it *detectable*, which is the property an auditor actually needs.
 */
object ChainVerifier {

    fun verify(chainKey: String, events: List<StoredEvent>): ChainVerification {
        var expectedPrev = EventHasher.GENESIS_HASH
        var expectedSequence = 1L

        for (event in events.sortedBy { it.payload.sequence }) {
            val sequence = event.payload.sequence

            if (sequence != expectedSequence) {
                return ChainVerification.Broken(
                    chainKey, expectedSequence,
                    ChainVerification.Broken.Reason.SEQUENCE_GAP,
                )
            }

            if (event.prevHash != expectedPrev) {
                return ChainVerification.Broken(
                    chainKey, sequence,
                    ChainVerification.Broken.Reason.PREDECESSOR_MISMATCH,
                )
            }

            val recomputed = EventHasher.hash(event.prevHash, event.payload)
            if (recomputed != event.eventHash) {
                return ChainVerification.Broken(
                    chainKey, sequence,
                    ChainVerification.Broken.Reason.CONTENT_ALTERED,
                )
            }

            expectedPrev = event.eventHash
            expectedSequence++
        }

        return ChainVerification.Intact
    }
}
