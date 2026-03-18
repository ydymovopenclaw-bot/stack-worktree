package com.github.ydymovopenclawbot.stackworktree.ui.stackgraph

import com.github.ydymovopenclawbot.stackworktree.pr.PrStatus

/**
 * Domain model for the stack graph UI.
 *
 * These are pure data classes with no IntelliJ Platform or Swing dependencies,
 * making them straightforward to unit-test.
 */

/** Health of a branch relative to its parent in the stack. */
enum class HealthStatus {
    /** Working tree is clean and branch is up-to-date with parent. */
    CLEAN,

    /** Uncommitted local modifications exist. */
    DIRTY,

    /** Merge/rebase conflict markers are present (from a previous failed rebase). */
    CONFLICT,

    /** Branch has not been rebased onto recent parent changes (behind ≥ 1). */
    STALE,

    /** Branch has been merged into trunk (detected via remote merged-branch list). */
    MERGED,
}

/**
 * All data needed to render a single node in the stack graph.
 *
 * @param id             Stable identifier (typically the branch name or worktree path).
 * @param branchName     Human-readable branch name shown inside the node.
 * @param parentId       [id] of the parent node, or `null` for root nodes.
 * @param ahead          Commits this branch is ahead of its parent.
 * @param behind         Commits this branch is behind its parent.
 * @param healthStatus   Visual health indicator controlling border colour.
 * @param isCurrentBranch Whether this node represents the currently checked-out branch.
 * @param hasWorktree    Whether a linked worktree is bound to this branch; renders a
 *                       folder-badge indicator in the graph node when `true`.
 * @param prStatus       Live PR and CI status for this branch, or `null` when no open PR
 *                       exists or the poller has not yet fetched data.  When non-null,
 *                       the graph node renders a PR state badge and a CI dot.
 */
data class StackNodeData(
    val id: String,
    val branchName: String,
    val parentId: String?,
    val ahead: Int = 0,
    val behind: Int = 0,
    val healthStatus: HealthStatus = HealthStatus.CLEAN,
    val isCurrentBranch: Boolean = false,
    val hasWorktree: Boolean = false,
    val prStatus: PrStatus? = null,
)

/**
 * Container for the full set of nodes that make up one stack graph.
 *
 * The list may be empty (no stacks tracked yet); the panel handles that gracefully.
 */
data class StackGraphData(val nodes: List<StackNodeData> = emptyList())
