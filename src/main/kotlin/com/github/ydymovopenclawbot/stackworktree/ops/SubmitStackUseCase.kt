package com.github.ydymovopenclawbot.stackworktree.ops

import com.github.ydymovopenclawbot.stackworktree.git.GitLayer
import com.github.ydymovopenclawbot.stackworktree.pr.PrInfo as PrProviderInfo
import com.github.ydymovopenclawbot.stackworktree.pr.PrProvider
import com.github.ydymovopenclawbot.stackworktree.stack.StackNavTable
import com.github.ydymovopenclawbot.stackworktree.state.BranchNode
import com.github.ydymovopenclawbot.stackworktree.state.PrInfo as StatePrInfo
import com.github.ydymovopenclawbot.stackworktree.state.StackStateStore
import com.intellij.openapi.diagnostic.logger

private val LOG = logger<SubmitStackUseCase>()

/**
 * Orchestrates the "Submit Stack" operation: pushes every non-trunk branch bottom-to-top,
 * creates or updates the corresponding pull request, generates a Markdown stack-navigation
 * table in each PR description, and persists PR metadata back into [StackStateStore].
 *
 * ## Two-pass strategy
 *
 * **Pass 1 (bottom-to-top)** pushes each branch and calls `createPr` / `updatePr`.
 * Because branches higher in the stack haven't been processed yet, their PR links show
 * `_(pending)_` in the navigation table on first pass — this is harmless and is corrected
 * in Pass 2.
 *
 * **Pass 2** refreshes every PR description once all PR URLs are known, so the final
 * published tables contain fully resolved hyperlinks throughout.
 *
 * ## Idempotency / no duplicates
 *
 * Existing PR info is looked up in priority order:
 * 1. The `prInfo.id` stored in [StackStateStore] (fastest — no network call).
 * 2. The hosting service's `findPrByBranch` API (handles PRs created outside the plugin or
 *    after a state-loss event).
 * 3. `createPr` — only called when neither of the above found an existing PR.
 *
 * ## Threading
 *
 * [execute] performs blocking git and network I/O and **must not** be called from the EDT.
 * Wrap it in a [com.intellij.openapi.progress.Task.Backgroundable].
 *
 * @param gitLayer   Pushes each branch to the remote.
 * @param prProvider Low-level provider for creating / updating PRs on the hosting service.
 * @param stateStore Reads and writes the [com.github.ydymovopenclawbot.stackworktree.state.StackState]
 *                   that holds branch → PR mappings.
 */
class SubmitStackUseCase(
    private val gitLayer: GitLayer,
    private val prProvider: PrProvider,
    private val stateStore: StackStateStore,
) {

    /**
     * Submits the entire stack: pushes all non-trunk branches and creates or updates their
     * pull requests with a Markdown navigation table.
     *
     * @return [SubmitResult] summarising how many PRs were created vs. updated, or
     *   [SubmitResult.NoState] if no stack state has been persisted yet.
     * @throws Exception propagated from [GitLayer.push] or [PrProvider] on the first failure.
     *   The stack is left partially submitted up to the failing branch.
     */
    fun execute(): SubmitResult {
        val state = stateStore.read() ?: run {
            LOG.info("SubmitStackUseCase: no StackState found — nothing to submit")
            return SubmitResult.NoState
        }

        val bfsOrder = StackNavTable.collectBfsOrder(state)
        if (bfsOrder.isEmpty()) {
            LOG.info("SubmitStackUseCase: no non-trunk branches — nothing to submit")
            return SubmitResult.Success(created = 0, updated = 0)
        }

        LOG.info("SubmitStackUseCase: submitting ${bfsOrder.size} branch(es): $bfsOrder")

        val remote         = state.repoConfig.remote
        val updatedBranches = state.branches.toMutableMap()
        var createdCount   = 0
        var updatedCount   = 0

        // ── Pass 1: push + create/update each PR ──────────────────────────────
        for (branch in bfsOrder) {
            val node   = updatedBranches[branch] ?: continue
            val parent = node.parent ?: state.repoConfig.trunk

            // 1a. Push to remote — fail fast if the push is rejected.
            LOG.info("SubmitStackUseCase: pushing '$branch' → remote '$remote'")
            gitLayer.push(branch, remote, forceWithLease = true)

            // 1b. Build a partial nav table (branches above may still show 'pending').
            val partialState = state.copy(branches = updatedBranches.toMap())
            val body         = buildPrBody(StackNavTable.generate(partialState, branch))
            val title        = branch   // use branch name as default title

            // 1c. Did this branch have a known PR before this submit started?
            val hadExistingPr = node.prInfo?.id?.toIntOrNull() != null

            // 1d. Create or update the PR.
            val providerInfo = createOrUpdate(branch, parent, title, body, node)

            if (hadExistingPr) updatedCount++ else createdCount++

            updatedBranches[branch] = node.copy(prInfo = providerInfo.toStatePrInfo())

            LOG.info(
                "SubmitStackUseCase: branch '$branch' — " +
                    "${if (hadExistingPr) "updated" else "created"} PR #${providerInfo.number}"
            )
        }

        // ── Pass 2: refresh all PR bodies now every URL is resolved ───────────
        val finalState = state.copy(branches = updatedBranches.toMap())
        for (branch in bfsOrder) {
            val node = updatedBranches[branch] ?: continue
            val prId = node.prInfo?.id?.toIntOrNull() ?: continue
            val body = buildPrBody(StackNavTable.generate(finalState, branch))
            LOG.info("SubmitStackUseCase: refreshing nav table in PR #$prId for '$branch'")
            prProvider.updatePr(prId, body = body)
        }

        // ── Persist ───────────────────────────────────────────────────────────
        stateStore.write(finalState)
        LOG.info("SubmitStackUseCase: complete — created=$createdCount updated=$updatedCount")

        return SubmitResult.Success(created = createdCount, updated = updatedCount)
    }

    // ── Private helpers ───────────────────────────────────────────────────────

    /**
     * Resolves the correct PR operation (update vs. create) without creating duplicates.
     *
     * Priority:
     * 1. Stored `prInfo.id` in [node] — update that PR directly (no network lookup needed).
     * 2. `findPrByBranch` from the provider — reuse an existing open PR created outside
     *    the plugin or after a state-loss.
     * 3. `createPr` — only when neither of the above finds an existing PR.
     */
    private fun createOrUpdate(
        branch: String,
        base: String,
        title: String,
        body: String,
        node: BranchNode,
    ): PrProviderInfo {
        val storedId = node.prInfo?.id?.toIntOrNull()
        if (storedId != null) {
            return prProvider.updatePr(storedId, title = title, body = body)
        }

        val existing = prProvider.findPrByBranch(branch)
        if (existing != null) {
            return prProvider.updatePr(existing.number, title = title, body = body, base = base)
        }

        return prProvider.createPr(branch, base, title, body)
    }

    /** Wraps [navTable] in a standard `## Stack` Markdown section. */
    private fun buildPrBody(navTable: String): String =
        if (navTable.isBlank()) "" else "## Stack\n\n$navTable\n"

    /**
     * Converts a [com.github.ydymovopenclawbot.stackworktree.pr.PrInfo] from the provider
     * layer into the serialisable [StatePrInfo] stored inside
     * [com.github.ydymovopenclawbot.stackworktree.state.BranchNode].
     *
     * The `provider` name is inferred from the PR URL's domain.
     */
    private fun PrProviderInfo.toStatePrInfo(): StatePrInfo = StatePrInfo(
        provider = when {
            url.contains("github.com", ignoreCase = true) -> "github"
            url.contains("gitlab",    ignoreCase = true)  -> "gitlab"
            else                                          -> "unknown"
        },
        id       = number.toString(),
        url      = url,
        status   = state.name.lowercase(),
        ciStatus = "pending",
    )
}

/** Result of a [SubmitStackUseCase.execute] call. */
sealed class SubmitResult {

    /** All branches were pushed and their PRs created or updated successfully. */
    data class Success(val created: Int, val updated: Int) : SubmitResult() {
        fun summaryMessage(): String = when {
            created == 0 && updated == 0 -> "Stack submitted — no changes needed"
            created > 0 && updated > 0   ->
                "$created PR${if (created == 1) "" else "s"} created, " +
                    "$updated PR${if (updated == 1) "" else "s"} updated"
            created > 0  ->
                "$created PR${if (created == 1) "" else "s"} created"
            else          ->
                "$updated PR${if (updated == 1) "" else "s"} updated"
        }
    }

    /** No [com.github.ydymovopenclawbot.stackworktree.state.StackState] exists yet. */
    object NoState : SubmitResult()
}
