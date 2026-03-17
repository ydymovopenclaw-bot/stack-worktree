package com.github.ydymovopenclawbot.stackworktree.ui

import com.github.ydymovopenclawbot.stackworktree.git.BranchDetailService
import com.github.ydymovopenclawbot.stackworktree.ui.stackgraph.StackGraphData
import com.github.ydymovopenclawbot.stackworktree.ui.stackgraph.StackGraphPanel
import com.github.ydymovopenclawbot.stackworktree.ui.stackgraph.StackNodeData
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.diagnostic.logger
import com.intellij.openapi.project.ProjectManager
import com.intellij.openapi.vcs.changes.ui.ChangesViewContentProvider
import com.intellij.ui.JBSplitter
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBPanel
import com.intellij.ui.components.JBScrollPane
import java.awt.BorderLayout
import javax.swing.JComponent
import javax.swing.ScrollPaneConstants
import javax.swing.SwingUtilities

private val LOG = logger<StacksTabFactory>()

/**
 * Content provider for the "Stacks" tab in the Git tool window.
 *
 * Renders a [JBSplitter] with:
 * - **Left** – [StackGraphPanel]: the stack graph that emits node-selection events.
 * - **Right** – [BranchDetailPanel]: shows metadata for the selected branch.
 *
 * Selecting a node in the graph triggers [BranchDetailService.loadNode] on a
 * pooled thread, then updates the detail panel on the EDT.
 */
class StacksTabFactory : ChangesViewContentProvider {

    override fun initContent(): JComponent {
        val project = ProjectManager.getInstance().openProjects.firstOrNull()
            ?: return JBPanel<JBPanel<*>>(BorderLayout()).apply {
                add(JBLabel("No project open"), BorderLayout.CENTER)
            }

        val service     = BranchDetailService(project)
        val detailPanel = BranchDetailPanel()

        val graphPanel = StackGraphPanel().apply {
            onNodeSelected = { node ->
                LOG.debug("Selected node: ${node.branchName}")
                ApplicationManager.getApplication().executeOnPooledThread {
                    val worktreePath = service.worktreesByBranch()[node.branchName]
                    val stackNode    = service.loadNode(node.branchName, worktreePath)
                    SwingUtilities.invokeLater {
                        if (stackNode != null) detailPanel.showNode(stackNode)
                        else detailPanel.clearSelection()
                    }
                }
            }
            onNodeNavigated = { node -> LOG.info("Navigate requested for: ${node.branchName}") }

            // Populate graph with current local branches.
            val nodes = service.listBranches().map { branch ->
                StackNodeData(id = branch, branchName = branch, parentId = null)
            }
            updateGraph(StackGraphData(nodes))
        }

        return JBSplitter(/* vertical = */ false, /* proportion = */ 0.35f).apply {
            firstComponent = JBScrollPane(
                graphPanel,
                ScrollPaneConstants.VERTICAL_SCROLLBAR_AS_NEEDED,
                ScrollPaneConstants.HORIZONTAL_SCROLLBAR_AS_NEEDED,
            )
            secondComponent = detailPanel
            dividerWidth    = 3
        }
    }

    override fun disposeContent() = Unit
}
