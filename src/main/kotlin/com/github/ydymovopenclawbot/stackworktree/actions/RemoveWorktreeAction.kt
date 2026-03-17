package com.github.ydymovopenclawbot.stackworktree.actions

import com.github.ydymovopenclawbot.stackworktree.git.WorktreeException
import com.github.ydymovopenclawbot.stackworktree.ops.WorktreeOps
import com.github.ydymovopenclawbot.stackworktree.state.StackTreeStateListener
import com.github.ydymovopenclawbot.stackworktree.state.stackStateService
import com.intellij.notification.NotificationGroupManager
import com.intellij.notification.NotificationType
import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.diagnostic.logger
import com.intellij.openapi.progress.ProgressIndicator
import com.intellij.openapi.progress.Task
import com.intellij.openapi.ui.Messages

private val LOG = logger<RemoveWorktreeAction>()

/**
 * Context-menu action: **Remove Worktree**.
 *
 * Removes the linked git worktree bound to the selected branch and clears the binding
 * from persisted state.
 *
 * Flow:
 * 1. Looks up the bound worktree path from
 *    [StackStateService][com.github.ydymovopenclawbot.stackworktree.state.StackStateService].
 * 2. Shows a confirmation dialog listing the path that will be deleted.
 * 3. Runs `git worktree remove` on a background thread.
 * 4. On success: fires [StackTreeStateListener] to refresh the graph.
 * 5. On error: shows a balloon notification with the failure reason.
 *
 * Visible and enabled only when a branch node is selected
 * ([StackDataKeys.SELECTED_BRANCH_NAME] is present) **and** that branch has a worktree
 * path recorded in the state service.
 */
class RemoveWorktreeAction : AnAction() {

    override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.BGT

    override fun update(e: AnActionEvent) {
        val project = e.project
        val branch  = e.getData(StackDataKeys.SELECTED_BRANCH_NAME)
        e.presentation.isEnabledAndVisible = project != null
            && branch != null
            && project.stackStateService().getWorktreePath(branch) != null
    }

    override fun actionPerformed(e: AnActionEvent) {
        val project = e.project ?: return
        val branch  = e.getData(StackDataKeys.SELECTED_BRANCH_NAME) ?: return
        val path    = project.stackStateService().getWorktreePath(branch) ?: return

        val confirmed = Messages.showYesNoDialog(
            project,
            "Remove the worktree for '$branch'?\n\nDirectory: $path\n\nThe directory will be deleted.",
            "Remove Worktree",
            "Remove",
            "Cancel",
            Messages.getWarningIcon(),
        )
        if (confirmed != Messages.YES) return

        LOG.info("RemoveWorktreeAction: removing worktree for '$branch' at '$path'")

        object : Task.Backgroundable(project, "Removing worktree for '$branch'…", false) {
            override fun run(indicator: ProgressIndicator) {
                indicator.isIndeterminate = true
                val ops = WorktreeOps.forProject(project)
                try {
                    ops.removeWorktreeForBranch(branch)
                    project.messageBus
                        .syncPublisher(StackTreeStateListener.TOPIC)
                        .stateChanged()
                    notify(project, "Worktree for '$branch' removed.", NotificationType.INFORMATION)
                } catch (ex: WorktreeException) {
                    LOG.warn("RemoveWorktreeAction: git worktree remove failed", ex)
                    notify(project, "Failed to remove worktree: ${ex.message}", NotificationType.ERROR)
                } catch (ex: Exception) {
                    LOG.error("RemoveWorktreeAction: unexpected error", ex)
                    notify(project, "Unexpected error: ${ex.message}", NotificationType.ERROR)
                }
            }
        }.queue()
    }

    private fun notify(project: com.intellij.openapi.project.Project, message: String, type: NotificationType) {
        NotificationGroupManager.getInstance()
            .getNotificationGroup("StackWorktree")
            .createNotification(message, type)
            .notify(project)
    }
}
