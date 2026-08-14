package com.acme.onboarding.adapter.ai

import com.acme.onboarding.application.criteria.CriteriaEvaluator
import com.acme.onboarding.application.criteria.EvaluationRequest
import com.acme.onboarding.application.criteria.ModelVerdict
import com.anthropic.client.AnthropicClient
import com.anthropic.client.okhttp.AnthropicOkHttpClient
import com.anthropic.core.JsonValue
import com.anthropic.models.messages.JsonOutputFormat
import com.anthropic.models.messages.MessageCreateParams
import com.anthropic.models.messages.OutputConfig
import com.anthropic.models.messages.ThinkingConfigAdaptive
import org.slf4j.LoggerFactory
import tools.jackson.databind.ObjectMapper
import java.util.Base64

/**
 * The criteria checklist, prefilled by Claude.
 *
 * **Everything here is advisory.** The model returns a verdict and the span it
 * relied on for each criterion Acme authored; a person confirms or overrides
 * every one before anything is approved or rejected. That is why the response is
 * stored with `source = MODEL` rather than merged into the reviewer's own
 * verdicts — the audit trail keeps what the model said and what the human
 * decided as two separate facts.
 *
 * Three implementation choices are load-bearing:
 *
 *  - **Structured outputs, not prompt-and-parse.** The response is constrained
 *    to a JSON schema, so a checklist comes back as data rather than prose to be
 *    regexed. A malformed verdict is a class of failure that simply cannot occur.
 *  - **The document goes up as a document or image block**, not as extracted
 *    text. A certificate of insurance is a layout — dates and limits sit in
 *    boxes — and flattening it to text is how a coverage limit gets read off the
 *    wrong line.
 *  - **Criteria are sent with their identifiers**, and verdicts for identifiers
 *    Acme did not ask about are discarded. The model cannot invent a criterion.
 *
 * The classification gate lives one layer up, in the caller: this class is never
 * handed a Restricted document, and the check belongs where it can refuse with a
 * message rather than where it can only throw.
 */
class AnthropicCriteriaEvaluator(
    apiKey: String,
    override val model: String,
    private val objectMapper: ObjectMapper,
) : CriteriaEvaluator {

    private val log = LoggerFactory.getLogger(javaClass)

    private val client: AnthropicClient = AnthropicOkHttpClient.builder()
        .apiKey(apiKey)
        .build()

    override val available = true

    override fun evaluate(request: EvaluationRequest): List<ModelVerdict> {
        val encoded = Base64.getEncoder().encodeToString(request.bytes)

        val params = MessageCreateParams.builder()
            .model(model)
            .maxTokens(MAX_TOKENS)
            // Adaptive rather than a fixed budget: reading a certificate against
            // two criteria and against twelve are different amounts of work, and
            // the model is a better judge of which it is looking at.
            .thinking(ThinkingConfigAdaptive.builder().build())
            .outputConfig(
                OutputConfig.builder()
                    .format(
                        JsonOutputFormat.builder()
                            .schema(JsonValue.from(responseSchema(request)))
                            .build(),
                    )
                    .build(),
            )
            .system(systemPrompt())
            .addUserMessageOfBlockParams(
                listOf(
                    AnthropicDocuments.block(request.contentType, encoded),
                    AnthropicDocuments.text(taskPrompt(request)),
                ),
            )
            .build()

        val response = client.messages().create(params)
        val json = response.content()
            .flatMap { it.text().map(::listOf).orElse(emptyList()) }
            .joinToString("") { it.text() }

        return parse(json, request)
    }

    private fun systemPrompt(): String =
        """
        You check supplier documents against acceptance criteria that an operations
        team at Acme, a managed service provider, wrote themselves.

        Judge only what the document shows. For each criterion return PASS when the
        document plainly satisfies it, FAIL when the document plainly contradicts
        it, and UNCLEAR when the document does not say, is illegible in the relevant
        place, or is ambiguous. UNCLEAR is the right answer more often than it feels
        like it is — a reviewer reads every one of these, and a confident wrong
        verdict costs them more than an honest "I could not tell".

        Quote the document for your evidence rather than describing it. The supplier
        reads this wording back in a rejection, so "the general liability aggregate
        shows USD 1,000,000" is useful and "coverage appears insufficient" is not.
        """.trimIndent()

    private fun taskPrompt(request: EvaluationRequest): String {
        val criteria = request.criteria.joinToString("\n") { "${it.criterionId}: ${it.text}" }
        return """
            Document: ${request.documentTypeName}
            Supplier: ${request.companyLegalName}
            Program: ${request.programName ?: "applies to every program this supplier is in"}

            Judge the attached document against each criterion below. Return one
            verdict per criterion, using the identifier exactly as given.

            $criteria
        """.trimIndent()
    }

    /**
     * The shape the answer must take.
     *
     * `enum` on the verdict and `additionalProperties: false` throughout are what
     * turn "usually returns the right shape" into "cannot return another one".
     */
    private fun responseSchema(request: EvaluationRequest): Map<String, Any> = mapOf(
        "type" to "object",
        "properties" to mapOf(
            "verdicts" to mapOf(
                "type" to "array",
                "items" to mapOf(
                    "type" to "object",
                    "properties" to mapOf(
                        "criterionId" to mapOf(
                            "type" to "string",
                            "description" to "The identifier of the criterion, copied exactly.",
                            "enum" to request.criteria.map { it.criterionId.toString() },
                        ),
                        "verdict" to mapOf("type" to "string", "enum" to listOf("PASS", "FAIL", "UNCLEAR")),
                        "evidence" to mapOf(
                            "type" to "string",
                            "description" to "What the document shows, quoted. Empty when it shows nothing relevant.",
                        ),
                        "confidence" to mapOf(
                            "type" to "number",
                            "description" to "0 to 1. How sure you are, given how legible and complete the document is.",
                        ),
                    ),
                    "required" to listOf("criterionId", "verdict", "evidence", "confidence"),
                    "additionalProperties" to false,
                ),
            ),
        ),
        "required" to listOf("verdicts"),
        "additionalProperties" to false,
    )

    /**
     * Reads the response, and keeps only verdicts about criteria that were asked
     * about. The schema already constrains this; discarding anything else is the
     * belt to its braces, and it costs one filter.
     */
    private fun parse(json: String, request: EvaluationRequest): List<ModelVerdict> {
        val asked = request.criteria.associateBy { it.criterionId.toString() }

        val parsed = runCatching { objectMapper.readValue(json, ModelResponse::class.java) }
            .getOrElse {
                log.warn("Could not read the model's response as a criteria checklist", it)
                return emptyList()
            }

        return parsed.verdicts.mapNotNull { verdict ->
            val criterion = asked[verdict.criterionId] ?: return@mapNotNull null
            ModelVerdict(
                criterionId = criterion.criterionId,
                verdict = verdict.verdict.uppercase(),
                evidence = verdict.evidence?.trim()?.takeIf { it.isNotEmpty() },
                confidence = verdict.confidence?.coerceIn(0.0, 1.0),
            )
        }
    }

    /** The wire shape, kept private: nothing outside this adapter should know it. */
    private data class ModelResponse(val verdicts: List<Entry> = emptyList()) {
        data class Entry(
            val criterionId: String = "",
            val verdict: String = "UNCLEAR",
            val evidence: String? = null,
            val confidence: Double? = null,
        )
    }

    private companion object {
        /**
         * Enough for a dozen criteria with quoted evidence, plus the thinking the
         * adaptive setting may spend. Thinking and response share this budget, so
         * a tight number here truncates the answer rather than saving anything.
         */
        const val MAX_TOKENS = 16_000L
    }
}
