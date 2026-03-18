package com.github.ydymovopenclawbot.stackworktree.pr.github

import com.github.ydymovopenclawbot.stackworktree.pr.PrInfo
import com.github.ydymovopenclawbot.stackworktree.pr.PrProvider
import com.github.ydymovopenclawbot.stackworktree.pr.PrProviderException
import com.github.ydymovopenclawbot.stackworktree.pr.PrStatus
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.service
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.project.Project
import kotlinx.coroutines.runBlocking
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import org.jetbrains.plugins.github.authentication.GithubAuthenticationManager
import org.jetbrains.plugins.github.authentication.accounts.GHAccountManager

/**
 * [PrProvider] implementation backed by the GitHub REST API (v3).
 *
 * Authentication uses the GitHub token stored in IntelliJ's account manager
 * (`Settings → Version Control → GitHub`).  The first account whose server matches
 * the project's remote is used; falls back to the first registered account.
 *
 * **Threading**: all methods perform blocking network I/O — callers must not invoke
 * them from the Event Dispatch Thread.
 *
 * The OkHttp client includes a [RateLimitInterceptor] that transparently retries
 * requests on HTTP 429 / 403 rate-limit responses with exponential back-off.
 */
@Service(Service.Level.PROJECT)
class GitHubProvider(private val project: Project) : PrProvider {

    // ── HTTP client ───────────────────────────────────────────────────────────

    private val client: OkHttpClient = OkHttpClient.Builder()
        .addInterceptor(RateLimitInterceptor())
        .build()

    // ── PrProvider ────────────────────────────────────────────────────────────

    override fun createPr(branch: String, base: String, title: String, body: String): PrInfo {
        val repo = repoInfo()
        val payload = buildJsonObject(
            "title" to title,
            "body" to body,
            "head" to branch,
            "base" to base,
        )
        val request = postRequest("${repo.apiBaseUrl}/repos/${repo.owner}/${repo.repo}/pulls", payload)
        return execute(request) { GitHubJsonParser.parsePrInfo(it) }
    }

    override fun updatePr(prId: Int, title: String?, body: String?, base: String?): PrInfo {
        val repo = repoInfo()
        val fields = mutableListOf<Pair<String, String>>()
        title?.let { fields += "title" to it }
        body?.let { fields += "body" to it }
        base?.let { fields += "base" to it }

        val payload = buildJsonObject(*fields.toTypedArray())
        val request = patchRequest(
            "${repo.apiBaseUrl}/repos/${repo.owner}/${repo.repo}/pulls/$prId",
            payload,
        )
        return execute(request) { GitHubJsonParser.parsePrInfo(it) }
    }

    override fun getPrStatus(prId: Int): PrStatus {
        val repo = repoInfo()
        val base = "${repo.apiBaseUrl}/repos/${repo.owner}/${repo.repo}"

        // 1. Fetch PR to get the head SHA
        val prJson = execute(getRequest("$base/pulls/$prId")) { it }
        val prInfo = GitHubJsonParser.parsePrInfo(prJson)

        // 2. Resolve head SHA from the raw PR JSON (re-parse for the sha field)
        val headSha = resolveHeadSha(prJson)

        // 3. Fetch check-runs and reviews in parallel would require coroutines; keep it
        //    simple with two sequential requests since we're already on a pooled thread.
        val checkRunsJson = execute(getRequest("$base/commits/$headSha/check-runs")) { it }
        val reviewsJson = execute(getRequest("$base/pulls/$prId/reviews")) { it }

        return GitHubJsonParser.parsePrStatus(
            prJson = prJson,
            checkRunsJson = checkRunsJson,
            reviewsJson = reviewsJson,
        ).copy(prInfo = prInfo)
    }

    override fun closePr(prId: Int) {
        val repo = repoInfo()
        val payload = buildJsonObject("state" to "closed")
        val request = patchRequest(
            "${repo.apiBaseUrl}/repos/${repo.owner}/${repo.repo}/pulls/$prId",
            payload,
        )
        execute(request) { /* response body not needed */ }
    }

    override fun findPrByBranch(branch: String): PrInfo? {
        val repo = repoInfo()
        // GitHub requires "owner:branch" for the head filter
        val url = "${repo.apiBaseUrl}/repos/${repo.owner}/${repo.repo}/pulls" +
            "?head=${repo.owner}:$branch&state=open&per_page=1"
        val list = execute(getRequest(url)) { GitHubJsonParser.parsePrList(it) }
        return list.firstOrNull()
    }

    // ── auth ──────────────────────────────────────────────────────────────────

    /**
     * Resolves the GitHub token for this project.
     *
     * Looks up registered GitHub accounts and picks the one whose server host matches the
     * project's remote URL (for GHE compatibility).  Falls back to the first account if
     * no exact match is found.
     *
     * @throws PrProviderException if no GitHub account is configured or the token cannot
     *   be retrieved.
     */
    private fun resolveToken(): String {
        val authManager = GithubAuthenticationManager.getInstance()
        val accounts = authManager.getAccounts()
        if (accounts.isEmpty()) {
            throw PrProviderException(
                "No GitHub account configured in the IDE. " +
                    "Go to Settings → Version Control → GitHub to add one."
            )
        }

        // Prefer an account whose server matches the project remote (GHE support)
        val repoHost = repoInfo().apiBaseUrl
            .removePrefix("https://")
            .removePrefix("http://")
            .removeSuffix("/api/v3")
            .removeSuffix("/api/v3/")

        val account = accounts.firstOrNull { it.server.host == repoHost }
            ?: accounts.first()

        val token = runBlocking { project.service<GHAccountManager>().findCredentials(account) }
            ?: throw PrProviderException(
                "Could not retrieve token for GitHub account '${account.name}'. " +
                    "Try re-authenticating in Settings → Version Control → GitHub."
            )
        return token
    }

    // ── HTTP helpers ──────────────────────────────────────────────────────────

    private fun repoInfo(): GitHubRepoInfo =
        GitHubRepoInfo.fromProject(project)
            ?: throw PrProviderException(
                "Cannot determine GitHub repository from the project's git remotes. " +
                    "Make sure the remote URL contains a GitHub hostname."
            )

    private fun getRequest(url: String): Request =
        Request.Builder()
            .url(url)
            .header("Authorization", "Bearer ${resolveToken()}")
            .header("Accept", "application/vnd.github+json")
            .header("X-GitHub-Api-Version", GITHUB_API_VERSION)
            .build()

    private fun postRequest(url: String, body: String): Request =
        Request.Builder()
            .url(url)
            .header("Authorization", "Bearer ${resolveToken()}")
            .header("Accept", "application/vnd.github+json")
            .header("X-GitHub-Api-Version", GITHUB_API_VERSION)
            .post(body.toRequestBody(JSON_MEDIA_TYPE))
            .build()

    private fun patchRequest(url: String, body: String): Request =
        Request.Builder()
            .url(url)
            .header("Authorization", "Bearer ${resolveToken()}")
            .header("Accept", "application/vnd.github+json")
            .header("X-GitHub-Api-Version", GITHUB_API_VERSION)
            .patch(body.toRequestBody(JSON_MEDIA_TYPE))
            .build()

    /**
     * Executes [request], asserts a successful (2xx) response, reads the body, and applies
     * [transform] to produce the result.
     *
     * @throws PrProviderException for non-2xx responses or I/O errors.
     */
    private fun <T> execute(request: Request, transform: (String) -> T): T {
        try {
            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) {
                    val errorBody = response.body?.string() ?: "(empty body)"
                    throw PrProviderException(
                        "GitHub API error ${response.code} for ${request.url}: $errorBody"
                    )
                }
                val bodyStr = response.body?.string() ?: ""
                return transform(bodyStr)
            }
        } catch (e: PrProviderException) {
            throw e
        } catch (e: Exception) {
            throw PrProviderException("Network error calling GitHub API: ${e.message}", e)
        }
    }

    // ── JSON helpers ──────────────────────────────────────────────────────────

    /** Builds a flat JSON object string from the given key-value pairs. */
    private fun buildJsonObject(vararg entries: Pair<String, String>): String {
        val fields = entries.joinToString(",\n  ") { (k, v) ->
            "\"$k\": ${v.toJsonString()}"
        }
        return "{\n  $fields\n}"
    }

    private fun String.toJsonString(): String {
        val escaped = replace("\\", "\\\\")
            .replace("\"", "\\\"")
            .replace("\n", "\\n")
            .replace("\r", "\\r")
            .replace("\t", "\\t")
        return "\"$escaped\""
    }

    /**
     * Extracts the head commit SHA from a raw GitHub PR JSON response.
     * Falls back to an empty string (which will produce a graceful API error).
     */
    private fun resolveHeadSha(prJson: String): String = try {
        // Lean on the already-present kotlinx.serialization.json for this one extraction
        val root = kotlinx.serialization.json.Json.parseToJsonElement(prJson)
            .let { it as? kotlinx.serialization.json.JsonObject } ?: return ""
        root["head"]
            ?.let { it as? kotlinx.serialization.json.JsonObject }
            ?.get("sha")
            ?.let { it as? kotlinx.serialization.json.JsonPrimitive }
            ?.content ?: ""
    } catch (_: Exception) {
        ""
    }

    private companion object {
        private val LOG = Logger.getInstance(GitHubProvider::class.java)
        private val JSON_MEDIA_TYPE = "application/json; charset=utf-8".toMediaType()
        private const val GITHUB_API_VERSION = "2022-11-28"
    }
}
