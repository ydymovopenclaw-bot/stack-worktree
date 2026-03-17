package com.github.ydymovopenclawbot.stackworktree.ui.actions

import com.intellij.icons.AllIcons
import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent

/**
 * Syncs stacks with the remote (disabled — coming in a future sprint).
 *
 * The tooltip is set on every [update] call so it remains visible even in the disabled state.
 */
class SyncAction : AnAction(
    "Sync",
    "Coming soon: sync stacks with the remote",
    AllIcons.Actions.Download,
) {
    override fun actionPerformed(e: AnActionEvent) = Unit

    override fun update(e: AnActionEvent) {
        e.presentation.isEnabled = false
        e.presentation.text = "Sync (coming soon)"
        e.presentation.description = "Coming soon: sync stacks with the remote"
    }

    override fun getActionUpdateThread() = ActionUpdateThread.BGT
}
