package com.github.ydymovopenclawbot.stackworktree.ui

import com.intellij.openapi.components.Service
import com.intellij.openapi.diagnostic.logger
import com.intellij.openapi.project.Project

private val LOG = logger<UiLayerImpl>()

/**
 * Production [UiLayer] implementation.
 *
 * - [refresh] broadcasts [STACK_STATE_TOPIC] so all UI subscribers rebuild.
 * - [notify] shows a non-blocking INFO balloon via [StackTreeNotifier].
 * - [notifyError] shows an ERROR balloon with an optional "Show Details" action.
 */
@Service(Service.Level.PROJECT)
class UiLayerImpl(private val project: Project) : UiLayer {

    override fun refresh() {
        LOG.debug("refresh: broadcasting STACK_STATE_TOPIC")
        project.messageBus.syncPublisher(STACK_STATE_TOPIC).stateChanged()
    }

    override fun notify(message: String) {
        LOG.debug("notify: $message")
        StackTreeNotifier.info(project, message)
    }

    override fun notifyError(message: String, detail: String?) {
        LOG.warn("notifyError: $message${if (detail != null) " [detail available]" else ""}")
        StackTreeNotifier.error(project, message, detail)
    }
}
