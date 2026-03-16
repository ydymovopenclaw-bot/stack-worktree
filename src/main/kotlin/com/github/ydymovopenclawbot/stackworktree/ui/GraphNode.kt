package com.github.ydymovopenclawbot.stackworktree.ui

/**
 * Layout-ready descriptor for one node in the rendered stack graph.
 *
 * Coordinates use an abstract integer grid — the renderer is responsible for
 * scaling them to screen pixels. No Swing or AWT types appear here.
 *
 * @param branch          Branch name; uniquely identifies the node within a graph.
 * @param x               Column index (0 = leftmost). Siblings at the same depth
 *                        occupy consecutive columns.
 * @param y               Row index (0 = trunk / top). Each level of stack depth
 *                        increments y by 1.
 * @param aheadBehindText Human-readable badge text, e.g. "+3 / -1". Empty string
 *                        when ahead/behind data is unavailable.
 * @param health          Derived health state; drives the node's border/fill colour.
 * @param hasWorktree     True when an active git worktree is checked out on this branch.
 * @param edgesTo         Branch names of direct children; used by the renderer to
 *                        draw connecting edges. Empty for leaf nodes.
 */
data class GraphNode(
    val branch: String,
    val x: Int,
    val y: Int,
    val aheadBehindText: String,
    val health: NodeHealth,
    val hasWorktree: Boolean,
    val edgesTo: List<String> = emptyList(),
)
