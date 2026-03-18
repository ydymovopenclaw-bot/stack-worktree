package com.github.ydymovopenclawbot.stackworktree.ui.stackgraph

import com.github.ydymovopenclawbot.stackworktree.git.AheadBehind
import com.github.ydymovopenclawbot.stackworktree.state.BranchHealth
import com.github.ydymovopenclawbot.stackworktree.state.BranchNode

/**
 * Pure-logic health calculator — no IntelliJ Platform or Swing dependencies.
 *
 * Converts persisted [BranchNode] state, live [AheadBehind] counts, and a set of
 * remotely-merged branch names into a [HealthStatus] for each tracked branch.
 *
 * ## Priority order (highest to lowest)
 * 1. [HealthStatus.MERGED]   — branch appears in the merged-remote set (purple).
 * 2. [HealthStatus.CONFLICT] — stored [BranchHealth.HAS_CONFLICTS] from a previous
 *                              failed rebase (red).
 * 3. [HealthStatus.STALE]    — [AheadBehind.behind] > 0 (amber); branch needs to be
 *                              rebased onto its parent.
 * 4. [HealthStatus.CLEAN]    — baseline; branch is up-to-date with its parent (green).
 *
 * [HealthStatus.DIRTY] is intentionally excluded from the automatic pipeline: it
 * requires a working-tree scan and is never set by the refresh path.
 */
object HealthCalculator {

    /**
     * Computes health for every branch in [branches].
     *
     * Marked `internal`: [computeSingle] is the sole production entry point
     * (called node-by-node in [com.github.ydymovopenclawbot.stackworktree.ui.StacksTabFactory]).
     * This overload exists for tests that exercise the full-map path; it should
     * be promoted to `public` if a batch call site is added in production code.
     *
     * @param branches       Branch nodes keyed by branch name (from [StackState.branches]).
     * @param aheadBehind    Ahead/behind counts keyed by branch name; entries may be absent
     *                       (treated as if both counters are zero).
     * @param mergedBranches Set of branch names known to have been merged into trunk.
     *                       Typically obtained from [GitLayer.getMergedRemoteBranches] without
     *                       a preceding fetch so the result reflects the last-known remote state.
     * @return Map of branch name → [HealthStatus] for every entry in [branches].
     */
    internal fun compute(
        branches: Map<String, BranchNode>,
        aheadBehind: Map<String, AheadBehind> = emptyMap(),
        mergedBranches: Set<String> = emptySet(),
    ): Map<String, HealthStatus> = branches.mapValues { (name, node) ->
        computeSingle(name, node, aheadBehind[name], mergedBranches)
    }

    /**
     * Computes health for a single branch node.
     *
     * Extracted as a public function so callers can drive health computation
     * node-by-node inside existing loops (e.g. [StacksTabFactory.performRefresh]).
     *
     * @param branch         Branch name.
     * @param node           Persisted [BranchNode] carrying last-known [BranchHealth].
     * @param ab             Live ahead/behind counts for this branch, or `null` if unavailable.
     * @param mergedBranches Set of branch names merged into trunk.
     */
    fun computeSingle(
        branch: String,
        node: BranchNode,
        ab: AheadBehind?,
        mergedBranches: Set<String> = emptySet(),
    ): HealthStatus = when {
        branch in mergedBranches               -> HealthStatus.MERGED
        node.health == BranchHealth.HAS_CONFLICTS -> HealthStatus.CONFLICT
        ab != null && ab.behind > 0            -> HealthStatus.STALE
        else                                   -> HealthStatus.CLEAN
    }
}
