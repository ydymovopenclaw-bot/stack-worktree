package com.github.ydymovopenclawbot.stackworktree.ui.actions

import com.github.ydymovopenclawbot.stackworktree.ui.UiLayer
import com.intellij.icons.AllIcons
import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.components.service

/**
 * Triggers a full stack state reload: reads [com.github.ydymovopenclawbot.stackworktree.state.StackState]
 * from git, recalculates ahead/behind counts via
 * [com.github.ydymovopenclawbot.stackworktree.git.AheadBehindCalculator], and re-renders the graph.
 *
 * **Two construction modes:**
 * 1. **Callback mode** (`onRefresh != null`) — used by [com.github.ydymovopenclawbot.stackworktree.ui.StackTreeToolbar]
 *    to also trigger the PR-status poller via a locally captured lambda.
 * 2. **Service mode** (`onRefresh == null`) — used when the action is instantiated by the
 *    IntelliJ Platform via its no-arg constructor (plugin.xml registration / keyboard shortcut).
 *    Delegates to [UiLayer.refresh], which publishes [com.github.ydymovopenclawbot.stackworktree.ui.STACK_STATE_TOPIC]
 *    so [com.github.ydymovopenclawbot.stackworktree.ui.StacksTabFactory] reloads the graph.
 *
 * @param onRefresh Optional callback; when `null` the action falls back to [UiLayer.refresh].
 */
class RefreshStacksAction(
    private val onRefresh: (() -> Unit)? = null,
) : AnAction(
    "Refresh",
    "Reload stack state and recalculate ahead/behind counts",
    AllIcons.Actions.Refresh,
) {
    override fun actionPerformed(e: AnActionEvent) {
        if (onRefresh != null) {
            onRefresh.invoke()
        } else {
            e.project?.service<UiLayer>()?.refresh()
        }
    }

    override fun update(e: AnActionEvent) {
        e.presentation.isEnabled = e.project != null
    }

    override fun getActionUpdateThread() = ActionUpdateThread.BGT
}
