package com.acme.onboarding.adapter.web

import com.acme.onboarding.application.extraction.DocumentExtractionService
import com.acme.onboarding.application.extraction.ExtractionView
import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.tags.Tag
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController
import java.util.UUID

/**
 * Field extraction from an uploaded document.
 *
 * Three endpoints and a clear split: reading is free of side effects, running
 * the model transmits the document to a third party and is recorded as such, and
 * correcting the expiry date is the only one that changes anything a supplier
 * would notice — which is why it is an explicit act rather than a consequence.
 */
@RestController
@RequestMapping("/api/documents/{submissionId}/extraction")
@Tag(name = "Field extraction")
class ExtractionController(private val extraction: DocumentExtractionService) {

    @GetMapping
    @Operation(summary = "What has already been read off this document, if anything")
    fun current(@PathVariable submissionId: UUID): ExtractionView =
        extraction.current(CurrentActor.require(), submissionId)

    @PostMapping
    @Operation(summary = "Read the document's fields and compare them with the supplier record")
    fun extract(@PathVariable submissionId: UUID): ExtractionView =
        extraction.extract(CurrentActor.require(), submissionId)

    @PostMapping("/expiry")
    @Operation(summary = "Correct the recorded expiry date to the one printed on the certificate")
    fun applyExpiry(@PathVariable submissionId: UUID): ExtractionView =
        extraction.applyExtractedExpiry(CurrentActor.require(), submissionId)
}
