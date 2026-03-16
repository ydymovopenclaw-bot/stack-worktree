package com.github.ydymovopenclawbot.stackworktree.ui

import com.github.ydymovopenclawbot.stackworktree.git.AheadBehind
import com.github.ydymovopenclawbot.stackworktree.state.StackNode
import com.github.ydymovopenclawbot.stackworktree.state.StackState

/**
 * Pure-logic view-model. Transforms a [StackState] into a flat list of [GraphNode]
 * objects carrying absolute grid coordinates and display properties.
 *
 * No Swing or AWT imports — the class is safe to unit-test without a headless IDE.
 *
 * ## Layout rules
 * - The trunk (root branch) sits at row `y = 0`.
 * - Each additional level of stack depth increments `y` by 1 (top-to-bottom).
 * - Siblings at the same depth are spread horizontally: `x` is assigned via a
 *   subtree-width DFS so that no two subtrees ever share a column range, and each
 *   parent is centred over its direct children.
 */
class StackGraphModel {

    /**
     * Converts [state] into a list of [GraphNode].
     *
     * @param state       Persisted stack topology (branches, worktree paths, health).
     * @param aheadBehind Ahead/behind commit counts keyed by branch name; may be
     *                    partial — missing entries produce [NodeHealth.UNKNOWN] and
     *                    an empty badge text.
     * @return Flat list of nodes ordered by (y ASC, x ASC) — trunk first.
     */
    fun buildGraph(
        state: StackState,
        aheadBehind: Map<String, AheadBehind> = emptyMap(),
    ): List<GraphNode> {
        if (state.branches.isEmpty()) return emptyList()

        val worktreeBranches = collectWorktreeBranches(state)
        val root = buildTree(state)

        val result = mutableListOf<GraphNode>()
        assignPositions(root, depth = 0, xOffset = 0, aheadBehind, worktreeBranches, result)
        result.sortWith(compareBy({ it.y }, { it.x }))
        return result
    }

    // ---------------------------------------------------------------------------
    // Tree construction
    // ---------------------------------------------------------------------------

    /**
     * Builds a [StackNode] tree from the flat [StackState.branches] map.
     *
     * The trunk is identified by [StackState.repoConfig.trunk]. Children are
     * sourced from [com.github.ydymovopenclawbot.stackworktree.state.BranchNode.children]
     * on each node, producing a tree that mirrors the stacked-branch hierarchy.
     */
    private fun buildTree(state: StackState): StackNode =
        buildStackNode(state.repoConfig.trunk, state)

    private fun buildStackNode(branch: String, state: StackState): StackNode {
        val node = state.branches[branch]
        val childBranches = node?.children ?: emptyList()
        return StackNode(
            branch = branch,
            children = childBranches
                .filter { it in state.branches }
                .map { buildStackNode(it, state) },
        )
    }

    // ---------------------------------------------------------------------------
    // Layout algorithm — subtree-width DFS
    // ---------------------------------------------------------------------------

    /**
     * Recursively assigns (x, y) positions using a subtree-width DFS.
     *
     * Each leaf consumes exactly **1** column. An internal node consumes the sum
     * of its children's widths. The node itself is placed at
     * `x = xOffset + (subtreeWidth - 1) / 2` (left-biased centre over the subtree).
     *
     * Returns the total column width consumed by this subtree.
     */
    private fun assignPositions(
        node: StackNode,
        depth: Int,
        xOffset: Int,
        aheadBehind: Map<String, AheadBehind>,
        worktreeBranches: Set<String>,
        result: MutableList<GraphNode>,
    ): Int {
        val subtreeWidth: Int
        if (node.children.isEmpty()) {
            subtreeWidth = 1
        } else {
            var childX = xOffset
            for (child in node.children) {
                childX += assignPositions(
                    child, depth + 1, childX, aheadBehind, worktreeBranches, result,
                )
            }
            subtreeWidth = childX - xOffset
        }

        val ab = aheadBehind[node.branch]
        result.add(
            GraphNode(
                branch = node.branch,
                x = xOffset + (subtreeWidth - 1) / 2,
                y = depth,
                aheadBehindText = formatAheadBehind(ab),
                health = deriveHealth(ab),
                hasWorktree = node.branch in worktreeBranches,
                edgesTo = node.children.map { it.branch },
            ),
        )
        return subtreeWidth
    }

    // ---------------------------------------------------------------------------
    // Display helpers
    // ---------------------------------------------------------------------------

    private fun formatAheadBehind(ab: AheadBehind?): String =
        if (ab == null) "" else "+${ab.ahead} / -${ab.behind}"

    private fun deriveHealth(ab: AheadBehind?): NodeHealth = when {
        ab == null        -> NodeHealth.UNKNOWN
        ab.behind == 0    -> NodeHealth.HEALTHY
        ab.behind <= 5    -> NodeHealth.NEEDS_REBASE
        else              -> NodeHealth.CONFLICT
    }

    private fun collectWorktreeBranches(state: StackState): Set<String> =
        state.branches.values
            .filter { it.worktreePath != null }
            .mapTo(mutableSetOf()) { it.name }
}
