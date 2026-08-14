package com.acme.onboarding.domain.audit

import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

/**
 * The detector, tested by tampering with a chain.
 *
 * Verification is offered to auditors next to the export, which means a
 * verifier that always answered "intact" would pass every other test in this
 * suite and quietly turn the strongest claim the product makes into decoration.
 * These cases are the three shapes tampering takes, including the careful one:
 * rewriting an event *and* recomputing its hash, which is what someone who
 * understands the scheme would try.
 */
class ChainVerifierTest {

    @Test
    fun `an untouched chain verifies`() {
        assertEquals(ChainVerification.Intact, ChainVerifier.verify(CHAIN, link(events(3))))
    }

    @Test
    fun `an event rewritten in place no longer matches its own hash`() {
        val stored = link(events(3)).toMutableList()
        val target = stored[1]

        // The stored hash is left alone, which is the realistic case: whoever
        // reached the row edited the content, not the digest column.
        stored[1] = target.copy(payload = target.payload.copy(action = "DOCUMENT_APPROVED"))

        val verification = ChainVerifier.verify(CHAIN, stored)
        assertIs<ChainVerification.Broken>(verification)
        assertEquals(2, verification.sequence)
        assertEquals(ChainVerification.Broken.Reason.CONTENT_ALTERED, verification.reason)
    }

    @Test
    fun `rewriting an event and recomputing its hash breaks its successor instead`() {
        val stored = link(events(3))
        val forged = stored[1].payload.copy(action = "DOCUMENT_APPROVED")
        val forgedHash = EventHasher.hash(stored[1].prevHash, forged)

        val tampered = listOf(
            stored[0],
            StoredEvent(forged, stored[1].prevHash, forgedHash),
            // Untouched, and still pointing at the hash the second event used
            // to have. This is the property the chain buys: a convincing edit
            // requires rewriting every event after it.
            stored[2],
        )

        val verification = ChainVerifier.verify(CHAIN, tampered)
        assertIs<ChainVerification.Broken>(verification)
        assertEquals(3, verification.sequence)
        assertEquals(ChainVerification.Broken.Reason.PREDECESSOR_MISMATCH, verification.reason)
    }

    @Test
    fun `a deleted event leaves a gap where it used to be`() {
        val stored = link(events(3))

        val verification = ChainVerifier.verify(CHAIN, listOf(stored[0], stored[2]))
        assertIs<ChainVerification.Broken>(verification)
        assertEquals(2, verification.sequence)
        assertEquals(ChainVerification.Broken.Reason.SEQUENCE_GAP, verification.reason)
    }

    // -- helpers --------------------------------------------------------------

    private fun events(count: Int): List<AuditEventPayload> = (1..count).map { sequence ->
        AuditEventPayload(
            chainKey = CHAIN,
            sequence = sequence.toLong(),
            actorLabel = "Marcus Lee <marcus.lee@acme-msp.example>",
            actorUserId = null,
            action = "DOCUMENT_UPLOADED",
            subjectType = "DOCUMENT",
            subjectId = null,
            beforeState = null,
            afterState = """{"documentType":"W9","version":$sequence}""",
            requestOrigin = "POST /api/suppliers/$CHAIN/documents from 198.51.100.7",
            occurredAt = START.plusSeconds(sequence.toLong()),
        )
    }

    /** Hashes a run of payloads into a chain, the way the recorder does. */
    private fun link(payloads: List<AuditEventPayload>): List<StoredEvent> {
        var prevHash = EventHasher.GENESIS_HASH
        return payloads.map { payload ->
            val eventHash = EventHasher.hash(prevHash, payload)
            StoredEvent(payload, prevHash, eventHash).also { prevHash = eventHash }
        }
    }

    private companion object {
        const val CHAIN = "8f1d3c62-1f2a-4a5e-9d21-0c3b7a5e4f10"
        val START: Instant = Instant.parse("2026-08-13T14:00:00Z")
    }
}
