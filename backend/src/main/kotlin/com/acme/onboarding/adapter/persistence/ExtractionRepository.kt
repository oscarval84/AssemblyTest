package com.acme.onboarding.adapter.persistence

import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.stereotype.Repository
import java.time.Instant
import java.util.UUID

/**
 * One extraction, as it was stored.
 *
 * [extracted] and [flags] stay JSON at this layer because the shape belongs to
 * the certificate rather than to the table: a document type the client adds next
 * quarter should not need a migration to be read.
 */
data class ExtractionRecord(
    val id: UUID,
    val documentSubmissionId: UUID,
    val model: String,
    val extractedJson: String,
    val flagsJson: String,
    val confidence: Double?,
    /** When the document was transmitted to the processor, not when it was read. */
    val disclosedAt: Instant,
    val createdAt: Instant,
)

@Repository
class ExtractionRepository(private val db: JdbcClient) {

    /**
     * The latest extraction for a submission, if there is one.
     *
     * Latest rather than only: re-running against a better scan is normal, and
     * every attempt is kept because each one records a transmission to a third
     * party that the audit trail already names.
     */
    fun latestFor(submissionId: UUID): ExtractionRecord? =
        db.sql("$SELECT WHERE document_submission_id = :id ORDER BY created_at DESC LIMIT 1")
            .param("id", submissionId)
            .query { rs, _ ->
                ExtractionRecord(
                    id = rs.uuid("id"),
                    documentSubmissionId = rs.uuid("document_submission_id"),
                    model = rs.getString("model"),
                    extractedJson = rs.getString("extracted"),
                    flagsJson = rs.getString("flags"),
                    confidence = rs.getBigDecimal("confidence")?.toDouble(),
                    disclosedAt = rs.instant("disclosed_at"),
                    createdAt = rs.instant("created_at"),
                )
            }
            .optional().orElse(null)

    fun insert(
        submissionId: UUID,
        model: String,
        extractedJson: String,
        flagsJson: String,
        confidence: Double?,
        disclosedAt: Instant,
    ): UUID =
        db.sql(
            """
            INSERT INTO extraction_result
                (document_submission_id, model, extracted, confidence, flags, disclosed_at)
            VALUES
                (:submissionId, :model, CAST(:extracted AS jsonb), :confidence,
                 CAST(:flags AS jsonb), :disclosedAt)
            RETURNING id
            """,
        )
            .param("submissionId", submissionId)
            .param("model", model)
            .param("extracted", extractedJson)
            .param("confidence", confidence)
            .param("flags", flagsJson)
            .param("disclosedAt", disclosedAt.asParam())
            .query(UUID::class.java).single()

    private companion object {
        const val SELECT = """
            SELECT id, document_submission_id, model, extracted, confidence, flags,
                   disclosed_at, created_at
              FROM extraction_result
        """
    }
}
