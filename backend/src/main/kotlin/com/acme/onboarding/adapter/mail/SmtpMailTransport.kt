package com.acme.onboarding.adapter.mail

import com.acme.onboarding.adapter.persistence.EmailRecord
import com.acme.onboarding.application.notification.MailTransport
import com.acme.onboarding.config.AcmeProperties
import jakarta.mail.internet.InternetAddress
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.mail.javamail.JavaMailSender
import org.springframework.mail.javamail.MimeMessageHelper
import org.springframework.stereotype.Component

/**
 * Delivery over SMTP: the second implementation of [MailTransport], and the only
 * thing standing between the outbox and a supplier's inbox.
 *
 * **It registers only when a mail host is configured.** That is deliberate, and
 * it is what keeps the demo posture honest: with no host there is no transport
 * named `smtp`, the drain reports delivery as switched off, and `/ops/outbox`
 * says so rather than showing messages marked sent that never left. Turning
 * delivery on is two environment variables and a credential — see
 * `docs/local-development.md` § Sending email for real.
 *
 * SMTP rather than a vendor HTTP API on purpose. Acme already has a mail domain
 * and, as an MSP whose clients audit them, they are likelier to want messages
 * leaving through their own infrastructure with their own SPF and DKIM than to
 * add a processor to the list. Any provider — SES, Postmark, Resend, an internal
 * relay — speaks SMTP, so this one class covers all of them and the choice stays
 * configuration rather than code. A vendor API would be a third implementation
 * of the same port on the day someone wants per-message delivery webhooks.
 */
@Component
@ConditionalOnProperty(prefix = "spring.mail", name = ["host"])
class SmtpMailTransport(
    private val mailSender: JavaMailSender,
    private val properties: AcmeProperties,
) : MailTransport {

    override val name = "smtp"

    /**
     * Throwing is the contract: the drain catches it, records the reason on the
     * row and leaves the message visible at `/ops/outbox` with its attempt
     * count. Nothing here retries — a message that fails four times should be
     * seen by a person, not hammered at a mail server.
     */
    override fun send(message: EmailRecord) {
        val mime = mailSender.createMimeMessage()
        val helper = MimeMessageHelper(mime, false, Charsets.UTF_8.name())

        helper.setFrom(InternetAddress(properties.mail.fromAddress, properties.mail.fromName))
        helper.setTo(InternetAddress(message.recipientEmail, message.recipientName))
        helper.setSubject(message.subject)

        // Plain text, which is what every template is. The bodies are written to
        // be read as sentences rather than laid out, and a supplier on a phone
        // in a warehouse office gets the same message as everyone else.
        helper.setText(message.bodyText, false)

        mailSender.send(mime)
    }
}
