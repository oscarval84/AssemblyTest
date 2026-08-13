package com.acme.onboarding.application.notification

import com.acme.onboarding.adapter.persistence.EmailOutboxRepository
import com.acme.onboarding.adapter.persistence.EmailRecord
import com.acme.onboarding.adapter.persistence.SupplierRepository
import com.acme.onboarding.config.AcmeProperties
import com.acme.onboarding.domain.user.Actor
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.util.UUID

/** One notification, as ops reads it back. */
data class OutboxEntry(
    val id: UUID,
    val template: String,
    val recipientEmail: String,
    val recipientName: String?,
    val subject: String,
    val bodyText: String,
    val status: String,
    val attempts: Int,
    val lastError: String?,
    val createdAt: java.time.Instant,
    val sentAt: java.time.Instant?,
    val supplierId: UUID?,
    val supplierLegalName: String?,
)

data class OutboxView(
    /** What is draining the outbox in this environment, named rather than implied. */
    val transport: String,
    val entries: List<OutboxEntry>,
)

/**
 * The notification log, visible in the product.
 *
 * The brief asks that key events either send email or land somewhere credible
 * and inspectable, and this is the inspectable half. It is not a debugging
 * screen: "did the supplier ever actually get told, and what did it say" is a
 * question ops answers daily and an auditor asks eventually, and neither of them
 * has access to a mail server log.
 */
@Service
class OutboxQueryService(
    private val outbox: EmailOutboxRepository,
    private val suppliers: SupplierRepository,
    private val properties: AcmeProperties,
) {

    @Transactional(readOnly = true)
    fun list(actor: Actor, supplierId: UUID? = null): OutboxView {
        actor.requireOps()

        val records = supplierId?.let(outbox::listForSupplier) ?: outbox.listRecent(200)
        val names = records.mapNotNull { it.supplierId }.distinct()
            .associateWith { suppliers.findById(it)?.legalName }

        return OutboxView(
            transport = properties.mail.transport,
            entries = records.map { it.toEntry(names[it.supplierId]) },
        )
    }

    private fun EmailRecord.toEntry(supplierLegalName: String?) = OutboxEntry(
        id = id,
        template = template,
        recipientEmail = recipientEmail,
        recipientName = recipientName,
        subject = subject,
        bodyText = bodyText,
        status = status,
        attempts = attempts,
        lastError = lastError,
        createdAt = createdAt,
        sentAt = sentAt,
        supplierId = supplierId,
        supplierLegalName = supplierLegalName,
    )
}
