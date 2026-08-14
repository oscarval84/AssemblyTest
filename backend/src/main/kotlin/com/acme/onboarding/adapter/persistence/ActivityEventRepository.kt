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

/**
 * One event as the auditor export writes it.
 *
 * Wider than [ActivityRow] because the audience is different: the timeline is
 * read inside one supplier's record, where the company and its programs are
 * already on screen, and a CSV is read with no context at all.
 */
data class AuditExportRow(
    val chainKey: String,
    val sequence: Long,
    val occurredAt: Instant,
    /** Null for the system chain, which belongs to no supplier. */
    val supplierLegalName: String?,
    val programCodes: List<String>,
    val actorLabel: String,
    val action: String,
    val subjectType: String,
    val subjectId: UUID?,
    val beforeState: String?,
    val afterState: String?,
    val requestOrigin: String?,
    val eventHash: String,
)

/**
 * The export filter, already resolved: calendar dates have become instants in
 * Acme's business time zone, and a program manager's scope has become the set of
 * programs they may read.
 *
 * Doing that resolution above this layer keeps one rule in one place — "today"
 * and "the 14th" mean what they mean in Acme's zone, not the server's.
 */
data class AuditExportQuery(
    val supplierId: UUID? = null,
    val programId: UUID? = null,
    val from: Instant? = null,
    val until: Instant? = null,
    /** Null means no program restriction; empty means the caller sees nothing. */
    val programScope: Set<UUID>? = null,
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

    fun count(chainKey: String): Int =
        db.sql("SELECT count(*) FROM activity_event WHERE chain_key = :chainKey")
            .param("chainKey", chainKey)
            .query(Int::class.java).single()

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

    /**
     * The auditor export, oldest first.
     *
     * Chronological rather than newest-first: this is read as a narrative of
     * what happened, not scanned for the latest thing.
     *
     * The join is on `s.id::text = e.chain_key` rather than casting the chain
     * key to `uuid`, because the system chain's key is the literal `SYSTEM` and
     * casting it would fail the whole query. It is a LEFT join for the same
     * reason: those events have no supplier and still belong in an export that
     * covers user administration.
     *
     * [limit] is passed by the caller rather than fixed here so it can ask for
     * one row more than it will accept and tell the difference between "this is
     * everything" and "this is where we stopped".
     */
    fun export(query: AuditExportQuery, limit: Int): List<AuditExportRow> {
        // A program filter is expressed against the supplier's enrollments, so
        // it necessarily excludes the system chain. That is the right answer:
        // "everything that happened in Northstar Health" does not include an
        // admin changing somebody's role.
        val conditions = mutableListOf<String>()
        val params = mutableMapOf<String, Any?>("limit" to limit)

        query.supplierId?.let {
            conditions += "e.chain_key = :supplierId"
            params["supplierId"] = it.toString()
        }
        query.from?.let {
            conditions += "e.occurred_at >= :from"
            params["from"] = it.asParam()
        }
        query.until?.let {
            conditions += "e.occurred_at < :until"
            params["until"] = it.asParam()
        }
        query.programId?.let {
            conditions += enrolledIn("pe.program_id = :programId")
            params["programId"] = it
        }
        query.programScope?.let { scope ->
            if (scope.isEmpty()) return emptyList()
            conditions += enrolledIn("pe.program_id IN (:scope)")
            params["scope"] = scope.toList()
        }

        val where = if (conditions.isEmpty()) "" else conditions.joinToString(" AND ", prefix = "WHERE ")

        return db.sql(
            """
            SELECT e.chain_key, e.sequence, e.occurred_at, e.actor_label, e.action,
                   e.subject_type, e.subject_id, e.before_state, e.after_state,
                   e.request_origin, e.event_hash,
                   s.legal_name,
                   (SELECT string_agg(p.code, ' ' ORDER BY p.code)
                      FROM program_enrollment pe
                      JOIN program p ON p.id = pe.program_id
                     WHERE pe.supplier_id = s.id) AS program_codes
              FROM activity_event e
              LEFT JOIN supplier s ON s.id::text = e.chain_key
             $where
             ORDER BY e.occurred_at, e.chain_key, e.sequence
             LIMIT :limit
            """,
        )
            .params(params)
            .query { rs, _ ->
                AuditExportRow(
                    chainKey = rs.getString("chain_key"),
                    sequence = rs.getLong("sequence"),
                    occurredAt = rs.instant("occurred_at"),
                    supplierLegalName = rs.getString("legal_name"),
                    // Program codes are identifiers — `NORTHSTAR_HEALTH` — so a
                    // space is a safe separator for the aggregate above.
                    programCodes = rs.getString("program_codes")
                        ?.split(' ')?.filter { it.isNotBlank() }.orEmpty(),
                    actorLabel = rs.getString("actor_label"),
                    action = rs.getString("action"),
                    subjectType = rs.getString("subject_type"),
                    subjectId = rs.uuidOrNull("subject_id"),
                    beforeState = rs.getString("before_state"),
                    afterState = rs.getString("after_state"),
                    requestOrigin = rs.getString("request_origin"),
                    eventHash = rs.getString("event_hash"),
                )
            }
            .list()
    }

    private companion object {
        /** Program filters are all "this event's supplier is enrolled in …". */
        fun enrolledIn(predicate: String): String =
            "EXISTS (SELECT 1 FROM program_enrollment pe " +
                "WHERE pe.supplier_id = s.id AND $predicate)"

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
