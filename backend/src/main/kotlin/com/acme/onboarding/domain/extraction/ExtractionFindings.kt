package com.acme.onboarding.domain.extraction

/**
 * What is wrong with a document, as a reviewer would put it.
 *
 * Deliberately a small closed set rather than free text: each of these is a
 * comparison somebody would otherwise do by eye between the document, the
 * program's requirements, and the supplier's own record.
 */
enum class ExtractionFlag {
    /** The date the supplier typed is not the date on the certificate. */
    EXPIRY_MISMATCH,

    /** The certificate is already expired, or expires before anyone could act. */
    EXPIRED_ON_ARRIVAL,

    /** Aggregate coverage is below what this program requires. */
    COVERAGE_BELOW_MINIMUM,

    /** The document names a different company from the supplier record. */
    NAME_MISMATCH,

    /** Acme is not named as the certificate holder. */
    HOLDER_NOT_ACME,

    WORKERS_COMPENSATION_MISSING,
    NOT_SIGNED,

    /** A W-9 whose tax classification does not match the supplier's own profile. */
    ENTITY_TYPE_MISMATCH,
}

/** One finding, with the numbers a reviewer needs to act on it. */
data class ExtractionFinding(
    val flag: ExtractionFlag,
    /** Written for a person, and quotable to a supplier. */
    val detail: String,
)

/**
 * Comparing company names across a document and a record.
 *
 * Shared by both document types and deliberately loose. "Northwind Staffing
 * Partners" and "Northwind Staffing Partners, LLC" are one company, and an
 * insurer or a supplier writes whichever is at hand. Flagging that pair would be
 * technically correct and would train a reviewer to click past every name
 * mismatch — including the one that mattered.
 */
internal object CompanyNames {

    private val SUFFIXES = setOf("llc", "inc", "incorporated", "co", "corp", "corporation", "ltd", "lp", "llp")

    fun match(one: String, other: String): Boolean = normalise(one) == normalise(other)

    fun mentionsAcme(value: String): Boolean = normalise(value).contains("acme")

    private fun normalise(name: String): String = name
        .lowercase()
        .map { if (it.isLetterOrDigit() || it.isWhitespace()) it else ' ' }
        .joinToString("")
        .split(' ')
        .filter { it.isNotBlank() && it !in SUFFIXES }
        .joinToString(" ")
}

internal fun money(amount: Long): String = "USD " + "%,d".format(amount)
