package com.acme.onboarding.adapter.web

import com.acme.onboarding.application.support.AuthenticationException
import com.acme.onboarding.application.support.ConflictException
import com.acme.onboarding.application.support.InvalidRequestException
import com.acme.onboarding.application.support.NotFoundException
import com.acme.onboarding.domain.onboarding.IllegalStageTransition
import com.acme.onboarding.domain.user.AccessDeniedException
import org.slf4j.LoggerFactory
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.web.HttpRequestMethodNotSupportedException
import org.springframework.web.bind.MethodArgumentNotValidException
import org.springframework.web.bind.annotation.ExceptionHandler
import org.springframework.web.bind.annotation.RestControllerAdvice
import org.springframework.web.multipart.MaxUploadSizeExceededException
import org.springframework.web.servlet.resource.NoResourceFoundException

/**
 * One error shape for the whole API: `{ message, status, code? }`.
 *
 * The messages are deliberately written for the person who will read them —
 * often an external supplier with no support channel into Acme — so each names
 * what to do next. Nothing here leaks a stack trace or an internal identifier:
 * `server.error.include-message` is `never` for exactly that reason, and this
 * handler is what makes that setting survivable.
 */
@RestControllerAdvice
class ApiExceptionHandler {

    private val log = LoggerFactory.getLogger(javaClass)

    data class ApiError(val message: String, val status: Int, val code: String? = null)

    @ExceptionHandler(NotFoundException::class)
    fun notFound(e: NotFoundException) = respond(HttpStatus.NOT_FOUND, e.message)

    @ExceptionHandler(InvalidRequestException::class)
    fun invalid(e: InvalidRequestException) = respond(HttpStatus.UNPROCESSABLE_ENTITY, e.message, e.code)

    @ExceptionHandler(ConflictException::class)
    fun conflict(e: ConflictException) = respond(HttpStatus.CONFLICT, e.message)

    @ExceptionHandler(AuthenticationException::class)
    fun unauthenticated(e: AuthenticationException) = respond(HttpStatus.UNAUTHORIZED, e.message)

    @ExceptionHandler(AccessDeniedException::class)
    fun forbidden(e: AccessDeniedException) = respond(HttpStatus.FORBIDDEN, e.message)

    /**
     * A rejected stage transition is a bug or a concurrent edit, never something
     * to paper over — so it is logged as an error even though the caller gets an
     * ordinary conflict.
     */
    @ExceptionHandler(IllegalStageTransition::class)
    fun illegalTransition(e: IllegalStageTransition): ResponseEntity<ApiError> {
        log.error("Illegal onboarding transition from {} to {}", e.from, e.to, e)
        return respond(
            HttpStatus.CONFLICT,
            "This supplier has moved on since the page was loaded. Refresh and try again.",
        )
    }

    @ExceptionHandler(MethodArgumentNotValidException::class)
    fun validation(e: MethodArgumentNotValidException): ResponseEntity<ApiError> {
        val message = e.bindingResult.fieldErrors.firstOrNull()?.defaultMessage
            ?: "Some of those details are not valid."
        return respond(HttpStatus.UNPROCESSABLE_ENTITY, message)
    }

    @ExceptionHandler(MaxUploadSizeExceededException::class)
    fun tooLarge(e: MaxUploadSizeExceededException) = respond(
        HttpStatus.PAYLOAD_TOO_LARGE,
        "That file is larger than the 10 MB limit. Most scanners can save a smaller file at " +
            "200 dpi — rescan and try again.",
        "TOO_LARGE",
    )

    /**
     * A URL this API does not serve.
     *
     * Without this it reaches the catch-all below and comes back as a 500
     * apologising for a fault on Acme's side — which is wrong twice over: it
     * tells the caller Acme is broken when the request was, and it buries real
     * 500s in noise from typos and stale links. Spring raises
     * `NoResourceFoundException` here rather than anything named "404", which is
     * why it is easy to miss.
     */
    @ExceptionHandler(NoResourceFoundException::class)
    fun noSuchRoute(e: NoResourceFoundException) = respond(
        HttpStatus.NOT_FOUND,
        "That address does not exist on this system. Check the link, or start again from the home page.",
    )

    /** A known URL called with the wrong verb — the same reasoning as above. */
    @ExceptionHandler(HttpRequestMethodNotSupportedException::class)
    fun wrongMethod(e: HttpRequestMethodNotSupportedException) = respond(
        HttpStatus.METHOD_NOT_ALLOWED,
        "That address does not accept ${e.method} requests.",
    )

    @ExceptionHandler(Exception::class)
    fun unexpected(e: Exception): ResponseEntity<ApiError> {
        log.error("Unhandled failure", e)
        return respond(
            HttpStatus.INTERNAL_SERVER_ERROR,
            "Something went wrong on our side. Try again, and tell your Acme contact if it keeps happening.",
        )
    }

    private fun respond(status: HttpStatus, message: String?, code: String? = null) =
        ResponseEntity.status(status).body(
            ApiError(message ?: status.reasonPhrase, status.value(), code),
        )
}
