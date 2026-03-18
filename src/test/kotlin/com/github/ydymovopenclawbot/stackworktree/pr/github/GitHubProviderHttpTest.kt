package com.github.ydymovopenclawbot.stackworktree.pr.github

import com.github.ydymovopenclawbot.stackworktree.pr.PrInfo
import com.github.ydymovopenclawbot.stackworktree.pr.PrProviderException
import com.github.ydymovopenclawbot.stackworktree.pr.PrState
import com.github.ydymovopenclawbot.stackworktree.pr.PrStatus
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

/**
 * Integration tests for the [GitHubProvider] HTTP layer, driven by [MockWebServer].
 *
 * [GitHubProvider] is an IntelliJ project service and cannot be instantiated outside the
 * IDE container.  [TestableGitHubClient] mirrors its HTTP behaviour with injected
 * [GitHubRepoInfo] and token so we can test endpoints, request shapes, and error
 * handling without a live IDE or real credentials.
 */
class GitHubProviderHttpTest {

    private lateinit var server: MockWebServer
    private lateinit var client: TestableGitHubClient

    @BeforeEach
    fun setUp() {
        server = MockWebServer()
        server.start()
        client = TestableGitHubClient(
            repoInfo = GitHubRepoInfo(
                owner = "octocat",
                repo = "Hello-World",
                apiBaseUrl = server.url("").toString().trimEnd('/'),
            ),
            token = "test-token-xyz",
        )
    }

    @AfterEach
    fun tearDown() {
        server.shutdown()
    }

    // ── createPr ──────────────────────────────────────────────────────────────

    @Test
    fun `createPr sends POST to pulls endpoint`() {
        server.enqueue(MockResponse().setResponseCode(201).setBody(prJson(number = 10)))

        val result = client.createPr("feature", "main", "My PR", "body text")

        assertEquals(10, result.number)
        assertEquals(PrState.OPEN, result.state)
        val req = server.takeRequest()
        assertEquals("POST", req.method)
        assertTrue(req.path!!.endsWith("/pulls"), "Expected path ending in /pulls, got ${req.path}")
        assertAuthHeader(req)
        val body = req.body.readUtf8()
        assertTrue(body.contains("\"head\""), "body missing 'head' field: $body")
        assertTrue(body.contains("\"base\""), "body missing 'base' field: $body")
    }

    // ── updatePr ─────────────────────────────────────────────────────────────

    @Test
    fun `updatePr sends PATCH to pulls slash id`() {
        server.enqueue(MockResponse().setResponseCode(200).setBody(prJson(number = 7)))

        val result = client.updatePr(prId = 7, title = "New title")

        assertEquals(7, result.number)
        val req = server.takeRequest()
        assertEquals("PATCH", req.method)
        assertTrue(req.path!!.endsWith("/pulls/7"), "Expected path ending in /pulls/7, got ${req.path}")
        assertAuthHeader(req)
    }

    // ── getPrStatus ───────────────────────────────────────────────────────────

    @Test
    fun `getPrStatus makes three requests and returns composed status`() {
        server.enqueue(MockResponse().setResponseCode(200).setBody(prJson(number = 5, sha = "deadbeef")))
        server.enqueue(MockResponse().setResponseCode(200).setBody("""{"check_runs":[]}"""))
        server.enqueue(MockResponse().setResponseCode(200).setBody("[]"))

        val status = client.getPrStatus(5)

        assertEquals(5, status.prInfo.number)
        assertEquals(3, server.requestCount)

        server.takeRequest() // PR
        val checkRunsReq = server.takeRequest()
        assertTrue(
            checkRunsReq.path!!.contains("deadbeef"),
            "Check-runs request should use head SHA; path was ${checkRunsReq.path}",
        )
    }

    // ── closePr ───────────────────────────────────────────────────────────────

    @Test
    fun `closePr sends PATCH with state closed`() {
        server.enqueue(MockResponse().setResponseCode(200).setBody(prJson(number = 3, state = "closed")))

        client.closePr(3)

        val req = server.takeRequest()
        assertEquals("PATCH", req.method)
        assertTrue(req.path!!.endsWith("/pulls/3"), "Expected path ending in /pulls/3, got ${req.path}")
        assertTrue(req.body.readUtf8().contains("closed"), "body should contain 'closed'")
    }

    // ── findPrByBranch ────────────────────────────────────────────────────────

    @Test
    fun `findPrByBranch returns first PR when list is non-empty`() {
        server.enqueue(MockResponse().setResponseCode(200).setBody("[${prJson(number = 99)}]"))

        val info = client.findPrByBranch("feature/foo")

        assertNotNull(info)
        assertEquals(99, info!!.number)
        val req = server.takeRequest()
        assertTrue(req.path!!.contains("state=open"), "Query should include state=open; path was ${req.path}")
    }

    @Test
    fun `findPrByBranch returns null for empty list`() {
        server.enqueue(MockResponse().setResponseCode(200).setBody("[]"))

        assertNull(client.findPrByBranch("no-such-branch"))
    }

    // ── error handling ────────────────────────────────────────────────────────

    @Test
    fun `throws PrProviderException on 404`() {
        server.enqueue(MockResponse().setResponseCode(404).setBody("""{"message":"Not Found"}"""))

        assertThrows<PrProviderException> {
            client.getPrStatus(9999)
        }
    }

    @Test
    fun `throws PrProviderException on 422`() {
        server.enqueue(MockResponse().setResponseCode(422).setBody("""{"message":"Validation Failed"}"""))

        assertThrows<PrProviderException> {
            client.createPr("branch", "main", "title", "body")
        }
    }

    // ── auth header ───────────────────────────────────────────────────────────

    @Test
    fun `all requests include Bearer token`() {
        server.enqueue(MockResponse().setResponseCode(200).setBody("[${prJson(number = 1)}]"))

        client.findPrByBranch("branch")

        val req = server.takeRequest()
        assertEquals("Bearer test-token-xyz", req.getHeader("Authorization"))
    }

    // ── helpers ───────────────────────────────────────────────────────────────

    private fun assertAuthHeader(req: RecordedRequest) {
        val auth = req.getHeader("Authorization") ?: ""
        assertTrue(auth.startsWith("Bearer "), "Expected Bearer auth, got: $auth")
    }

    private fun prJson(
        number: Int = 1,
        state: String = "open",
        merged: Boolean = false,
        sha: String = "abc123",
    ) = """{"number":$number,"title":"My PR title","html_url":"https://github.com/octocat/Hello-World/pull/$number","state":"$state","merged":$merged,"head":{"sha":"$sha"}}"""
}

// ─────────────────────────────────────────────────────────────────────────────
// Test harness — mirrors GitHubProvider HTTP logic with injected dependencies
// ─────────────────────────────────────────────────────────────────────────────

/**
 * Thin wrapper around the same HTTP mechanics used by [GitHubProvider], with
 * [GitHubRepoInfo] and the auth token injected instead of resolved from the IDE.
 * This lets us write full end-to-end HTTP tests without an IntelliJ container.
 */
private class TestableGitHubClient(
    private val repoInfo: GitHubRepoInfo,
    private val token: String,
) {
    private val httpClient = OkHttpClient.Builder()
        .addInterceptor(RateLimitInterceptor(initialBackoffMs = 10))
        .build()

    private val base get() = "${repoInfo.apiBaseUrl}/repos/${repoInfo.owner}/${repoInfo.repo}"
    private val jsonMime = "application/json; charset=utf-8".toMediaType()

    fun createPr(branch: String, base: String, title: String, body: String): PrInfo {
        val payload = jsonOf("title" to title, "body" to body, "head" to branch, "base" to base)
        return execute(postReq("${this.base}/pulls", payload)) { GitHubJsonParser.parsePrInfo(it) }
    }

    fun updatePr(prId: Int, title: String? = null, body: String? = null, base: String? = null): PrInfo {
        val fields = buildList<Pair<String, String>> {
            title?.let { add("title" to it) }
            body?.let { add("body" to it) }
            base?.let { add("base" to it) }
        }
        val payload = jsonOf(*fields.toTypedArray())
        return execute(patchReq("${this.base}/pulls/$prId", payload)) { GitHubJsonParser.parsePrInfo(it) }
    }

    fun getPrStatus(prId: Int): PrStatus {
        val prJson = execute(getReq("${base}/pulls/$prId")) { it }
        val sha = extractSha(prJson)
        val checksJson = execute(getReq("${base}/commits/$sha/check-runs")) { it }
        val reviewsJson = execute(getReq("${base}/pulls/$prId/reviews")) { it }
        return GitHubJsonParser.parsePrStatus(prJson, checksJson, reviewsJson)
    }

    fun closePr(prId: Int) {
        execute(patchReq("${base}/pulls/$prId", jsonOf("state" to "closed"))) {}
    }

    fun findPrByBranch(branch: String): PrInfo? {
        val url = "${base}/pulls?head=${repoInfo.owner}:$branch&state=open&per_page=1"
        return execute(getReq(url)) { GitHubJsonParser.parsePrList(it).firstOrNull() }
    }

    // ── request builders ──────────────────────────────────────────────────────

    private fun getReq(url: String) = Request.Builder()
        .url(url).header("Authorization", "Bearer $token").build()

    private fun postReq(url: String, body: String) = Request.Builder()
        .url(url).header("Authorization", "Bearer $token")
        .post(body.toRequestBody(jsonMime)).build()

    private fun patchReq(url: String, body: String) = Request.Builder()
        .url(url).header("Authorization", "Bearer $token")
        .patch(body.toRequestBody(jsonMime)).build()

    // ── execute ───────────────────────────────────────────────────────────────

    private fun <T> execute(req: Request, transform: (String) -> T): T {
        httpClient.newCall(req).execute().use { resp ->
            if (!resp.isSuccessful) {
                val errorBody = resp.body?.string() ?: ""
                throw PrProviderException("HTTP ${resp.code} for ${req.url}: $errorBody")
            }
            return transform(resp.body?.string() ?: "")
        }
    }

    // ── helpers ───────────────────────────────────────────────────────────────

    private fun jsonOf(vararg pairs: Pair<String, String>): String {
        val fields = pairs.joinToString(",") { (k, v) ->
            "\"$k\":\"${v.replace("\\", "\\\\").replace("\"", "\\\"")}\""
        }
        return "{$fields}"
    }

    private fun extractSha(prJson: String): String = try {
        kotlinx.serialization.json.Json.parseToJsonElement(prJson)
            .jsonObject["head"]?.jsonObject?.get("sha")?.jsonPrimitive?.content ?: ""
    } catch (_: Exception) {
        ""
    }
}
