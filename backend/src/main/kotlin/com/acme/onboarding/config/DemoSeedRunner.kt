package com.acme.onboarding.config

import com.acme.onboarding.application.demo.DemoDataSeeder
import org.springframework.boot.ApplicationArguments
import org.springframework.boot.ApplicationRunner
import org.springframework.stereotype.Component

/**
 * Seeds the demo world on an empty database at startup.
 *
 * Only on an *empty* one: re-seeding a database that already has suppliers would
 * be a data-loss bug wearing a convenience feature's clothes. Restoring the demo
 * from a used state is the admin-only reset, where someone has asked for it.
 */
@Component
class DemoSeedRunner(private val seeder: DemoDataSeeder) : ApplicationRunner {

    override fun run(args: ApplicationArguments) {
        seeder.seedIfEmpty()
    }
}
