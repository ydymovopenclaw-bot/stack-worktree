package com.github.ydymovopenclawbot.stackworktree.actions

import com.github.ydymovopenclawbot.stackworktree.git.GitLayer
import com.github.ydymovopenclawbot.stackworktree.git.WorktreeException
import com.github.ydymovopenclawbot.stackworktree.ops.WorktreeOps
import com.github.ydymovopenclawbot.stackworktree.state.StackTreeStateListener
import com.github.ydymovopenclawbot.stackworktree.state.stackStateService
import com.github.ydymovopenclawbot.stackworktree.ui.CreateWorktreeDialog
import com.intellij.notification.NotificationGroupManager
import com.intellij.notification.NotificationType
import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.components.service
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
 * 2. Shows [CreateWorktreeDialog] so the user can override the path and optionally
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

        val ops      = WorktreeOps.forProject(project)
        val gitLayer = project.service<GitLayer>()
        // Note: listLocalBranches() runs `git branch --list` which is typically < 100ms.
        // IntelliJ's own git plugin also calls lightweight git commands on the EDT before
        // showing dialogs, so this is consistent with platform conventions.
        val branches = gitLayer.listLocalBranches()

        val dialog = CreateWorktreeDialog(
            project           = project,
            branches          = branches,
            preselectedBranch = branch,
            pathResolver      = { ops.defaultWorktreePath(it) },
        )
        if (!dialog.showAndGet()) return   // user cancelled

        val chosenPath     = dialog.getChosenPath()
        val selectedBranch = dialog.getSelectedBranch()
        val isNewBranch    = dialog.isCreateNewBranch()
        val baseBranch     = dialog.getBaseBranch()
        val openAfter      = dialog.isOpenAfterCreation()

        // Persist the user's chosen base directory as the new default, if requested.
        if (dialog.isRememberDefault()) {
            val parentDir = java.io.File(chosenPath).parent
            if (parentDir != null) {
                project.stackStateService().setWorktreeBasePath(parentDir)
            }
        }

        LOG.info("CreateWorktreeAction: creating worktree for '$selectedBranch' at '$chosenPath'")

        object : Task.Backgroundable(project, "Creating worktree for '$selectedBranch'…", false) {
            override fun run(indicator: ProgressIndicator) {
                indicator.isIndeterminate = true
                try {
                    if (isNewBranch) {
                        gitLayer.createBranch(selectedBranch, baseBranch)
                    }
                    val wt = ops.createWorktreeForBranch(selectedBranch, chosenPath)
                    project.messageBus
                        .syncPublisher(StackTreeStateListener.TOPIC)
                        .stateChanged()
                    notify(project, "Worktree for '$selectedBranch' created at '$chosenPath'.",
                        NotificationType.INFORMATION)
                    if (openAfter) {
                        ApplicationManager.getApplication().invokeLater {
                            OpenInNewWindowAction.perform(wt)
                        }
                    }
                } catch (ex: WorktreeException) {
                    if (isNewBranch) {
                        try { gitLayer.deleteBranch(selectedBranch) } catch (_: Exception) {}
                    }
                    LOG.warn("CreateWorktreeAction: git worktree add failed", ex)
                    notify(project, "Failed to create worktree: ${ex.message}", NotificationType.ERROR)
                } catch (ex: IllegalStateException) {
                    if (isNewBranch) {
                        try { gitLayer.deleteBranch(selectedBranch) } catch (_: Exception) {}
                    }
                    LOG.warn("CreateWorktreeAction: branch already has worktree", ex)
                    notify(project, ex.message ?: "Branch already has a worktree.", NotificationType.WARNING)
                } catch (ex: Exception) {
                    if (isNewBranch) {
                        try { gitLayer.deleteBranch(selectedBranch) } catch (_: Exception) {}
                    }
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
