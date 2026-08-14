package com.acme.onboarding.config

import org.springframework.boot.context.properties.ConfigurationProperties
import java.time.Duration
import java.time.ZoneId

/**
 * Everything under `acme.*` in application.yml, bound once and typed.
 *
 * The business time zone is the load-bearing one: every expiry decision resolves
 * "today" in it, and a job firing at 02:00 UTC must not expire a certificate a
 * day early for an ops team in New York.
 */
@ConfigurationProperties(prefix = "acme")
data class AcmeProperties(
    val businessTimeZone: ZoneId,
    val compliance: Compliance,
    val session: Session,
    val invitation: Invitation,
    val passwordReset: PasswordReset,
    val storage: Storage,
    val mail: Mail,
    val agreement: Agreement,
    val demo: Demo,
    val security: Security,
    val jobs: Jobs,
    val ai: Ai,
    val portalBaseUrl: String,
) {
    data class Compliance(val expiryWarningDays: Long)

    data class Session(
        val ttl: Duration,
        val cookieName: String,
        /**
         * False only for local HTTP development. Every deployed environment is
         * behind Firebase Hosting on HTTPS and sets this true, the same way it
         * overrides the database credentials.
         */
        val cookieSecure: Boolean,
    )

    data class Invitation(val ttl: Duration)

    data class PasswordReset(val ttl: Duration)

    data class Storage(
        val provider: String,
        val bucket: String,
        val localPath: String,
        val signedUrlTtl: Duration,
    )

    data class Mail(
        val transport: String,
        val fromAddress: String,
        val fromName: String,
    )

    /** The signed agreement's text is versioned so "what did they agree to" stays answerable. */
    data class Agreement(val templateVersion: String)

    data class Demo(val seedOnStartup: Boolean)

    /**
     * Scheduled work. In GCP, Cloud Scheduler calls these endpoints with an OIDC
     * token and Cloud Run verifies it before the request reaches the app; the
     * shared secret below is the local and defence-in-depth equivalent.
     */
    data class Jobs(val token: String)

    /**
     * The model behind criteria prefill.
     *
     * [apiKey] is blank in every environment that has not enabled it, and that
     * is the switch: no key, no adapter, and the checklist is filled in by a
     * person — which is the fallback the design requires anyway.
     */
    data class Ai(
        val apiKey: String,
        val model: String,
    )

    data class Security(
        val fieldEncryptionKey: String,
        /** Hex-encoded, as `Encryptors.stronger` expects. */
        val fieldEncryptionSalt: String,
    )
}
