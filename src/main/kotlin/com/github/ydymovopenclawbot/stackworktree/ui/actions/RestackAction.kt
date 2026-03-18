package com.github.ydymovopenclawbot.stackworktree.ui.actions

import com.github.ydymovopenclawbot.stackworktree.ops.OpsLayer
import com.github.ydymovopenclawbot.stackworktree.ops.OpsLayerImpl
import com.intellij.icons.AllIcons
import com.intellij.notification.NotificationGroupManager
import com.intellij.notification.NotificationType
import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.diagnostic.logger
import com.intellij.openapi.progress.ProgressIndicator
import com.intellij.openapi.progress.Task

private val LOG = logger<RestackAction>()

/**
 * Toolbar action: **Restack All**.
 *
 * Rebases every tracked branch bottom-to-top (BFS order from trunk) onto its parent via
 * [OpsLayer.restackAll]. Each branch is processed with `git rebase --onto <parent>
 * <oldParentTip> <branch>` so only commits unique to the branch are replayed.
 *
 * Conflicts at any branch open IntelliJ's three-pane merge dialog. Resolving and continuing
 * lets the cascade proceed; aborting stops the cascade and leaves already-rebased branches
 * in their new state (no rollback).
 *
 * Progress is shown as a determinate progress bar: "Restacking branch 2/4: feature-auth".
 */
class RestackAction : AnAction(
    "Restack",
    "Rebase all stacks bottom-to-top onto their parents",
    AllIcons.General.Reset,
) {

    override fun getActionUpdateThread() = ActionUpdateThread.BGT

    override fun update(e: AnActionEvent) {
        e.presentation.isEnabled = e.project != null
    }

    override fun actionPerformed(e: AnActionEvent) {
        val project = e.project ?: return

        object : Task.Backgroundable(project, "Restacking all branches\u2026", /* canBeCancelled */ false) {
            override fun run(indicator: ProgressIndicator) {
                indicator.isIndeterminate = false
                try {
                    val ops: OpsLayer = OpsLayerImpl.forProject(project)
                    ops.restackAll { current, total, branchName ->
                        indicator.text     = "Restacking branch $current/$total: $branchName"
                        indicator.fraction = current.toDouble() / total
                    }
                } catch (ex: Exception) {
                    LOG.warn("RestackAction: restack failed", ex)
                    NotificationGroupManager.getInstance()
                        .getNotificationGroup("StackWorktree")
                        .createNotification("Restack failed: ${ex.message}", NotificationType.ERROR)
                        .notify(project)
                }
            }
        }.queue()
    }
}
