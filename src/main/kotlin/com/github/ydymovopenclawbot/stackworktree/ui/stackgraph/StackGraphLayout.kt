package com.github.ydymovopenclawbot.stackworktree.ui.stackgraph

import java.awt.geom.Rectangle2D

/**
 * Pure layout engine for the stack graph — no Swing or IntelliJ Platform dependencies.
 *
 * Implements a top-down tree layout:
 * - Leaf nodes are assigned sequential horizontal columns (left → right).
 * - Parent nodes are centred over their children's column range
 *   (Reingold–Tilford–style, simplified for a DAG whose nodes have at most one parent).
 * - Vertical position is determined by depth (distance from the nearest root).
 *
 * All sizes are in pixels at 1× DPI; the caller scales for HiDPI if required.
 */
object StackGraphLayout {

    // ------------------------------------------------------------------
    // Geometry constants (pixels)
    // ------------------------------------------------------------------

    const val NODE_WIDTH = 180
    const val NODE_HEIGHT = 56
    const val H_GAP = 24          // horizontal gap between sibling nodes
    const val V_GAP = 48          // vertical gap between parent and child rows
    const val PADDING = 20        // margin around the entire canvas
    const val CORNER_ARC = 12     // rounded-rectangle arc diameter

    // ------------------------------------------------------------------
    // Public API
    // ------------------------------------------------------------------

    /**
     * Result of a layout pass.
     *
     * @param nodeRects   Node id → axis-aligned bounding rectangle (for hit-testing and rendering).
     * @param edges       Directed edges as (parentId, childId) pairs (for drawing connector lines).
     * @param canvasWidth  Total pixel width required (use as [javax.swing.JComponent.preferredSize] width).
     * @param canvasHeight Total pixel height required.
     */
    data class Result(
        val nodeRects: Map<String, Rectangle2D>,
        val edges: List<Pair<String, String>>,
        val canvasWidth: Int,
        val canvasHeight: Int,
    )

    /**
     * Computes the layout for [data].
     *
     * Returns a [Result] with empty collections when [data] contains no nodes.
     */
    fun compute(data: StackGraphData): Result {
        if (data.nodes.isEmpty()) return Result(emptyMap(), emptyList(), 0, 0)

        // Build parent → children adjacency (null key holds root nodes)
        val childrenOf: Map<String?, List<StackNodeData>> = data.nodes.groupBy { it.parentId }

        val roots: List<StackNodeData> = childrenOf[null] ?: emptyList()

        // ------------------------------------------------------------------
        // DFS to assign columns: leaves first, parents centred over children
        // ------------------------------------------------------------------

        var nextLeafColumn = 0
        val columns = mutableMapOf<String, Int>()
        val depths  = mutableMapOf<String, Int>()

        fun dfs(node: StackNodeData, depth: Int) {
            depths[node.id] = depth
            val children = childrenOf[node.id] ?: emptyList()
            if (children.isEmpty()) {
                // Leaf: claim the next available column
                columns[node.id] = nextLeafColumn++
            } else {
                // Recurse into children first
                children.forEach { dfs(it, depth + 1) }
                // Centre parent over its children's column range
                val first = columns[children.first().id]!!
                val last  = columns[children.last().id]!!
                columns[node.id] = (first + last) / 2
            }
        }

        roots.forEach { dfs(it, 0) }

        // ------------------------------------------------------------------
        // Build pixel rectangles
        // ------------------------------------------------------------------

        val nodeRects: Map<String, Rectangle2D> = data.nodes.associate { node ->
            val col   = columns[node.id] ?: 0
            val depth = depths[node.id]  ?: 0
            val x = PADDING + col   * (NODE_WIDTH  + H_GAP)
            val y = PADDING + depth * (NODE_HEIGHT + V_GAP)
            node.id to Rectangle2D.Float(
                x.toFloat(), y.toFloat(),
                NODE_WIDTH.toFloat(), NODE_HEIGHT.toFloat(),
            )
        }

        // ------------------------------------------------------------------
        // Collect edges
        // ------------------------------------------------------------------

        val edges: List<Pair<String, String>> = data.nodes.mapNotNull { node ->
            node.parentId?.let { parentId -> parentId to node.id }
        }

        // ------------------------------------------------------------------
        // Canvas dimensions
        // ------------------------------------------------------------------

        val maxCol   = if (columns.isEmpty()) 0 else columns.values.max()
        val maxDepth = if (depths.isEmpty())  0 else depths.values.max()

        val canvasWidth  = PADDING * 2 + (maxCol   + 1) * NODE_WIDTH  + maxCol   * H_GAP
        val canvasHeight = PADDING * 2 + (maxDepth + 1) * NODE_HEIGHT + maxDepth * V_GAP

        return Result(nodeRects, edges, canvasWidth, canvasHeight)
    }
}
