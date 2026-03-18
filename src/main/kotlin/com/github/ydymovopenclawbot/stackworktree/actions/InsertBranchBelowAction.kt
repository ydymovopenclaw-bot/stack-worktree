package com.github.ydymovopenclawbot.stackworktree.actions

import com.github.ydymovopenclawbot.stackworktree.git.WorktreeException
import com.github.ydymovopenclawbot.stackworktree.ops.OpsLayer
import com.github.ydymovopenclawbot.stackworktree.ops.OpsLayerImpl
import com.intellij.notification.NotificationGroupManager
import com.intellij.notification.NotificationType
import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.diagnostic.logger
import com.intellij.openapi.progress.ProgressIndicator
import com.intellij.openapi.progress.Task
import com.intellij.openapi.ui.Messages

private val LOG = logger<InsertBranchBelowAction>()

/**
 * Context-menu action: **Insert Branch Below**.
 *
 * Creates a new branch as a direct child of the selected branch and re-parents all
 * existing children of the selected branch to the new branch.
 *
 * ```
 * Before:  … → selected → C1, C2, …
 * After:   … → selected → <new> → C1, C2, …
 * ```
 *
 * The action is enabled only when a branch node is selected in the Stacks panel
 * ([StackDataKeys.SELECTED_BRANCH_NAME] is present in the data context).
 */
class InsertBranchBelowAction : AnAction() {

    override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.BGT

    override fun update(e: AnActionEvent) {
        e.presentation.isEnabledAndVisible =
            e.getData(StackDataKeys.SELECTED_BRANCH_NAME) != null
    }

    override fun actionPerformed(e: AnActionEvent) {
        val project = e.project ?: return
        val targetBranch = e.getData(StackDataKeys.SELECTED_BRANCH_NAME) ?: return

        val newBranchName = Messages.showInputDialog(
            project,
            "Enter a name for the new branch to insert below '$targetBranch':",
            "Insert Branch Below",
            null,
        )?.trim() ?: return  // user cancelled

        if (!isValidBranchName(newBranchName)) {
            Messages.showErrorDialog(
                project,
                "'$newBranchName' is not a valid git branch name.",
                "Invalid Branch Name",
            )
            return
        }

        LOG.info("InsertBranchBelowAction: inserting '$newBranchName' below '$targetBranch'")

        object : Task.Backgroundable(project, "Inserting branch below '$targetBranch'…", true) {
            override fun run(indicator: ProgressIndicator) {
                indicator.isIndeterminate = true
                try {
                    val ops: OpsLayer = OpsLayerImpl.forProject(project)
                    ops.insertBranchBelow(targetBranch, newBranchName)
                } catch (ex: WorktreeException) {
                    LOG.warn("InsertBranchBelowAction: failed", ex)
                    notify("Insert branch below failed: ${ex.message}", NotificationType.ERROR)
                } catch (ex: IllegalStateException) {
                    LOG.warn("InsertBranchBelowAction: invalid state", ex)
                    notify(ex.message ?: "Invalid state.", NotificationType.WARNING)
                } catch (ex: Exception) {
                    LOG.error("InsertBranchBelowAction: unexpected error", ex)
                    notify("Unexpected error: ${ex.message}", NotificationType.ERROR)
                }
            }

            private fun notify(message: String, type: NotificationType) {
                NotificationGroupManager.getInstance()
                    .getNotificationGroup("StackWorktree")
                    .createNotification(message, type)
                    .notify(project)
            }
        }.queue()
    }
}
