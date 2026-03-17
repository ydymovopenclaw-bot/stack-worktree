package com.github.ydymovopenclawbot.stackworktree.ui

import com.github.ydymovopenclawbot.stackworktree.git.Worktree
import com.intellij.ide.util.PropertiesComponent
import com.intellij.openapi.project.Project
import com.intellij.ui.JBColor
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBPanel
import com.intellij.util.ui.JBUI
import java.awt.BorderLayout
import java.awt.Color
import java.awt.Cursor
import java.awt.Dimension
import java.awt.FlowLayout
import java.awt.Font
import java.awt.Graphics
import java.awt.Graphics2D
import java.awt.RenderingHints
import java.awt.event.MouseAdapter
import java.awt.event.MouseEvent
import java.awt.geom.RoundRectangle2D
import java.io.File
import javax.swing.BoxLayout
import javax.swing.JPanel
import javax.swing.SwingConstants

private const val COLLAPSE_KEY = "stackworktree.worktreeListPanel.collapsed"

/**
 * Collapsible panel that lists all git worktrees for the current repository.
 *
 * Shows both StackTree-managed worktrees (with a "Stack" badge) and worktrees
 * created externally by the user.  Clicking a tracked worktree row fires
 * [onWorktreeSelected] so the caller can forward the selection to the graph panel.
 *
 * Each row displays:
 * - Directory name of the worktree path (full path shown in tooltip)
 * - Checked-out branch name (bold) or "(detached)" in italics
 * - 7-character HEAD short hash in a secondary colour
 * - "Stack" pill badge when the branch is registered in the StackTree graph
 *
 * Collapse state is persisted across IDE restarts via [PropertiesComponent] so
 * the panel reopens in the same position the user left it.
 *
 * **Must be updated on the EDT.**  The [refresh] method rebuilds all rows;
 * callers on background threads must dispatch via [javax.swing.SwingUtilities.invokeLater].
 */
class WorktreeListPanel(
    private val project: Project,
    private val onWorktreeSelected: (Worktree) -> Unit,
) : JBPanel<WorktreeListPanel>(BorderLayout()) {

    // -------------------------------------------------------------------------
    // Sub-components
    // -------------------------------------------------------------------------

    private val props = PropertiesComponent.getInstance(project)

    private val headerLabel = JBLabel("Worktrees").apply {
        font = JBUI.Fonts.label(12f).deriveFont(Font.BOLD)
    }

    private val arrowLabel = JBLabel().apply {
        horizontalAlignment = SwingConstants.CENTER
    }

    private val contentPanel = JBPanel<JBPanel<*>>().apply {
        layout = BoxLayout(this, BoxLayout.Y_AXIS)
        isOpaque = false
    }

    // -------------------------------------------------------------------------
    // Collapsed state — backed by PropertiesComponent for persistence
    // -------------------------------------------------------------------------

    private var collapsed: Boolean
        get()      = props.getBoolean(COLLAPSE_KEY, false)
        set(value) {
            props.setValue(COLLAPSE_KEY, value)
            contentPanel.isVisible = !value
            updateArrow(value)
            revalidate()
            repaint()
        }

    // -------------------------------------------------------------------------
    // Initialisation
    // -------------------------------------------------------------------------

    init {
        isOpaque = false
        border = JBUI.Borders.customLine(SEPARATOR_COLOR, 1, 0, 0, 0)

        val headerPanel = JBPanel<JBPanel<*>>(BorderLayout()).apply {
            isOpaque = false
            border = JBUI.Borders.empty(4, 6, 4, 6)
            cursor = Cursor.getPredefinedCursor(Cursor.HAND_CURSOR)

            val leftFlow = JPanel(FlowLayout(FlowLayout.LEFT, 4, 0)).apply {
                isOpaque = false
                add(arrowLabel)
                add(headerLabel)
            }
            add(leftFlow, BorderLayout.WEST)

            addMouseListener(object : MouseAdapter() {
                override fun mouseClicked(e: MouseEvent) {
                    collapsed = !collapsed
                }
            })
        }

        add(headerPanel, BorderLayout.NORTH)
        add(contentPanel, BorderLayout.CENTER)

        // Restore persisted state without triggering a redundant write.
        val initial = props.getBoolean(COLLAPSE_KEY, false)
        contentPanel.isVisible = !initial
        updateArrow(initial)
    }

    // -------------------------------------------------------------------------
    // Public API
    // -------------------------------------------------------------------------

    /**
     * Rebuilds the row list to reflect [worktrees].
     *
     * [trackedBranches] is the set of branch names that are registered as nodes
     * in the StackTree stack graph; rows for those branches show a "Stack" badge
     * and respond to click events.
     *
     * Must be called on the EDT.
     */
    fun refresh(worktrees: List<Worktree>, trackedBranches: Set<String>) {
        contentPanel.removeAll()
        headerLabel.text = "Worktrees (${worktrees.size})"

        for (wt in worktrees) {
            contentPanel.add(createRow(wt, isTracked = wt.branch in trackedBranches))
        }

        contentPanel.revalidate()
        contentPanel.repaint()
    }

    // -------------------------------------------------------------------------
    // Row construction
    // -------------------------------------------------------------------------

    private fun createRow(wt: Worktree, isTracked: Boolean): JPanel {
        val row = JBPanel<JBPanel<*>>(BorderLayout()).apply {
            isOpaque = false
            border = JBUI.Borders.empty(2, 10, 2, 10)

            if (isTracked) {
                cursor = Cursor.getPredefinedCursor(Cursor.HAND_CURSOR)
                addMouseListener(object : MouseAdapter() {
                    override fun mouseClicked(e: MouseEvent) = onWorktreeSelected(wt)
                    override fun mouseEntered(e: MouseEvent) {
                        isOpaque = true
                        background = ROW_HOVER_COLOR
                        repaint()
                    }
                    override fun mouseExited(e: MouseEvent) {
                        isOpaque = false
                        repaint()
                    }
                })
            }
        }

        // ── Left: path directory name + branch ────────────────────────────────
        val leftFlow = JPanel(FlowLayout(FlowLayout.LEFT, 6, 0)).apply { isOpaque = false }

        val dirName = File(wt.path).name.ifEmpty { wt.path }
        // The main worktree uses a slightly bolder path label so it is easy to identify.
        leftFlow.add(JBLabel(dirName).apply {
            font        = if (wt.isMain) JBUI.Fonts.label(11f).deriveFont(Font.BOLD)
                          else           JBUI.Fonts.label(11f)
            foreground  = SECONDARY_TEXT
            toolTipText = wt.path
        })

        if (wt.branch.isNotEmpty()) {
            leftFlow.add(JBLabel(wt.branch).apply {
                font       = JBUI.Fonts.label(11f).deriveFont(Font.BOLD)
                foreground = BRANCH_TEXT_COLOR
            })
        } else {
            leftFlow.add(JBLabel("(detached)").apply {
                font       = JBUI.Fonts.label(11f).deriveFont(Font.ITALIC)
                foreground = SECONDARY_TEXT
            })
        }
        row.add(leftFlow, BorderLayout.CENTER)

        // ── Right: short SHA + optional "Stack" badge ─────────────────────────
        val rightFlow = JPanel(FlowLayout(FlowLayout.RIGHT, 4, 0)).apply { isOpaque = false }

        val shortHash = wt.head.take(7)
        if (shortHash.isNotEmpty()) {
            rightFlow.add(JBLabel(shortHash).apply {
                font       = JBUI.Fonts.label(10f)
                foreground = SECONDARY_TEXT
            })
        }

        if (isTracked) rightFlow.add(StackBadge())

        row.add(rightFlow, BorderLayout.EAST)

        return row
    }

    // -------------------------------------------------------------------------
    // Arrow
    // -------------------------------------------------------------------------

    private fun updateArrow(isCollapsed: Boolean) {
        arrowLabel.text      = if (isCollapsed) "▶" else "▼"
        arrowLabel.font      = JBUI.Fonts.label(9f)
        arrowLabel.foreground = SECONDARY_TEXT
    }

    // -------------------------------------------------------------------------
    // Colours
    // -------------------------------------------------------------------------

    companion object {
        val SEPARATOR_COLOR: JBColor  = JBColor(Color(0xDDDDDD), Color(0x444444))
        val SECONDARY_TEXT: JBColor   = JBColor(Color(0x888888), Color(0x777777))
        val BRANCH_TEXT_COLOR: JBColor = JBColor(Color(0x1A1A1A), Color(0xBBBBBB))
        val ROW_HOVER_COLOR: JBColor  = JBColor(Color(0xEEF5FE), Color(0x2D3F6B))
        val BADGE_BG: JBColor         = JBColor(Color(0x4285F4), Color(0x3A5FBF))
        val BADGE_TEXT: JBColor       = JBColor(Color.WHITE, Color.WHITE)
    }
}

// =============================================================================
// StackBadge — small pill painted via Java2D for correct rounded corners on all L&Fs
// =============================================================================

/**
 * Small rounded "Stack" pill badge drawn via Java2D rather than relying on a
 * bordered [JBLabel] so the corners are consistently rounded on every look-and-feel.
 */
private class StackBadge : JPanel() {

    private val text    = "Stack"
    private val hPad    = JBUI.scale(5)
    private val vPad    = JBUI.scale(2)
    private val badgeFont = JBUI.Fonts.label(9f).deriveFont(Font.BOLD)

    init {
        isOpaque = false
        val fm = getFontMetrics(badgeFont)
        preferredSize = Dimension(fm.stringWidth(text) + hPad * 2, fm.height + vPad * 2)
    }

    override fun paintComponent(g: Graphics) {
        val g2 = g as Graphics2D
        g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
        g2.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON)

        val arc   = height.toDouble()
        val shape = RoundRectangle2D.Double(0.0, 0.0, width.toDouble(), height.toDouble(), arc, arc)

        g2.color = WorktreeListPanel.BADGE_BG
        g2.fill(shape)

        g2.font  = badgeFont
        g2.color = WorktreeListPanel.BADGE_TEXT
        val fm = g2.getFontMetrics(badgeFont)
        val tx = (width  - fm.stringWidth(text)) / 2
        val ty = (height + fm.ascent - fm.descent) / 2
        g2.drawString(text, tx, ty)
    }
}
