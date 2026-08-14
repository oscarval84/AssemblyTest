package com.acme.onboarding.domain.extraction

import java.time.LocalDate

/**
 * Compares what a certificate says against what was expected of it.
 *
 * Pure, and in the domain layer, because these are Acme's rules rather than the
 * model's: the model reads the document, and this decides whether what it read
 * is a problem. That split is what keeps the findings stable when the model
 * changes, and what makes them testable without a network.
 *
 * **Nothing here decides anything.** A finding is shown to a reviewer beside the
 * document; no status moves on its own.
 */
object CertificateFindings {

    /** Below this, a renewal will not arrive before the certificate lapses. */
    private const val TOO_SOON_DAYS = 7L

    fun evaluate(
        fields: ExtractedCertificate,
        typedExpiry: LocalDate?,
        requiredAggregate: Long?,
        supplierLegalName: String,
        today: LocalDate,
    ): List<ExtractionFinding> = buildList {
        // The comparison that matters most. The supplier types the expiry date
        // at upload and the whole compliance engine runs on it; if the document
        // says something else, one of the two is wrong and the wrong one might
        // be the one being trusted. This is the check that would have caught the
        // certificate that lapsed while the system thought it was current.
        if (fields.expiresOn != null && typedExpiry != null && fields.expiresOn != typedExpiry) {
            add(
                ExtractionFinding(
                    ExtractionFlag.EXPIRY_MISMATCH,
                    "The certificate expires ${fields.expiresOn}, and $typedExpiry was entered on upload.",
                ),
            )
        }

        fields.expiresOn?.let { expiry ->
            if (!expiry.isAfter(today)) {
                add(
                    ExtractionFinding(
                        ExtractionFlag.EXPIRED_ON_ARRIVAL,
                        "The certificate expired on $expiry, before it was reviewed.",
                    ),
                )
            } else if (expiry.isBefore(today.plusDays(TOO_SOON_DAYS))) {
                add(
                    ExtractionFinding(
                        ExtractionFlag.EXPIRED_ON_ARRIVAL,
                        "The certificate expires on $expiry, inside a week. Ask for the renewal now.",
                    ),
                )
            }
        }

        if (requiredAggregate != null && fields.generalLiabilityAggregate != null &&
            fields.generalLiabilityAggregate < requiredAggregate
        ) {
            add(
                ExtractionFinding(
                    ExtractionFlag.COVERAGE_BELOW_MINIMUM,
                    "The general liability aggregate shows ${money(fields.generalLiabilityAggregate)}; " +
                        "this program requires ${money(requiredAggregate)}.",
                ),
            )
        }

        if (fields.namedInsured != null && !CompanyNames.match(fields.namedInsured, supplierLegalName)) {
            add(
                ExtractionFinding(
                    ExtractionFlag.NAME_MISMATCH,
                    "The certificate insures \"${fields.namedInsured}\"; the supplier record says " +
                        "\"$supplierLegalName\".",
                ),
            )
        }

        if (fields.certificateHolder != null && !CompanyNames.mentionsAcme(fields.certificateHolder)) {
            add(
                ExtractionFinding(
                    ExtractionFlag.HOLDER_NOT_ACME,
                    "The certificate holder reads \"${fields.certificateHolder}\", which does not name Acme.",
                ),
            )
        }

        if (fields.workersCompensationPresent == false) {
            add(
                ExtractionFinding(
                    ExtractionFlag.WORKERS_COMPENSATION_MISSING,
                    "No workers' compensation coverage is shown.",
                ),
            )
        }

        if (fields.signed == false) {
            add(
                ExtractionFinding(
                    ExtractionFlag.NOT_SIGNED,
                    "The certificate carries no authorised representative's signature.",
                ),
            )
        }
    }

}

/**
 * The fields as the domain sees them.
 *
 * A copy of the adapter's shape rather than a shared type, so the rules above
 * never import anything that knows about a model or an API.
 */
data class ExtractedCertificate(
    val namedInsured: String? = null,
    val certificateHolder: String? = null,
    val generalLiabilityAggregate: Long? = null,
    val expiresOn: LocalDate? = null,
    val workersCompensationPresent: Boolean? = null,
    val signed: Boolean? = null,
)
