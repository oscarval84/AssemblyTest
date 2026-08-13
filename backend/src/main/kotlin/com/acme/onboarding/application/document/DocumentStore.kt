package com.acme.onboarding.application.document

import java.net.URI
import java.time.Duration

/**
 * Where document bytes live.
 *
 * A port rather than a direct Cloud Storage call, for one reason that matters
 * beyond testability: local development must not require cloud credentials, and
 * a developer running the app without them should get a working upload, not a
 * stack trace.
 *
 * [signedUrl] returns null when the backing store cannot mint URLs. Callers
 * stream the bytes themselves in that case — the authorization and the audit
 * event have already happened either way, which is the part that must not depend
 * on which store is configured.
 */
interface DocumentStore {

    fun put(key: String, bytes: ByteArray, contentType: String)

    fun read(key: String): ByteArray

    fun exists(key: String): Boolean

    /** Erasure of content, independent of the record that the content existed. */
    fun delete(key: String)

    fun signedUrl(key: String, ttl: Duration): URI?
}
