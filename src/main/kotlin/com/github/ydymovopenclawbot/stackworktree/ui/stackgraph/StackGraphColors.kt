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
    /**
     * Muted orange — branch has uncommitted local changes.
     *
     * Intentionally distinct from [STATUS_STALE] (amber) so users can visually
     * distinguish "dirty working tree" from "behind parent". Currently excluded
     * from the automatic health pipeline (which never performs a working-tree
     * scan); reserved for future use.
     */
    val STATUS_DIRTY: JBColor = JBColor(Color(0xF57C00), Color(0xBF6010))
    val STATUS_CONFLICT: JBColor = JBColor(Color(0xEA4335), Color(0xFF6B6B))
    /** Amber — branch is behind its parent and needs a rebase. */
    val STATUS_STALE: JBColor = JBColor(Color(0xFBBC04), Color(0xE6A817))
    /** Purple — branch has been merged into trunk. */
    val STATUS_MERGED: JBColor = JBColor(Color(0x8E44AD), Color(0xB06FCF))

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
    // PR state badge colours
    // ------------------------------------------------------------------

    /** Open PR — blue. */
    val BADGE_PR_OPEN_BG: JBColor   = JBColor(Color(0xE8F0FE), Color(0x1C2C5B))
    val BADGE_PR_OPEN_TEXT: JBColor = JBColor(Color(0x1A73E8), Color(0x8AB4F8))

    /** Draft PR — gray. */
    val BADGE_PR_DRAFT_BG: JBColor   = JBColor(Color(0xF1F3F4), Color(0x3A3A3A))
    val BADGE_PR_DRAFT_TEXT: JBColor = JBColor(Color(0x80868B), Color(0x9AA0A6))

    /** Approved PR — green. */
    val BADGE_PR_APPROVED_BG: JBColor   = JBColor(Color(0xE6F4EA), Color(0x1E3A26))
    val BADGE_PR_APPROVED_TEXT: JBColor = JBColor(Color(0x137333), Color(0x81C995))

    /** Changes requested — red. */
    val BADGE_PR_CHANGES_BG: JBColor   = JBColor(Color(0xFCE8E6), Color(0x3A1E1E))
    val BADGE_PR_CHANGES_TEXT: JBColor = JBColor(Color(0xC5221F), Color(0xF28B82))

    /** Merged PR — purple. */
    val BADGE_PR_MERGED_BG: JBColor   = JBColor(Color(0xF3E8FD), Color(0x2A1A3E))
    val BADGE_PR_MERGED_TEXT: JBColor = JBColor(Color(0x8E44AD), Color(0xB06FCF))

    // ------------------------------------------------------------------
    // CI dot colours (small filled circle)
    // ------------------------------------------------------------------

    val CI_PASSING: JBColor = JBColor(Color(0x34A853), Color(0x4CAF82))
    val CI_FAILING: JBColor = JBColor(Color(0xEA4335), Color(0xFF6B6B))
    val CI_PENDING: JBColor = JBColor(Color(0xFBBC04), Color(0xE6A817))

    // ------------------------------------------------------------------
    // Keyboard focus ring
    // ------------------------------------------------------------------

    /**
     * Dashed outer ring drawn around the keyboard-focused node.
     * Distinct from [NODE_SELECTED_BORDER] (solid blue selection ring) so users can
     * differentiate keyboard focus from mouse selection at a glance.
     */
    val KEYBOARD_FOCUS_RING: JBColor = JBColor(Color(0x1A73E8), Color(0x8AB4F8))

    // ------------------------------------------------------------------
    // Helper
    // ------------------------------------------------------------------

    /** Returns the border colour that corresponds to [status]. */
    fun borderForStatus(status: HealthStatus): JBColor = when (status) {
        HealthStatus.CLEAN    -> STATUS_CLEAN
        HealthStatus.DIRTY    -> STATUS_DIRTY
        HealthStatus.CONFLICT -> STATUS_CONFLICT
        HealthStatus.STALE    -> STATUS_STALE
        HealthStatus.MERGED   -> STATUS_MERGED
    }
}
