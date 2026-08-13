package com.acme.onboarding.adapter.persistence

import com.acme.onboarding.domain.audit.AuditEventPayload
import com.acme.onboarding.domain.audit.StoredEvent
import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.stereotype.Repository
import java.sql.ResultSet
import java.time.Instant
import java.util.UUID

/** One event as the timeline and the auditor export read it. */
data class ActivityRow(
    val id: UUID,
    val chainKey: String,
    val sequence: Long,
    val actorLabel: String,
    val action: String,
    val subjectType: String,
    val subjectId: UUID?,
    val beforeState: String?,
    val afterState: String?,
    val occurredAt: Instant,
)

@Repository
class ActivityEventRepository(private val db: JdbcClient) {

    /**
     * Serialises writers on one chain for the rest of the transaction.
     *
     * `SELECT ... FOR UPDATE` would be the obvious way to hold the tail, but the
     * application's production role deliberately has no `UPDATE` on this table
     * (V2), and row locking requires it. A transaction-scoped advisory lock
     * needs no privileges on the row and releases on commit or rollback.
     */
    fun lockChain(chainKey: String) {
        db.sql("SELECT pg_advisory_xact_lock(hashtextextended(:chainKey, 0))")
            .param("chainKey", chainKey)
            .query().listOfRows()
    }

    /** The last event in the chain: its sequence and hash, or null if empty. */
    fun tail(chainKey: String): Pair<Long, String>? =
        db.sql(
            """
            SELECT sequence, event_hash
              FROM activity_event
             WHERE chain_key = :chainKey
             ORDER BY sequence DESC
             LIMIT 1
            """,
        )
            .param("chainKey", chainKey)
            .query { rs, _ -> rs.getLong("sequence") to rs.getString("event_hash") }
            .optional().orElse(null)

    fun append(payload: AuditEventPayload, prevHash: String, eventHash: String): UUID =
        db.sql(
            """
            INSERT INTO activity_event
                (chain_key, sequence, prev_hash, event_hash, actor_user_id, actor_label,
                 action, subject_type, subject_id, before_state, after_state,
                 request_origin, occurred_at)
            VALUES
                (:chainKey, :sequence, :prevHash, :eventHash, CAST(:actorUserId AS uuid), :actorLabel,
                 :action, :subjectType, CAST(:subjectId AS uuid), CAST(:beforeState AS json),
                 CAST(:afterState AS json), :requestOrigin, :occurredAt)
            RETURNING id
            """,
        )
            .param("chainKey", payload.chainKey)
            .param("sequence", payload.sequence)
            .param("prevHash", prevHash)
            .param("eventHash", eventHash)
            .param("actorUserId", payload.actorUserId?.toString())
            .param("actorLabel", payload.actorLabel)
            .param("action", payload.action)
            .param("subjectType", payload.subjectType)
            .param("subjectId", payload.subjectId?.toString())
            .param("beforeState", payload.beforeState)
            .param("afterState", payload.afterState)
            .param("requestOrigin", payload.requestOrigin)
            .param("occurredAt", payload.occurredAt.asParam())
            .query(UUID::class.java).single()

    fun timeline(chainKey: String, limit: Int = 200): List<ActivityRow> =
        db.sql("$SELECT_ROW WHERE chain_key = :chainKey ORDER BY sequence DESC LIMIT :limit")
            .param("chainKey", chainKey)
            .param("limit", limit)
            .query(::mapRow).list()

    /**
     * The whole chain in order, for verification. Read as the payload the hash
     * was computed over, so a mismatch means the stored content changed — not
     * that the application and the database disagree about how to read it.
     */
    fun chain(chainKey: String): List<StoredEvent> =
        db.sql(
            """
            SELECT chain_key, sequence, prev_hash, event_hash, actor_user_id, actor_label,
                   action, subject_type, subject_id, before_state, after_state,
                   request_origin, occurred_at
              FROM activity_event
             WHERE chain_key = :chainKey
             ORDER BY sequence
            """,
        )
            .param("chainKey", chainKey)
            .query { rs, _ ->
                StoredEvent(
                    payload = AuditEventPayload(
                        chainKey = rs.getString("chain_key"),
                        sequence = rs.getLong("sequence"),
                        actorLabel = rs.getString("actor_label"),
                        actorUserId = rs.uuidOrNull("actor_user_id"),
                        action = rs.getString("action"),
                        subjectType = rs.getString("subject_type"),
                        subjectId = rs.uuidOrNull("subject_id"),
                        beforeState = rs.getString("before_state"),
                        afterState = rs.getString("after_state"),
                        requestOrigin = rs.getString("request_origin"),
                        occurredAt = rs.instant("occurred_at"),
                    ),
                    prevHash = rs.getString("prev_hash"),
                    eventHash = rs.getString("event_hash"),
                )
            }
            .list()

    private companion object {
        const val SELECT_ROW = """
            SELECT id, chain_key, sequence, actor_label, action, subject_type, subject_id,
                   before_state, after_state, occurred_at
              FROM activity_event
        """

        fun mapRow(rs: ResultSet, @Suppress("UNUSED_PARAMETER") rowNum: Int) = ActivityRow(
            id = rs.uuid("id"),
            chainKey = rs.getString("chain_key"),
            sequence = rs.getLong("sequence"),
            actorLabel = rs.getString("actor_label"),
            action = rs.getString("action"),
            subjectType = rs.getString("subject_type"),
            subjectId = rs.uuidOrNull("subject_id"),
            beforeState = rs.getString("before_state"),
            afterState = rs.getString("after_state"),
            occurredAt = rs.instant("occurred_at"),
        )
    }
}
