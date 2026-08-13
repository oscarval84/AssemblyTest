package com.acme.onboarding.application.document

import java.time.Instant

/**
 * The agreement as it was executed, ready to be rendered into a file.
 *
 * The client said every previous attempt died at documents and signatures, so
 * the deliverable is not a record that someone clicked "I agree" — it is the
 * executed document. An auditor asking for a signed agreement wants a file, not
 * an assurance that one could be reconstructed from a database.
 */
data class ExecutedAgreement(
    val templateVersion: String,
    /** The agreement text, placeholders already resolved. */
    val body: String,
    val companyLegalName: String,
    val programName: String?,
    val signerName: String,
    val signerEmail: String,
    val typedName: String,
    val signedAt: Instant,
    val signerIp: String?,
    val signerUserAgent: String?,
    /** Proves which text was signed, after the template has moved on. */
    val templateSha256: String,
)

interface ExecutedAgreementRenderer {
    fun render(agreement: ExecutedAgreement): ByteArray
}

/**
 * Renders a plain document, used to give the seeded demo world real files.
 *
 * Generating them beats committing binary fixtures: the file can name the
 * supplier it belongs to, and nobody has to wonder whether a checked-in PDF
 * contains something it should not.
 */
interface SimpleDocumentRenderer {
    fun render(title: String, lines: List<String>): ByteArray
}
