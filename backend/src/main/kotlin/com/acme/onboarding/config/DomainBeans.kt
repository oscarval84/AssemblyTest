package com.acme.onboarding.config

import com.acme.onboarding.adapter.ai.AnthropicCriteriaEvaluator
import com.acme.onboarding.adapter.ai.AnthropicDocumentExtractor
import com.acme.onboarding.application.criteria.CriteriaEvaluator
import com.acme.onboarding.application.criteria.DisabledCriteriaEvaluator
import com.acme.onboarding.application.extraction.DisabledDocumentExtractor
import com.acme.onboarding.application.extraction.DocumentExtractor
import com.acme.onboarding.domain.compliance.ComplianceEvaluator
import com.acme.onboarding.domain.requirement.RequirementResolver
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder
import org.springframework.security.crypto.encrypt.BytesEncryptor
import org.springframework.security.crypto.encrypt.Encryptors
import org.springframework.security.crypto.password.PasswordEncoder
import tools.jackson.databind.ObjectMapper
import java.time.Clock

/**
 * The domain classes are plain Kotlin with no framework annotations, so they are
 * wired here rather than component-scanned. That is the point of the layering:
 * the compliance rules can be constructed in a test with a fixed clock and no
 * Spring context at all.
 */
@Configuration
class DomainBeans {

    /** Injected everywhere instead of `Instant.now()`, so time is testable. */
    @Bean
    fun clock(): Clock = Clock.systemUTC()

    @Bean
    fun complianceEvaluator(properties: AcmeProperties, clock: Clock): ComplianceEvaluator =
        ComplianceEvaluator(
            businessZone = properties.businessTimeZone,
            warningDays = properties.compliance.expiryWarningDays,
            clock = clock,
        )

    @Bean
    fun requirementResolver(): RequirementResolver = RequirementResolver()

    /**
     * The model that prefills the criteria checklist, or the honest absence of
     * one.
     *
     * Chosen here rather than with a conditional annotation so the fallback is
     * something a reader can see: a blank key selects [DisabledCriteriaEvaluator],
     * the UI stops offering the button, and review carries on with a person
     * ticking each criterion.
     */
    @Bean
    fun criteriaEvaluator(properties: AcmeProperties, objectMapper: ObjectMapper): CriteriaEvaluator =
        properties.ai.apiKey.takeIf { it.isNotBlank() }
            ?.let { AnthropicCriteriaEvaluator(it, properties.ai.model, objectMapper) }
            ?: DisabledCriteriaEvaluator

    /**
     * Reads the fields off an uploaded document, or the honest absence of one.
     * Same key as the criteria prefill: an environment either has a model or it
     * does not, and half of one would be a confusing thing to explain.
     *
     * Which document types may actually be sent is not decided here — see
     * `DocumentExtractionService`, where the W-9's switch lives.
     */
    @Bean
    fun documentExtractor(properties: AcmeProperties, objectMapper: ObjectMapper): DocumentExtractor =
        properties.ai.apiKey.takeIf { it.isNotBlank() }
            ?.let { AnthropicDocumentExtractor(it, properties.ai.model, objectMapper) }
            ?: DisabledDocumentExtractor

    @Bean
    fun passwordEncoder(): PasswordEncoder = BCryptPasswordEncoder()

    /**
     * Field-level encryption for Restricted values — the tax ID today, bank
     * details next. AES-256-GCM, so a modified ciphertext fails to decrypt
     * rather than yielding plausible garbage.
     *
     * This protects against a database copy leaving the perimeter — a backup, a
     * dump, a read replica someone points a tool at. It is not a substitute for
     * access control, and the key living in Secret Manager rather than in this
     * repository is what makes it worth anything at all.
     */
    @Bean
    fun fieldEncryptor(properties: AcmeProperties): BytesEncryptor = Encryptors.stronger(
        properties.security.fieldEncryptionKey,
        properties.security.fieldEncryptionSalt,
    )
}
