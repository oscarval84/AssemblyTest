package com.acme.onboarding.application.extraction

import com.acme.onboarding.adapter.persistence.CatalogRepository
import com.acme.onboarding.adapter.persistence.EnrollmentRepository
import com.acme.onboarding.adapter.persistence.ExtractionRepository
import com.acme.onboarding.adapter.persistence.SubmissionRepository
import com.acme.onboarding.adapter.persistence.SupplierRepository
import com.acme.onboarding.application.audit.ActivityRecorder
import com.acme.onboarding.application.audit.AuditAction
import com.acme.onboarding.application.document.DocumentStore
import com.acme.onboarding.application.support.InvalidRequestException
import com.acme.onboarding.application.support.NotFoundException
import com.acme.onboarding.domain.compliance.ComplianceEvaluator
import com.acme.onboarding.domain.extraction.CertificateFinding
import com.acme.onboarding.domain.extraction.CertificateFindings
import com.acme.onboarding.domain.extraction.CertificateFlag
import com.acme.onboarding.domain.extraction.ExtractedCertificate
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
    /** Null until somebody has run it; running it is always a deliberate act. */
    val fields: CoiFields? = null,
    val confidence: Double? = null,
    val findings: List<CertificateFinding> = emptyList(),
    /** The date on the submission today, for the reviewer to compare against. */
    val recordedExpiry: LocalDate? = null,
    val extractedAt: Instant? = null,
) {
    /**
     * True when the certificate and the submission disagree about expiry, which
     * is the one finding that has a one-click answer.
     */
    val expiryDisagrees: Boolean
        get() = findings.any { it.flag == CertificateFlag.EXPIRY_MISMATCH }
}

/**
 * Reading a certificate of insurance, and comparing it with what was claimed.
 *
 * The stretch goal, built last because the product is correct without it: the
 * supplier types the expiry date at upload and the compliance engine runs on
 * that. What was missing is that nobody checked it against the document, and an
 * expiry date wrong by two months is exactly the shape of the failure that let a
 * supplier work on a lapsed certificate twice.
 *
 * So this reads the certificate and *disagrees out loud*. It never writes the
 * date it checks — [applyExtractedExpiry] is a separate action a person takes,
 * with its own audit event. Replacing a mistake nobody checks with a mistake
 * nobody can see would not be an improvement.
 *
 * The classification gate is the same one criteria review enforces, for the same
 * reason: extraction transmits the document to a third party, so it runs on
 * Confidential and Internal documents only. A W-9 is Restricted and is refused
 * here, in code.
 */
@Service
class CoiExtractionService(
    private val submissions: SubmissionRepository,
    private val suppliers: SupplierRepository,
    private val enrollments: EnrollmentRepository,
    private val catalog: CatalogRepository,
    private val extractions: ExtractionRepository,
    private val store: DocumentStore,
    private val extractor: CoiExtractor,
    private val journal: ExtractionJournal,
    private val evaluator: ComplianceEvaluator,
    private val objectMapper: ObjectMapper,
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
        val fields = stored?.let { objectMapper.readValue(it.extractedJson, CoiFields::class.java) }

        return ExtractionView(
            submissionId = submissionId,
            available = extractor.available && offerable(submission.documentTypeCode, submission.classification),
            model = stored?.model ?: extractor.model.takeIf { extractor.available },
            fields = fields,
            confidence = stored?.confidence,
            findings = fields?.let { findings(it, submission.expiresOn, submission.supplierId, submission.enrollmentId) }
                ?: emptyList(),
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
                "No model is configured in this environment, so certificates are read by a person. " +
                    "Nothing is missing from the review — this only saves reading time.",
            )
        }

        val submission = submissions.findById(submissionId)
            ?: throw NotFoundException("That document is no longer available.")

        if (submission.classification == RESTRICTED) {
            throw AccessDeniedException(
                "${submission.documentTypeName} is classified Restricted, so it is never sent to a " +
                    "third-party model. Read it yourself.",
            )
        }
        if (submission.documentTypeCode != CERTIFICATE_OF_INSURANCE) {
            throw InvalidRequestException(
                "Field extraction is built for certificates of insurance. Other documents are read " +
                    "against their acceptance criteria instead.",
            )
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
        )
        val disclosedAt = Instant.now(clock)

        val outcome = try {
            extractor.extract(
                ExtractionRequest(
                    companyLegalName = supplier.legalName,
                    contentType = submission.contentType,
                    bytes = store.read(submission.storageKey),
                ),
            )
        } catch (error: Exception) {
            log.warn("Certificate extraction failed for submission {}", submission.id, error)
            throw InvalidRequestException(
                "The model could not be reached just now. Nothing has changed — read the certificate " +
                    "yourself, or try again.",
            )
        }

        val found = findings(outcome.fields, submission.expiresOn, submission.supplierId, submission.enrollmentId)

        journal.recordExtraction(
            submissionId = submission.id,
            supplierId = submission.supplierId,
            model = extractor.model,
            extractedJson = objectMapper.writeValueAsString(outcome.fields),
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
     * Whether the button is worth offering at all: a certificate, and a
     * classification that permits sending it. Both are checked again in
     * [extract] — this is what keeps the screen from offering something that
     * would be refused.
     */
    private fun offerable(documentTypeCode: String, classification: String): Boolean =
        documentTypeCode == CERTIFICATE_OF_INSURANCE && classification != RESTRICTED

    private fun findings(
        fields: CoiFields,
        recordedExpiry: LocalDate?,
        supplierId: UUID,
        enrollmentId: UUID?,
    ): List<CertificateFinding> {
        val supplier = suppliers.findById(supplierId) ?: return emptyList()

        return CertificateFindings.evaluate(
            fields = ExtractedCertificate(
                namedInsured = fields.namedInsured,
                certificateHolder = fields.certificateHolder,
                generalLiabilityAggregate = fields.generalLiabilityAggregate,
                expiresOn = fields.expiresOn,
                workersCompensationPresent = fields.workersCompensationPresent,
                signed = fields.signed,
            ),
            typedExpiry = recordedExpiry,
            requiredAggregate = requiredAggregate(supplierId, enrollmentId),
            supplierLegalName = supplier.legalName,
            today = evaluator.today(),
        )
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
    ) {
        recorder.record(
            action = AuditAction.DOCUMENT_DISCLOSED,
            subjectType = "DOCUMENT",
            subjectId = submissionId,
            actor = actor,
            supplierId = supplierId,
            after = mapOf(
                "processor" to "Anthropic",
                "model" to model,
                "documentType" to documentTypeCode,
                "classification" to classification,
                "purpose" to "FIELD_EXTRACTION",
            ),
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
        findings: List<CertificateFinding>,
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
