package com.github.ydymovopenclawbot.stackworktree.ops

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
}
