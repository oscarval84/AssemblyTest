package com.acme.onboarding.adapter.web

import com.acme.onboarding.adapter.persistence.RejectionReasonRecord
import com.acme.onboarding.application.document.DocumentReviewService
import com.acme.onboarding.application.document.DocumentService
import com.acme.onboarding.application.document.DownloadResult
import com.acme.onboarding.application.document.RejectionGrounds
import com.acme.onboarding.application.document.ReviewQueueItem
import com.acme.onboarding.application.document.SignatureService
import com.acme.onboarding.application.support.InvalidRequestException
import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.tags.Tag
import jakarta.validation.Valid
import jakarta.validation.constraints.NotBlank
import org.springframework.core.io.ByteArrayResource
import org.springframework.core.io.Resource
import org.springframework.http.ContentDisposition
import org.springframework.http.HttpHeaders
import org.springframework.http.HttpStatus
import org.springframework.http.MediaType
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.ResponseStatus
import org.springframework.web.bind.annotation.RestController
import java.util.UUID

@RestController
@RequestMapping("/api/documents")
@Tag(name = "Documents")
class DocumentController(
    private val documents: DocumentService,
    private val signatures: SignatureService,
    private val review: DocumentReviewService,
) {

    /**
     * Exactly one kind of grounds, never both.
     *
     * [criterionId] is the primary path — a criterion the reviewer failed the
     * document on, quoted back to the supplier in Acme's own words. [reasonCode]
     * covers what criteria cannot express: an illegible scan, the wrong document
     * entirely.
     */
    data class RejectBody(
        val criterionId: UUID? = null,
        val reasonCode: String? = null,
        /** Optional, and the difference between a reason and an explanation. */
        val note: String? = null,
    ) {
        fun grounds(): RejectionGrounds = when {
            criterionId != null && reasonCode != null -> throw InvalidRequestException(
                "A rejection rests on one thing: a failed criterion, or a reason from the catalog.",
            )

            criterionId != null -> RejectionGrounds.Criterion(criterionId)
            !reasonCode.isNullOrBlank() -> RejectionGrounds.CatalogReason(reasonCode)
            else -> throw InvalidRequestException("Choose a reason the supplier will understand.")
        }
    }

    data class SignBody(
        val supplierId: UUID,
        @field:NotBlank(message = "Tell us which document you are signing.")
        val documentTypeCode: String,
        val enrollmentId: UUID? = null,
        @field:NotBlank(message = "Type your full name as your signature.")
        val typedName: String,
    )

    /**
     * Never a public object URL. The request is authorised, the access is
     * recorded, and only then does the caller reach the bytes — as a short-lived
     * signed URL in Cloud Storage, or streamed from the local store in development.
     */
    @GetMapping("/{id}/download")
    @Operation(summary = "Fetch a document, recording who read it")
    fun download(@PathVariable id: UUID): ResponseEntity<Resource> =
        when (val result = documents.download(CurrentActor.require(), id)) {
            is DownloadResult.Redirect ->
                ResponseEntity.status(HttpStatus.FOUND).location(result.uri).build()

            is DownloadResult.Streamed ->
                ResponseEntity.ok()
                    .contentType(MediaType.parseMediaType(result.contentType))
                    .header(
                        HttpHeaders.CONTENT_DISPOSITION,
                        ContentDisposition.inline().filename(result.filename).build().toString(),
                    )
                    // Documents are Confidential or Restricted; no shared cache
                    // should ever hold a copy.
                    .header(HttpHeaders.CACHE_CONTROL, "no-store")
                    .body(ByteArrayResource(result.bytes))
        }

    @GetMapping("/review-queue")
    @Operation(summary = "Documents waiting on Acme, oldest first")
    fun reviewQueue(): List<ReviewQueueItem> = review.queue(CurrentActor.require())

    @GetMapping("/rejection-reasons")
    @Operation(summary = "The reason catalog, so rejecting is one click rather than a blank box")
    fun rejectionReasons(): List<RejectionReasonRecord> = review.rejectionReasons(CurrentActor.require())

    @PostMapping("/{id}/approve")
    @Operation(summary = "Accept a document; approving the last one completes onboarding")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    fun approve(@PathVariable id: UUID) = review.approve(CurrentActor.require(), id)

    @PostMapping("/{id}/reject")
    @Operation(summary = "Hand a document back with a reason the supplier can act on")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    fun reject(@PathVariable id: UUID, @Valid @RequestBody body: RejectBody) =
        review.reject(CurrentActor.require(), id, body.grounds(), body.note)

    @GetMapping("/agreement")
    @Operation(summary = "The agreement text and its hash, as shown before signing")
    fun agreement(
        @RequestParam supplierId: UUID,
        @RequestParam documentTypeCode: String,
    ): SignatureService.AgreementPreview =
        signatures.preview(CurrentActor.require(), supplierId, documentTypeCode)

    @PostMapping("/sign")
    @Operation(summary = "Sign an agreement, producing an executed PDF")
    @ResponseStatus(HttpStatus.CREATED)
    fun sign(@Valid @RequestBody body: SignBody): Map<String, UUID> {
        val submissionId = signatures.sign(
            CurrentActor.require(),
            SignatureService.SignRequest(
                supplierId = body.supplierId,
                documentTypeCode = body.documentTypeCode,
                enrollmentId = body.enrollmentId,
                typedName = body.typedName,
            ),
        )
        return mapOf("submissionId" to submissionId)
    }
}
