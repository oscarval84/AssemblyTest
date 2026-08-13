package com.acme.onboarding.application.support

/**
 * The failures the API surfaces, separated from Spring's exception hierarchy so
 * the application layer stays framework-free and testable without a web context.
 *
 * Every message here is read by a person — often a supplier who is not an
 * employee of Acme and cannot ask an engineer what went wrong. That is why they
 * name the next action rather than the internal cause.
 */
class NotFoundException(message: String) : RuntimeException(message)

class InvalidRequestException(message: String, val code: String? = null) : RuntimeException(message)

class ConflictException(message: String) : RuntimeException(message)

/**
 * Sign-in failed. Deliberately vague about *why* for the credential path, so the
 * endpoint cannot be used to discover which email addresses have accounts.
 */
class AuthenticationException(message: String) : RuntimeException(message)
