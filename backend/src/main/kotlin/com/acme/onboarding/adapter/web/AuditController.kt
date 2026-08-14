package com.acme.onboarding.adapter.web

import com.acme.onboarding.application.audit.AuditExport
import com.acme.onboarding.application.audit.AuditExportFormat
import com.acme.onboarding.application.audit.AuditExportRequest
import com.acme.onboarding.application.audit.AuditExportService
import com.acme.onboarding.application.audit.ChainVerificationView
import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.tags.Tag
import org.springframework.format.annotation.DateTimeFormat
import org.springframework.http.ContentDisposition
import org.springframework.http.HttpHeaders
import org.springframework.http.MediaType
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController
import java.time.LocalDate
import java.util.UUID

/**
 * The auditor-facing endpoints.
 *
 * The export is a GET that writes an audit event, which is unusual enough to
 * say out loud: it is not a cache-safe read, and it is deliberately a link
 * rather than a form so the browser saves the file with no JavaScript holding a
 * copy of the audit log in memory. `no-store` keeps it out of shared caches for
 * the same reason document downloads carry it — the file contains every state
 * change in the range, and one of those ranges covers every supplier.
 */
@RestController
@RequestMapping("/api/audit")
@Tag(name = "Audit")
class AuditController(private val audit: AuditExportService) {

    @GetMapping("/export.csv", produces = ["text/csv"])
    @Operation(summary = "The activity history as a CSV, filtered by supplier, program and date range")
    fun exportCsv(
        @RequestParam(required = false) supplierId: UUID?,
        @RequestParam(required = false) programId: UUID?,
        @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) from: LocalDate?,
        @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) to: LocalDate?,
    ): ResponseEntity<ByteArray> = respond(
        audit.export(
            CurrentActor.require(),
            AuditExportRequest(supplierId = supplierId, programId = programId, from = from, to = to),
            AuditExportFormat.CSV,
        ),
    )

    /**
     * The same events, as a document rather than a spreadsheet.
     *
     * Two formats because the recipients differ: the CSV goes to whoever will
     * filter and pivot it, and the PDF is what actually gets attached to an
     * audit response — paginated, unedited, and readable without a spreadsheet.
     */
    @GetMapping("/export.pdf", produces = ["application/pdf"])
    @Operation(summary = "The same activity history as a paginated PDF, for handing over")
    fun exportPdf(
        @RequestParam(required = false) supplierId: UUID?,
        @RequestParam(required = false) programId: UUID?,
        @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) from: LocalDate?,
        @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) to: LocalDate?,
    ): ResponseEntity<ByteArray> = respond(
        audit.export(
            CurrentActor.require(),
            AuditExportRequest(supplierId = supplierId, programId = programId, from = from, to = to),
            AuditExportFormat.PDF,
        ),
    )

    private fun respond(export: AuditExport): ResponseEntity<ByteArray> =
        ResponseEntity.ok()
            .contentType(MediaType.parseMediaType(export.contentType))
            .header(
                HttpHeaders.CONTENT_DISPOSITION,
                ContentDisposition.attachment().filename(export.filename).build().toString(),
            )
            .header(HttpHeaders.CACHE_CONTROL, "no-store")
            // So a caller scripting this — or a test — can assert on how many
            // events came back without parsing the file.
            .header("X-Audit-Row-Count", export.rowCount.toString())
            .body(export.bytes)

    @GetMapping("/chains/{chainKey}/verification")
    @Operation(summary = "Walk one chain and report the first break, if there is one")
    fun verify(@PathVariable chainKey: String): ChainVerificationView =
        audit.verifyChain(CurrentActor.require(), chainKey)
}
