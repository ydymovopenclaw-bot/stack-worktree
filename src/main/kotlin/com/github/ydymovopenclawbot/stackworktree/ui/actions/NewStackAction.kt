package com.github.ydymovopenclawbot.stackworktree.ui.actions

import com.intellij.icons.AllIcons
import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent

/** Creates a new named stack (stub — implementation planned for a future sprint). */
class NewStackAction : AnAction(
    "New Stack",
    "Create a new named stack",
    AllIcons.General.Add,
) {
    override fun actionPerformed(e: AnActionEvent) = Unit
    override fun getActionUpdateThread() = ActionUpdateThread.BGT
}
