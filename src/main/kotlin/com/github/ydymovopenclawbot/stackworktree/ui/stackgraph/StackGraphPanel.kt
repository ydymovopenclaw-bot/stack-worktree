package com.github.ydymovopenclawbot.stackworktree.ui.stackgraph

import com.github.ydymovopenclawbot.stackworktree.pr.ChecksState
import com.github.ydymovopenclawbot.stackworktree.pr.PrState
import com.github.ydymovopenclawbot.stackworktree.pr.PrStatus
import com.github.ydymovopenclawbot.stackworktree.pr.ReviewState
import com.intellij.util.ui.JBUI
import java.awt.BasicStroke
import java.awt.Dimension
import java.awt.Font
import java.awt.Graphics
import java.awt.Graphics2D
import java.awt.Point
import java.awt.RenderingHints
import java.awt.event.KeyEvent
import java.awt.event.MouseAdapter
import java.awt.event.MouseEvent
import java.awt.geom.CubicCurve2D
import java.awt.geom.Ellipse2D
import java.awt.geom.Rectangle2D
import java.awt.geom.RoundRectangle2D
import javax.swing.AbstractAction
import javax.swing.JComponent
import javax.swing.JPanel
import javax.swing.KeyStroke
import javax.swing.SwingUtilities

/**
 * Custom Swing panel that renders a stack graph using Java2D.
 *
 * ## Usage
 * ```kotlin
 * val panel = StackGraphPanel()
 * panel.onNodeSelected  = { node -> /* highlight / show details */ }
 * panel.onNodeNavigated = { node -> /* switch to that worktree   */ }
 * panel.updateGraph(StackGraphData(nodes))
 * ```
 *
 * Wrap in a [com.intellij.ui.components.JBScrollPane] to support graphs that
 * exceed the visible viewport — [preferredSize] is updated automatically on
 * every [updateGraph] call.
 *
 * ## Theme support
 * All colours are [com.intellij.ui.JBColor] instances defined in [StackGraphColors];
 * they resolve to the correct light/dark variant automatically whenever the IDE
 * theme changes and [repaint] is called.
 */
class StackGraphPanel : JPanel() {

    // ------------------------------------------------------------------
    // Callbacks
    // ------------------------------------------------------------------

    /** Invoked on the EDT when the user single-clicks a node. */
    var onNodeSelected: ((StackNodeData) -> Unit)? = null

    /** Invoked on the EDT when the user double-clicks a node. */
    var onNodeNavigated: ((StackNodeData) -> Unit)? = null

    /**
     * Invoked on the EDT when the user triggers the context menu (right-click /
     * Ctrl-click on macOS). The [StackNodeData] is the hit-tested node under the
     * pointer, or `null` when the click lands on empty canvas. [Point] is the
     * component-relative location suitable for [javax.swing.JPopupMenu.show].
     */
    var onContextMenu: ((node: StackNodeData?, location: Point) -> Unit)? = null

    // ------------------------------------------------------------------
    // Internal state
    // ------------------------------------------------------------------

    private var graphData: StackGraphData = StackGraphData()
    private var layoutResult: StackGraphLayout.Result = StackGraphLayout.compute(StackGraphData())
    private var nodeById: Map<String, StackNodeData> = emptyMap()

    /** Id of the currently selected node, or `null` when nothing is selected. */
    var selectedNodeId: String? = null
        private set

    /**
     * Nodes ordered top-to-bottom by their Y position in the layout for consistent keyboard
     * navigation.  Updated on every [updateGraph] call.
     */
    private var navOrder: List<StackNodeData> = emptyList()

    /**
     * Index into [navOrder] of the keyboard-focused node; -1 when no node has keyboard focus.
     *
     * Exposed as `internal` so [StackGraphPanelTest] can verify navigation state without
     * simulating raw key events.
     */
    internal var focusedNodeIndex: Int = -1
        private set

    // ------------------------------------------------------------------
    // Initialisation
    // ------------------------------------------------------------------

    init {
        isOpaque = true
        isFocusable = true
        background = StackGraphColors.PANEL_BG

        addMouseListener(object : MouseAdapter() {
            override fun mouseClicked(e: MouseEvent) {
                // Right-click is handled by mousePressed/mouseReleased for cross-platform
                // popup-trigger correctness; skip it here to avoid double invocation.
                if (SwingUtilities.isRightMouseButton(e)) return
                val hit = hitTest(e.x, e.y) ?: return
                // Transfer keyboard focus to the panel so arrow keys work immediately after a click.
                requestFocusInWindow()
                when (e.clickCount) {
                    1 -> {
                        selectedNodeId = hit.id
                        focusedNodeIndex = navOrder.indexOfFirst { it.id == hit.id }
                        repaint()
                        onNodeSelected?.invoke(hit)
                    }
                    2 -> onNodeNavigated?.invoke(hit)
                }
            }

            // macOS fires the popup trigger on mousePressed; Windows/Linux on mouseReleased.
            // Handling both — guarded by isPopupTrigger — is the standard Swing idiom for
            // reliable cross-platform context menus.
            override fun mousePressed(e: MouseEvent)  = maybeShowContextMenu(e)
            override fun mouseReleased(e: MouseEvent) = maybeShowContextMenu(e)

            private fun maybeShowContextMenu(e: MouseEvent) {
                if (!e.isPopupTrigger) return
                val hit = hitTest(e.x, e.y)
                if (hit != null) {
                    selectedNodeId = hit.id
                    focusedNodeIndex = navOrder.indexOfFirst { it.id == hit.id }
                    repaint()
                }
                onContextMenu?.invoke(hit, Point(e.x, e.y))
            }
        })

        setupKeyboardNavigation()

        // Screen-reader accessibility: name and description are read by assistive technology.
        // Use getAccessibleContext() (not the field) so the lazy instance is created first.
        getAccessibleContext()?.let { ctx ->
            ctx.accessibleName        = "Stack Graph"
            ctx.accessibleDescription =
                "Use arrow keys to navigate between branches, Enter to check out the focused branch, Space to select it"
        }
    }

    // ------------------------------------------------------------------
    // Public API
    // ------------------------------------------------------------------

    /**
     * Replaces the displayed graph with [data], recomputes the layout,
     * and schedules a repaint.
     *
     * **Must be called on the Event Dispatch Thread (EDT).** Callers on
     * background threads must dispatch via
     * [javax.swing.SwingUtilities.invokeLater].
     */
    fun updateGraph(data: StackGraphData) {
        graphData      = data
        nodeById       = data.nodes.associateBy { it.id }
        layoutResult   = StackGraphLayout.compute(data)
        selectedNodeId = null

        // Re-derive the top-to-bottom visual order so keyboard navigation reflects the
        // rendered layout.  Nodes with no rect (shouldn't happen) sort to the top.
        navOrder = data.nodes.sortedBy { layoutResult.nodeRects[it.id]?.minY ?: 0.0 }
        focusedNodeIndex = -1

        val w = layoutResult.canvasWidth.coerceAtLeast(200)
        val h = layoutResult.canvasHeight.coerceAtLeast(100)
        preferredSize = Dimension(w, h)

        revalidate()
        repaint()
    }

    // ------------------------------------------------------------------
    // Hit-testing
    // ------------------------------------------------------------------

    private fun hitTest(px: Int, py: Int): StackNodeData? {
        for ((id, rect) in layoutResult.nodeRects) {
            if (rect.contains(px.toDouble(), py.toDouble())) {
                return nodeById[id]
            }
        }
        return null
    }

    // ------------------------------------------------------------------
    // Painting
    // ------------------------------------------------------------------

    override fun paintComponent(g: Graphics) {
        super.paintComponent(g)
        val g2 = g as Graphics2D
        g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING,        RenderingHints.VALUE_ANTIALIAS_ON)
        g2.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING,   RenderingHints.VALUE_TEXT_ANTIALIAS_ON)
        g2.setRenderingHint(RenderingHints.KEY_RENDERING,           RenderingHints.VALUE_RENDER_QUALITY)
        g2.setRenderingHint(RenderingHints.KEY_STROKE_CONTROL,      RenderingHints.VALUE_STROKE_PURE)
        g2.setRenderingHint(RenderingHints.KEY_FRACTIONALMETRICS,   RenderingHints.VALUE_FRACTIONALMETRICS_ON)

        if (graphData.nodes.isEmpty()) {
            paintEmptyState(g2)
            return
        }

        paintEdges(g2)
        paintNodes(g2)
    }

    // ------------------------------------------------------------------
    // Empty state
    // ------------------------------------------------------------------

    private fun paintEmptyState(g2: Graphics2D) {
        val msg  = "No stacks tracked yet"
        val font = JBUI.Fonts.label(13f).deriveFont(Font.ITALIC)
        g2.font  = font
        g2.color = StackGraphColors.NODE_BORDER

        val fm  = g2.getFontMetrics(font)
        val tw  = fm.stringWidth(msg)
        val x   = (width  - tw) / 2
        val y   = (height + fm.ascent - fm.descent) / 2
        g2.drawString(msg, x, y)
    }

    // ------------------------------------------------------------------
    // Edges
    // ------------------------------------------------------------------

    private fun paintEdges(g2: Graphics2D) {
        g2.color  = StackGraphColors.EDGE
        g2.stroke = BasicStroke(1.5f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND)

        val rects = layoutResult.nodeRects
        for ((parentId, childId) in layoutResult.edges) {
            val pr = rects[parentId] ?: continue
            val cr = rects[childId]  ?: continue

            val x1 = pr.centerX
            val y1 = pr.maxY
            val x2 = cr.centerX
            val y2 = cr.minY

            val ctrlOffset = (y2 - y1) * 0.45
            val curve = CubicCurve2D.Double(
                x1, y1,
                x1, y1 + ctrlOffset,
                x2, y2 - ctrlOffset,
                x2, y2,
            )
            g2.draw(curve)
        }
    }

    // ------------------------------------------------------------------
    // Nodes
    // ------------------------------------------------------------------

    private fun paintNodes(g2: Graphics2D) {
        val focusedId = navOrder.getOrNull(focusedNodeIndex)?.id
        for ((id, rect) in layoutResult.nodeRects) {
            val node = nodeById[id] ?: continue
            paintNode(
                g2,
                node,
                rect,
                isSelected = (id == selectedNodeId),
                isFocused  = (id == focusedId),
            )
        }
    }

    private fun paintNode(
        g2: Graphics2D,
        node: StackNodeData,
        rect: Rectangle2D,
        isSelected: Boolean,
        isFocused: Boolean = false,
    ) {
        val arc  = StackGraphLayout.CORNER_ARC.toDouble()
        val rrect = RoundRectangle2D.Double(rect.x, rect.y, rect.width, rect.height, arc, arc)

        // Fill
        g2.color = if (isSelected) StackGraphColors.NODE_SELECTED_BG else StackGraphColors.NODE_BG
        g2.fill(rrect)

        // Health-status border — always drawn so the status colour is never hidden
        g2.color  = StackGraphColors.borderForStatus(node.healthStatus)
        g2.stroke = BasicStroke(1.5f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND)
        g2.draw(rrect)

        // Selection highlight — inset second stroke so the health colour remains visible beneath it
        if (isSelected) {
            val inset   = 1.5
            val selArc  = (arc - inset * 2).coerceAtLeast(0.0)
            val selRect = RoundRectangle2D.Double(
                rect.x + inset, rect.y + inset,
                rect.width - inset * 2, rect.height - inset * 2,
                selArc, selArc,
            )
            g2.color  = StackGraphColors.NODE_SELECTED_BORDER
            g2.stroke = BasicStroke(2.0f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND)
            g2.draw(selRect)
        }

        // Keyboard focus ring — outermost dashed ring, drawn only when the node has
        // keyboard focus so users can distinguish keyboard focus from mouse selection.
        if (isFocused) {
            val outset  = 2.5
            val focArc  = arc + outset * 2
            val focRect = RoundRectangle2D.Double(
                rect.x - outset, rect.y - outset,
                rect.width + outset * 2, rect.height + outset * 2,
                focArc, focArc,
            )
            g2.color  = StackGraphColors.KEYBOARD_FOCUS_RING
            g2.stroke = BasicStroke(
                1.5f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND,
                1f, floatArrayOf(4f, 3f), 0f,  // dashed: 4px on, 3px off
            )
            g2.draw(focRect)
        }

        // Current-branch indicator dot (small filled circle, top-left corner)
        if (node.isCurrentBranch) {
            val dotR = 5.0
            val dot  = Ellipse2D.Double(rect.x + 8, rect.y + (rect.height - dotR) / 2, dotR, dotR)
            g2.color = StackGraphColors.CURRENT_BRANCH_DOT
            g2.fill(dot)
        }

        val textStartX = rect.x + if (node.isCurrentBranch) 20.0 else 10.0

        // Branch name
        val labelFont  = JBUI.Fonts.label(12f).deriveFont(Font.PLAIN)
        g2.font  = labelFont
        g2.color = StackGraphColors.NODE_TEXT

        val fm              = g2.getFontMetrics(labelFont)
        val aheadBehindArea = if (node.ahead > 0 || node.behind > 0) 60 else 0
        val worktreeArea    = if (node.hasWorktree) 26 else 0
        val prBadgeArea     = if (node.prStatus != null) 30 else 0
        val ciDotArea       = if (node.prStatus?.checksState != null &&
                                  node.prStatus.checksState != ChecksState.NONE) 14 else 0
        val badgeArea       = aheadBehindArea + worktreeArea + prBadgeArea + ciDotArea
        val maxLabelW = (rect.width - (textStartX - rect.x) - 10 - badgeArea).toInt()
        val label     = truncate(node.branchName, fm, maxLabelW)

        val labelY = (rect.y + (rect.height + fm.ascent - fm.descent) / 2).toInt()
        g2.drawString(label, textStartX.toInt(), labelY)

        // Right-side badges (behind, ahead, worktree, PR state, CI dot) — painted right-to-left
        val hasBadges = node.ahead > 0 || node.behind > 0 || node.hasWorktree || node.prStatus != null
        if (hasBadges) {
            var badgeX = (rect.maxX - 8).toInt()
            val badgeFont = JBUI.Fonts.label(10f).deriveFont(Font.BOLD)
            g2.font = badgeFont
            val bfm = g2.getFontMetrics(badgeFont)
            val badgeCy = rect.y + rect.height / 2

            if (node.behind > 0) {
                badgeX = paintBadge(g2, bfm, "↓${node.behind}", badgeX, badgeCy.toInt(),
                    StackGraphColors.BADGE_BEHIND_BG, StackGraphColors.BADGE_BEHIND_TEXT)
                badgeX -= 4
            }
            if (node.ahead > 0) {
                badgeX = paintBadge(g2, bfm, "↑${node.ahead}", badgeX, badgeCy.toInt(),
                    StackGraphColors.BADGE_AHEAD_BG, StackGraphColors.BADGE_AHEAD_TEXT)
                badgeX -= 4
            }
            if (node.hasWorktree) {
                // "W" badge — indicates a linked git worktree is bound to this branch
                badgeX = paintBadge(g2, bfm, "W", badgeX, badgeCy.toInt(),
                    StackGraphColors.BADGE_WORKTREE_BG, StackGraphColors.BADGE_WORKTREE_TEXT)
                badgeX -= 4
            }

            // PR state badge — rightmost of the left-side group; painted after worktree
            node.prStatus?.let { status ->
                badgeX = paintPrBadge(g2, bfm, status, badgeX, badgeCy.toInt())
                badgeX -= 4

                // CI dot — small circle to the left of the PR badge
                if (status.checksState != ChecksState.NONE) {
                    badgeX = paintCiDot(g2, status.checksState, badgeX, badgeCy.toInt())
                }
            }
        }
    }

    /**
     * Draws a small pill badge and returns the x coordinate immediately to the left of it.
     */
    private fun paintBadge(
        g2: Graphics2D,
        fm: java.awt.FontMetrics,
        text: String,
        rightX: Int,
        centerY: Int,
        bgColor: java.awt.Color,
        textColor: java.awt.Color,
    ): Int {
        val textW  = fm.stringWidth(text)
        val padH   = 3
        val padV   = 2
        val bw     = textW + padH * 2
        val bh     = fm.height + padV * 2
        val bx     = rightX - bw
        val by     = centerY - bh / 2

        val badge = RoundRectangle2D.Double(bx.toDouble(), by.toDouble(), bw.toDouble(), bh.toDouble(), 8.0, 8.0)
        g2.color = bgColor
        g2.fill(badge)

        g2.color = textColor
        g2.drawString(text, bx + padH, by + padV + fm.ascent)

        return bx
    }

    // ------------------------------------------------------------------
    // Package-internal API (for testing and keyboard navigation)
    // ------------------------------------------------------------------

    /**
     * Programmatically selects the node with [id] and fires [onNodeSelected].
     * No-op if [id] is not present in the current graph.
     *
     * Also syncs [focusedNodeIndex] to the selected node so that subsequent
     * arrow-key presses continue from the newly selected position.
     *
     * This mirrors the single-click code path and is exposed so that tests can
     * verify callback invocation without simulating [java.awt.event.MouseEvent]s.
     */
    internal fun selectNode(id: String) {
        val node = nodeById[id] ?: return
        selectedNodeId = id
        focusedNodeIndex = navOrder.indexOfFirst { it.id == id }
        repaint()
        onNodeSelected?.invoke(node)
    }

    // ------------------------------------------------------------------
    // Keyboard navigation
    // ------------------------------------------------------------------

    /**
     * Sets up [InputMap] / [ActionMap] bindings on [JComponent.WHEN_FOCUSED] so keyboard
     * shortcuts fire only when this panel holds keyboard focus:
     *
     * - **↓ (Down)** — move focus to the next node (top → bottom visual order).
     * - **↑ (Up)**   — move focus to the previous node.
     * - **Enter**    — invoke [onNodeNavigated] on the focused node (checkout equivalent).
     * - **Space**    — invoke [onNodeSelected] on the focused node (select equivalent).
     */
    private fun setupKeyboardNavigation() {
        val im = getInputMap(JComponent.WHEN_FOCUSED)
        val am = actionMap

        im.put(KeyStroke.getKeyStroke(KeyEvent.VK_DOWN, 0), "stackgraph.nav.down")
        am.put("stackgraph.nav.down", object : AbstractAction() {
            override fun actionPerformed(e: java.awt.event.ActionEvent) = navigateDown()
        })

        im.put(KeyStroke.getKeyStroke(KeyEvent.VK_UP, 0), "stackgraph.nav.up")
        am.put("stackgraph.nav.up", object : AbstractAction() {
            override fun actionPerformed(e: java.awt.event.ActionEvent) = navigateUp()
        })

        im.put(KeyStroke.getKeyStroke(KeyEvent.VK_ENTER, 0), "stackgraph.nav.enter")
        am.put("stackgraph.nav.enter", object : AbstractAction() {
            override fun actionPerformed(e: java.awt.event.ActionEvent) = activateFocusedNode()
        })

        im.put(KeyStroke.getKeyStroke(KeyEvent.VK_SPACE, 0), "stackgraph.nav.space")
        am.put("stackgraph.nav.space", object : AbstractAction() {
            override fun actionPerformed(e: java.awt.event.ActionEvent) = selectFocusedNode()
        })
    }

    /**
     * Moves keyboard focus to the next node in visual top-to-bottom order.
     * If no node is focused yet, focuses the first node.
     */
    private fun navigateDown() {
        if (navOrder.isEmpty()) return
        focusedNodeIndex = if (focusedNodeIndex < 0) 0
                           else (focusedNodeIndex + 1).coerceAtMost(navOrder.size - 1)
        repaint()
    }

    /**
     * Moves keyboard focus to the previous node in visual top-to-bottom order.
     * If no node is focused yet, focuses the first node.
     */
    private fun navigateUp() {
        if (navOrder.isEmpty()) return
        focusedNodeIndex = if (focusedNodeIndex < 0) 0
                           else (focusedNodeIndex - 1).coerceAtLeast(0)
        repaint()
    }

    /**
     * Fires [onNodeNavigated] on the focused node (Enter key — checkout equivalent of double-click).
     * No-op when no node is focused.
     */
    private fun activateFocusedNode() {
        val node = navOrder.getOrNull(focusedNodeIndex) ?: return
        onNodeNavigated?.invoke(node)
    }

    /**
     * Selects the focused node and fires [onNodeSelected] (Space key — select equivalent of single-click).
     * No-op when no node is focused.
     */
    private fun selectFocusedNode() {
        val node = navOrder.getOrNull(focusedNodeIndex) ?: return
        selectedNodeId = node.id
        repaint()
        onNodeSelected?.invoke(node)
    }

    // ------------------------------------------------------------------
    // PR badge helpers
    // ------------------------------------------------------------------

    /**
     * Draws a PR state badge and returns the x coordinate immediately to the left of it.
     *
     * The badge text and colours are chosen based on the combined PR state and review decision:
     * - Merged  → "M"  purple
     * - Draft   → "D"  gray
     * - Approved → "✓" green
     * - Changes requested → "✗" red
     * - Open (no review yet) → "PR" blue
     */
    private fun paintPrBadge(
        g2: Graphics2D,
        fm: java.awt.FontMetrics,
        status: PrStatus,
        rightX: Int,
        centerY: Int,
    ): Int {
        val (text, bg, fg) = when {
            status.prInfo.state == PrState.MERGED ->
                Triple("M", StackGraphColors.BADGE_PR_MERGED_BG, StackGraphColors.BADGE_PR_MERGED_TEXT)
            status.prInfo.isDraft ->
                Triple("D", StackGraphColors.BADGE_PR_DRAFT_BG, StackGraphColors.BADGE_PR_DRAFT_TEXT)
            status.reviewState == ReviewState.APPROVED ->
                Triple("✓", StackGraphColors.BADGE_PR_APPROVED_BG, StackGraphColors.BADGE_PR_APPROVED_TEXT)
            status.reviewState == ReviewState.CHANGES_REQUESTED ->
                Triple("✗", StackGraphColors.BADGE_PR_CHANGES_BG, StackGraphColors.BADGE_PR_CHANGES_TEXT)
            else ->
                Triple("PR", StackGraphColors.BADGE_PR_OPEN_BG, StackGraphColors.BADGE_PR_OPEN_TEXT)
        }
        return paintBadge(g2, fm, text, rightX, centerY, bg, fg)
    }

    /**
     * Draws a small filled circle (CI status dot) and returns the x coordinate immediately
     * to the left of it.
     *
     * Colours: green = passing, red = failing, yellow = pending.
     */
    private fun paintCiDot(
        g2: Graphics2D,
        checksState: ChecksState,
        rightX: Int,
        centerY: Int,
    ): Int {
        val color = when (checksState) {
            ChecksState.PASSING -> StackGraphColors.CI_PASSING
            ChecksState.FAILING -> StackGraphColors.CI_FAILING
            ChecksState.PENDING -> StackGraphColors.CI_PENDING
            ChecksState.NONE    -> return rightX
        }
        val dotD = 8.0
        val dot  = Ellipse2D.Double(
            rightX - dotD,
            centerY - dotD / 2,
            dotD,
            dotD,
        )
        g2.color = color
        g2.fill(dot)
        return (rightX - dotD - 2).toInt()
    }

    // ------------------------------------------------------------------
    // Helpers
    // ------------------------------------------------------------------

    /** Truncates [text] with an ellipsis if it exceeds [maxWidth] pixels. */
    private fun truncate(text: String, fm: java.awt.FontMetrics, maxWidth: Int): String {
        if (maxWidth <= 0 || fm.stringWidth(text) <= maxWidth) return text
        val ellipsis = "…"
        val ellipsisW = fm.stringWidth(ellipsis)
        var result = text
        while (result.isNotEmpty() && fm.stringWidth(result) + ellipsisW > maxWidth) {
            result = result.dropLast(1)
        }
        return result + ellipsis
    }
}
