package com.github.ydymovopenclawbot.stackworktree.ops

/**
 * Result of a [OpsLayer.syncAll] operation.
 *
 * @param mergedBranches  Tracked branches detected as merged into trunk on the remote
 *                        and subsequently removed from the stack.
 * @param prunedWorktrees Absolute paths of linked worktrees that were removed because
 *                        their branch was detected as merged (requires auto-prune enabled).
 * @param updatedBranches Ahead/behind status for every tracked branch that remains after
 *                        merged branches are removed.
 */
data class SyncResult(
    val mergedBranches: List<String>,
    val prunedWorktrees: List<String>,
    val updatedBranches: List<BranchStatus>,
) {
    /**
     * Human-readable one-line summary suitable for a notification balloon.
     *
     * Examples:
     * - `"Synced: 0 merged"`
     * - `"Synced: 2 merged, 3 need rebase"`
     * - `"Synced: 1 merged, 1 worktree pruned"`
     * - `"Synced: 2 merged, 3 need rebase, 2 worktrees pruned"`
     */
    fun summaryMessage(): String = buildString {
        append("Synced: ${mergedBranches.size} merged")
        val needRebase = updatedBranches.count { it.behindCount > 0 }
        if (needRebase > 0) append(", $needRebase need rebase")
        if (prunedWorktrees.isNotEmpty()) {
            val label = if (prunedWorktrees.size == 1) "worktree" else "worktrees"
            append(", ${prunedWorktrees.size} $label pruned")
        }
    }
}

/** Ahead/behind status of a single branch relative to its stack parent after a sync. */
data class BranchStatus(
    val branch: String,
    val aheadCount: Int,
    val behindCount: Int,
)
