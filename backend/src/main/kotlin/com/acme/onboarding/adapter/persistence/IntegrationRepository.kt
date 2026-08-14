package com.acme.onboarding.adapter.persistence

import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.stereotype.Repository
import java.sql.ResultSet
import java.time.Duration
import java.time.Instant
import java.util.UUID

data class IntegrationMessageRecord(
    val id: UUID,
    val direction: String,
    val targetSystem: String,
    val messageType: String,
    val externalRef: String?,
    val supplierId: UUID?,
    val supplierLegalName: String?,
    val payload: String,
    val status: String,
    val attempts: Int,
    val lastError: String?,
    val createdAt: Instant,
    val processedAt: Instant?,
)

data class VmsLinkRecord(
    val entityType: String,
    val entityId: UUID,
    val externalId: String,
    val externalSystem: String,
)

@Repository
class VmsLinkRepository(private val db: JdbcClient) {

    fun findLocalId(externalSystem: String, entityType: String, externalId: String): UUID? =
        db.sql(
            """
            SELECT entity_id FROM vms_link
             WHERE external_system = :system AND entity_type = :type AND external_id = :externalId
            """,
        )
            .param("system", externalSystem)
            .param("type", entityType)
            .param("externalId", externalId)
            .query(UUID::class.java).optional().orElse(null)

    fun findExternalId(externalSystem: String, entityType: String, entityId: UUID): String? =
        db.sql(
            """
            SELECT external_id FROM vms_link
             WHERE external_system = :system AND entity_type = :type AND entity_id = :entityId
            """,
        )
            .param("system", externalSystem)
            .param("type", entityType)
            .param("entityId", entityId)
            .query(String::class.java).optional().orElse(null)

    /**
     * Records the link, tolerating a repeat.
     *
     * `DO NOTHING` rather than an upsert: a link is a statement that two records
     * are the same thing, and re-pointing one silently would hide exactly the
     * mix-up an operator needs to see.
     */
    fun link(externalSystem: String, entityType: String, entityId: UUID, externalId: String) {
        db.sql(
            """
            INSERT INTO vms_link (external_system, entity_type, entity_id, external_id)
            VALUES (:system, :type, :entityId, :externalId)
            ON CONFLICT DO NOTHING
            """,
        )
            .param("system", externalSystem)
            .param("type", entityType)
            .param("entityId", entityId)
            .param("externalId", externalId)
            .update()
    }

    fun listForSupplier(supplierId: UUID): List<VmsLinkRecord> =
        db.sql(
            """
            SELECT entity_type, entity_id, external_id, external_system
              FROM vms_link WHERE entity_id = :supplierId
            """,
        )
            .param("supplierId", supplierId)
            .query { rs, _ ->
                VmsLinkRecord(
                    entityType = rs.getString("entity_type"),
                    entityId = rs.uuid("entity_id"),
                    externalId = rs.getString("external_id"),
                    externalSystem = rs.getString("external_system"),
                )
            }
            .list()
}

@Repository
class IntegrationMessageRepository(private val db: JdbcClient) {

    fun enqueue(
        direction: String,
        targetSystem: String,
        messageType: String,
        externalRef: String?,
        supplierId: UUID?,
        payloadJson: String,
        status: String = "PENDING",
    ): UUID =
        db.sql(
            """
            INSERT INTO integration_message
                (direction, target_system, message_type, external_ref, supplier_id, payload, status,
                 processed_at)
            VALUES
                (:direction, :targetSystem, :messageType, :externalRef, CAST(:supplierId AS uuid),
                 CAST(:payload AS jsonb), :status,
                 CASE WHEN :status IN ('RECEIVED', 'SENT') THEN now() ELSE NULL END)
            RETURNING id
            """,
        )
            .param("direction", direction)
            .param("targetSystem", targetSystem)
            .param("messageType", messageType)
            .param("externalRef", externalRef)
            .param("supplierId", supplierId?.toString())
            .param("payload", payloadJson)
            .param("status", status)
            .query(UUID::class.java).single()

    /**
     * Outbound work that is due: never sent, or failed and past its backoff.
     *
     * Eligibility is decided by the database's clock rather than the
     * application's, and so is the backoff below. The column is written with
     * `now()`, so comparing it against a JVM instant makes the schedule depend on
     * two clocks agreeing — which they do until the day they do not, and the
     * symptom is a queue that quietly stops draining.
     */
    fun claimDue(limit: Int): List<IntegrationMessageRecord> =
        db.sql(
            """
            $SELECT
             WHERE m.direction = 'OUTBOUND'
               AND m.status IN ('PENDING', 'FAILED')
               AND m.next_attempt_at <= now()
             ORDER BY m.created_at
             LIMIT :limit
            """,
        )
            .param("limit", limit)
            .query(::map).list()

    fun markSent(id: UUID, at: Instant) {
        db.sql(
            """
            UPDATE integration_message
               SET status = 'SENT', processed_at = :at, attempts = attempts + 1, last_error = NULL
             WHERE id = :id
            """,
        ).param("at", at.asParam()).param("id", id).update()
    }

    /**
     * Records a failure and schedules the retry.
     *
     * After [maxAttempts] the message dead-letters rather than retrying forever.
     * A silently failing integration is worse than no integration, because
     * everyone downstream believes the VMS is current — so the terminal state is
     * visible and manual rather than an infinite quiet loop.
     */
    fun markFailed(id: UUID, error: String, backoff: Duration, maxAttempts: Int) {
        db.sql(
            """
            UPDATE integration_message
               SET attempts = attempts + 1,
                   last_error = :error,
                   status = CASE WHEN attempts + 1 >= :maxAttempts THEN 'DEAD_LETTER' ELSE 'FAILED' END,
                   next_attempt_at = now() + make_interval(secs => :backoffSeconds)
             WHERE id = :id
            """,
        )
            .param("error", error.take(500))
            .param("maxAttempts", maxAttempts)
            .param("backoffSeconds", backoff.toSeconds().toDouble())
            .param("id", id)
            .update()
    }

    /** Puts a dead-lettered or failed message back in the queue, by hand. */
    fun requeue(id: UUID): Boolean =
        db.sql(
            """
            UPDATE integration_message
               SET status = 'PENDING', next_attempt_at = now(), last_error = NULL
             WHERE id = :id AND direction = 'OUTBOUND' AND status IN ('FAILED', 'DEAD_LETTER')
            """,
        ).param("id", id).update() == 1

    fun listRecent(limit: Int = 200): List<IntegrationMessageRecord> =
        db.sql("$SELECT ORDER BY m.created_at DESC LIMIT :limit")
            .param("limit", limit)
            .query(::map).list()

    fun countDeadLettered(): Int =
        db.sql("SELECT count(*) FROM integration_message WHERE status = 'DEAD_LETTER'")
            .query(Integer::class.java).single().toInt()

    private companion object {
        const val SELECT = """
            SELECT m.id, m.direction, m.target_system, m.message_type, m.external_ref, m.supplier_id,
                   m.payload::text AS payload, m.status, m.attempts, m.last_error, m.created_at,
                   m.processed_at, s.legal_name
              FROM integration_message m
              LEFT JOIN supplier s ON s.id = m.supplier_id
        """

        fun map(rs: ResultSet, @Suppress("UNUSED_PARAMETER") rowNum: Int) = IntegrationMessageRecord(
            id = rs.uuid("id"),
            direction = rs.getString("direction"),
            targetSystem = rs.getString("target_system"),
            messageType = rs.getString("message_type"),
            externalRef = rs.getString("external_ref"),
            supplierId = rs.uuidOrNull("supplier_id"),
            supplierLegalName = rs.getString("legal_name"),
            payload = rs.getString("payload"),
            status = rs.getString("status"),
            attempts = rs.getInt("attempts"),
            lastError = rs.getString("last_error"),
            createdAt = rs.instant("created_at"),
            processedAt = rs.instantOrNull("processed_at"),
        )
    }
}

@Repository
class VmsConflictRepository(private val db: JdbcClient) {

    fun record(supplierId: UUID, field: String, localValue: String?, remoteValue: String?) {
        db.sql(
            """
            INSERT INTO vms_field_conflict (supplier_id, field, local_value, remote_value)
            SELECT :supplierId, :field, :localValue, :remoteValue
             WHERE NOT EXISTS (
                 SELECT 1 FROM vms_field_conflict
                  WHERE supplier_id = :supplierId AND field = :field AND resolved_at IS NULL
                    AND remote_value IS NOT DISTINCT FROM :remoteValue
             )
            """,
        )
            .param("supplierId", supplierId)
            .param("field", field)
            .param("localValue", localValue)
            .param("remoteValue", remoteValue)
            .update()
    }

    fun openCount(supplierId: UUID): Int =
        db.sql("SELECT count(*) FROM vms_field_conflict WHERE supplier_id = :id AND resolved_at IS NULL")
            .param("id", supplierId)
            .query(Integer::class.java).single().toInt()
}
