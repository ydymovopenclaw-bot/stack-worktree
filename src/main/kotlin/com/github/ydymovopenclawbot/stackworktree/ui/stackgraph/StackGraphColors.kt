package com.github.ydymovopenclawbot.stackworktree.ui.stackgraph

import com.intellij.ui.JBColor
import java.awt.Color

/**
 * Centralised colour palette for the stack graph panel.
 *
 * Every constant is a [JBColor] with explicit light and dark variants so the
 * panel automatically adapts when the IDE theme changes — no manual detection
 * required.
 */
object StackGraphColors {

    // ------------------------------------------------------------------
    // Canvas / panel background
    // ------------------------------------------------------------------

    val PANEL_BG: JBColor = JBColor(Color(0xF5F5F5), Color(0x2B2B2B))

    // ------------------------------------------------------------------
    // Node fill & text
    // ------------------------------------------------------------------

    /** Default fill for a non-selected node. */
    val NODE_BG: JBColor = JBColor(Color(0xFFFFFF), Color(0x3C3F41))

    /** Fill tint applied on top of NODE_BG when a node is selected. */
    val NODE_SELECTED_BG: JBColor = JBColor(Color(0xE8F0FE), Color(0x2D3F6B))

    /** Primary text colour for the branch name label. */
    val NODE_TEXT: JBColor = JBColor(Color(0x1A1A1A), Color(0xBBBBBB))

    /** Fallback border used when no health status overrides it. */
    val NODE_BORDER: JBColor = JBColor(Color(0xCCCCCC), Color(0x555555))

    /** Thicker border drawn around the selected node. */
    val NODE_SELECTED_BORDER: JBColor = JBColor(Color(0x4285F4), Color(0x6EA6FF))

    // ------------------------------------------------------------------
    // Health-status border colours (one per HealthStatus variant)
    // ------------------------------------------------------------------

    val STATUS_CLEAN: JBColor = JBColor(Color(0x34A853), Color(0x4CAF82))
    val STATUS_DIRTY: JBColor = JBColor(Color(0xFBBC04), Color(0xE6A817))
    val STATUS_CONFLICT: JBColor = JBColor(Color(0xEA4335), Color(0xFF6B6B))
    val STATUS_STALE: JBColor = JBColor(Color(0x9AA0A6), Color(0x666666))

    // ------------------------------------------------------------------
    // Ahead / behind badges
    // ------------------------------------------------------------------

    val BADGE_AHEAD_BG: JBColor = JBColor(Color(0xE6F4EA), Color(0x1E3A26))
    val BADGE_AHEAD_TEXT: JBColor = JBColor(Color(0x137333), Color(0x81C995))

    val BADGE_BEHIND_BG: JBColor = JBColor(Color(0xFCE8E6), Color(0x3A1E1E))
    val BADGE_BEHIND_TEXT: JBColor = JBColor(Color(0xC5221F), Color(0xF28B82))

    // ------------------------------------------------------------------
    // Worktree badge — teal pill shown when a linked worktree is bound
    // ------------------------------------------------------------------

    val BADGE_WORKTREE_BG: JBColor = JBColor(Color(0xE8F5E9), Color(0x1B3A2B))
    val BADGE_WORKTREE_TEXT: JBColor = JBColor(Color(0x2E7D32), Color(0x66BB6A))

    // ------------------------------------------------------------------
    // Edge (connector line between parent and child nodes)
    // ------------------------------------------------------------------

    val EDGE: JBColor = JBColor(Color(0xBBBBBB), Color(0x555555))

    // ------------------------------------------------------------------
    // "Current branch" indicator dot
    // ------------------------------------------------------------------

    val CURRENT_BRANCH_DOT: JBColor = JBColor(Color(0x4285F4), Color(0x6EA6FF))

    // ------------------------------------------------------------------
    // Helper
    // ------------------------------------------------------------------

    /** Returns the border colour that corresponds to [status]. */
    fun borderForStatus(status: HealthStatus): JBColor = when (status) {
        HealthStatus.CLEAN    -> STATUS_CLEAN
        HealthStatus.DIRTY    -> STATUS_DIRTY
        HealthStatus.CONFLICT -> STATUS_CONFLICT
        HealthStatus.STALE    -> STATUS_STALE
    }
}
