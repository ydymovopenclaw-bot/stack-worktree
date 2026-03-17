package com.github.ydymovopenclawbot.stackworktree.actions

import com.github.ydymovopenclawbot.stackworktree.git.BranchOperationException
import com.github.ydymovopenclawbot.stackworktree.ops.OpsLayer
import com.github.ydymovopenclawbot.stackworktree.ui.AddBranchToStackDialog
import com.github.ydymovopenclawbot.stackworktree.ui.stackgraph.StackNodeData
import com.intellij.notification.NotificationGroupManager
import com.intellij.notification.NotificationType
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.diagnostic.logger
import com.intellij.openapi.project.Project
import javax.swing.SwingUtilities

private val LOG = logger<AddBranchToStackAction>()

/**
 * Orchestrates the "Add Branch to Stack" user flow:
 *
 * 1. Shows [AddBranchToStackDialog].
 * 2. On confirmation, delegates all git + state operations to [opsLayer] on a pooled thread.
 * 3. Invokes [onSuccess] on the EDT with the new [StackNodeData] so the caller can
 *    trigger a graph refresh.
 *
 * Constructed programmatically (not via plugin.xml) so it can accept context
 * objects rather than relying on DataContext wiring.
 *
 * @param project    The current project.
 * @param parentNode The node the new branch will be stacked on.
 * @param opsLayer   Orchestration layer that performs the git and state operations.
 * @param onSuccess  Called on the EDT after the branch is created successfully.
 */
class AddBranchToStackAction(
    private val project: Project,
    private val parentNode: StackNodeData,
    private val opsLayer: OpsLayer,
    private val onSuccess: (newNode: StackNodeData) -> Unit,
) {

    fun perform() {
        val dialog = AddBranchToStackDialog(project, parentNode)
        if (!dialog.showAndGet()) return   // user cancelled

        val newBranch     = dialog.getBranchName()
        val commitMessage = dialog.getCommitMessage()
        val useWorktree   = dialog.isCreateWorktree()
        val worktreePath  = if (useWorktree) defaultWorktreePath(newBranch) else null

        ApplicationManager.getApplication().executeOnPooledThread {
            try {
                val newNode = opsLayer.addBranchToStack(
                    parentNode     = parentNode,
                    newBranch      = newBranch,
                    commitMessage  = commitMessage,
                    createWorktree = useWorktree,
                    worktreePath   = worktreePath,
                )
                SwingUtilities.invokeLater {
                    notify("Branch '${newNode.branchName}' added to stack.", NotificationType.INFORMATION)
                    onSuccess(newNode)
                }
            } catch (ex: BranchOperationException) {
                LOG.warn("Add branch to stack failed", ex)
                SwingUtilities.invokeLater {
                    notify("Failed to create branch: ${ex.message}", NotificationType.ERROR)
                }
            } catch (ex: Exception) {
                LOG.error("Unexpected error in AddBranchToStackAction", ex)
                SwingUtilities.invokeLater {
                    notify("Unexpected error: ${ex.message}", NotificationType.ERROR)
                }
            }
        }
    }

    // ── Private helpers ───────────────────────────────────────────────────────

    /**
     * Resolves the default worktree path as a sibling directory of the project root.
     *
     * @throws BranchOperationException if [Project.basePath] is null (e.g. default project).
     */
    private fun defaultWorktreePath(branch: String): String {
        val base = project.basePath
            ?: throw BranchOperationException(
                "Cannot determine worktree path: project '${project.name}' has no base directory"
            )
        return "$base/../worktrees/$branch"
    }

    private fun notify(message: String, type: NotificationType) {
        NotificationGroupManager.getInstance()
            .getNotificationGroup("StackWorktree")
            .createNotification(message, type)
            .notify(project)
    }
}
