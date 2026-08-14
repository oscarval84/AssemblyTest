package com.acme.onboarding.application.extraction

import java.time.LocalDate

/**
 * What a certificate of insurance says, as the model reads it.
 *
 * Every field is nullable because a certificate that does not say something is
 * the normal case, not an error — a scan can be cut off, a broker can leave the
 * workers' compensation box empty, and the honest answer is "it does not say".
 * A field the model guesses at is worse than a field it leaves null: a reviewer
 * checks nulls and trusts values.
 */
data class CoiFields(
    val insurer: String? = null,
    val policyNumber: String? = null,
    val namedInsured: String? = null,
    val certificateHolder: String? = null,
    val generalLiabilityEachOccurrence: Long? = null,
    val generalLiabilityAggregate: Long? = null,
    val effectiveOn: LocalDate? = null,
    val expiresOn: LocalDate? = null,
    val workersCompensationPresent: Boolean? = null,
    val signed: Boolean? = null,
)

/** One certificate, with everything the model needs to read it in context. */
data class ExtractionRequest(
    val companyLegalName: String,
    val contentType: String,
    val bytes: ByteArray,
)

data class ExtractionOutcome(
    val fields: CoiFields,
    /** 0 to 1, and lower on a phone photograph than on a broker's PDF. */
    val confidence: Double?,
)

/**
 * Reads the fields off a certificate of insurance.
 *
 * A port for the same reason the criteria evaluator is one: the product has to
 * be correct without it. Expiry dates are typed by the supplier at upload and
 * validated there, so extraction never *supplies* the date the compliance engine
 * runs on — it checks it, and disagreeing with a person is the whole value.
 */
interface CoiExtractor {

    /** False when no API key is configured; the UI then never offers the button. */
    val available: Boolean

    /** Recorded on every extraction and every disclosure event. */
    val model: String

    fun extract(request: ExtractionRequest): ExtractionOutcome
}

/** The default: no model, and it says so rather than inventing fields. */
object DisabledCoiExtractor : CoiExtractor {
    override val available = false
    override val model = "none"

    override fun extract(request: ExtractionRequest): ExtractionOutcome =
        throw UnsupportedOperationException(
            "No model is configured (acme.ai.api-key is unset), so certificates are read by a person.",
        )
}
