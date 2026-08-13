package com.acme.onboarding.adapter.web

import com.acme.onboarding.application.demo.DemoDataSeeder
import com.acme.onboarding.application.support.NotFoundException
import com.acme.onboarding.config.AcmeProperties
import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.tags.Tag
import org.springframework.http.HttpStatus
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.ResponseStatus
import org.springframework.web.bind.annotation.RestController

/**
 * Demo-only, and dark in any environment that holds real supplier data — both
 * endpoints answer 404 when `acme.demo.seed-on-startup` is off, so the feature
 * cannot be discovered, let alone used, in production.
 */
@RestController
@RequestMapping("/api/demo")
@Tag(name = "Demo")
class DemoController(
    private val seeder: DemoDataSeeder,
    private val properties: AcmeProperties,
) {

    data class DemoInfo(val password: String, val accounts: List<DemoDataSeeder.DemoAccount>)

    @GetMapping("/accounts")
    @Operation(summary = "The demo sign-ins, shown on the sign-in screen")
    fun accounts(): DemoInfo {
        requireDemoMode()
        return DemoInfo(DemoDataSeeder.DEMO_PASSWORD, DemoDataSeeder.DEMO_ACCOUNTS)
    }

    @PostMapping("/reset")
    @Operation(summary = "Restore the seeded demo world")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    fun reset() {
        requireDemoMode()
        seeder.reset(CurrentActor.require())
    }

    private fun requireDemoMode() {
        if (!properties.demo.seedOnStartup) throw NotFoundException("Not found.")
    }
}
