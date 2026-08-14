package com.acme.onboarding.adapter.ai

import com.anthropic.models.messages.Base64ImageSource
import com.anthropic.models.messages.Base64PdfSource
import com.anthropic.models.messages.ContentBlockParam
import com.anthropic.models.messages.DocumentBlockParam
import com.anthropic.models.messages.ImageBlockParam
import com.anthropic.models.messages.TextBlockParam

/**
 * Turning an uploaded document into a content block, once.
 *
 * Both model-backed features send the same three file types the upload
 * validator accepts, and both send them as a document or an image rather than
 * as extracted text — a certificate of insurance is a layout, and flattening it
 * is how an aggregate limit gets read off the each-occurrence line.
 */
internal object AnthropicDocuments {

    /**
     * A PDF is a document block; a scan or a phone photograph is an image block.
     *
     * Suppliers send both — a national firm's broker emails a generated PDF, a
     * two-person agency photographs the certificate on their desk. Uploads are
     * restricted to PDF, PNG and JPEG by magic bytes before they ever reach
     * storage, so an unexpected content type here is a bug rather than input.
     */
    fun block(contentType: String, base64: String): ContentBlockParam = when (contentType) {
        "application/pdf" -> ContentBlockParam.ofDocument(
            DocumentBlockParam.builder()
                .source(Base64PdfSource.builder().data(base64).build())
                .build(),
        )

        else -> ContentBlockParam.ofImage(
            ImageBlockParam.builder()
                .source(
                    Base64ImageSource.builder()
                        .mediaType(imageMediaType(contentType))
                        .data(base64)
                        .build(),
                )
                .build(),
        )
    }

    fun text(value: String): ContentBlockParam =
        ContentBlockParam.ofText(TextBlockParam.builder().text(value).build())

    private fun imageMediaType(contentType: String): Base64ImageSource.MediaType = when (contentType) {
        "image/png" -> Base64ImageSource.MediaType.IMAGE_PNG
        else -> Base64ImageSource.MediaType.IMAGE_JPEG
    }
}
