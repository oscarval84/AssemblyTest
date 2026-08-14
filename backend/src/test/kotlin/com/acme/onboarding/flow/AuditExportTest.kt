package com.acme.onboarding.flow

import com.acme.onboarding.adapter.persistence.CatalogRepository
import com.acme.onboarding.adapter.persistence.UserRepository
import com.acme.onboarding.application.audit.AuditAction
import com.acme.onboarding.application.audit.AuditExportRequest
import com.acme.onboarding.application.audit.AuditExportService
import com.acme.onboarding.application.auth.InvitationService
import com.acme.onboarding.application.supplier.NewSupplierRequest
import com.acme.onboarding.application.supplier.SupplierService
import com.acme.onboarding.application.support.InvalidRequestException
import com.acme.onboarding.application.support.hash
import com.acme.onboarding.config.AcmeProperties
import com.acme.onboarding.domain.user.AccessDeniedException
import com.acme.onboarding.domain.user.Actor
import com.acme.onboarding.domain.user.Role
import com.acme.onboarding.domain.user.UserStatus
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.testcontainers.service.connection.ServiceConnection
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.security.crypto.password.PasswordEncoder
import org.testcontainers.junit.jupiter.Container
import org.testcontainers.junit.jupiter.Testcontainers
import org.testcontainers.postgresql.PostgreSQLContainer
import java.time.LocalDate
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The export Dana asked for by name: *"a history I can hand to an auditor."*
 *
 * What these tests are actually about is what makes a history handable. It has
 * to be complete for what was asked and empty of what was not — a filter that
 * silently drops events is worse than no filter — it has to read as a narrative
 * rather than a dump, it has to keep a program manager inside their programs,
 * and taking a copy of it has to be an event in its own right.
 */
@Testcontainers
@SpringBootTest(properties = ["acme.demo.seed-on-startup=false"])
class AuditExportTest {

    companion object {
        @Container
        @ServiceConnection
        @JvmStatic
        val postgres: PostgreSQLContainer = PostgreSQLContainer("postgres:17-alpine")

        private const val PASSWORD = "Onboarding2026!"
    }

    @Autowired private lateinit var audit: AuditExportService
    @Autowired private lateinit var suppliers: SupplierService
    @Autowired private lateinit var invitations: InvitationService
    @Autowired private lateinit var users: UserRepository
    @Autowired private lateinit var catalog: CatalogRepository
    @Autowired private lateinit var passwordEncoder: PasswordEncoder
    @Autowired private lateinit var properties: AcmeProperties
    @Autowired private lateinit var jdbc: JdbcTemplate

    @Test
    fun `one supplier's export carries that supplier, its programs and nobody else`() {
        val ops = staffActor(Role.OPS)
        val world = twoSuppliersInDifferentPrograms(ops)

        val export = audit.export(ops, AuditExportRequest(supplierId = world.first.supplierId))

        assertEquals(HEADER, headerOf(export.csv))
        assertEquals(export.rowCount, dataRows(export.csv).size)
        assertTrue(export.csv.contains(world.first.legalName), export.csv)
        assertTrue(export.csv.contains(world.first.programCode), export.csv)
        assertFalse(export.csv.contains(world.second.legalName), export.csv)

        // Oldest first: an auditor reads this as what happened, in order, and
        // the first thing that ever happens to a supplier is being invited.
        assertTrue(dataRows(export.csv).first().contains(AuditAction.SUPPLIER_INVITED), export.csv)

        // The file names itself after what is in it, because these arrive as
        // attachments and `export(3).csv` is how the wrong period gets sent.
        assertTrue(export.filename.startsWith("acme-audit-"), export.filename)
        assertTrue(export.filename.endsWith(".csv"), export.filename)
    }

    @Test
    fun `a program filter reports that program and leaves the rest out`() {
        val ops = staffActor(Role.OPS)
        val world = twoSuppliersInDifferentPrograms(ops)

        val export = audit.export(ops, AuditExportRequest(programId = world.first.programId))

        assertTrue(export.csv.contains(world.first.legalName), export.csv)
        assertFalse(export.csv.contains(world.second.legalName), export.csv)
    }

    @Test
    fun `a range that ended yesterday returns a header and nothing else`() {
        val ops = staffActor(Role.OPS)
        val world = twoSuppliersInDifferentPrograms(ops)
        val today = LocalDate.now(properties.businessTimeZone)

        val before = audit.export(
            ops,
            AuditExportRequest(supplierId = world.first.supplierId, to = today.minusDays(1)),
        )
        assertEquals(0, before.rowCount)
        assertEquals(0, dataRows(before.csv).size)

        // Both ends inclusive: "from today to today" has to contain today, or
        // the person filling in the form gets an empty file and concludes the
        // wrong thing about their own audit trail.
        val including = audit.export(
            ops,
            AuditExportRequest(supplierId = world.first.supplierId, from = today, to = today),
        )
        assertTrue(including.rowCount > 0)
    }

    @Test
    fun `a backwards range is refused rather than answered with nothing`() {
        val ops = staffActor(Role.OPS)
        val today = LocalDate.now(properties.businessTimeZone)

        assertFailsWith<InvalidRequestException> {
            audit.export(ops, AuditExportRequest(from = today, to = today.minusDays(7)))
        }
    }

    @Test
    fun `a program manager exports their programs and is refused the others`() {
        val ops = staffActor(Role.OPS)
        val world = twoSuppliersInDifferentPrograms(ops)
        val manager = programManager(world.first.programId)

        val export = audit.export(manager, AuditExportRequest())
        assertTrue(export.csv.contains(world.first.legalName), export.csv)
        assertFalse(export.csv.contains(world.second.legalName), export.csv)

        assertFailsWith<AccessDeniedException> {
            audit.export(manager, AuditExportRequest(programId = world.second.programId))
        }

        // Refused rather than handed an empty file: "no events" and "not yours
        // to read" are different answers and must not look alike.
        assertFailsWith<AccessDeniedException> {
            audit.export(manager, AuditExportRequest(supplierId = world.second.supplierId))
        }
    }

    @Test
    fun `a supplier user cannot export the audit log at all`() {
        val ops = staffActor(Role.OPS)
        val world = twoSuppliersInDifferentPrograms(ops)

        assertFailsWith<AccessDeniedException> {
            audit.export(world.first.supplierUser, AuditExportRequest())
        }
        assertFailsWith<AccessDeniedException> {
            audit.export(
                world.first.supplierUser,
                AuditExportRequest(supplierId = world.first.supplierId),
            )
        }
    }

    @Test
    fun `taking a copy of the log is itself recorded in the log`() {
        val ops = staffActor(Role.OPS)
        val world = twoSuppliersInDifferentPrograms(ops)

        val export = audit.export(ops, AuditExportRequest(supplierId = world.first.supplierId))

        val recorded = suppliers.activity(ops, world.first.supplierId)
            .first { it.action == AuditAction.AUDIT_EXPORTED }
        assertEquals(ops.label, recorded.actorLabel)

        val after = recorded.afterState.orEmpty()
        assertTrue(after.contains("\"rowCount\":${export.rowCount}"), after)

        // It lands in the exported supplier's own chain, so "who took a copy of
        // this company's history" is answerable from the record it concerns.
        assertEquals(world.first.supplierId.toString(), recorded.chainKey)
    }

    @Test
    fun `a company name that looks like a spreadsheet formula is defused`() {
        val ops = staffActor(Role.OPS)
        // Not a hypothetical: the legal name is typed by whoever fills in the
        // intake form, and this file is opened in Excel by definition.
        val hostileName = "=cmd|'/c calc'!A1 Staffing"
        val supplierId = supplier(ops, hostileName, program()).supplierId

        val export = audit.export(ops, AuditExportRequest(supplierId = supplierId))

        assertTrue(export.csv.contains("'$hostileName"), export.csv)
        assertFalse(export.csv.contains(",$hostileName"), export.csv)
    }

    @Test
    fun `verification walks a supplier's chain and counts it`() {
        val ops = staffActor(Role.OPS)
        val world = twoSuppliersInDifferentPrograms(ops)

        val verification = audit.verifyChain(ops, world.first.supplierId.toString())

        assertTrue(verification.intact)
        assertEquals(suppliers.activity(ops, world.first.supplierId).size, verification.eventCount)
        assertNull(verification.brokenAtSequence)

        assertFailsWith<AccessDeniedException> {
            audit.verifyChain(world.first.supplierUser, world.first.supplierId.toString())
        }
    }

    // -- helpers --------------------------------------------------------------

    private data class SupplierWorld(
        val supplierId: UUID,
        val legalName: String,
        val programId: UUID,
        val programCode: String,
        val supplierUser: Actor,
    )

    private fun unique() = UUID.randomUUID().toString().take(8)

    private fun staffActor(role: Role): Actor {
        val email = "${role.name.lowercase()}-${unique()}@acme-msp.example"
        val id = users.insert(email, "Test ${role.name}", role, null, UserStatus.ACTIVE, passwordEncoder.hash(PASSWORD))
        return Actor(id, email, "Test ${role.name}", role, null)
    }

    private fun programManager(vararg programIds: UUID): Actor {
        val actor = staffActor(Role.PROGRAM_MANAGER)
        users.replaceProgramScope(actor.userId, programIds.toList())
        return actor
    }

    /** A program of its own per test, so one test's events cannot answer another's filter. */
    private fun program(): Pair<UUID, String> {
        val code = "AUDIT_${unique()}"
        val programId = catalog.insertProgram(code, "Audit Program", "Created by a test.")
        val types = catalog.documentTypes().associateBy { it.code }
        catalog.addRequirement(programId, types.getValue("W9").id, "{}")
        return programId to code
    }

    private fun supplier(ops: Actor, legalName: String, program: Pair<UUID, String>): SupplierWorld {
        val contactEmail = "owner-${unique()}@example.test"
        val created = suppliers.createAndInvite(
            ops,
            NewSupplierRequest(legalName, "Robin Fell", contactEmail, listOf(program.first)),
        )
        val body = jdbc.queryForObject(
            "SELECT body_text FROM email_message WHERE lower(recipient_email) = lower(?) ORDER BY created_at DESC LIMIT 1",
            String::class.java,
            contactEmail,
        )!!
        val token = body.substringAfter("/invitation/").substringBefore('\n').trim()

        return SupplierWorld(
            supplierId = created.profile.id,
            legalName = legalName,
            programId = program.first,
            programCode = program.second,
            supplierUser = invitations.accept(token, PASSWORD).actor,
        )
    }

    /** Two companies that share nothing, which is what makes a filter provable. */
    private fun twoSuppliersInDifferentPrograms(ops: Actor): Pair<SupplierWorld, SupplierWorld> =
        supplier(ops, "Northwind Clinical ${unique()}", program()) to
            supplier(ops, "Beacon Facilities ${unique()}", program())

    /** The byte-order mark is for Excel, not for assertions. */
    private fun headerOf(csv: String): List<String> =
        csv.lineSequence().first().removePrefix("\uFEFF").split(',')

    private fun dataRows(csv: String): List<String> =
        csv.trimEnd().lines().drop(1).filter { it.isNotBlank() }
}

private val HEADER = listOf(
    "occurred_at_utc",
    "supplier",
    "programs",
    "action",
    "actor",
    "subject_type",
    "subject_id",
    "before",
    "after",
    "request_origin",
    "chain_key",
    "sequence",
    "event_hash",
)
