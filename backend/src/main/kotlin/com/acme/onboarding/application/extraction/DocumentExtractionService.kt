package com.acme.onboarding.application.extraction

import com.acme.onboarding.adapter.persistence.CatalogRepository
import com.acme.onboarding.adapter.persistence.EnrollmentRepository
import com.acme.onboarding.adapter.persistence.ExtractionRepository
import com.acme.onboarding.adapter.persistence.SubmissionRecord
import com.acme.onboarding.adapter.persistence.SubmissionRepository
import com.acme.onboarding.adapter.persistence.SupplierRepository
import com.acme.onboarding.application.audit.ActivityRecorder
import com.acme.onboarding.application.audit.AuditAction
import com.acme.onboarding.application.document.DocumentStore
import com.acme.onboarding.application.support.InvalidRequestException
import com.acme.onboarding.application.support.NotFoundException
import com.acme.onboarding.config.AcmeProperties
import com.acme.onboarding.domain.compliance.ComplianceEvaluator
import com.acme.onboarding.domain.extraction.CertificateFindings
import com.acme.onboarding.domain.extraction.ExtractedCertificate
import com.acme.onboarding.domain.extraction.ExtractedTaxForm
import com.acme.onboarding.domain.extraction.ExtractionFinding
import com.acme.onboarding.domain.extraction.ExtractionFlag
import com.acme.onboarding.domain.extraction.TaxFormFindings
import com.acme.onboarding.domain.user.AccessDeniedException
import com.acme.onboarding.domain.user.Actor
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import tools.jackson.databind.ObjectMapper
import java.time.Clock
import java.time.Instant
import java.time.LocalDate
import java.util.UUID

/** What a reviewer sees beside the certificate. */
data class ExtractionView(
    val submissionId: UUID,
    /** False when no model is configured, or the document may not be sent to one. */
    val available: Boolean,
    val model: String?,
    /**
     * Exactly one of these is populated, and only after somebody has run it —
     * running it is always a deliberate act, never a consequence of opening a
     * screen.
     */
    val coi: CoiFields? = null,
    val w9: W9Fields? = null,
    val confidence: Double? = null,
    val findings: List<ExtractionFinding> = emptyList(),
    /** The date on the submission today, for the reviewer to compare against. */
    val recordedExpiry: LocalDate? = null,
    val extractedAt: Instant? = null,
) {
    /**
     * True when the certificate and the submission disagree about expiry, which
     * is the one finding that has a one-click answer.
     */
    val expiryDisagrees: Boolean
        get() = findings.any { it.flag == ExtractionFlag.EXPIRY_MISMATCH }
}

/**
 * Reading an uploaded document, and comparing it with what was claimed.
 *
 * The stretch goal, built last because the product is correct without it: the
 * supplier types the expiry date at upload and the compliance engine runs on
 * that. What was missing is that nobody checked it against the document, and an
 * expiry date wrong by two months is exactly the shape of the failure that let a
 * supplier work on a lapsed certificate twice.
 *
 * So this reads the document and *disagrees out loud*. It never writes the date
 * it checks — [applyExtractedExpiry] is a separate action a person takes, with
 * its own audit event. Replacing a mistake nobody checks with a mistake nobody
 * can see would not be an improvement.
 *
 * **Two documents, two postures.** A certificate of insurance is Confidential
 * and is read wherever a model is configured. A W-9 is Restricted: it carries a
 * taxpayer identification number, the client told us a past vendor was careless
 * with exactly this class of data, and whether it may be transmitted is Acme's
 * decision. So it is off by default and behind a switch **Acme controls** —
 * `acme.ai.w9-extraction-enabled`, false unless set.
 *
 * That is deliberately a setting rather than a refusal in code. The memo tells
 * Acme this decision is theirs; a decision they cannot exercise without a deploy
 * is not theirs. What stays ours, and is not configurable, is that no taxpayer
 * identification number is ever read or stored — see [W9Fields].
 *
 * Everything else Restricted — banking details — is refused outright. Nobody
 * asked for it, and a switch nobody asked for is a switch waiting to be found.
 */
@Service
class DocumentExtractionService(
    private val submissions: SubmissionRepository,
    private val suppliers: SupplierRepository,
    private val enrollments: EnrollmentRepository,
    private val catalog: CatalogRepository,
    private val extractions: ExtractionRepository,
    private val store: DocumentStore,
    private val extractor: DocumentExtractor,
    private val journal: ExtractionJournal,
    private val evaluator: ComplianceEvaluator,
    private val objectMapper: ObjectMapper,
    private val properties: AcmeProperties,
    private val clock: Clock,
) {

    private val log = LoggerFactory.getLogger(javaClass)

    /** What has already been read off this document, without reading it again. */
    @Transactional(readOnly = true)
    fun current(actor: Actor, submissionId: UUID): ExtractionView {
        actor.requireOps()
        val submission = submissions.findById(submissionId)
            ?: throw NotFoundException("That document is no longer available.")

        val stored = extractions.latestFor(submissionId)
        val taxForm = submission.documentTypeCode == W9

        val coi = stored?.takeIf { !taxForm }?.let { objectMapper.readValue(it.extractedJson, CoiFields::class.java) }
        val w9 = stored?.takeIf { taxForm }?.let { objectMapper.readValue(it.extractedJson, W9Fields::class.java) }

        return ExtractionView(
            submissionId = submissionId,
            available = extractor.available && offerable(submission.documentTypeCode),
            model = stored?.model ?: extractor.model.takeIf { extractor.available },
            coi = coi,
            w9 = w9,
            confidence = stored?.confidence,
            findings = findings(coi, w9, submission),
            recordedExpiry = submission.expiresOn,
            extractedAt = stored?.createdAt,
        )
    }

    /**
     * Runs the model over the certificate and stores what it read.
     *
     * Not transactional, for the same reason the criteria prefill is not: the
     * disclosure event commits before the document is transmitted, so a call
     * that times out still leaves the record that it was sent.
     */
    fun extract(actor: Actor, submissionId: UUID): ExtractionView {
        actor.requireOps()

        if (!extractor.available) {
            throw InvalidRequestException(
                "No model is configured in this environment, so documents are read by a person. " +
                    "Nothing is missing from the review — this only saves reading time.",
            )
        }

        val submission = submissions.findById(submissionId)
            ?: throw NotFoundException("That document is no longer available.")

        if (!offerable(submission.documentTypeCode)) {
            throw refusal(submission.documentTypeCode, submission.documentTypeName, submission.classification)
        }

        val supplier = suppliers.findById(submission.supplierId)
            ?: throw NotFoundException("That supplier no longer exists.")

        journal.recordDisclosure(
            actor = actor,
            submissionId = submission.id,
            supplierId = submission.supplierId,
            documentTypeCode = submission.documentTypeCode,
            classification = submission.classification,
            model = extractor.model,
            // Named in the event, so an auditor sampling the log sees exactly
            // when Acme's decision took effect rather than having to correlate
            // a deploy with a configuration change.
            underOverride = submission.classification == RESTRICTED,
        )
        val disclosedAt = Instant.now(clock)

        val outcome = try {
            extractor.extract(
                ExtractionRequest(
                    documentTypeCode = submission.documentTypeCode,
                    companyLegalName = supplier.legalName,
                    contentType = submission.contentType,
                    bytes = store.read(submission.storageKey),
                ),
            )
        } catch (error: Exception) {
            log.warn("Extraction failed for submission {}", submission.id, error)
            throw InvalidRequestException(
                "The model could not be reached just now. Nothing has changed — read the document " +
                    "yourself, or try again.",
            )
        }

        val found = findings(outcome.coi, outcome.w9, submission)

        journal.recordExtraction(
            submissionId = submission.id,
            supplierId = submission.supplierId,
            model = extractor.model,
            extractedJson = objectMapper.writeValueAsString(outcome.coi ?: outcome.w9),
            flagsJson = objectMapper.writeValueAsString(found.map { it.flag.name }),
            confidence = outcome.confidence,
            disclosedAt = disclosedAt,
            findings = found,
        )

        return current(actor, submissionId)
    }

    /**
     * Corrects the submission's expiry date to the one on the certificate.
     *
     * The one finding with a one-click answer, and the only place extraction
     * touches state that matters. It is an explicit act by a reviewer who has
     * both dates in front of them, and it records both values — because the
     * compliance engine runs on this date, and "why did this supplier's expiry
     * change" has to have an answer that is not "the model".
     */
    @Transactional
    fun applyExtractedExpiry(actor: Actor, submissionId: UUID): ExtractionView {
        actor.requireOps()

        val submission = submissions.findById(submissionId)
            ?: throw NotFoundException("That document is no longer available.")
        val stored = extractions.latestFor(submissionId)
            ?: throw InvalidRequestException("Nothing has been read off this certificate yet.")

        if (submission.documentTypeCode == W9) {
            throw InvalidRequestException("A W-9 carries no expiry date.")
        }

        val fields = objectMapper.readValue(stored.extractedJson, CoiFields::class.java)
        val extracted = fields.expiresOn
            ?: throw InvalidRequestException("The model could not read an expiry date off this certificate.")

        if (extracted == submission.expiresOn) {
            throw InvalidRequestException("The recorded expiry date already matches the certificate.")
        }

        submissions.updateExpiry(submissionId, extracted)

        journal.recordExpiryCorrection(
            actor = actor,
            submissionId = submissionId,
            supplierId = submission.supplierId,
            before = submission.expiresOn,
            after = extracted,
            model = stored.model,
        )

        return current(actor, submissionId)
    }

    /**
     * Whether the button is worth offering at all, and the single place the
     * policy lives. [extract] asks the same question before transmitting, so the
     * screen never offers something that would then be refused.
     */
    private fun offerable(documentTypeCode: String): Boolean = when (documentTypeCode) {
        CERTIFICATE_OF_INSURANCE -> true
        W9 -> properties.ai.w9ExtractionEnabled
        // Everything else, Restricted or not: banking details have no switch
        // because nobody asked for one, and the rest are read against their
        // acceptance criteria instead.
        else -> false
    }

    private fun refusal(documentTypeCode: String, documentTypeName: String, classification: String) = when {
        documentTypeCode == W9 -> AccessDeniedException(
            "Sending a W-9 to a model is switched off. It carries a taxpayer identification number, so " +
                "turning it on is Acme's decision to make with their compliance function — see the " +
                "decision memo. It is one setting once that decision is made.",
        )

        classification == RESTRICTED -> AccessDeniedException(
            "$documentTypeName is classified Restricted, so it is never sent to a third-party model. " +
                "Read it yourself.",
        )

        else -> InvalidRequestException(
            "Field extraction is built for certificates of insurance and W-9s. Other documents are read " +
                "against their acceptance criteria instead.",
        )
    }

    /**
     * The comparisons, dispatched by what was read.
     *
     * Both rule sets are pure and live in the domain layer: the model reads the
     * document, and Acme's rules decide whether what it read is a problem.
     */
    private fun findings(coi: CoiFields?, w9: W9Fields?, submission: SubmissionRecord): List<ExtractionFinding> {
        val supplier = suppliers.findById(submission.supplierId) ?: return emptyList()

        return when {
            coi != null -> CertificateFindings.evaluate(
                fields = ExtractedCertificate(
                    namedInsured = coi.namedInsured,
                    certificateHolder = coi.certificateHolder,
                    generalLiabilityAggregate = coi.generalLiabilityAggregate,
                    expiresOn = coi.expiresOn,
                    workersCompensationPresent = coi.workersCompensationPresent,
                    signed = coi.signed,
                ),
                typedExpiry = submission.expiresOn,
                requiredAggregate = requiredAggregate(submission.supplierId, submission.enrollmentId),
                supplierLegalName = supplier.legalName,
                today = evaluator.today(),
            )

            w9 != null -> TaxFormFindings.evaluate(
                fields = ExtractedTaxForm(
                    legalName = w9.legalName,
                    businessName = w9.businessName,
                    taxClassification = w9.taxClassification,
                    address = w9.address,
                    signed = w9.signed,
                ),
                supplierLegalName = supplier.legalName,
                supplierEntityType = supplier.entityType,
            )

            else -> emptyList()
        }
    }

    /**
     * The coverage minimum this certificate has to clear.
     *
     * A supplier-scope certificate satisfies every program at once, so the bar
     * is the highest of the programs it is being held to — clearing the strictest
     * clears the rest, and flagging against the laxest would pass a document that
     * fails somewhere else.
     */
    private fun requiredAggregate(supplierId: UUID, enrollmentId: UUID?): Long? {
        val relevant = enrollments.listForSupplier(supplierId)
            .filter { enrollmentId == null || it.id == enrollmentId }
            .map { it.programId }

        return catalog.requirementsForPrograms(relevant)
            .filter { it.documentType.code == CERTIFICATE_OF_INSURANCE }
            .mapNotNull { (it.constraints["generalLiabilityMinimum"] as? Number)?.toLong() }
            .maxOrNull()
    }

    private companion object {
        const val RESTRICTED = "RESTRICTED"
        const val CERTIFICATE_OF_INSURANCE = "CERTIFICATE_OF_INSURANCE"
        const val W9 = "W9"
    }
}

/**
 * The writes that bracket a model call, each in its own transaction.
 *
 * Same shape as the criteria journal, and for the same reason: the disclosure
 * commits before the transmission and survives a failed one.
 */
@Service
class ExtractionJournal(
    private val extractions: ExtractionRepository,
    private val recorder: ActivityRecorder,
) {

    @Transactional
    fun recordDisclosure(
        actor: Actor,
        submissionId: UUID,
        supplierId: UUID,
        documentTypeCode: String,
        classification: String,
        model: String,
        underOverride: Boolean,
    ) {
        recorder.record(
            action = AuditAction.DOCUMENT_DISCLOSED,
            subjectType = "DOCUMENT",
            subjectId = submissionId,
            actor = actor,
            supplierId = supplierId,
            after = buildMap {
                put("processor", "Anthropic")
                put("model", model)
                put("documentType", documentTypeCode)
                put("classification", classification)
                put("purpose", "FIELD_EXTRACTION")
                // Only present when it is true, so its presence in the log is
                // itself the signal: a Restricted document left the building
                // because Acme turned that on.
                if (underOverride) put("restrictedOverride", "acme.ai.w9-extraction-enabled")
            },
        )
    }

    @Transactional
    fun recordExtraction(
        submissionId: UUID,
        supplierId: UUID,
        model: String,
        extractedJson: String,
        flagsJson: String,
        confidence: Double?,
        disclosedAt: Instant,
        findings: List<ExtractionFinding>,
    ) {
        extractions.insert(submissionId, model, extractedJson, flagsJson, confidence, disclosedAt)

        recorder.record(
            action = AuditAction.DOCUMENT_FIELDS_EXTRACTED,
            subjectType = "DOCUMENT",
            subjectId = submissionId,
            actor = null,
            supplierId = supplierId,
            systemActorLabel = model,
            after = mapOf(
                "model" to model,
                "confidence" to confidence,
                // The findings rather than the fields: an auditor asking what
                // the model said about this certificate wants the disagreements,
                // and the full extraction is a row away.
                "findings" to findings.map { it.flag.name },
            ),
        )
    }

    @Transactional
    fun recordExpiryCorrection(
        actor: Actor,
        submissionId: UUID,
        supplierId: UUID,
        before: LocalDate?,
        after: LocalDate,
        model: String,
    ) {
        recorder.record(
            action = AuditAction.DOCUMENT_EXPIRY_CORRECTED,
            subjectType = "DOCUMENT",
            subjectId = submissionId,
            actor = actor,
            supplierId = supplierId,
            before = mapOf("expiresOn" to before?.toString()),
            after = mapOf(
                "expiresOn" to after.toString(),
                "source" to "CERTIFICATE",
                "readBy" to model,
            ),
        )
    }
}
