package com.acme.onboarding.adapter.ai

import com.acme.onboarding.application.extraction.CoiFields
import com.acme.onboarding.application.extraction.DocumentExtractor
import com.acme.onboarding.application.extraction.ExtractionOutcome
import com.acme.onboarding.application.extraction.ExtractionRequest
import com.acme.onboarding.application.extraction.W9Fields
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
class AnthropicDocumentExtractor(
    apiKey: String,
    override val model: String,
    private val objectMapper: ObjectMapper,
) : DocumentExtractor {

    private val log = LoggerFactory.getLogger(javaClass)

    private val client: AnthropicClient = AnthropicOkHttpClient.builder()
        .apiKey(apiKey)
        .build()

    override val available = true

    override fun extract(request: ExtractionRequest): ExtractionOutcome {
        val encoded = Base64.getEncoder().encodeToString(request.bytes)
        val taxForm = request.documentTypeCode == W9

        val params = MessageCreateParams.builder()
            .model(model)
            .maxTokens(MAX_TOKENS)
            .thinking(ThinkingConfigAdaptive.builder().build())
            .outputConfig(
                OutputConfig.builder()
                    .format(
                        JsonOutputFormat.builder()
                            .schema(JsonValue.from(if (taxForm) W9_SCHEMA else COI_SCHEMA))
                            .build(),
                    )
                    .build(),
            )
            .system(if (taxForm) W9_SYSTEM_PROMPT else COI_SYSTEM_PROMPT)
            .addUserMessageOfBlockParams(
                listOf(
                    AnthropicDocuments.block(request.contentType, encoded),
                    AnthropicDocuments.text(
                        if (taxForm) {
                            """
                            Read the attached Form W-9 for ${request.companyLegalName}.

                            Report only what the form shows, and only the fields in the schema.
                            Leave a field null when the form does not say or that part of the
                            scan is not legible.
                            """.trimIndent()
                        } else {
                            """
                            Read the attached certificate of insurance for ${request.companyLegalName}.

                            Report only what the document shows. Leave a field null when the
                            certificate does not say, when the relevant box is empty, or when
                            that part of the scan is not legible.
                            """.trimIndent()
                        },
                    ),
                ),
            )
            .build()

        val response = client.messages().create(params)
        val json = response.content()
            .flatMap { it.text().map(::listOf).orElse(emptyList()) }
            .joinToString("") { it.text() }

        return if (taxForm) parseTaxForm(json) else parseCertificate(json)
    }

    private fun parseCertificate(json: String): ExtractionOutcome {
        val parsed = runCatching { objectMapper.readValue(json, CertificateResponse::class.java) }
            .getOrElse {
                log.warn("Could not read the model's response as a certificate", it)
                return ExtractionOutcome(coi = CoiFields())
            }

        return ExtractionOutcome(
            coi = CoiFields(
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

    /**
     * Reads a W-9, and cannot read a taxpayer identification number.
     *
     * There is no field for one in the schema, none in [W9Fields], and none in
     * this parser. Whether the document is sent at all is Acme's decision; the
     * absence of anywhere to put the number is ours.
     */
    private fun parseTaxForm(json: String): ExtractionOutcome {
        val parsed = runCatching { objectMapper.readValue(json, TaxFormResponse::class.java) }
            .getOrElse {
                log.warn("Could not read the model's response as a W-9", it)
                return ExtractionOutcome(w9 = W9Fields())
            }

        return ExtractionOutcome(
            w9 = W9Fields(
                legalName = parsed.legalName.clean(),
                businessName = parsed.businessName.clean(),
                taxClassification = parsed.taxClassification.clean(),
                address = parsed.address.clean(),
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

    private data class CertificateResponse(
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

    private data class TaxFormResponse(
        val legalName: String? = null,
        val businessName: String? = null,
        val taxClassification: String? = null,
        val address: String? = null,
        val signed: Boolean? = null,
        val confidence: Double? = null,
    )

    private companion object {
        const val MAX_TOKENS = 8_000L
        const val W9 = "W9"

        val COI_SYSTEM_PROMPT = """
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
        val COI_SCHEMA: Map<String, Any> = mapOf(
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

        /**
         * Note what this prompt does not ask for, and says so out loud: the
         * model is told to ignore the taxpayer identification number. The
         * schema has no field for it either, so this is a second lock on the
         * same door rather than the only one.
         */
        val W9_SYSTEM_PROMPT = """
            You read IRS Form W-9 for Acme, a managed service provider that onboards
            staffing suppliers.

            Report only the fields in the schema: the name and business name as written,
            the federal tax classification that is checked, the address, and whether the
            certification block carries a signature.

            Do not report the taxpayer identification number, the SSN, or the EIN. Do not
            transcribe it, summarise it, or refer to it in any field. There is no field
            for it and it is not wanted.

            Leave a field null when the form does not say or that part of the scan is not
            legible. Set confidence low when the scan is poor, cropped, or handwritten.
        """.trimIndent()

        val W9_SCHEMA: Map<String, Any> = mapOf(
            "type" to "object",
            "properties" to mapOf(
                "legalName" to nullableString("Line 1, the name as shown on the income tax return."),
                "businessName" to nullableString("Line 2, business or disregarded entity name, if any."),
                "taxClassification" to nullableString("The federal tax classification checked on line 3."),
                "address" to nullableString("The address on lines 5 and 6, as one line."),
                "signed" to nullableBoolean("Whether the certification block carries a signature."),
                "confidence" to mapOf(
                    "type" to "number",
                    "description" to "0 to 1, reflecting how legible and complete the form is.",
                ),
            ),
            "required" to listOf(
                "legalName", "businessName", "taxClassification", "address", "signed", "confidence",
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
