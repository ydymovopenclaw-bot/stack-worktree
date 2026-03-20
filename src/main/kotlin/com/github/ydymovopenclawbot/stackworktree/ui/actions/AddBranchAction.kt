package com.github.ydymovopenclawbot.stackworktree.ui.actions

import com.intellij.icons.AllIcons
import com.intellij.notification.NotificationGroupManager
import com.intellij.notification.NotificationType
import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent

/**
 * Adds a branch to the active stack.
 *
 * Full implementation (dialog + [com.github.ydymovopenclawbot.stackworktree.ops.OpsLayer.addBranchToStack]
 * call) is deferred to a future sprint.  Until then the action shows a balloon so users
 * get explicit feedback instead of a silent no-op when they trigger the shortcut.
 *
 * TODO(S8.x): Replace the notification with the full AddBranchToStack dialog flow.
 */
class AddBranchAction : AnAction(
    "Add Branch",
    "Add a branch to the current stack",
    AllIcons.Vcs.Branch,
) {
    override fun getActionUpdateThread() = ActionUpdateThread.BGT

    override fun update(e: AnActionEvent) {
        e.presentation.isEnabled = e.project != null
    }

    override fun actionPerformed(e: AnActionEvent) {
        val project = e.project ?: return
        NotificationGroupManager.getInstance()
            .getNotificationGroup("StackWorktree")
            .createNotification(
                "Add Branch is not yet implemented. Use the right-click context menu on a node to insert a branch.",
                NotificationType.INFORMATION,
            )
            .notify(project)
    }
}
