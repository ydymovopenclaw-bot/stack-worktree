package com.github.ydymovopenclawbot.stackworktree.git

/**
 * Git layer — responsible for all git operations (worktree creation, branch management, etc.).
 */
interface GitLayer {
    /** Returns the list of current git worktrees for the project. */
    fun listWorktrees(): List<WorktreeInfo>

    /** Creates a new worktree at [path] checked out at [branch]. */
    fun createWorktree(path: String, branch: String): WorktreeInfo

    /** Removes the worktree at [path]. */
    fun removeWorktree(path: String)
}

/** Minimal descriptor for a git worktree. */
data class WorktreeInfo(
    val path: String,
    val branch: String,
    val isMain: Boolean = false,
)
