package com.acme.onboarding.adapter.web

import com.acme.onboarding.application.document.DocumentService
import com.acme.onboarding.application.document.DownloadResult
import com.acme.onboarding.application.document.SignatureService
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
) {

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
