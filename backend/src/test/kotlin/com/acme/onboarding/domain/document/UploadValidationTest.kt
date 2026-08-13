package com.acme.onboarding.domain.document

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * The declared content type is a claim by the sender. These tests are about what
 * happens when that claim is false, which is the only case that matters.
 */
class UploadValidationTest {

    private val pdf = "%PDF-1.7\nrest of file".toByteArray(Charsets.US_ASCII)
    private val png = byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A, 0x00)
    private val jpeg = byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 0xFF.toByte(), 0xE0.toByte())

    @Test
    fun `accepts the three formats we collect, by their bytes`() {
        assertEquals(
            "application/pdf",
            assertIs<UploadValidation.Result.Accepted>(
                UploadValidation.validate("application/pdf", pdf.size.toLong(), pdf),
            ).contentType,
        )
        assertEquals(
            "image/png",
            assertIs<UploadValidation.Result.Accepted>(
                UploadValidation.validate("image/png", png.size.toLong(), png),
            ).contentType,
        )
        assertEquals(
            "image/jpeg",
            assertIs<UploadValidation.Result.Accepted>(
                UploadValidation.validate("image/jpeg", jpeg.size.toLong(), jpeg),
            ).contentType,
        )
    }

    @Test
    fun `trusts the bytes over the declared type`() {
        // The exact attack the magic-byte check exists for: an executable, or a
        // script, announced as a PDF because the sender controls the header.
        val executable = byteArrayOf(0x4D, 0x5A, 0x90.toByte(), 0x00)

        val result = assertIs<UploadValidation.Result.Rejected>(
            UploadValidation.validate("application/pdf", executable.size.toLong(), executable),
        )
        assertEquals(UploadValidation.Result.Code.UNSUPPORTED_TYPE, result.code)
    }

    @Test
    fun `flags a file whose declared format contradicts its contents`() {
        val result = assertIs<UploadValidation.Result.Rejected>(
            UploadValidation.validate("image/png", pdf.size.toLong(), pdf),
        )
        assertEquals(UploadValidation.Result.Code.CONTENT_MISMATCH, result.code)
    }

    @Test
    fun `a missing content type is not fatal when the bytes are recognisable`() {
        // Some scanners and older browsers send nothing at all. The file is
        // still identifiable, and refusing it would be a dead end for the
        // two-person agencies this product is partly built for.
        assertIs<UploadValidation.Result.Accepted>(
            UploadValidation.validate(null, pdf.size.toLong(), pdf),
        )
    }

    @Test
    fun `rejects an empty file before looking at anything else`() {
        val result = assertIs<UploadValidation.Result.Rejected>(
            UploadValidation.validate("application/pdf", 0, ByteArray(0)),
        )
        assertEquals(UploadValidation.Result.Code.EMPTY, result.code)
    }

    @Test
    fun `the size limit is 10 MB, and saying so is part of the message`() {
        assertIs<UploadValidation.Result.Accepted>(
            UploadValidation.validate("application/pdf", UploadValidation.MAX_SIZE_BYTES, pdf),
        )

        val result = assertIs<UploadValidation.Result.Rejected>(
            UploadValidation.validate("application/pdf", UploadValidation.MAX_SIZE_BYTES + 1, pdf),
        )
        assertEquals(UploadValidation.Result.Code.TOO_LARGE, result.code)
        assertTrue(result.message.contains("10 MB"), result.message)
        // Every rejection names the next action, or the supplier is stuck.
        assertTrue(result.message.contains("rescan", ignoreCase = true), result.message)
    }
}
