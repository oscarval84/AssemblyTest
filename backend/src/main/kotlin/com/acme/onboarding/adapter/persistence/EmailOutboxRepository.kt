package com.acme.onboarding.adapter.persistence

import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.stereotype.Repository
import java.sql.ResultSet
import java.time.Instant
import java.util.UUID

data class EmailRecord(
    val id: UUID,
    val template: String,
    val recipientEmail: String,
    val recipientName: String?,
    val subject: String,
    val bodyText: String,
    val status: String,
    val attempts: Int,
    val lastError: String?,
    val createdAt: Instant,
    val sentAt: Instant?,
    val supplierId: UUID?,
)

/**
 * The transactional outbox.
 *
 * Rows are written in the same transaction as the state change that caused them,
 * so an email about a rejection cannot exist unless the rejection committed —
 * and, just as importantly, a rolled-back rejection cannot send one.
 */
@Repository
class EmailOutboxRepository(private val db: JdbcClient) {

    fun enqueue(
        template: String,
        recipientEmail: String,
        recipientName: String?,
        subject: String,
        bodyText: String,
        supplierId: UUID?,
    ): UUID =
        db.sql(
            """
            INSERT INTO email_message
                (template, recipient_email, recipient_name, subject, body_text, supplier_id)
            VALUES (:template, :recipientEmail, :recipientName, :subject, :bodyText,
                    CAST(:supplierId AS uuid))
            RETURNING id
            """,
        )
            .param("template", template)
            .param("recipientEmail", recipientEmail.trim())
            .param("recipientName", recipientName?.trim())
            .param("subject", subject)
            .param("bodyText", bodyText)
            .param("supplierId", supplierId?.toString())
            .query(UUID::class.java).single()

    fun listRecent(limit: Int = 100): List<EmailRecord> =
        db.sql("$SELECT ORDER BY created_at DESC LIMIT :limit")
            .param("limit", limit)
            .query(::map).list()

    fun listForSupplier(supplierId: UUID): List<EmailRecord> =
        db.sql("$SELECT WHERE supplier_id = :supplierId ORDER BY created_at DESC")
            .param("supplierId", supplierId)
            .query(::map).list()

    fun listPending(limit: Int = 50): List<EmailRecord> =
        db.sql("$SELECT WHERE status = 'PENDING' ORDER BY created_at LIMIT :limit")
            .param("limit", limit)
            .query(::map).list()

    fun markSent(id: UUID, at: Instant) {
        db.sql(
            """
            UPDATE email_message
               SET status = 'SENT', sent_at = :at, attempts = attempts + 1, last_error = NULL
             WHERE id = :id
            """,
        ).param("at", at.asParam()).param("id", id).update()
    }

    fun markFailed(id: UUID, error: String) {
        db.sql(
            """
            UPDATE email_message
               SET status = 'FAILED', attempts = attempts + 1, last_error = :error
             WHERE id = :id
            """,
        ).param("error", error.take(500)).param("id", id).update()
    }

    private companion object {
        const val SELECT = """
            SELECT id, template, recipient_email, recipient_name, subject, body_text,
                   status, attempts, last_error, created_at, sent_at, supplier_id
              FROM email_message
        """

        fun map(rs: ResultSet, @Suppress("UNUSED_PARAMETER") rowNum: Int) = EmailRecord(
            id = rs.uuid("id"),
            template = rs.getString("template"),
            recipientEmail = rs.getString("recipient_email"),
            recipientName = rs.getString("recipient_name"),
            subject = rs.getString("subject"),
            bodyText = rs.getString("body_text"),
            status = rs.getString("status"),
            attempts = rs.getInt("attempts"),
            lastError = rs.getString("last_error"),
            createdAt = rs.instant("created_at"),
            sentAt = rs.instantOrNull("sent_at"),
            supplierId = rs.uuidOrNull("supplier_id"),
        )
    }
}
