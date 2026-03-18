package com.github.ydymovopenclawbot.stackworktree.pr

import com.intellij.ide.BrowserUtil
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.service
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.project.Project

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
