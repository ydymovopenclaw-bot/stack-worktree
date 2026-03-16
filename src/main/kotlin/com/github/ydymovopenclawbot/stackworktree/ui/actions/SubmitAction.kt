package com.github.ydymovopenclawbot.stackworktree.ui.actions

import com.intellij.icons.AllIcons
import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent

/**
 * Submits stack branches for code review (disabled — coming in a future sprint).
 *
 * The tooltip is set on every [update] call so it remains visible even in the disabled state.
 */
class SubmitAction : AnAction(
    "Submit",
    "Coming soon: submit stack branches for code review",
    AllIcons.Actions.Upload,
) {
    override fun actionPerformed(e: AnActionEvent) = Unit

    override fun update(e: AnActionEvent) {
        e.presentation.isEnabled = false
        e.presentation.description = "Coming soon: submit stack branches for code review"
    }

    override fun getActionUpdateThread() = ActionUpdateThread.BGT
}
