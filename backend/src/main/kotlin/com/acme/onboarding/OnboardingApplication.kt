package com.acme.onboarding

import org.springframework.boot.autoconfigure.SpringBootApplication
import org.springframework.boot.context.properties.ConfigurationPropertiesScan
import org.springframework.boot.runApplication
import org.springframework.boot.security.autoconfigure.UserDetailsServiceAutoConfiguration
import org.springframework.transaction.annotation.EnableTransactionManagement

/**
 * [UserDetailsServiceAutoConfiguration] is excluded because identities live in
 * `app_user` and are resolved by [com.acme.onboarding.config.SessionAuthenticationFilter].
 * Left in, Spring Boot helpfully creates an in-memory user with a random password
 * and logs it at startup — a credential that does not exist in this system, in a
 * log a reviewer will read.
 */
@SpringBootApplication(exclude = [UserDetailsServiceAutoConfiguration::class])
@ConfigurationPropertiesScan
@EnableTransactionManagement
class OnboardingApplication

fun main(args: Array<String>) {
	runApplication<OnboardingApplication>(*args)
}
