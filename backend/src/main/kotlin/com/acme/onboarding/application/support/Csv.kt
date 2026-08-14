package com.acme.onboarding.application.support

/**
 * RFC 4180 CSV, written for a spreadsheet an auditor opens rather than for a
 * parser we control.
 *
 * Three decisions here are about that reader and not about the format:
 *
 *  - **CRLF line endings**, as the RFC specifies, because Excel on Windows is
 *    the likeliest destination and it is the strictest common consumer.
 *  - **A UTF-8 byte-order mark.** Excel ignores the charset in the response
 *    header and falls back to the system code page, which turns a supplier
 *    named `Hopital General` with its accents into mojibake in the one artifact
 *    that is supposed to be evidence. Every other mainstream reader tolerates
 *    the mark.
 *  - **Formula injection is neutralised.** Half of these values are free text
 *    somebody outside Acme typed — a company's legal name, an uploaded
 *    filename. A field beginning `=`, `+`, `-` or `@` is executed as a formula
 *    when the file is opened, so it is prefixed with an apostrophe, which
 *    spreadsheets strip on display and every other reader keeps visible. The
 *    alternative is shipping an audit artifact that runs code on open.
 */
object Csv {

    private const val LINE_SEPARATOR = "\r\n"

    /** Escaped rather than typed: the character itself is invisible in a diff. */
    private const val BYTE_ORDER_MARK = "\uFEFF"

    private val NEEDS_QUOTING = setOf(',', '"', '\n', '\r')
    private val FORMULA_STARTERS = setOf('=', '+', '-', '@', '\t', '\r')

    fun render(header: List<String>, rows: List<List<String?>>): String =
        buildString {
            append(BYTE_ORDER_MARK)
            append(line(header))
            rows.forEach { append(line(it)) }
        }

    private fun line(values: List<String?>): String =
        values.joinToString(separator = ",", postfix = LINE_SEPARATOR) { field(it) }

    private fun field(value: String?): String {
        // An absent value and an empty one are both an empty field: CSV has no
        // way to say "null", and inventing one would be read as the literal
        // string by whatever opens this.
        val raw = value.orEmpty()
        val guarded = if (raw.firstOrNull() in FORMULA_STARTERS) "'$raw" else raw

        return if (guarded.any { it in NEEDS_QUOTING }) {
            "\"" + guarded.replace("\"", "\"\"") + "\""
        } else {
            guarded
        }
    }
}
