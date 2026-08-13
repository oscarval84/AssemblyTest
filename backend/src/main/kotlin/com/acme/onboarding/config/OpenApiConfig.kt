package com.acme.onboarding.config

import io.swagger.v3.oas.models.OpenAPI
import io.swagger.v3.oas.models.info.Info
import io.swagger.v3.oas.models.media.Schema
import org.springdoc.core.customizers.OpenApiCustomizer
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration

@Configuration
class OpenApiConfig {

    @Bean
    fun onboardingApiDefinition(): OpenAPI = OpenAPI().info(
        Info()
            .title("Acme Supplier Onboarding")
            .version("v1")
            .description(
                "Session-authenticated. Every mutating request must echo the CSRF token from " +
                    "the XSRF-TOKEN cookie in an X-XSRF-TOKEN header.",
            ),
    )

    /**
     * Marks every non-nullable property required.
     *
     * The document already carries Kotlin's nullability — an optional field is
     * emitted as a union with `null` — but nothing marks the rest as required, so
     * a generated client types every field as possibly absent. That turns the
     * contract into noise: a frontend forced to null-check `legalName` learns
     * nothing from a backend change that actually makes a field optional.
     *
     * The rule is exactly Kotlin's: nullable means optional, everything else is
     * always present. Applying it to the document rather than working around it
     * in the client is what keeps the generated types worth generating.
     */
    @Bean
    fun kotlinNullabilityAsRequired(): OpenApiCustomizer = OpenApiCustomizer { openApi ->
        openApi.components?.schemas?.values?.forEach { schema ->
            val properties = schema.properties ?: return@forEach
            val required = properties
                .filterValues { property -> !isNullable(property) }
                .keys
                .sorted()

            if (required.isNotEmpty()) schema.required = required
        }
    }

    /** OpenAPI 3.1 expresses nullability as a union of types rather than a flag. */
    private fun isNullable(property: Schema<*>): Boolean =
        property.nullable == true || property.types?.contains("null") == true
}
