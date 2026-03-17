package com.github.ydymovopenclawbot.stackworktree.model

/** A single commit as displayed in a one-line log. */
data class CommitEntry(
    /** Short (7-char) commit hash. */
    val hash: String,
    /** First line of the commit message. */
    val subject: String,
)

/**
 * Represents a branch node in the stack graph.
 *
 * @param branchName   Short name of the branch (e.g. "feature/foo").
 * @param parentBranch Short name of the parent branch, or null for trunk / unresolved.
 * @param aheadCount   Commits in this branch not present in [parentBranch].
 * @param behindCount  Commits in [parentBranch] not present in this branch.
 * @param commits      Recent commits unique to this branch (git log --oneline).
 * @param worktreePath Absolute path to the bound worktree, or null if not bound.
 */
data class StackNode(
    val branchName: String,
    val parentBranch: String?,
    val aheadCount: Int,
    val behindCount: Int,
    val commits: List<CommitEntry>,
    val worktreePath: String?,
)
