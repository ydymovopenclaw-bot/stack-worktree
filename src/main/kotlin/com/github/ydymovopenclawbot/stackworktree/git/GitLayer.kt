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

    /**
     * Returns how many commits [branch] is ahead of and behind [parent].
     *
     * Implemented via `git rev-list --left-right --count <parent>...<branch>`.
     * - ahead = commits in [branch] not in [parent]
     * - behind = commits in [parent] not in [branch]
     */
    fun aheadBehind(branch: String, parent: String): AheadBehind
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

/** How many commits a branch is ahead of and behind its parent. */
data class AheadBehind(val ahead: Int, val behind: Int)
