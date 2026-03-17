package com.github.ydymovopenclawbot.stackworktree.ops

import com.github.ydymovopenclawbot.stackworktree.git.GitLayer
import com.github.ydymovopenclawbot.stackworktree.git.GitLayerImpl
import com.github.ydymovopenclawbot.stackworktree.git.ProcessGitRunner
import com.github.ydymovopenclawbot.stackworktree.git.RebaseResult
import com.github.ydymovopenclawbot.stackworktree.state.BranchNode
import com.github.ydymovopenclawbot.stackworktree.state.RepoConfig
import com.github.ydymovopenclawbot.stackworktree.state.StackState
import com.github.ydymovopenclawbot.stackworktree.state.StackStateStore
import com.github.ydymovopenclawbot.stackworktree.state.stackStateService
import com.github.ydymovopenclawbot.stackworktree.ui.stackgraph.StackNodeData
import com.intellij.openapi.diagnostic.logger
import com.intellij.openapi.project.Project
import git4idea.GitUtil

private val LOG = logger<OpsLayerImpl>()

/**
 * Production [OpsLayer] that coordinates [GitLayer] and [StackStateStore] to implement
 * higher-level stack operations.
 *
 * The [gitLayerOverride] and [stateStoreOverride] parameters allow injecting test
 * doubles without requiring a live IntelliJ project.  When `null` (the default) the
 * dependencies are resolved from the first available git repository inside [project].
 */
class OpsLayerImpl(
    private val project: Project,
    private val gitLayerOverride: GitLayer? = null,
    private val stateStoreOverride: StackStateStore? = null,
) : OpsLayer {

    companion object {
        /** Creates a production [OpsLayer] wired to the first git repository in [project]. */
        fun forProject(project: Project): OpsLayer = OpsLayerImpl(project)
    }

    // -------------------------------------------------------------------------
    // Lazy dependencies (re-resolved on every call unless overridden)
    // -------------------------------------------------------------------------

    private fun gitLayer(): GitLayer = gitLayerOverride ?: run {
        val root = repoRoot()
        GitLayerImpl(project, root)
    }

    private fun stateStore(): StackStateStore = stateStoreOverride ?: run {
        val root = repoRoot()
        val path = java.io.File(root.path).toPath()
        com.github.ydymovopenclawbot.stackworktree.state.StateStorage(path, ProcessGitRunner())
    }

    private fun repoRoot() =
        GitUtil.getRepositoryManager(project).repositories.firstOrNull()?.root
            ?: error("No git repository found in project '${project.name}'")

    // -------------------------------------------------------------------------
    // OpsLayer stubs (not in scope for this task)
    // -------------------------------------------------------------------------

    override fun switchWorktree(worktreePath: String): Unit =
        TODO("switchWorktree not yet implemented")

    override fun syncAll(): Unit =
        TODO("syncAll not yet implemented")

    override fun pruneStale(): Unit =
        TODO("pruneStale not yet implemented")

    // -------------------------------------------------------------------------
    // Insert above
    // -------------------------------------------------------------------------

    /**
     * Inserts [newBranchName] between [targetBranch] and its parent.
     *
     * ```
     * Before:  … → parent → targetBranch → children…
     * After:   … → parent → newBranchName → targetBranch → children…
     * ```
     *
     * Algorithm:
     * 1. Read current [StackState] (initialise a minimal one if absent).
     * 2. Validate [newBranchName] is absent from both stack state and local git.
     * 3. Create [newBranchName] from [targetBranch]'s parent tip (or its own tip when root).
     * 4. Save [targetBranch]'s pre-rebase SHA, then rebase [targetBranch] onto [newBranchName].
     * 5. Cascade-rebase descendants in BFS order using pre-rebase tip SHAs as upstreams.
     * 6. On any abort: best-effort rollback of rebased branches, reset [targetBranch], delete [newBranchName].
     * 7. On full success: persist updated state.
     */
    override fun insertBranchAbove(targetBranch: String, newBranchName: String) {
        val git = gitLayer()
        val store = stateStore()

        val originalState = readOrInit(store, targetBranch)
        requireBranchAbsent(git, originalState, newBranchName)

        val targetNode = originalState.branches[targetBranch]
            ?: BranchNode(name = targetBranch, parent = null)
        val oldParent: String? = targetNode.parent

        // Create newBranchName from oldParent's tip (or targetBranch itself when it is root).
        val createBase = oldParent ?: targetBranch
        LOG.info("insertBranchAbove: creating '$newBranchName' from '$createBase'")
        git.createBranch(newBranchName, createBase)

        val updatedState = buildStateForInsertAbove(originalState, targetBranch, newBranchName, oldParent)

        // When targetBranch is the root (no parent) both branches share the same commit,
        // so no rebase is needed — just persist the updated state graph.
        if (oldParent != null) {
            // Capture targetBranch's SHA *before* rebasing — used as upstream for its children.
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

            // Cascade: rebase descendants of targetBranch in BFS order.
            val cascadeResult = rebaseDescendants(git, updatedState, targetBranch, oldTargetTip)
            if (cascadeResult is CascadeResult.Aborted) {
                // Children have been rolled back by rebaseDescendants; clean up the two
                // remaining mutations from this operation: newBranchName and targetBranch.
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

    // -------------------------------------------------------------------------
    // Insert below
    // -------------------------------------------------------------------------

    /**
     * Inserts [newBranchName] between [targetBranch] and all its current children.
     *
     * ```
     * Before:  … → targetBranch → C1, C2, …
     * After:   … → targetBranch → newBranchName → C1, C2, …
     * ```
     *
     * Algorithm:
     * 1. Read current [StackState].
     * 2. Validate [newBranchName] is absent from both stack state and local git.
     * 3. Create [newBranchName] at [targetBranch]'s tip.
     * 4. For each direct child C: capture its pre-rebase SHA, then rebase C onto [newBranchName].
     * 5. On abort: best-effort `resetBranch` for already-moved children, then delete [newBranchName].
     * 6. On full success: persist updated state.
     */
    override fun insertBranchBelow(targetBranch: String, newBranchName: String) {
        val git = gitLayer()
        val store = stateStore()

        val originalState = readOrInit(store, targetBranch)
        requireBranchAbsent(git, originalState, newBranchName)

        val children: List<String> = originalState.branches[targetBranch]?.children ?: emptyList()

        LOG.info("insertBranchBelow: creating '$newBranchName' from '$targetBranch'")
        git.createBranch(newBranchName, targetBranch)

        val updatedState = buildStateForInsertBelow(originalState, targetBranch, newBranchName, children)

        // Rebase each existing child onto newBranchName.
        // Track completed rebases (branch → pre-rebase SHA) for best-effort rollback.
        data class Rebased(val branch: String, val oldTip: String)
        val rebased = mutableListOf<Rebased>()

        for (child in children) {
            val oldChildTip = git.resolveCommit(child)
            LOG.info("insertBranchBelow: rebasing '$child' --onto '$newBranchName' (upstream '$targetBranch')")
            when (val r = git.rebaseOnto(child, newBranchName, targetBranch)) {
                is RebaseResult.Success -> rebased.add(Rebased(child, oldChildTip))
                is RebaseResult.Aborted -> {
                    LOG.warn("insertBranchBelow: rebase of '$child' aborted — rolling back. Reason: ${r.reason}")
                    // Best-effort: force-reset already-moved children back to their pre-rebase tips.
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

    // -------------------------------------------------------------------------
    // Add branch to stack
    // -------------------------------------------------------------------------

    /**
     * Creates a new branch on top of [parentNode] and registers it in the stack graph.
     *
     * Sequence:
     * 1. `git checkout -b <newBranch>` **or** `git worktree add <worktreePath> <newBranch>`
     *    depending on [createWorktree].
     * 2. If [commitMessage] is non-blank: `git add -A && git commit -m <commitMessage>`.
     * 3. Records branch→parent (and optional worktree path) in [StackStateService]
     *    for lightweight, restart-safe lookups.
     * 4. Writes the updated [StackState] to `refs/stacktree/state` via [StateStorage]
     *    so [com.github.ydymovopenclawbot.stackworktree.ui.StacksTabFactory.performRefresh]
     *    displays the new branch with accurate ahead/behind counts on the next refresh.
     * 5. Returns a [StackNodeData] representing the new node.
     *
     * @throws com.github.ydymovopenclawbot.stackworktree.git.BranchOperationException
     *   if a git command fails (e.g. branch already exists, nothing to commit).
     */
    override fun addBranchToStack(
        parentNode: StackNodeData,
        newBranch: String,
        commitMessage: String?,
        createWorktree: Boolean,
        worktreePath: String?,
    ): StackNodeData {
        val git = gitLayer()
        val store = stateStore()

        // Step 1 — create branch
        if (createWorktree) {
            requireNotNull(worktreePath) { "worktreePath must be non-null when createWorktree=true" }
            git.worktreeAdd(worktreePath, newBranch)
        } else {
            git.checkoutNewBranch(newBranch)
        }

        // Step 2 — optional commit
        if (!commitMessage.isNullOrBlank()) {
            git.stageAll()
            git.commit(commitMessage)
        }

        // Step 3 — XML persistence (quick lookup, survives IDE restart)
        project.stackStateService().recordBranch(
            branch       = newBranch,
            parentBranch = parentNode.branchName,
            worktreePath = worktreePath,
        )

        // Step 4 — git-object persistence (feeds the full refresh pipeline)
        persistToStateStorage(store, newBranch, parentNode.branchName, worktreePath)

        // Step 5 — return view-model for the new node
        return StackNodeData(
            id         = newBranch,
            branchName = newBranch,
            parentId   = parentNode.id,
        )
    }

    // -------------------------------------------------------------------------
    // Rebase cascade
    // -------------------------------------------------------------------------

    /**
     * Rebases all descendants of [rootBranch] (BFS order) after [rootBranch] has been
     * moved by the "insert above" operation.
     *
     * The critical correctness detail: each `git rebase --onto <newParent> <upstream> <child>`
     * must supply the *pre-rebase* SHA of `<child>`'s parent as `<upstream>`, so that only
     * the child's own commits (not its parent's) are replayed.  These SHAs are captured
     * before each rebase and stored in [oldTips] for use by deeper levels.
     *
     * Rollback of the two outer mutations ([rootBranch] itself and the newly created branch)
     * is the **caller's** responsibility; this function only rolls back the children it has
     * already moved.
     *
     * @param oldRootTip SHA of [rootBranch] **before** it was rebased. Seeded into [oldTips]
     *                   so first-level children can supply the correct upstream.
     * @return [CascadeResult.Success] on full success; [CascadeResult.Aborted] if any rebase
     *         was aborted (best-effort child rollback is performed before returning).
     */
    private fun rebaseDescendants(
        git: GitLayer,
        state: StackState,
        rootBranch: String,
        oldRootTip: String,
    ): CascadeResult {
        // Maps branchName → tip SHA captured before that branch was rebased.
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

            // Save child's tip before rebasing so its own children can use it as upstream.
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
                    // Best-effort: force-reset already-moved children back to their pre-rebase SHAs.
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

    /** Result of a [rebaseDescendants] call. */
    private sealed class CascadeResult {
        object Success : CascadeResult()
        object Aborted : CascadeResult()
    }

    // -------------------------------------------------------------------------
    // Rollback helpers
    // -------------------------------------------------------------------------

    /**
     * Force-deletes [branchName] created during the operation (best-effort).
     *
     * @return `true` if the deletion succeeded; `false` if it failed (already logged).
     */
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

    // -------------------------------------------------------------------------
    // State builders
    // -------------------------------------------------------------------------

    /** Builds the updated [StackState] for an "insert above" operation. */
    private fun buildStateForInsertAbove(
        original: StackState,
        targetBranch: String,
        newBranchName: String,
        oldParent: String?,
    ): StackState {
        val newNode = BranchNode(name = newBranchName, parent = oldParent, children = listOf(targetBranch))
        val updatedTarget = (original.branches[targetBranch] ?: BranchNode(name = targetBranch, parent = null))
            .copy(parent = newBranchName)
        val updatedOldParent = oldParent?.let { p ->
            original.branches[p]?.let { node ->
                node.copy(children = node.children.map { if (it == targetBranch) newBranchName else it })
            }
        }

        return original.copy(
            branches = buildMap {
                putAll(original.branches)
                put(newBranchName, newNode)
                put(targetBranch, updatedTarget)
                if (updatedOldParent != null && oldParent != null) put(oldParent, updatedOldParent)
            }
        )
    }

    /** Builds the updated [StackState] for an "insert below" operation. */
    private fun buildStateForInsertBelow(
        original: StackState,
        targetBranch: String,
        newBranchName: String,
        children: List<String>,
    ): StackState {
        val newNode = BranchNode(name = newBranchName, parent = targetBranch, children = children)
        val updatedTarget = (original.branches[targetBranch] ?: BranchNode(name = targetBranch, parent = null))
            .copy(children = listOf(newBranchName))
        val updatedChildren = children.mapNotNull { child ->
            original.branches[child]?.copy(parent = newBranchName)?.let { child to it }
        }.toMap()

        return original.copy(
            branches = buildMap {
                putAll(original.branches)
                put(newBranchName, newNode)
                put(targetBranch, updatedTarget)
                putAll(updatedChildren)
            }
        )
    }

    // -------------------------------------------------------------------------
    // Utility
    // -------------------------------------------------------------------------

    /** Returns the stored [StackState], or a minimal one seeded with [seedBranch] as trunk. */
    private fun readOrInit(store: StackStateStore, seedBranch: String): StackState =
        store.read() ?: StackState(
            repoConfig = RepoConfig(trunk = seedBranch, remote = "origin"),
            branches = mapOf(seedBranch to BranchNode(name = seedBranch, parent = null)),
        )

    /**
     * Validates that [branchName] is absent from both the stack state **and** local git,
     * producing a user-friendly [IllegalArgumentException] before any git operation runs.
     *
     * Checking git directly avoids a confusing [WorktreeCommandException] when the branch
     * exists in git but is not yet tracked in the stack state.
     */
    private fun requireBranchAbsent(git: GitLayer, state: StackState, branchName: String) {
        require(branchName !in state.branches) {
            "Branch '$branchName' already exists in the stack state"
        }
        require(!git.branchExists(branchName)) {
            "Branch '$branchName' already exists as a local git branch"
        }
    }

    /**
     * Writes the new branch relationship to `refs/stacktree/state`.
     *
     * If no state exists yet, bootstraps a minimal [StackState] with [parentBranch]
     * as the trunk.  Also updates the parent's [BranchNode.children] list so the
     * graph renders edges correctly.
     */
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
            // Ensure the parent appears in the graph even if it was not previously tracked.
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
