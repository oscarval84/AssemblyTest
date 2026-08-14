package com.acme.onboarding.adapter.persistence

import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.stereotype.Repository
import java.time.LocalDate
import java.util.UUID

/** An approved document with an expiry date, and who to tell about it. */
data class ExpiringDocument(
    val submissionId: UUID,
    val supplierId: UUID,
    val supplierLegalName: String,
    val contactEmail: String?,
    val contactName: String?,
    val documentTypeCode: String,
    val documentTypeName: String,
    val expiresOn: LocalDate,
    val programNames: List<String>,
)

@Repository
class ExpiryRepository(private val db: JdbcClient) {

    /**
     * Every current, approved, expiring document due on or before [through].
     *
     * Only approved ones: a certificate still in review has not been accepted,
     * so its expiry is not yet a fact about the supplier's standing. Deactivated
     * suppliers are excluded — chasing a renewal from a company Acme no longer
     * works with is the kind of automated nonsense that gets a sender blocked.
     */
    fun listExpiringThrough(through: LocalDate): List<ExpiringDocument> =
        db.sql(
            """
            SELECT s.id, s.supplier_id, s.expires_on,
                   sup.legal_name, sup.primary_contact_email, sup.primary_contact_name,
                   t.code, t.name,
                   COALESCE(
                       (SELECT string_agg(p.name, '|' ORDER BY p.name)
                          FROM program_enrollment e
                          JOIN program p ON p.id = e.program_id
                         WHERE e.supplier_id = s.supplier_id),
                       ''
                   ) AS program_names
              FROM document_submission s
              JOIN document_type t ON t.id = s.document_type_id
              JOIN supplier sup ON sup.id = s.supplier_id
             WHERE s.is_current
               AND s.status = 'APPROVED'
               AND t.expiring
               AND s.expires_on IS NOT NULL
               AND s.expires_on <= CAST(:through AS date)
               AND sup.deactivated_at IS NULL
             ORDER BY s.expires_on, sup.legal_name
            """,
        )
            .param("through", through.toString())
            .query { rs, _ ->
                ExpiringDocument(
                    submissionId = rs.uuid("id"),
                    supplierId = rs.uuid("supplier_id"),
                    supplierLegalName = rs.getString("legal_name"),
                    contactEmail = rs.getString("primary_contact_email"),
                    contactName = rs.getString("primary_contact_name"),
                    documentTypeCode = rs.getString("code"),
                    documentTypeName = rs.getString("name"),
                    expiresOn = rs.getDate("expires_on").toLocalDate(),
                    programNames = rs.getString("program_names")
                        .split('|')
                        .filter { it.isNotBlank() },
                )
            }
            .list()

    /**
     * Claims the right to send one reminder, and reports whether this caller won.
     *
     * Insert-then-send rather than check-then-send: two sweeps running at once —
     * a retry, an overlapping schedule — would both pass a prior check and both
     * send. The primary key makes the claim atomic, so the second one silently
     * loses and sends nothing.
     */
    fun claimReminder(submissionId: UUID, thresholdDays: Int, expiresOn: LocalDate): Boolean =
        db.sql(
            """
            INSERT INTO expiry_reminder (document_submission_id, threshold_days, expires_on)
            VALUES (:submissionId, :thresholdDays, CAST(:expiresOn AS date))
            ON CONFLICT (document_submission_id, threshold_days) DO NOTHING
            """,
        )
            .param("submissionId", submissionId)
            .param("thresholdDays", thresholdDays)
            .param("expiresOn", expiresOn.toString())
            .update() == 1
}
