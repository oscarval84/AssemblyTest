package com.acme.onboarding.adapter.web

import com.acme.onboarding.application.notification.OutboxQueryService
import com.acme.onboarding.application.notification.OutboxView
import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.tags.Tag
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController
import java.util.UUID

@RestController
@RequestMapping("/api/outbox")
@Tag(name = "Notifications")
class OutboxController(private val outbox: OutboxQueryService) {

    @GetMapping
    @Operation(summary = "Every notification the system has queued, newest first")
    fun list(@RequestParam(required = false) supplierId: UUID?): OutboxView =
        outbox.list(CurrentActor.require(), supplierId)
}
