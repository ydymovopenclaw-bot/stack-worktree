package com.github.ydymovopenclawbot.stackworktree.actions

import com.github.ydymovopenclawbot.stackworktree.git.GitLayer
import com.github.ydymovopenclawbot.stackworktree.ops.WorktreeOps
import com.github.ydymovopenclawbot.stackworktree.state.StackTreeStateListener
import com.github.ydymovopenclawbot.stackworktree.state.stackStateService
import com.github.ydymovopenclawbot.stackworktree.ui.CreateWorktreeDialog
import com.intellij.notification.NotificationGroupManager
import com.intellij.notification.NotificationType
import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.components.service
import com.intellij.openapi.diagnostic.logger
import com.intellij.openapi.progress.ProgressIndicator
import com.intellij.openapi.progress.Task
import com.intellij.openapi.project.DumbAwareAction
import git4idea.repo.GitRepositoryManager
import javax.swing.SwingUtilities

private val LOG = logger<CreateWorktreeFromBranchAction>()

class CreateWorktreeFromBranchAction : DumbAwareAction(
    "Create Worktree",
    "Create a git worktree for this branch",
    null,
) {
    override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.BGT

    override fun actionPerformed(e: AnActionEvent) {
        val project = e.project ?: return
        val gitLayer = project.service<GitLayer>()
        val branches = gitLayer.listLocalBranches()
        val repo = GitRepositoryManager.getInstance(project).repositories.firstOrNull()
        val currentBranch = repo?.currentBranchName

        val ops = WorktreeOps.forProject(project)
        val defaultBranch = branches.firstOrNull() ?: return

        val existingWtPaths = buildMap {
            gitLayer.worktreeList().filter { it.branch.isNotEmpty() }.forEach { put(it.branch, it.path) }
        }
        val worktreeBranches = existingWtPaths.keys

        val defaultBasePath = project.stackStateService().getWorktreeBasePath()
            ?: run {
                val projectDir = project.basePath ?: return
                "$projectDir/../${project.name}-worktrees"
            }

        val dialog = CreateWorktreeDialog(
            project = project,
            branches = branches,
            worktreeBranches = worktreeBranches,
            defaultBasePath = defaultBasePath,
            currentBranch = currentBranch,
            existingWorktreePaths = existingWtPaths,
        )
        if (!dialog.showAndGet()) return

        val selectedBranch = dialog.getSelectedBranch()
        val chosenPath = dialog.getChosenPath()
        val isNewBranch = dialog.isCreateNewBranch()

        if (dialog.isRememberDefault()) {
            val parentDir = java.io.File(chosenPath).parent
            if (parentDir != null) project.stackStateService().setWorktreeBasePath(parentDir)
        }

        object : Task.Backgroundable(project, "Creating worktree for '$selectedBranch'\u2026", false) {
            override fun run(indicator: ProgressIndicator) {
                indicator.isIndeterminate = true
                try {
                    if (isNewBranch) {
                        gitLayer.createBranch(selectedBranch, dialog.getBaseBranch())
                    }
                    val wt = ops.createWorktreeForBranch(selectedBranch, chosenPath)
                    project.messageBus.syncPublisher(StackTreeStateListener.TOPIC).stateChanged()
                    NotificationGroupManager.getInstance()
                        .getNotificationGroup("StackWorktree")
                        .createNotification("Worktree created at '$chosenPath'.", NotificationType.INFORMATION)
                        .notify(project)
                    if (dialog.isOpenAfterCreation()) {
                        SwingUtilities.invokeLater { OpenInNewWindowAction.perform(wt) }
                    }
                } catch (ex: Exception) {
                    if (isNewBranch) {
                        try { gitLayer.deleteBranch(selectedBranch) } catch (e: Exception) {
                            LOG.warn("Rollback: failed to delete orphaned branch '$selectedBranch'", e)
                        }
                    }
                    LOG.warn("CreateWorktreeFromBranch: failed", ex)
                    NotificationGroupManager.getInstance()
                        .getNotificationGroup("StackWorktree")
                        .createNotification("Failed: ${ex.message}", NotificationType.ERROR)
                        .notify(project)
                }
            }
        }.queue()
    }

    override fun update(e: AnActionEvent) {
        e.presentation.isEnabledAndVisible = e.project != null
    }

    companion object {
        fun shouldDisable(branch: String, worktreePaths: Map<String, String>): Boolean =
            branch in worktreePaths
    }
}
