package com.acme.onboarding.application.audit

import com.acme.onboarding.adapter.persistence.ActivityEventRepository
import com.acme.onboarding.adapter.persistence.AuditExportQuery
import com.acme.onboarding.adapter.persistence.AuditExportRow
import com.acme.onboarding.adapter.persistence.CatalogRepository
import com.acme.onboarding.adapter.persistence.UserRepository
import com.acme.onboarding.application.document.SimpleDocumentRenderer
import com.acme.onboarding.application.supplier.SupplierService
import com.acme.onboarding.application.support.Csv
import com.acme.onboarding.application.support.InvalidRequestException
import com.acme.onboarding.application.support.NotFoundException
import com.acme.onboarding.config.AcmeProperties
import com.acme.onboarding.domain.audit.ChainVerification
import com.acme.onboarding.domain.user.AccessDeniedException
import com.acme.onboarding.domain.user.Actor
import com.acme.onboarding.domain.user.Role
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.Instant
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.util.UUID

/** The filter, exactly as the screen offers it: a supplier, a program, a range. */
data class AuditExportRequest(
    val supplierId: UUID? = null,
    val programId: UUID? = null,
    /** Calendar dates in Acme's business time zone, both ends inclusive. */
    val from: LocalDate? = null,
    val to: LocalDate? = null,
)

/**
 * The two shapes an auditor asks for.
 *
 * CSV is the working format — filtered, sorted, pivoted, and the only sane
 * answer above a few thousand events. PDF is the handover format: one document,
 * paginated, that reads the same in five years as it does today and does not
 * invite editing. Same query, same rows, same event count; the difference is
 * what the person on the other end does with it.
 */
enum class AuditExportFormat(val extension: String, val contentType: String) {
    CSV("csv", "text/csv; charset=UTF-8"),
    PDF("pdf", "application/pdf"),
}

/** A rendered export, ready to be handed to the browser. */
data class AuditExport(
    val filename: String,
    val contentType: String,
    val bytes: ByteArray,
    val rowCount: Int,
)

/**
 * Whether a chain is intact, in a shape an API can return.
 *
 * [ChainVerification] is a sealed hierarchy because that is the right model in
 * the domain; this is the flat version, so a client does not have to know the
 * hierarchy to render one sentence.
 */
data class ChainVerificationView(
    val chainKey: String,
    val eventCount: Int,
    val intact: Boolean,
    val brokenAtSequence: Long? = null,
    val reason: String? = null,
)

/**
 * The auditor-facing surface: an export Dana can hand over, and a check that
 * says whether it can be trusted.
 *
 * This is the request the client made in her own words — *"a history I can hand
 * to an auditor"* — and the two halves answer the two questions an auditor asks.
 * The CSV answers "what happened, to whom, and when". The verification answers
 * "how do you know this is all of it", which a database dump cannot answer at
 * all: every row carries the hash that binds it to its predecessor, and the
 * chain either walks cleanly or names the event where it stops.
 *
 * Exporting is itself an audited event. Data leaving the system is recorded
 * wherever it goes (§7), and an export of the audit log is not an exception to
 * that rule — it is the case that most needs it.
 */
@Service
class AuditExportService(
    private val events: ActivityEventRepository,
    private val suppliers: SupplierService,
    private val catalog: CatalogRepository,
    private val users: UserRepository,
    private val recorder: ActivityRecorder,
    private val pdf: SimpleDocumentRenderer,
    private val properties: AcmeProperties,
) {

    @Transactional
    fun export(
        actor: Actor,
        request: AuditExportRequest,
        format: AuditExportFormat = AuditExportFormat.CSV,
    ): AuditExport {
        requireStaff(actor)

        if (request.from != null && request.to != null && request.from.isAfter(request.to)) {
            throw InvalidRequestException("The start date is after the end date.")
        }

        // Resolving the names also proves the caller may read them: a supplier
        // is loaded through the same visibility check every other screen uses,
        // so a program manager asking for a supplier outside their programs is
        // refused here rather than handed an empty file they would read as
        // "nothing ever happened".
        val supplierName = request.supplierId?.let { suppliers.visibleSnapshot(actor, it).supplier.legalName }
        val program = request.programId?.let { programId ->
            catalog.programs().firstOrNull { it.id == programId }
                ?: throw NotFoundException("That program no longer exists.")
        }

        val scope = programScope(actor)
        if (scope != null && program != null && program.id !in scope) {
            throw AccessDeniedException("That program is not one of yours.")
        }

        val zone = properties.businessTimeZone
        val rows = events.export(
            AuditExportQuery(
                supplierId = request.supplierId,
                programId = request.programId,
                // A calendar date becomes an instant in Acme's zone, and the
                // end of the range is the start of the following day: "to the
                // 14th" means through the end of the 14th, which is the only
                // reading a person filling in this form has in mind.
                from = request.from?.atStartOfDay(zone)?.toInstant(),
                until = request.to?.plusDays(1)?.atStartOfDay(zone)?.toInstant(),
                programScope = scope,
            ),
            limit = MAX_ROWS + 1,
        )

        if (rows.size > MAX_ROWS) {
            throw InvalidRequestException(
                "That covers more than $MAX_ROWS events, which is more than a spreadsheet is useful for. " +
                    "Narrow the dates, or export one supplier or program at a time.",
            )
        }

        // The PDF cap is far lower on purpose. Past a couple of thousand events
        // it is hundreds of pages nobody reads, and the honest answer is the
        // format built for that volume rather than a document that pretends to
        // be readable.
        if (format == AuditExportFormat.PDF && rows.size > MAX_PDF_ROWS) {
            throw InvalidRequestException(
                "$MAX_PDF_ROWS events is about as much as a PDF is worth reading. Narrow the range for " +
                    "the document, or take the CSV — it holds the same events either way.",
            )
        }

        recorder.record(
            action = AuditAction.AUDIT_EXPORTED,
            subjectType = "AUDIT_LOG",
            subjectId = request.supplierId,
            actor = actor,
            // A single-supplier export lands in that supplier's own chain, so
            // "who took a copy of this company's history" is answerable from
            // the record it concerns. A broader one has no chain to belong to
            // and goes to the system chain.
            supplierId = request.supplierId,
            after = mapOf(
                "format" to format.name,
                "supplier" to supplierName,
                "program" to program?.code,
                "from" to request.from?.toString(),
                "to" to request.to?.toString(),
                "rowCount" to rows.size,
            ),
        )

        val bytes = when (format) {
            AuditExportFormat.CSV -> Csv.render(HEADER, rows.map(::toCsvRow)).toByteArray(Charsets.UTF_8)
            AuditExportFormat.PDF -> pdf.render(
                title = "Acme supplier onboarding — activity history",
                lines = pdfLines(rows, supplierName, program?.name, request),
            )
        }

        return AuditExport(
            filename = filename(supplierName, program?.code, format),
            contentType = format.contentType,
            bytes = bytes,
            rowCount = rows.size,
        )
    }

    /**
     * The PDF, as an auditor reads it: a cover block saying exactly what was
     * asked for, then one paragraph per event in the order they happened.
     *
     * Written as lines rather than as a layout because the renderer is the same
     * one that produces executed agreements, and an audit document has the same
     * requirement as a signed one — legible, complete, and boring.
     */
    private fun pdfLines(
        rows: List<AuditExportRow>,
        supplierName: String?,
        programName: String?,
        request: AuditExportRequest,
    ): List<String> = buildList {
        add("Generated: ${DateTimeFormatter.ISO_INSTANT.format(Instant.now())}")
        add("Supplier: ${supplierName ?: "every supplier in scope"}")
        add("Program: ${programName ?: "every program"}")
        add(
            "Period: " + when {
                request.from != null && request.to != null -> "${request.from} to ${request.to}"
                request.from != null -> "from ${request.from}"
                request.to != null -> "up to ${request.to}"
                else -> "the whole history"
            } + " (dates in ${properties.businessTimeZone})",
        )
        add("Events: ${rows.size}")
        add("")
        add(
            "Every event below is held in an append-only, hash-chained log. Each line's " +
                "position and hash are printed so this document can be checked against the system " +
                "it came from. Tax IDs and bank details never appear: the log records that they " +
                "were set, never what they were set to.",
        )
        add("")

        rows.forEach { row ->
            add("${DateTimeFormatter.ISO_INSTANT.format(row.occurredAt)}  ${row.action}")
            row.supplierLegalName?.let { name ->
                val programs = row.programCodes.joinToString(" ").ifBlank { "no programs" }
                add("    Supplier: $name ($programs)")
            }
            add("    By: ${row.actorLabel}")
            add("    Subject: ${row.subjectType}${row.subjectId?.let { " $it" } ?: ""}")
            row.beforeState?.let { add("    Before: $it") }
            row.afterState?.let { add("    After: $it") }
            row.requestOrigin?.let { add("    Origin: $it") }
            add("    Chain: ${row.chainKey} #${row.sequence}  hash ${row.eventHash}")
            add("")
        }
    }

    /**
     * Walks one chain and reports the first break.
     *
     * Offered next to the export rather than buried in an admin tool: a history
     * is only evidence if somebody can check it, and the check has to be
     * available to the person handing it over.
     */
    @Transactional(readOnly = true)
    fun verifyChain(actor: Actor, chainKey: String): ChainVerificationView {
        requireStaff(actor)

        if (chainKey == ActivityRecorder.SYSTEM_CHAIN) {
            // The system chain carries user administration and scheduled jobs,
            // which is Acme's own house rather than any supplier's. A program
            // manager is scoped to programs and has no business in it.
            actor.requireOps()
        } else {
            val supplierId = runCatching { UUID.fromString(chainKey) }.getOrNull()
                ?: throw NotFoundException("There is no audit chain by that name.")
            suppliers.visibleSnapshot(actor, supplierId)
        }

        val eventCount = events.count(chainKey)
        return when (val verification = recorder.verify(chainKey)) {
            is ChainVerification.Intact ->
                ChainVerificationView(chainKey, eventCount, intact = true)

            is ChainVerification.Broken -> ChainVerificationView(
                chainKey = chainKey,
                eventCount = eventCount,
                intact = false,
                brokenAtSequence = verification.sequence,
                reason = verification.reason.name,
            )
        }
    }

    private fun requireStaff(actor: Actor) {
        if (actor.role == Role.SUPPLIER_USER) {
            throw AccessDeniedException("The audit export is for Acme staff.")
        }
    }

    /** Null means "no program restriction"; an empty set means "sees nothing". */
    private fun programScope(actor: Actor): Set<UUID>? =
        if (actor.role == Role.PROGRAM_MANAGER) users.programScope(actor.userId).toSet() else null

    private fun toCsvRow(row: AuditExportRow): List<String?> = listOf(
        // ISO-8601 in UTC, with the offset spelled out. An auditor reconciling
        // this against a mail server log or a VMS record needs an instant that
        // cannot be misread, not a friendly local rendering.
        DateTimeFormatter.ISO_INSTANT.format(row.occurredAt),
        row.supplierLegalName,
        row.programCodes.joinToString(" "),
        row.action,
        row.actorLabel,
        row.subjectType,
        row.subjectId?.toString(),
        row.beforeState,
        row.afterState,
        row.requestOrigin,
        row.chainKey,
        row.sequence.toString(),
        row.eventHash,
    )

    /**
     * A name that says what is inside without being opened.
     *
     * Auditors receive several of these, and `export(3).csv` in a downloads
     * folder is how the wrong period gets attached to a response.
     */
    private fun filename(supplierName: String?, programCode: String?, format: AuditExportFormat): String {
        val scope = supplierName?.let(::slug) ?: programCode?.let(::slug) ?: "all-suppliers"
        val today = LocalDate.now(properties.businessTimeZone)
        return "acme-audit-$scope-$today.${format.extension}"
    }

    private fun slug(value: String): String =
        value.lowercase()
            .map { if (it.isLetterOrDigit()) it else '-' }
            .joinToString("")
            .replace(Regex("-+"), "-")
            // Trimmed after truncating, or a name cut mid-word leaves the
            // separator dangling against the date.
            .take(40)
            .trim('-')
            .ifBlank { "supplier" }

    private companion object {
        /**
         * Past this the file stops being something a person reads and starts
         * being a database dump with extra steps. Refusing with a message that
         * names the way out beats silently truncating an audit artifact, which
         * is the one thing an export like this must never do.
         */
        const val MAX_ROWS = 50_000

        /** A document, not a database dump: roughly two hundred readable pages. */
        const val MAX_PDF_ROWS = 2_000

        val HEADER = listOf(
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
    }
}
