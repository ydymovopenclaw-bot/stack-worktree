package com.github.ydymovopenclawbot.stackworktree.pr.github

import com.intellij.openapi.diagnostic.Logger
import okhttp3.Interceptor
import okhttp3.Response

/**
 * OkHttp interceptor that handles GitHub API rate limiting gracefully.
 *
 * Detection strategy:
 * - HTTP **429** (Too Many Requests) — always a rate limit signal.
 * - HTTP **403** with `X-RateLimit-Remaining: 0` — GitHub's primary rate limit response.
 *
 * Retry strategy:
 * 1. Read the `Retry-After` header (seconds) if present; otherwise read `X-RateLimit-Reset`
 *    (Unix epoch) to compute the wait.  Fall back to exponential backoff seeded at
 *    [initialBackoffMs] if neither header is present.
 * 2. Retry up to [maxRetries] times.  After exhausting retries the last error response is
 *    returned as-is so the caller can inspect the status code.
 *
 * This interceptor calls [Thread.sleep] and therefore **must** run on a background thread.
 */
class RateLimitInterceptor(
    private val maxRetries: Int = 3,
    private val initialBackoffMs: Long = 1_000L,
) : Interceptor {

    override fun intercept(chain: Interceptor.Chain): Response {
        var response = chain.proceed(chain.request())
        var attempt = 0

        while (attempt < maxRetries && isRateLimited(response)) {
            val waitMs = waitDurationMs(response, attempt)
            LOG.warn(
                "GitHub rate limit hit (HTTP ${response.code}). " +
                    "Retrying in ${waitMs}ms (attempt ${attempt + 1}/$maxRetries)."
            )
            response.close()
            Thread.sleep(waitMs)
            response = chain.proceed(chain.request())
            attempt++
        }

        return response
    }

    // ── helpers ──────────────────────────────────────────────────────────────

    private fun isRateLimited(response: Response): Boolean {
        if (response.code == 429) return true
        if (response.code == 403 && response.header("X-RateLimit-Remaining") == "0") return true
        return false
    }

    /**
     * Returns the number of milliseconds to wait before the next retry.
     *
     * Priority:
     * 1. `Retry-After` header (seconds as integer).
     * 2. `X-RateLimit-Reset` header (Unix epoch seconds) — wait until that instant, plus a
     *    small safety buffer.
     * 3. Exponential backoff: `initialBackoffMs * 2^attempt`.
     */
    private fun waitDurationMs(response: Response, attempt: Int): Long {
        val retryAfter = response.header("Retry-After")?.toLongOrNull()
        if (retryAfter != null) return retryAfter * 1_000L

        val resetEpoch = response.header("X-RateLimit-Reset")?.toLongOrNull()
        if (resetEpoch != null) {
            val nowSeconds = System.currentTimeMillis() / 1_000L
            val waitSeconds = (resetEpoch - nowSeconds).coerceAtLeast(0L)
            return (waitSeconds + RESET_BUFFER_SECONDS) * 1_000L
        }

        return initialBackoffMs * (1L shl attempt) // 1s, 2s, 4s, …
    }

    private companion object {
        private val LOG = Logger.getInstance(RateLimitInterceptor::class.java)
        private const val RESET_BUFFER_SECONDS = 2L
    }
}
