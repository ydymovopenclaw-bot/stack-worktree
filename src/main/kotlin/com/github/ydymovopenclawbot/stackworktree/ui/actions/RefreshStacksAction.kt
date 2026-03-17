package com.github.ydymovopenclawbot.stackworktree.ui.actions

import com.intellij.icons.AllIcons
import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent

/**
 * Triggers a full stack state reload: reads [com.github.ydymovopenclawbot.stackworktree.state.StackState]
 * from git, recalculates ahead/behind counts via
 * [com.github.ydymovopenclawbot.stackworktree.git.AheadBehindCalculator], and re-renders the graph.
 *
 * @param onRefresh Callback that executes the refresh pipeline (provided by [com.github.ydymovopenclawbot.stackworktree.ui.StacksTabFactory]).
 */
class RefreshStacksAction(
    private val onRefresh: () -> Unit,
) : AnAction(
    "Refresh",
    "Reload stack state and recalculate ahead/behind counts",
    AllIcons.Actions.Refresh,
) {
    override fun actionPerformed(e: AnActionEvent) = onRefresh()
    override fun getActionUpdateThread() = ActionUpdateThread.BGT
}
