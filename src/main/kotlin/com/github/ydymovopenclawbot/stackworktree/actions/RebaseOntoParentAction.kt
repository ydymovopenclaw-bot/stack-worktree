package com.github.ydymovopenclawbot.stackworktree.actions

import com.github.ydymovopenclawbot.stackworktree.ops.OpsLayer
import com.github.ydymovopenclawbot.stackworktree.ops.OpsLayerImpl
import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
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
 * The action is enabled only when a branch node is selected in the Stacks panel
 * ([StackDataKeys.SELECTED_BRANCH_NAME] is present in the data context).
 */
class RebaseOntoParentAction : AnAction() {

    override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.BGT

    override fun update(e: AnActionEvent) {
        e.presentation.isEnabledAndVisible =
            e.getData(StackDataKeys.SELECTED_BRANCH_NAME) != null
    }

    override fun actionPerformed(e: AnActionEvent) {
        val project = e.project ?: return
        val branch  = e.getData(StackDataKeys.SELECTED_BRANCH_NAME) ?: return

        LOG.info("RebaseOntoParentAction: rebasing '$branch' onto parent")

        object : Task.Backgroundable(project, "Rebasing '$branch' onto parent…", true) {
            override fun run(indicator: ProgressIndicator) {
                indicator.isIndeterminate = true
                val ops: OpsLayer = OpsLayerImpl.forProject(project)
                ops.rebaseOntoParent(branch)
            }
        }.queue()
    }
}
