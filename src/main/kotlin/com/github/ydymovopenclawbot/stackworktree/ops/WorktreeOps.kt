package com.github.ydymovopenclawbot.stackworktree.ops

import com.github.ydymovopenclawbot.stackworktree.git.GitLayer
import com.github.ydymovopenclawbot.stackworktree.git.GitLayerImpl
import com.github.ydymovopenclawbot.stackworktree.git.ProcessGitRunner
import com.github.ydymovopenclawbot.stackworktree.git.Worktree
import com.github.ydymovopenclawbot.stackworktree.git.WorktreeAlreadyExistsException
import com.github.ydymovopenclawbot.stackworktree.git.WorktreeException
import com.github.ydymovopenclawbot.stackworktree.git.WorktreeNotFoundException
import com.github.ydymovopenclawbot.stackworktree.state.StackStateService
import com.github.ydymovopenclawbot.stackworktree.state.StackStateStore
import com.github.ydymovopenclawbot.stackworktree.state.StateStorage
import com.github.ydymovopenclawbot.stackworktree.state.stackStateService
import com.intellij.openapi.diagnostic.logger
import com.intellij.openapi.project.Project
import git4idea.GitUtil

private val LOG = logger<WorktreeOps>()

/**
 * Orchestrates worktree creation and removal for *existing* branches already present
 * in the local git repository.
 *
 * Coordinates:
 * - [GitLayer]         — executes `git worktree add / remove / list`
 * - [StackStateStore]  — updates the git-object state (`refs/stacktree/state`)
 * - [StackStateService] — updates the IDE-persistent XML service for fast lookups
 *
 * The `*Override` constructor parameters accept test doubles so the class can be unit-
 * tested without a live [Project].  Pass `null` (the default) for production use.
 *
 * @see OpsLayerImpl.addBranchToStack for creating a *new* branch with an optional worktree.
 */
class WorktreeOps(
    private val project: Project,
    private val gitLayerOverride: GitLayer? = null,
    private val stateStoreOverride: StackStateStore? = null,
    private val stateServiceOverride: StackStateService? = null,
) {

    companion object {
        /** Creates a production [WorktreeOps] wired to the first git repository in [project]. */
        fun forProject(project: Project): WorktreeOps = WorktreeOps(project)
    }

    // ── Dependencies ──────────────────────────────────────────────────────────

    private fun gitLayer(): GitLayer = gitLayerOverride ?: GitLayerImpl.withRoot(project, repoRoot())

    private fun stateStore(): StackStateStore = stateStoreOverride ?: run {
        val path = java.io.File(repoRoot().path).toPath()
        StateStorage(path, ProcessGitRunner())
    }

    private fun stateService(): StackStateService =
        stateServiceOverride ?: project.stackStateService()

    private fun repoRoot() =
        GitUtil.getRepositoryManager(project).repositories.firstOrNull()?.root
            ?: error("No git repository found in project '${project.name}'")

    // ── Public API ────────────────────────────────────────────────────────────

    /**
     * Creates a linked worktree for [branch] (which must already exist locally as a
     * git branch).
     *
     * Steps:
     * 1. Guards against double-binding: if [StackStateService] already records a path
     *    for [branch], throws [IllegalStateException] immediately (no git I/O needed).
     * 2. Resolves the target path: [customPath] when provided, else [defaultWorktreePath].
     * 3. Calls `git worktree add <path> <branch>`.
     * 4. Persists the binding in [StackStateService] (fast XML lookup) and in the
     *    git-object [StackStateStore] (feeds the full graph-refresh pipeline).
     *
     * @param branch      Short name of the existing local branch.
     * @param customPath  Absolute or relative path for the new worktree directory.
     *                    When `null`, [defaultWorktreePath] is used.
     * @return            The [Worktree] descriptor from `git worktree list`.
     * @throws IllegalStateException if [branch] is already bound to a worktree in state.
     * @throws WorktreeAlreadyExistsException if `git worktree add` reports the path is taken.
     * @throws WorktreeException for any other git-level failure.
     */
    fun createWorktreeForBranch(branch: String, customPath: String? = null): Worktree {
        val svc = stateService()
        val existing = svc.getWorktreePath(branch)
        check(existing == null) {
            "Branch '$branch' is already bound to worktree '$existing'"
        }

        val path = customPath ?: defaultWorktreePath(branch)
        LOG.info("WorktreeOps: creating worktree for '$branch' at '$path'")
        val worktree = gitLayer().worktreeAdd(path, branch)

        // Persist to the lightweight XML service.
        val parent = svc.getParent(branch)
        if (parent != null) {
            svc.recordBranch(branch, parent, path)
        } else {
            svc.updateWorktreePath(branch, path)
        }

        // Persist to git-object state so the graph refresh picks up the change.
        val store = stateStore()
        store.read()?.let { current ->
            val updated = current.copy(
                branches = current.branches.toMutableMap().also { map ->
                    val node = map[branch]
                    if (node != null) map[branch] = node.copy(worktreePath = path)
                },
            )
            store.write(updated)
        }

        LOG.info("WorktreeOps: worktree for '$branch' created at '${worktree.path}'")
        return worktree
    }

    /**
     * Removes the linked worktree bound to [branch].
     *
     * Steps:
     * 1. Resolves the path from [StackStateService]; falls back to scanning
     *    `git worktree list` to handle orphaned state (path present in git but not
     *    recorded in the XML service).
     * 2. Calls `git worktree remove <path>`.
     * 3. Clears the binding from both [StackStateService] and [StackStateStore].
     *
     * @param branch Short name of the branch whose worktree should be removed.
     * @throws WorktreeNotFoundException if no worktree is bound to [branch].
     * @throws WorktreeException for other git-level failures (e.g. locked worktree).
     */
    fun removeWorktreeForBranch(branch: String) {
        val svc  = stateService()
        val path = svc.getWorktreePath(branch)
            ?: gitLayer().worktreeList()
                .firstOrNull { it.branch == branch }
                ?.path
            ?: throw WorktreeNotFoundException("No worktree found for branch '$branch'")

        LOG.info("WorktreeOps: removing worktree for '$branch' at '$path'")
        gitLayer().worktreeRemove(path)

        // Clear from XML service.
        svc.clearWorktreePath(branch)

        // Clear from git-object state.
        val store = stateStore()
        store.read()?.let { current ->
            val updated = current.copy(
                branches = current.branches.toMutableMap().also { map ->
                    val node = map[branch]
                    if (node != null) map[branch] = node.copy(worktreePath = null)
                },
            )
            store.write(updated)
        }
        LOG.info("WorktreeOps: worktree for '$branch' removed")
    }

    /**
     * Computes the default worktree path for [branch].
     *
     * Reads the configurable base path from [StackStateService.getWorktreeBasePath];
     * falls back to `<projectDir>/../<projectName>-worktrees/<sanitisedBranch>`.
     *
     * The branch name is sanitised by replacing `/` and `\` with `-` to produce a
     * valid directory-name component.
     */
    fun defaultWorktreePath(branch: String): String {
        val base = stateService().getWorktreeBasePath()
        val sanitised = branch.replace('/', '-').replace('\\', '-')
        return if (base != null) {
            "$base/$sanitised"
        } else {
            val projectDir = project.basePath
                ?: error("Cannot determine worktree path: project has no base directory")
            "$projectDir/../${project.name}-worktrees/$sanitised"
        }
    }
}
