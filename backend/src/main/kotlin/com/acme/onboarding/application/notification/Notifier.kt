package com.acme.onboarding.application.notification

import com.acme.onboarding.adapter.persistence.EmailOutboxRepository
import com.acme.onboarding.config.AcmeProperties
import org.springframework.stereotype.Component
import org.springframework.transaction.annotation.Propagation
import org.springframework.transaction.annotation.Transactional
import java.util.UUID

/**
 * Queues notifications into the transactional outbox.
 *
 * `MANDATORY` for the same reason the audit recorder uses it: the row must be
 * written inside the transaction that caused it. An email announcing a rejection
 * that was rolled back is worse than no email at all — the supplier acts on
 * something that never happened.
 */
@Component
class Notifier(
    private val outbox: EmailOutboxRepository,
    private val properties: AcmeProperties,
) {

    @Transactional(propagation = Propagation.MANDATORY)
    fun supplierInvited(
        recipientEmail: String,
        recipientName: String?,
        supplierId: UUID,
        companyName: String,
        invitedByName: String,
        token: String,
    ): UUID = enqueue(
        template = "SUPPLIER_INVITED",
        recipientEmail = recipientEmail,
        recipientName = recipientName,
        supplierId = supplierId,
        subject = "Acme is ready to onboard $companyName",
        body = """
            Hello${recipientName?.let { " $it" } ?: ""},

            $invitedByName at Acme has invited $companyName to complete supplier onboarding.

            Set up your account and see what we need:
            ${link("invitation", token)}

            The onboarding checklist shows every document we need and why, and it tells you
            where each one stands once you have sent it. Nothing is a black box: if something
            is rejected you will see the reason and be able to replace it in one step.

            This link is single-use and expires in ${properties.invitation.ttl.toDays()} days.
            If it lapses, reply to this email and we will send a new one.
        """.trimIndent(),
    )

    @Transactional(propagation = Propagation.MANDATORY)
    fun staffInvited(
        recipientEmail: String,
        recipientName: String,
        roleLabel: String,
        invitedByName: String,
        token: String,
    ): UUID = enqueue(
        template = "STAFF_INVITED",
        recipientEmail = recipientEmail,
        recipientName = recipientName,
        supplierId = null,
        subject = "Your Acme supplier onboarding account",
        body = """
            Hello $recipientName,

            $invitedByName has created an account for you in Acme's supplier onboarding
            console with the role of $roleLabel.

            Set your password to get started:
            ${link("invitation", token)}

            This link is single-use and expires in ${properties.invitation.ttl.toDays()} days.
        """.trimIndent(),
    )

    @Transactional(propagation = Propagation.MANDATORY)
    fun passwordReset(
        recipientEmail: String,
        recipientName: String,
        token: String,
        issuedByStaff: Boolean,
    ): UUID {
        val opening = if (issuedByStaff) {
            "Someone at Acme started a password reset for your account at your request."
        } else {
            "We received a request to reset the password on your account."
        }

        return enqueue(
            template = "PASSWORD_RESET",
            recipientEmail = recipientEmail,
            recipientName = recipientName,
            supplierId = null,
            subject = "Reset your Acme onboarding password",
            body = """
            Hello $recipientName,

            $opening

            Choose a new password:
            ${link("reset-password", token)}

            This link expires in ${properties.passwordReset.ttl.toHours()} hour(s) and can be
            used once. Setting a new password signs you out everywhere else.

            If you did not expect this, you can ignore this email — your current password
            still works and nothing has changed.
            """.trimIndent(),
        )
    }

    @Transactional(propagation = Propagation.MANDATORY)
    fun documentRejected(
        recipientEmail: String,
        recipientName: String?,
        supplierId: UUID,
        documentName: String,
        reasonLabel: String,
        note: String?,
    ): UUID = enqueue(
        template = "DOCUMENT_REJECTED",
        recipientEmail = recipientEmail,
        recipientName = recipientName,
        supplierId = supplierId,
        subject = "Action needed: $documentName could not be accepted",
        body = """
            Hello${recipientName?.let { " $it" } ?: ""},

            We reviewed the $documentName you sent and cannot accept it yet.

            Reason: $reasonLabel${note?.let { "\n            Reviewer's note: $it" } ?: ""}

            Upload a replacement here:
            ${link("portal/documents", "")}

            Your other documents are unaffected — only this one needs attention.
        """.trimIndent(),
    )

    @Transactional(propagation = Propagation.MANDATORY)
    fun onboardingCompleted(
        recipientEmail: String,
        recipientName: String?,
        supplierId: UUID,
        companyName: String,
    ): UUID = enqueue(
        template = "ONBOARDING_COMPLETED",
        recipientEmail = recipientEmail,
        recipientName = recipientName,
        supplierId = supplierId,
        subject = "$companyName is approved and ready to work with Acme",
        body = """
            Hello${recipientName?.let { " $it" } ?: ""},

            Every document we needed from $companyName has been reviewed and approved.
            You are cleared to begin placements under your programs.

            One thing to keep an eye on: insurance certificates expire. We will remind you
            before yours does, and your portal always shows the current expiry date.

            ${link("portal", "")}
        """.trimIndent(),
    )

    private fun enqueue(
        template: String,
        recipientEmail: String,
        recipientName: String?,
        supplierId: UUID?,
        subject: String,
        body: String,
    ): UUID = outbox.enqueue(
        template = template,
        recipientEmail = recipientEmail,
        recipientName = recipientName,
        subject = subject,
        bodyText = body + signature(),
        supplierId = supplierId,
    )

    private fun signature(): String = "\n\n— ${properties.mail.fromName}\n${properties.mail.fromAddress}\n"

    private fun link(path: String, token: String): String {
        val base = properties.portalBaseUrl.trimEnd('/')
        return if (token.isBlank()) "$base/$path" else "$base/$path/$token"
    }
}
