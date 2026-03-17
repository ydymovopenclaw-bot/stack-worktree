package com.github.ydymovopenclawbot.stackworktree.actions

import com.github.ydymovopenclawbot.stackworktree.ops.OpsLayerImpl
import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.diagnostic.logger
import com.intellij.openapi.progress.ProgressIndicator
import com.intellij.openapi.progress.Task
import com.intellij.openapi.ui.Messages

private val LOG = logger<InsertBranchAboveAction>()

/**
 * Context-menu action: **Insert Branch Above**.
 *
 * Creates a new branch between the selected branch and its parent, then rebases
 * the selected branch (and its descendants) onto the new branch.
 *
 * ```
 * Before:  … → parent → selected → children…
 * After:   … → parent → <new> → selected → children…
 * ```
 *
 * The action is enabled only when a branch node is selected in the Stacks panel
 * ([StackDataKeys.SELECTED_BRANCH_NAME] is present in the data context).
 */
class InsertBranchAboveAction : AnAction() {

    override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.BGT

    override fun update(e: AnActionEvent) {
        e.presentation.isEnabledAndVisible =
            e.getData(StackDataKeys.SELECTED_BRANCH_NAME) != null
    }

    override fun actionPerformed(e: AnActionEvent) {
        val project = e.project ?: return
        val targetBranch = e.getData(StackDataKeys.SELECTED_BRANCH_NAME) ?: return

        val newBranchName = Messages.showInputDialog(
            project,
            "Enter a name for the new branch to insert above '$targetBranch':",
            "Insert Branch Above",
            null,
        )?.trim() ?: return  // user cancelled

        if (!isValidBranchName(newBranchName)) {
            Messages.showErrorDialog(
                project,
                "'$newBranchName' is not a valid git branch name.",
                "Invalid Branch Name",
            )
            return
        }

        LOG.info("InsertBranchAboveAction: inserting '$newBranchName' above '$targetBranch'")

        object : Task.Backgroundable(project, "Inserting branch above '$targetBranch'…", true) {
            override fun run(indicator: ProgressIndicator) {
                indicator.isIndeterminate = true
                OpsLayerImpl(project).insertBranchAbove(targetBranch, newBranchName)
            }
        }.queue()
    }
}
