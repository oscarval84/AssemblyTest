package com.acme.onboarding.adapter.web

import com.acme.onboarding.adapter.persistence.AcceptanceCriterionRecord
import com.acme.onboarding.application.criteria.CriteriaChecklist
import com.acme.onboarding.application.criteria.CriteriaReviewService
import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.tags.Tag
import jakarta.validation.Valid
import jakarta.validation.constraints.NotBlank
import org.springframework.http.HttpStatus
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.PutMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.ResponseStatus
import org.springframework.web.bind.annotation.RestController
import java.util.UUID

/**
 * Acceptance criteria: authored by ops, checked at review time.
 *
 * A requirement with no criteria is normal rather than broken — review falls
 * back to a person reading the document, exactly as it works today.
 */
@RestController
@RequestMapping("/api")
@Tag(name = "Acceptance criteria")
class CriteriaController(private val criteria: CriteriaReviewService) {

    data class CriteriaBody(val criteria: List<String>)

    data class JudgementBody(
        @field:NotBlank(message = "Say whether it passes, fails or is unclear.")
        val verdict: String,
        /** What in the document the verdict rests on; quoted back to the supplier. */
        val evidence: String? = null,
    )

    @GetMapping("/requirements/{requirementId}/criteria")
    @Operation(summary = "The criteria currently in force for a requirement")
    fun current(@PathVariable requirementId: UUID): List<AcceptanceCriterionRecord> =
        criteria.forRequirement(CurrentActor.require(), requirementId)

    @PutMapping("/requirements/{requirementId}/criteria")
    @Operation(summary = "Replace a requirement's criteria, producing a new version")
    fun author(
        @PathVariable requirementId: UUID,
        @RequestBody body: CriteriaBody,
    ): Map<String, Int> = mapOf(
        "version" to criteria.author(CurrentActor.require(), requirementId, body.criteria),
    )

    @GetMapping("/documents/{submissionId}/criteria")
    @Operation(summary = "The criteria checklist for one submission, with any verdicts so far")
    fun checklist(@PathVariable submissionId: UUID): CriteriaChecklist =
        criteria.checklist(CurrentActor.require(), submissionId)

    @PostMapping("/documents/{submissionId}/criteria/{criterionId}")
    @Operation(summary = "Record a reviewer's verdict on one criterion")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    fun judge(
        @PathVariable submissionId: UUID,
        @PathVariable criterionId: UUID,
        @Valid @RequestBody body: JudgementBody,
    ) = criteria.judge(CurrentActor.require(), submissionId, criterionId, body.verdict.uppercase(), body.evidence)

    @GetMapping("/documents/{submissionId}/criteria/{criterionId}/rejection-note")
    @Operation(summary = "The rejection wording a failed criterion produces")
    fun rejectionNote(
        @PathVariable submissionId: UUID,
        @PathVariable criterionId: UUID,
    ): Map<String, String> = mapOf(
        "note" to criteria.rejectionNoteFor(CurrentActor.require(), submissionId, criterionId),
    )
}
