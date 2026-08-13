package com.acme.onboarding.adapter.web

import com.acme.onboarding.application.audit.RequestContext
import com.acme.onboarding.application.support.AuthenticationException
import com.acme.onboarding.domain.user.Actor
import jakarta.servlet.http.HttpServletRequest
import org.springframework.security.core.context.SecurityContextHolder
import org.springframework.stereotype.Component
import org.springframework.web.context.request.RequestContextHolder
import org.springframework.web.context.request.ServletRequestAttributes

/**
 * The caller, resolved from the security context that
 * [com.acme.onboarding.config.SessionAuthenticationFilter] populated.
 *
 * Controllers read it and hand it to the application layer, which is where the
 * authorization decisions live — a URL pattern cannot express "this supplier
 * user may only ever reach their own supplier".
 */
object CurrentActor {

    fun optional(): Actor? =
        SecurityContextHolder.getContext().authentication?.principal as? Actor

    fun require(): Actor =
        optional() ?: throw AuthenticationException("Sign in to continue.")
}

/**
 * Request metadata for the audit log.
 *
 * Returns nulls rather than placeholders when there is no request, so an event
 * written by a scheduled job is honestly recorded as having no origin instead of
 * inheriting whatever thread it ran on.
 */
@Component
class ServletRequestContext : RequestContext {

    override fun origin(): String? = current()?.let { request ->
        "${request.method} ${request.requestURI} from ${ip()}"
    }

    override fun ip(): String? = current()?.let { request ->
        // Cloud Run terminates TLS and forwards the client address; the first
        // entry is the original client, the rest are proxies.
        request.getHeader("X-Forwarded-For")?.substringBefore(',')?.trim()?.takeIf { it.isNotEmpty() }
            ?: request.remoteAddr
    }

    override fun userAgent(): String? = current()?.getHeader("User-Agent")?.take(500)

    private fun current(): HttpServletRequest? =
        (RequestContextHolder.getRequestAttributes() as? ServletRequestAttributes)?.request
}
