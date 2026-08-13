package com.acme.onboarding.application.support

import org.springframework.security.crypto.password.PasswordEncoder

/**
 * `PasswordEncoder.encode` is declared as possibly returning null so that
 * implementations delegating to an external key service can express failure.
 * BCrypt never does, and a null hash silently stored would be an account nobody
 * can sign in to — so it fails loudly here instead.
 */
fun PasswordEncoder.hash(rawPassword: String): String =
    checkNotNull(encode(rawPassword)) { "Password encoder returned no hash" }
