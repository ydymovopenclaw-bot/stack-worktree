package com.github.ydymovopenclawbot.stackworktree.ui.actions

import com.intellij.icons.AllIcons
import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent

/**
 * Rebases all stack branches onto the latest mainline (disabled — coming in a future sprint).
 *
 * The tooltip is set on every [update] call so it remains visible even in the disabled state.
 */
class RestackAction : AnAction(
    "Restack",
    "Coming soon: rebase all stacks onto the latest main",
    AllIcons.General.Reset,
) {
    override fun actionPerformed(e: AnActionEvent) = Unit

    override fun update(e: AnActionEvent) {
        e.presentation.isEnabled = false
        e.presentation.description = "Coming soon: rebase all stacks onto the latest main"
    }

    override fun getActionUpdateThread() = ActionUpdateThread.BGT
}
