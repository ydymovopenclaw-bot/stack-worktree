package com.github.ydymovopenclawbot.stackworktree.pr.gitlab

import com.github.ydymovopenclawbot.stackworktree.pr.PrInfo
import com.github.ydymovopenclawbot.stackworktree.pr.PrProvider
import com.github.ydymovopenclawbot.stackworktree.pr.PrProviderException
import com.github.ydymovopenclawbot.stackworktree.pr.PrStatus
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.Messages
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody

/**
 * [PrProvider] implementation backed by the GitLab REST API v4.
 *
 * Authentication uses a Personal Access Token (PAT) stored in IntelliJ's
 * [com.intellij.ide.passwordSafe.PasswordSafe] via [GitLabTokenStore].  If no token is
 * stored for the detected host, the user is prompted to enter one via an input dialog;
 * the token is then persisted for future calls.
 *
 * **Threading**: all methods perform blocking network I/O — callers must not invoke
 * them from the Event Dispatch Thread.
 *
 * @param project    The current IDE project (used for the token-prompt dialog owner).
 * @param repoInfo   The resolved GitLab project coordinates.
 * @param tokenStore Application-level service that manages PAT persistence.
 */
class GitLabProvider(
    private val project: Project,
    private val repoInfo: GitLabRepoInfo,
    private val tokenStore: GitLabTokenStore,
) : PrProvider {

    // Shared OkHttpClient — reuse across calls for connection pooling.
    private val client: OkHttpClient = OkHttpClient()

    // ── PrProvider ────────────────────────────────────────────────────────────

    override fun createPr(branch: String, base: String, title: String, body: String): PrInfo {
        val token = resolveToken()
        val payload = buildJsonPayload(
            "source_branch" to branch,
            "target_branch" to base,
            "title" to title,
            "description" to body,
        )
        val url = "${repoInfo.apiBaseUrl}/api/v4/projects/${repoInfo.encodedNamespace}/merge_requests"
        return execute(postRequest(url, payload, token)) { GitLabJsonParser.parseMrInfo(it) }
    }

    override fun updatePr(prId: Int, title: String?, body: String?, base: String?): PrInfo {
        val token = resolveToken()
        val fields = buildList<Pair<String, String>> {
            title?.let { add("title" to it) }
            body?.let { add("description" to it) }
            base?.let { add("target_branch" to it) }
        }
        val payload = buildJsonPayload(*fields.toTypedArray())
        val url = "${repoInfo.apiBaseUrl}/api/v4/projects/${repoInfo.encodedNamespace}/merge_requests/$prId"
        return execute(putRequest(url, payload, token)) { GitLabJsonParser.parseMrInfo(it) }
    }

    override fun getPrStatus(prId: Int): PrStatus {
        val token = resolveToken()
        val base = "${repoInfo.apiBaseUrl}/api/v4/projects/${repoInfo.encodedNamespace}"

        val mrJson = execute(getRequest("$base/merge_requests/$prId", token)) { it }
        val pipelinesJson = execute(getRequest("$base/merge_requests/$prId/pipelines", token)) { it }
        val approvalsJson = execute(getRequest("$base/merge_requests/$prId/approvals", token)) { it }

        return GitLabJsonParser.parsePrStatus(mrJson, pipelinesJson, approvalsJson)
    }

    override fun closePr(prId: Int) {
        val token = resolveToken()
        // GitLab closes an MR by sending state_event=close via a PUT update
        val payload = buildJsonPayload("state_event" to "close")
        val url = "${repoInfo.apiBaseUrl}/api/v4/projects/${repoInfo.encodedNamespace}/merge_requests/$prId"
        execute(putRequest(url, payload, token)) { /* response body not needed */ }
    }

    override fun findPrByBranch(branch: String): PrInfo? {
        val token = resolveToken()
        val url = "${repoInfo.apiBaseUrl}/api/v4/projects/${repoInfo.encodedNamespace}/merge_requests" +
            "?source_branch=$branch&state=opened&per_page=1"
        val list = execute(getRequest(url, token)) { GitLabJsonParser.parseMrList(it) }
        return list.firstOrNull()
    }

    // ── Auth ──────────────────────────────────────────────────────────────────

    /**
     * Returns the stored PAT for the GitLab host, or prompts the user for one if none is
     * stored.  The entered token is persisted before being returned.
     *
     * @throws PrProviderException if the user cancels the prompt or enters an empty token.
     */
    private fun resolveToken(): String {
        tokenStore.getToken(repoInfo.host)?.let { return it }

        // No stored token — show an input dialog on the EDT and wait for the result.
        var inputToken: String? = null
        ApplicationManager.getApplication().invokeAndWait {
            inputToken = Messages.showInputDialog(
                project,
                "Enter a Personal Access Token for GitLab (${repoInfo.host}).\n" +
                    "Required scope: api",
                "GitLab Authentication",
                Messages.getQuestionIcon(),
            )
        }

        val pat = inputToken?.takeIf { it.isNotBlank() }
            ?: throw PrProviderException(
                "No GitLab Personal Access Token provided for ${repoInfo.host}. " +
                    "Create one at https://${repoInfo.host}/-/user_settings/personal_access_tokens"
            )

        tokenStore.storeToken(repoInfo.host, pat)
        return pat
    }

    // ── HTTP helpers ──────────────────────────────────────────────────────────

    private fun getRequest(url: String, token: String): Request =
        Request.Builder()
            .url(url)
            .header("PRIVATE-TOKEN", token)
            .build()

    private fun postRequest(url: String, body: String, token: String): Request =
        Request.Builder()
            .url(url)
            .header("PRIVATE-TOKEN", token)
            .post(body.toRequestBody(JSON_MEDIA_TYPE))
            .build()

    private fun putRequest(url: String, body: String, token: String): Request =
        Request.Builder()
            .url(url)
            .header("PRIVATE-TOKEN", token)
            .put(body.toRequestBody(JSON_MEDIA_TYPE))
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
                        "GitLab API error ${response.code} for ${request.url}: $errorBody"
                    )
                }
                return transform(response.body?.string() ?: "")
            }
        } catch (e: PrProviderException) {
            throw e
        } catch (e: Exception) {
            throw PrProviderException("Network error calling GitLab API: ${e.message}", e)
        }
    }

    // ── JSON helpers ──────────────────────────────────────────────────────────

    /**
     * Builds a flat JSON object from the given key-value pairs using
     * [buildJsonObject] to ensure correct escaping of all characters.
     */
    private fun buildJsonPayload(vararg entries: Pair<String, String>): String =
        buildJsonObject { entries.forEach { (k, v) -> put(k, v) } }.toString()

    private companion object {
        @Suppress("unused")
        private val LOG = Logger.getInstance(GitLabProvider::class.java)
        private val JSON_MEDIA_TYPE = "application/json; charset=utf-8".toMediaType()
    }
}
