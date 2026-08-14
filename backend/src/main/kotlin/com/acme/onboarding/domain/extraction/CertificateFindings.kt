package com.acme.onboarding.domain.extraction

import java.time.LocalDate

/**
 * What is wrong with a certificate, as a reviewer would put it.
 *
 * Deliberately a small closed set rather than free text: each of these is a
 * comparison somebody would otherwise do by eye between the document, the
 * program's requirements, and the supplier's own record.
 */
enum class CertificateFlag {
    /** The date the supplier typed is not the date on the certificate. */
    EXPIRY_MISMATCH,

    /** The certificate is already expired, or expires before anyone could act. */
    EXPIRED_ON_ARRIVAL,

    /** Aggregate coverage is below what this program requires. */
    COVERAGE_BELOW_MINIMUM,

    /** The named insured is not the company Acme is onboarding. */
    NAME_MISMATCH,

    /** Acme is not named as the certificate holder. */
    HOLDER_NOT_ACME,

    WORKERS_COMPENSATION_MISSING,
    NOT_SIGNED,
}

/** One finding, with the numbers a reviewer needs to act on it. */
data class CertificateFinding(
    val flag: CertificateFlag,
    /** Written for a person, and quotable to a supplier. */
    val detail: String,
)

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
    ): List<CertificateFinding> = buildList {
        // The comparison that matters most. The supplier types the expiry date
        // at upload and the whole compliance engine runs on it; if the document
        // says something else, one of the two is wrong and the wrong one might
        // be the one being trusted. This is the check that would have caught the
        // certificate that lapsed while the system thought it was current.
        if (fields.expiresOn != null && typedExpiry != null && fields.expiresOn != typedExpiry) {
            add(
                CertificateFinding(
                    CertificateFlag.EXPIRY_MISMATCH,
                    "The certificate expires ${fields.expiresOn}, and $typedExpiry was entered on upload.",
                ),
            )
        }

        fields.expiresOn?.let { expiry ->
            if (!expiry.isAfter(today)) {
                add(
                    CertificateFinding(
                        CertificateFlag.EXPIRED_ON_ARRIVAL,
                        "The certificate expired on $expiry, before it was reviewed.",
                    ),
                )
            } else if (expiry.isBefore(today.plusDays(TOO_SOON_DAYS))) {
                add(
                    CertificateFinding(
                        CertificateFlag.EXPIRED_ON_ARRIVAL,
                        "The certificate expires on $expiry, inside a week. Ask for the renewal now.",
                    ),
                )
            }
        }

        if (requiredAggregate != null && fields.generalLiabilityAggregate != null &&
            fields.generalLiabilityAggregate < requiredAggregate
        ) {
            add(
                CertificateFinding(
                    CertificateFlag.COVERAGE_BELOW_MINIMUM,
                    "The general liability aggregate shows ${money(fields.generalLiabilityAggregate)}; " +
                        "this program requires ${money(requiredAggregate)}.",
                ),
            )
        }

        if (fields.namedInsured != null && !namesMatch(fields.namedInsured, supplierLegalName)) {
            add(
                CertificateFinding(
                    CertificateFlag.NAME_MISMATCH,
                    "The certificate insures \"${fields.namedInsured}\"; the supplier record says " +
                        "\"$supplierLegalName\".",
                ),
            )
        }

        if (fields.certificateHolder != null && !mentionsAcme(fields.certificateHolder)) {
            add(
                CertificateFinding(
                    CertificateFlag.HOLDER_NOT_ACME,
                    "The certificate holder reads \"${fields.certificateHolder}\", which does not name Acme.",
                ),
            )
        }

        if (fields.workersCompensationPresent == false) {
            add(
                CertificateFinding(
                    CertificateFlag.WORKERS_COMPENSATION_MISSING,
                    "No workers' compensation coverage is shown.",
                ),
            )
        }

        if (fields.signed == false) {
            add(
                CertificateFinding(
                    CertificateFlag.NOT_SIGNED,
                    "The certificate carries no authorised representative's signature.",
                ),
            )
        }
    }

    /**
     * Whether two company names are the same company.
     *
     * Compared loosely on purpose. "Northwind Staffing Partners" and "Northwind
     * Staffing Partners, LLC" are one company, and an insurer writes whichever
     * one is on the policy. Flagging that pair would train a reviewer to ignore
     * the flag, which costs more than the mismatch it would catch.
     */
    private fun namesMatch(one: String, other: String): Boolean = normalise(one) == normalise(other)

    private fun mentionsAcme(holder: String): Boolean = normalise(holder).contains("acme")

    private val SUFFIXES = setOf("llc", "inc", "incorporated", "co", "corp", "corporation", "ltd", "lp", "llp")

    private fun normalise(name: String): String = name
        .lowercase()
        .map { if (it.isLetterOrDigit() || it.isWhitespace()) it else ' ' }
        .joinToString("")
        .split(' ')
        .filter { it.isNotBlank() && it !in SUFFIXES }
        .joinToString(" ")

    private fun money(amount: Long): String = "USD " + "%,d".format(amount)
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
