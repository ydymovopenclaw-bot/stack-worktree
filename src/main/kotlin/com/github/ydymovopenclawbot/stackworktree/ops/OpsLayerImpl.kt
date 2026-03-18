package com.github.ydymovopenclawbot.stackworktree.ops

import com.github.ydymovopenclawbot.stackworktree.git.GitLayer
import com.github.ydymovopenclawbot.stackworktree.git.GitLayerImpl
import com.github.ydymovopenclawbot.stackworktree.git.ProcessGitRunner
import com.github.ydymovopenclawbot.stackworktree.git.RebaseResult
import com.github.ydymovopenclawbot.stackworktree.pr.PrProvider
import com.github.ydymovopenclawbot.stackworktree.state.BranchHealth
import com.github.ydymovopenclawbot.stackworktree.state.BranchNode
import com.github.ydymovopenclawbot.stackworktree.state.PluginState
import com.github.ydymovopenclawbot.stackworktree.state.RepoConfig
import com.github.ydymovopenclawbot.stackworktree.state.StackState
import com.github.ydymovopenclawbot.stackworktree.state.StackStateStore
import com.github.ydymovopenclawbot.stackworktree.state.StateLayer
import com.github.ydymovopenclawbot.stackworktree.git.WorktreeException
import com.github.ydymovopenclawbot.stackworktree.state.TrackedBranchNode
import com.github.ydymovopenclawbot.stackworktree.state.stackStateService
import com.github.ydymovopenclawbot.stackworktree.ui.STACK_STATE_TOPIC
import com.github.ydymovopenclawbot.stackworktree.ui.UiLayer
import com.github.ydymovopenclawbot.stackworktree.ui.stackgraph.StackNodeData
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.service
import com.intellij.openapi.diagnostic.logger
import com.intellij.openapi.project.Project
import git4idea.GitUtil

private val LOG = logger<OpsLayerImpl>()

/**
 * Production [OpsLayer] implementation.
 *
 * Track/untrack operations are pure state mutations — no git branches are created
 * or deleted. After every mutation the project message bus fires [STACK_STATE_TOPIC]
 * so that UI subscribers (e.g. StacksTabFactory) refresh automatically.
 *
 * The mutation algorithms are extracted into [Algorithms] so they can be unit-tested
 * without an IntelliJ Platform environment.
 */
@Service(Service.Level.PROJECT)
class OpsLayerImpl(
    private val project: Project,
    private val gitLayerOverride: GitLayer? = null,
    private val stateStoreOverride: StackStateStore? = null,
    private val uiLayerOverride: UiLayer? = null,
    private val stateLayerOverride: StateLayer? = null,
) : OpsLayer {

    companion object {
        fun forProject(project: Project): OpsLayer = OpsLayerImpl(project)
    }

    private fun stateLayer(): StateLayer = stateLayerOverride ?: project.service<StateLayer>()

    private fun gitLayer(): GitLayer = gitLayerOverride ?: GitLayerImpl(project)

    private fun uiLayer(): UiLayer = uiLayerOverride ?: project.service<UiLayer>()

    private fun stateStore(): StackStateStore = stateStoreOverride ?: run {
        val root = repoRoot()
        val path = java.io.File(root.path).toPath()
        com.github.ydymovopenclawbot.stackworktree.state.StateStorage(path, ProcessGitRunner())
    }

    private fun repoRoot() =
        GitUtil.getRepositoryManager(project).repositories.firstOrNull()?.root
            ?: error("No git repository found in project '${project.name}'")

    override fun switchWorktree(worktreePath: String): Unit =
        TODO("switchWorktree not yet implemented")

    override fun syncAll(autoPrune: Boolean): SyncResult {
        val git   = gitLayer()
        val sl    = stateLayer()
        val store = stateStore()
        val ui    = uiLayer()

        // ── Determine trunk / remote ──────────────────────────────────────────
        val pluginState = sl.load()
        val stackState  = store.read()
        val trunk  = stackState?.repoConfig?.trunk  ?: pluginState.trunkBranch ?: "main"
        val remote = stackState?.repoConfig?.remote ?: "origin"

        // ── 1. Fetch ──────────────────────────────────────────────────────────
        try {
            git.fetchRemote(remote)
        } catch (e: WorktreeException) {
            LOG.warn("syncAll: fetch '$remote' failed", e)
            val msg = "Sync failed: could not fetch '$remote': ${e.message}"
            ui.notify(msg)
            return SyncResult(emptyList(), emptyList(), emptyList())
        }

        // ── 2. Detect merged branches ─────────────────────────────────────────
        val mergedOnRemote: Set<String> = try {
            git.getMergedRemoteBranches(remote, trunk)
        } catch (e: WorktreeException) {
            LOG.warn("syncAll: getMergedRemoteBranches failed", e)
            ui.notify("Sync failed: could not detect merged branches: ${e.message}")
            return SyncResult(emptyList(), emptyList(), emptyList())
        }

        // Intersect with branches that are currently tracked by this plugin.
        val trackedInPluginState: Set<String> = pluginState.trackedBranches.keys.toSet() - trunk
        val trackedInStackState:  Set<String> = stackState?.branches?.keys.orEmpty() - trunk
        val allTracked = trackedInPluginState + trackedInStackState
        val mergedTracked: List<String> = allTracked.filter { it in mergedOnRemote }

        // ── 3. Remove merged branches, re-parent their children ───────────────
        val prunedWorktrees = mutableListOf<String>()
        val allWorktrees: List<com.github.ydymovopenclawbot.stackworktree.git.Worktree> =
            runCatching { git.worktreeList() }
                .onFailure { LOG.warn("syncAll: worktreeList failed", it) }
                .getOrDefault(emptyList())

        var updatedPluginState = pluginState
        var updatedStackState  = stackState

        for (merged in mergedTracked) {
            // Remove from PluginState tree (re-parents children automatically).
            if (merged in updatedPluginState.trackedBranches) {
                updatedPluginState = Algorithms.applyUntrackBranch(updatedPluginState, merged)
            }

            // Remove from StackState tree (re-parent children, splice parent's child list).
            updatedStackState = updatedStackState?.let { ss ->
                val mergedNode = ss.branches[merged] ?: return@let ss
                val parentName = mergedNode.parent
                val updated    = ss.branches.toMutableMap()

                updated.remove(merged)

                // Re-parent each child of the merged branch.
                for (child in mergedNode.children) {
                    updated[child] = updated[child]?.copy(parent = parentName) ?: continue
                }

                // Splice the merged node's children into the parent's child list at the
                // same position the merged branch occupied.
                // When parentName is null the merged branch is a direct child of trunk,
                // so the trunk node's children list must be updated instead.
                val spliceTarget = parentName ?: ss.repoConfig.trunk
                val parentNode = updated[spliceTarget]
                if (parentNode != null) {
                    val idx      = parentNode.children.indexOf(merged)
                    val newList  = parentNode.children.toMutableList()
                    if (idx >= 0) newList.removeAt(idx)
                    val insertAt = if (idx >= 0) idx else newList.size
                    newList.addAll(insertAt, mergedNode.children)
                    updated[spliceTarget] = parentNode.copy(children = newList)
                }

                ss.copy(branches = updated)
            }

            // Optionally prune the linked worktree (skip the main worktree).
            if (autoPrune) {
                val wt = allWorktrees.find { it.branch == merged && !it.isMain }
                if (wt != null) {
                    try {
                        git.worktreeRemove(wt.path)
                        prunedWorktrees += wt.path
                        LOG.info("syncAll: pruned worktree for merged branch '$merged': ${wt.path}")
                    } catch (e: WorktreeException) {
                        LOG.warn("syncAll: failed to prune worktree '${wt.path}' for '$merged': ${e.message}")
                    }
                }
            }
        }

        // ── 4. Recalculate ahead/behind for remaining tracked branches ─────────
        // Use the parent recorded in PluginState; fall back to trunk when unknown.
        val remainingTracked: Set<String> =
            updatedPluginState.trackedBranches.keys.toSet() - trunk

        val branchStatuses: List<BranchStatus> = remainingTracked.mapNotNull { branch ->
            val parent = updatedPluginState.trackedBranches[branch]?.parentName ?: trunk
            runCatching { git.aheadBehind(branch, parent) }
                .onFailure { LOG.warn("syncAll: aheadBehind('$branch', '$parent') failed: ${it.message}") }
                .getOrNull()
                ?.let { ab -> BranchStatus(branch, ab.ahead, ab.behind) }
        }

        // ── 5. Persist ────────────────────────────────────────────────────────
        sl.save(updatedPluginState)
        var stateWriteFailed = false
        if (updatedStackState != null) {
            runCatching { store.write(updatedStackState) }
                .onFailure {
                    stateWriteFailed = true
                    LOG.warn("syncAll: failed to write StackState: ${it.message}", it)
                }
        }

        // ── 6. Notify & refresh ───────────────────────────────────────────────
        val result = SyncResult(mergedTracked, prunedWorktrees, branchStatuses)
        val message = if (stateWriteFailed) {
            result.summaryMessage() + " (warning: state persistence failed — changes may not survive restart)"
        } else {
            result.summaryMessage()
        }
        ui.notify(message)
        ui.refresh()

        LOG.info(
            "syncAll: complete — ${mergedTracked.size} merged, " +
                "${branchStatuses.count { it.behindCount > 0 }} need rebase, " +
                "${prunedWorktrees.size} worktrees pruned"
        )
        return result
    }

    override fun pruneStale(): Unit =
        TODO("pruneStale not yet implemented")

    override fun submitStack(): SubmitResult {
        val git      = gitLayer()
        val store    = stateStore()
        val provider = project.service<PrProvider>()
        val ui       = uiLayer()

        val useCase = SubmitStackUseCase(git, provider, store)
        val result  = useCase.execute()

        when (result) {
            is SubmitResult.Success -> {
                ui.notify(result.summaryMessage())
                ui.refresh()
            }
            is SubmitResult.NoState ->
                ui.notify("Submit Stack: no stack state found. Track some branches first.")
        }

        return result
    }

    override fun insertBranchAbove(targetBranch: String, newBranchName: String) {
        val git = gitLayer()
        val store = stateStore()

        val originalState = readOrInit(store, targetBranch)
        requireBranchAbsent(git, originalState, newBranchName)

        val targetNode = originalState.branches[targetBranch]
            ?: BranchNode(name = targetBranch, parent = null)
        val oldParent: String? = targetNode.parent

        val createBase = oldParent ?: targetBranch
        LOG.info("insertBranchAbove: creating '$newBranchName' from '$createBase'")
        git.createBranch(newBranchName, createBase)

        val updatedState = buildStateForInsertAbove(originalState, targetBranch, newBranchName, oldParent)

        if (oldParent != null) {
            val oldTargetTip = git.resolveCommit(targetBranch)

            LOG.info("insertBranchAbove: rebasing '$targetBranch' --onto '$newBranchName' (upstream '$oldParent')")
            when (val r = git.rebaseOnto(targetBranch, newBranchName, oldParent)) {
                is RebaseResult.Success -> Unit
                is RebaseResult.Aborted -> {
                    LOG.warn("insertBranchAbove: rebase aborted — rolling back. Reason: ${r.reason}")
                    rollbackBranchCreation(git, newBranchName)
                    return
                }
            }

            val cascadeResult = rebaseDescendants(git, updatedState, targetBranch, oldTargetTip)
            if (cascadeResult is CascadeResult.Aborted) {
                val deleteOk = rollbackBranchCreation(git, newBranchName)
                val resetOk = runCatching { git.resetBranch(targetBranch, oldTargetTip) }.isSuccess
                if (!deleteOk || !resetOk) {
                    LOG.warn(
                        "rollback: partial failure (deleteNewBranch=$deleteOk, resetTarget=$resetOk)" +
                            " — repository may need manual cleanup"
                    )
                }
                return
            }
        }

        store.write(updatedState)
        LOG.info("insertBranchAbove: done — '$newBranchName' inserted above '$targetBranch'")
    }

    override fun insertBranchBelow(targetBranch: String, newBranchName: String) {
        val git = gitLayer()
        val store = stateStore()

        val originalState = readOrInit(store, targetBranch)
        requireBranchAbsent(git, originalState, newBranchName)

        val children: List<String> = originalState.branches[targetBranch]?.children ?: emptyList()

        LOG.info("insertBranchBelow: creating '$newBranchName' from '$targetBranch'")
        git.createBranch(newBranchName, targetBranch)

        val updatedState = buildStateForInsertBelow(originalState, targetBranch, newBranchName, children)

        data class Rebased(val branch: String, val oldTip: String)
        val rebased = mutableListOf<Rebased>()

        for (child in children) {
            val oldChildTip = git.resolveCommit(child)
            LOG.info("insertBranchBelow: rebasing '$child' --onto '$newBranchName' (upstream '$targetBranch')")
            when (val r = git.rebaseOnto(child, newBranchName, targetBranch)) {
                is RebaseResult.Success -> rebased.add(Rebased(child, oldChildTip))
                is RebaseResult.Aborted -> {
                    LOG.warn("insertBranchBelow: rebase of '$child' aborted — rolling back. Reason: ${r.reason}")
                    for (done in rebased.asReversed()) {
                        runCatching { git.resetBranch(done.branch, done.oldTip) }
                            .onFailure {
                                LOG.warn("rollback: could not restore '${done.branch}' — manual cleanup may be needed: ${it.message}")
                            }
                    }
                    rollbackBranchCreation(git, newBranchName)
                    return
                }
            }
        }

        store.write(updatedState)
        LOG.info("insertBranchBelow: done — '$newBranchName' inserted below '$targetBranch'")
    }

    override fun addBranchToStack(
        parentNode: StackNodeData,
        newBranch: String,
        commitMessage: String?,
        createWorktree: Boolean,
        worktreePath: String?,
    ): StackNodeData {
        val git = gitLayer()
        val store = stateStore()

        if (createWorktree) {
            requireNotNull(worktreePath) { "worktreePath must be non-null when createWorktree=true" }
            git.worktreeAdd(worktreePath, newBranch)
        } else {
            git.checkoutNewBranch(newBranch)
        }

        if (!commitMessage.isNullOrBlank()) {
            git.stageAll()
            git.commit(commitMessage)
        }

        project.stackStateService().recordBranch(
            branch       = newBranch,
            parentBranch = parentNode.branchName,
            worktreePath = worktreePath,
        )

        persistToStateStorage(store, newBranch, parentNode.branchName, worktreePath)

        return StackNodeData(
            id         = newBranch,
            branchName = newBranch,
            parentId   = parentNode.id,
        )
    }

    // ── Track / Untrack ───────────────────────────────────────────────────────

    override fun trackBranch(branch: String, parentBranch: String) {
        val sl = stateLayer()
        sl.save(Algorithms.applyTrackBranch(sl.load(), branch, parentBranch))
        notifyStateChanged()
    }

    override fun untrackBranch(branch: String) {
        val sl = stateLayer()
        sl.save(Algorithms.applyUntrackBranch(sl.load(), branch))
        notifyStateChanged()
    }

    override fun rebaseOntoParent(branch: String) {
        val git   = gitLayer()
        val store = stateStore()
        val ui    = uiLayer()

        val currentState = store.read()
            ?: error("No stack state found for project '${project.name}'")
        val branchNode = currentState.branches[branch]
            ?: error("Branch '$branch' is not tracked in the stack state")
        val parent = branchNode.parent
            ?: error("Branch '$branch' has no parent — it is the root of the stack")

        LOG.info("rebaseOntoParent: rebasing '$branch' onto '$parent'")

        val result = try {
            git.rebaseOnto(branch, parent, parent)
        } catch (e: Exception) {
            LOG.warn("rebaseOntoParent: git rebase of '$branch' onto '$parent' failed: ${e.message}", e)
            ui.notify("Rebase of '$branch' onto '$parent' failed: ${e.message}")
            return
        }

        when (result) {
            is RebaseResult.Success -> {
                // After a clean rebase onto parent, merge-base = tip of parent.
                val newBaseCommit = git.resolveCommit(parent)
                val updatedNode = branchNode.copy(
                    baseCommit = newBaseCommit,
                    health     = BranchHealth.CLEAN,
                )
                store.write(currentState.copy(branches = currentState.branches + (branch to updatedNode)))
                LOG.info("rebaseOntoParent: '$branch' successfully rebased onto '$parent'; baseCommit=$newBaseCommit")
                ui.notify("Rebased '$branch' onto '$parent' successfully")
                ui.refresh()
            }
            is RebaseResult.Aborted -> {
                // Repository left in pre-rebase state; no state written.
                LOG.info("rebaseOntoParent: rebase of '$branch' aborted. Reason: ${result.reason}")
            }
        }
    }

    override fun restackAll(
        onProgress: ((current: Int, total: Int, branchName: String) -> Unit)?,
    ): RestackResult {
        val git   = gitLayer()
        val store = stateStore()
        val state = store.read() ?: return RestackResult.Success(0)
        val trunk = state.repoConfig.trunk

        val bfsOrder = collectBfsOrder(state)
        if (bfsOrder.isEmpty()) return RestackResult.Success(0)

        val total    = bfsOrder.size
        val oldTips  = mutableMapOf<String, String>()
        oldTips[trunk] = git.resolveCommit(trunk)

        val updatedBranches = state.branches.toMutableMap()
        var rebasedCount    = 0

        for ((i, branch) in bfsOrder.withIndex()) {
            onProgress?.invoke(i + 1, total, branch)

            val parentName  = state.branches[branch]?.parent ?: run {
                LOG.warn("restackAll: '$branch' has no parent in state — skipping")
                continue
            }
            val oldParentTip = oldTips[parentName] ?: git.resolveCommit(parentName)
            // Record pre-rebase tip so descendants can use it as upstream.
            oldTips[branch] = git.resolveCommit(branch)

            LOG.info("restackAll: rebasing '$branch' --onto '$parentName' (upstream=$oldParentTip) [${i + 1}/$total]")
            when (val r = git.rebaseOnto(branch, parentName, oldParentTip)) {
                is RebaseResult.Success -> {
                    rebasedCount++
                    val existingNode = updatedBranches[branch] ?: run {
                        LOG.warn("restackAll: '$branch' missing from branches map after rebase — skipping update")
                        continue
                    }
                    updatedBranches[branch] = existingNode.copy(baseCommit = git.resolveCommit(parentName))
                }
                is RebaseResult.Aborted -> {
                    LOG.warn("restackAll: aborted at '$branch' [${i + 1}/$total]. Reason: ${r.reason}")
                    // Persist partial progress — no rollback per acceptance criteria.
                    store.write(state.copy(branches = updatedBranches))
                    uiLayer().refresh()
                    return RestackResult.Aborted(rebasedCount, branch, r.reason)
                }
            }
        }

        store.write(state.copy(branches = updatedBranches))
        uiLayer().refresh()
        uiLayer().notify("Restacked $rebasedCount branch${if (rebasedCount == 1) "" else "es"} successfully")
        LOG.info("restackAll: done — rebased $rebasedCount/$total branches")
        return RestackResult.Success(rebasedCount)
    }

    private fun notifyStateChanged() {
        project.messageBus.syncPublisher(STACK_STATE_TOPIC).stateChanged()
    }

    // ── Pure mutation algorithms (internal for testing) ───────────────────────

    /**
     * Platform-free implementations of the track/untrack algorithms.
     *
     * Exposed as `internal` so [OpsLayerImplTest][com.github.ydymovopenclawbot.stackworktree.ops.OpsLayerImplTest]
     * can exercise every code path — including edge cases like mid-stack re-parenting —
     * without requiring a live [Project] or IntelliJ Platform test fixtures.
     */
    internal object Algorithms {

        /**
         * Returns a new [PluginState] with [branch] inserted as a child of [parentBranch].
         *
         * @throws IllegalArgumentException if [branch] is already tracked, or if
         *   [parentBranch] is neither the trunk nor an existing tracked branch.
         */
        fun applyTrackBranch(
            current: PluginState,
            branch: String,
            parentBranch: String,
        ): PluginState {
            require(branch !in current.trackedBranches) {
                "'$branch' is already tracked"
            }
            val validParents = buildSet {
                current.trunkBranch?.let { add(it) }
                addAll(current.trackedBranches.keys)
            }
            require(parentBranch in validParents) {
                "'$parentBranch' is not a valid parent (must be trunk or a tracked branch)"
            }

            val updated = current.trackedBranches.toMutableMap()
            updated[branch] = TrackedBranchNode(name = branch, parentName = parentBranch)

            // Append branch to parent's children list (parent may be trunk = not in map).
            val parentNode = updated[parentBranch]
            if (parentNode != null) {
                updated[parentBranch] = parentNode.copy(children = parentNode.children + branch)
            }

            return current.copy(trackedBranches = updated)
        }

        /**
         * Returns a new [PluginState] with [branch] removed from the tracked tree.
         *
         * Children of [branch] are re-parented to [branch]'s own parent, spliced into
         * the parent's children list at the position where [branch] was.
         *
         * @throws IllegalArgumentException if [branch] is not currently tracked.
         */
        fun applyUntrackBranch(current: PluginState, branch: String): PluginState {
            val node = current.trackedBranches[branch]
                ?: throw IllegalArgumentException("'$branch' is not currently tracked")

            val updated = current.trackedBranches.toMutableMap()

            // Re-parent each child to this node's parent.
            for (childName in node.children) {
                val child = updated[childName] ?: continue
                updated[childName] = child.copy(parentName = node.parentName)
            }

            // Splice node's children into its parent's children list at the removed node's
            // position. If the parent is trunk (not in the map) there is no list to update —
            // the re-parented children already carry the correct parentName.
            val parentName = node.parentName
            val parentNode = if (parentName != null) updated[parentName] else null
            if (parentNode != null && parentName != null) {
                val idx = parentNode.children.indexOf(branch)
                val newChildren = parentNode.children.toMutableList()
                if (idx >= 0) newChildren.removeAt(idx)
                val insertAt = if (idx >= 0) idx else newChildren.size
                newChildren.addAll(insertAt, node.children)
                updated[parentName] = parentNode.copy(children = newChildren)
            }

            updated.remove(branch)
            return current.copy(trackedBranches = updated)
        }
    }

    // ── Rebase helpers ────────────────────────────────────────────────────────

    /**
     * Returns all non-trunk branches in BFS (parent-before-child) order, suitable for
     * driving the cascade in [restackAll].
     */
    private fun collectBfsOrder(state: StackState): List<String> {
        val trunk  = state.repoConfig.trunk
        val result = mutableListOf<String>()
        val queue  = ArrayDeque(state.branches[trunk]?.children ?: emptyList())
        while (queue.isNotEmpty()) {
            val branch = queue.removeFirst()
            result.add(branch)
            queue.addAll(state.branches[branch]?.children ?: emptyList())
        }
        return result
    }

    private fun rebaseDescendants(
        git: GitLayer,
        state: StackState,
        rootBranch: String,
        oldRootTip: String,
    ): CascadeResult {
        val oldTips = mutableMapOf(rootBranch to oldRootTip)

        data class Rebased(val branch: String, val oldTip: String)
        val rebased = mutableListOf<Rebased>()

        val queue = ArrayDeque(state.branches[rootBranch]?.children ?: emptyList())
        while (queue.isNotEmpty()) {
            val child = queue.removeFirst()
            val childParent = state.branches[child]?.parent ?: continue
            val oldParentTip = oldTips[childParent] ?: run {
                LOG.warn("rebaseDescendants: no pre-rebase tip for '$childParent' — skipping '$child'")
                continue
            }

            val oldChildTip = git.resolveCommit(child)
            LOG.info("rebaseDescendants: rebasing '$child' --onto '$childParent' (upstream was $oldParentTip)")
            when (val r = git.rebaseOnto(child, childParent, oldParentTip)) {
                is RebaseResult.Success -> {
                    oldTips[child] = oldChildTip
                    rebased.add(Rebased(child, oldChildTip))
                    queue.addAll(state.branches[child]?.children ?: emptyList())
                }
                is RebaseResult.Aborted -> {
                    LOG.warn("rebaseDescendants: cascade rebase of '$child' aborted. Reason: ${r.reason}")
                    for (done in rebased.asReversed()) {
                        runCatching { git.resetBranch(done.branch, done.oldTip) }
                            .onFailure {
                                LOG.warn("rollback: could not restore '${done.branch}' — manual cleanup may be needed: ${it.message}")
                            }
                    }
                    return CascadeResult.Aborted
                }
            }
        }
        return CascadeResult.Success
    }

    private sealed class CascadeResult {
        object Success : CascadeResult()
        object Aborted : CascadeResult()
    }

    private fun rollbackBranchCreation(git: GitLayer, branchName: String): Boolean {
        return try {
            git.deleteBranch(branchName)
            LOG.info("rollback: deleted '$branchName'")
            true
        } catch (e: Exception) {
            LOG.warn("rollback: could not delete '$branchName': ${e.message}")
            false
        }
    }

    private fun readOrInit(store: StackStateStore, seedBranch: String): StackState =
        store.read() ?: StackState(
            repoConfig = RepoConfig(trunk = seedBranch, remote = "origin"),
            branches = mapOf(seedBranch to BranchNode(name = seedBranch, parent = null)),
        )

    private fun requireBranchAbsent(git: GitLayer, state: StackState, branchName: String) {
        require(branchName !in state.branches) {
            "Branch '$branchName' already exists in the stack state"
        }
        require(!git.branchExists(branchName)) {
            "Branch '$branchName' already exists as a local git branch"
        }
    }

    private fun persistToStateStorage(
        store: StackStateStore,
        newBranch: String,
        parentBranch: String,
        worktreePath: String?,
    ) {
        val current = store.read()

        val newBranchNode = BranchNode(name = newBranch, parent = parentBranch, worktreePath = worktreePath)
        val updatedParent = current?.branches?.get(parentBranch)
            ?.let { it.copy(children = it.children + newBranch) }

        val baseBranches = current?.branches ?: emptyMap()
        val newBranches = buildMap {
            putAll(baseBranches)
            put(newBranch, newBranchNode)
            if (updatedParent != null) put(parentBranch, updatedParent)
            if (parentBranch !in baseBranches) {
                put(parentBranch, BranchNode(name = parentBranch, parent = null))
            }
        }

        val newState = current?.copy(branches = newBranches)
            ?: StackState(
                repoConfig = RepoConfig(trunk = parentBranch, remote = "origin"),
                branches   = newBranches,
            )

        store.write(newState)
    }
}
