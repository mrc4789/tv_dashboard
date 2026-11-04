package com.example.tvdashboard.data

import java.net.URI
import java.net.URISyntaxException

/** Kiểm tra URL dashboard: chỉ nhận http/https và phải có host. */
object UrlValidator {

    enum class Error { EMPTY, BAD_SCHEME, BAD_HOST, MALFORMED }

    sealed interface Result {
        data class Valid(val url: String) : Result
        data class Invalid(val error: Error) : Result
    }

    fun validate(input: String?): Result {
        val trimmed = input?.trim().orEmpty()
        if (trimmed.isEmpty()) return Result.Invalid(Error.EMPTY)

        val uri = try {
            URI(trimmed)
        } catch (e: URISyntaxException) {
            return Result.Invalid(Error.MALFORMED)
        }

        val scheme = uri.scheme?.lowercase()
        if (scheme != "http" && scheme != "https") return Result.Invalid(Error.BAD_SCHEME)
        if (uri.host.isNullOrBlank()) return Result.Invalid(Error.BAD_HOST)

        return Result.Valid(trimmed)
    }
}
