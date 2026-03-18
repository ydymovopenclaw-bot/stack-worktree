package com.github.ydymovopenclawbot.stackworktree.ui.actions

import com.github.ydymovopenclawbot.stackworktree.ops.OpsLayer
import com.github.ydymovopenclawbot.stackworktree.ops.SubmitResult
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

private val LOG = logger<SubmitAction>()

/**
 * Toolbar action that submits the current stack for code review.
 *
 * Pushing and PR creation are performed on a background thread via
 * [Task.Backgroundable] so the EDT is never blocked.  The action is
 * enabled whenever the project has a loaded [OpsLayer] service.
 *
 * On success a balloon notification shows how many PRs were created/updated.
 * On failure the exception message is shown as an error balloon and logged.
 */
class SubmitAction : AnAction(
    "Submit Stack",
    "Push all stack branches and create or update pull requests",
    AllIcons.Actions.Upload,
) {

    override fun actionPerformed(e: AnActionEvent) {
        val project = e.project ?: return

        object : Task.Backgroundable(project, "Submitting stack…", false) {
            override fun run(indicator: ProgressIndicator) {
                indicator.isIndeterminate = true
                indicator.text = "Pushing branches and creating PRs…"
                try {
                    val ops    = project.service<OpsLayer>()
                    val result = ops.submitStack()
                    if (result is SubmitResult.NoState) {
                        notify(
                            project,
                            "Submit Stack: no stack state found. Track some branches first.",
                            NotificationType.WARNING,
                        )
                    }
                    // Success notification is shown by OpsLayerImpl.submitStack() via UiLayer.
                } catch (ex: Exception) {
                    LOG.warn("SubmitAction: submit failed", ex)
                    notify(
                        project,
                        "Submit Stack failed: ${ex.message ?: ex.javaClass.simpleName}",
                        NotificationType.ERROR,
                    )
                }
            }
        }.queue()
    }

    override fun update(e: AnActionEvent) {
        // Enabled whenever a project is open — OpsLayerImpl will report NoState gracefully
        // if no stack has been configured yet.
        e.presentation.isEnabled  = e.project != null
        e.presentation.text        = "Submit Stack"
        e.presentation.description = "Push all stack branches and create or update pull requests"
    }

    override fun getActionUpdateThread() = ActionUpdateThread.BGT

    // ── Helpers ───────────────────────────────────────────────────────────────

    private fun notify(
        project: com.intellij.openapi.project.Project,
        message: String,
        type: NotificationType,
    ) {
        NotificationGroupManager.getInstance()
            .getNotificationGroup("StackWorktree")
            .createNotification(message, type)
            .notify(project)
    }
}
