package com.github.ydymovopenclawbot.stackworktree.ui.actions

import com.intellij.icons.AllIcons
import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent

/** Adds a branch to the active stack (stub — implementation planned for a future sprint). */
class AddBranchAction : AnAction(
    "Add Branch",
    "Add a branch to the current stack",
    AllIcons.Vcs.Branch,
) {
    override fun actionPerformed(e: AnActionEvent) = Unit
    override fun getActionUpdateThread() = ActionUpdateThread.BGT
}
