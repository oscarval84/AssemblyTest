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

/**
 * What a W-9 says — minus the one thing on it that matters most.
 *
 * There is no taxpayer identification number here, and there is no schema field
 * for one. Acme decides whether the *document* is transmitted; this system does
 * not get to decide to keep a second copy of a taxpayer ID outside the encrypted
 * column built for it. With nothing to read it into, no configuration can cause
 * it to be stored.
 */
data class W9Fields(
    val legalName: String? = null,
    val businessName: String? = null,
    val taxClassification: String? = null,
    val address: String? = null,
    val signed: Boolean? = null,
)

/** One document, with everything the model needs to read it in context. */
data class ExtractionRequest(
    val documentTypeCode: String,
    val companyLegalName: String,
    val contentType: String,
    val bytes: ByteArray,
)

/**
 * What was read. Exactly one of the two shapes is populated, decided by the
 * document type — a certificate has no tax classification and a W-9 has no
 * coverage limit, and a single flat type carrying both would invite a reviewer
 * to look for a field that cannot exist.
 */
data class ExtractionOutcome(
    val coi: CoiFields? = null,
    val w9: W9Fields? = null,
    /** 0 to 1, and lower on a phone photograph than on a broker's PDF. */
    val confidence: Double? = null,
)

/**
 * Reads the fields off an uploaded document.
 *
 * A port for the same reason the criteria evaluator is one: the product has to
 * be correct without it. Expiry dates are typed by the supplier at upload and
 * validated there, so extraction never *supplies* the date the compliance engine
 * runs on — it checks it, and disagreeing with a person is the whole value.
 */
interface DocumentExtractor {

    /** False when no API key is configured; the UI then never offers the button. */
    val available: Boolean

    /** Recorded on every extraction and every disclosure event. */
    val model: String

    fun extract(request: ExtractionRequest): ExtractionOutcome
}

/** The default: no model, and it says so rather than inventing fields. */
object DisabledDocumentExtractor : DocumentExtractor {
    override val available = false
    override val model = "none"

    override fun extract(request: ExtractionRequest): ExtractionOutcome =
        throw UnsupportedOperationException(
            "No model is configured (acme.ai.api-key is unset), so documents are read by a person.",
        )
}
