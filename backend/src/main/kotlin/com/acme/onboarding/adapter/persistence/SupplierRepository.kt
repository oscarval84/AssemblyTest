package com.acme.onboarding.adapter.persistence

import com.acme.onboarding.domain.onboarding.OnboardingStage
import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.stereotype.Repository
import java.sql.ResultSet
import java.time.Instant
import java.util.UUID

data class SupplierRecord(
    val id: UUID,
    val legalName: String,
    val dbaName: String?,
    val entityType: String?,
    /** The full tax ID is never read back out of here; only the last four are. */
    val taxIdLast4: String?,
    val addressLine1: String?,
    val addressLine2: String?,
    val city: String?,
    val state: String?,
    val postalCode: String?,
    val country: String,
    val primaryContactName: String?,
    val primaryContactEmail: String?,
    val primaryContactPhone: String?,
    val stage: OnboardingStage,
    val deactivatedAt: Instant?,
    val createdAt: Instant,
    val updatedAt: Instant,
)

/** The editable half of a supplier record: everything the profile form owns. */
data class SupplierProfileUpdate(
    val legalName: String,
    val dbaName: String?,
    val entityType: String?,
    val addressLine1: String?,
    val addressLine2: String?,
    val city: String?,
    val state: String?,
    val postalCode: String?,
    val primaryContactName: String?,
    val primaryContactEmail: String?,
    val primaryContactPhone: String?,
)

data class EnrollmentRecord(
    val id: UUID,
    val supplierId: UUID,
    val programId: UUID,
    val programCode: String,
    val programName: String,
    val programDescription: String?,
    val status: String,
    val enrolledAt: Instant,
    val activatedAt: Instant?,
)

@Repository
class SupplierRepository(private val db: JdbcClient) {

    fun insert(legalName: String, contactName: String?, contactEmail: String?): UUID =
        db.sql(
            """
            INSERT INTO supplier (legal_name, primary_contact_name, primary_contact_email)
            VALUES (:legalName, :contactName, :contactEmail)
            RETURNING id
            """,
        )
            .param("legalName", legalName.trim())
            .param("contactName", contactName?.trim())
            .param("contactEmail", contactEmail?.trim())
            .query(UUID::class.java).single()

    fun findById(id: UUID): SupplierRecord? =
        db.sql("$SELECT WHERE id = :id").param("id", id).query(::map).optional().orElse(null)

    fun list(): List<SupplierRecord> =
        db.sql("$SELECT WHERE deactivated_at IS NULL ORDER BY legal_name").query(::map).list()

    fun updateProfile(id: UUID, update: SupplierProfileUpdate) {
        db.sql(
            """
            UPDATE supplier
               SET legal_name = :legalName,
                   dba_name = :dbaName,
                   entity_type = :entityType,
                   address_line1 = :addressLine1,
                   address_line2 = :addressLine2,
                   city = :city,
                   state = :state,
                   postal_code = :postalCode,
                   primary_contact_name = :primaryContactName,
                   primary_contact_email = :primaryContactEmail,
                   primary_contact_phone = :primaryContactPhone,
                   updated_at = now()
             WHERE id = :id
            """,
        )
            .param("id", id)
            .param("legalName", update.legalName.trim())
            .param("dbaName", update.dbaName?.trim())
            .param("entityType", update.entityType?.trim())
            .param("addressLine1", update.addressLine1?.trim())
            .param("addressLine2", update.addressLine2?.trim())
            .param("city", update.city?.trim())
            .param("state", update.state?.trim())
            .param("postalCode", update.postalCode?.trim())
            .param("primaryContactName", update.primaryContactName?.trim())
            .param("primaryContactEmail", update.primaryContactEmail?.trim())
            .param("primaryContactPhone", update.primaryContactPhone?.trim())
            .update()
    }

    /**
     * Written once, at submission. After that only [SupplierRecord.taxIdLast4]
     * is ever rendered — the ciphertext has no read path outside a deliberate
     * decryption, which is what "masked after submission" has to mean to be more
     * than a UI convention.
     */
    fun updateTaxId(id: UUID, encrypted: ByteArray, last4: String) {
        db.sql(
            """
            UPDATE supplier
               SET tax_id_encrypted = :encrypted, tax_id_last4 = :last4, updated_at = now()
             WHERE id = :id
            """,
        )
            .param("encrypted", encrypted)
            .param("last4", last4)
            .param("id", id)
            .update()
    }

    fun updateStage(id: UUID, stage: OnboardingStage) {
        db.sql("UPDATE supplier SET stage = :stage, updated_at = now() WHERE id = :id")
            .param("stage", stage.name).param("id", id).update()
    }

    private companion object {
        const val SELECT = """
            SELECT id, legal_name, dba_name, entity_type, tax_id_last4,
                   address_line1, address_line2, city, state, postal_code, country,
                   primary_contact_name, primary_contact_email, primary_contact_phone,
                   stage, deactivated_at, created_at, updated_at
              FROM supplier
        """

        fun map(rs: ResultSet, @Suppress("UNUSED_PARAMETER") rowNum: Int) = SupplierRecord(
            id = rs.uuid("id"),
            legalName = rs.getString("legal_name"),
            dbaName = rs.getString("dba_name"),
            entityType = rs.getString("entity_type"),
            taxIdLast4 = rs.getString("tax_id_last4"),
            addressLine1 = rs.getString("address_line1"),
            addressLine2 = rs.getString("address_line2"),
            city = rs.getString("city"),
            state = rs.getString("state"),
            postalCode = rs.getString("postal_code"),
            country = rs.getString("country"),
            primaryContactName = rs.getString("primary_contact_name"),
            primaryContactEmail = rs.getString("primary_contact_email"),
            primaryContactPhone = rs.getString("primary_contact_phone"),
            stage = OnboardingStage.valueOf(rs.getString("stage")),
            deactivatedAt = rs.instantOrNull("deactivated_at"),
            createdAt = rs.instant("created_at"),
            updatedAt = rs.instant("updated_at"),
        )
    }
}

@Repository
class EnrollmentRepository(private val db: JdbcClient) {

    fun insert(supplierId: UUID, programId: UUID): UUID =
        db.sql(
            """
            INSERT INTO program_enrollment (supplier_id, program_id)
            VALUES (:supplierId, :programId)
            RETURNING id
            """,
        )
            .param("supplierId", supplierId)
            .param("programId", programId)
            .query(UUID::class.java).single()

    fun listForSupplier(supplierId: UUID): List<EnrollmentRecord> =
        db.sql("$SELECT WHERE e.supplier_id = :supplierId ORDER BY p.name")
            .param("supplierId", supplierId)
            .query(::map).list()

    fun listAll(): List<EnrollmentRecord> =
        db.sql("$SELECT ORDER BY p.name").query(::map).list()

    fun activate(id: UUID) {
        db.sql(
            """
            UPDATE program_enrollment
               SET status = 'ACTIVE', activated_at = now()
             WHERE id = :id AND status <> 'ACTIVE'
            """,
        ).param("id", id).update()
    }

    private companion object {
        const val SELECT = """
            SELECT e.id, e.supplier_id, e.program_id, p.code AS program_code, p.name AS program_name,
                   p.description AS program_description, e.status, e.enrolled_at, e.activated_at
              FROM program_enrollment e
              JOIN program p ON p.id = e.program_id
        """

        fun map(rs: ResultSet, @Suppress("UNUSED_PARAMETER") rowNum: Int) = EnrollmentRecord(
            id = rs.uuid("id"),
            supplierId = rs.uuid("supplier_id"),
            programId = rs.uuid("program_id"),
            programCode = rs.getString("program_code"),
            programName = rs.getString("program_name"),
            programDescription = rs.getString("program_description"),
            status = rs.getString("status"),
            enrolledAt = rs.instant("enrolled_at"),
            activatedAt = rs.instantOrNull("activated_at"),
        )
    }
}
