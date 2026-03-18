package com.github.ydymovopenclawbot.stackworktree.pr

import com.intellij.ide.BrowserUtil
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.service
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.project.Project
import com.github.ydymovopenclawbot.stackworktree.pr.ChecksState
import com.github.ydymovopenclawbot.stackworktree.pr.ReviewState

/**
 * Default [PrLayer] implementation that delegates all host operations to the active
 * [PrProvider] project service (currently [com.github.ydymovopenclawbot.stackworktree.pr.github.GitHubProvider]).
 *
 * UI code that just needs to look up or open a PR should interact only with [PrLayer];
 * code that needs finer-grained PR management (create, update, status) should inject
 * [PrProvider] directly.
 */
@Service(Service.Level.PROJECT)
class DefaultPrLayer(private val project: Project) : PrLayer {

    private val provider: PrProvider get() = project.service<PrProvider>()

    override fun findPr(branch: String): PrInfo? {
        return try {
            provider.findPrByBranch(branch)
        } catch (e: PrProviderException) {
            LOG.warn("Failed to look up PR for branch '$branch': ${e.message}")
            null
        }
    }

    override fun getPrStatus(branch: String): PrStatus? {
        val pr = findPr(branch) ?: return null
        return try {
            provider.getPrStatus(pr.number)
        } catch (e: PrProviderException) {
            LOG.warn("Failed to get PR status for branch '$branch' (PR #${pr.number}): ${e.message}")
            // Degrade gracefully: return the PR info we already have with no CI/review data
            PrStatus(prInfo = pr, checksState = ChecksState.NONE, reviewState = ReviewState.NONE)
        }
    }

    override fun openPr(branch: String) {
        val pr = findPr(branch) ?: run {
            LOG.info("openPr: no PR found for branch '$branch'")
            return
        }
        BrowserUtil.browse(pr.url)
    }

    private companion object {
        private val LOG = Logger.getInstance(DefaultPrLayer::class.java)
    }
}
