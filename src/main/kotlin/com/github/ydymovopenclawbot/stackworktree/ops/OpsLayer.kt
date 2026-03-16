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
}
