package com.acme.onboarding.adapter.persistence

import com.acme.onboarding.domain.user.Actor
import com.acme.onboarding.domain.user.Role
import com.acme.onboarding.domain.user.UserStatus
import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.stereotype.Repository
import java.sql.ResultSet
import java.time.Instant
import java.util.UUID

data class UserRecord(
    val id: UUID,
    val email: String,
    val fullName: String,
    val role: Role,
    val supplierId: UUID?,
    val status: UserStatus,
    val passwordHash: String?,
    val lastLoginAt: Instant?,
    val createdAt: Instant,
) {
    fun toActor(): Actor = Actor(id, email, fullName, role, supplierId)
}

@Repository
class UserRepository(private val db: JdbcClient) {

    fun findById(id: UUID): UserRecord? =
        db.sql("$SELECT WHERE id = :id").param("id", id).query(::map).optional().orElse(null)

    /** Email is unique case-insensitively, and login lowercases before lookup. */
    fun findByEmail(email: String): UserRecord? =
        db.sql("$SELECT WHERE lower(email) = lower(:email)")
            .param("email", email.trim())
            .query(::map).optional().orElse(null)

    fun insert(
        email: String,
        fullName: String,
        role: Role,
        supplierId: UUID?,
        status: UserStatus,
        passwordHash: String?,
    ): UUID =
        db.sql(
            """
            INSERT INTO app_user (email, full_name, role, supplier_id, status, password_hash)
            VALUES (:email, :fullName, :role, :supplierId, :status, :passwordHash)
            RETURNING id
            """,
        )
            .param("email", email.trim())
            .param("fullName", fullName.trim())
            .param("role", role.name)
            .param("supplierId", supplierId)
            .param("status", status.name)
            .param("passwordHash", passwordHash)
            .query(UUID::class.java).single()

    fun listStaff(): List<UserRecord> =
        db.sql("$SELECT WHERE role <> 'SUPPLIER_USER' ORDER BY full_name").query(::map).list()

    fun listForSupplier(supplierId: UUID): List<UserRecord> =
        db.sql("$SELECT WHERE supplier_id = :supplierId ORDER BY full_name")
            .param("supplierId", supplierId)
            .query(::map).list()

    /**
     * The count both admin safeguards are checked against. Read inside the same
     * transaction as the write it guards, or two concurrent demotions each see
     * two admins and leave zero.
     */
    fun countActiveAdmins(): Int =
        db.sql("SELECT count(*) FROM app_user WHERE role = 'ADMIN' AND status = 'ACTIVE'")
            .query(Integer::class.java).single().toInt()

    fun updateRole(id: UUID, role: Role) {
        db.sql("UPDATE app_user SET role = :role, updated_at = now() WHERE id = :id")
            .param("role", role.name).param("id", id).update()
    }

    fun updateStatus(id: UUID, status: UserStatus) {
        db.sql("UPDATE app_user SET status = :status, updated_at = now() WHERE id = :id")
            .param("status", status.name).param("id", id).update()
    }

    /** Accepting an invitation and consuming a reset token both land here. */
    fun setPassword(id: UUID, passwordHash: String) {
        db.sql(
            """
            UPDATE app_user
               SET password_hash = :hash, status = 'ACTIVE', updated_at = now()
             WHERE id = :id
            """,
        ).param("hash", passwordHash).param("id", id).update()
    }

    fun recordLogin(id: UUID, at: Instant) {
        db.sql("UPDATE app_user SET last_login_at = :at, updated_at = now() WHERE id = :id")
            .param("at", at.asParam()).param("id", id).update()
    }

    fun programScope(userId: UUID): List<UUID> =
        db.sql("SELECT program_id FROM program_manager_assignment WHERE user_id = :userId")
            .param("userId", userId)
            .query(UUID::class.java).list().filterNotNull()

    fun replaceProgramScope(userId: UUID, programIds: List<UUID>) {
        db.sql("DELETE FROM program_manager_assignment WHERE user_id = :userId")
            .param("userId", userId).update()
        programIds.forEach { programId ->
            db.sql(
                """
                INSERT INTO program_manager_assignment (user_id, program_id)
                VALUES (:userId, :programId)
                """,
            ).param("userId", userId).param("programId", programId).update()
        }
    }

    private companion object {
        const val SELECT = """
            SELECT id, email, full_name, role, supplier_id, status,
                   password_hash, last_login_at, created_at
              FROM app_user
        """

        fun map(rs: ResultSet, @Suppress("UNUSED_PARAMETER") rowNum: Int) = UserRecord(
            id = rs.uuid("id"),
            email = rs.getString("email"),
            fullName = rs.getString("full_name"),
            role = Role.valueOf(rs.getString("role")),
            supplierId = rs.uuidOrNull("supplier_id"),
            status = UserStatus.valueOf(rs.getString("status")),
            passwordHash = rs.getString("password_hash"),
            lastLoginAt = rs.instantOrNull("last_login_at"),
            createdAt = rs.instant("created_at"),
        )
    }
}
