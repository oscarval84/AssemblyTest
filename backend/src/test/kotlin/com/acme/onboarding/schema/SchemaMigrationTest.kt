package com.acme.onboarding.schema

import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.testcontainers.service.connection.ServiceConnection
import org.springframework.dao.DataAccessException
import org.springframework.jdbc.core.JdbcTemplate
import org.testcontainers.junit.jupiter.Container
import org.testcontainers.junit.jupiter.Testcontainers
import org.testcontainers.postgresql.PostgreSQLContainer
import java.util.UUID
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * Verifies the schema against a real PostgreSQL, not a developer's local
 * database, so the result is the same on any machine and in CI.
 *
 * The point of these tests is not that the SQL parses — Flyway would have said
 * so. It is that the guarantees the architecture claims are actually enforced by
 * the database. An append-only log that is only append-only because the
 * application happens not to issue UPDATEs is a comment, not a control.
 */
@Testcontainers
// Demo seeding off: this asserts what the schema enforces, and a seeded world
// would have it counting rows the migrations did not create.
@SpringBootTest(properties = ["acme.demo.seed-on-startup=false"])
class SchemaMigrationTest {

    companion object {
        @Container
        @ServiceConnection
        @JvmStatic
        // Testcontainers 2.x dropped the self-type parameter this class carried
        // in 1.x, so it is used raw here.
        val postgres: PostgreSQLContainer = PostgreSQLContainer("postgres:17-alpine")
    }

    @Autowired
    private lateinit var jdbc: JdbcTemplate

    @Test
    fun `every migration applied`() {
        val applied = jdbc.queryForList(
            "SELECT version, success FROM flyway_schema_history WHERE version IS NOT NULL ORDER BY installed_rank",
        )

        assertEquals(listOf("1", "2", "3", "4", "5", "6", "7", "8"), applied.map { it["version"] })
        assertTrue(applied.all { it["success"] == true })
    }

    @Test
    fun `reference data is loaded and every document type is classified`() {
        // classification is NOT NULL in the schema, so an unclassified document
        // type cannot exist. This asserts the seeded set is complete and that
        // the scope split — the basis of requirement resolution — is real.
        val types = jdbc.queryForList("SELECT code, scope, classification FROM document_type")
        assertEquals(6, types.size)
        assertTrue(types.all { it["classification"] != null })

        assertEquals(
            setOf("W9", "CERTIFICATE_OF_INSURANCE", "BANKING_FORM", "SUPPLIER_AGREEMENT"),
            types.filter { it["scope"] == "SUPPLIER" }.map { it["code"] }.toSet(),
        )
        assertEquals(
            setOf("PROGRAM_ADDENDUM", "BACKGROUND_CHECK_ATTESTATION"),
            types.filter { it["scope"] == "PROGRAM" }.map { it["code"] }.toSet(),
        )

        assertEquals(
            8,
            jdbc.queryForObject("SELECT count(*) FROM rejection_reason", Int::class.java),
        )
    }

    @Test
    fun `the audit log rejects updates and deletes`() {
        insertAuditEvent()

        // The governance claim under test: history cannot be rewritten, and the
        // enforcement binds the table owner too — which is what makes it hold in
        // local development, where the application and the migration run as the
        // same role.
        val onUpdate = assertFailsWith<DataAccessException> {
            jdbc.update("UPDATE activity_event SET action = 'TAMPERED'")
        }
        assertTrue(onUpdate.message!!.contains("append-only"), onUpdate.message!!)

        val onDelete = assertFailsWith<DataAccessException> {
            jdbc.update("DELETE FROM activity_event")
        }
        assertTrue(onDelete.message!!.contains("append-only"), onDelete.message!!)

        assertEquals(
            1,
            jdbc.queryForObject("SELECT count(*) FROM activity_event", Int::class.java),
        )
    }

    @Test
    fun `a chain cannot contain two events at the same sequence`() {
        insertAuditEvent(chainKey = "CHAIN_A", sequence = 1)

        // Without this, a concurrent writer could fork a supplier's history into
        // two branches that each verify in isolation.
        assertFailsWith<DataAccessException> {
            insertAuditEvent(chainKey = "CHAIN_A", sequence = 1)
        }
    }

    @Test
    fun `a supplier binding is required for supplier users and forbidden for staff`() {
        val supplierId = insertSupplier()

        // Staff must not be bound to a supplier: that binding is what scopes an
        // external user to their own company.
        assertFailsWith<DataAccessException> {
            insertUser(role = "OPS", supplierId = supplierId)
        }

        // A supplier user without a binding would resolve to every supplier.
        assertFailsWith<DataAccessException> {
            insertUser(role = "SUPPLIER_USER", supplierId = null)
        }

        insertUser(role = "OPS", supplierId = null)
        insertUser(role = "SUPPLIER_USER", supplierId = supplierId)
    }

    @Test
    fun `the same person cannot upload and approve a document`() {
        // Segregation of duties, enforced in the database rather than trusted to
        // the service layer. Ops uploading on behalf of a two-person agency is a
        // real workflow; that path must still force a second approver.
        val supplierId = insertSupplier()
        val opsUser = insertUser(role = "OPS", supplierId = null)
        val typeId = jdbc.queryForObject(
            "SELECT id FROM document_type WHERE code = 'W9'", UUID::class.java,
        )!!

        assertFailsWith<DataAccessException> {
            jdbc.update(
                """
                INSERT INTO document_submission
                    (supplier_id, document_type_id, storage_key, original_filename,
                     content_type, size_bytes, checksum_sha256, status,
                     uploaded_by, reviewed_by, reviewed_at)
                VALUES (?, ?, 'k', 'w9.pdf', 'application/pdf', 1, 'abc', 'APPROVED', ?, ?, now())
                """.trimIndent(),
                supplierId, typeId, opsUser, opsUser,
            )
        }
    }

    @Test
    fun `a rejected document must carry a reason`() {
        val supplierId = insertSupplier()
        val typeId = jdbc.queryForObject(
            "SELECT id FROM document_type WHERE code = 'BANKING_FORM'", UUID::class.java,
        )!!

        // "Rejected, no reason given" is the experience suppliers described as
        // faxing paperwork into a void. The schema refuses to record it.
        assertFailsWith<DataAccessException> {
            jdbc.update(
                """
                INSERT INTO document_submission
                    (supplier_id, document_type_id, storage_key, original_filename,
                     content_type, size_bytes, checksum_sha256, status)
                VALUES (?, ?, 'k', 'bank.pdf', 'application/pdf', 1, 'abc', 'REJECTED')
                """.trimIndent(),
                supplierId, typeId,
            )
        }
    }

    // -- helpers ------------------------------------------------------------

    private fun insertSupplier(): UUID =
        jdbc.queryForObject(
            "INSERT INTO supplier (legal_name) VALUES ('Test Supplier') RETURNING id",
            UUID::class.java,
        )!!

    private fun insertUser(role: String, supplierId: UUID?): UUID =
        jdbc.queryForObject(
            """
            INSERT INTO app_user (email, full_name, role, supplier_id)
            VALUES (?, 'Test User', ?, ?) RETURNING id
            """.trimIndent(),
            UUID::class.java,
            "user-${UUID.randomUUID()}@example.test", role, supplierId,
        )!!

    private fun insertAuditEvent(chainKey: String = "SYSTEM", sequence: Long = 1) {
        jdbc.update(
            """
            INSERT INTO activity_event
                (chain_key, sequence, prev_hash, event_hash, actor_label,
                 action, subject_type)
            VALUES (?, ?, 'genesis', 'hash', 'system', 'TEST', 'TEST')
            """.trimIndent(),
            chainKey, sequence,
        )
    }
}
