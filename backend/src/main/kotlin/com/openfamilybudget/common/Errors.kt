package com.openfamilybudget.common

import org.springframework.http.HttpStatus
import org.springframework.http.ProblemDetail
import org.springframework.web.bind.MethodArgumentNotValidException
import org.springframework.web.bind.annotation.ExceptionHandler
import org.springframework.web.bind.annotation.RestControllerAdvice

class ApiException(val status: HttpStatus, val code: String, message: String) : RuntimeException(message)

fun notFound(what: String) = ApiException(HttpStatus.NOT_FOUND, "NOT_FOUND", "$what not found")
fun forbidden(msg: String = "Insufficient permissions") = ApiException(HttpStatus.FORBIDDEN, "FORBIDDEN", msg)
fun badRequest(code: String, msg: String) = ApiException(HttpStatus.UNPROCESSABLE_ENTITY, code, msg)

@RestControllerAdvice
class ApiExceptionHandler {
    @ExceptionHandler(ApiException::class)
    fun handle(e: ApiException): ProblemDetail =
        ProblemDetail.forStatusAndDetail(e.status, e.message).apply { setProperty("code", e.code) }

    @ExceptionHandler(MethodArgumentNotValidException::class)
    fun handle(e: MethodArgumentNotValidException): ProblemDetail =
        ProblemDetail.forStatusAndDetail(
            HttpStatus.BAD_REQUEST,
            e.bindingResult.fieldErrors.joinToString("; ") { "${it.field}: ${it.defaultMessage}" },
        ).apply { setProperty("code", "VALIDATION") }
}
