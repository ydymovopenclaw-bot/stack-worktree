package com.github.ydymovopenclawbot.stackworktree.actions

import com.github.ydymovopenclawbot.stackworktree.git.BranchOperationException
import com.github.ydymovopenclawbot.stackworktree.git.GitLayerImpl
import com.github.ydymovopenclawbot.stackworktree.state.stackStateService
import com.github.ydymovopenclawbot.stackworktree.ui.AddBranchToStackDialog
import com.github.ydymovopenclawbot.stackworktree.ui.stackgraph.StackNodeData
import com.intellij.notification.NotificationGroupManager
import com.intellij.notification.NotificationType
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.diagnostic.logger
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.LocalFileSystem
import git4idea.GitUtil
import javax.swing.SwingUtilities

private val LOG = logger<AddBranchToStackAction>()

/**
 * Orchestrates the "Add Branch to Stack" user flow:
 *
 * 1. Shows [AddBranchToStackDialog].
 * 2. On confirmation, runs the git operation on a pooled thread.
 * 3. Records the parent relationship in [StackStateService][com.github.ydymovopenclawbot.stackworktree.state.StackStateService].
 * 4. Invokes [onSuccess] on the EDT with the new [StackNodeData] so the graph
 *    can be refreshed immediately.
 *
 * Constructed programmatically (not via plugin.xml) so it can accept context
 * objects rather than relying on DataContext wiring.
 *
 * @param project    The current project.
 * @param parentNode The node the new branch will be stacked on.
 * @param onSuccess  Called on the EDT after the branch is created successfully.
 */
class AddBranchToStackAction(
    private val project: Project,
    private val parentNode: StackNodeData,
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
                val newNode = execute(newBranch, commitMessage, useWorktree, worktreePath)
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

    private fun execute(
        newBranch: String,
        commitMessage: String?,
        useWorktree: Boolean,
        worktreePath: String?,
    ): StackNodeData {
        val gitLayer = buildGitLayer()

        if (useWorktree) {
            requireNotNull(worktreePath) { "worktreePath must be non-null when createWorktree=true" }
            gitLayer.worktreeAdd(worktreePath, newBranch)
        } else {
            gitLayer.checkoutNewBranch(newBranch)
        }

        if (!commitMessage.isNullOrBlank()) {
            gitLayer.stageAll()
            gitLayer.commit(commitMessage)
        }

        // Record the parent relationship atomically.
        project.stackStateService().recordBranch(
            branch      = newBranch,
            parentBranch = parentNode.branchName,
            worktreePath = worktreePath,
        )

        return StackNodeData(
            id         = newBranch,
            branchName = newBranch,
            parentId   = parentNode.id,
        )
    }

    private fun buildGitLayer(): GitLayerImpl {
        val repo = GitUtil.getRepositoryManager(project).repositories.firstOrNull()
            ?: error("No git repository found in project '${project.name}'")
        val vf = LocalFileSystem.getInstance().refreshAndFindFileByIoFile(repo.root.toNioPath().toFile())
            ?: error("VirtualFile not found for git root ${repo.root}")
        return GitLayerImpl(project, vf)
    }

    private fun defaultWorktreePath(branch: String): String =
        "${project.basePath}/../worktrees/$branch"

    private fun notify(message: String, type: NotificationType) {
        NotificationGroupManager.getInstance()
            .getNotificationGroup("StackWorktree")
            .createNotification(message, type)
            .notify(project)
    }
}
