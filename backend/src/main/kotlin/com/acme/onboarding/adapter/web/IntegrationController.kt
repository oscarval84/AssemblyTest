package com.acme.onboarding.adapter.web

import com.acme.onboarding.adapter.persistence.IntegrationMessageRecord
import com.acme.onboarding.application.vms.VmsSyncService
import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.tags.Tag
import org.springframework.http.HttpStatus
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.ResponseStatus
import org.springframework.web.bind.annotation.RestController
import java.util.UUID

/**
 * Every pull and every push, with a retry.
 *
 * A silently failing integration is worse than no integration, because everyone
 * downstream believes the VMS is current. This screen is what makes the failure
 * loud, and it is also the answer to "when was the VMS told, and what did we
 * send" — part of the audit story rather than a debugging aid.
 */
@RestController
@RequestMapping("/api/integrations")
@Tag(name = "VMS integration")
class IntegrationController(private val vms: VmsSyncService) {

    @GetMapping("/messages")
    @Operation(summary = "Every message exchanged with the VMS, newest first")
    fun messages(): List<IntegrationMessageRecord> = vms.messages(CurrentActor.require())

    @PostMapping("/messages/{id}/retry")
    @Operation(summary = "Put a failed or dead-lettered push back in the queue")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    fun retry(@PathVariable id: UUID) = vms.retry(CurrentActor.require(), id)
}
