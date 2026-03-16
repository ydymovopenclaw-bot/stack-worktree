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
     * @param state       Persisted stack topology (stacks, branches, worktree paths).
     * @param aheadBehind Ahead/behind commit counts keyed by branch name; may be
     *                    partial — missing entries produce [NodeHealth.UNKNOWN] and
     *                    an empty badge text.
     * @return Flat list of nodes ordered by (y ASC, x ASC) — trunk first.
     */
    fun buildGraph(
        state: StackState,
        aheadBehind: Map<String, AheadBehind> = emptyMap(),
    ): List<GraphNode> {
        if (state.stacks.isEmpty()) return emptyList()

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
     * Builds a [StackNode] tree from the flat [StackEntry] lists.
     *
     * For each entry the branch list is walked pairwise to record parent→child
     * edges.  Branches appearing in multiple entries share a single tree node
     * (fork point), so a fork like `["main","A"]` + `["main","B"]` produces:
     * ```
     * main
     * ├── A
     * └── B
     * ```
     */
    private fun buildTree(state: StackState): StackNode {
        // branch → ordered set of direct children (insertion order preserved)
        val childrenMap = LinkedHashMap<String, LinkedHashSet<String>>()

        for (entry in state.stacks) {
            for (branch in entry.branches) {
                childrenMap.getOrPut(branch) { LinkedHashSet() }
            }
            for (i in 0 until entry.branches.size - 1) {
                childrenMap.getOrPut(entry.branches[i]) { LinkedHashSet() }
                    .add(entry.branches[i + 1])
            }
        }

        val allChildren = childrenMap.values.flatten().toSet()
        // Root = branch that is never a child of another branch
        val root = childrenMap.keys.firstOrNull { it !in allChildren }
            ?: state.stacks.first().branches.first()

        return buildStackNode(root, childrenMap)
    }

    private fun buildStackNode(
        branch: String,
        childrenMap: Map<String, LinkedHashSet<String>>,
    ): StackNode = StackNode(
        branch = branch,
        children = (childrenMap[branch] ?: emptySet<String>())
            .map { buildStackNode(it, childrenMap) },
    )

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
        state.stacks.flatMapTo(mutableSetOf()) { entry ->
            entry.branches.zip(entry.worktreePaths)
                .filter { (_, path) -> path.isNotEmpty() }
                .map { (branch, _) -> branch }
        }
}
