package com.acme.onboarding.adapter.persistence

import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.stereotype.Repository
import java.sql.ResultSet
import java.time.Instant
import java.util.UUID

data class AcceptanceCriterionRecord(
    val id: UUID,
    val programRequirementId: UUID,
    val version: Int,
    val ordinal: Int,
    val text: String,
)

data class CriteriaEvaluationRecord(
    val id: UUID,
    val documentSubmissionId: UUID,
    val criterionId: UUID,
    val criterionText: String,
    val criteriaVersion: Int,
    val verdict: String,
    val evidence: String?,
    val confidence: Double?,
    val source: String,
    val model: String?,
    val decidedByName: String?,
    val decidedAt: Instant,
)

@Repository
class CriteriaRepository(private val db: JdbcClient) {

    /** The criteria in force for one requirement. */
    fun current(programRequirementId: UUID): List<AcceptanceCriterionRecord> =
        db.sql("$SELECT WHERE program_requirement_id = :id AND retired_at IS NULL ORDER BY ordinal")
            .param("id", programRequirementId)
            .query(::map).list()

    /** The criteria in force for every requirement of these programs, at once. */
    fun currentForPrograms(programIds: Collection<UUID>): List<AcceptanceCriterionRecord> {
        if (programIds.isEmpty()) return emptyList()
        return db.sql(
            """
            SELECT c.id, c.program_requirement_id, c.version, c.ordinal, c.text
              FROM acceptance_criterion c
              JOIN program_requirement r ON r.id = c.program_requirement_id
             WHERE r.program_id IN (:programIds) AND c.retired_at IS NULL
             ORDER BY c.ordinal
            """,
        )
            .param("programIds", programIds.toList())
            .query(::map).list()
    }

    fun currentVersion(programRequirementId: UUID): Int =
        db.sql(
            """
            SELECT COALESCE(max(version), 0) FROM acceptance_criterion
             WHERE program_requirement_id = :id
            """,
        )
            .param("id", programRequirementId)
            .query(Integer::class.java).single().toInt()

    /**
     * Replaces the criteria with a new version.
     *
     * Retire-and-insert rather than update: an evaluation records the version it
     * judged against, and rewriting the text in place would silently change what
     * every past review claims to have checked. After the criteria change in
     * June, "what was this document held to in March" still has an answer.
     */
    fun replace(programRequirementId: UUID, texts: List<String>, authorId: UUID?): Int {
        val nextVersion = currentVersion(programRequirementId) + 1

        db.sql(
            """
            UPDATE acceptance_criterion SET retired_at = now()
             WHERE program_requirement_id = :id AND retired_at IS NULL
            """,
        ).param("id", programRequirementId).update()

        texts.forEachIndexed { index, text ->
            db.sql(
                """
                INSERT INTO acceptance_criterion
                    (program_requirement_id, version, ordinal, text, created_by)
                VALUES (:id, :version, :ordinal, :text, CAST(:authorId AS uuid))
                """,
            )
                .param("id", programRequirementId)
                .param("version", nextVersion)
                .param("ordinal", index + 1)
                .param("text", text.trim())
                .param("authorId", authorId?.toString())
                .update()
        }
        return nextVersion
    }

    fun recordVerdict(
        submissionId: UUID,
        criterion: AcceptanceCriterionRecord,
        verdict: String,
        evidence: String?,
        confidence: Double?,
        source: String,
        model: String?,
        decidedBy: UUID?,
    ) {
        db.sql(
            """
            INSERT INTO criteria_evaluation
                (document_submission_id, criterion_id, criterion_text, criteria_version, verdict,
                 evidence, confidence, source, model, decided_by)
            VALUES
                (:submissionId, :criterionId, :text, :version, :verdict, :evidence, :confidence,
                 :source, :model, CAST(:decidedBy AS uuid))
            ON CONFLICT (document_submission_id, criterion_id) DO UPDATE
                SET verdict = EXCLUDED.verdict,
                    evidence = EXCLUDED.evidence,
                    confidence = EXCLUDED.confidence,
                    source = EXCLUDED.source,
                    model = EXCLUDED.model,
                    decided_by = EXCLUDED.decided_by,
                    decided_at = now()
            """,
        )
            .param("submissionId", submissionId)
            .param("criterionId", criterion.id)
            .param("text", criterion.text)
            .param("version", criterion.version)
            .param("verdict", verdict)
            .param("evidence", evidence)
            .param("confidence", confidence)
            .param("source", source)
            .param("model", model)
            .param("decidedBy", decidedBy?.toString())
            .update()
    }

    fun verdictsFor(submissionId: UUID): List<CriteriaEvaluationRecord> =
        db.sql(
            """
            SELECT e.id, e.document_submission_id, e.criterion_id, e.criterion_text,
                   e.criteria_version, e.verdict, e.evidence, e.confidence, e.source, e.model,
                   e.decided_at, u.full_name
              FROM criteria_evaluation e
              LEFT JOIN app_user u ON u.id = e.decided_by
              JOIN acceptance_criterion c ON c.id = e.criterion_id
             WHERE e.document_submission_id = :submissionId
             ORDER BY c.ordinal
            """,
        )
            .param("submissionId", submissionId)
            .query { rs, _ ->
                CriteriaEvaluationRecord(
                    id = rs.uuid("id"),
                    documentSubmissionId = rs.uuid("document_submission_id"),
                    criterionId = rs.uuid("criterion_id"),
                    criterionText = rs.getString("criterion_text"),
                    criteriaVersion = rs.getInt("criteria_version"),
                    verdict = rs.getString("verdict"),
                    evidence = rs.getString("evidence"),
                    confidence = rs.getBigDecimal("confidence")?.toDouble(),
                    source = rs.getString("source"),
                    model = rs.getString("model"),
                    decidedByName = rs.getString("full_name"),
                    decidedAt = rs.instant("decided_at"),
                )
            }
            .list()

    /**
     * The requirement a submission belongs to, for the program it was uploaded
     * against. Supplier-scope documents satisfy several programs at once, so a
     * caller has to say which program's criteria it is asking about.
     */
    fun requirementFor(programId: UUID, documentTypeCode: String): UUID? =
        db.sql(
            """
            SELECT r.id FROM program_requirement r
              JOIN document_type t ON t.id = r.document_type_id
             WHERE r.program_id = :programId AND t.code = :code
            """,
        )
            .param("programId", programId)
            .param("code", documentTypeCode)
            .query(UUID::class.java).optional().orElse(null)

    private companion object {
        const val SELECT = """
            SELECT id, program_requirement_id, version, ordinal, text
              FROM acceptance_criterion
        """

        fun map(rs: ResultSet, @Suppress("UNUSED_PARAMETER") rowNum: Int) = AcceptanceCriterionRecord(
            id = rs.uuid("id"),
            programRequirementId = rs.uuid("program_requirement_id"),
            version = rs.getInt("version"),
            ordinal = rs.getInt("ordinal"),
            text = rs.getString("text"),
        )
    }
}
