package com.github.ydymovopenclawbot.stackworktree.ui

import com.intellij.notification.NotificationGroupManager
import com.intellij.notification.NotificationType
import com.intellij.openapi.components.Service
import com.intellij.openapi.project.Project

/**
 * Production [UiLayer] implementation.
 *
 * - [refresh] broadcasts [STACK_STATE_TOPIC] so all UI subscribers rebuild.
 * - [notify] shows a non-blocking balloon notification.
 */
@Service(Service.Level.PROJECT)
class UiLayerImpl(private val project: Project) : UiLayer {

    override fun refresh() {
        project.messageBus.syncPublisher(STACK_STATE_TOPIC).stateChanged()
    }

    override fun notify(message: String) {
        NotificationGroupManager.getInstance()
            .getNotificationGroup("Stack Worktree Notifications")
            .createNotification(message, NotificationType.INFORMATION)
            .notify(project)
    }
}
