package com.github.ydymovopenclawbot.stackworktree.ops

import com.github.ydymovopenclawbot.stackworktree.ui.stackgraph.StackNodeData

/**
 * Ops layer — orchestrates higher-level operations that coordinate git and state layers.
 */
interface OpsLayer {
    /** Switches the active worktree context to [worktreePath]. */
    fun switchWorktree(worktreePath: String)

    /** Synchronises all worktrees with the latest remote state. */
    fun syncAll()

    /** Cleans up stale worktrees that no longer have a corresponding remote branch. */
    fun pruneStale()

    /**
     * Inserts a new branch **above** [targetBranch] in the stack:
     *
     * ```
     * Before:  … → P → targetBranch → …
     * After:   … → P → newBranchName → targetBranch → …
     * ```
     *
     * 1. Creates [newBranchName] from [targetBranch]'s current parent (or from
     *    [targetBranch] itself if it is the root).
     * 2. Rebases [targetBranch] — and its transitive descendants — onto [newBranchName].
     * 3. Persists the updated parent/children pointers in the stack state.
     *
     * If the user aborts a conflict-resolution dialog the operation is rolled back and
     * the repository is left in its pre-operation state.
     *
     * @throws IllegalArgumentException if [newBranchName] already exists.
     */
    fun insertBranchAbove(targetBranch: String, newBranchName: String)

    /**
     * Inserts a new branch **below** [targetBranch] in the stack:
     *
     * ```
     * Before:  … → targetBranch → C1, C2, …
     * After:   … → targetBranch → newBranchName → C1, C2, …
     * ```
     *
     * 1. Creates [newBranchName] from [targetBranch]'s current tip.
     * 2. Re-parents every direct child of [targetBranch] to [newBranchName] and
     *    rebases each child onto [newBranchName].
     * 3. Persists the updated parent/children pointers in the stack state.
     *
     * If the user aborts a conflict-resolution dialog the operation is rolled back.
     *
     * @throws IllegalArgumentException if [newBranchName] already exists.
     */
    fun insertBranchBelow(targetBranch: String, newBranchName: String)

    /**
     * Creates a new branch on top of [parentNode] and registers it in the stack graph.
     *
     * Sequence:
     * 1. `git checkout -b <newBranch>` **or** `git worktree add <worktreePath> <newBranch>`
     *    depending on [createWorktree].
     * 2. If [commitMessage] is non-blank: `git add -A && git commit -m <commitMessage>`.
     * 3. Records branch→parent (and optional worktree path) in [StackStateService][com.github.ydymovopenclawbot.stackworktree.state.StackStateService].
     * 4. Returns a [StackNodeData] representing the new node so callers can
     *    immediately refresh the graph.
     *
     * @param parentNode     The node the new branch is stacked on top of.
     * @param newBranch      Short name for the new git branch.
     * @param commitMessage  Optional commit message; when non-blank, stages all
     *                       changes and commits before returning.
     * @param createWorktree When `true`, creates a linked worktree via
     *                       `git worktree add` instead of a plain branch.
     * @param worktreePath   Required (non-null) when [createWorktree] is `true`.
     * @return               [StackNodeData] for the newly created node.
     */
    fun addBranchToStack(
        parentNode: StackNodeData,
        newBranch: String,
        commitMessage: String?,
        createWorktree: Boolean,
        worktreePath: String?,
    ): StackNodeData

    /**
     * Marks [branch] as tracked, inserting it as a child of [parentBranch].
     *
     * [parentBranch] must equal [com.github.ydymovopenclawbot.stackworktree.state.PluginState.trunkBranch]
     * or already be present in the tracked-branch map.
     *
     * @throws IllegalArgumentException if [branch] is already tracked or [parentBranch] is invalid.
     */
    fun trackBranch(branch: String, parentBranch: String)

    /**
     * Removes [branch] from the tracked tree without deleting the underlying git branch.
     *
     * Children of [branch] are re-parented to [branch]'s own parent, preserving their
     * relative order at the position where [branch] was in the parent's children list.
     *
     * @throws IllegalArgumentException if [branch] is not currently tracked.
     */
    fun untrackBranch(branch: String)

    /**
     * Rebases [branch] onto its tracked parent branch (simple `git rebase <parent> <branch>`).
     *
     * Uses [git4idea.rebase.GitRebaser] so that conflicts open IntelliJ's three-pane
     * merge dialog automatically. The caller blocks until the rebase completes, the user
     * resolves all conflicts and continues, or the user aborts.
     *
     * After a successful rebase:
     * - [com.github.ydymovopenclawbot.stackworktree.state.BranchNode.baseCommit] is updated to the
     *   new merge-base (= current tip of parent).
     * - [com.github.ydymovopenclawbot.stackworktree.state.BranchNode.health] is set to
     *   [com.github.ydymovopenclawbot.stackworktree.state.BranchHealth.CLEAN].
     * - A success notification balloon is shown.
     * - The UI stack graph is refreshed.
     *
     * On abort (user dismisses merge dialog): the repository is left in its original
     * pre-rebase state and no state is written.
     *
     * @throws IllegalStateException if [branch] is not tracked or has no parent.
     */
    fun rebaseOntoParent(branch: String)
}
