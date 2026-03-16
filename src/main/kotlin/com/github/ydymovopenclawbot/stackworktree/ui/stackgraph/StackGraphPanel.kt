package com.github.ydymovopenclawbot.stackworktree.ui.stackgraph

import com.intellij.util.ui.JBUI
import java.awt.BasicStroke
import java.awt.Dimension
import java.awt.Font
import java.awt.Graphics
import java.awt.Graphics2D
import java.awt.RenderingHints
import java.awt.event.MouseAdapter
import java.awt.event.MouseEvent
import java.awt.geom.CubicCurve2D
import java.awt.geom.Ellipse2D
import java.awt.geom.Rectangle2D
import java.awt.geom.RoundRectangle2D
import javax.swing.JPanel

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

    // ------------------------------------------------------------------
    // Internal state
    // ------------------------------------------------------------------

    private var graphData: StackGraphData = StackGraphData()
    private var layoutResult: StackGraphLayout.Result = StackGraphLayout.compute(StackGraphData())

    /** Id of the currently selected node, or `null` when nothing is selected. */
    var selectedNodeId: String? = null
        private set

    // ------------------------------------------------------------------
    // Initialisation
    // ------------------------------------------------------------------

    init {
        isOpaque = true
        background = StackGraphColors.PANEL_BG

        addMouseListener(object : MouseAdapter() {
            override fun mouseClicked(e: MouseEvent) {
                val hit = hitTest(e.x, e.y) ?: return
                when (e.clickCount) {
                    1 -> {
                        selectedNodeId = hit.id
                        repaint()
                        onNodeSelected?.invoke(hit)
                    }
                    2 -> onNodeNavigated?.invoke(hit)
                }
            }
        })
    }

    // ------------------------------------------------------------------
    // Public API
    // ------------------------------------------------------------------

    /**
     * Replaces the displayed graph with [data], recomputes the layout,
     * and schedules a repaint.
     *
     * Safe to call from any thread; Swing operations are performed on the EDT
     * via [revalidate]/[repaint] (which are thread-safe by contract).
     */
    fun updateGraph(data: StackGraphData) {
        graphData    = data
        layoutResult = StackGraphLayout.compute(data)
        selectedNodeId = null

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
        val nodeById = graphData.nodes.associateBy { it.id }
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
        val nodeById = graphData.nodes.associateBy { it.id }
        for ((id, rect) in layoutResult.nodeRects) {
            val node = nodeById[id] ?: continue
            paintNode(g2, node, rect, isSelected = (id == selectedNodeId))
        }
    }

    private fun paintNode(g2: Graphics2D, node: StackNodeData, rect: Rectangle2D, isSelected: Boolean) {
        val arc  = StackGraphLayout.CORNER_ARC.toDouble()
        val rrect = RoundRectangle2D.Double(rect.x, rect.y, rect.width, rect.height, arc, arc)

        // Fill
        g2.color = if (isSelected) StackGraphColors.NODE_SELECTED_BG else StackGraphColors.NODE_BG
        g2.fill(rrect)

        // Border
        val borderColor  = if (isSelected) StackGraphColors.NODE_SELECTED_BORDER
                           else            StackGraphColors.borderForStatus(node.healthStatus)
        val strokeWidth  = if (isSelected) 2.5f else 1.5f
        g2.color  = borderColor
        g2.stroke = BasicStroke(strokeWidth, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND)
        g2.draw(rrect)

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

        val fm        = g2.getFontMetrics(labelFont)
        val badgeArea = if (node.ahead > 0 || node.behind > 0) 60 else 0
        val maxLabelW = (rect.width - (textStartX - rect.x) - 10 - badgeArea).toInt()
        val label     = truncate(node.branchName, fm, maxLabelW)

        val labelY = (rect.y + (rect.height + fm.ascent - fm.descent) / 2).toInt()
        g2.drawString(label, textStartX.toInt(), labelY)

        // Ahead / behind badges
        if (node.ahead > 0 || node.behind > 0) {
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
                paintBadge(g2, bfm, "↑${node.ahead}", badgeX, badgeCy.toInt(),
                    StackGraphColors.BADGE_AHEAD_BG, StackGraphColors.BADGE_AHEAD_TEXT)
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
