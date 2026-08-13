package com.acme.onboarding.adapter.persistence

import com.acme.onboarding.domain.compliance.DocumentScope
import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.stereotype.Repository
import tools.jackson.databind.ObjectMapper
import java.sql.ResultSet
import java.util.UUID

data class ProgramRecord(
    val id: UUID,
    val code: String,
    val name: String,
    val description: String?,
    val active: Boolean,
)

data class DocumentTypeRecord(
    val id: UUID,
    val code: String,
    val name: String,
    val description: String?,
    val scope: DocumentScope,
    val expiring: Boolean,
    val classification: String,
    val requiresSignature: Boolean,
)

/**
 * What one program demands, flattened with the document type it points at.
 *
 * [constraints] stays a loose map on purpose: the shape differs per document
 * type — a coverage minimum on a certificate has no analogue on a W-9 — and v1
 * should not invent columns for constraints the client has not confirmed.
 */
data class ProgramRequirementRecord(
    val id: UUID,
    val programId: UUID,
    val documentType: DocumentTypeRecord,
    val constraints: Map<String, Any?>,
)

data class RejectionReasonRecord(val code: String, val label: String)

@Repository
class CatalogRepository(
    private val db: JdbcClient,
    private val objectMapper: ObjectMapper,
) {

    fun programs(): List<ProgramRecord> =
        db.sql("SELECT id, code, name, description, active FROM program ORDER BY name")
            .query(::mapProgram).list()

    fun programByCode(code: String): ProgramRecord? =
        db.sql("SELECT id, code, name, description, active FROM program WHERE code = :code")
            .param("code", code).query(::mapProgram).optional().orElse(null)

    fun documentTypes(): List<DocumentTypeRecord> =
        db.sql("$SELECT_TYPE ORDER BY code").query(::mapType).list()

    fun documentTypeByCode(code: String): DocumentTypeRecord? =
        db.sql("$SELECT_TYPE WHERE code = :code").param("code", code)
            .query(::mapType).optional().orElse(null)

    fun requirementsForPrograms(programIds: Collection<UUID>): List<ProgramRequirementRecord> {
        if (programIds.isEmpty()) return emptyList()
        return db.sql(
            """
            SELECT r.id, r.program_id, r.constraints,
                   t.id AS type_id, t.code, t.name, t.description, t.scope,
                   t.expiring, t.classification, t.requires_signature
              FROM program_requirement r
              JOIN document_type t ON t.id = r.document_type_id
             WHERE r.program_id IN (:programIds)
             ORDER BY t.scope DESC, t.code
            """,
        )
            .param("programIds", programIds.toList())
            .query { rs, _ ->
                ProgramRequirementRecord(
                    id = rs.uuid("id"),
                    programId = rs.uuid("program_id"),
                    documentType = DocumentTypeRecord(
                        id = rs.uuid("type_id"),
                        code = rs.getString("code"),
                        name = rs.getString("name"),
                        description = rs.getString("description"),
                        scope = DocumentScope.valueOf(rs.getString("scope")),
                        expiring = rs.getBoolean("expiring"),
                        classification = rs.getString("classification"),
                        requiresSignature = rs.getBoolean("requires_signature"),
                    ),
                    constraints = readConstraints(rs.getString("constraints")),
                )
            }
            .list()
    }

    fun addRequirement(programId: UUID, documentTypeId: UUID, constraintsJson: String) {
        db.sql(
            """
            INSERT INTO program_requirement (program_id, document_type_id, constraints)
            VALUES (:programId, :documentTypeId, CAST(:constraints AS jsonb))
            ON CONFLICT (program_id, document_type_id) DO NOTHING
            """,
        )
            .param("programId", programId)
            .param("documentTypeId", documentTypeId)
            .param("constraints", constraintsJson)
            .update()
    }

    fun insertProgram(code: String, name: String, description: String?): UUID =
        db.sql(
            """
            INSERT INTO program (code, name, description)
            VALUES (:code, :name, :description)
            RETURNING id
            """,
        )
            .param("code", code).param("name", name).param("description", description)
            .query(UUID::class.java).single()

    fun rejectionReasons(): List<RejectionReasonRecord> =
        db.sql("SELECT code, label FROM rejection_reason WHERE active ORDER BY sort_order")
            .query { rs, _ -> RejectionReasonRecord(rs.getString("code"), rs.getString("label")) }
            .list()

    @Suppress("UNCHECKED_CAST")
    private fun readConstraints(json: String?): Map<String, Any?> =
        if (json.isNullOrBlank()) emptyMap() else objectMapper.readValue(json, Map::class.java) as Map<String, Any?>

    private companion object {
        const val SELECT_TYPE = """
            SELECT id, code, name, description, scope, expiring, classification, requires_signature
              FROM document_type
        """

        fun mapProgram(rs: ResultSet, @Suppress("UNUSED_PARAMETER") rowNum: Int) = ProgramRecord(
            id = rs.uuid("id"),
            code = rs.getString("code"),
            name = rs.getString("name"),
            description = rs.getString("description"),
            active = rs.getBoolean("active"),
        )

        fun mapType(rs: ResultSet, @Suppress("UNUSED_PARAMETER") rowNum: Int) = DocumentTypeRecord(
            id = rs.uuid("id"),
            code = rs.getString("code"),
            name = rs.getString("name"),
            description = rs.getString("description"),
            scope = DocumentScope.valueOf(rs.getString("scope")),
            expiring = rs.getBoolean("expiring"),
            classification = rs.getString("classification"),
            requiresSignature = rs.getBoolean("requires_signature"),
        )
    }
}
