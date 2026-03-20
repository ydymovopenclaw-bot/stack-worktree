package com.github.ydymovopenclawbot.stackworktree.actions

import com.github.ydymovopenclawbot.stackworktree.ops.OpsLayer
import com.github.ydymovopenclawbot.stackworktree.state.StateLayer
import com.intellij.notification.NotificationGroupManager
import com.intellij.notification.NotificationType
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.components.service
import com.intellij.openapi.progress.ProgressIndicator
import com.intellij.openapi.progress.Task
import com.intellij.openapi.ui.DialogWrapper
import com.intellij.openapi.ui.Messages
import javax.swing.BoxLayout
import javax.swing.JCheckBox
import javax.swing.JComponent
import javax.swing.JLabel
import javax.swing.JPanel

/**
 * Action that removes the entire stack: untracks all branches, optionally deletes
 * git branches and prunes linked worktrees.
 *
 * Shows a confirmation dialog with checkboxes for "Delete git branches" and
 * "Remove linked worktrees" (both unchecked by default). On confirmation, dispatches
 * the operation to a background thread.
 */
class RemoveStackAction : AnAction() {

    override fun actionPerformed(e: AnActionEvent) {
        val project = e.project ?: return
        val stateLayer = project.service<StateLayer>()
        val currentState = stateLayer.load()
        val branchCount = currentState.trackedBranches.size
        val trunkBranch = currentState.trunkBranch ?: "main"

        if (branchCount == 0) return

        val dialog = RemoveStackDialog(trunkBranch, branchCount)
        if (!dialog.showAndGet()) return

        val deleteBranches = dialog.deleteBranches
        val removeWorktrees = dialog.removeWorktrees

        object : Task.Backgroundable(project, "Removing stack\u2026", false) {
            override fun run(indicator: ProgressIndicator) {
                indicator.isIndeterminate = true
                val opsLayer = project.service<OpsLayer>()
                val result = opsLayer.removeStack(
                    stackRoot = trunkBranch,
                    deleteBranches = deleteBranches,
                    removeWorktrees = removeWorktrees,
                )
                NotificationGroupManager.getInstance()
                    .getNotificationGroup("StackWorktree")
                    .createNotification(result.summary(), NotificationType.INFORMATION)
                    .notify(project)
            }
        }.queue()
    }

    override fun update(e: AnActionEvent) {
        val project = e.project
        if (project == null) {
            e.presentation.isEnabled = false
            return
        }
        val stateLayer = project.service<StateLayer>()
        e.presentation.isEnabled = stateLayer.load().trackedBranches.isNotEmpty()
    }

    /**
     * Confirmation dialog for the Remove Stack operation.
     *
     * Shows the branch count and two checkboxes (both unchecked by default):
     * - "Delete git branches"
     * - "Remove linked worktrees"
     */
    private class RemoveStackDialog(private val trunkBranch: String, private val branchCount: Int) : DialogWrapper(true) {
        var deleteBranches = false
            private set
        var removeWorktrees = false
            private set

        private val deleteBranchesCheckbox = JCheckBox("Delete git branches", false)
        private val removeWorktreesCheckbox = JCheckBox("Remove linked worktrees", false)

        init {
            title = "Remove Stack"
            setOKButtonText("Remove")
            init()
        }

        override fun createCenterPanel(): JComponent {
            val panel = JPanel()
            panel.layout = BoxLayout(panel, BoxLayout.Y_AXIS)
            panel.add(JLabel("Remove stack '$trunkBranch' with $branchCount tracked branch${if (branchCount == 1) "" else "es"}?"))
            panel.add(javax.swing.Box.createVerticalStrut(8))
            panel.add(deleteBranchesCheckbox)
            panel.add(removeWorktreesCheckbox)
            return panel
        }

        override fun doOKAction() {
            deleteBranches = deleteBranchesCheckbox.isSelected
            removeWorktrees = removeWorktreesCheckbox.isSelected
            super.doOKAction()
        }
    }
}
