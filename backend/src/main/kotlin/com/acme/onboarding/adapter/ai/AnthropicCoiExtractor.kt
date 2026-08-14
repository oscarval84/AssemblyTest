package com.acme.onboarding.adapter.ai

import com.acme.onboarding.application.extraction.CoiExtractor
import com.acme.onboarding.application.extraction.CoiFields
import com.acme.onboarding.application.extraction.ExtractionOutcome
import com.acme.onboarding.application.extraction.ExtractionRequest
import com.anthropic.client.AnthropicClient
import com.anthropic.client.okhttp.AnthropicOkHttpClient
import com.anthropic.core.JsonValue
import com.anthropic.models.messages.JsonOutputFormat
import com.anthropic.models.messages.MessageCreateParams
import com.anthropic.models.messages.OutputConfig
import com.anthropic.models.messages.ThinkingConfigAdaptive
import org.slf4j.LoggerFactory
import tools.jackson.databind.ObjectMapper
import java.time.LocalDate
import java.util.Base64

/**
 * Reads the fields off a certificate of insurance.
 *
 * The stretch goal, and the one whose value is easiest to state: a supplier
 * types the expiry date at upload, the whole compliance engine runs on it, and
 * nobody checks it against the document. This reads the document and disagrees
 * out loud when the two differ — which is the shape of the failure that cost the
 * client twice.
 *
 * **It never supplies the date it checks.** The extraction is stored, compared,
 * and shown to a reviewer; correcting the submission is a separate action a
 * person takes. A model that silently rewrote the date the compliance engine
 * runs on would have replaced a mistake nobody checks with a mistake nobody can
 * see.
 *
 * Two details carry the accuracy. The document goes up as a document or image
 * block rather than as extracted text, because a certificate is a layout — limits
 * and dates sit in boxes, and flattening it is how an aggregate gets read off the
 * "each occurrence" line. And every field is nullable in the schema, with the
 * prompt insisting on null over a guess: a reviewer checks nulls and trusts
 * values, so a guess costs more than a gap.
 */
class AnthropicCoiExtractor(
    apiKey: String,
    override val model: String,
    private val objectMapper: ObjectMapper,
) : CoiExtractor {

    private val log = LoggerFactory.getLogger(javaClass)

    private val client: AnthropicClient = AnthropicOkHttpClient.builder()
        .apiKey(apiKey)
        .build()

    override val available = true

    override fun extract(request: ExtractionRequest): ExtractionOutcome {
        val encoded = Base64.getEncoder().encodeToString(request.bytes)

        val params = MessageCreateParams.builder()
            .model(model)
            .maxTokens(MAX_TOKENS)
            .thinking(ThinkingConfigAdaptive.builder().build())
            .outputConfig(
                OutputConfig.builder()
                    .format(JsonOutputFormat.builder().schema(JsonValue.from(SCHEMA)).build())
                    .build(),
            )
            .system(SYSTEM_PROMPT)
            .addUserMessageOfBlockParams(
                listOf(
                    AnthropicDocuments.block(request.contentType, encoded),
                    AnthropicDocuments.text(
                        """
                        Read the attached certificate of insurance for ${request.companyLegalName}.

                        Report only what the document shows. Leave a field null when the
                        certificate does not say, when the relevant box is empty, or when
                        that part of the scan is not legible.
                        """.trimIndent(),
                    ),
                ),
            )
            .build()

        val response = client.messages().create(params)
        val json = response.content()
            .flatMap { it.text().map(::listOf).orElse(emptyList()) }
            .joinToString("") { it.text() }

        return parse(json)
    }

    private fun parse(json: String): ExtractionOutcome {
        val parsed = runCatching { objectMapper.readValue(json, ModelResponse::class.java) }
            .getOrElse {
                log.warn("Could not read the model's response as a certificate", it)
                return ExtractionOutcome(CoiFields(), null)
            }

        return ExtractionOutcome(
            fields = CoiFields(
                insurer = parsed.insurer.clean(),
                policyNumber = parsed.policyNumber.clean(),
                namedInsured = parsed.namedInsured.clean(),
                certificateHolder = parsed.certificateHolder.clean(),
                generalLiabilityEachOccurrence = parsed.generalLiabilityEachOccurrence,
                generalLiabilityAggregate = parsed.generalLiabilityAggregate,
                effectiveOn = parsed.effectiveOn.asDate(),
                expiresOn = parsed.expiresOn.asDate(),
                workersCompensationPresent = parsed.workersCompensationPresent,
                signed = parsed.signed,
            ),
            confidence = parsed.confidence?.coerceIn(0.0, 1.0),
        )
    }

    private fun String?.clean(): String? = this?.trim()?.takeIf { it.isNotEmpty() }

    /**
     * A date the model could not read comes back null rather than throwing. The
     * schema asks for `YYYY-MM-DD`; anything else is a field the reviewer fills
     * in, not a failed extraction.
     */
    private fun String?.asDate(): LocalDate? =
        this?.trim()?.takeIf { it.isNotEmpty() }?.let { runCatching { LocalDate.parse(it) }.getOrNull() }

    private data class ModelResponse(
        val insurer: String? = null,
        val policyNumber: String? = null,
        val namedInsured: String? = null,
        val certificateHolder: String? = null,
        val generalLiabilityEachOccurrence: Long? = null,
        val generalLiabilityAggregate: Long? = null,
        val effectiveOn: String? = null,
        val expiresOn: String? = null,
        val workersCompensationPresent: Boolean? = null,
        val signed: Boolean? = null,
        val confidence: Double? = null,
    )

    private companion object {
        const val MAX_TOKENS = 8_000L

        val SYSTEM_PROMPT = """
            You read certificates of insurance for Acme, a managed service provider
            that onboards staffing suppliers.

            Report only what the certificate shows. Every field may be null, and null
            is the right answer whenever the document does not say, the box is empty,
            or that part of the scan is not legible — a reviewer checks nulls and
            trusts values, so a guess costs them more than a gap.

            Amounts are whole US dollars with no separators: 2000000, not "2,000,000"
            or "$2M". Read the aggregate from the aggregate line and the each-occurrence
            limit from its own line; they are different numbers and are often confused.
            Dates are YYYY-MM-DD, taken from the policy's own effective and expiration
            fields rather than from the date the certificate was issued.

            Set confidence low when the scan is poor, cropped, or handwritten.
        """.trimIndent()

        /**
         * Every field nullable, `additionalProperties: false`, amounts as integers.
         * The schema is what turns "usually the right shape" into "cannot be another
         * one" — there is no parsing step here that can go wrong at review time.
         */
        val SCHEMA: Map<String, Any> = mapOf(
            "type" to "object",
            "properties" to mapOf(
                "insurer" to nullableString("The insurance company named on the certificate."),
                "policyNumber" to nullableString("The general liability policy number."),
                "namedInsured" to nullableString("The company insured, exactly as written."),
                "certificateHolder" to nullableString("The certificate holder block, exactly as written."),
                "generalLiabilityEachOccurrence" to nullableInteger("Each-occurrence limit, whole US dollars."),
                "generalLiabilityAggregate" to nullableInteger("General aggregate limit, whole US dollars."),
                "effectiveOn" to nullableString("Policy effective date, YYYY-MM-DD."),
                "expiresOn" to nullableString("Policy expiration date, YYYY-MM-DD."),
                "workersCompensationPresent" to nullableBoolean("Whether workers' compensation coverage is shown."),
                "signed" to nullableBoolean("Whether an authorised representative's signature is present."),
                "confidence" to mapOf(
                    "type" to "number",
                    "description" to "0 to 1, reflecting how legible and complete the certificate is.",
                ),
            ),
            "required" to listOf(
                "insurer", "policyNumber", "namedInsured", "certificateHolder",
                "generalLiabilityEachOccurrence", "generalLiabilityAggregate",
                "effectiveOn", "expiresOn", "workersCompensationPresent", "signed", "confidence",
            ),
            "additionalProperties" to false,
        )

        fun nullableString(description: String) =
            mapOf("type" to listOf("string", "null"), "description" to description)

        fun nullableInteger(description: String) =
            mapOf("type" to listOf("integer", "null"), "description" to description)

        fun nullableBoolean(description: String) =
            mapOf("type" to listOf("boolean", "null"), "description" to description)
    }
}
