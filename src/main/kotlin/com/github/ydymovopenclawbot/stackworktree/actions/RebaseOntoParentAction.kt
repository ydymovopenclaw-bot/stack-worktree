package com.github.ydymovopenclawbot.stackworktree.actions

import com.github.ydymovopenclawbot.stackworktree.ops.OpsLayer
import com.github.ydymovopenclawbot.stackworktree.state.StateLayer
import com.github.ydymovopenclawbot.stackworktree.ui.UiLayer
import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.components.service
import com.intellij.openapi.diagnostic.logger
import com.intellij.openapi.progress.ProgressIndicator
import com.intellij.openapi.progress.Task

private val LOG = logger<RebaseOntoParentAction>()

/**
 * Context-menu action: **Rebase onto Parent**.
 *
 * Rebases the selected stack branch onto its tracked parent branch using
 * [git4idea.rebase.GitRebaser], which opens IntelliJ's three-pane merge dialog
 * automatically when conflicts are detected.
 *
 * After a successful rebase:
 * - The branch's `baseCommit` in the stack state is updated to the new merge-base.
 * - A success notification balloon is displayed.
 * - The Stacks graph panel is refreshed.
 *
 * On abort (user cancels the merge dialog), the repository is left in its
 * pre-rebase state and no state changes are made.
 *
 * The action is enabled only when a branch node **with a tracked parent** is
 * selected in the Stacks panel. Root/trunk branches (no parent in
 * [StateLayer.load().trackedBranches]) are disabled automatically.
 */
class RebaseOntoParentAction : AnAction() {

    override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.BGT

    override fun update(e: AnActionEvent) {
        val project = e.project
        val branch  = e.getData(StackDataKeys.SELECTED_BRANCH_NAME)
        if (project == null || branch == null) {
            e.presentation.isEnabledAndVisible = false
            return
        }
        // Disable for root/trunk branches that have no parent in the tracked tree.
        val hasParent = project.service<StateLayer>().load()
            .trackedBranches[branch]?.parentName != null
        e.presentation.isEnabledAndVisible = hasParent
    }

    override fun actionPerformed(e: AnActionEvent) {
        val project = e.project ?: return
        val branch  = e.getData(StackDataKeys.SELECTED_BRANCH_NAME) ?: return

        LOG.info("RebaseOntoParentAction: rebasing '$branch' onto parent")

        object : Task.Backgroundable(project, "Rebasing '$branch' onto parent…", true) {
            override fun run(indicator: ProgressIndicator) {
                indicator.isIndeterminate = true
                try {
                    project.service<OpsLayer>().rebaseOntoParent(branch)
                } catch (ex: Exception) {
                    LOG.warn("RebaseOntoParentAction: rebase of '$branch' failed: ${ex.message}", ex)
                    project.service<UiLayer>().notify("Rebase failed: ${ex.message}")
                }
            }
        }.queue()
    }
}
