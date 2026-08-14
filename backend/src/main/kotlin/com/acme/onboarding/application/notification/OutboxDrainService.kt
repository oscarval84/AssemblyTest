package com.acme.onboarding.application.notification

import com.acme.onboarding.adapter.persistence.EmailOutboxRepository
import com.acme.onboarding.adapter.persistence.EmailRecord
import com.acme.onboarding.config.AcmeProperties
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Component
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.Clock
import java.time.Instant

/**
 * How a queued message actually leaves the building.
 *
 * A port, because the demo posture and a production posture differ only in this
 * one implementation: the outbox row, the audit trail and the ops-visible log
 * are written identically either way.
 */
interface MailTransport {
    /** The value of `acme.mail.transport` this implementation answers to. */
    val name: String

    /** Throwing marks the message failed and records why; returning marks it sent. */
    fun send(message: EmailRecord)
}

/**
 * The demo transport: it delivers nothing, and says so.
 *
 * Marking messages `SENT` without sending them would make the outbox screen lie
 * about the one thing it exists to answer. Leaving them `PENDING` is the honest
 * state — the message is written, durable and inspectable, and delivery is
 * switched off. The screen says exactly that.
 */
@Component
class OutboxOnlyTransport : MailTransport {
    override val name = "outbox-only"

    override fun send(message: EmailRecord) {
        throw UnsupportedOperationException(
            "Delivery is switched off in this environment (acme.mail.transport=outbox-only)",
        )
    }
}

/**
 * Which transport is in force, resolved once.
 *
 * Two screens ask the same question and must not answer it differently: the
 * drain asks "can I deliver", and `/ops/outbox` tells ops whether anything is
 * actually leaving. A configured name is not enough on its own — `smtp` with no
 * mail host registers no transport at all, and reporting delivery as on because
 * a string says so is exactly the lie the outbox exists to prevent.
 */
@Component
class MailDelivery(
    private val transports: List<MailTransport>,
    private val properties: AcmeProperties,
) {
    val configuredName: String get() = properties.mail.transport

    /** Null when nothing can deliver: no such transport, or the demo one. */
    fun active(): MailTransport? =
        transports.firstOrNull { it.name == configuredName }?.takeUnless { it is OutboxOnlyTransport }

    val enabled: Boolean get() = active() != null
}

data class DrainResult(
    val transport: String,
    val attempted: Int,
    val sent: Int,
    val failed: Int,
    /** True when nothing was attempted because no transport can deliver. */
    val deliveryDisabled: Boolean,
)

/**
 * Drains the outbox, one message at a time.
 *
 * Each message is committed on its own so a single bad recipient cannot roll
 * back the delivery of everything queued behind it, and every attempt increments
 * the row's counter — a message that keeps failing is visible at `/ops/outbox`
 * with the reason, rather than disappearing into a retry loop.
 */
@Service
class OutboxDrainService(
    private val outbox: EmailOutboxRepository,
    private val delivery: MailDelivery,
    private val clock: Clock,
) {

    private val log = LoggerFactory.getLogger(javaClass)

    fun drain(batchSize: Int = 50): DrainResult {
        val configured = delivery.configuredName
        val transport = delivery.active()

        if (transport == null) {
            val pending = outbox.listPending(batchSize).size
            return DrainResult(configured, attempted = pending, sent = 0, failed = 0, deliveryDisabled = true)
        }

        var sent = 0
        var failed = 0
        val batch = outbox.listPending(batchSize)

        batch.forEach { message ->
            try {
                transport.send(message)
                markSent(message)
                sent++
            } catch (error: Exception) {
                log.warn("Delivery failed for {} to {}", message.template, message.recipientEmail, error)
                markFailed(message, error.message ?: error.javaClass.simpleName)
                failed++
            }
        }

        return DrainResult(configured, batch.size, sent, failed, deliveryDisabled = false)
    }

    @Transactional
    fun markSent(message: EmailRecord) = outbox.markSent(message.id, Instant.now(clock))

    @Transactional
    fun markFailed(message: EmailRecord, reason: String) = outbox.markFailed(message.id, reason)
}
