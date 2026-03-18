package com.github.ydymovopenclawbot.stackworktree.pr.github

import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Timeout
import java.util.concurrent.TimeUnit

@Timeout(10, unit = TimeUnit.SECONDS)
class RateLimitInterceptorTest {

    private lateinit var server: MockWebServer

    @BeforeEach
    fun setUp() {
        server = MockWebServer()
        server.start()
    }

    @AfterEach
    fun tearDown() {
        server.shutdown()
    }

    // ── helpers ───────────────────────────────────────────────────────────────

    private fun clientWith(maxRetries: Int = 3, initialBackoffMs: Long = 10): OkHttpClient =
        OkHttpClient.Builder()
            .addInterceptor(RateLimitInterceptor(maxRetries = maxRetries, initialBackoffMs = initialBackoffMs))
            .build()

    private fun get(): okhttp3.Response {
        val request = Request.Builder().url(server.url("/")).build()
        return clientWith().newCall(request).execute()
    }

    // ── no rate limit ─────────────────────────────────────────────────────────

    @Test
    fun `passes through 200 response without retry`() {
        server.enqueue(MockResponse().setResponseCode(200).setBody("ok"))
        val response = get()
        assertEquals(200, response.code)
        assertEquals(1, server.requestCount)
    }

    @Test
    fun `passes through 404 response without retry`() {
        server.enqueue(MockResponse().setResponseCode(404))
        val response = get()
        assertEquals(404, response.code)
        assertEquals(1, server.requestCount)
    }

    @Test
    fun `403 without rate-limit header is not retried`() {
        server.enqueue(MockResponse().setResponseCode(403).setBody("forbidden"))
        val response = get()
        assertEquals(403, response.code)
        assertEquals(1, server.requestCount)
    }

    // ── 429 retries ───────────────────────────────────────────────────────────

    @Test
    fun `retries on 429 and returns success`() {
        server.enqueue(MockResponse().setResponseCode(429))
        server.enqueue(MockResponse().setResponseCode(200).setBody("ok"))

        val client = OkHttpClient.Builder()
            .addInterceptor(RateLimitInterceptor(maxRetries = 3, initialBackoffMs = 10))
            .build()
        val response = client.newCall(Request.Builder().url(server.url("/")).build()).execute()

        assertEquals(200, response.code)
        assertEquals(2, server.requestCount)
    }

    @Test
    fun `retries on 403 with X-RateLimit-Remaining zero`() {
        server.enqueue(
            MockResponse()
                .setResponseCode(403)
                .addHeader("X-RateLimit-Remaining", "0")
        )
        server.enqueue(MockResponse().setResponseCode(200).setBody("ok"))

        val client = OkHttpClient.Builder()
            .addInterceptor(RateLimitInterceptor(maxRetries = 3, initialBackoffMs = 10))
            .build()
        val response = client.newCall(Request.Builder().url(server.url("/")).build()).execute()

        assertEquals(200, response.code)
        assertEquals(2, server.requestCount)
    }

    @Test
    fun `respects Retry-After header`() {
        // Use 0s Retry-After so the test doesn't actually wait
        server.enqueue(
            MockResponse()
                .setResponseCode(429)
                .addHeader("Retry-After", "0")
        )
        server.enqueue(MockResponse().setResponseCode(200))

        val client = OkHttpClient.Builder()
            .addInterceptor(RateLimitInterceptor(maxRetries = 2, initialBackoffMs = 10))
            .build()
        val response = client.newCall(Request.Builder().url(server.url("/")).build()).execute()

        assertEquals(200, response.code)
        assertEquals(2, server.requestCount)
    }

    @Test
    fun `gives up after max retries and returns last rate-limit response`() {
        // Enqueue more 429s than maxRetries
        repeat(5) {
            server.enqueue(MockResponse().setResponseCode(429))
        }

        val client = OkHttpClient.Builder()
            .addInterceptor(RateLimitInterceptor(maxRetries = 2, initialBackoffMs = 10))
            .build()
        val response = client.newCall(Request.Builder().url(server.url("/")).build()).execute()

        assertEquals(429, response.code)
        // 1 initial + 2 retries = 3 total requests
        assertEquals(3, server.requestCount)
    }
}
