package com.github.ydymovopenclawbot.stackworktree.pr.gitlab

import com.github.ydymovopenclawbot.stackworktree.pr.PrInfo
import com.github.ydymovopenclawbot.stackworktree.pr.PrProvider
import com.github.ydymovopenclawbot.stackworktree.pr.PrProviderException
import com.github.ydymovopenclawbot.stackworktree.pr.PrStatus
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.Messages

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

    // ── PrProvider ────────────────────────────────────────────────────────────

    override fun createPr(branch: String, base: String, title: String, body: String): PrInfo {
        LOG.info("GitLabProvider: createPr source='$branch' target='$base'")
        return http().createPr(branch, base, title, body)
    }

    override fun updatePr(prId: Int, title: String?, body: String?, base: String?): PrInfo {
        LOG.info("GitLabProvider: updatePr #$prId title=$title base=$base")
        return http().updatePr(prId, title, body, base)
    }

    override fun getPrStatus(prId: Int): PrStatus {
        LOG.info("GitLabProvider: getPrStatus #$prId")
        return http().getPrStatus(prId)
    }

    override fun closePr(prId: Int) {
        LOG.info("GitLabProvider: closePr #$prId")
        http().closePr(prId)
    }

    override fun findPrByBranch(branch: String): PrInfo? {
        LOG.info("GitLabProvider: findPrByBranch '$branch'")
        return http().findPrByBranch(branch)
    }

    // ── HTTP client construction ───────────────────────────────────────────────

    /**
     * Constructs a [GitLabHttpClient] using the resolved PAT and the shared [OkHttpClient]
     * singleton.  Token resolution may prompt the user on the first call if no PAT is stored.
     */
    private fun http(): GitLabHttpClient =
        GitLabHttpClient(repoInfo, resolveToken())

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
        LOG.info("GitLabProvider: PAT stored for host '${repoInfo.host}'")
        return pat
    }

    private companion object {
        private val LOG = Logger.getInstance(GitLabProvider::class.java)
    }
}
