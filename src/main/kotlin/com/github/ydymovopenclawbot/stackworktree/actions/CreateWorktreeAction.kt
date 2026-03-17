package com.github.ydymovopenclawbot.stackworktree.actions

import com.github.ydymovopenclawbot.stackworktree.git.WorktreeException
import com.github.ydymovopenclawbot.stackworktree.ops.WorktreeOps
import com.github.ydymovopenclawbot.stackworktree.state.StackTreeStateListener
import com.github.ydymovopenclawbot.stackworktree.state.stackStateService
import com.github.ydymovopenclawbot.stackworktree.ui.WorktreePathDialog
import com.intellij.notification.NotificationGroupManager
import com.intellij.notification.NotificationType
import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.diagnostic.logger
import com.intellij.openapi.progress.ProgressIndicator
import com.intellij.openapi.progress.Task

private val LOG = logger<CreateWorktreeAction>()

/**
 * Context-menu action: **Create Worktree**.
 *
 * Creates a linked git worktree for the selected branch node so the branch can be
 * checked out concurrently in a separate directory.
 *
 * Flow:
 * 1. Resolves the default path via [WorktreeOps.defaultWorktreePath].
 * 2. Shows [WorktreePathDialog] so the user can override the path and optionally
 *    remember the chosen base directory as the new default.
 * 3. Runs `git worktree add` on a background thread.
 * 4. On success: fires [StackTreeStateListener] to refresh the graph.
 * 5. On error: shows a balloon notification with the failure reason.
 *
 * Visible and enabled only when a branch node is selected
 * ([StackDataKeys.SELECTED_BRANCH_NAME] is present) **and** that branch does not yet
 * have a worktree recorded in [StackStateService][com.github.ydymovopenclawbot.stackworktree.state.StackStateService].
 */
class CreateWorktreeAction : AnAction() {

    override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.BGT

    override fun update(e: AnActionEvent) {
        val project = e.project
        val branch  = e.getData(StackDataKeys.SELECTED_BRANCH_NAME)
        e.presentation.isEnabledAndVisible = project != null
            && branch != null
            && project.stackStateService().getWorktreePath(branch) == null
    }

    override fun actionPerformed(e: AnActionEvent) {
        val project = e.project ?: return
        val branch  = e.getData(StackDataKeys.SELECTED_BRANCH_NAME) ?: return

        val ops          = WorktreeOps.forProject(project)
        val defaultPath  = ops.defaultWorktreePath(branch)

        val dialog = WorktreePathDialog(project, branch, defaultPath)
        if (!dialog.showAndGet()) return   // user cancelled

        val chosenPath = dialog.getChosenPath()

        // Persist the user's chosen base directory as the new default, if requested.
        if (dialog.isRememberDefault()) {
            val parentDir = java.io.File(chosenPath).parent
            if (parentDir != null) {
                project.stackStateService().setWorktreeBasePath(parentDir)
            }
        }

        LOG.info("CreateWorktreeAction: creating worktree for '$branch' at '$chosenPath'")

        object : Task.Backgroundable(project, "Creating worktree for '$branch'…", false) {
            override fun run(indicator: ProgressIndicator) {
                indicator.isIndeterminate = true
                try {
                    ops.createWorktreeForBranch(branch, chosenPath)
                    project.messageBus
                        .syncPublisher(StackTreeStateListener.TOPIC)
                        .stateChanged()
                    notify(project, "Worktree for '$branch' created at '$chosenPath'.",
                        NotificationType.INFORMATION)
                } catch (ex: WorktreeException) {
                    LOG.warn("CreateWorktreeAction: git worktree add failed", ex)
                    notify(project, "Failed to create worktree: ${ex.message}", NotificationType.ERROR)
                } catch (ex: IllegalStateException) {
                    LOG.warn("CreateWorktreeAction: branch already has worktree", ex)
                    notify(project, ex.message ?: "Branch already has a worktree.", NotificationType.WARNING)
                } catch (ex: Exception) {
                    LOG.error("CreateWorktreeAction: unexpected error", ex)
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
