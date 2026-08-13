package com.acme.onboarding.application.support

import java.security.MessageDigest
import java.security.SecureRandom
import java.util.Base64

/**
 * Opaque credentials: session tokens, invitation links, password-reset links.
 *
 * Only the SHA-256 of a token is ever stored. SHA-256 rather than BCrypt is the
 * right call here and the reasoning is worth stating, because the usual advice
 * says the opposite: BCrypt is slow *by design* to make guessing a low-entropy
 * human password expensive. These tokens carry 256 bits of entropy from a CSPRNG,
 * so there is nothing to guess — and they are verified on every single request,
 * where a deliberately slow hash would be a self-inflicted denial of service.
 */
object Tokens {

    private val random = SecureRandom()
    private val encoder: Base64.Encoder = Base64.getUrlEncoder().withoutPadding()

    fun generate(): String {
        val bytes = ByteArray(32)
        random.nextBytes(bytes)
        return encoder.encodeToString(bytes)
    }

    fun hash(token: String): String =
        MessageDigest.getInstance("SHA-256")
            .digest(token.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it) }
}
