package com.github.ydymovopenclawbot.stackworktree.ui

import com.intellij.notification.NotificationAction
import com.intellij.notification.NotificationGroupManager
import com.intellij.notification.NotificationType
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.Messages

/**
 * Centralized helper for surfacing StackTree events to the user as balloon notifications.
 *
 * All three severity levels share the same "StackTree" notification group registered in
 * `plugin.xml`.  When [detail] is supplied on [warn] or [error], a **"Show Details"** action
 * is added to the balloon so the user can inspect the full error message or stack trace
 * without needing to open `idea.log`.
 *
 * All methods are safe to call from any thread (IntelliJ's notification system dispatches
 * to the EDT internally).
 */
object StackTreeNotifier {

    private const val GROUP = "StackTree"

    /** Shows an INFORMATION balloon with [message]. */
    fun info(project: Project, message: String) =
        notify(project, message, NotificationType.INFORMATION, detail = null)

    /**
     * Shows a WARNING balloon with [message].
     *
     * @param detail Optional full text shown when the user clicks "Show Details".
     */
    fun warn(project: Project, message: String, detail: String? = null) =
        notify(project, message, NotificationType.WARNING, detail)

    /**
     * Shows an ERROR balloon with [message].
     *
     * @param detail Optional full text (e.g. stack trace) shown in "Show Details" dialog.
     */
    fun error(project: Project, message: String, detail: String? = null) =
        notify(project, message, NotificationType.ERROR, detail)

    // -------------------------------------------------------------------------

    private fun notify(
        project: Project,
        message: String,
        type: NotificationType,
        detail: String?,
    ) {
        val notification = NotificationGroupManager.getInstance()
            .getNotificationGroup(GROUP)
            .createNotification(message, type)

        if (!detail.isNullOrBlank()) {
            notification.addAction(
                NotificationAction.createSimple("Show Details") {
                    Messages.showInfoMessage(project, detail, "StackTree — Details")
                }
            )
        }

        notification.notify(project)
    }
}
