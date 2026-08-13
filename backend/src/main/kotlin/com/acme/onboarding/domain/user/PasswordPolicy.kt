package com.acme.onboarding.domain.user

/**
 * What counts as an acceptable password.
 *
 * Length carries almost all of the strength, so the rule is a floor on length
 * rather than the familiar character-class checklist. Composition rules push
 * people toward `Password1!` — predictable substitutions that a cracking
 * dictionary already knows — while costing every honest user a failed attempt.
 * NIST dropped them for exactly this reason.
 */
object PasswordPolicy {

    const val MINIMUM_LENGTH = 12

    /** The most-guessed strings, plus the ones this product invites by name. */
    private val obvious = setOf(
        "password", "passw0rd", "123456789012", "qwertyuiop12",
        "acmeonboarding", "supplieronboarding", "onboarding123",
    )

    sealed interface Result {
        data object Accepted : Result
        data class Rejected(val message: String) : Result
    }

    fun check(password: String): Result = when {
        password.length < MINIMUM_LENGTH -> Result.Rejected(
            "Use at least $MINIMUM_LENGTH characters. A short phrase you will remember is " +
                "stronger than a short password with symbols in it.",
        )

        password.isBlank() -> Result.Rejected("That password is only whitespace.")

        password.lowercase().filter { it.isLetterOrDigit() } in obvious -> Result.Rejected(
            "That password is one of the first an attacker tries. Choose something else.",
        )

        else -> Result.Accepted
    }
}
