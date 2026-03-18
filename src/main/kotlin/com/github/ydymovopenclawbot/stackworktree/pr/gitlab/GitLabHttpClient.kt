package com.github.ydymovopenclawbot.stackworktree.pr.gitlab

import com.github.ydymovopenclawbot.stackworktree.pr.PrInfo
import com.github.ydymovopenclawbot.stackworktree.pr.PrProviderException
import com.github.ydymovopenclawbot.stackworktree.pr.PrStatus
import com.intellij.openapi.diagnostic.Logger
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.net.URLEncoder

/**
 * Low-level HTTP client for the GitLab REST API v4.
 *
 * Encapsulates all request building, response parsing, and error handling for the GitLab
 * endpoints used by the plugin.  Callers supply a resolved [token] and a [GitLabRepoInfo];
 * the class itself is stateless beyond those inputs.
 *
 * All methods perform **blocking** network I/O and must not be called from the EDT.
 *
 * @param repoInfo   Resolved coordinates (host, namespace, base URL) for the GitLab project.
 * @param token      A valid GitLab Personal Access Token with `api` scope.
 * @param httpClient The OkHttpClient to use.  Defaults to [HTTP_CLIENT], the shared singleton,
 *                   which is reused across all instances to share its thread and connection pools.
 *                   Pass a custom client (e.g. backed by MockWebServer) in tests.
 */
internal class GitLabHttpClient(
    private val repoInfo: GitLabRepoInfo,
    private val token: String,
    private val httpClient: OkHttpClient = HTTP_CLIENT,
) {
    // Base path for all project-scoped GitLab API requests.
    private val projectBase: String
        get() = "${repoInfo.apiBaseUrl}/api/v4/projects/${repoInfo.encodedNamespace}"

    // ── PrProvider operations ──────────────────────────────────────────────────

    fun createPr(branch: String, base: String, title: String, body: String): PrInfo {
        val payload = buildJsonPayload(
            "source_branch" to branch,
            "target_branch" to base,
            "title" to title,
            "description" to body,
        )
        return execute(postRequest("$projectBase/merge_requests", payload)) {
            GitLabJsonParser.parseMrInfo(it)
        }
    }

    fun updatePr(prId: Int, title: String?, body: String?, base: String?): PrInfo {
        val fields = buildList<Pair<String, String>> {
            title?.let { add("title" to it) }
            body?.let { add("description" to it) }
            base?.let { add("target_branch" to it) }
        }
        val payload = buildJsonPayload(*fields.toTypedArray())
        return execute(putRequest("$projectBase/merge_requests/$prId", payload)) {
            GitLabJsonParser.parseMrInfo(it)
        }
    }

    fun getPrStatus(prId: Int): PrStatus {
        val mrJson = execute(getRequest("$projectBase/merge_requests/$prId")) { it }
        val pipelinesJson = execute(getRequest("$projectBase/merge_requests/$prId/pipelines")) { it }
        val approvalsJson = execute(getRequest("$projectBase/merge_requests/$prId/approvals")) { it }
        return GitLabJsonParser.parsePrStatus(mrJson, pipelinesJson, approvalsJson)
    }

    fun closePr(prId: Int) {
        val payload = buildJsonPayload("state_event" to "close")
        execute(putRequest("$projectBase/merge_requests/$prId", payload)) { /* response not needed */ }
    }

    fun findPrByBranch(branch: String): PrInfo? {
        // Branch names can contain characters that are special in query strings
        // (e.g. '/', '+', '&', '#').  Use URLEncoder for full percent-encoding,
        // then swap '+' back to '%20' since URLEncoder uses '+' for spaces but
        // GitLab's API parser expects %20.
        val encodedBranch = URLEncoder.encode(branch, "UTF-8").replace("+", "%20")
        val url = "$projectBase/merge_requests?source_branch=$encodedBranch&state=opened&per_page=1"
        LOG.info("GitLabHttpClient: findPrByBranch '$branch' → GET $url")
        return execute(getRequest(url)) { GitLabJsonParser.parseMrList(it).firstOrNull() }
    }

    // ── Request builders ───────────────────────────────────────────────────────

    private fun getRequest(url: String): Request =
        Request.Builder().url(url).header("PRIVATE-TOKEN", token).build()

    private fun postRequest(url: String, body: String): Request =
        Request.Builder()
            .url(url)
            .header("PRIVATE-TOKEN", token)
            .post(body.toRequestBody(JSON_MEDIA_TYPE))
            .build()

    private fun putRequest(url: String, body: String): Request =
        Request.Builder()
            .url(url)
            .header("PRIVATE-TOKEN", token)
            .put(body.toRequestBody(JSON_MEDIA_TYPE))
            .build()

    // ── Response execution ─────────────────────────────────────────────────────

    /**
     * Executes [request], asserts a 2xx response, reads the response body, and passes it
     * to [transform] to produce the result.
     *
     * @throws PrProviderException on non-2xx status, a missing/empty body on a 2xx response,
     *   or any I/O error.
     */
    internal fun <T> execute(request: Request, transform: (String) -> T): T {
        try {
            httpClient.newCall(request).execute().use { response ->
                if (!response.isSuccessful) {
                    val errorBody = response.body?.string() ?: "(empty body)"
                    throw PrProviderException(
                        "GitLab API error ${response.code} for ${request.url}: $errorBody"
                    )
                }
                val body = response.body?.string()
                    ?: throw PrProviderException(
                        "GitLab API returned empty body for ${request.url}"
                    )
                return transform(body)
            }
        } catch (e: PrProviderException) {
            throw e
        } catch (e: Exception) {
            throw PrProviderException("Network error calling GitLab API: ${e.message}", e)
        }
    }

    // ── JSON helpers ───────────────────────────────────────────────────────────

    /**
     * Builds a flat JSON object string from the given key-value pairs.
     * Uses [buildJsonObject] to ensure correct escaping of all characters.
     */
    private fun buildJsonPayload(vararg entries: Pair<String, String>): String =
        buildJsonObject { entries.forEach { (k, v) -> put(k, v) } }.toString()

    // ── Companion ──────────────────────────────────────────────────────────────

    companion object {
        /**
         * Application-wide shared [OkHttpClient].
         *
         * A single instance is reused across all [GitLabHttpClient] usages so that the
         * underlying thread pool and connection pool are shared rather than duplicated.
         * Do **not** shut this down; its lifetime matches the JVM process.
         */
        val HTTP_CLIENT: OkHttpClient = OkHttpClient()

        private val JSON_MEDIA_TYPE = "application/json; charset=utf-8".toMediaType()
        private val LOG = Logger.getInstance(GitLabHttpClient::class.java)
    }
}
