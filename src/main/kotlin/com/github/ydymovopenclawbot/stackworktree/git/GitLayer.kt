package com.github.ydymovopenclawbot.stackworktree.git

/**
 * Git layer — responsible for all git worktree operations.
 */
interface GitLayer {
    /** Creates a new worktree at [path] checked out at [branch]. */
    fun worktreeAdd(path: String, branch: String): Worktree

    /** Removes the worktree at [path]. */
    fun worktreeRemove(path: String)

    /** Returns the list of current git worktrees for the repository. */
    fun worktreeList(): List<Worktree>

    /** Prunes stale worktree administrative files. */
    fun worktreePrune()
}

/**
 * Descriptor for a git worktree.
 *
 * @param path     Absolute path to the worktree directory.
 * @param branch   Checked-out branch name (short form), or empty string for a detached HEAD.
 * @param head     Full SHA of the current HEAD commit.
 * @param isLocked Whether the worktree is locked (prevents pruning / removal).
 */
data class Worktree(
    val path: String,
    val branch: String,
    val head: String,
    val isLocked: Boolean,
)
