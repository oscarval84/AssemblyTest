package com.acme.onboarding.config

import jakarta.servlet.http.HttpServletRequest
import jakarta.servlet.http.HttpServletResponse
import org.springframework.security.web.csrf.CookieCsrfTokenRepository
import org.springframework.security.web.csrf.CsrfToken
import org.springframework.security.web.csrf.CsrfTokenRepository

/**
 * A CSRF cookie that survives the request that reads it.
 *
 * The stock [CookieCsrfTokenRepository] is asked to clear its cookie whenever an
 * authentication is established, and this application establishes one on *every*
 * request — the session filter resolves the caller from the database each time
 * rather than from a servlet session. The result was an `XSRF-TOKEN` cookie that
 * every authenticated response deleted and the next response re-created, so a
 * mutating request that raced a page's parallel reads sent a token the browser
 * no longer held. It failed as an intermittent 403 that read like an
 * authorization bug and was not one.
 *
 * Refusing the clear is safe here because the token is not a credential. This is
 * the double-submit pattern: the server compares the header against the cookie
 * the same browser sent, and the protection comes from the fact that another
 * origin can cause the cookie to be *sent* but cannot *read* it to set the
 * header. Rotating that value adds nothing an attacker was going to defeat, and
 * losing it mid-session breaks every write.
 *
 * The session token is the credential, and that one is `HttpOnly` and is
 * genuinely revoked — on sign-out, on password change, and on deactivation.
 */
class StableCookieCsrfTokenRepository : CsrfTokenRepository {

    private val delegate = CookieCsrfTokenRepository.withHttpOnlyFalse()

    override fun generateToken(request: HttpServletRequest): CsrfToken =
        delegate.generateToken(request)

    override fun saveToken(token: CsrfToken?, request: HttpServletRequest, response: HttpServletResponse) {
        // A null token means "delete the cookie". That is the call this class
        // exists to ignore.
        if (token == null) return
        delegate.saveToken(token, request, response)
    }

    override fun loadToken(request: HttpServletRequest): CsrfToken? = delegate.loadToken(request)
}
