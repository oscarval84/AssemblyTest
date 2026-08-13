package com.acme.onboarding.adapter.pdf

import com.acme.onboarding.application.document.ExecutedAgreement
import com.acme.onboarding.application.document.ExecutedAgreementRenderer
import com.acme.onboarding.application.document.SimpleDocumentRenderer
import org.apache.pdfbox.pdmodel.PDDocument
import org.apache.pdfbox.pdmodel.PDPage
import org.apache.pdfbox.pdmodel.PDPageContentStream
import org.apache.pdfbox.pdmodel.common.PDRectangle
import org.apache.pdfbox.pdmodel.font.PDType1Font
import org.apache.pdfbox.pdmodel.font.Standard14Fonts
import org.springframework.stereotype.Component
import java.io.ByteArrayOutputStream
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/**
 * Renders the executed agreement as a PDF.
 *
 * Deliberately plain: the value of this artifact is that it is immutable and
 * complete, not that it is designed. It carries the agreement text as signed,
 * the signature block, and the hash of the template — so "which text did they
 * agree to" has an answer that does not depend on this system still existing in
 * its current form.
 */
@Component
class PdfBoxAgreementRenderer : ExecutedAgreementRenderer, SimpleDocumentRenderer {

    override fun render(agreement: ExecutedAgreement): ByteArray = layout(
        buildList {
            addAll(agreement.body.lines().flatMap { wrap(it) })
            add("")
            add(RULE)
            add("")
            add("SIGNATURE")
            add("")
            addAll(signatureBlock(agreement).flatMap { wrap(it) })
        },
    )

    override fun render(title: String, lines: List<String>): ByteArray = layout(
        buildList {
            add(title.uppercase())
            add(RULE)
            add("")
            addAll(lines.flatMap { wrap(it) })
        },
    )

    private fun layout(lines: List<String>): ByteArray {
        PDDocument().use { document ->
            var cursor = 0
            while (cursor < lines.size) {
                val page = PDPage(PDRectangle.LETTER)
                document.addPage(page)

                PDPageContentStream(document, page).use { content ->
                    content.beginText()
                    content.setFont(FONT, FONT_SIZE)
                    content.setLeading(LEADING)
                    content.newLineAtOffset(MARGIN, PDRectangle.LETTER.height - MARGIN)

                    var drawn = 0
                    while (cursor < lines.size && drawn < LINES_PER_PAGE) {
                        content.showText(sanitise(lines[cursor]))
                        content.newLine()
                        cursor++
                        drawn++
                    }
                    content.endText()
                }
            }

            return ByteArrayOutputStream().use { out ->
                document.save(out)
                out.toByteArray()
            }
        }
    }

    private fun signatureBlock(agreement: ExecutedAgreement): List<String> {
        val signedAt = TIMESTAMP.format(agreement.signedAt.atZone(ZoneId.of("UTC")))
        return buildList {
            add("Signed by (typed): ${agreement.typedName}")
            add("Signer: ${agreement.signerName} <${agreement.signerEmail}>")
            add("On behalf of: ${agreement.companyLegalName}")
            agreement.programName?.let { add("Program: $it") }
            add("Signed at: $signedAt")
            agreement.signerIp?.let { add("Originating address: $it") }
            agreement.signerUserAgent?.let { add("Signer's browser: $it") }
            add("Template version: ${agreement.templateVersion}")
            add("Template SHA-256: ${agreement.templateSha256}")
            add("")
            add(
                "This signature was captured electronically through Acme's supplier onboarding " +
                    "platform. The details above are recorded in an append-only audit log.",
            )
        }
    }

    /** The standard-14 fonts are WinAnsi; anything outside it would throw at draw time. */
    private fun sanitise(line: String): String = line
        .replace('—', '-')
        .replace('–', '-')
        .replace('’', '\'')
        .replace('“', '"')
        .replace('”', '"')
        .filter { it.code in 32..255 }

    private fun wrap(line: String): List<String> {
        if (line.length <= MAX_CHARS) return listOf(line)
        val wrapped = mutableListOf<String>()
        var current = StringBuilder()
        line.split(' ').forEach { word ->
            if (current.isEmpty()) {
                current.append(word)
            } else if (current.length + 1 + word.length <= MAX_CHARS) {
                current.append(' ').append(word)
            } else {
                wrapped.add(current.toString())
                current = StringBuilder(word)
            }
        }
        if (current.isNotEmpty()) wrapped.add(current.toString())
        return wrapped
    }

    private companion object {
        val FONT = PDType1Font(Standard14Fonts.FontName.COURIER)
        const val FONT_SIZE = 9f
        const val LEADING = 12f
        const val MARGIN = 54f
        const val MAX_CHARS = 88
        const val LINES_PER_PAGE = 58
        const val RULE = "--------------------------------------------------------------------------"
        val TIMESTAMP: DateTimeFormatter = DateTimeFormatter.ofPattern("d MMMM yyyy 'at' HH:mm:ss 'UTC'")
    }
}
