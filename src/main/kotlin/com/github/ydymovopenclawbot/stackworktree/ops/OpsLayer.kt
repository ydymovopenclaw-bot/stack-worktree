package com.github.ydymovopenclawbot.stackworktree.ops

import com.github.ydymovopenclawbot.stackworktree.ui.stackgraph.StackNodeData
import com.github.ydymovopenclawbot.stackworktree.ops.SyncResult

/**
 * Ops layer — orchestrates higher-level operations that coordinate git and state layers.
 */
interface OpsLayer {
    /** Switches the active worktree context to [worktreePath]. */
    fun switchWorktree(worktreePath: String)

    /**
     * Fetches the remote, removes merged branches from the stack (re-parenting their
     * children), optionally prunes linked worktrees for merged branches, and recalculates
     * ahead/behind status for every remaining tracked branch.
     *
     * A summary notification balloon is shown on completion. Runs on the calling thread —
     * callers are responsible for dispatching to a background thread
     * (e.g. via [com.intellij.openapi.progress.Task.Backgroundable]).
     *
     * @param autoPrune When `true`, linked worktrees for merged branches are removed via
     *                  `git worktree remove`. Defaults to `true`.
     * @return [SyncResult] describing what changed; also shown as a notification balloon.
     */
    fun syncAll(autoPrune: Boolean = false): SyncResult

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

    /**
     * Submits the stack: pushes every non-trunk branch bottom-to-top, then creates or
     * updates the corresponding pull request on the hosting service (GitHub / GitLab).
     *
     * Each PR description includes a Markdown stack-navigation table listing all branches
     * in the stack in order, with the current branch's row **bolded** so reviewers can
     * immediately orient themselves.
     *
     * On a subsequent call the operation is idempotent: already-open PRs are updated
     * (never duplicated), and the navigation table is refreshed with the latest PR URLs.
     *
     * PR metadata (number, URL, status) is persisted in the
     * [com.github.ydymovopenclawbot.stackworktree.state.StackState] after every submit.
     *
     * **Must be called from a background thread** — performs blocking git push and
     * network I/O.
     *
     * @return [SubmitResult] describing what was created / updated.
     */
    fun submitStack(): SubmitResult

    /**
     * Rebases every tracked branch onto its parent in bottom-to-top (BFS) order, cascading
     * from trunk through all stacks.
     *
     * Each branch is rebased via `git rebase --onto <parent> <oldParentTip> <branch>` so
     * that only commits unique to the branch are replayed. The old parent tip is recorded
     * just before the parent itself is rebased, which is the same proven pattern used by the
     * existing internal `rebaseDescendants` helper.
     *
     * Conflict handling is delegated to [git4idea.rebase.GitRebaser], which opens
     * IntelliJ's three-pane merge dialog automatically. The cascade pauses until the user
     * resolves conflicts and continues, or aborts.
     *
     * **Abort semantics differ from [insertBranchAbove]/[insertBranchBelow]**: already-rebased
     * branches are **not** rolled back. The partial progress is persisted so the user can
     * continue manually from where the cascade stopped.
     *
     * Progress is reported via the optional [onProgress] callback with 1-based [current],
     * [total] branch count, and the [branchName] currently being rebased — suitable for
     * forwarding to [com.intellij.openapi.progress.ProgressIndicator].
     *
     * @return [RestackResult.Success] when all branches were rebased, or
     *   [RestackResult.Aborted] (with the count of already-rebased branches) when the user
     *   aborted at a conflict.
     */
    fun restackAll(
        onProgress: ((current: Int, total: Int, branchName: String) -> Unit)? = null,
    ): RestackResult
}

/** Result of a [OpsLayer.restackAll] operation. */
sealed class RestackResult {
    /** All branches were successfully rebased. */
    data class Success(val rebasedCount: Int) : RestackResult()

    /**
     * Cascade was aborted at [failedBranch]; [rebasedCount] branches completed before
     * the abort. Already-rebased branches are kept in their new state.
     */
    data class Aborted(
        val rebasedCount: Int,
        val failedBranch: String,
        val reason: String,
    ) : RestackResult()
}
