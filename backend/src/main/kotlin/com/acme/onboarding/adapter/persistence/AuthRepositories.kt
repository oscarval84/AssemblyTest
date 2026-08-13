package com.acme.onboarding.adapter.persistence

import com.acme.onboarding.domain.user.Role
import org.springframework.jdbc.core.simple.JdbcClient
import org.springframework.stereotype.Repository
import java.sql.ResultSet
import java.time.Instant
import java.util.UUID

data class SessionRecord(
    val id: UUID,
    val userId: UUID,
    val expiresAt: Instant,
    val lastSeenAt: Instant,
)

/**
 * Server-side sessions. Only the SHA-256 of the token is stored, so a database
 * leak yields no usable credential, and revocation is a write rather than a wait
 * for expiry.
 */
@Repository
class SessionRepository(private val db: JdbcClient) {

    fun create(
        userId: UUID,
        tokenHash: String,
        expiresAt: Instant,
        ip: String?,
        userAgent: String?,
    ): UUID =
        db.sql(
            """
            INSERT INTO user_session (user_id, token_hash, expires_at, ip, user_agent)
            VALUES (:userId, :tokenHash, :expiresAt, :ip, :userAgent)
            RETURNING id
            """,
        )
            .param("userId", userId)
            .param("tokenHash", tokenHash)
            .param("expiresAt", expiresAt.asParam())
            .param("ip", ip)
            .param("userAgent", userAgent)
            .query(UUID::class.java).single()

    fun findActive(tokenHash: String, now: Instant): SessionRecord? =
        db.sql(
            """
            SELECT id, user_id, expires_at, last_seen_at
              FROM user_session
             WHERE token_hash = :tokenHash
               AND revoked_at IS NULL
               AND expires_at > :now
            """,
        )
            .param("tokenHash", tokenHash)
            .param("now", now.asParam())
            .query { rs, _ ->
                SessionRecord(
                    id = rs.uuid("id"),
                    userId = rs.uuid("user_id"),
                    expiresAt = rs.instant("expires_at"),
                    lastSeenAt = rs.instant("last_seen_at"),
                )
            }
            .optional().orElse(null)

    fun revoke(tokenHash: String) {
        db.sql("UPDATE user_session SET revoked_at = now() WHERE token_hash = :tokenHash AND revoked_at IS NULL")
            .param("tokenHash", tokenHash).update()
    }

    /**
     * Used by deactivation and by every password change. "Sign out everywhere"
     * is not a courtesy here: an admin removing someone's access expects it gone
     * on the next request, not when a token happens to lapse.
     */
    fun revokeAllForUser(userId: UUID): Int =
        db.sql("UPDATE user_session SET revoked_at = now() WHERE user_id = :userId AND revoked_at IS NULL")
            .param("userId", userId).update()

    fun touch(id: UUID, at: Instant) {
        db.sql("UPDATE user_session SET last_seen_at = :at WHERE id = :id")
            .param("at", at.asParam()).param("id", id).update()
    }
}

data class InvitationRecord(
    val id: UUID,
    val email: String,
    val role: Role,
    val supplierId: UUID?,
    val expiresAt: Instant,
    val acceptedAt: Instant?,
)

@Repository
class InvitationRepository(private val db: JdbcClient) {

    fun create(
        tokenHash: String,
        email: String,
        role: Role,
        supplierId: UUID?,
        invitedBy: UUID?,
        expiresAt: Instant,
    ): UUID =
        db.sql(
            """
            INSERT INTO invitation (token_hash, email, role, supplier_id, invited_by, expires_at)
            VALUES (:tokenHash, :email, :role, CAST(:supplierId AS uuid), CAST(:invitedBy AS uuid), :expiresAt)
            RETURNING id
            """,
        )
            .param("tokenHash", tokenHash)
            .param("email", email.trim())
            .param("role", role.name)
            .param("supplierId", supplierId?.toString())
            .param("invitedBy", invitedBy?.toString())
            .param("expiresAt", expiresAt.asParam())
            .query(UUID::class.java).single()

    fun findByTokenHash(tokenHash: String): InvitationRecord? =
        db.sql(
            """
            SELECT id, email, role, supplier_id, expires_at, accepted_at
              FROM invitation
             WHERE token_hash = :tokenHash
            """,
        )
            .param("tokenHash", tokenHash)
            .query(::map).optional().orElse(null)

    fun markAccepted(id: UUID, at: Instant) {
        db.sql("UPDATE invitation SET accepted_at = :at WHERE id = :id")
            .param("at", at.asParam()).param("id", id).update()
    }

    private companion object {
        fun map(rs: ResultSet, @Suppress("UNUSED_PARAMETER") rowNum: Int) = InvitationRecord(
            id = rs.uuid("id"),
            email = rs.getString("email"),
            role = Role.valueOf(rs.getString("role")),
            supplierId = rs.uuidOrNull("supplier_id"),
            expiresAt = rs.instant("expires_at"),
            acceptedAt = rs.instantOrNull("accepted_at"),
        )
    }
}

data class PasswordResetRecord(
    val id: UUID,
    val userId: UUID,
    val expiresAt: Instant,
    val consumedAt: Instant?,
)

@Repository
class PasswordResetRepository(private val db: JdbcClient) {

    fun create(tokenHash: String, userId: UUID, issuedBy: UUID?, expiresAt: Instant): UUID =
        db.sql(
            """
            INSERT INTO password_reset_token (token_hash, user_id, issued_by, expires_at)
            VALUES (:tokenHash, :userId, CAST(:issuedBy AS uuid), :expiresAt)
            RETURNING id
            """,
        )
            .param("tokenHash", tokenHash)
            .param("userId", userId)
            .param("issuedBy", issuedBy?.toString())
            .param("expiresAt", expiresAt.asParam())
            .query(UUID::class.java).single()

    fun findByTokenHash(tokenHash: String): PasswordResetRecord? =
        db.sql(
            """
            SELECT id, user_id, expires_at, consumed_at
              FROM password_reset_token
             WHERE token_hash = :tokenHash
            """,
        )
            .param("tokenHash", tokenHash)
            .query { rs, _ ->
                PasswordResetRecord(
                    id = rs.uuid("id"),
                    userId = rs.uuid("user_id"),
                    expiresAt = rs.instant("expires_at"),
                    consumedAt = rs.instantOrNull("consumed_at"),
                )
            }
            .optional().orElse(null)

    fun markConsumed(id: UUID, at: Instant) {
        db.sql("UPDATE password_reset_token SET consumed_at = :at WHERE id = :id")
            .param("at", at.asParam()).param("id", id).update()
    }
}
