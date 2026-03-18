package com.github.ydymovopenclawbot.stackworktree.pr

import com.github.ydymovopenclawbot.stackworktree.pr.github.GitHubProvider
import com.github.ydymovopenclawbot.stackworktree.pr.github.GitHubRepoInfo
import com.github.ydymovopenclawbot.stackworktree.pr.gitlab.GitLabProvider
import com.github.ydymovopenclawbot.stackworktree.pr.gitlab.GitLabRepoInfo
import com.github.ydymovopenclawbot.stackworktree.pr.gitlab.GitLabTokenStore
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.service
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.project.Project

/**
 * [PrProvider] implementation that auto-detects the hosting platform (GitLab or GitHub)
 * from the project's git remotes and delegates all operations to the appropriate
 * platform-specific provider.
 *
 * Detection order:
 * 1. **GitLab** — if any remote URL is recognised as a GitLab URL (hostname contains
 *    "gitlab", or matches [gitLabHostOverride] if set), a [GitLabProvider] is used.
 * 2. **GitHub** — if any remote URL is recognised as a GitHub URL (hostname contains
 *    "github"), a [GitHubProvider] is used.
 * 3. Otherwise — a [PrProviderException] is thrown.
 *
 * The detected provider is cached after the first successful resolution so subsequent
 * calls pay no detection cost.
 *
 * @param gitLabHostOverride  When non-null, forces GitLab detection for a self-hosted
 *   instance whose domain name does not contain the word "gitlab".
 *
 *   **Note**: The IntelliJ service container always passes only [Project] when constructing
 *   this service, so [gitLabHostOverride] is always `null` at runtime.  It exists solely for
 *   programmatic construction in tests.
 *   TODO(S6.x): wire this from a persisted settings object (e.g. PropertiesComponent) once
 *   a configuration UI for self-hosted GitLab instances is available.
 */
@Service(Service.Level.PROJECT)
class AutodetectPrProvider(
    private val project: Project,
    private val gitLabHostOverride: String? = null,
) : PrProvider {

    private val delegate: PrProvider by lazy { resolveProvider() }

    // ── PrProvider ────────────────────────────────────────────────────────────

    override fun createPr(branch: String, base: String, title: String, body: String): PrInfo =
        delegate.createPr(branch, base, title, body)

    override fun updatePr(prId: Int, title: String?, body: String?, base: String?): PrInfo =
        delegate.updatePr(prId, title, body, base)

    override fun getPrStatus(prId: Int): PrStatus =
        delegate.getPrStatus(prId)

    override fun closePr(prId: Int) =
        delegate.closePr(prId)

    override fun findPrByBranch(branch: String): PrInfo? =
        delegate.findPrByBranch(branch)

    // ── Detection ─────────────────────────────────────────────────────────────

    private fun resolveProvider(): PrProvider {
        // GitLab is checked first so that self-hosted instances (even those whose hostname
        // happens to also contain "github") are handled correctly when an override is set.
        val gitLabInfo = GitLabRepoInfo.fromProject(project, gitLabHostOverride)
        if (gitLabInfo != null) {
            LOG.info("AutodetectPrProvider: using GitLabProvider for host '${gitLabInfo.host}'")
            val tokenStore = service<GitLabTokenStore>()
            return GitLabProvider(project, gitLabInfo, tokenStore)
        }

        val gitHubInfo = GitHubRepoInfo.fromProject(project)
        if (gitHubInfo != null) {
            LOG.info("AutodetectPrProvider: using GitHubProvider for '${gitHubInfo.apiBaseUrl}'")
            return GitHubProvider(project)
        }

        throw PrProviderException(
            "Could not detect a supported git hosting service (GitLab or GitHub) from the " +
                "project's git remotes. Ensure the 'origin' remote points to a GitLab or " +
                "GitHub repository."
        )
    }

    private companion object {
        private val LOG = Logger.getInstance(AutodetectPrProvider::class.java)
    }
}
