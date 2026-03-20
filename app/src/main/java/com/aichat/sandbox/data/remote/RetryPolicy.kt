package com.aichat.sandbox.data.remote

import android.util.Log
import kotlinx.coroutines.delay
import retrofit2.Response

data class RetryPolicy(
    val maxAttempts: Int = 3,
    val initialDelayMs: Long = 1000,
    val retryableStatusCodes: Set<Int> = setOf(429, 500, 502, 503, 529)
)

suspend fun <T> retryWithBackoff(
    policy: RetryPolicy = RetryPolicy(),
    onRetryAttempt: ((attempt: Int) -> Unit)? = null,
    block: suspend () -> Response<T>
): Response<T> {
    var lastResponse: Response<T>? = null

    repeat(policy.maxAttempts) { attempt ->
        val response = block()

        if (response.isSuccessful || response.code() !in policy.retryableStatusCodes) {
            return response
        }

        lastResponse = response

        if (attempt < policy.maxAttempts - 1) {
            val retryAfterMs = parseRetryAfter(response)
            val backoffMs = retryAfterMs
                ?: (policy.initialDelayMs * (1L shl attempt)) // exponential: 1s, 2s, 4s...

            Log.w(
                "RetryPolicy",
                "Request failed with ${response.code()}, retry ${attempt + 1}/${policy.maxAttempts} after ${backoffMs}ms"
            )
            onRetryAttempt?.invoke(attempt + 1)
            delay(backoffMs)
        }
    }

    return lastResponse!!
}

private fun <T> parseRetryAfter(response: Response<T>): Long? {
    val header = response.headers()["Retry-After"] ?: return null
    // Retry-After can be seconds (integer) or an HTTP date; handle the integer case
    return header.toLongOrNull()?.let { it * 1000 }
}
