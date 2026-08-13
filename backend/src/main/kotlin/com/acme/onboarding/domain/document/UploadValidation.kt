package com.acme.onboarding.domain.document

/**
 * What a supplier is allowed to upload, decided from the bytes rather than from
 * what the browser claimed.
 *
 * A declared `Content-Type` is a hint supplied by the client, so trusting it
 * means the only thing stopping an executable from being stored under
 * `application/pdf` is the client's honesty. The first bytes of the file are the
 * one part of an upload the sender cannot lie about without changing the file.
 */
object UploadValidation {

    const val MAX_SIZE_BYTES: Long = 10L * 1024 * 1024

    private val signatures: Map<String, List<ByteArray>> = mapOf(
        "application/pdf" to listOf("%PDF".toByteArray(Charsets.US_ASCII)),
        "image/png" to listOf(byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A)),
        // JPEG variants (JFIF, Exif, raw) all share the SOI marker plus a marker byte.
        "image/jpeg" to listOf(byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 0xFF.toByte())),
    )

    val acceptedContentTypes: Set<String> = signatures.keys

    sealed interface Result {
        /** [contentType] is the type proven by the bytes, not the declared one. */
        data class Accepted(val contentType: String) : Result

        /**
          * [message] is written for the supplier, not for a log. Every rejection
          * names the next action: a two-person agency told only "invalid file"
          * has nowhere to go, and that is the dead end this product is replacing.
          */
        data class Rejected(val code: Code, val message: String) : Result

        enum class Code { EMPTY, TOO_LARGE, UNSUPPORTED_TYPE, CONTENT_MISMATCH }
    }

    fun validate(declaredContentType: String?, sizeBytes: Long, head: ByteArray): Result {
        if (sizeBytes <= 0) {
            return Result.Rejected(
                Result.Code.EMPTY,
                "That file is empty. Check that it saved correctly, then upload it again.",
            )
        }

        if (sizeBytes > MAX_SIZE_BYTES) {
            return Result.Rejected(
                Result.Code.TOO_LARGE,
                "That file is ${humanSize(sizeBytes)}, and the limit is 10 MB. " +
                    "Most scanners can save a smaller file at 200 dpi — rescan and try again.",
            )
        }

        val detected = signatures.entries
            .firstOrNull { (_, magics) -> magics.any { head.startsWith(it) } }
            ?.key

        if (detected == null) {
            return Result.Rejected(
                Result.Code.UNSUPPORTED_TYPE,
                "We accept PDF, PNG and JPEG files. If you have a Word document or a photo in " +
                    "another format, save or export it as a PDF and upload that.",
            )
        }

        val declared = declaredContentType?.substringBefore(';')?.trim()?.lowercase()
        if (declared != null && declared in acceptedContentTypes && declared != detected) {
            return Result.Rejected(
                Result.Code.CONTENT_MISMATCH,
                "This file is named as one format but its contents are another " +
                    "(${label(declared)} versus ${label(detected)}). Re-export it and upload it again.",
            )
        }

        return Result.Accepted(detected)
    }

    private fun ByteArray.startsWith(prefix: ByteArray): Boolean =
        size >= prefix.size && prefix.indices.all { this[it] == prefix[it] }

    private fun label(contentType: String): String = when (contentType) {
        "application/pdf" -> "PDF"
        "image/png" -> "PNG"
        "image/jpeg" -> "JPEG"
        else -> contentType
    }

    private fun humanSize(bytes: Long): String {
        val mb = bytes.toDouble() / (1024 * 1024)
        return if (mb >= 1) "%.1f MB".format(mb) else "%d KB".format(bytes / 1024)
    }
}
