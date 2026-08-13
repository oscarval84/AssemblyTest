package com.acme.onboarding.adapter.persistence

import com.acme.onboarding.domain.compliance.DocumentScope
import com.acme.onboarding.domain.compliance.SubmissionStatus
import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.stereotype.Repository
import java.sql.ResultSet
import java.time.Instant
import java.time.LocalDate
import java.util.UUID

data class SubmissionRecord(
    val id: UUID,
    val supplierId: UUID,
    val documentTypeId: UUID,
    val documentTypeCode: String,
    val documentTypeName: String,
    val scope: DocumentScope,
    val expiring: Boolean,
    val classification: String,
    val enrollmentId: UUID?,
    val version: Int,
    val isCurrent: Boolean,
    val storageKey: String,
    val originalFilename: String,
    val contentType: String,
    val sizeBytes: Long,
    val checksumSha256: String,
    val status: SubmissionStatus,
    val issuedOn: LocalDate?,
    val expiresOn: LocalDate?,
    val rejectionReasonCode: String?,
    val rejectionReasonLabel: String?,
    val rejectionNote: String?,
    val uploadedBy: UUID?,
    val uploadedByName: String?,
    val uploadedAt: Instant,
    val reviewedBy: UUID?,
    val reviewedByName: String?,
    val reviewedAt: Instant?,
)

/** Everything an upload needs to record, once the bytes are safely stored. */
data class NewSubmission(
    val supplierId: UUID,
    val documentTypeId: UUID,
    val enrollmentId: UUID?,
    val version: Int,
    val storageKey: String,
    val originalFilename: String,
    val contentType: String,
    val sizeBytes: Long,
    val checksumSha256: String,
    val issuedOn: LocalDate?,
    val expiresOn: LocalDate?,
    val uploadedBy: UUID?,
    val status: SubmissionStatus = SubmissionStatus.PENDING,
)

@Repository
class SubmissionRepository(private val db: JdbcClient) {

    fun findById(id: UUID): SubmissionRecord? =
        db.sql("$SELECT WHERE s.id = :id").param("id", id).query(::map).optional().orElse(null)

    /**
     * The supplier's current set: one row per requirement slot, superseded
     * versions excluded. Supplier-scope rows carry a null enrollment and are
     * shared across every program, which is what makes a second onboarding
     * mostly pre-filled.
     */
    fun listCurrentForSupplier(supplierId: UUID): List<SubmissionRecord> =
        db.sql("$SELECT WHERE s.supplier_id = :supplierId AND s.is_current ORDER BY t.code")
            .param("supplierId", supplierId)
            .query(::map).list()

    fun listVersions(supplierId: UUID, documentTypeId: UUID, enrollmentId: UUID?): List<SubmissionRecord> =
        db.sql("$SELECT WHERE s.supplier_id = :supplierId AND s.document_type_id = :typeId AND $SAME_SLOT ORDER BY s.version DESC")
            .param("supplierId", supplierId)
            .param("typeId", documentTypeId)
            .param("enrollmentId", enrollmentId?.toString())
            .query(::map).list()

    fun listPendingReview(): List<SubmissionRecord> =
        db.sql("$SELECT WHERE s.is_current AND s.status = 'PENDING' ORDER BY s.uploaded_at")
            .query(::map).list()

    /**
     * Retires the current version of one requirement slot and reports the
     * highest version number seen, so the replacement can be numbered without a
     * second round trip. Nothing is overwritten: an auditor asking what was on
     * file in March needs the March file, not its replacement.
     */
    fun supersedeCurrent(supplierId: UUID, documentTypeId: UUID, enrollmentId: UUID?): Int {
        db.sql(
            """
            UPDATE document_submission s
               SET is_current = FALSE
             WHERE s.supplier_id = :supplierId
               AND s.document_type_id = :typeId
               AND $SAME_SLOT
               AND s.is_current
            """,
        )
            .param("supplierId", supplierId)
            .param("typeId", documentTypeId)
            .param("enrollmentId", enrollmentId?.toString())
            .update()

        return db.sql(
            """
            SELECT COALESCE(max(s.version), 0)
              FROM document_submission s
             WHERE s.supplier_id = :supplierId
               AND s.document_type_id = :typeId
               AND $SAME_SLOT
            """,
        )
            .param("supplierId", supplierId)
            .param("typeId", documentTypeId)
            .param("enrollmentId", enrollmentId?.toString())
            .query(Integer::class.java).single().toInt()
    }

    fun insert(submission: NewSubmission): UUID =
        db.sql(
            """
            INSERT INTO document_submission
                (supplier_id, document_type_id, enrollment_id, version, is_current,
                 storage_key, original_filename, content_type, size_bytes, checksum_sha256,
                 status, issued_on, expires_on, uploaded_by)
            VALUES
                (:supplierId, :typeId, CAST(:enrollmentId AS uuid), :version, TRUE,
                 :storageKey, :originalFilename, :contentType, :sizeBytes, :checksum,
                 :status, CAST(:issuedOn AS date), CAST(:expiresOn AS date), CAST(:uploadedBy AS uuid))
            RETURNING id
            """,
        )
            .param("supplierId", submission.supplierId)
            .param("typeId", submission.documentTypeId)
            .param("enrollmentId", submission.enrollmentId?.toString())
            .param("version", submission.version)
            .param("storageKey", submission.storageKey)
            .param("originalFilename", submission.originalFilename)
            .param("contentType", submission.contentType)
            .param("sizeBytes", submission.sizeBytes)
            .param("checksum", submission.checksumSha256)
            .param("status", submission.status.name)
            .param("issuedOn", submission.issuedOn?.toString())
            .param("expiresOn", submission.expiresOn?.toString())
            .param("uploadedBy", submission.uploadedBy?.toString())
            .query(UUID::class.java).single()

    fun recordReview(
        id: UUID,
        status: SubmissionStatus,
        reviewerId: UUID,
        rejectionReasonCode: String?,
        rejectionNote: String?,
        at: Instant,
    ) {
        db.sql(
            """
            UPDATE document_submission
               SET status = :status,
                   reviewed_by = :reviewerId,
                   reviewed_at = :at,
                   rejection_reason_code = :reasonCode,
                   rejection_note = :note
             WHERE id = :id
            """,
        )
            .param("status", status.name)
            .param("reviewerId", reviewerId)
            .param("at", at.asParam())
            .param("reasonCode", rejectionReasonCode)
            .param("note", rejectionNote)
            .param("id", id)
            .update()
    }

    private companion object {
        /**
         * Matches one requirement slot. `COALESCE` mirrors the partial unique
         * index in V1, which treats supplier-scope rows — where the enrollment
         * is NULL — as a single slot rather than as unrelated rows.
         */
        const val SAME_SLOT = """
            COALESCE(s.enrollment_id, '00000000-0000-0000-0000-000000000000'::uuid)
                = COALESCE(CAST(:enrollmentId AS uuid), '00000000-0000-0000-0000-000000000000'::uuid)
        """

        const val SELECT = """
            SELECT s.id, s.supplier_id, s.document_type_id, s.enrollment_id, s.version, s.is_current,
                   s.storage_key, s.original_filename, s.content_type, s.size_bytes, s.checksum_sha256,
                   s.status, s.issued_on, s.expires_on, s.rejection_reason_code, s.rejection_note,
                   s.uploaded_by, s.uploaded_at, s.reviewed_by, s.reviewed_at,
                   t.code AS type_code, t.name AS type_name, t.scope, t.expiring, t.classification,
                   r.label AS rejection_label,
                   up.full_name AS uploaded_by_name,
                   rv.full_name AS reviewed_by_name
              FROM document_submission s
              JOIN document_type t ON t.id = s.document_type_id
              LEFT JOIN rejection_reason r ON r.code = s.rejection_reason_code
              LEFT JOIN app_user up ON up.id = s.uploaded_by
              LEFT JOIN app_user rv ON rv.id = s.reviewed_by
        """

        fun map(rs: ResultSet, @Suppress("UNUSED_PARAMETER") rowNum: Int) = SubmissionRecord(
            id = rs.uuid("id"),
            supplierId = rs.uuid("supplier_id"),
            documentTypeId = rs.uuid("document_type_id"),
            documentTypeCode = rs.getString("type_code"),
            documentTypeName = rs.getString("type_name"),
            scope = DocumentScope.valueOf(rs.getString("scope")),
            expiring = rs.getBoolean("expiring"),
            classification = rs.getString("classification"),
            enrollmentId = rs.uuidOrNull("enrollment_id"),
            version = rs.getInt("version"),
            isCurrent = rs.getBoolean("is_current"),
            storageKey = rs.getString("storage_key"),
            originalFilename = rs.getString("original_filename"),
            contentType = rs.getString("content_type"),
            sizeBytes = rs.getLong("size_bytes"),
            checksumSha256 = rs.getString("checksum_sha256"),
            status = SubmissionStatus.valueOf(rs.getString("status")),
            issuedOn = rs.localDateOrNull("issued_on"),
            expiresOn = rs.localDateOrNull("expires_on"),
            rejectionReasonCode = rs.getString("rejection_reason_code"),
            rejectionReasonLabel = rs.getString("rejection_label"),
            rejectionNote = rs.getString("rejection_note"),
            uploadedBy = rs.uuidOrNull("uploaded_by"),
            uploadedByName = rs.getString("uploaded_by_name"),
            uploadedAt = rs.instant("uploaded_at"),
            reviewedBy = rs.uuidOrNull("reviewed_by"),
            reviewedByName = rs.getString("reviewed_by_name"),
            reviewedAt = rs.instantOrNull("reviewed_at"),
        )
    }
}

data class SignatureRecord(
    val id: UUID,
    val documentSubmissionId: UUID,
    val signerUserId: UUID,
    val signerName: String,
    val typedName: String,
    val signedAt: Instant,
    val templateVersion: String,
)

@Repository
class SignatureRepository(private val db: JdbcClient) {

    fun insert(
        documentSubmissionId: UUID,
        signerUserId: UUID,
        typedName: String,
        signerIp: String?,
        signerUserAgent: String?,
        templateVersion: String,
        templateSha256: String,
        executedStorageKey: String,
        executedSha256: String,
    ): UUID =
        db.sql(
            """
            INSERT INTO signature_record
                (document_submission_id, signer_user_id, typed_name, signer_ip, signer_user_agent,
                 template_version, template_sha256, executed_storage_key, executed_sha256)
            VALUES
                (:submissionId, :signerUserId, :typedName, :ip, :userAgent,
                 :templateVersion, :templateSha256, :executedKey, :executedSha256)
            RETURNING id
            """,
        )
            .param("submissionId", documentSubmissionId)
            .param("signerUserId", signerUserId)
            .param("typedName", typedName.trim())
            .param("ip", signerIp)
            .param("userAgent", signerUserAgent)
            .param("templateVersion", templateVersion)
            .param("templateSha256", templateSha256)
            .param("executedKey", executedStorageKey)
            .param("executedSha256", executedSha256)
            .query(UUID::class.java).single()

    fun findBySubmission(submissionId: UUID): SignatureRecord? =
        db.sql(
            """
            SELECT s.id, s.document_submission_id, s.signer_user_id, s.typed_name,
                   s.signed_at, s.template_version, u.full_name AS signer_name
              FROM signature_record s
              JOIN app_user u ON u.id = s.signer_user_id
             WHERE s.document_submission_id = :submissionId
             ORDER BY s.signed_at DESC
             LIMIT 1
            """,
        )
            .param("submissionId", submissionId)
            .query { rs, _ ->
                SignatureRecord(
                    id = rs.uuid("id"),
                    documentSubmissionId = rs.uuid("document_submission_id"),
                    signerUserId = rs.uuid("signer_user_id"),
                    signerName = rs.getString("signer_name"),
                    typedName = rs.getString("typed_name"),
                    signedAt = rs.instant("signed_at"),
                    templateVersion = rs.getString("template_version"),
                )
            }
            .optional().orElse(null)
}
