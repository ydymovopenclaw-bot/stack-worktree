package com.github.ydymovopenclawbot.stackworktree.pr.gitlab

import com.github.ydymovopenclawbot.stackworktree.pr.PrInfo
import com.github.ydymovopenclawbot.stackworktree.pr.PrProviderException
import com.github.ydymovopenclawbot.stackworktree.pr.PrState
import com.github.ydymovopenclawbot.stackworktree.pr.PrStatus
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
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
 * Integration tests for the [GitLabProvider] HTTP layer, driven by [MockWebServer].
 *
 * [GitLabProvider] requires an IntelliJ project service context and cannot be instantiated
 * outside the IDE container.  [TestableGitLabClient] mirrors its HTTP behaviour with injected
 * [GitLabRepoInfo] and token so we can test all endpoints, request shapes, and error handling
 * without a live IDE or real credentials.
 */
class GitLabProviderHttpTest {

    private lateinit var server: MockWebServer
    private lateinit var client: TestableGitLabClient

    @BeforeEach
    fun setUp() {
        server = MockWebServer()
        server.start()
        client = TestableGitLabClient(
            repoInfo = GitLabRepoInfo(
                host = "gitlab.com",
                namespace = "myorg/myrepo",
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
    fun `createPr sends POST to merge_requests endpoint`() {
        server.enqueue(MockResponse().setResponseCode(201).setBody(mrJson(iid = 10)))

        val result = client.createPr("feature", "main", "My MR", "body text")

        assertEquals(10, result.number)
        assertEquals(PrState.OPEN, result.state)
        val req = server.takeRequest()
        assertEquals("POST", req.method)
        assertTrue(
            req.path!!.endsWith("/merge_requests"),
            "Expected path ending in /merge_requests, got ${req.path}",
        )
        assertPrivateTokenHeader(req)
        val body = req.body.readUtf8()
        assertTrue(body.contains("source_branch"), "body missing 'source_branch': $body")
        assertTrue(body.contains("target_branch"), "body missing 'target_branch': $body")
        assertTrue(body.contains("feature"), "body missing branch name 'feature': $body")
    }

    @Test
    fun `createPr sends correct source and target branches`() {
        server.enqueue(MockResponse().setResponseCode(201).setBody(mrJson(iid = 5)))

        client.createPr("my-feature", "develop", "Add feature", "")

        val body = server.takeRequest().body.readUtf8()
        assertTrue(body.contains("\"source_branch\":\"my-feature\""), "body: $body")
        assertTrue(body.contains("\"target_branch\":\"develop\""), "body: $body")
    }

    @Test
    fun `createPr escapes control characters in title and description`() {
        server.enqueue(MockResponse().setResponseCode(201).setBody(mrJson(iid = 11)))

        client.createPr("feature", "main", "Title\u0001Here", "Body\u0000Text")

        val body = server.takeRequest().body.readUtf8()
        assertTrue('\u0001' !in body, "Control char \\u0001 must be escaped in JSON body: $body")
        assertTrue('\u0000' !in body, "Control char \\u0000 must be escaped in JSON body: $body")
    }

    // ── updatePr ─────────────────────────────────────────────────────────────

    @Test
    fun `updatePr sends PUT to merge_requests slash iid`() {
        server.enqueue(MockResponse().setResponseCode(200).setBody(mrJson(iid = 7)))

        val result = client.updatePr(prId = 7, title = "Updated title")

        assertEquals(7, result.number)
        val req = server.takeRequest()
        assertEquals("PUT", req.method)
        assertTrue(
            req.path!!.endsWith("/merge_requests/7"),
            "Expected path ending in /merge_requests/7, got ${req.path}",
        )
        assertPrivateTokenHeader(req)
    }

    @Test
    fun `updatePr maps body parameter to description field`() {
        server.enqueue(MockResponse().setResponseCode(200).setBody(mrJson(iid = 3)))

        client.updatePr(prId = 3, body = "new description")

        val reqBody = server.takeRequest().body.readUtf8()
        assertTrue(reqBody.contains("\"description\""), "body missing 'description' key: $reqBody")
        assertTrue(reqBody.contains("new description"), "body missing description text: $reqBody")
    }

    @Test
    fun `updatePr maps base parameter to target_branch field`() {
        server.enqueue(MockResponse().setResponseCode(200).setBody(mrJson(iid = 4)))

        client.updatePr(prId = 4, base = "release")

        val reqBody = server.takeRequest().body.readUtf8()
        assertTrue(reqBody.contains("\"target_branch\""), "body missing 'target_branch': $reqBody")
        assertTrue(reqBody.contains("release"), "body missing branch value: $reqBody")
    }

    // ── getPrStatus ───────────────────────────────────────────────────────────

    @Test
    fun `getPrStatus makes three requests and returns composed status`() {
        server.enqueue(MockResponse().setResponseCode(200).setBody(mrJson(iid = 5)))
        server.enqueue(MockResponse().setResponseCode(200).setBody("[]"))
        server.enqueue(MockResponse().setResponseCode(200).setBody(approvalsJson(approved = false)))

        val status = client.getPrStatus(5)

        assertEquals(5, status.prInfo.number)
        assertEquals(3, server.requestCount)
    }

    @Test
    fun `getPrStatus fetches MR then pipelines then approvals in order`() {
        server.enqueue(MockResponse().setResponseCode(200).setBody(mrJson(iid = 2)))
        server.enqueue(MockResponse().setResponseCode(200).setBody("[]"))
        server.enqueue(MockResponse().setResponseCode(200).setBody(approvalsJson(approved = false)))

        client.getPrStatus(2)

        val mrReq = server.takeRequest()
        val pipelinesReq = server.takeRequest()
        val approvalsReq = server.takeRequest()

        assertTrue(mrReq.path!!.matches(Regex(".*/merge_requests/2$")), "MR path: ${mrReq.path}")
        assertTrue(pipelinesReq.path!!.contains("pipelines"), "pipelines path: ${pipelinesReq.path}")
        assertTrue(approvalsReq.path!!.contains("approvals"), "approvals path: ${approvalsReq.path}")
    }

    // ── closePr ───────────────────────────────────────────────────────────────

    @Test
    fun `closePr sends PUT with state_event close`() {
        server.enqueue(MockResponse().setResponseCode(200).setBody(mrJson(iid = 3, state = "closed")))

        client.closePr(3)

        val req = server.takeRequest()
        assertEquals("PUT", req.method)
        assertTrue(
            req.path!!.endsWith("/merge_requests/3"),
            "Expected path ending in /merge_requests/3, got ${req.path}",
        )
        val body = req.body.readUtf8()
        assertTrue(body.contains("state_event"), "body missing 'state_event': $body")
        assertTrue(body.contains("close"), "body missing value 'close': $body")
    }

    // ── findPrByBranch ────────────────────────────────────────────────────────

    @Test
    fun `findPrByBranch returns first MR when list is non-empty`() {
        server.enqueue(MockResponse().setResponseCode(200).setBody("[${mrJson(iid = 99)}]"))

        val info = client.findPrByBranch("feature/foo")

        assertNotNull(info)
        assertEquals(99, info!!.number)
        val req = server.takeRequest()
        assertTrue(req.path!!.contains("state=opened"), "path should include state=opened; got ${req.path}")
        assertTrue(req.path!!.contains("source_branch=feature%2Ffoo"), "path should URL-encode branch; got ${req.path}")
    }

    @Test
    fun `findPrByBranch returns null for empty list`() {
        server.enqueue(MockResponse().setResponseCode(200).setBody("[]"))

        assertNull(client.findPrByBranch("no-such-branch"))
    }

    // ── namespace encoding ────────────────────────────────────────────────────

    @Test
    fun `namespace slashes are encoded as percent-2F in API path`() {
        server.enqueue(MockResponse().setResponseCode(200).setBody("[${mrJson(iid = 1)}]"))

        client.findPrByBranch("main")

        val req = server.takeRequest()
        assertTrue(
            req.path!!.contains("myorg%2Fmyrepo"),
            "Namespace slashes should be encoded in path; got ${req.path}",
        )
    }

    // ── state mapping ─────────────────────────────────────────────────────────

    @Test
    fun `opened MR maps to PrState OPEN`() {
        server.enqueue(MockResponse().setResponseCode(200).setBody("[${mrJson(iid = 1, state = "opened")}]"))
        assertEquals(PrState.OPEN, client.findPrByBranch("main")?.state)
    }

    @Test
    fun `closed MR maps to PrState CLOSED`() {
        server.enqueue(MockResponse().setResponseCode(200).setBody("[${mrJson(iid = 2, state = "closed")}]"))
        assertEquals(PrState.CLOSED, client.findPrByBranch("main")?.state)
    }

    @Test
    fun `merged MR maps to PrState MERGED`() {
        server.enqueue(MockResponse().setResponseCode(200).setBody("[${mrJson(iid = 3, state = "merged")}]"))
        assertEquals(PrState.MERGED, client.findPrByBranch("main")?.state)
    }

    // ── error handling ────────────────────────────────────────────────────────

    @Test
    fun `throws PrProviderException on 404`() {
        server.enqueue(MockResponse().setResponseCode(404).setBody("""{"message":"404 Not Found"}"""))

        assertThrows<PrProviderException> { client.getPrStatus(9999) }
    }

    @Test
    fun `throws PrProviderException on 422`() {
        server.enqueue(MockResponse().setResponseCode(422).setBody("""{"message":"Validation failed"}"""))

        assertThrows<PrProviderException> {
            client.createPr("branch", "main", "title", "body")
        }
    }

    @Test
    fun `throws PrProviderException on 401`() {
        server.enqueue(MockResponse().setResponseCode(401).setBody("""{"message":"401 Unauthorized"}"""))

        assertThrows<PrProviderException> { client.findPrByBranch("branch") }
    }

    // ── auth header ───────────────────────────────────────────────────────────

    @Test
    fun `all requests include PRIVATE-TOKEN header`() {
        server.enqueue(MockResponse().setResponseCode(200).setBody("[${mrJson(iid = 1)}]"))

        client.findPrByBranch("branch")

        val req = server.takeRequest()
        assertEquals("test-token-xyz", req.getHeader("PRIVATE-TOKEN"))
    }

    @Test
    fun `createPr includes PRIVATE-TOKEN header`() {
        server.enqueue(MockResponse().setResponseCode(201).setBody(mrJson(iid = 1)))

        client.createPr("feat", "main", "title", "body")

        assertPrivateTokenHeader(server.takeRequest())
    }

    // ── helpers ───────────────────────────────────────────────────────────────

    private fun assertPrivateTokenHeader(req: RecordedRequest) {
        val token = req.getHeader("PRIVATE-TOKEN") ?: ""
        assertTrue(token.isNotBlank(), "Expected PRIVATE-TOKEN header, got empty")
    }

    private fun mrJson(
        iid: Int = 1,
        state: String = "opened",
        title: String = "My MR title",
    ) = """{"iid":$iid,"title":"$title","web_url":"https://gitlab.com/myorg/myrepo/-/merge_requests/$iid","state":"$state"}"""

    private fun approvalsJson(approved: Boolean, approvalsRequired: Int = 1) =
        """{"approved":$approved,"approvals_required":$approvalsRequired,"approvals_left":${if (approved) 0 else approvalsRequired}}"""
}

// ─────────────────────────────────────────────────────────────────────────────
// Test harness — mirrors GitLabProvider HTTP logic with injected dependencies
// ─────────────────────────────────────────────────────────────────────────────

/**
 * Thin wrapper around the same HTTP mechanics used by [GitLabProvider], with
 * [GitLabRepoInfo] and the auth token injected instead of resolved from the IDE.
 * This lets us write full end-to-end HTTP tests without an IntelliJ container.
 *
 * **IMPORTANT — keep in sync with [GitLabProvider]**: This class intentionally duplicates
 * the HTTP logic from [GitLabProvider] rather than testing [GitLabProvider] directly,
 * because [GitLabProvider] requires the IntelliJ project service container to resolve its
 * token via [GitLabTokenStore].  Any change to a URL path, query-parameter encoding, HTTP
 * verb, header, or JSON body field in [GitLabProvider] **must** be reflected here, and vice
 * versa, or the test suite will diverge silently from production behaviour.
 *
 * In particular, note that [findPrByBranch] must URL-encode branch names (replacing '/'
 * with '%2F') to match the production implementation — the test at
 * `findPrByBranch returns first MR when list is non-empty` asserts this explicitly.
 */
private class TestableGitLabClient(
    private val repoInfo: GitLabRepoInfo,
    private val token: String,
) {
    private val httpClient = OkHttpClient()
    private val jsonMime = "application/json; charset=utf-8".toMediaType()

    private val base get() = "${repoInfo.apiBaseUrl}/api/v4/projects/${repoInfo.encodedNamespace}"

    fun createPr(branch: String, base: String, title: String, body: String): PrInfo {
        val payload = jsonOf(
            "source_branch" to branch,
            "target_branch" to base,
            "title" to title,
            "description" to body,
        )
        return execute(postReq("${this.base}/merge_requests", payload)) { GitLabJsonParser.parseMrInfo(it) }
    }

    fun updatePr(prId: Int, title: String? = null, body: String? = null, base: String? = null): PrInfo {
        val fields = buildList<Pair<String, String>> {
            title?.let { add("title" to it) }
            body?.let { add("description" to it) }
            base?.let { add("target_branch" to it) }
        }
        return execute(putReq("${this.base}/merge_requests/$prId", jsonOf(*fields.toTypedArray()))) {
            GitLabJsonParser.parseMrInfo(it)
        }
    }

    fun getPrStatus(prId: Int): PrStatus {
        val mrJson = execute(getReq("$base/merge_requests/$prId")) { it }
        val pipelinesJson = execute(getReq("$base/merge_requests/$prId/pipelines")) { it }
        val approvalsJson = execute(getReq("$base/merge_requests/$prId/approvals")) { it }
        return GitLabJsonParser.parsePrStatus(mrJson, pipelinesJson, approvalsJson)
    }

    fun closePr(prId: Int) {
        execute(putReq("$base/merge_requests/$prId", jsonOf("state_event" to "close"))) {}
    }

    fun findPrByBranch(branch: String): PrInfo? {
        val encodedBranch = branch.replace("/", "%2F")
        val url = "$base/merge_requests?source_branch=$encodedBranch&state=opened&per_page=1"
        return execute(getReq(url)) { GitLabJsonParser.parseMrList(it).firstOrNull() }
    }

    // ── request builders ──────────────────────────────────────────────────────

    private fun getReq(url: String) = Request.Builder()
        .url(url)
        .header("PRIVATE-TOKEN", token)
        .build()

    private fun postReq(url: String, body: String) = Request.Builder()
        .url(url)
        .header("PRIVATE-TOKEN", token)
        .post(body.toRequestBody(jsonMime))
        .build()

    private fun putReq(url: String, body: String) = Request.Builder()
        .url(url)
        .header("PRIVATE-TOKEN", token)
        .put(body.toRequestBody(jsonMime))
        .build()

    // ── execute ───────────────────────────────────────────────────────────────

    private fun <T> execute(req: Request, transform: (String) -> T): T {
        httpClient.newCall(req).execute().use { resp ->
            if (!resp.isSuccessful) {
                val errorBody = resp.body?.string() ?: ""
                throw com.github.ydymovopenclawbot.stackworktree.pr.PrProviderException(
                    "HTTP ${resp.code} for ${req.url}: $errorBody"
                )
            }
            return transform(resp.body?.string() ?: "")
        }
    }

    // ── JSON helper ───────────────────────────────────────────────────────────

    /**
     * Builds a JSON object using [buildJsonObject] so that control characters are
     * correctly escaped.
     */
    private fun jsonOf(vararg pairs: Pair<String, String>): String =
        buildJsonObject { pairs.forEach { (k, v) -> put(k, v) } }.toString()
}
