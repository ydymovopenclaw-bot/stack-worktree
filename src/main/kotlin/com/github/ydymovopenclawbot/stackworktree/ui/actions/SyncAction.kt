package com.github.ydymovopenclawbot.stackworktree.ui.actions

import com.github.ydymovopenclawbot.stackworktree.ops.OpsLayer
import com.github.ydymovopenclawbot.stackworktree.ops.OpsLayerImpl
import com.intellij.icons.AllIcons
import com.intellij.notification.NotificationGroupManager
import com.intellij.notification.NotificationType
import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.components.service
import com.intellij.openapi.diagnostic.logger
import com.intellij.openapi.progress.ProgressIndicator
import com.intellij.openapi.progress.Task

private val LOG = logger<SyncAction>()

/**
 * Toolbar action: **Sync**.
 *
 * Fetches the remote, removes merged branches from the stack (re-parenting their
 * children), prunes linked worktrees for merged branches, and recalculates
 * ahead/behind status for every remaining tracked branch.
 *
 * Runs on a background thread via [Task.Backgroundable] so the EDT is never blocked.
 * A summary notification balloon is shown by [OpsLayer.syncAll] on completion, e.g.:
 * `"Synced: 2 merged, 3 need rebase"`.
 */
class SyncAction : AnAction(
    "Sync",
    "Fetch remote and sync stacks: detect merged branches, prune worktrees, and refresh ahead/behind counts",
    AllIcons.Actions.Refresh,
) {

    override fun getActionUpdateThread() = ActionUpdateThread.BGT

    override fun update(e: AnActionEvent) {
        e.presentation.isEnabled = e.project != null
    }

    override fun actionPerformed(e: AnActionEvent) {
        val project = e.project ?: return

        object : Task.Backgroundable(project, "Syncing stacks\u2026", /* canBeCancelled */ true) {
            override fun run(indicator: ProgressIndicator) {
                indicator.isIndeterminate = true
                indicator.text = "Fetching remote\u2026"

                try {
                    val ops: OpsLayer = OpsLayerImpl.forProject(project)
                    val result = ops.syncAll()
                    indicator.checkCanceled()

                    // Update indicator text with a brief summary while the progress window
                    // is still visible; the notification balloon carries the full message.
                    indicator.text = result.summaryMessage()
                } catch (ex: Exception) {
                    LOG.warn("SyncAction: sync failed", ex)
                    NotificationGroupManager.getInstance()
                        .getNotificationGroup("StackWorktree")
                        .createNotification("Sync failed: ${ex.message}", NotificationType.ERROR)
                        .notify(project)
                }
            }
        }.queue()
    }
}
